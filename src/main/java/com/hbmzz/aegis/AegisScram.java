/*
 * HBMZZ - Aegis DFC telemetry terminal.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hbmzz.aegis;

import com.hbmzz.HBMZZ;
import com.hbmzz.dfc.DFCReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Command-driven emergency shutdown (SCRAM) for a DFC core.
 *
 * <p>Armed by the {@code /aegis scram} command. One session per core; every
 * server second it writes a temperature 2000 C lower than the reading. If the
 * reading has instead climbed since the last write — the core is outrunning
 * the cooldown — the session slams it straight to 0 C. This is deliberately a
 * blunt instrument: it writes another mod's heat field through
 * {@link DFCReader#writeTemperature}, the same route the hacker tablet uses,
 * and is meant as an emergency brake, not a replacement for the reactor's own
 * control rods.</p>
 *
 * <p>Sessions are keyed by dimension + position, paused while the chunk is
 * unloaded and dropped when the core disappears or the server stops. Writes
 * always run on the server tick thread.</p>
 */
public final class AegisScram {

    /** Degrees removed from the core each server second. */
    public static final int COOL_PER_SECOND = 2000;

    private static final Map<String, Session> SESSIONS =
            new LinkedHashMap<String, Session>();

    public AegisScram() {
    }

    /** Arms (or re-arms) a shutdown for the core at {@code core}. */
    public static String arm(World world, BlockPos core) {
        TileEntity te = world.getTileEntity(core);
        if (!DFCReader.isCore(te)) {
            return "该坐标不是 DFC 核心。";
        }
        Session session = sessions().get(key(world, core));
        if (session == null) {
            session = new Session(world.provider.getDimension(), core);
            sessions().put(key(world, core), session);
        }
        session.lastTarget = Double.NaN; // no trend baseline on a fresh arm
        session.slammed = false;
        double temp = DFCReader.temperature(te);
        return String.format(Locale.ROOT, "自动停堆已启动 · 当前 %.0f°C · 每秒降 %d°C，失控即归零",
                temp, COOL_PER_SECOND);
    }

    /** Disarms every session in the given world. */
    public static String disarm(World world) {
        int removed = 0;
        synchronized (SESSIONS) {
            java.util.Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Session> e = it.next();
                if (e.getValue().dimension == world.provider.getDimension()) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed > 0 ? "自动停堆已解除（" + removed + " 台）。" : "没有正在运行的自动停堆。";
    }

    /** One-line status of the sessions in the given world. */
    public static String status(World world) {
        if (sessions().isEmpty()) {
            return "没有正在运行的自动停堆。";
        }
        StringBuilder sb = new StringBuilder("自动停堆状态:");
        for (Session s : sessions().values()) {
            if (s.dimension != world.provider.getDimension()) {
                continue;
            }
            // Prefer the caller's world; a cross-dimension session just reports
            // its last-known state rather than reaching into another world.
            World w = s.dimension == world.provider.getDimension() ? world : null;
            TileEntity te = w != null && w.isBlockLoaded(s.core)
                    ? w.getTileEntity(s.core) : null;
            double temp = te != null ? DFCReader.temperature(te) : Double.NaN;
            sb.append(" [").append(s.core.getX()).append(',').append(s.core.getY())
                    .append(',').append(s.core.getZ()).append("] ");
            if (Double.isNaN(temp)) {
                sb.append("核心未加载");
            } else {
                sb.append(String.format(Locale.ROOT, "%.0f°C", temp));
                if (s.slammed) {
                    sb.append("(已强制归零)");
                }
            }
        }
        return sb.toString();
    }

    /** Drops all sessions. Called when the server stops. */
    public static void clearAll() {
        synchronized (SESSIONS) {
            SESSIONS.clear();
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || sessions().isEmpty()) {
            return;
        }
        // ServerTickEvent carries only a phase in 1.12.2; reach the server
        // through the common handler instead of a non-existent event.server.
        MinecraftServer server = net.minecraftforge.fml.common.FMLCommonHandler
                .instance().getMinecraftServerInstance();
        if (server == null || server.getTickCounter() % 20L != 0L) {
            return; // once per server second
        }
        synchronized (SESSIONS) {
            java.util.Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Session> e = it.next();
                Session s = e.getValue();
                World world = server.getWorld(s.dimension);
                if (world == null || !world.isBlockLoaded(s.core)) {
                    continue; // pause while the chunk is away
                }
                TileEntity te = world.getTileEntity(s.core);
                if (!DFCReader.isCore(te)) {
                    it.remove(); // core gone — nothing left to cool
                    continue;
                }
                double temp = DFCReader.temperature(te);
                double target;
                if (!Double.isNaN(s.lastTarget) && temp > s.lastTarget + 0.5D) {
                    // The core climbed after we told it to cool: runaway. Slam 0.
                    target = 0.0D;
                    if (!s.slammed) {
                        s.slammed = true;
                        HBMZZ.logger.info("Aegis SCRAM: runaway at " + s.core
                                + " (" + String.format(Locale.ROOT, "%.0f", temp)
                                + "C) — forced to 0C");
                    }
                } else {
                    // Normal ramp: step down 2000 C, never below zero.
                    target = Math.max(0.0D, temp - COOL_PER_SECOND);
                    s.slammed = false;
                }
                if (DFCReader.writeTemperature(te, target)) {
                    s.lastTarget = target;
                } else {
                    HBMZZ.logger.warn("Aegis SCRAM: cannot write temperature at " + s.core);
                    it.remove();
                }
            }
        }
    }

    private static Map<String, Session> sessions() {
        return SESSIONS;
    }

    private static String key(World world, BlockPos pos) {
        return world.provider.getDimension() + ":" + pos.toLong();
    }

    private static final class Session {
        final int dimension;
        final BlockPos core;
        double lastTarget = Double.NaN;
        boolean slammed;

        Session(int dimension, BlockPos core) {
            this.dimension = dimension;
            this.core = core;
        }
    }
}

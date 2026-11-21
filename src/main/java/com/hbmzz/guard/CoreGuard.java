/*
 * HBMZZ - independent DFC core thermal guard.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */

package com.hbmzz.guard;

import com.hbmzz.HBMZZ;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Standalone emergency cooling and melt-down forecast for DFC cores.
 *
 * <p>This class does not touch the Aegis telemetry package at all; it reads and
 * writes the core heat through {@link CoreThermal} only. Two things happen on a
 * one-second cadence:</p>
 * <ol>
 *   <li><b>Shutdown sessions</b> - a core that has been SCRAM'd is cooled every
 *       second: above 10000 C by 5000, otherwise by 2000, never below zero,
 *       until it reaches zero.</li>
 *   <li><b>Thermal samples</b> - observed cores are sampled once a second to
 *       derive a degrees-per-second slope. The forecast is the time left until
 *       the core reaches its melting point at that slope.</li>
 * </ol>
 *
 * <p>All work runs on the server tick thread. Sessions are keyed by dimension
 * and position, pause while the chunk is unloaded, and are dropped when the
 * core disappears or the server stops.</p>
 */
public final class CoreGuard {

    /** Degrees removed per second while the core is above 10000 C. */
    public static final int COOL_HOT = 5000;
    /** Degrees removed per second while the core is at or below 10000 C. */
    public static final int COOL_NORMAL = 2000;
    /** Temperature above which the faster cooling rate applies. */
    public static final double HOT_THRESHOLD = 10000.0D;

    private static final Map<String, Session> SESSIONS =
            new LinkedHashMap<String, Session>();
    private static final Map<String, Sample> SAMPLES =
            new LinkedHashMap<String, Sample>();

    public CoreGuard() {
    }

    // ------------------------------------------------------------- commands

    /** Arms (or re-arms) continuous cooling on the given core. */
    public static String arm(World world, BlockPos corePos) {
        TileEntity te = world == null ? null : world.getTileEntity(corePos);
        if (!CoreThermal.isCore(te)) {
            return "该坐标不是 DFC 核心。";
        }
        String k = key(world, corePos);
        synchronized (SESSIONS) {
            Session s = SESSIONS.get(k);
            if (s == null) {
                s = new Session(world.provider.getDimension(), corePos);
                SESSIONS.put(k, s);
            }
            s.steps = 0;
        }
        observe(world, corePos);
        double temp = CoreThermal.temperature(te);
        return String.format(Locale.ROOT, "自动停堆已启动 · 当前 %.0f°C", temp)
                + " · 每秒降 " + COOL_NORMAL + "°C（超 " + (int) HOT_THRESHOLD
                + "°C 每秒降 " + COOL_HOT + "°C）· 直到 0";
    }

    /** True when the given core has a live automatic-shutdown session. */
    public static boolean isArmed(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return false;
        }
        String k = key(world, corePos);
        synchronized (SESSIONS) {
            return SESSIONS.containsKey(k);
        }
    }

    /** Disarms every session in the given world. */
    public static String disarm(World world) {
        int removed = 0;
        final int dim = world.provider.getDimension();
        synchronized (SESSIONS) {
            Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                if (it.next().getValue().dimension == dim) {
                    it.remove();
                    removed++;
                }
            }
        }
        return removed > 0 ? "自动停堆已解除（" + removed + " 台）。" : "没有正在运行的自动停堆。";
    }

    /** One-line status of active sessions in the given world. */
    public static String status(World world) {
        final int dim = world.provider.getDimension();
        StringBuilder sb = new StringBuilder("自动停堆状态:");
        synchronized (SESSIONS) {
            int count = 0;
            for (Session s : SESSIONS.values()) {
                if (s.dimension != dim) {
                    continue;
                }
                count++;
                TileEntity te = world.isBlockLoaded(s.core) ? world.getTileEntity(s.core) : null;
                double temp = te != null ? CoreThermal.temperature(te) : Double.NaN;
                sb.append(" [").append(s.core.getX()).append(',').append(s.core.getY())
                        .append(',').append(s.core.getZ()).append("] ");
                if (Double.isNaN(temp)) {
                    sb.append("核心未加载");
                } else {
                    sb.append(String.format(Locale.ROOT, "%.0f°C", temp))
                            .append(" · ").append(s.steps).append(" 秒");
                }
            }
            if (count == 0) {
                return "没有正在运行的自动停堆。";
            }
        }
        return sb.toString();
    }

    /** Adds a core to the once-per-second observation table for forecasting. */
    public static void observe(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return;
        }
        String k = key(world, corePos);
        synchronized (SAMPLES) {
            if (!SAMPLES.containsKey(k)) {
                Sample s = new Sample(world.provider.getDimension(), corePos);
                TileEntity te = world.getTileEntity(corePos);
                if (!Double.isNaN(CoreThermal.temperature(te))) {
                    s.lastTemp = CoreThermal.temperature(te);
                    s.hasPrev = true;
                }
                SAMPLES.put(k, s);
            }
        }
    }

    /**
     * Forecast for a core: seconds until it reaches its melting point at the
     * measured heating slope. A negative slope means it is cooling (forecast
     * "stable"). Returns a ready-to-send Chinese line.
     */
    public static String forecast(World world, BlockPos corePos) {
        TileEntity te = world == null ? null : world.getTileEntity(corePos);
        if (!CoreThermal.isCore(te)) {
            return "该坐标不是 DFC 核心。";
        }
        observe(world, corePos);
        double temp = CoreThermal.temperature(te);
        double melt = CoreThermal.meltingPoint(te);
        String k = key(world, corePos);
        Double slope = null;
        synchronized (SAMPLES) {
            Sample s = SAMPLES.get(k);
            if (s != null) {
                slope = s.slope;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "核心 %d,%d,%d · 温度 %.0f°C · 熔点 %.0f°C",
                corePos.getX(), corePos.getY(), corePos.getZ(), temp, melt));
        if (slope == null) {
            sb.append(" · 熔毁预测：采样中，稍后再查");
        } else if (slope <= 0.0D) {
            sb.append(" · 熔毁预测：温度未上升，暂无风险");
        } else {
            double seconds = Math.max(0.0D, (melt - temp) / slope);
            if (temp >= melt) {
                sb.append(" · 熔毁预测：已到熔点，临界！");
            } else {
                sb.append(String.format(Locale.ROOT,
                        " · 熔毁预测：约 %.0f 秒后达到熔点（+%.1f °C/秒）",
                        seconds, slope));
            }
        }
        return sb.toString();
    }

    public static void clearAll() {
        synchronized (SESSIONS) {
            SESSIONS.clear();
        }
        synchronized (SAMPLES) {
            SAMPLES.clear();
        }
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || server.getTickCounter() % 20L != 0L) {
            return; // once per server second
        }

        // Sample observed cores first, so a forecast reflects the last second.
        synchronized (SAMPLES) {
            Iterator<Map.Entry<String, Sample>> it = SAMPLES.entrySet().iterator();
            while (it.hasNext()) {
                Sample s = it.next().getValue();
                World w = server.getWorld(s.dimension);
                if (w == null || !w.isBlockLoaded(s.core)) {
                    continue;
                }
                TileEntity te = w.getTileEntity(s.core);
                double temp = CoreThermal.temperature(te);
                if (Double.isNaN(temp)) {
                    it.remove();
                    continue;
                }
                if (s.hasPrev) {
                    s.slope = temp - s.lastTemp; // degrees per second
                }
                s.lastTemp = temp;
                s.hasPrev = true;
            }
        }

        // Cool every armed core by the appropriate per-second step.
        synchronized (SESSIONS) {
            Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Session s = it.next().getValue();
                World w = server.getWorld(s.dimension);
                if (w == null || !w.isBlockLoaded(s.core)) {
                    continue; // pause while the chunk is away
                }
                TileEntity te = w.getTileEntity(s.core);
                if (!CoreThermal.isCore(te)) {
                    it.remove(); // core gone - nothing left to cool
                    continue;
                }
                double temp = CoreThermal.temperature(te);
                if (Double.isNaN(temp) || temp <= 0.0D) {
                    // Reached zero (or unreadable): hold at zero and stop.
                    CoreThermal.writeTemperature(te, 0.0D);
                    s.steps++;
                    continue;
                }
                int step = temp > HOT_THRESHOLD ? COOL_HOT : COOL_NORMAL;
                double target = Math.max(0.0D, temp - step);
                if (!CoreThermal.writeTemperature(te, target)) {
                    HBMZZ.logger.warn("CoreGuard: cannot write temperature at " + s.core);
                    it.remove();
                    continue;
                }
                s.steps++;
                if (s.steps % 10 == 0) {
                    HBMZZ.logger.info("CoreGuard: cooling " + s.core + " to "
                            + String.format(Locale.ROOT, "%.0f", target) + "C (step " + step + ")");
                }
            }
        }
    }

    /** Broadcasts a forecast line to every player in the world (used by the command). */
    public static void tellAll(World world, String text) {
        if (world == null) {
            return;
        }
        for (EntityPlayer p : world.playerEntities) {
            p.sendMessage(new TextComponentString(text));
        }
    }

    private static String key(World world, BlockPos pos) {
        return world.provider.getDimension() + ":" + pos.toLong();
    }

    private static final class Session {
        final int dimension;
        final BlockPos core;
        int steps;

        Session(int dimension, BlockPos core) {
            this.dimension = dimension;
            this.core = core;
        }
    }

    private static final class Sample {
        final int dimension;
        final BlockPos core;
        double lastTemp;
        double slope;
        boolean hasPrev;

        Sample(int dimension, BlockPos core) {
            this.dimension = dimension;
            this.core = core;
        }
    }
}

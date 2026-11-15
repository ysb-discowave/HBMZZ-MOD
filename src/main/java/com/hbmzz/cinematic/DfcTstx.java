/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
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
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.cinematic;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.dfc.DFCReader;
import com.hbmzz.network.CinematicMessage;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * "DFC-TSTX": a forced three-second orbit the moment a core enters collapse.
 *
 * <h3>The rule</h3>
 * <p>Registered exactly as {@code DFC-TSTX}, because that is the name the
 * operator will type. 1.12.2 has no gamerule registry, so it is created on
 * world load and only when absent - {@code addGameRule} overwrites whatever it
 * finds, and an unconditional call would reset the rule to false every time a
 * save is opened. {@code GameRules.getString} returns "" for a missing key,
 * which is the only test that distinguishes "absent" from "false".</p>
 *
 * <h3>Why it is a separate director</h3>
 * <p>{@link CinematicDirector} already owns {@code zd_tx}, and that rule is
 * about the <em>finale</em>: five seconds before the core goes, one long orbit.
 * This rule is about the <em>beginning</em>: the first three seconds of a
 * collapse, so the player sees the thing start to come apart. Two different
 * moments, two different durations, two different switches - sharing one state
 * machine would mean switching one rule on silently changes what the other
 * does.</p>
 *
 * <h3>Timing</h3>
 * <p>Edge-triggered per core: a core that is already collapsing when the rule
 * is switched on counts as an edge too, because from this director's point of
 * view it is the first time it has seen it. The shot is a fixed number of
 * ticks ({@code dfcTstx.ticks}, 60 = three seconds), sent as the existing
 * start/stop pair, so the camera is integrated on the client and the network
 * sees two small packets.</p>
 */
public final class DfcTstx {

    /** The gamerule, spelled the way it is meant to be typed. */
    public static final String RULE = "DFC-TSTX";

    private static final DfcTstx INSTANCE = new DfcTstx();

    /** One in-flight shot. A core can be orbited by exactly one of these. */
    private static final class Shot {
        final BlockPos core;
        int ticks;

        Shot(BlockPos core, int ticks) {
            this.core = core;
            this.ticks = ticks;
        }
    }

    private final Map<String, Shot> shots = new HashMap<String, Shot>();
    private int scanTimer;

    private DfcTstx() {
    }

    public static DfcTstx instance() {
        return INSTANCE;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        registerRule(event.getWorld());
    }

    /** Creates {@code DFC-TSTX} on first sight without touching an existing value. */
    public static void registerRule(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        try {
            GameRules rules = world.getGameRules();
            if (rules == null) {
                return;
            }
            if (rules.getString(RULE).isEmpty()) {
                rules.addGameRule(RULE, "false", GameRules.ValueType.BOOLEAN_VALUE);
            }
        } catch (Throwable broken) {
            if (HBMZZ.logger != null) {
                HBMZZ.logger.warn("Could not register gamerule " + RULE, broken);
            }
        }
    }

    public static boolean enabled(World world) {
        if (world == null || world.isRemote) {
            return false;
        }
        try {
            return world.getGameRules().getBoolean(RULE);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        World world = event.world;
        if (!enabled(world)) {
            // Switching the rule off mid-shot releases the camera immediately
            // instead of leaving the player behind it until the timer expires.
            if (!this.shots.isEmpty()) {
                releaseAll(world);
            }
            return;
        }
        tickShots(world);
        if (--this.scanTimer > 0) {
            return;
        }
        this.scanTimer = 10;
        scan(world);
    }

    private void scan(World world) {
        List<TileEntity> loaded = world.loadedTileEntityList;
        for (int i = 0; i < loaded.size(); i++) {
            TileEntity te;
            try {
                te = loaded.get(i);
            } catch (IndexOutOfBoundsException raced) {
                break;
            }
            if (!DFCReader.isCore(te) || !DFCReader.collapsing(te)) {
                continue;
            }
            BlockPos pos = te.getPos();
            if (pos == null) {
                continue;
            }
            String key = key(world, pos);
            if (this.shots.containsKey(key)) {
                continue;
            }
            if (CinematicDirector.orbiting(world)) {
                // Another shot already owns the camera; two directors fighting
                // over it would snap the view between two orbits.
                continue;
            }
            start(world, pos);
            this.shots.put(key, new Shot(pos, Math.max(10, HBMZZConfig.tstxTicks)));
            HBMZZ.logger.info("DFC-TSTX: orbiting the core at " + pos + " for "
                    + HBMZZConfig.tstxTicks + " ticks");
            return;
        }
        // A core that stopped collapsing drops out of the table and its shot
        // is cut short: orbiting a machine that has recovered is nonsense.
        Iterator<Map.Entry<String, Shot>> it = this.shots.entrySet().iterator();
        while (it.hasNext()) {
            Shot shot = it.next().getValue();
            if (!DFCReader.collapsing(world.getTileEntity(shot.core))) {
                it.remove();
                stop(world, shot.core);
                return;
            }
        }
    }

    private void tickShots(World world) {
        if (this.shots.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, Shot>> it = this.shots.entrySet().iterator();
        while (it.hasNext()) {
            Shot shot = it.next().getValue();
            if (--shot.ticks <= 0) {
                it.remove();
                stop(world, shot.core);
            }
        }
    }

    private void releaseAll(World world) {
        Iterator<Map.Entry<String, Shot>> it = this.shots.entrySet().iterator();
        while (it.hasNext()) {
            stop(world, it.next().getValue().core);
            it.remove();
        }
    }

    private void start(World world, BlockPos pos) {
        if (NetworkHandler.INSTANCE == null) {
            return;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 3.0D;
        double z = pos.getZ() + 0.5D;
        try {
            NetworkHandler.INSTANCE.sendToAllAround(
                    new CinematicMessage(CinematicMessage.START, x, y, z,
                            HBMZZConfig.tstxRadius, HBMZZConfig.tstxHeight, HBMZZConfig.tstxSpeed),
                    new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                            world.provider.getDimension(), x, y, z, HBMZZConfig.tstxRange));
        } catch (Throwable broken) {
            if (HBMZZ.logger != null) {
                HBMZZ.logger.warn("Could not send the DFC-TSTX camera packet", broken);
            }
        }
    }

    /**
     * Ends the shot around one core. Sent to the same radius the start used:
     * a stop packet pointed at the world origin would also cancel the five
     * second finale camera if the two rules were ever on at once.
     */
    private void stop(World world, BlockPos pos) {
        if (NetworkHandler.INSTANCE == null || pos == null) {
            return;
        }
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 3.0D;
        double z = pos.getZ() + 0.5D;
        try {
            NetworkHandler.INSTANCE.sendToAllAround(
                    new CinematicMessage(CinematicMessage.STOP, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                    new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                            world.provider.getDimension(), x, y, z, HBMZZConfig.tstxRange));
        } catch (Throwable ignored) {
        }
    }

    private static String key(World world, BlockPos pos) {
        return world.provider.getDimension() + ":" + pos.toLong();
    }
}

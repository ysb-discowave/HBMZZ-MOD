/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3.
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

import java.util.List;

/**
 * Decides when a machine is about to go off and asks nearby clients to watch it.
 *
 * <h3>The gamerule</h3>
 * <p>{@code zd_tx}, boolean, default false. 1.12.2 has no gamerule registry, so
 * it is created on world load - and only when it is not already there.
 * {@code addGameRule} overwrites whatever value it finds, so calling it
 * unconditionally would reset the rule to false every time a world was opened.
 * {@code GameRules.getString} returns "" for an absent key and "true"/"false"
 * for a present one, which is the only test that tells the two apart.</p>
 *
 * <h3>What counts as "about to go off"</h3>
 * <ul>
 *   <li><b>RBMK</b> - the moment the rods start jumping. That is our own state
 *       machine, so it calls {@link #watchRbmk} directly instead of being
 *       polled. It lasts the whole one-minute countdown.</li>
 *   <li><b>DFC</b> - the last five seconds before the core detonates, read from
 *       the same numbers the monitor draws (see {@link DFCReader#explosionIn}).
 *       Polled once a second: the window is short, but asking a counter whether
 *       it moved twenty times a second is a waste of a server tick.</li>
 * </ul>
 *
 * <p>Both end at the explosion itself. The camera is released rather than left
 * orbiting a crater, so the player watches the blast from their own eyes -
 * which is the entire point of stopping there.</p>
 */
public final class CinematicDirector {

    /** The gamerule the player turns this on with. */
    public static final String RULE = "zd_tx";

    private static final int KIND_RBMK = 1;
    private static final int KIND_DFC = 2;

    /** How far away a player has to be to be included in the shot. */
    private static final double VIEW_RANGE = 96.0D;

    /** Highest DFC countdown (seconds) that still counts as "the finale". */
    private static final double DFC_WINDOW = 5.0D;

    /** Which world and which kind the camera is currently pointed at. */
    private int activeWorldId = Integer.MIN_VALUE;
    private int activeKind;
    private double lastX;
    private double lastY;
    private double lastZ;
    private int cooldown;

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        registerRule(event.getWorld());
    }

    /** Creates {@code zd_tx} on first sight, without touching an existing value. */
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

    /** True when the player has switched the camera on for this world. */
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

    /**
     * Called by the RBMK state machine when a reactor starts jumping.
     *
     * @param height how far above the core the column's lid sits, so the camera
     *               frames the machine rather than its foundation.
     */
    public static void watchRbmk(World world, BlockPos core, int height) {
        if (!enabled(world) || core == null) {
            return;
        }
        DIRECTOR.aim(world, KIND_RBMK,
                core.getX() + 0.5D, core.getY() + height + 0.5D, core.getZ() + 0.5D,
                HBMZZConfig.cinematicSpeed);
    }

    /** The RBMK is now in pieces; give the camera back. */
    public static void releaseRbmk(World world) {
        DIRECTOR.stopIf(world, KIND_RBMK);
    }

    /**
     * One instance, registered on the event bus. The static entry points above
     * exist because the RBMK side is a plain state machine with no bus access.
     */
    private static final CinematicDirector DIRECTOR = new CinematicDirector();

    public static CinematicDirector instance() {
        return DIRECTOR;
    }

    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        World world = event.world;
        if (--this.cooldown > 0) {
            return;
        }
        this.cooldown = 20;
        if (!enabled(world)) {
            stopIf(world, this.activeKind);
            return;
        }
        pollDfc(world);
    }

    /**
     * Watches every loaded DFC core for one that is counting down.
     *
     * <p>Only the last five seconds. Starting earlier would mean orbiting a
     * machine whose collapse the player may still cancel - stabilisation can be
     * restored - and a camera that has already taken over is a camera the
     * player has to sit through.</p>
     */
    private void pollDfc(World world) {
        TileEntity best = null;
        double bestCountdown = Double.MAX_VALUE;
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
            double countdown = DFCReader.explosionIn(te);
            if (countdown < 0.0D || countdown > DFC_WINDOW) {
                continue;
            }
            if (countdown < bestCountdown) {
                best = te;
                bestCountdown = countdown;
            }
        }

        if (best == null) {
            stopIf(world, KIND_DFC);
            return;
        }
        BlockPos pos = best.getPos();
        // The core is a multiblock the size of a house; aim at its middle, not
        // at the one block the tile entity happens to occupy.
        aim(world, KIND_DFC,
                pos.getX() + 0.5D, pos.getY() + 3.0D, pos.getZ() + 0.5D,
                HBMZZConfig.cinematicDfcSpeed);
    }

    private void aim(World world, int kind, double x, double y, double z, float speed) {
        this.activeWorldId = System.identityHashCode(world);
        this.activeKind = kind;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        send(world, new CinematicMessage(CinematicMessage.START, x, y, z,
                HBMZZConfig.cinematicRadius, HBMZZConfig.cinematicHeight, speed),
                x, y, z);
    }

    private void stopIf(World world, int kind) {
        if (world == null || world.isRemote) {
            return;
        }
        if (System.identityHashCode(world) != this.activeWorldId || kind != this.activeKind) {
            return;
        }
        this.activeWorldId = Integer.MIN_VALUE;
        send(world, new CinematicMessage(CinematicMessage.STOP, 0.0D, 0.0D, 0.0D, 0.0F, 0.0F, 0.0F),
                this.lastX, this.lastY, this.lastZ);
    }

    private static void send(World world, CinematicMessage message, double x, double y, double z) {
        try {
            if (NetworkHandler.INSTANCE == null) {
                return;
            }
            NetworkHandler.INSTANCE.sendToAllAround(message,
                    new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                            world.provider.getDimension(), x, y, z, VIEW_RANGE));
        } catch (Throwable broken) {
            if (HBMZZ.logger != null) {
                HBMZZ.logger.warn("Could not send the cinematic camera packet", broken);
            }
        }
    }
}

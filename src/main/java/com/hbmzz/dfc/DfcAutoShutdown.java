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

package com.hbmzz.dfc;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.network.DfcFxMessage;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.init.SoundEvents;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Runs the automatic shutdown: the server half of the show and the only part
 * that touches the core.
 *
 * <h3>Who owns what</h3>
 * <ul>
 *   <li>{@link DfcShutdownPlan} decides <em>when</em> each phase happens. It has
 *       no idea a world exists.</li>
 *   <li>This class applies the decision: it writes the temperature, broadcasts
 *       the packets, plays the sounds, and gives up when the monitor stops
 *       asking.</li>
 *   <li>The monitor owns the <em>arm</em> switch. Every tick an armed monitor
 *       calls {@link #tick}; when it is switched off, unbound or broken it
 *       calls {@link #release}. A monitor that unloads simply stops calling,
 *       and the session expires on its own.</li>
 * </ul>
 *
 * <h3>Why the tick comes from the monitor</h3>
 * <p>The obvious alternative - a world-level watcher - would keep cooling a
 * core after the player unbound the screen, which is exactly the behaviour the
 * "the switch follows the binding" requirement forbids. Driving from the
 * monitor makes the authority the same object that owns the button.</p>
 *
 * <h3>Multiple monitors, one core</h3>
 * <p>Two armed screens watching the same core would otherwise run two plans,
 * doubling the ramp and firing two implosions. A session remembers the tick it
 * was last advanced on, and the second caller in the same tick is a no-op.</p>
 */
public final class DfcAutoShutdown {

    /** How long a session survives without being ticked (chunk unloaded). */
    private static final int GRACE_TICKS = 200;

    private static final Map<String, Session> SESSIONS = new HashMap<String, Session>();

    private DfcAutoShutdown() {
    }

    /**
     * Advances the procedure for one armed monitor. Called every server tick.
     */
    public static void tick(World world, BlockPos monitorPos, BlockPos corePos) {
        if (world == null || world.isRemote || corePos == null || !HBMZZConfig.dfcShutdownEnabled) {
            return;
        }
        long now = world.getTotalWorldTime();
        String key = key(world, corePos);
        Session session = SESSIONS.get(key);
        if (!world.isBlockLoaded(corePos)) {
            // Not loaded is not the same as gone. The session keeps its plan and
            // pauses; only a loaded-but-empty position counts as "the core is
            // gone", otherwise walking away would silently disarm the screen.
            if (session != null && now - session.lastSeen > GRACE_TICKS) {
                SESSIONS.remove(key);
            }
            return;
        }
        TileEntity core = world.getTileEntity(corePos);

        if (core == null) {
            // The core's chunk is not loaded (or the core is gone). Pause
            // rather than reset: a one-second chunk hiccup must not restart a
            // sixty-second ramp, but a core that never comes back must not
            // leave an entry behind forever.
            if (session != null && now - session.lastSeen > GRACE_TICKS) {
                SESSIONS.remove(key);
            }
            return;
        }

        if (session == null) {
            session = new Session(world.provider.getDimension(), corePos);
            SESSIONS.put(key, session);
        }
        session.lastSeen = now;

        if (session.lastTick == now) {
            // A second monitor on the same core; the first one already ticked.
            return;
        }
        session.lastTick = now;

        boolean collapsing = DFCReader.collapsing(core);
        double explosionIn = DFCReader.explosionIn(core);
        DfcShutdownPlan.Phase before = session.plan.phase();
        session.plan.tick(collapsing, explosionIn);
        DfcShutdownPlan.Phase after = session.plan.phase();

        if (after != before) {
            onPhase(world, core, session, before, after);
        }

        applyTemperature(world, core, session);
        emit(world, corePos, session, now);
    }

    /** Stops the procedure for a core. Safe to call when nothing is running. */
    public static void release(World world, BlockPos corePos) {
        if (world == null || world.isRemote || corePos == null) {
            return;
        }
        Session session = SESSIONS.remove(key(world, corePos));
        if (session != null) {
            session.plan.release();
        }
    }

    /** True while a core is being actively cooled or shown. For the screen label. */
    public static boolean active(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return false;
        }
        Session session = SESSIONS.get(key(world, corePos));
        return session != null && session.plan.active();
    }

    /** Current phase name, for diagnostics and the monitor's status line. */
    public static String phaseOf(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return "IDLE";
        }
        Session session = SESSIONS.get(key(world, corePos));
        return session == null ? "IDLE" : session.plan.phase().name();
    }

    /** Drops every session. Called when the server stops. */
    public static void clearAll() {
        SESSIONS.clear();
    }

    // ------------------------------------------------------------------ phases

    private static void onPhase(World world, TileEntity core, Session session,
                                DfcShutdownPlan.Phase before, DfcShutdownPlan.Phase after) {
        BlockPos pos = core.getPos();
        double cx = pos.getX() + 0.5D;
        double cy = pos.getY() + 3.0D;
        double cz = pos.getZ() + 0.5D;

        switch (after) {
            case AURA:
                sound(world, pos, SoundEvents.BLOCK_END_PORTAL_SPAWN, 0.45F, 0.65F);
                break;

            case RETRACT:
                // The moment the procedure commits. One implosion, one low
                // report: the aura was a warning, this is the machine doing
                // something about it.
                fx(world, DfcFxMessage.IMPLODE, cx, cy, cz, HBMZZConfig.dfcShutdownFxRange, 0.0F);
                sound(world, pos, SoundEvents.ENTITY_GENERIC_EXPLODE, 0.55F, 0.55F);
                HBMZZ.logger.info("DFC auto shutdown: retraction started at " + pos
                        + " with " + session.plan.window() + " ticks of ramp");
                break;

            case HOLD:
                fx(world, DfcFxMessage.IMPLODE, cx, cy, cz, HBMZZConfig.dfcShutdownFxRange * 0.6F, 1.0F);
                sound(world, pos, SoundEvents.BLOCK_ANVIL_PLACE, 0.5F, 0.45F);
                if (HBMZZConfig.dfcShutdownForceStop && !session.stopAttempted) {
                    session.stopAttempted = true;
                    if (DFCReader.clearCollapse(core)) {
                        HBMZZ.logger.info("DFC auto shutdown: cleared the collapse at " + pos);
                    } else {
                        // Not a failure of the cooling - the temperature is
                        // already zero. It only means the upstream countdown
                        // owns its own state and will not be talked out of it.
                        HBMZZ.logger.warn("DFC auto shutdown: core at " + pos
                                + " reached 0 C but exposes no way to clear its collapse;"
                                + " the upstream countdown keeps running");
                    }
                }
                break;

            case DONE:
                HBMZZ.logger.info("DFC auto shutdown: finished at " + pos);
                break;

            default:
                break;
        }
    }

    /**
     * Writes the plan's temperature, and aborts the procedure if the write
     * stops landing.
     *
     * <p>An unreachable core is not something to keep pretending about: if the
     * temperature cannot be written for a few ticks the effects stop too. The
     * alternative is a minute of convincing blue particles around a core that
     * is not cooling at all.</p>
     */
    private static void applyTemperature(World world, TileEntity core, Session session) {
        double target = session.plan.targetTemperature();
        if (Double.isNaN(target)) {
            session.writeFailures = 0;
            return;
        }
        if (DFCReader.writeTemperature(core, target)) {
            session.writeFailures = 0;
            return;
        }
        if (++session.writeFailures >= 3) {
            if (!session.reportedWriteFailure) {
                session.reportedWriteFailure = true;
                HBMZZ.logger.warn("DFC auto shutdown: cannot write the temperature of the core at "
                        + core.getPos() + "; releasing so the display cannot claim a cooldown");
            }
            session.plan.release();
        }
    }

    private static void emit(World world, BlockPos corePos, Session session, long now) {
        DfcShutdownPlan.Phase phase = session.plan.phase();
        double cx = corePos.getX() + 0.5D;
        double cy = corePos.getY() + 3.0D;
        double cz = corePos.getZ() + 0.5D;
        float range = HBMZZConfig.dfcShutdownFxRange;

        switch (phase) {
            case AURA:
                if (now % Math.max(1, HBMZZConfig.dfcShutdownAuraTicks) == 0L) {
                    fx(world, DfcFxMessage.AURA, cx, cy, cz, range, 0.0F);
                }
                if (now % Math.max(10, HBMZZConfig.dfcShutdownBurstTicks) == 0L) {
                    fx(world, DfcFxMessage.BURST, cx, cy, cz, range, 0.0F);
                    sound(world, corePos, SoundEvents.ENTITY_ENDERDRAGON_GROWL, 0.25F, 0.6F);
                }
                break;

            case RETRACT:
                {
                    int window = Math.max(1, session.plan.window());
                    float progress = Math.min(1.0F, (float) session.plan.age() / (float) window);
                    // Every other tick, so the ring reads as a continuous pull
                    // instead of twelve separate puffs.
                    if (now % 2L == 0L) {
                        // The radius is the visible part: it runs from the full
                        // cloud down to almost nothing, which is what "收回" is.
                        float radius = range * (1.0F - progress) + 0.6F;
                        fx(world, DfcFxMessage.RING, cx, cy, cz, radius, progress);
                    }
                }
                break;

            default:
                break;
        }
    }

    // ---------------------------------------------------------------- plumbing

    private static void fx(World world, byte mode, double x, double y, double z,
                           float radius, float progress) {
        if (NetworkHandler.INSTANCE == null) {
            return;
        }
        try {
            NetworkHandler.INSTANCE.sendToAllAround(
                    new DfcFxMessage(mode, x, y, z, radius, progress),
                    new NetworkRegistry.TargetPoint(world.provider.getDimension(), x, y, z,
                            HBMZZConfig.dfcShutdownWatchRange));
        } catch (Throwable broken) {
            // A dropped effect packet is not worth taking a tick down for.
        }
    }

    private static void sound(World world, BlockPos pos, net.minecraft.util.SoundEvent event,
                              float volume, float pitch) {
        try {
            world.playSound(null, pos, event, SoundCategory.BLOCKS, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    private static String key(World world, BlockPos pos) {
        return world.provider.getDimension() + ":" + pos.toLong();
    }

    private static final class Session {
        final DfcShutdownPlan plan;
        long lastSeen;
        long lastTick = Long.MIN_VALUE;
        int writeFailures;
        boolean stopAttempted;
        boolean reportedWriteFailure;

        Session(int dimension, BlockPos core) {
            this.plan = new DfcShutdownPlan(
                    HBMZZConfig.dfcShutdownRetractSeconds * 20,
                    HBMZZConfig.dfcShutdownHoldSeconds * 20,
                    HBMZZConfig.dfcShutdownFallbackTicks,
                    200);
        }
    }
}

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
import com.rsaudio.util.RemoteAudio;
import net.minecraft.init.SoundEvents;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Runs the automatic shutdown: the server half of the show and the only part
 * that touches the core.
 *
 * <h3>Who owns what</h3>
 * <ul>
 *   <li>{@link DfcShutdownPlan} decides <em>when</em> each phase happens. It has
 *       no idea a world exists.</li>
 *   <li>{@link DfcShutdownArms} decides <em>which monitor owns which core</em>.
 *       A core keeps a single shared plan while several monitors arm it, and
 *       releasing one monitor leaves the others alone.</li>
 *   <li>This class applies the decision: it writes the temperature, broadcasts
 *       the packets, plays the sounds, and asks the monitor to disarm when the
 *       write stops landing.</li>
 * </ul>
 *
 * <h3>Why the tick comes from the monitor</h3>
 * <p>A world-level watcher would keep cooling a core after the player unbound
 * the screen, which is exactly what "the switch follows the binding" forbids.
 * Driving from the monitor makes the authority the same object that owns the
 * button.</p>
 *
 * <h3>Multiple monitors, one core</h3>
 * <p>The shared plan is advanced once per tick no matter how many monitors
 * call in. Rendering a second screen or turning a second one off never resets
 * or doubles the ramp.</p>
 */
public final class DfcAutoShutdown {

    /**
     * 温度驱动流程 {@link DfcAutoStopPlan} 按核心保存。它和倒计时流程
     * ({@link DfcShutdownPlan}) 刻意分开：前者看"温度 > 5000℃ 且正在破坏建筑"，
     * 后者看爆炸倒计时。两套流程互不打断，表同一个核心时只让温度流程写温度。
     */
    private static final Map<String, DfcAutoStopPlan> AUTO_STOPS =
            new HashMap<String, DfcAutoStopPlan>();

    private DfcAutoShutdown() {
    }

    /**
     * Advances the procedure for one armed monitor. Called every server tick.
     *
     * @return {@code false} when the temperature write has failed for good and
     *         the calling monitor should switch itself off; {@code true} otherwise.
     */
    public static boolean tick(World world, BlockPos monitorPos, BlockPos corePos) {
        if (world == null || world.isRemote || monitorPos == null || corePos == null
                || !HBMZZConfig.dfcShutdownEnabled) {
            return true;
        }
        long now = world.getTotalWorldTime();
        // getTileEntity loads the chunk; the monitor has already declined to
        // call us here for exactly that reason. Keep the guard for safety.
        if (!world.isBlockLoaded(corePos)) {
            return true;
        }
        TileEntity core = world.getTileEntity(corePos);
        if (core == null) {
            return true;
        }

        String monitorKey = key(world, monitorPos);
        String coreKey = key(world, corePos);

        // Temperature-driven auto stop. A core hotter than the threshold that is
        // destroying blocks enters auto stop immediately - never a pause/hold. This
        // takes priority over the countdown flow: while it runs, it is the only
        // writer of the core temperature.
        if (driveAutoStop(world, core, corePos, coreKey, now)) {
            return true;
        }
        DfcShutdownPlan plan = DfcShutdownArms.begin(monitorKey, coreKey, now, newPlan());
        if (DfcShutdownArms.aborted(monitorKey)) {
            return false;
        }
        if (plan == null) {
            // Either a second monitor already advanced this shared core this
            // tick, or the session is finished with a failed write.
            return true;
        }

        boolean collapsing = DFCReader.collapsing(core);
        double explosionIn = DFCReader.explosionIn(core);
        DfcShutdownPlan.Phase before = plan.phase();
        plan.tick(collapsing, explosionIn);
        DfcShutdownPlan.Phase after = plan.phase();

        if (after != before) {
            onPhase(world, core, plan, monitorKey, before, after);
        }

        if (!applyTemperature(world, core, plan, monitorKey)) {
            DfcShutdownArms.abort(monitorKey);
            return false;
        }
        emit(world, corePos, plan, now);
        return true;
    }

    /** Stops the procedure held by one monitor. Safe to call when nothing is running. */
    public static void release(World world, BlockPos monitorPos) {
        if (world == null || world.isRemote || monitorPos == null) {
            return;
        }
        // Binding changes / switch-off are handled by the monitor's own reset;
        // auto-stop plans are dropped wholesale on server stop via clearAll().
        DfcShutdownArms.release(key(world, monitorPos));
    }

    /**
     * 统一显示文案：停堆 / 自动停堆 / 强制抑制。给监控屏状态区调用。
     * 不返回任何"停顿 / 暂停"之类的表述。
     */
    public static String stopStateLabel(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return DfcAutoStopPlan.State.STOPPED.label();
        }
        DfcAutoStopPlan plan = AUTO_STOPS.get(key(world, corePos));
        return plan == null ? DfcAutoStopPlan.State.STOPPED.label() : plan.displayState();
    }

    /** True while a core is being actively cooled or shown. For the screen label. */
    public static boolean active(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return false;
        }
        return DfcShutdownArms.active(key(world, corePos));
    }

    /** Current phase name, for diagnostics and the monitor's status line. */
    public static String phaseOf(World world, BlockPos corePos) {
        if (world == null || corePos == null) {
            return "IDLE";
        }
        return DfcShutdownArms.phase(key(world, corePos));
    }

    /** Drops every arm and session. Called when the server stops. */
    public static void clearAll() {
        DfcShutdownArms.clear();
        AUTO_STOPS.clear();
    }

    // ------------------------------------------------- temperature-driven auto stop

    /**
     * Drives one core's {@link DfcAutoStopPlan}. Returns true when this flow owns
     * the core this tick (i.e. it is active, or it just finished), so the caller
     * skips the countdown flow for this tick.
     */
    private static boolean driveAutoStop(World world, TileEntity core, BlockPos corePos,
                                         String coreKey, long now) {
        DfcAutoStopPlan plan = AUTO_STOPS.get(coreKey);
        if (plan == null) {
            plan = new DfcAutoStopPlan(
                    HBMZZConfig.dfcAutoStopTempC,
                    HBMZZConfig.dfcAutoStopStepC,
                    HBMZZConfig.dfcAutoStopRampTicks,
                    HBMZZConfig.dfcAutoStopSuppressFxTicks);
            AUTO_STOPS.put(coreKey, plan);
        }

        boolean wasActive = plan.active();
        double temperature = DFCReader.temperature(core);
        boolean destroying = DFCReader.collapsing(core);
        plan.tick(temperature, destroying);

        if (plan.enteredAutoStop()) {
            onAutoStopEnter(world, corePos);
        }
        if (plan.enteredSuppress()) {
            onSuppressEnter(world, corePos);
        }
        if (plan.emittingSuppressFx()) {
            suppressFx(world, corePos, plan, now);
        }

        Double target = plan.desiredTemperature();
        if (target != null) {
            DFCReader.writeTemperature(core, target);
            if (plan.zeroWrittenThisTick()) {
                onSuppressComplete(world, core, corePos);
            }
        }

        // Keep the entry after it finishes for one tick so a finished core does
        // not immediately fall through to the countdown flow in the same tick.
        return wasActive || plan.active();
    }

    /** Moment automatic stop engages: play the configured music and one implosion. */
    private static void onAutoStopEnter(World world, BlockPos corePos) {
        playAutoStopMusic(world, corePos);
        double cx = corePos.getX() + 0.5D;
        double cy = corePos.getY() + 3.0D;
        double cz = corePos.getZ() + 0.5D;
        fx(world, DfcFxMessage.IMPLODE, cx, cy, cz, HBMZZConfig.dfcShutdownFxRange, 0.0F);
        sound(world, corePos, SoundEvents.ENTITY_GENERIC_EXPLODE, 0.55F, 0.55F);
        HBMZZ.logger.info("DFC auto stop: engaged at " + corePos
                + " (hotter than " + HBMZZConfig.dfcAutoStopTempC + " C while destroying)");
    }

    /** Moment forced suppression starts: a distinct, unmistakable effect. */
    private static void onSuppressEnter(World world, BlockPos corePos) {
        double cx = corePos.getX() + 0.5D;
        double cy = corePos.getY() + 3.0D;
        double cz = corePos.getZ() + 0.5D;
        fx(world, DfcFxMessage.SUPPRESS, cx, cy, cz, HBMZZConfig.dfcShutdownFxRange, 0.0F);
        sound(world, corePos, SoundEvents.ENTITY_ENDERDRAGON_GROWL, 0.4F, 0.5F);
        HBMZZ.logger.info("DFC auto stop: forced suppression at " + corePos
                + " - temperature still above threshold after gradual cooldown");
    }

    /** The visible effect process during forced suppression, until it snaps to 0. */
    private static void suppressFx(World world, BlockPos corePos, DfcAutoStopPlan plan, long now) {
        double cx = corePos.getX() + 0.5D;
        double cy = corePos.getY() + 3.0D;
        double cz = corePos.getZ() + 0.5D;
        float progress = (float) plan.age() / (float) Math.max(1, plan.suppressFxTicks());
        // Two rings a tick, tightening inward: a deliberate process, not a flash.
        if (now % 2L == 0L) {
            float radius = HBMZZConfig.dfcShutdownFxRange * (1.0F - progress) + 0.8F;
            fx(world, DfcFxMessage.SUPPRESS, cx, cy, cz, radius, progress);
        }
    }

    /** The effect has played and temperature was just written to 0. */
    private static void onSuppressComplete(World world, TileEntity core, BlockPos corePos) {
        double cx = corePos.getX() + 0.5D;
        double cy = corePos.getY() + 3.0D;
        double cz = corePos.getZ() + 0.5D;
        fx(world, DfcFxMessage.IMPLODE, cx, cy, cz, HBMZZConfig.dfcShutdownFxRange, 1.0F);
        DFCReader.clearCollapse(core);
        HBMZZ.logger.info("DFC auto stop: forced suppression finished at " + corePos
                + " - temperature set to 0");
    }

    /** Plays the configured track via rsa's server->client audio route. */
    private static void playAutoStopMusic(World world, BlockPos corePos) {
        String name = HBMZZConfig.dfcAutoStopMusic;
        if (name == null || name.isEmpty()) {
            return;
        }
        try {
            RemoteAudio.playAt(world, corePos, HBMZZConfig.dfcShutdownWatchRange, 0, name, false);
        } catch (Throwable t) {
            // A missing rsaudio installation must never take the shutdown down.
            HBMZZ.logger.warn("DFC auto stop: could not play music '" + name + "': " + t);
        }
    }

    // ------------------------------------------------------------------ phases

    private static void onPhase(World world, TileEntity core, DfcShutdownPlan plan,
                                String monitorKey,
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
                        + " with " + plan.window() + " ticks of ramp");
                break;

            case HOLD:
                fx(world, DfcFxMessage.IMPLODE, cx, cy, cz, HBMZZConfig.dfcShutdownFxRange * 0.6F, 1.0F);
                sound(world, pos, SoundEvents.BLOCK_ANVIL_PLACE, 0.5F, 0.45F);
                if (HBMZZConfig.dfcShutdownForceStop && DfcShutdownArms.claimStopAttempt(monitorKey)) {
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
     * Writes the plan's temperature.
     *
     * @return false after three consecutive failed writes, so the caller can
     *         abort instead of showing a cooldown that never lands.
     */
    private static boolean applyTemperature(World world, TileEntity core,
                                            DfcShutdownPlan plan, String monitorKey) {
        double target = plan.targetTemperature();
        if (Double.isNaN(target)) {
            DfcShutdownArms.noteWrite(monitorKey, true);
            return true;
        }
        boolean written = DFCReader.writeTemperature(core, target);
        int failures = DfcShutdownArms.noteWrite(monitorKey, written);
        if (written || failures < 3) {
            return true;
        }
        HBMZZ.logger.warn("DFC auto shutdown: cannot write the temperature of the core at "
                + core.getPos() + "; releasing so the display cannot claim a cooldown");
        return false;
    }

    private static void emit(World world, BlockPos corePos, DfcShutdownPlan plan, long now) {
        DfcShutdownPlan.Phase phase = plan.phase();
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
                    int window = Math.max(1, plan.window());
                    float progress = Math.min(1.0F, (float) plan.age() / (float) window);
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

    private static Supplier<DfcShutdownPlan> newPlan() {
        return new Supplier<DfcShutdownPlan>() {
            @Override
            public DfcShutdownPlan get() {
                return new DfcShutdownPlan(
                        HBMZZConfig.dfcShutdownRetractSeconds * 20,
                        HBMZZConfig.dfcShutdownHoldSeconds * 20,
                        HBMZZConfig.dfcShutdownFallbackTicks,
                        200);
            }
        };
    }

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
}

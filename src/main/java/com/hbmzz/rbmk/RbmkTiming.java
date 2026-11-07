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

package com.hbmzz.rbmk;

/**
 * The numbers and the state machine behind the staged RBMK blow-up.
 *
 * <p>Everything here is a pure function of ticks and readings: no world, no
 * tile entity, no client. That is deliberate. A meltdown is the one feature in
 * this mod that cannot be tested by looking at it - by the time it runs, the
 * save is on fire - so the timing, the ramp and the trip condition are kept
 * where {@code tools/rbmkcheck} can run them a thousand times without a game.</p>
 *
 * <h3>The sequence</h3>
 * <ol>
 *   <li><b>WARMUP</b> - {@link #JUMP_TICKS} ticks (one minute) of rods jumping.
 *       The jump itself is the Cursed Edition's, driven by the {@code
 *       leafia$damage} field it adds to {@code TileEntityRBMKBase}; we only
 *       decide how much of it to ask for, via {@link #jumpDamage(int)}.</li>
 *   <li><b>blast</b> - the lid leaves. {@link #LID_LAUNCH_VY} up, gravity
 *       {@link #LID_GRAVITY} down: about sixty blocks of apex, and the plate is
 *       held inside its own footprint on the way down so that it lands back on
 *       the core rather than somewhere in the field.</li>
 *   <li><b>second blast</b> - the plate hits the core, the core detonates
 *       under it and throws it again, {@link #LID_REBLAST_VY}, this time with a
 *       sideways kick because the blast is no longer symmetric.</li>
 *   <li><b>rest</b> - it falls a second time, lands on whatever is left, and
 *       stays there smoking.</li>
 * </ol>
 *
 * <p>Two launches, not one, because that is the shape of the accident being
 * imitated: the first blast throws the lid, the lid lands back on the core, the
 * core answers. A single launch would read as a big explosion; this reads as a
 * machine coming apart in stages, which is the whole point.</p>
 */
public final class RbmkTiming {

    /** How long the rods jump before anything breaks. One minute of warning. */
    public static final int JUMP_TICKS = 1200;

    /** Ticks between two scans for a reactor that has not tripped yet. */
    public static final int SCAN_INTERVAL = 20;

    /**
     * Degrees of heat above which a reactor is considered tripped - together
     * with full steam, see {@link #steamFull(int[], int)}.
     *
     * <p>1000, not the 1500 that {@code maxHeat()} returns for most columns:
     * at 1500 the base mod melts the core down on its own and there would be
     * nothing left for this sequence to stage. 1000 is the range where the
     * boiler is screaming and the rods are already nervous.</p>
     */
    public static final double HEAT_TRIP = 1000.0;

    /** Jump damage asked for at the first tick of WARMUP. */
    public static final int DAMAGE_START = 240;

    /**
     * Jump damage asked for at the last tick.
     *
     * <p>Below the Cursed Edition's own 1200 meltdown line on purpose: 1200
     * makes {@code TileEntityRBMKBase.meltdown()} fire from inside the addon and
     * the reactor is destroyed before the lid ever moves. 1150 is the loudest
     * jump that still leaves the trigger ours.</p>
     */
    public static final int DAMAGE_END = 1150;

    /**
     * Upward speed of the lid on the first blast, in blocks a tick.
     *
     * <p>3.2 puts the apex around sixty blocks up - high enough that the plate
     * leaves the reactor's own smoke and reads against the sky before it turns
     * over. The old 2.0 topped out below the treeline, which is why the first
     * version looked like a hop rather than a launch.</p>
     */
    public static final double LID_LAUNCH_VY = 3.2D;

    /** Upward speed on the second blast - harder, because the core helped. */
    public static final double LID_REBLAST_VY = 4.25D;

    /** Per-tick gravity on a flying lid. */
    public static final double LID_GRAVITY = 0.08D;

    /** Horizontal drag per tick while flying. */
    public static final double LID_DRAG = 0.97D;

    /** Vertical drag per tick. Barely any: the lid should fall like a lid. */
    public static final double LID_DRAG_Y = 0.995D;

    /**
     * Fastest a lid may fall, in blocks a tick.
     *
     * <p>Without this the terminal velocity of an 0.08/0.005 integrator is
     * sixteen blocks a tick, and the plate tunnels straight through the reactor
     * on the way down - the one landing that has to be seen. The reference
     * implementation caps at 2.5 for the same reason.</p>
     */
    public static final double LID_MAX_FALL = 2.5D;

    /**
     * How far outside its own footprint a plate may drift, in blocks.
     *
     * <p>The first landing has to be <em>on the reactor</em>, or the second
     * blast has nothing to be a second blast of. The plate is allowed to lean
     * out over the edge - it is a slab, not a ball - but not to sail off and
     * come down in the field next door.</p>
     */
    public static final double LID_HOME_PAD = 2.0D;

    /** Give up waiting for a landing after this many ticks and blink it out. */
    public static final int FLIGHT_LIMIT = 400;

    /** Ticks the settled lid stays on the ground, smoking, before it goes. */
    public static final int REST_TICKS = 1200;

    /** Blocks of lid offset allowed either side of the plate centre. */
    public static final int MAX_LID_SPAN = 32;

    /** Most lids one plate will carry. A 32x32 core is already absurd. */
    public static final int MAX_LIDS = 1024;

    /** Where the reactor is in the sequence. */
    public enum Phase {
        /** Nothing wrong. Waiting for the trip. */
        IDLE,
        /** Rods jumping, heat held under the base mod's meltdown line. */
        WARMUP,
        /** The lid has been thrown; the entity owns the rest. */
        SPENT
    }

    private RbmkTiming() {
    }

    /**
     * One tick of the state machine.
     *
     * <p>Monotonic by construction: a phase only ever moves forward, and
     * {@link Phase#SPENT} has no exit. A reactor that trips, cools down and
     * trips again must not restart the countdown halfway through - by then the
     * lid is already in the air and restarting would throw a second one.</p>
     */
    public static Phase advance(Phase phase, boolean tripped, int elapsed, int jumpTicks) {
        if (phase == null || phase == Phase.SPENT) {
            return Phase.SPENT;
        }
        if (phase == Phase.IDLE) {
            return tripped ? Phase.WARMUP : Phase.IDLE;
        }
        return elapsed >= Math.max(1, jumpTicks) ? Phase.SPENT : Phase.WARMUP;
    }

    /** As {@link #advance(Phase, boolean, int, int)}, at the default length. */
    public static Phase advance(Phase phase, boolean tripped, int elapsed) {
        return advance(phase, tripped, elapsed, JUMP_TICKS);
    }

    /**
     * Jump damage for a given tick of the warm-up.
     *
     * <p>Rises on a square-root curve so the first seconds are a twitch and the
     * last are a hammer. Linear would look mechanical, and the Cursed Edition's
     * own damage already grows by heat rather than by time - this is the curve
     * that keeps the two visually compatible.</p>
     */
    public static int jumpDamage(int elapsed, int jumpTicks) {
        int span = Math.max(1, jumpTicks);
        if (elapsed <= 0) {
            return DAMAGE_START;
        }
        if (elapsed >= span) {
            return DAMAGE_END;
        }
        double progress = (double) elapsed / (double) span;
        double eased = Math.sqrt(progress);
        return (int) Math.round(DAMAGE_START + (DAMAGE_END - DAMAGE_START) * eased);
    }

    /** As {@link #jumpDamage(int, int)}, at the default length. */
    public static int jumpDamage(int elapsed) {
        return jumpDamage(elapsed, JUMP_TICKS);
    }

    /** True when the warm-up is over and the lid should leave. */
    public static boolean launchDue(int elapsed, int jumpTicks) {
        return elapsed >= Math.max(1, jumpTicks);
    }

    /** Upward speed for a lid that is being thrown for the nth time. */
    public static double launchVy(boolean second) {
        return second ? LID_REBLAST_VY : LID_LAUNCH_VY;
    }

    /** Rough apex of a launch, in blocks. Used by the docs and by the tests. */
    public static double apex(double vy) {
        // v^2 / 2g in a discrete integrator overshoots slightly; the closed
        // form is what the player perceives, so it is what we document.
        return vy * vy / (2.0D * LID_GRAVITY);
    }

    /**
     * "Steam full" against the capacity the base mod declares.
     *
     * <p>Stricter than an average on purpose. A reactor where three of forty
     * columns are full is not about to blow its lid; an average would call that
     * full at 7% and trip on a reactor that is merely warm. Columns that hold
     * no steam at all (absorbers, reflectors, control rods) are not counted, or
     * no reactor would ever trip.</p>
     *
     * <p>Only half the answer, because the capacity is not trustworthy - see
     * {@link #steamStalled(long, long, long)}.</p>
     */
    public static boolean steamFull(int[] steam, int maxSteam) {
        if (steam == null || maxSteam <= 0) {
            return false;
        }
        boolean any = false;
        for (int value : steam) {
            if (value <= 0) {
                continue;
            }
            any = true;
            if (value < maxSteam) {
                return false;
            }
        }
        return any;
    }

    /**
     * "Steam full" measured by behaviour: the buffer stopped filling.
     *
     * <p>{@link #steamFull(int[], int)} compares against
     * {@code TileEntityRBMKBase.maxSteam}, which is a compile-time constant of
     * 16000. Addons that rework RBMK boilers raise the real ceiling without
     * touching that field - the reference implementation everyone runs with
     * raises it to 80000 - so a reactor sitting at 16000 of 80000 is 20% full
     * and would trip instantly. Reading the field there is not just imprecise,
     * it is wrong by a factor of five.</p>
     *
     * <p>What is true regardless of the constant: a full buffer stops rising.
     * Three consecutive scans (two seconds) of an unchanged, non-zero total
     * means the steam has nowhere left to go. The heat half of the trip is
     * checked separately, so a reactor that is simply idle cannot pass this.</p>
     */
    public static boolean steamStalled(long twoScansAgo, long previous, long now) {
        return now > 0L && now == previous && previous == twoScansAgo;
    }

    /** Hottest column above the trip line. */
    public static boolean overheated(double[] heat, double threshold) {
        if (heat == null) {
            return false;
        }
        for (double value : heat) {
            if (value > threshold) {
                return true;
            }
        }
        return false;
    }

    /**
     * Both halves of the trip condition, kept together so they cannot drift.
     *
     * <p>Heat is the "is it hot" half; steam is the "does it have anywhere to
     * put the pressure" half. Either definition of full counts - the declared
     * capacity, or the observed plateau - because which one is right depends on
     * which addons are installed, and a trip that works on one pack and not the
     * other is worse than one that is slightly eager.</p>
     */
    public static boolean tripped(double[] heat, int[] steam, int maxSteam, double threshold) {
        return overheated(heat, threshold) && steamFull(steam, maxSteam);
    }

    /** As {@link #tripped(double[], int[], int, double)}, plus the plateau test. */
    public static boolean tripped(double[] heat, int[] steam, int maxSteam, double threshold,
                                  long steamTwoScansAgo, long steamPrevious, long steamNow) {
        return overheated(heat, threshold)
                && (steamFull(steam, maxSteam)
                || steamStalled(steamTwoScansAgo, steamPrevious, steamNow));
    }
}

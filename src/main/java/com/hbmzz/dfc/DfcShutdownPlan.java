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

/**
 * The auto-shutdown procedure as a state machine, with no world in sight.
 *
 * <h3>Why this is a separate class</h3>
 * <p>Every decision the procedure makes - when the aura starts, when the
 * retraction begins, how fast the temperature falls, when it lets go - is a
 * pure function of "is the core collapsing" and "how many seconds remain".
 * Keeping it free of Minecraft types means the whole sequence can be stepped
 * and asserted offline (see {@code tools/dfccheck}), instead of only being
 * observable by waiting a minute next to a real DFC and hoping the explosion
 * does not take the log with it. The renderer, the particles and the field
 * writes live in {@link DfcAutoShutdown}, which owns a plan and nothing else.</p>
 *
 * <h3>Phases</h3>
 * <table>
 *   <tr><th>Phase</th><th>Entered when</th><th>What it does</th></tr>
 *   <tr><td>{@link Phase#IDLE}</td><td>nothing is wrong</td><td>nothing</td></tr>
 *   <tr><td>{@link Phase#AURA}</td><td>the armed core starts collapsing</td>
 *       <td>blue aura + energy bursts, temperature untouched</td></tr>
 *   <tr><td>{@link Phase#RETRACT}</td><td>one minute (configurable) remains</td>
 *       <td>suction rings; temperature jumps to 50 and ramps to 0 over the
 *           remaining window</td></tr>
 *   <tr><td>{@link Phase#HOLD}</td><td>the ramp finished</td>
 *       <td>temperature pinned at 0 for a few seconds</td></tr>
 *   <tr><td>{@link Phase#DONE}</td><td>the hold finished</td>
 *       <td>nothing; waits for the core to stop collapsing, then re-arms</td></tr>
 * </table>
 *
 * <h3>The two deliberate asymmetries</h3>
 * <ul>
 *   <li><b>AURA aborts, RETRACT does not.</b> If the operator switches the
 *       monitor off (or unbinds it) while the aura is running, the aura simply
 *       stops - nothing has been changed yet. Once the retraction has started
 *       the procedure is committed: it finishes the ramp even if the collapse
 *       ends halfway, because a core left at 37 degrees because somebody
 *       clicked a screen would look like a bug and behave like one.</li>
 *   <li><b>DONE needs a calm stretch, not a calm tick.</b> A core that flickers
 *       in and out of collapse would otherwise re-arm the procedure on every
 *       other tick and fire the implosion sound dozens of times.</li>
 * </ul>
 */
public final class DfcShutdownPlan {

    /** Where the procedure currently is. */
    public enum Phase {
        IDLE,
        AURA,
        RETRACT,
        HOLD,
        DONE
    }

    /** Temperature the retraction starts from, in degrees. */
    public static final double START_TEMPERATURE = 50.0D;

    private final int retractTicks;
    private final int holdTicks;
    private final int fallbackTicks;
    private final int rearmTicks;

    private Phase phase = Phase.IDLE;
    private int age;
    private int window;
    private int calm;

    /**
     * @param retractTicks  ramp length, normally the last minute (1200 ticks)
     * @param holdTicks     how long 0 degrees is held after the ramp
     * @param fallbackTicks when to give up waiting for a countdown and start
     *                      the ramp anyway
     * @param rearmTicks    calm ticks required before a finished plan re-arms
     */
    public DfcShutdownPlan(int retractTicks, int holdTicks, int fallbackTicks, int rearmTicks) {
        this.retractTicks = Math.max(20, retractTicks);
        this.holdTicks = Math.max(0, holdTicks);
        this.fallbackTicks = Math.max(20, fallbackTicks);
        this.rearmTicks = Math.max(20, rearmTicks);
    }

    public Phase phase() {
        return this.phase;
    }

    /** Ticks spent in the current phase. */
    public int age() {
        return this.age;
    }

    /** Length of the current ramp, in ticks; 0 outside RETRACT. */
    public int window() {
        return this.phase == Phase.RETRACT ? this.window : 0;
    }

    /** True while the procedure is doing something visible. */
    public boolean active() {
        return this.phase != Phase.IDLE && this.phase != Phase.DONE;
    }

    /**
     * Temperature to write this tick, or {@link Double#NaN} when the procedure
     * is not touching the core.
     */
    public double targetTemperature() {
        switch (this.phase) {
            case RETRACT:
                // Starts at 50 on the first tick of the phase and reaches 0 on
                // the last. Clamped because a one-tick overshoot would write a
                // negative heat and some cores clamp that to something odd.
                double left = 1.0D - (double) this.age / (double) this.window;
                return Math.max(0.0D, Math.min(START_TEMPERATURE, START_TEMPERATURE * left));
            case HOLD:
                return 0.0D;
            default:
                return Double.NaN;
        }
    }

    /**
     * Advances one server tick.
     *
     * @param collapsing  the core's own collapse flag
     * @param explosionIn seconds until detonation as the core reports it, or a
     *                    negative number when it reports nothing
     */
    public void tick(boolean collapsing, double explosionIn) {
        switch (this.phase) {
            case IDLE:
                if (collapsing) {
                    enter(Phase.AURA, 0);
                }
                return;

            case AURA:
                this.age++;
                if (!collapsing) {
                    // Nothing has been changed yet, so there is nothing to undo.
                    enter(Phase.IDLE, 0);
                    return;
                }
                if (explosionIn > 0.0D && explosionIn <= this.retractTicks / 20.0D) {
                    beginRetract(explosionIn);
                } else if (explosionIn < 0.0D && this.age >= this.fallbackTicks) {
                    // No countdown at all: the aura has run long enough that
                    // waiting for a number that will never arrive is worse than
                    // starting the cooldown.
                    beginRetract(-1.0D);
                }
                return;

            case RETRACT:
                this.age++;
                if (this.age >= this.window) {
                    enter(Phase.HOLD, 0);
                }
                return;

            case HOLD:
                this.age++;
                if (this.age >= Math.max(1, this.holdTicks)) {
                    enter(Phase.DONE, 0);
                }
                return;

            case DONE:
                if (collapsing) {
                    this.calm = 0;
                } else if (++this.calm >= this.rearmTicks) {
                    enter(Phase.IDLE, 0);
                }
                return;

            default:
                enter(Phase.IDLE, 0);
        }
    }

    /** Forgets everything. Called when the monitor stops arming this core. */
    public void release() {
        enter(Phase.IDLE, 0);
    }

    private void beginRetract(double explosionIn) {
        int remaining = explosionIn > 0.0D ? (int) Math.ceil(explosionIn * 20.0D) : this.retractTicks;
        // One tick of headroom: reaching exactly 0 on the tick the core blows
        // is indistinguishable from failing to reach it.
        this.window = Math.max(20, Math.min(this.retractTicks, remaining - 1));
        enter(Phase.RETRACT, 0);
    }

    private void enter(Phase next, int age) {
        this.phase = next;
        this.age = age;
        this.calm = 0;
    }
}

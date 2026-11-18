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

package com.hbmzz.reactor;

/**
 * The reactor's physics and its safety rules, as pure functions.
 *
 * <p>No world, no tile entity, no client, no static mutable state - exactly the
 * discipline of {@code com.hbmzz.rbmk.RbmkTiming}. Every method answers one
 * question about a reactor in isolation, so {@code tools/reactorcheck} can walk
 * a core through a thousand scrams and prove that none of them melt down.</p>
 *
 * <h3>The control loop</h3>
 * <ol>
 *   <li>{@link #targetFlux} turns rod position into a demanded flux, with fuel
 *       and temperature feedback applied. A hotter core asks for less flux:
 *       that negative feedback is what keeps a real reactor controllable.</li>
 *   <li>{@link #nextFlux} eases the actual flux toward the target. Rods cannot
 *       make flux jump - the lag is the difference between a control system and
 *       a light switch.</li>
 *   <li>{@link #nextHeat} subtracts cooling from heat production. Cooling wins
 *       ties, so a reactor with enough flow cannot slowly cook itself.</li>
 *   <li>{@link ReactorMachine} decides the phase from the readings.</li>
 * </ol>
 *
 * <p>All math is guarded against NaN, infinities and negative inputs. A bad
 * reading degrades to a safe value, never to a silent runaway.</p>
 */
public final class ReactorRules {

    private ReactorRules() {
    }

    /**
     * Flux the core is asking for this tick, 0..ratedFlux.
     *
     * <p>Three gates, all of which must pass: rods must be out, fuel must be
     * present, and the core must not be in scram. The temperature term is a
     * multiplier that shrinks toward zero as heat rises - the model's equivalent
     * of Doppler broadening. Clamped at zero so a very hot core asks for no
     * power rather than negative power.</p>
     */
    public static double targetFlux(ReactorInput in, ReactorState state,
                                    ReactorSpec spec, boolean scramming) {
        if (in == null || state == null || spec == null) {
            return 0.0D;
        }
        if (scramming || !in.fuel || state.phase == ReactorPhase.SPENT) {
            return 0.0D;
        }
        if (state.phase == ReactorPhase.OFFLINE && !in.start) {
            return 0.0D;
        }
        double feedback = Math.max(0.0D, 1.0D - state.heat * spec.feedback);
        double demand = spec.ratedFlux * in.reactivity() * feedback;
        return clamp(demand, 0.0D, spec.ratedFlux);
    }

    /**
     * First-order approach to the target. A scram drives flux down faster than
     * a normal ramp: rods dropping are a mechanical action, not a slow thermal
     * one, and a scram that leaves half the power on is not a scram.
     */
    public static double nextFlux(double current, double target, ReactorSpec spec, boolean scramming) {
        if (spec == null) {
            return 0.0D;
        }
        double rate = scramming ? 1.0D : spec.fluxLag;
        double next = current + (target - current) * rate;
        return clamp(next, 0.0D, spec.ratedFlux);
    }

    /**
     * Heat after one tick: production minus cooling minus ambient loss.
     *
     * <p>Cooling is evaluated at the current heat, so a cold core does not lose
     * heat it never had, and a hot core loses proportionally more. The result is
     * floored at zero and capped far above the melt line so a pathological spec
     * cannot overflow into an infinity that poisons later ticks.</p>
     */
    public static double nextHeat(double heat, double flux, double coolant,
                                  ReactorSpec spec, boolean scramming) {
        if (spec == null) {
            return 0.0D;
        }
        double h = clamp(heat, 0.0D, spec.meltHeat * 4.0D + 1.0D);
        double f = clamp(flux, 0.0D, spec.ratedFlux);
        double flow = clamp(coolant, 0.0D, 1.0D);
        // During a scram the coolant valves are forced fully open regardless
        // of what the panel asks for. Safety input outranks operator input.
        if (scramming) {
            flow = 1.0D;
        }
        double produced = spec.heatPerFlux * f;
        double removed = spec.coolPerFlow * flow + spec.ambientLoss * h;
        return clamp(h + produced - removed, 0.0D, spec.meltHeat * 4.0D + 1.0D);
    }

    /** Power delivered this tick. Only an active, non-scramming core exports. */
    public static double power(ReactorPhase phase, double flux, ReactorSpec spec, boolean scramming) {
        if (spec == null || scramming || phase == null || !phase.isActive()) {
            return 0.0D;
        }
        return clamp(spec.ratedOutput * flux / spec.ratedFlux, 0.0D, spec.ratedOutput);
    }

    /** Heat carried away by the coolant loop this tick. */
    public static double steam(double heat, double coolant, ReactorSpec spec, boolean scramming) {
        if (spec == null || heat <= 0.0D) {
            return 0.0D;
        }
        double flow = scramming ? 1.0D : clamp(coolant, 0.0D, 1.0D);
        return Math.max(0.0D, spec.coolPerFlow * flow);
    }

    /** True once the core has crossed the point of no return. */
    public static boolean melted(double heat, ReactorSpec spec) {
        return spec != null && finite(heat) >= spec.meltHeat;
    }

    /** True when the core is hot enough that it must trip itself. */
    public static boolean overTemperature(double heat, ReactorSpec spec) {
        return spec != null && finite(heat) >= spec.scramHeat;
    }

    /**
     * Decide the next phase. Kept separate from the numeric integration so a
     * scram can be reasoned about on its own - the safety property that matters
     * most is "heat never keeps climbing once SCRAM is latched".
     *
     * <p>Priority when several things happen on the same tick: meltdown first,
     * then an explicit or thermal scram, then the normal operating transitions.
     * A core cannot be both RUNNING and SCRAM, and a spent core has no exit.</p>
     */
    public static ReactorPhase nextPhase(ReactorState state, ReactorInput in,
                                         ReactorSpec spec, double nextHeat, double nextFlux) {
        if (state == null || spec == null) {
            return ReactorPhase.SPENT;
        }
        ReactorPhase phase = state.phase;
        if (phase == ReactorPhase.SPENT) {
            return ReactorPhase.SPENT;
        }
        if (melted(nextHeat, spec)) {
            return ReactorPhase.SPENT;
        }
        boolean trip = in != null && in.scram;
        boolean thermal = overTemperature(nextHeat, spec);
        if (trip || thermal) {
            return ReactorPhase.SCRAM;
        }
        switch (phase) {
            case OFFLINE:
                return in != null && in.start && in.fuel ? ReactorPhase.STARTING : ReactorPhase.OFFLINE;
            case STARTING:
                return nextFlux >= spec.ratedFlux * spec.runningBand
                        ? ReactorPhase.RUNNING : ReactorPhase.STARTING;
            case RUNNING:
                return nextFlux <= spec.ratedFlux * spec.runningRelease
                        ? ReactorPhase.STARTING : ReactorPhase.RUNNING;
            case SCRAM:
                // A scram holds until the core is cool and the recovery delay
                // has elapsed, then returns to OFFLINE. It never goes straight
                // back to STARTING: an operator must re-arm it.
                if (nextHeat < spec.scramHeat * 0.5D && state.ticks >= spec.scramRecoverTicks) {
                    return ReactorPhase.OFFLINE;
                }
                return ReactorPhase.SCRAM;
            default:
                return ReactorPhase.SPENT;
        }
    }

    /** Clamp with NaN -> 0, matching the safe-by-default rule of ReactorInput. */
    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }

    private static double finite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? Double.MAX_VALUE : v;
    }
}

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
 * Advances a reactor by exactly one tick. This is the whole engine: state in,
 * state out, no side effects.
 *
 * <p>The method is static and stateless so the same input always yields the
 * same output. That property is the reason the offline check can replay a
 * scram hundreds of times with different specs and trust the result. A tile
 * entity simply holds a {@link ReactorState} and calls
 * {@link #step(ReactorState, ReactorInput, ReactorSpec)} once per tick.</p>
 */
public final class ReactorMachine {

    private ReactorMachine() {
    }

    /**
     * One deterministic tick.
     *
     * <p>Order of operations matters and is fixed: decide whether we are
     * scramming (explicit or thermal) <em>before</em> computing flux, so a trip
     * on this tick cannot still produce a full-power tick's worth of heat;
     * integrate flux and heat; then choose the phase from the new readings.
     * Doing it in any other order produced the classic bug where the tick that
     * trips is also the tick that melts.</p>
     */
    public static ReactorState step(ReactorState state, ReactorInput in, ReactorSpec spec) {
        if (state == null || spec == null) {
            return ReactorState.COLD;
        }
        if (state.phase == ReactorPhase.SPENT) {
            return state;
        }

        boolean scramming = state.phase == ReactorPhase.SCRAM
                || (in != null && in.scram)
                || (in != null && in.fuel && ReactorRules.overTemperature(state.heat, spec));

        double target = ReactorRules.targetFlux(in, state, spec, scramming);
        double flux = ReactorRules.nextFlux(state.flux, target, spec, scramming);
        double heat = ReactorRules.nextHeat(state.heat, flux, in == null ? 0.0D : in.coolant,
                spec, scramming);

        ReactorPhase phase = ReactorRules.nextPhase(state, in, spec, heat, flux);
        int ticks = phase == state.phase ? state.ticks + 1 : 0;
        double produced = state.produced + ReactorRules.power(phase, flux, spec, scramming);
        return state.with(phase, flux, heat, ticks, produced);
    }

    /** Build the observable result for a state, given the input that produced it. */
    public static ReactorOutput observe(ReactorState state, ReactorInput in, ReactorSpec spec) {
        if (state == null || spec == null) {
            return new ReactorOutput(ReactorPhase.SPENT, 0.0D, 0.0D, 0.0D, 0.0D, false, true);
        }
        boolean scramming = state.phase == ReactorPhase.SCRAM
                || (in != null && in.scram);
        double power = ReactorRules.power(state.phase, state.flux, spec, scramming);
        double steam = ReactorRules.steam(state.heat, in == null ? 0.0D : in.coolant, spec, scramming);
        return new ReactorOutput(state.phase, state.flux, state.heat, power, steam,
                scramming, state.phase == ReactorPhase.SPENT);
    }

    /** Step a core by {@code n} ticks with the same input each tick. */
    public static ReactorState run(ReactorState start, ReactorInput in, ReactorSpec spec, int n) {
        ReactorState s = start;
        int count = n < 0 ? 0 : n;
        for (int i = 0; i < count; i++) {
            s = step(s, in, spec);
        }
        return s;
    }
}

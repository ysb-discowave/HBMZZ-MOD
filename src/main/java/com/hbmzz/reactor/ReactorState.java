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
 * Immutable snapshot of a reactor core. The {@code objects} layer of this
 * package; the only thing a tile entity needs to persist.
 *
 * <p>Immutability is not ceremony. The machine returns a new state each tick,
 * so an old reference always describes the past exactly - which is what makes
 * it possible to assert "a spent core never restarts" in an offline check
 * without a world. A mutable state would let a stray holder change history.</p>
 */
public final class ReactorState {

    public static final ReactorState COLD =
            new ReactorState(ReactorPhase.OFFLINE, 0.0D, 0.0D, 0, 0.0D);

    public final ReactorPhase phase;
    /** Neutron flux, 0..ratedFlux. */
    public final double flux;
    /** Core heat, never negative. */
    public final double heat;
    /** Ticks spent in the current phase. */
    public final int ticks;
    /** Energy produced over the core's lifetime, for diagnostics. */
    public final double produced;

    public ReactorState(ReactorPhase phase, double flux, double heat, int ticks, double produced) {
        if (phase == null) {
            throw new IllegalArgumentException("phase must not be null");
        }
        this.phase = phase;
        this.flux = finite(flux, 0.0D, 0.0D, Double.MAX_VALUE);
        this.heat = finite(heat, 0.0D, 0.0D, Double.MAX_VALUE);
        this.ticks = ticks < 0 ? 0 : ticks;
        this.produced = finite(produced, 0.0D, 0.0D, Double.MAX_VALUE);
    }

    /** Return a state with one field replaced; used by the machine. */
    public ReactorState with(ReactorPhase newPhase, double newFlux, double newHeat,
                             int newTicks, double newProduced) {
        return new ReactorState(newPhase, newFlux, newHeat, newTicks, newProduced);
    }

    private static double finite(double v, double fallback, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < lo) {
            return fallback;
        }
        return v > hi ? hi : v;
    }
}

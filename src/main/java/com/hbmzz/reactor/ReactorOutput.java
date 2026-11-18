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
 * The observable result of one reactor tick - the {@code Phenomenon} layer.
 *
 * <p>A pure snapshot, never stored in a tile entity and never synced by itself:
 * the game adapter reads it and decides what to persist or send to clients.
 * Keeping presentation data out of the physics is deliberate - a meltdown is
 * decided here, the smoke and light are somebody else's problem.</p>
 */
public final class ReactorOutput {

    public final ReactorPhase phase;
    /** Neutron flux, 0..ratedFlux. */
    public final double flux;
    /** Core heat, in degrees. */
    public final double heat;
    /** Delivered power, 0..ratedOutput; zero unless the core is active. */
    public final double power;
    /** Steam/coolant enthalpy carried away this tick. */
    public final double steam;
    /** True when a scram is in progress. */
    public final boolean scramming;
    /** True once the core is spent; latches for good. */
    public final boolean melted;

    public ReactorOutput(ReactorPhase phase, double flux, double heat,
                         double power, double steam, boolean scramming, boolean melted) {
        this.phase = phase;
        this.flux = flux;
        this.heat = heat;
        this.power = power;
        this.steam = steam;
        this.scramming = scramming;
        this.melted = melted;
    }

    /** Convenience for callers that only need a redstone strength. */
    public int redstoneLevel() {
        if (power <= 0.0D) {
            return 0;
        }
        return (int) Math.round(15.0D * Math.min(1.0D, power / 15.0D));
    }
}

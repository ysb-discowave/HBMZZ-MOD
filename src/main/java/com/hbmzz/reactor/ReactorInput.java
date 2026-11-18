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
 * Everything that can drive a reactor in one tick, normalised at the boundary.
 *
 * <p>This is the {@code ccio} equivalent in GFBS terms: the outside world
 * (redstone, a control panel, a computer bridge) never pokes the physics. It
 * fills one of these value objects, and {@link ReactorMachine} consumes it.
 * Because the fields are final and clamped in the constructor, a wire carrying
 * a negative signal or a NaN cannot corrupt a running core - a lesson from the
 * redstone gate work, where an out-of-range comparator once latched a door.</p>
 */
public final class ReactorInput {

    /** Control-rod insertion, 0 = fully withdrawn, 100 = fully inserted. */
    public final double rod;
    /** Coolant valve opening, 0 = closed, 1 = fully open. */
    public final double coolant;
    /** Whether fuel is present. No fuel means no chain reaction, full stop. */
    public final boolean fuel;
    /** Edge-triggered start request from a panel or redstone pulse. */
    public final boolean start;
    /** Emergency shutdown request. Takes priority over everything else. */
    public final boolean scram;

    public ReactorInput(double rod, double coolant, boolean fuel, boolean start, boolean scram) {
        this.rod = clamp(rod, 0.0D, 100.0D);
        this.coolant = clamp(coolant, 0.0D, 1.0D);
        this.fuel = fuel;
        this.start = start;
        this.scram = scram;
    }

    /** An inert tick: rods in, no coolant demand, no commands. */
    public static ReactorInput idle() {
        return new ReactorInput(100.0D, 0.0D, false, false, false);
    }

    /** Full-power run request with the valve open. */
    public static ReactorInput run(double rod, double coolant) {
        return new ReactorInput(rod, coolant, true, false, false);
    }

    /**
     * Clamp with NaN/Infinity handling. A NaN is treated as the safe extreme
     * (rods in, valve closed) rather than as zero: on a control line, silence
     * must never look like a run command.
     */
    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) {
            return lo;
        }
        if (v <= lo) {
            return lo;
        }
        if (v >= hi) {
            return hi;
        }
        return v;
    }

    /** Fraction of the chain reaction the rods allow, 0..1. */
    public double reactivity() {
        return 1.0D - rod / 100.0D;
    }
}

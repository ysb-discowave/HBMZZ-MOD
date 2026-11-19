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

package com.hbmzz.reactor;

/**
 * The multi-block reactor core, as pure functions of its surroundings.
 *
 * <p>The assembled reactor is a cluster the player lays down by hand - an
 * emitter here, an absorber there, a stabiliser around it, control rods to
 * throttle. None of that physics is hard-coded into one block: this class turns
 * "how many of each component sits next to the core" into a
 * {@link ReactorSpec} and a {@link ReactorInput}. The decision itself - whether
 * the cluster melts or holds - still lives in {@link ReactorRules}, untouched.</p>
 *
 * <p>Keeping this here (in the pure {@code com.hbmzz.reactor} package, not in
 * the tile entity) is deliberate: {@code tools/reactorcheck} can prove the
 * safety properties of a fully-rigged core - stabiliser count bought enough
 * temperature feedback that a rod failure still trips it - without a world, a
 * tile entity, or a block placed in the ground.</p>
 */
public final class ReactorCoreMath {

    private ReactorCoreMath() {
    }

    /** Largest cluster radius scanned around the core, in blocks. */
    public static final int RADIUS = 1;
    /** One of each component is a sensible minimum; beyond these counts stop paying off. */
    public static final int MAX_EFFECTIVE_COMPONENTS = 16;

    /**
     * Build the spec for a core with the given rig. Every component is a knob
     * on the same base spec, and all four are clamped so an absurd build -
     * three hundred stabilisers - cannot produce an infinite spec.
     *
     * <ul>
     *   <li><b>Emitters</b> raise the rated output and the heat each unit of
     *       flux produces: more fuel, more power, and more waste to manage.</li>
     *   <li><b>Absorbers</b> raise the cooling a full-open valve can carry away:
     *       more surface for the coolant to do its work.</li>
     *   <li><b>Stabilisers</b> raise the temperature feedback coefficient - the
     *       one number that decides whether a hot core pushes back on the chain
     *       reaction or runs away. This is the "temperature coefficient" knob.</li>
     * </ul>
     */
    public static ReactorSpec specFor(int emitters, int absorbers, int stabilisers) {
        int e = clampCount(emitters);
        int a = clampCount(absorbers);
        int s = clampCount(stabilisers);
        ReactorSpec base = ReactorSpec.DEFAULT;
        return ReactorSpec.builder()
                .heatPerFlux(base.heatPerFlux * (1.0D + 0.25D * e))
                .ratedOutput(base.ratedOutput * (1.0D + 0.50D * e))
                .coolPerFlow(base.coolPerFlow * (1.0D + 0.40D * a))
                .feedback(base.feedback * (1.0D + 0.25D * s))
                .build();
    }

    /**
     * Control-rod insertion, 0 (fully withdrawn) .. 100 (fully in), from the
     * rods the player has placed.
     *
     * <p>Each rod's redstone power is its insertion depth. With no rods placed
     * the core runs wide open - a deliberate choice: the rods are the only
     * thing standing between a bare core and a scram, so their absence should
     * be visible, not hidden behind an assumed half-insertion.</p>
     *
     * @param depths the redstone power (0..15) read from each rod position
     */
    public static double rodFrom(int... depths) {
        if (depths == null || depths.length == 0) {
            return 0.0D;
        }
        int sum = 0;
        for (int d : depths) {
            sum += clampDepth(d);
        }
        // More rods give finer, deeper control; one rod at full power already
        // drives the core to a strong insertion.
        double average = sum / (double) depths.length;
        double perRod = 100.0D * average / 15.0D;
        // Diminishing returns past the first few rods, so a hundred rods do not
        // produce "infinitely inserted" rods.
        double saturation = 1.0D - Math.pow(0.75D, Math.min(depths.length, MAX_EFFECTIVE_COMPONENTS));
        return clamp(perRod * saturation, 0.0D, 100.0D);
    }

    /**
     * Coolant valve opening, 0 (closed) .. 1 (wide open), from the absorbers
     * placed around the core. Eight absorbers is a full loop; more buy nothing.
     */
    public static double coolantFrom(int absorbers) {
        int a = clampCount(absorbers);
        return clamp((double) a / 8.0D, 0.0D, 1.0D);
    }

    private static int clampCount(int v) {
        if (v < 0) {
            return 0;
        }
        return v > MAX_EFFECTIVE_COMPONENTS ? MAX_EFFECTIVE_COMPONENTS : v;
    }

    private static int clampDepth(int v) {
        if (v < 0) {
            return 0;
        }
        return v > 15 ? 15 : v;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v) || v < lo) {
            return lo;
        }
        return v > hi ? hi : v;
    }
}

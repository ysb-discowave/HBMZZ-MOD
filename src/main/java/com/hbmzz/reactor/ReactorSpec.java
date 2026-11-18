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
 * Every tunable number of a reactor, in one immutable value.
 *
 * <p>The physics does not read a config file and does not reach for a static
 * singleton. It reads a {@code ReactorSpec}. That is the single most important
 * architectural decision here: it is what lets {@code tools/reactorcheck} build
 * a reactor with a two-degree meltdown line and prove the safety logic in a
 * millisecond, without launching the game or editing a config on disk. It is
 * also what lets a future block expose several core grades simply by handing
 * {@link ReactorMachine} a different spec.</p>
 *
 * <p>Defaults are deliberately conservative. A fresh core trips long before
 * it melts, and a scram always wins over heat production, so the common failure
 * (a player walking away from a running reactor) ends in a safe shutdown rather
 * than a hole in the world.</p>
 */
public final class ReactorSpec {

    /** A ready-to-use core with the shipped balance numbers. */
    public static final ReactorSpec DEFAULT = new Builder().build();

    /** Highest flux the core is designed for. Output power is scaled by this. */
    public final double ratedFlux;
    /** Heat added per unit of flux per tick. */
    public final double heatPerFlux;
    /** Heat removed per unit of coolant flow per tick at full flow. */
    public final double coolPerFlow;
    /** Fraction of stored heat lost to the environment each tick. */
    public final double ambientLoss;
    /** Heat at which the core melts down and becomes SPENT. */
    public final double meltHeat;
    /** Heat at which the core trips itself into SCRAM. */
    public final double scramHeat;
    /** Fraction of rated flux above which a starting core goes RUNNING. */
    public final double runningBand;
    /** Fraction of rated flux below which a running core drops to STARTING. */
    public final double runningRelease;
    /** First-order response rate of flux to a change in rod position (0..1]. */
    public final double fluxLag;
    /** Power output, in arbitrary units, at rated flux. */
    public final double ratedOutput;
    /**
     * Temperature feedback coefficient. Flux is multiplied by
     * {@code 1 - heat * feedback}, clamped non-negative: hot fuel pushes back
     * against the chain reaction. This is the reactor's self-stabilising
     * behaviour and the reason a core does not run away the instant rods move.
     */
    public final double feedback;
    /** Ticks a core must spend cooling below scramHeat before it is OFFLINE. */
    public final int scramRecoverTicks;

    private ReactorSpec(Builder b) {
        this.ratedFlux = b.ratedFlux;
        this.heatPerFlux = b.heatPerFlux;
        this.coolPerFlow = b.coolPerFlow;
        this.ambientLoss = b.ambientLoss;
        this.meltHeat = b.meltHeat;
        this.scramHeat = b.scramHeat;
        this.runningBand = b.runningBand;
        this.runningRelease = b.runningRelease;
        this.fluxLag = b.fluxLag;
        this.ratedOutput = b.ratedOutput;
        this.feedback = b.feedback;
        this.scramRecoverTicks = b.scramRecoverTicks;
    }

    /**
     * A copy of this spec with different numbers. Used by tests and by any
     * block that wants a harder or gentler core without subclassing physics.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builder with validation, so an impossible spec cannot reach the machine. */
    public static final class Builder {

        private double ratedFlux = 100.0D;
        private double heatPerFlux = 4.0D;
        private double coolPerFlow = 520.0D;
        private double ambientLoss = 0.010D;
        private double meltHeat = 1000.0D;
        private double scramHeat = 700.0D;
        private double runningBand = 0.90D;
        private double runningRelease = 0.80D;
        private double fluxLag = 0.08D;
        private double ratedOutput = 128.0D;
        private double feedback = 0.00045D;
        private int scramRecoverTicks = 200;

        private Builder() {
        }

        public Builder ratedFlux(double v) { this.ratedFlux = v; return this; }
        public Builder heatPerFlux(double v) { this.heatPerFlux = v; return this; }
        public Builder coolPerFlow(double v) { this.coolPerFlow = v; return this; }
        public Builder ambientLoss(double v) { this.ambientLoss = v; return this; }
        public Builder meltHeat(double v) { this.meltHeat = v; return this; }
        public Builder scramHeat(double v) { this.scramHeat = v; return this; }
        public Builder runningBand(double v) { this.runningBand = v; return this; }
        public Builder runningRelease(double v) { this.runningRelease = v; return this; }
        public Builder fluxLag(double v) { this.fluxLag = v; return this; }
        public Builder ratedOutput(double v) { this.ratedOutput = v; return this; }
        public Builder feedback(double v) { this.feedback = v; return this; }
        public Builder scramRecoverTicks(int v) { this.scramRecoverTicks = v; return this; }

        /**
         * Rejects nonsense rather than letting it become a subtle gameplay bug.
         * In particular a scram line at or above the melt line is useless: the
         * core would melt in the same tick it decided to shut down.
         */
        public ReactorSpec build() {
            requireFinite(ratedFlux, "ratedFlux");
            requireFinite(heatPerFlux, "heatPerFlux");
            requireFinite(coolPerFlow, "coolPerFlow");
            requireFinite(ambientLoss, "ambientLoss");
            requireFinite(meltHeat, "meltHeat");
            requireFinite(scramHeat, "scramHeat");
            requireFinite(runningBand, "runningBand");
            requireFinite(runningRelease, "runningRelease");
            requireFinite(fluxLag, "fluxLag");
            requireFinite(ratedOutput, "ratedOutput");
            requireFinite(feedback, "feedback");
            if (ratedFlux <= 0.0D) {
                throw new IllegalArgumentException("ratedFlux must be positive");
            }
            if (meltHeat <= 0.0D) {
                throw new IllegalArgumentException("meltHeat must be positive");
            }
            if (scramHeat <= 0.0D || scramHeat >= meltHeat) {
                throw new IllegalArgumentException("scramHeat must be in (0, meltHeat)");
            }
            if (runningBand <= 0.0D || runningBand > 1.0D) {
                throw new IllegalArgumentException("runningBand must be in (0, 1]");
            }
            if (runningRelease <= 0.0D || runningRelease > runningBand) {
                throw new IllegalArgumentException("runningRelease must be in (0, runningBand]");
            }
            if (fluxLag <= 0.0D || fluxLag > 1.0D) {
                throw new IllegalArgumentException("fluxLag must be in (0, 1]");
            }
            if (heatPerFlux < 0.0D || coolPerFlow < 0.0D || ambientLoss < 0.0D
                    || feedback < 0.0D || ratedOutput < 0.0D) {
                throw new IllegalArgumentException("rates and output must be non-negative");
            }
            if (scramRecoverTicks < 0) {
                throw new IllegalArgumentException("scramRecoverTicks must be non-negative");
            }
            return new ReactorSpec(this);
        }

        private static void requireFinite(double v, String name) {
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }
    }
}

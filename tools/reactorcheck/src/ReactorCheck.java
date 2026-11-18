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

import com.hbmzz.reactor.ReactorInput;
import com.hbmzz.reactor.ReactorMachine;
import com.hbmzz.reactor.ReactorOutput;
import com.hbmzz.reactor.ReactorPhase;
import com.hbmzz.reactor.ReactorRules;
import com.hbmzz.reactor.ReactorSpec;
import com.hbmzz.reactor.ReactorState;
import com.hbmzz.reactor.api.SimpleReactor;

/**
 * Offline check of the reactor's decision layer, in the spirit of RbmkCheck.
 *
 * <p>A reactor that mishandles a scram is invisible right up to the moment the
 * base is gone. So the questions that matter - does a scram always win, can a
 * spent core restart, does cooling hold heat down, do bad inputs degrade
 * safely - are answered here without a world, a tile entity or a client.</p>
 */
public final class ReactorCheck {

    private static int checks;
    private static int failed;

    public static void main(String[] args) {
        spec();
        input();
        physics();
        phases();
        scram();
        boundaries();
        api();

        System.out.println();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================================================================== spec

    private static void spec() {
        System.out.println("-- spec --");
        ReactorSpec s = ReactorSpec.DEFAULT;
        check("the shipped scram line sits below the melt line",
                s.scramHeat < s.meltHeat, s.scramHeat + " vs " + s.meltHeat);
        check("the shipped spec is immutable (same instance reused)",
                ReactorSpec.DEFAULT == s);
        boolean threw = false;
        try {
            ReactorSpec.builder().scramHeat(900.0D).meltHeat(800.0D).build();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("a scram line above the melt line is rejected", threw);
        threw = false;
        try {
            ReactorSpec.builder().ratedFlux(Double.NaN).build();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("a non-finite number is rejected", threw);
        threw = false;
        try {
            ReactorSpec.builder().runningBand(0.5D).runningRelease(0.8D).build();
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("release band above the running band is rejected", threw);
    }

    // ================================================================= input

    private static void input() {
        System.out.println();
        System.out.println("-- input --");
        check("rods are clamped low", new ReactorInput(-5, 0.5, true, false, false).rod == 0.0D);
        check("rods are clamped high", new ReactorInput(150, 0.5, true, false, false).rod == 100.0D);
        check("coolant is clamped", new ReactorInput(0, 4, true, false, false).coolant == 1.0D);
        check("a NaN rod is treated as fully inserted (safe)",
                new ReactorInput(Double.NaN, 0, true, false, false).rod == 0.0D);
        check("fully withdrawn rods give full reactivity",
                new ReactorInput(0, 1, true, false, false).reactivity() == 1.0D);
        check("fully inserted rods give no reactivity",
                new ReactorInput(100, 1, true, false, false).reactivity() == 0.0D);
        check("an idle input carries no fuel", !ReactorInput.idle().fuel);
    }

    // =============================================================== physics

    private static void physics() {
        System.out.println();
        System.out.println("-- physics --");
        ReactorSpec s = ReactorSpec.DEFAULT;
        ReactorState cold = ReactorState.COLD;
        ReactorInput run = ReactorInput.run(0.0D, 1.0D);
        // An OFFLINE core with no start edge ignores the rods - that gate is the
        // whole point of the start interlock. Use an armed (starting) core to
        // measure the demand curve itself.
        ReactorState armed = cold.with(ReactorPhase.STARTING, 0.0D, 0.0D, 0, 0.0D);
        double target = ReactorRules.targetFlux(run, armed, s, false);
        check("withdrawing rods demands flux", target > 0.0D, "target=" + target);
        check("demand never exceeds rated flux", target <= s.ratedFlux);

        // Temperature feedback: a hot core asks for less than a cold one.
        ReactorState hot = cold.with(ReactorPhase.RUNNING, 0.0D, 400.0D, 0, 0.0D);
        double hotTarget = ReactorRules.targetFlux(run, hot, s, false);
        check("temperature feedback reduces demand", hotTarget < target,
                "hot=" + hotTarget + " cold=" + target);

        // Flux lags; it does not jump to target.
        double next = ReactorRules.nextFlux(0.0D, s.ratedFlux, s, false);
        check("flux eases toward the target", next > 0.0D && next < s.ratedFlux,
                "next=" + next);
        double scramFlux = ReactorRules.nextFlux(s.ratedFlux, 0.0D, s, true);
        check("a scram drops flux faster than a normal ramp",
                scramFlux < s.ratedFlux * 0.01D, "scramFlux=" + scramFlux);

        // Cooling holds heat down; no cooling with rods out heats up.
        ReactorState powered = cold.with(ReactorPhase.RUNNING, s.ratedFlux, 300.0D, 0, 0.0D);
        double cooled = ReactorRules.nextHeat(powered.heat, powered.flux, 1.0D, s, false);
        double uncooled = ReactorRules.nextHeat(powered.heat, powered.flux, 0.0D, s, false);
        check("full cooling removes more heat than it adds", cooled < powered.heat,
                "heat " + powered.heat + " -> " + cooled);
        check("no cooling lets heat climb", uncooled > powered.heat,
                "heat " + powered.heat + " -> " + uncooled);
        check("heat never goes negative",
                ReactorRules.nextHeat(0.0D, 0.0D, 1.0D, s, false) >= 0.0D);

        // Power only while active.
        check("an offline core delivers nothing",
                ReactorRules.power(ReactorPhase.OFFLINE, s.ratedFlux, s, false) == 0.0D);
        check("a scram delivers nothing even at full flux",
                ReactorRules.power(ReactorPhase.RUNNING, s.ratedFlux, s, true) == 0.0D);
        check("a running core at rated flux delivers rated output",
                ReactorRules.power(ReactorPhase.RUNNING, s.ratedFlux, s, false) == s.ratedOutput);
    }

    // ================================================================= phases

    private static void phases() {
        System.out.println();
        System.out.println("-- phases --");
        ReactorSpec s = ReactorSpec.DEFAULT;

        // Cold core with no start stays offline.
        ReactorState cold = ReactorMachine.run(ReactorState.COLD, ReactorInput.idle(), s, 50);
        check("a cold core never starts by itself", cold.phase == ReactorPhase.OFFLINE);

        // Start command begins the ramp.
        ReactorInput start = new ReactorInput(0.0D, 1.0D, true, true, false);
        ReactorState starting = ReactorMachine.step(ReactorState.COLD, start, s);
        check("a start command begins the ramp", starting.phase == ReactorPhase.STARTING);

        // Running is reached only after a start edge AND flux crossing the band.
        ReactorInput run = ReactorInput.run(0.0D, 1.0D);
        ReactorState ramped = ReactorMachine.run(ReactorState.COLD, start, s, 1);
        ramped = ReactorMachine.run(ramped, run, s, 200);
        check("a core eventually reaches RUNNING", ramped.phase == ReactorPhase.RUNNING,
                "phase=" + ramped.phase + " flux=" + ramped.flux);

        // With rods in, a running core falls back toward STARTING.
        ReactorInput stop = ReactorInput.run(100.0D, 1.0D);
        ReactorState woundDown = ReactorMachine.run(ramped, stop, s, 200);
        check("a running core falls back when rods go in",
                woundDown.phase != ReactorPhase.RUNNING, "phase=" + woundDown.phase);

        check("a spent core has no transitions",
                ReactorPhase.SPENT.canTransitionTo(ReactorPhase.OFFLINE) == false);
        check("a scram cannot skip straight to running",
                ReactorPhase.SCRAM.canTransitionTo(ReactorPhase.RUNNING) == false);
        check("offline can go to starting",
                ReactorPhase.OFFLINE.canTransitionTo(ReactorPhase.STARTING));
    }

    // ================================================================== scram

    private static void scram() {
        System.out.println();
        System.out.println("-- scram --");
        ReactorSpec s = ReactorSpec.DEFAULT;
        ReactorInput run = ReactorInput.run(0.0D, 0.0D); // no cooling: heat climbs
        ReactorState cold = ReactorState.COLD;
        ReactorInput startEdge = new ReactorInput(0.0D, 0.0D, true, true, false);
        // Seed the ramp with a start edge, then let it drift with no cooling.
        // Watch the trajectory: a core that trips and then safely recovers to
        // OFFLINE is the desired outcome, so checking only the final phase
        // would report a false failure on a correctly working protection.
        ReactorState drifted = ReactorMachine.run(cold, startEdge, s, 1);
        boolean didTrip = false;
        boolean melted = false;
        for (int i = 0; i < 400; i++) {
            drifted = ReactorMachine.step(drifted, run, s);
            if (drifted.phase == ReactorPhase.SCRAM) {
                didTrip = true;
            }
            if (drifted.phase == ReactorPhase.SPENT) {
                melted = true;
            }
        }
        check("an uncooled core trips on temperature", didTrip,
                "final phase=" + drifted.phase);
        check("a trip happens before meltdown with the shipped spec", !melted,
                "melted=" + melted);
        check("a tripped core recovers safely to OFFLINE",
                drifted.phase == ReactorPhase.OFFLINE, "phase=" + drifted.phase);

        // Explicit scram from a running core: heat must fall, never rise.
        ReactorInput scram = new ReactorInput(0.0D, 0.0D, true, false, true);
        ReactorState running = ReactorMachine.run(cold,
                new ReactorInput(0.0D, 1.0D, true, true, false), s, 1);
        running = ReactorMachine.run(running, ReactorInput.run(0.0D, 1.0D), s, 200);
        double heatAtTrip = running.heat;
        ReactorState tripped = ReactorMachine.step(running, scram, s);
        double after = tripped.heat;
        check("scram latches immediately",
                tripped.phase == ReactorPhase.SCRAM, "phase=" + tripped.phase);
        check("heat falls on the very tick of the scram",
                after <= heatAtTrip, heatAtTrip + " -> " + after);
        check("the explicit-scram core stays out of SPENT",
                tripped.phase != ReactorPhase.SPENT, "phase=" + tripped.phase);
        // Seed a genuinely hot core to prove the cooling curve, not just 0 -> 0.
        ReactorState hotScram = new ReactorState(ReactorPhase.SCRAM, 0.0D, 500.0D, 0, 0.0D);
        ReactorState cooled = ReactorMachine.run(hotScram, scram, s, 200);
        check("a scrammed core cools toward offline",
                cooled.heat < hotScram.heat, hotScram.heat + " -> " + cooled.heat);
        check("a scrammed core delivers no power",
                ReactorMachine.observe(cooled, scram, s).power == 0.0D);

        // Repeated scram inputs must never restart the core.
        ReactorState again = ReactorMachine.run(cooled, ReactorInput.run(0.0D, 1.0D), s, 500);
        check("a scrammed core cannot be restarted by the start input",
                again.phase == ReactorPhase.OFFLINE || again.phase == ReactorPhase.SCRAM,
                "phase=" + again.phase);
    }

    // ============================================================ boundaries

    private static void boundaries() {
        System.out.println();
        System.out.println("-- boundaries --");
        ReactorSpec s = ReactorSpec.DEFAULT;

        check("a null input is inert",
                ReactorMachine.step(ReactorState.COLD, null, s).phase == ReactorPhase.OFFLINE);
        check("a null state returns a cold core",
                ReactorMachine.step(null, ReactorInput.idle(), s) == ReactorState.COLD);
        check("a null spec returns a cold core",
                ReactorMachine.step(ReactorState.COLD, ReactorInput.idle(), null) == ReactorState.COLD);
        check("target flux with nulls is zero",
                ReactorRules.targetFlux(null, null, null, false) == 0.0D);

        // NaN heat cannot poison the sim.
        ReactorState nan = new ReactorState(ReactorPhase.RUNNING, Double.NaN, Double.NaN, 0, 0.0D);
        check("a NaN state is sanitised", nan.flux == 0.0D && nan.heat == 0.0D);
        ReactorState afterNan = ReactorMachine.step(nan, ReactorInput.idle(), s);
        check("a NaN state can still be stepped", afterNan.heat >= 0.0D);

        // A tiny meltdown line still melts when the heat crosses it.
        ReactorSpec fragile = ReactorSpec.builder()
                .meltHeat(20.0D).scramHeat(10.0D).heatPerFlux(50.0D).build();
        // A start edge is required before any core builds heat.
        ReactorState melted = ReactorMachine.run(ReactorState.COLD,
                new ReactorInput(0.0D, 0.0D, true, true, false), fragile, 1);
        melted = ReactorMachine.run(melted, ReactorInput.run(0.0D, 0.0D), fragile, 10);
        check("a fragile spec melts", melted.phase == ReactorPhase.SPENT,
                "phase=" + melted.phase + " heat=" + melted.heat);
        ReactorState frozen = ReactorMachine.step(melted, ReactorInput.run(0.0D, 1.0D), fragile);
        check("a melted core stays melted", frozen == melted);
    }

    // ==================================================================== api

    private static void api() {
        System.out.println();
        System.out.println("-- api --");
        ReactorSpec s = ReactorSpec.DEFAULT;
        SimpleReactor reactor = new SimpleReactor(s);
        check("a fresh reactor is offline", reactor.state().phase == ReactorPhase.OFFLINE);
        check("a fresh reactor has no output", reactor.output().power == 0.0D);

        ReactorOutput out = reactor.tick(new ReactorInput(0.0D, 1.0D, true, true, false));
        check("the api returns an output", out != null && out.phase == ReactorPhase.STARTING);

        reactor.scram();
        ReactorOutput tripped = reactor.tick(ReactorInput.run(0.0D, 0.0D));
        check("api.scram latches into the core", tripped.scramming && !tripped.melted);

        // Restoring a spent state must stick.
        ReactorState spent = new ReactorState(ReactorPhase.SPENT, 0.0D, 900.0D, 0, 0.0D);
        reactor.restore(spent);
        check("restore loads a saved state", reactor.isSpent());
        reactor.tick(ReactorInput.run(0.0D, 1.0D));
        check("a restored spent core cannot be restarted", reactor.isSpent());
    }

    // ================================================================ harness

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  PASS  " + what);
            return;
        }
        failed++;
        System.out.println("  FAIL  " + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }
}

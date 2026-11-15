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

import com.hbmzz.dfc.DfcShutdownPlan;
import com.hbmzz.dfc.DfcShutdownPlan.Phase;

/**
 * Steps the auto-shutdown plan through every edge it has, with no world.
 *
 * <p>{@link DfcShutdownPlan} deliberately imports nothing from Minecraft, so
 * the minute-long sequence can be replayed here in microseconds. What is being
 * asserted is the part that is impossible to eyeball in game: that the ramp
 * never goes back up, that it reaches exactly zero rather than almost zero,
 * and that aborting during the aura and aborting during the retraction are
 * different things.</p>
 *
 * <p>It does not test the parts that only exist in a world - the field write,
 * the packets, the sound. Those are one step each and are listed in the README
 * as "needs a real DFC".</p>
 */
public final class DfcCheck {

    private static int passed;
    private static int failed;

    private DfcCheck() {
    }

    public static void main(String[] args) {
        idleByDefault();
        auraUntilTheLastMinute();
        retractStartsAtFifty();
        rampIsMonotonicAndEndsAtZero();
        holdThenDone();
        doneWaitsForCalm();
        auraAborts();
        retractIsCommitted();
        fallbackWhenTheCoreCannotCount();
        shortWindowFollowsTheCountdown();

        System.out.println();
        System.out.println("dfccheck: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void idleByDefault() {
        DfcShutdownPlan plan = plan();
        plan.tick(false, -1.0D);
        check("idle core stays IDLE", plan.phase() == Phase.IDLE);
        check("idle core writes nothing", Double.isNaN(plan.targetTemperature()));
    }

    private static void auraUntilTheLastMinute() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 300.0D);
        check("collapse enters AURA", plan.phase() == Phase.AURA);
        for (int i = 0; i < 200; i++) {
            plan.tick(true, 300.0D);
        }
        check("AURA holds while a minute remains", plan.phase() == Phase.AURA);
        check("AURA does not touch the temperature", Double.isNaN(plan.targetTemperature()));
    }

    private static void retractStartsAtFifty() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 60.0D);
        plan.tick(true, 60.0D);
        check("60 s remaining enters RETRACT", plan.phase() == Phase.RETRACT);
        check("retraction starts at 50 C", Math.abs(plan.targetTemperature() - 50.0D) < 1.0E-6D);
        check("window is one minute of ticks", plan.window() > 1100 && plan.window() <= 1200);
    }

    private static void rampIsMonotonicAndEndsAtZero() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 60.0D);
        plan.tick(true, 60.0D);
        double last = plan.targetTemperature();
        boolean monotonic = true;
        boolean negative = false;
        for (int i = 0; i < 1200 && plan.phase() == Phase.RETRACT; i++) {
            plan.tick(true, Math.max(1.0D, 60.0D - i / 20.0D));
            double now = plan.targetTemperature();
            if (now > last + 1.0E-9D) {
                monotonic = false;
            }
            if (now < 0.0D) {
                negative = true;
            }
            last = now;
        }
        check("ramp never goes back up", monotonic);
        check("ramp never goes below zero", !negative);
        check("last ramp step is within a degree of zero", last >= 0.0D && last < 1.0D);
        check("ramp ends in HOLD", plan.phase() == Phase.HOLD);
        check("HOLD writes exactly zero", plan.targetTemperature() == 0.0D);
    }

    private static void holdThenDone() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 60.0D);
        plan.tick(true, 60.0D);
        while (plan.phase() == Phase.RETRACT) {
            plan.tick(true, 60.0D);
        }
        check("HOLD pins zero", plan.phase() == Phase.HOLD && plan.targetTemperature() == 0.0D);
        for (int i = 0; i < 100; i++) {
            plan.tick(true, 60.0D);
        }
        check("HOLD ends in DONE", plan.phase() == Phase.DONE);
        check("DONE writes nothing", Double.isNaN(plan.targetTemperature()));
    }

    private static void doneWaitsForCalm() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 60.0D);
        plan.tick(true, 60.0D);
        while (plan.phase() != Phase.DONE) {
            plan.tick(true, 60.0D);
        }
        for (int i = 0; i < 199; i++) {
            plan.tick(false, -1.0D);
        }
        check("one flicker of calm does not re-arm", plan.phase() == Phase.DONE);
        plan.tick(false, -1.0D);
        check("a sustained calm re-arms", plan.phase() == Phase.IDLE);
    }

    private static void auraAborts() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 300.0D);
        plan.tick(false, -1.0D);
        check("AURA aborts when the collapse clears", plan.phase() == Phase.IDLE);
    }

    private static void retractIsCommitted() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 60.0D);
        plan.tick(true, 60.0D);
        for (int i = 0; i < 10; i++) {
            plan.tick(false, -1.0D);
        }
        check("RETRACT survives the collapse clearing", plan.phase() == Phase.RETRACT);
        check("RETRACT keeps cooling after the collapse clears", plan.targetTemperature() > 0.0D);
    }

    private static void fallbackWhenTheCoreCannotCount() {
        DfcShutdownPlan plan = new DfcShutdownPlan(1200, 100, 100, 200);
        plan.tick(true, -1.0D);
        for (int i = 0; i < 99; i++) {
            plan.tick(true, -1.0D);
        }
        check("no countdown keeps the aura until the fallback", plan.phase() == Phase.AURA);
        plan.tick(true, -1.0D);
        check("fallback starts the retraction anyway", plan.phase() == Phase.RETRACT);
        check("fallback uses the full window", plan.window() > 1100);
    }

    private static void shortWindowFollowsTheCountdown() {
        DfcShutdownPlan plan = plan();
        plan.tick(true, 30.0D);
        plan.tick(true, 30.0D);
        check("a late arm uses the remaining time", plan.phase() == Phase.RETRACT
                && plan.window() > 520 && plan.window() < 600);
        check("a late arm still starts at 50 C", Math.abs(plan.targetTemperature() - 50.0D) < 1.0E-6D);
    }

    private static DfcShutdownPlan plan() {
        return new DfcShutdownPlan(1200, 100, 1200, 200);
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }
}

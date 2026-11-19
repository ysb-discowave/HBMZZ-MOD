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

import com.hbmzz.dfc.DfcAutoStopPlan;
import com.hbmzz.dfc.DfcAutoStopPlan.State;
import com.hbmzz.dfc.DfcShutdownArms;
import com.hbmzz.dfc.DfcShutdownPlan;
import com.hbmzz.dfc.DfcShutdownPlan.Phase;

import java.util.function.Supplier;

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
        twoMonitorsShareOneCore();
        sameTickSecondMonitorIsANoop();
        abortSticksUntilRearmed();
        rebindDropsTheOldCore();
        writeStreakResetsOnSuccess();
        stopAttemptClaimsOnce();
        autoStopTerminologyAndFlow();

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

    // ------------------------------------------------------- arm / session ownership

    private static Supplier<DfcShutdownPlan> factory() {
        return new Supplier<DfcShutdownPlan>() {
            @Override
            public DfcShutdownPlan get() {
                return plan();
            }
        };
    }

    private static void twoMonitorsShareOneCore() {
        DfcShutdownArms.clear();
        DfcShutdownPlan first = DfcShutdownArms.begin("m1", "core", 1L, factory());
        check("first monitor gets a plan", first != null);
        first.tick(true, 300.0D);
        check("shared plan entered AURA", first.phase() == Phase.AURA && first.age() == 0);

        DfcShutdownPlan second = DfcShutdownArms.begin("m2", "core", 2L, factory());
        check("second monitor gets the same plan", second == first);
        second.tick(true, 300.0D);
        check("same plan keeps advancing", first.age() == 1);

        DfcShutdownArms.release("m1");
        check("releasing one monitor keeps the session",
                DfcShutdownArms.sessionCount() == 1 && DfcShutdownArms.armed("m2", "core"));
        DfcShutdownPlan survivor = DfcShutdownArms.begin("m2", "core", 3L, factory());
        check("survivor gets the same plan", survivor == first);
        survivor.tick(true, 300.0D);
        check("survivor continues without restarting",
                survivor.age() == 2 && survivor.phase() == Phase.AURA);

        DfcShutdownArms.release("m2");
        check("last release deletes the session", DfcShutdownArms.sessionCount() == 0);
        DfcShutdownArms.clear();
    }

    private static void sameTickSecondMonitorIsANoop() {
        DfcShutdownArms.clear();
        DfcShutdownPlan first = DfcShutdownArms.begin("m1", "core", 7L, factory());
        DfcShutdownPlan second = DfcShutdownArms.begin("m2", "core", 7L, factory());
        check("second monitor on the same tick is a no-op", first != null && second == null);
        check("both monitors are still armed",
                DfcShutdownArms.armed("m1", "core") && DfcShutdownArms.armed("m2", "core"));
        DfcShutdownArms.clear();
    }

    private static void abortSticksUntilRearmed() {
        DfcShutdownArms.clear();
        DfcShutdownPlan plan = DfcShutdownArms.begin("m1", "core", 1L, factory());
        plan.tick(true, 300.0D);
        DfcShutdownArms.abort("m1");
        check("aborted monitor cannot advance",
                DfcShutdownArms.begin("m1", "core", 2L, factory()) == null
                        && DfcShutdownArms.aborted("m1"));
        check("aborted core reports IDLE", "IDLE".equals(DfcShutdownArms.phase("core"))
                && !DfcShutdownArms.active("core"));

        DfcShutdownArms.release("m1");
        DfcShutdownPlan fresh = DfcShutdownArms.begin("m1", "core", 3L, factory());
        check("re-arming after release starts fresh",
                fresh != null && fresh != plan && fresh.phase() == Phase.IDLE);
        DfcShutdownArms.clear();
    }

    private static void rebindDropsTheOldCore() {
        DfcShutdownArms.clear();
        DfcShutdownPlan old = DfcShutdownArms.begin("m1", "A", 1L, factory());
        old.tick(true, 300.0D);
        DfcShutdownPlan next = DfcShutdownArms.begin("m1", "B", 2L, factory());
        check("rebind gives a fresh plan", next != null && next != old && next.phase() == Phase.IDLE);
        check("old core session is dropped",
                DfcShutdownArms.sessionCount() == 1
                        && "IDLE".equals(DfcShutdownArms.phase("A"))
                        && DfcShutdownArms.armed("m1", "B"));
        DfcShutdownArms.clear();
    }

    private static void writeStreakResetsOnSuccess() {
        DfcShutdownArms.clear();
        DfcShutdownArms.begin("m1", "core", 1L, factory());
        DfcShutdownArms.noteWrite("m1", false);
        DfcShutdownArms.noteWrite("m1", false);
        check("a successful write clears the streak",
                DfcShutdownArms.noteWrite("m1", true) == 0
                        && DfcShutdownArms.noteWrite("m1", false) == 1);
        DfcShutdownArms.clear();
    }

    private static void stopAttemptClaimsOnce() {
        DfcShutdownArms.clear();
        DfcShutdownArms.begin("m1", "core", 1L, factory());
        check("stop attempt claims only once",
                DfcShutdownArms.claimStopAttempt("m1") && !DfcShutdownArms.claimStopAttempt("m1"));
        DfcShutdownArms.clear();
    }

    /** 温度驱动的自动停堆 / 强制抑制：术语、触发、特效后瞬时归零。 */
    private static void autoStopTerminologyAndFlow() {
        DfcAutoStopPlan p = new DfcAutoStopPlan(250, 3, 5);

        // 5000C 恰好不触发（严格大于），正常显示"停堆"
        p.tick(5000.0D, true);
        check("auto stop: 5000C does not trigger", p.state() == State.STOPPED);
        check("auto stop: 停堆 label", "停堆".equals(p.displayState()));

        // 超过阈值且正在破坏 -> 立即自动停堆
        p.tick(5100.0D, true);
        check("auto stop: hot+destroying enters immediately", p.state() == State.AUTO_STOP);
        check("auto stop: 自动停堆 label", "自动停堆".equals(p.displayState()));
        check("auto stop: entered flag for music", p.enteredAutoStop());
        check("auto stop: no pause/hold label",
                !"停顿".equals(p.displayState()) && !"暂停".equals(p.displayState()));

        // 热但没破坏 -> 不触发
        DfcAutoStopPlan idle = new DfcAutoStopPlan(250, 3, 5);
        idle.tick(9000.0D, false);
        check("auto stop: hot but not destroying stays stopped", idle.state() == State.STOPPED);

        // 逐步降温：每 tick 一个步长
        Double cooled = p.desiredTemperature();
        check("auto stop: cools one step per tick",
                cooled != null && Math.abs(cooled - (5100.0D - 250.0D)) < 1.0E-6D);

        // 降温后仍高于阈值 -> 强制抑制
        DfcAutoStopPlan s = new DfcAutoStopPlan(10, 3, 5);
        s.tick(9000.0D, true);
        s.tick(9000.0D, true);
        s.tick(9000.0D, true);
        s.tick(9000.0D, true);
        check("suppress: still hot after ramp enters", s.state() == State.FORCE_SUPPRESS);
        check("suppress: 强制抑制 label", "强制抑制".equals(s.displayState()));

        // 特效期间不降温（不是持续降温）
        boolean noCoolDuringFx = true;
        for (int i = 0; i < 4; i++) {
            s.tick(9000.0D, true);
            if (s.desiredTemperature() != null) {
                noCoolDuringFx = false;
            }
        }
        check("suppress: effect plays without gradual cooling", noCoolDuringFx && s.emittingSuppressFx());

        // 特效结束那一 tick 立即归零（不是无过程的直接归零）
        s.tick(9000.0D, true);
        Double zero = s.desiredTemperature();
        check("suppress: instant zero when effect ends",
                zero != null && zero == 0.0D && s.zeroWrittenThisTick());

        // 降回阈值以下 -> 停堆，不进抑制
        DfcAutoStopPlan safe = new DfcAutoStopPlan(3000, 3, 5);
        safe.tick(9000.0D, true);
        safe.tick(4000.0D, false);
        check("auto stop: cooled below threshold stops, no suppression",
                safe.state() == State.STOPPED);
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

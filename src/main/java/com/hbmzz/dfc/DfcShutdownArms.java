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

package com.hbmzz.dfc;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Owns the relationship between armed monitors and the cores they watch.
 *
 * <h3>Two different ownerships</h3>
 * <p>The arm switch belongs to a <em>monitor</em>; the procedure belongs to a
 * <em>core</em>. Conflating the two was the original bug: sessions were keyed
 * by core, so switching off one of two screens watching the same core deleted
 * the shared session and restarted the other screen's cooldown from zero.</p>
 *
 * <ul>
 *   <li>{@code ARMS} maps a monitor to the core it currently drives. Switching
 *       one monitor off only drops its own entry.</li>
 *   <li>{@code SESSIONS} maps a core to one shared {@link DfcShutdownPlan}.
 *       Two monitors on the same core advance the same plan, so the ramp is
 *       never doubled and a change of which monitor ticks first cannot send
 *       the temperature backwards.</li>
 * </ul>
 * <p>The session is deleted only when the last monitor armed on that core
 * releases it. This class has no Minecraft types on purpose, so the whole
 * ownership rule can be replayed offline by {@code tools/dfccheck}.</p>
 */
public final class DfcShutdownArms {

    private static final Map<String, String> ARMS = new HashMap<String, String>();
    private static final Map<String, Session> SESSIONS = new HashMap<String, Session>();

    private DfcShutdownArms() {
    }

    /**
     * Registers {@code monitorKey} as driving {@code coreKey} and returns the
     * core's shared plan when this call should advance it.
     *
     * @return the plan to tick, or {@code null} when another monitor already
     *         advanced this core on the same tick, or when the session was
     *         aborted by a failed temperature write. Use {@link #aborted(String)}
     *         to tell the two apart.
     */
    public static DfcShutdownPlan begin(String monitorKey, String coreKey, long now,
                                        Supplier<DfcShutdownPlan> factory) {
        String previous = ARMS.put(monitorKey, coreKey);
        if (previous != null && !previous.equals(coreKey)) {
            // The monitor was rebound while still armed. Drop its claim on the
            // old core; it must not keep that cooldown alive by accident.
            drop(previous);
        }
        Session session = SESSIONS.get(coreKey);
        if (session == null) {
            session = new Session(factory.get());
            SESSIONS.put(coreKey, session);
        }
        if (session.aborted || session.lastTick == now) {
            return null;
        }
        session.lastTick = now;
        return session.plan;
    }

    /**
     * Removes one monitor's arm. The shared plan survives while another
     * monitor is still armed on the same core.
     */
    public static void release(String monitorKey) {
        String coreKey = ARMS.remove(monitorKey);
        if (coreKey != null) {
            drop(coreKey);
        }
    }

    /** Deletes a core's session only when no monitor still arms it. */
    private static void drop(String coreKey) {
        for (String armed : ARMS.values()) {
            if (coreKey.equals(armed)) {
                return;
            }
        }
        Session session = SESSIONS.remove(coreKey);
        if (session != null) {
            session.plan.release();
        }
    }

    /**
     * Gives up on a core whose temperature cannot be written. The plan is
     * reset and will never advance again until every monitor on that core is
     * released and re-arms it.
     */
    public static void abort(String monitorKey) {
        Session session = sessionOf(monitorKey);
        if (session != null) {
            session.aborted = true;
            session.plan.release();
        }
    }

    /** True when this monitor's core was aborted by a failed write. */
    public static boolean aborted(String monitorKey) {
        Session session = sessionOf(monitorKey);
        return session != null && session.aborted;
    }

    /** True once, so a core only attempts to clear its collapse once. */
    public static boolean claimStopAttempt(String monitorKey) {
        Session session = sessionOf(monitorKey);
        if (session == null || session.stopAttempted) {
            return false;
        }
        session.stopAttempted = true;
        return true;
    }

    /** Records a temperature write; returns the run of consecutive failures. */
    public static int noteWrite(String monitorKey, boolean written) {
        Session session = sessionOf(monitorKey);
        if (session == null) {
            return 0;
        }
        if (written) {
            session.writeFailures = 0;
            return 0;
        }
        return ++session.writeFailures;
    }

    /** True when this monitor currently drives this core. */
    public static boolean armed(String monitorKey, String coreKey) {
        return coreKey.equals(ARMS.get(monitorKey));
    }

    /** True while a core is being actively cooled or shown. */
    public static boolean active(String coreKey) {
        Session session = SESSIONS.get(coreKey);
        return session != null && !session.aborted && session.plan.active();
    }

    /** Current phase name of a core, for the monitor's status line. */
    public static String phase(String coreKey) {
        Session session = SESSIONS.get(coreKey);
        if (session == null || session.aborted) {
            return "IDLE";
        }
        return session.plan.phase().name();
    }

    /** Drops every arm and session. Called when the server stops. */
    public static void clear() {
        ARMS.clear();
        SESSIONS.clear();
    }

    private static Session sessionOf(String monitorKey) {
        String coreKey = ARMS.get(monitorKey);
        return coreKey == null ? null : SESSIONS.get(coreKey);
    }

    // Counters used only by the offline regression test.
    public static int armCount() {
        return ARMS.size();
    }

    public static int sessionCount() {
        return SESSIONS.size();
    }

    private static final class Session {
        final DfcShutdownPlan plan;
        long lastTick = Long.MIN_VALUE;
        boolean aborted;
        boolean stopAttempted;
        int writeFailures;

        Session(DfcShutdownPlan plan) {
            this.plan = plan;
        }
    }
}

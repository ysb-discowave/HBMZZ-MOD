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

package com.hbmzz.hack;

/**
 * The four things a hacked core can look like from the monitoring side.
 *
 * <p>Deliberately a plain constants class with no Minecraft imports: the state
 * travels inside {@code DFCSnapshot}, which is serialized on the server and
 * rendered on the client, and both the monitor's tick loop and the canvas need
 * to name these numbers. Keeping them out of either package means the DFC
 * renderer never has to reach into server-side code just to compare an int.</p>
 *
 * <h3>Timeline</h3>
 * <pre>
 *   set &lt;field&gt; &lt;value&gt;
 *        |
 *        v
 *   DISCONNECT   60 ticks (3 s)   screen goes dark, node link reported broken
 *        |
 *        v
 *   DEGRADED    600 ticks (30 s)  page is back, some readouts say Err,
 *        |                        red frame blinks once a second
 *        v
 *   NORMAL
 *
 *   cut  ->  CUT  1200 ticks (60 s) or until "restore"
 * </pre>
 *
 * <p>Every write refreshes the clock, so a hacker who keeps poking a core keeps
 * the screen in DISCONNECT rather than letting it settle into DEGRADED. That is
 * the behaviour the fiction asks for: the link does not survive being tampered
 * with, and it does not heal while the tampering continues.</p>
 */
public final class HackState {

    /** Nothing is wrong. */
    public static final int NORMAL = 0;

    /** The link is reported broken: full-screen, before any data comes back. */
    public static final int DISCONNECT = 1;

    /** Data is back, but part of it is marked as untrustworthy. */
    public static final int DEGRADED = 2;

    /** The core signal was cut outright: everything reads ERROR#！. */
    public static final int CUT = 3;

    /**
     * How long the screen stays dark after a write. Three seconds, on the nose.
     *
     * <p>Not {@code final}: {@code HBMZZConfig} overrides it at load time, and
     * the canvas reads the same field to size the recovery bar, so a server that
     * retunes it gets a bar that matches instead of a bar that lies.</p>
     */
    public static int disconnectTicks = 60;

    /** How long the Err-marked readouts stay that way after the last write. */
    public static int degradedTicks = 600;

    /**
     * How long a cut lasts before the feed comes back on its own.
     *
     * <p>A cut has no natural end - there is no machine left to poll - so it
     * needs one anyway. Without a timeout a single mistyped command would leave
     * every screen in the world reading ERROR#！ until the server restarted, and
     * "restore" is a command the player has to already know.</p>
     */
    public static int cutTicks = 1200;

    private HackState() {
    }

    /** Short tag for logs and the tablet's status strip. */
    public static String name(int state) {
        switch (state) {
            case DISCONNECT:
                return "LINK LOST";
            case DEGRADED:
                return "COMPROMISED";
            case CUT:
                return "SIGNAL CUT";
            default:
                return "NOMINAL";
        }
    }
}

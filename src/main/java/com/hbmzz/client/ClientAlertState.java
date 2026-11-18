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

package com.hbmzz.client;

import com.hbmzz.alert.AlertSyncMessage;

/**
 * The facility code as this client last heard it.
 *
 * <p>One copy for the whole client, not one per display. That is the whole
 * reason every screen agrees: they are not each following the same feed, they
 * are all reading the same three fields. A hundred displays therefore cost one
 * hundred draws and one packet per change - not a hundred subscriptions that can
 * individually fall behind.</p>
 */
public final class ClientAlertState {

    private static final int MAX = 6;

    private static int code;
    private static long since;
    private static long nowTick;
    private static String source = "";
    private static String actor = "";
    private static final int[] histCodes = new int[MAX];
    private static final long[] histTicks = new long[MAX];
    private static final String[] histSources = new String[MAX];
    private static int histCount;
    private static long receivedAt;

    private ClientAlertState() {
    }

    public static void accept(AlertSyncMessage message) {
        if (message == null) {
            return;
        }
        code = message.code();
        since = message.since();
        nowTick = message.nowTick();
        source = message.source();
        actor = message.actor();
        int count = Math.min(MAX, message.historySize());
        for (int i = 0; i < count; i++) {
            histCodes[i] = message.historyCode(i);
            histTicks[i] = message.historyTick(i);
            histSources[i] = message.historySource(i);
        }
        histCount = count;
        receivedAt = System.currentTimeMillis();
    }

    /** Leaving a world must not leave the last facility's DELTA on the next one. */
    public static void reset() {
        code = 0;
        since = 0L;
        nowTick = 0L;
        source = "";
        actor = "";
        histCount = 0;
        receivedAt = 0L;
    }

    /**
     * True when a packet arrived recently enough to trust.
     *
     * <p>Five seconds: the displays refresh once a second, so a gap this long
     * means the feed is genuinely gone rather than merely quiet. The screen then
     * says NO LINK instead of freezing on a stale GREEN, which is the failure
     * mode that would actually hurt - a dead alarm panel that still reads normal.</p>
     */
    public static boolean live() {
        return receivedAt != 0L && System.currentTimeMillis() - receivedAt < 5000L;
    }

    public static int code() {
        return code;
    }

    public static long since() {
        return since;
    }

    public static long nowTick() {
        return nowTick;
    }

    public static String source() {
        return source;
    }

    public static String actor() {
        return actor;
    }

    public static int historyCount() {
        return histCount;
    }

    public static int[] historyCodes() {
        return histCodes;
    }

    public static long[] historyTicks() {
        return histTicks;
    }

    public static String[] historySources() {
        return histSources;
    }
}

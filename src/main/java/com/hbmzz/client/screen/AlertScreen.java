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
 * Portions of the render structure and the offscreen-canvas pipeline are derived
 * from JiezhiMod by Shushang de Jiezhi, used under written permission to license
 * them under GPL-3.0. See AUTHORIZATION.md.
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client.screen;

import com.hbmzz.alert.AlertCode;
import com.hbmzz.client.MonitorPalette;

import java.awt.Color;
import java.awt.Graphics2D;
import java.util.List;

/**
 * The facility status panel.
 *
 * <p>Layout, top to bottom: what this screen is, what the facility is, what that
 * means, how long it has meant it, and what happened before. The order is the
 * order a person reads an alarm panel in - the code has to be legible from the
 * other end of the room, so it gets the only 46-pixel line on the screen and
 * everything else is sized to support it.</p>
 *
 * <p>Coordinates are explicit constants rather than a running cursor. A cursor
 * makes the layout a consequence of how many lines the previous block happened
 * to wrap to, which is how the history list ended up under the footer the first
 * time this was drawn: the caption wrapped to two lines on RED and one on GREEN,
 * so the panel had two different heights depending on what it was saying.</p>
 */
public final class AlertScreen extends OffscreenPanel {

    /** Rows of history that fit between the event caption and the footer. */
    private static final int ROWS = 3;

    private static final int HEADER_H = 26;
    private static final int CARD_TOP = 32;
    private static final int CARD_H = 78;
    private static final int DETAIL_Y = CARD_TOP + CARD_H + 18;
    private static final int AGE_Y = 172;
    private static final int RULE_Y = 180;
    private static final int LOG_Y = 192;
    private static final int ROW0 = 204;
    private static final int ROW_STEP = 14;

    public AlertScreen() {
        super(BASE);
    }

    /**
     * Paints one frame.
     *
     * @param nowMs     client clock, only used to drive the blink
     * @param codeId    active code, 0..7
     * @param sinceTick world tick the code was raised
     * @param nowTick   world tick now; the difference is the age
     * @param histCodes history, newest first
     */
    public void render(long nowMs, int codeId, long sinceTick, long nowTick, String source, String actor,
                       int[] histCodes, long[] histTicks, String[] histSources, int histCount,
                       boolean live) {
        AlertCode code = AlertCode.byId(codeId);
        Graphics2D g = this.g;
        g.setBackground(new Color(0, 0, 0, 0));
        g.clearRect(0, 0, this.width, this.height);

        boolean blink = code.isAlarm() && (nowMs / 500L) % 2L == 0L;

        // --- background -----------------------------------------------------
        rect(0, 0, this.width, this.height, MonitorPalette.BG);
        grid(MonitorPalette.GRID, 16);

        // A wash of the active colour down the left edge: readable in peripheral
        // vision without competing with the code word itself.
        rect(0, 0, 4, this.height, MonitorPalette.alpha(code.argb(), 0.85F));
        if (blink) {
            rect(0, 0, 4, this.height, MonitorPalette.alpha(0xFFFFFFFF, 0.55F));
        }

        // --- header ---------------------------------------------------------
        rect(4, 0, this.width - 4, HEADER_H, MonitorPalette.BG_OUTER);
        text("FACILITY STATUS", 12, 18, 12, MonitorPalette.TEXT, true);
        textRight("设施状态", this.width - 12, 18, 11, MonitorPalette.MUTED, false);
        line(4, HEADER_H, this.width, HEADER_H, MonitorPalette.BORDER_DIM, 1.0F);

        // --- the code -------------------------------------------------------
        rect(8, CARD_TOP, this.width - 16, CARD_H, MonitorPalette.CARD);
        frame(8, CARD_TOP, this.width - 16, CARD_H,
                blink ? MonitorPalette.alpha(code.argb(), 0.95F) : MonitorPalette.CARD_EDGE, 1.0F);
        rect(8, CARD_TOP, 5, CARD_H, MonitorPalette.alpha(code.argb(), blink ? 1.0F : 0.8F));

        int labelSize = code.label().length() >= 5 ? 38 : 46;
        int labelWidth = measure(code.label(), labelSize, true);
        text(code.label(), 22, CARD_TOP + 46, labelSize,
                blink ? MonitorPalette.alpha(code.argb(), 1.0F) : code.argb(), true);
        int textX = 22 + labelWidth + 9;
        int avail = this.width - 14 - textX;
        textFit(code.zhTitle(), textX, CARD_TOP + 26, new int[]{13, 11, 9}, MonitorPalette.TEXT,
                true, avail);
        textFit(code.enTitle(), textX, CARD_TOP + 41, new int[]{10, 8}, MonitorPalette.MUTED,
                false, avail);
        textRight("级别 " + code.severity() + "/" + (AlertCode.count() - 1),
                this.width - 14, CARD_TOP + 13, 9, MonitorPalette.MUTED, false);
        textRight(live ? "LIVE" : "NO LINK", this.width - 14, CARD_TOP + 68, 9,
                live ? MonitorPalette.GREEN : MonitorPalette.RED, true);

        // --- meaning --------------------------------------------------------
        int y = DETAIL_Y;
        List<String> zh = wrap(code.zhDetail(), 11, false, this.width - 24);
        for (int i = 0; i < zh.size() && i < 2; i++) {
            text(zh.get(i), 12, y + i * 14, 11, MonitorPalette.TEXT, false);
        }
        int enTop = y + Math.min(2, zh.size()) * 14;
        List<String> en = wrap(code.enDetail(), 9, false, this.width - 24);
        if (!en.isEmpty()) {
            text(en.get(0), 12, enTop, 9, MonitorPalette.MUTED, false);
        }

        // --- age and origin -------------------------------------------------
        long age = Math.max(0L, nowTick - sinceTick);
        text(clock(age), 12, AGE_Y, 13, code.argb(), true);
        String who = source == null || source.isEmpty() ? "-" : source;
        if (actor != null && !actor.isEmpty()) {
            who = who + " / " + actor;
        }
        textRight(trim(who, 30), this.width - 12, AGE_Y, 9, MonitorPalette.MUTED, false);
        line(8, RULE_Y, this.width - 8, RULE_Y, MonitorPalette.BORDER_DIM, 1.0F);

        // --- history --------------------------------------------------------
        text("EVENT LOG", 12, LOG_Y, 9, MonitorPalette.GREEN_DIM, true);
        for (int i = 0; i < ROWS; i++) {
            int baseline = ROW0 + i * ROW_STEP;
            int rowTop = baseline - 9;
            if (i >= histCount) {
                // Empty slots are drawn as slots, not left out: the panel then
                // has a fixed shape and nothing jumps when the first event
                // arrives.
                rect(12, rowTop, 3, 9, MonitorPalette.TRACK);
                text("--", 22, baseline, 9, MonitorPalette.GREEN_DIM, false);
                continue;
            }
            AlertCode entry = AlertCode.byId(histCodes[i]);
            rect(12, rowTop, 3, 9, MonitorPalette.alpha(entry.argb(), 0.9F));
            text(entry.label(), 22, baseline, 9, entry.argb(), true);
            text(trim(histSources[i], 20), 78, baseline, 9, MonitorPalette.MUTED, false);
            textRight(clock(Math.max(0L, nowTick - histTicks[i])), this.width - 12, baseline, 9,
                    MonitorPalette.MUTED, false);
        }

        // --- footer ---------------------------------------------------------
        line(8, this.height - 14, this.width - 8, this.height - 14, MonitorPalette.BORDER_DIM, 1.0F);
        text("HBMZZ FACILITY BUS", 12, this.height - 4, 7, MonitorPalette.GREEN_DIM, false);
        textRight("CODE " + code.id() + "/" + (AlertCode.count() - 1), this.width - 12,
                this.height - 4, 7, MonitorPalette.GREEN_DIM, false);

        markDirty();
    }

    private static String trim(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, Math.max(0, limit - 1)) + "…";
    }
}

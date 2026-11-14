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

import java.awt.Graphics2D;
import java.util.List;

/**
 * The nuke monitor's face.
 *
 * <p>Five numbers and a state. The state is the only one allowed to be large,
 * because it is the only one anybody acts on: {@code IDLE} means the thing is
 * furniture, {@code COUNTDOWN} means leave. Everything else - yield, fuse,
 * coordinates, the controller it is wired to - is reference data sized to be
 * read when the player has stopped walking.</p>
 *
 * <p>The facility code strip at the bottom is not decoration. A bomb going off
 * raises ALPHA, and this screen is often the last thing a player looks at before
 * the crater: having the facility code in the corner means the two displays
 * agree at the moment it matters, without a second screen in the room.</p>
 */
public final class NukeScreen extends OffscreenPanel {

    public NukeScreen() {
        super(BASE);
    }

    public void render(long nowMs, boolean hasDevice, int state, int yield, int fuse, int countdown,
                       int x, int y, int z, int dim, boolean hasController,
                       int cx, int cy, int cz, int cdim, int codeId, boolean live) {
        AlertCode code = AlertCode.byId(codeId);
        Graphics2D g = this.g;
        g.setBackground(new java.awt.Color(0, 0, 0, 0));
        g.clearRect(0, 0, this.width, this.height);

        // 2 mirrors NukeSnapshot.STATE_COUNTDOWN without importing the packet class.
        boolean counting = state == 2;
        boolean blink = counting && (nowMs / 400L) % 2L == 0L;
        int accent = hasDevice ? (counting ? MonitorPalette.RED : MonitorPalette.AMBER) : MonitorPalette.MUTED;

        rect(0, 0, this.width, this.height, MonitorPalette.BG);
        grid(MonitorPalette.GRID, 16);
        rect(0, 0, 4, this.height, MonitorPalette.alpha(accent, 0.85F));

        // --- header ---------------------------------------------------------
        rect(4, 0, this.width - 4, 26, MonitorPalette.BG_OUTER);
        text("NUCLEAR DEVICE", 12, 18, 12, MonitorPalette.TEXT, true);
        textRight("核装置", this.width - 12, 18, 11, MonitorPalette.MUTED, false);
        line(4, 26, this.width, 26, MonitorPalette.BORDER_DIM, 1.0F);

        // --- state ----------------------------------------------------------
        int cardTop = 34;
        int cardH = 58;
        rect(8, cardTop, this.width - 16, cardH, MonitorPalette.CARD);
        frame(8, cardTop, this.width - 16, cardH, MonitorPalette.CARD_EDGE, 1.0F);
        String stateWord;
        if (!hasDevice) {
            stateWord = "NO DEVICE";
        } else if (counting) {
            stateWord = "COUNTDOWN";
        } else {
            stateWord = "IDLE";
        }
        text(stateWord, 18, cardTop + 34, hasDevice ? 26 : 24,
                blink ? MonitorPalette.alpha(accent, 1.0F) : accent, true);
        if (hasDevice && counting) {
            textRight(seconds(countdown), this.width - 16, cardTop + 34, 18, MonitorPalette.RED, true);
        } else if (!hasDevice) {
            textRight(live ? "STANDBY" : "NO LINK", this.width - 16, cardTop + 34, 9,
                    live ? MonitorPalette.MUTED : MonitorPalette.RED, false);
        } else {
            textRight("待命", this.width - 16, cardTop + 34, 11, MonitorPalette.MUTED, false);
        }
        if (counting) {
            double fraction = fuse <= 0 ? 0.0D : (double) countdown / (double) fuse;
            bar(18, cardTop + 42, this.width - 34, 6, fraction, MonitorPalette.TRACK, MonitorPalette.RED);
        }

        int cursor = cardTop + cardH + 14;

        if (!hasDevice) {
            List<String> hint = wrap("用斧子蹲下右键核弹记录坐标，再蹲下右键本屏完成联动。",
                    11, false, this.width - 24);
            for (String lineText : hint) {
                text(lineText, 12, cursor, 11, MonitorPalette.TEXT, false);
                cursor += 14;
            }
            cursor += 2;
            List<String> hint2 = wrap("Sneak-right-click a device with an axe, then this screen.",
                    9, false, this.width - 24);
            for (int i = 0; i < hint2.size() && i < 2; i++) {
                text(hint2.get(i), 12, cursor + i * 12, 9, MonitorPalette.MUTED, false);
            }
            cursor += 30;
            text("斧子 = 记录 / 联动 / 中止倒计时", 12, cursor, 9, MonitorPalette.GREEN_DIM, false);
            cursor += 14;
            text("控制器收到红信号后全图广播并引爆。", 12, cursor, 9, MonitorPalette.GREEN_DIM, false);
        } else {
            cursor = row(cursor, "YIELD", "当量", yield + " kT", MonitorPalette.AMBER);
            cursor = row(cursor, "FUSE", "引信", seconds(fuse), MonitorPalette.AMBER);
            cursor = row(cursor, "POSITION", "坐标", x + " " + y + " " + z, MonitorPalette.TEXT);
            cursor = row(cursor, "DIMENSION", "维度", String.valueOf(dim), MonitorPalette.TEXT);
            String ctrl = hasController ? (cx + " " + cy + " " + cz + " / D" + cdim) : "未联动";
            cursor = row(cursor, "CONTROLLER", "控制器", ctrl,
                    hasController ? MonitorPalette.CYAN : MonitorPalette.MUTED);
        }

        // --- facility code strip -------------------------------------------
        int stripTop = this.height - 40;
        rect(4, stripTop, this.width - 4, this.height - stripTop, MonitorPalette.BG_OUTER);
        line(4, stripTop, this.width, stripTop, MonitorPalette.BORDER_DIM, 1.0F);
        rect(12, stripTop + 9, 4, 16, code.argb());
        text(code.label(), 22, stripTop + 22, 14, code.argb(), true);
        text(code.zhTitle(), 22 + measure(code.label(), 14, true) + 8, stripTop + 21, 10,
                MonitorPalette.TEXT, false);
        textRight(live ? "LIVE" : "NO LINK", this.width - 12, stripTop + 22, 8,
                live ? MonitorPalette.GREEN : MonitorPalette.RED, true);

        text("NUCLEAR MONITOR", 12, stripTop + 33, 7, MonitorPalette.GREEN_DIM, false);
        markDirty();
    }

    /** One label/value line; returns the next baseline. */
    private int row(int baseline, String en, String zh, String value, int valueColor) {
        text(zh, 12, baseline, 10, MonitorPalette.MUTED, false);
        text(en, 12, baseline + 9, 7, MonitorPalette.GREEN_DIM, false);
        textRight(value, this.width - 12, baseline, 11, valueColor, false);
        // No per-row rule. At this size a 1px line lands two logical pixels
        // under a 7px label and reads as a strikethrough, not a separator.
        return baseline + 22;
    }
}

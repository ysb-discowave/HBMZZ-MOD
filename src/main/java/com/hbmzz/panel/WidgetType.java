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

package com.hbmzz.panel;

/**
 * The control types the operator can put on a custom panel.
 *
 * <p>Seven, and no more. Each one earns its place by answering a different
 * question about the core: <em>how much</em> (readout, bar, dial), <em>is it
 * true</em> (lamp), <em>what happened</em> (trend, banner), <em>what is this
 * thing</em> (label). Anything that would only be a prettier version of one of
 * those is not here, because every type is also a row in the palette, a hit box
 * on the canvas and a branch in the painter.</p>
 *
 * <p>Sizes are in grid cells (one cell = 8 virtual pixels on the 256x192 panel
 * canvas). They are the size the control is added at; the operator can stretch
 * any of them afterwards.</p>
 */
public enum WidgetType {

    READOUT("\u6570\u503c\u8868", "\u5927\u5b57\u5b9e\u65f6\u6570\u503c", 8, 4, true, false),
    BAR("\u6761\u5f62\u4eea\u8868", "\u5206\u6bb5\u91cf\u6761 + \u6570\u503c", 10, 3, true, false),
    DIAL("\u73af\u5f62\u4eea\u8868", "\u5f27\u7ebf\u8868\u76d8", 7, 7, true, false),
    LAMP("\u72b6\u6001\u706f", "\u5e03\u5c14\u72b6\u6001\u6307\u793a\u706f", 7, 2, true, false),
    LABEL("\u6587\u672c\u6807\u7b7e", "\u9759\u6001\u8bf4\u660e\u6587\u5b57", 10, 2, false, true),
    TREND("\u8d8b\u52bf\u66f2\u7ebf", "\u8fd1 40 \u6b21\u91c7\u6837\u66f2\u7ebf", 12, 5, true, false),
    BANNER("\u62a5\u8b66\u6a2a\u5e45", "\u663e\u793a\u5f53\u524d\u62a5\u8b66\u4fe1\u606f", 19, 2, false, false);

    private final String label;
    private final String desc;
    private final int defW;
    private final int defH;
    private final boolean needsChannel;
    private final boolean needsText;

    WidgetType(String label, String desc, int defW, int defH, boolean needsChannel, boolean needsText) {
        this.label = label;
        this.desc = desc;
        this.defW = defW;
        this.defH = defH;
        this.needsChannel = needsChannel;
        this.needsText = needsText;
    }

    public String label() {
        return this.label;
    }

    public String desc() {
        return this.desc;
    }

    public int defW() {
        return this.defW;
    }

    public int defH() {
        return this.defH;
    }

    public boolean needsChannel() {
        return this.needsChannel;
    }

    public boolean needsText() {
        return this.needsText;
    }

    public static WidgetType byName(String name) {
        for (WidgetType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return READOUT;
    }
}

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
 * What a rule can do when its conditions hold.
 *
 * <p>The set is shaped by one constraint: every action has to be something the
 * <em>monitor</em> can do on its own, because the panel is a device bolted to a
 * wall and not a scripting host. So it can shout (报警/蜂鸣), show (横幅/切页),
 * remember (记录) and switch a wire (红石输出) - and that is all.</p>
 */
public enum ActionType {

    ALARM("\u89e6\u53d1\u62a5\u8b66", true, true, true, false),
    BANNER("\u663e\u793a\u6a2a\u5e45", false, true, true, false),
    SOUND("\u8702\u9e23\u63d0\u793a", false, true, false, false),
    PAGE("\u5207\u6362\u9875\u9762", false, false, false, true),
    LOG("\u8bb0\u5f55\u4e8b\u4ef6", false, false, true, false),
    REDSTONE("\u7ea2\u77f3\u8f93\u51fa", true, true, false, false),
    CLEAR("\u89e3\u9664\u62a5\u8b66", false, false, false, false);

    private final String label;
    private final boolean amount;
    private final boolean seconds;
    private final boolean text;
    private final boolean page;

    ActionType(String label, boolean amount, boolean seconds, boolean text, boolean page) {
        this.label = label;
        this.amount = amount;
        this.seconds = seconds;
        this.text = text;
        this.page = page;
    }

    public String label() {
        return this.label;
    }

    /** A scalar with a per-type meaning: alarm level, redstone strength. */
    public boolean usesAmount() {
        return this.amount;
    }

    public boolean usesSeconds() {
        return this.seconds;
    }

    public boolean usesText() {
        return this.text;
    }

    public boolean usesPage() {
        return this.page;
    }

    public String amountLabel() {
        return this == REDSTONE ? "\u5f3a\u5ea6" : "\u7b49\u7ea7";
    }

    public static ActionType byName(String name) {
        for (ActionType type : values()) {
            if (type.name().equals(name)) {
                return type;
            }
        }
        return ALARM;
    }

    public ActionType next(int delta) {
        ActionType[] all = values();
        return all[(ordinal() + delta + all.length) % all.length];
    }
}

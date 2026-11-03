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

import net.minecraft.nbt.NBTTagCompound;

/** One action block of a rule, with whatever parameters its type needs. */
public class Action {

    public static final String[] PAGE_NAMES = {
            "\u6838\u5fc3", "\u90e8\u4ef6", "\u66f2\u7ebf", "\u81ea\u5b9a\u4e49"};

    public ActionType type = ActionType.ALARM;
    /** Alarm level (1/2) or redstone strength (1..15). */
    public double amount = 2.0D;
    /** How long the effect lasts. */
    public double seconds = 10.0D;
    public String text = "\u6e29\u5ea6\u8d85\u9650";
    public int page;

    public static Action of(ActionType type) {
        Action action = new Action();
        action.type = type;
        switch (type) {
            case ALARM:
                action.amount = 2.0D;
                action.seconds = 10.0D;
                action.text = "\u6e29\u5ea6\u8d85\u9650";
                break;
            case BANNER:
                action.seconds = 5.0D;
                action.text = "\u6ce8\u610f\u5806\u82af\u72b6\u6001";
                break;
            case SOUND:
                action.seconds = 3.0D;
                break;
            case PAGE:
                action.page = 0;
                break;
            case LOG:
                action.text = "\u89e6\u53d1";
                break;
            case REDSTONE:
                action.amount = 15.0D;
                action.seconds = 5.0D;
                break;
            default:
                break;
        }
        return action;
    }

    public String describe() {
        StringBuilder builder = new StringBuilder(this.type.label());
        switch (this.type) {
            case ALARM:
                builder.append(" \u7b49\u7ea7").append((int) this.amount)
                        .append(" \u6301\u7eed").append((int) this.seconds).append("\u79d2");
                break;
            case BANNER:
                builder.append(" \u6301\u7eed").append((int) this.seconds).append("\u79d2");
                break;
            case SOUND:
                builder.append(" \u6301\u7eed").append((int) this.seconds).append("\u79d2");
                break;
            case PAGE:
                int p = Math.max(0, Math.min(PAGE_NAMES.length - 1, this.page));
                builder.append(" \u2192 0").append(p + 1).append(' ').append(PAGE_NAMES[p]);
                break;
            case REDSTONE:
                builder.append(" \u5f3a\u5ea6").append((int) this.amount)
                        .append(" \u6301\u7eed").append((int) this.seconds).append("\u79d2");
                break;
            default:
                break;
        }
        return builder.toString();
    }

    public Action copy() {
        Action copy = new Action();
        copy.type = this.type;
        copy.amount = this.amount;
        copy.seconds = this.seconds;
        copy.text = this.text;
        copy.page = this.page;
        return copy;
    }

    public void clamp() {
        this.amount = Math.max(1.0D, Math.min(15.0D, this.amount));
        this.seconds = Math.max(1.0D, Math.min(120.0D, this.seconds));
        this.page = Math.max(0, Math.min(PAGE_NAMES.length - 1, this.page));
        if (this.text == null) {
            this.text = "";
        }
        if (this.text.length() > 28) {
            this.text = this.text.substring(0, 28);
        }
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("t", this.type.name());
        tag.setDouble("a", this.amount);
        tag.setDouble("s", this.seconds);
        tag.setString("x", this.text == null ? "" : this.text);
        tag.setInteger("p", this.page);
        return tag;
    }

    public static Action read(NBTTagCompound tag) {
        Action action = new Action();
        action.type = ActionType.byName(tag.getString("t"));
        action.amount = tag.getDouble("a");
        action.seconds = tag.getDouble("s");
        action.text = tag.getString("x");
        action.page = tag.getInteger("p");
        action.clamp();
        return action;
    }
}

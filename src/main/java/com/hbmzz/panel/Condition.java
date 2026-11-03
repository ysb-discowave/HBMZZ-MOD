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

import com.hbmzz.dfc.DFCSnapshot;
import net.minecraft.nbt.NBTTagCompound;

/**
 * One test inside a rule: {@code 温度 大于 6000}.
 *
 * <p>Left side is always a channel, right side is either a constant or another
 * channel - the second form is what lets a rule say "hotter than its own
 * melting point" without the operator having to type 2250 and then remember to
 * change it when the fuel changes.</p>
 *
 * <p>Boolean channels are compared as 1 and 0. The editor hides the operator
 * for those and offers 是/否 instead, because "堆芯在线 大于 0.5" is a sentence
 * nobody should have to read.</p>
 */
public class Condition {

    public static final String[] OPS = {">", ">=", "<", "<=", "==", "!="};
    public static final String[] OP_LABELS = {
            "\u5927\u4e8e", "\u4e0d\u5c0f\u4e8e", "\u5c0f\u4e8e",
            "\u4e0d\u5927\u4e8e", "\u7b49\u4e8e", "\u4e0d\u7b49\u4e8e"};

    public Channel left = Channel.TEMPERATURE;
    public int op;
    public double right = 6000.0D;
    /** When set, the right-hand side is this channel's live value. */
    public Channel rightChannel;

    public boolean test(DFCSnapshot snapshot) {
        double a = this.left.read(snapshot);
        double b = this.rightChannel == null ? this.right : this.rightChannel.read(snapshot);
        switch (this.op) {
            case 1:
                return a >= b;
            case 2:
                return a < b;
            case 3:
                return a <= b;
            case 4:
                return Math.abs(a - b) < 0.0001D;
            case 5:
                return Math.abs(a - b) >= 0.0001D;
            case 0:
            default:
                return a > b;
        }
    }

    public String opText() {
        return OPS[Math.max(0, Math.min(OPS.length - 1, this.op))];
    }

    public String opLabel() {
        return OP_LABELS[Math.max(0, Math.min(OP_LABELS.length - 1, this.op))];
    }

    public String rightText() {
        if (this.rightChannel != null) {
            return this.rightChannel.label();
        }
        return this.left.isBoolean() ? (this.right >= 0.5D ? "\u662f" : "\u5426")
                : this.left.formatNumber(this.right);
    }

    /** One-line form used by rule summaries and the log. */
    public String describe() {
        return this.left.label() + " " + opText() + " " + rightText();
    }

    public Condition copy() {
        Condition copy = new Condition();
        copy.left = this.left;
        copy.op = this.op;
        copy.right = this.right;
        copy.rightChannel = this.rightChannel;
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("l", this.left.name());
        tag.setInteger("o", this.op);
        tag.setDouble("r", this.right);
        tag.setString("rc", this.rightChannel == null ? "" : this.rightChannel.name());
        return tag;
    }

    public static Condition read(NBTTagCompound tag) {
        Condition condition = new Condition();
        condition.left = Channel.byName(tag.getString("l"));
        condition.op = Math.max(0, Math.min(OPS.length - 1, tag.getInteger("o")));
        condition.right = tag.getDouble("r");
        String rc = tag.getString("rc");
        condition.rightChannel = rc.isEmpty() ? null : Channel.byName(rc);
        return condition;
    }
}

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

package com.hbmzz.ctrl;

import net.minecraft.nbt.NBTTagCompound;

/**
 * One automation link: read a number off one machine, and when it crosses a
 * threshold, push a value into another.
 *
 * <pre>
 *   当 [A] 的 [heat] 大于 [900]      冷却 [5] 秒
 *   则 [B] 发 [rbmk_ctrl_set_level] 的 [level] = [0]
 * </pre>
 *
 * <p>This is the flat, one-breath form of NTM: Cursed Edition's node graph. A
 * graph buys arbitrary wiring at the cost of reading like a schematic; an
 * operator standing in front of a reactor at 3am needs the sentence above, not
 * a canvas of boxes. Everything the panel does is one of these sentences, and
 * anything a graph could do with two of them can be written as two rules -
 * which is also how a human would say it out loud.</p>
 *
 * <p>Rules run on the server, on the same half-second tick as the readouts, so
 * two players can never disagree about whether the rods were ordered in.</p>
 */
public class CtrlRule {

    public int srcLink = -1;
    public String srcKey = "";
    /** Index into {@link #OPS}. */
    public int op;
    public double threshold;

    public int dstLink = -1;
    public String dstEvent = "";
    public String dstVar = "";
    public double value;
    /** When set, the event carries a string instead of a number. */
    public String text = "";

    /** Minimum seconds between two firings of this rule. */
    public int cooldown = 5;
    public boolean enabled = true;

    /** Runtime: world tick the rule last fired at, 0 for never. */
    public long lastFire;

    public static final String[] OPS = {">", ">=", "<", "<=", "==", "!="};
    public static final String[] OP_LABELS = {"大于", "不小于", "小于", "不大于", "等于", "不等于"};

    public boolean armed() {
        return this.enabled && this.srcLink >= 0 && this.dstLink >= 0
                && !this.dstEvent.isEmpty() && !this.srcKey.isEmpty();
    }

    public String opLabel() {
        return OP_LABELS[Math.max(0, Math.min(OP_LABELS.length - 1, this.op))];
    }

    /** True when {@code value} satisfies this rule's comparison. */
    public boolean matches(double actual) {
        switch (this.op) {
            case 1:
                return actual >= this.threshold;
            case 2:
                return actual < this.threshold;
            case 3:
                return actual <= this.threshold;
            case 4:
                return Math.abs(actual - this.threshold) < 1.0E-4D;
            case 5:
                return Math.abs(actual - this.threshold) >= 1.0E-4D;
            case 0:
            default:
                return actual > this.threshold;
        }
    }

    /** One line for the editor's rule list. */
    public String summary() {
        StringBuilder out = new StringBuilder();
        out.append("当 ").append(CtrlLink.letter(this.srcLink)).append(" 的 ")
                .append(this.srcKey.isEmpty() ? "?" : this.srcKey)
                .append(' ').append(this.opLabel()).append(' ')
                .append(trim(this.threshold));
        out.append(" → ").append(CtrlLink.letter(this.dstLink)).append(" 发 ")
                .append(this.dstEvent.isEmpty() ? "?" : this.dstEvent);
        if (!this.dstVar.isEmpty()) {
            out.append('.').append(this.dstVar).append(" = ").append(trim(this.value));
        }
        return out.toString();
    }

    private static String trim(double value) {
        return Math.abs(value - Math.rint(value)) < 1.0E-4D
                ? String.valueOf((long) Math.rint(value))
                : String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    public void clamp() {
        this.srcLink = this.srcLink < 0 ? -1 : Math.min(CtrlPanel.LINKS - 1, this.srcLink);
        this.dstLink = this.dstLink < 0 ? -1 : Math.min(CtrlPanel.LINKS - 1, this.dstLink);
        this.op = Math.max(0, Math.min(OPS.length - 1, this.op));
        this.cooldown = Math.max(0, Math.min(600, this.cooldown));
        if (this.srcKey == null) {
            this.srcKey = "";
        }
        if (this.dstEvent == null) {
            this.dstEvent = "";
        }
        if (this.dstVar == null) {
            this.dstVar = "";
        }
        if (this.text == null) {
            this.text = "";
        }
    }

    public CtrlRule copy() {
        CtrlRule copy = new CtrlRule();
        copy.srcLink = this.srcLink;
        copy.srcKey = this.srcKey;
        copy.op = this.op;
        copy.threshold = this.threshold;
        copy.dstLink = this.dstLink;
        copy.dstEvent = this.dstEvent;
        copy.dstVar = this.dstVar;
        copy.value = this.value;
        copy.text = this.text;
        copy.cooldown = this.cooldown;
        copy.enabled = this.enabled;
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("sl", this.srcLink);
        tag.setString("sk", this.srcKey);
        tag.setInteger("op", this.op);
        tag.setDouble("th", this.threshold);
        tag.setInteger("dl", this.dstLink);
        tag.setString("de", this.dstEvent);
        tag.setString("dv", this.dstVar);
        tag.setDouble("vl", this.value);
        tag.setString("tx", this.text);
        tag.setInteger("cd", this.cooldown);
        tag.setBoolean("en", this.enabled);
        return tag;
    }

    public static CtrlRule read(NBTTagCompound tag) {
        CtrlRule rule = new CtrlRule();
        rule.srcLink = tag.hasKey("sl") ? tag.getInteger("sl") : -1;
        rule.srcKey = tag.getString("sk");
        rule.op = tag.getInteger("op");
        rule.threshold = tag.getDouble("th");
        rule.dstLink = tag.hasKey("dl") ? tag.getInteger("dl") : -1;
        rule.dstEvent = tag.getString("de");
        rule.dstVar = tag.getString("dv");
        rule.value = tag.getDouble("vl");
        rule.text = tag.getString("tx");
        rule.cooldown = tag.hasKey("cd") ? tag.getInteger("cd") : 5;
        rule.enabled = !tag.hasKey("en") || tag.getBoolean("en");
        rule.clamp();
        return rule;
    }
}

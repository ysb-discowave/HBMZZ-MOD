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
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;

/**
 * One block program: a trigger, a group of conditions, a column of actions.
 *
 * <pre>
 *   当 [周期检测 每 20 刻]
 *   若 [温度] [大于] [6000]
 *      并且 [稳定度] [小于] [40]
 *   则 [触发报警 2 级 10 秒]
 *      并且 [记录事件 温度超限]
 * </pre>
 *
 * <p>That shape - and only that shape - is deliberate. A general graph editor
 * would let an operator build a loop the server cannot stop; a flat rule with
 * a junction and two lists can be read in one breath, validated in one pass and
 * evaluated without a stack. Conditions within a rule share one junction
 * (all/any) rather than nesting, which covers what an alarm panel actually
 * needs: "too hot AND unstable", "no core OR no fuel".</p>
 */
public class Rule {

    public static final int TRIGGER_INTERVAL = 0;
    public static final int TRIGGER_BOOT = 1;
    public static final int TRIGGER_COLLAPSE = 2;
    public static final int TRIGGER_LINK = 3;
    public static final int TRIGGER_EDGE = 4;

    public static final String[] TRIGGER_NAMES = {
            "\u5468\u671f\u68c0\u6d4b", "\u5f00\u673a\u65f6", "\u574d\u7f29\u5f00\u59cb",
            "\u6570\u636e\u4e2d\u65ad", "\u6570\u503c\u7a7f\u8d8a"};

    public static final String[] TRIGGER_HINTS = {
            "\u6761\u4ef6\u6210\u7acb\u65f6\u53cd\u590d\u6267\u884c",
            "\u5c4f\u5e55\u5f00\u673a\u6216\u8bfb\u6863\u540e\u6267\u884c\u4e00\u6b21",
            "\u5806\u82af\u5f00\u59cb\u574d\u7f29\u65f6\u6267\u884c\u4e00\u6b21",
            "\u4e0e\u5806\u82af\u65ad\u5f00\u65f6\u6267\u884c\u4e00\u6b21",
            "\u6761\u4ef6\u7531\u5047\u53d8\u771f\u65f6\u6267\u884c\u4e00\u6b21"};

    public static final int MAX_CONDITIONS = 4;
    public static final int MAX_ACTIONS = 4;

    public String name = "\u65b0\u89c4\u5219";
    public int trigger = TRIGGER_INTERVAL;
    /** Ticks between checks for {@link #TRIGGER_INTERVAL}. */
    public int interval = 20;
    /** 0 = all conditions must hold, 1 = any one is enough. */
    public int junction;
    /** Minimum seconds between two firings of this rule. */
    public int cooldown = 5;
    public boolean enabled = true;

    public final List<Condition> conditions = new ArrayList<Condition>();
    public final List<Action> actions = new ArrayList<Action>();

    public static Rule example() {
        Rule rule = new Rule();
        rule.name = "\u6e29\u5ea6\u8d85\u9650";
        rule.trigger = TRIGGER_INTERVAL;
        rule.interval = 20;
        rule.junction = 0;
        rule.cooldown = 10;

        Condition hot = new Condition();
        hot.left = Channel.TEMPERATURE;
        hot.op = 0;
        hot.right = 6000.0D;
        rule.conditions.add(hot);

        Action alarm = Action.of(ActionType.ALARM);
        alarm.amount = 2.0D;
        alarm.seconds = 10.0D;
        alarm.text = "\u6e29\u5ea6\u8d85\u9650";
        rule.actions.add(alarm);

        Action page = Action.of(ActionType.PAGE);
        page.page = 0;
        rule.actions.add(page);
        return rule;
    }

    /** True when the condition group holds for this sample. */
    public boolean matches(DFCSnapshot snapshot) {
        if (this.conditions.isEmpty()) {
            return true;
        }
        boolean any = this.junction == 1;
        for (Condition condition : this.conditions) {
            boolean ok = condition.test(snapshot);
            if (any && ok) {
                return true;
            }
            if (!any && !ok) {
                return false;
            }
        }
        return !any;
    }

    /** Flat one-liner for the rule list: conditions on the left, first action right. */
    public String summary() {
        StringBuilder builder = new StringBuilder();
        if (this.conditions.isEmpty()) {
            builder.append("\u65e0\u6761\u4ef6");
        } else {
            String joiner = this.junction == 1 ? " \u6216 " : " \u4e14 ";
            for (int i = 0; i < this.conditions.size(); i++) {
                if (i > 0) {
                    builder.append(joiner);
                }
                builder.append(this.conditions.get(i).describe());
            }
        }
        builder.append(" \u2192 ");
        builder.append(this.actions.isEmpty() ? "\u65e0\u52a8\u4f5c" : this.actions.get(0).type.label());
        if (this.actions.size() > 1) {
            builder.append(" \u7b49 ").append(this.actions.size()).append(" \u9879");
        }
        return builder.toString();
    }

    public void clamp() {
        while (this.conditions.size() > MAX_CONDITIONS) {
            this.conditions.remove(this.conditions.size() - 1);
        }
        while (this.actions.size() > MAX_ACTIONS) {
            this.actions.remove(this.actions.size() - 1);
        }
        this.trigger = Math.max(0, Math.min(TRIGGER_NAMES.length - 1, this.trigger));
        // The engine only looks at the program every 10 ticks, so an interval
        // below that would silently mean "every pass" while the editor claimed
        // otherwise.
        this.interval = Math.max(10, Math.min(400, this.interval));
        this.junction = Math.max(0, Math.min(1, this.junction));
        this.cooldown = Math.max(0, Math.min(600, this.cooldown));
        if (this.name == null || this.name.trim().isEmpty()) {
            this.name = "\u65b0\u89c4\u5219";
        }
        if (this.name.length() > 16) {
            this.name = this.name.substring(0, 16);
        }
        for (Action action : this.actions) {
            action.clamp();
        }
    }

    public Rule copy() {
        Rule copy = new Rule();
        copy.name = this.name;
        copy.trigger = this.trigger;
        copy.interval = this.interval;
        copy.junction = this.junction;
        copy.cooldown = this.cooldown;
        copy.enabled = this.enabled;
        for (Condition condition : this.conditions) {
            copy.conditions.add(condition.copy());
        }
        for (Action action : this.actions) {
            copy.actions.add(action.copy());
        }
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("n", this.name);
        tag.setInteger("tg", this.trigger);
        tag.setInteger("iv", this.interval);
        tag.setInteger("jn", this.junction);
        tag.setInteger("cd", this.cooldown);
        tag.setBoolean("en", this.enabled);
        NBTTagList conds = new NBTTagList();
        for (Condition condition : this.conditions) {
            conds.appendTag(condition.write());
        }
        tag.setTag("c", conds);
        NBTTagList acts = new NBTTagList();
        for (Action action : this.actions) {
            acts.appendTag(action.write());
        }
        tag.setTag("a", acts);
        return tag;
    }

    public static Rule read(NBTTagCompound tag) {
        Rule rule = new Rule();
        rule.name = tag.getString("n");
        rule.trigger = tag.getInteger("tg");
        rule.interval = tag.getInteger("iv") > 0 ? tag.getInteger("iv") : 20;
        rule.junction = tag.getInteger("jn");
        rule.cooldown = tag.getInteger("cd");
        rule.enabled = !tag.hasKey("en") || tag.getBoolean("en");
        NBTTagList conds = tag.getTagList("c", 10);
        for (int i = 0; i < conds.tagCount(); i++) {
            rule.conditions.add(Condition.read(conds.getCompoundTagAt(i)));
        }
        NBTTagList acts = tag.getTagList("a", 10);
        for (int i = 0; i < acts.tagCount(); i++) {
            rule.actions.add(Action.read(acts.getCompoundTagAt(i)));
        }
        rule.clamp();
        return rule;
    }
}

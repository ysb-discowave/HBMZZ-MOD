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
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom panel's automation: an ordered list of independent rules.
 *
 * <p>Order only matters when two rules fight over the same thing (one raises an
 * alarm, a later one clears it) - evaluation is top to bottom in one pass, so
 * "the last word wins" is a property an operator can predict by looking at the
 * list.</p>
 */
public class LogicProgram {

    public static final int MAX_RULES = 8;

    public final List<Rule> rules = new ArrayList<Rule>();

    public static LogicProgram defaults() {
        LogicProgram program = new LogicProgram();
        program.rules.add(Rule.example());

        Rule collapse = new Rule();
        collapse.name = "\u574d\u7f29\u62a5\u8b66";
        collapse.trigger = Rule.TRIGGER_COLLAPSE;
        collapse.cooldown = 60;
        Action alarm = Action.of(ActionType.ALARM);
        alarm.amount = 2.0D;
        alarm.seconds = 15.0D;
        alarm.text = "\u5806\u82af\u5df2\u574d\u7f29";
        collapse.actions.add(alarm);
        program.rules.add(collapse);
        return program;
    }

    public Rule add() {
        if (this.rules.size() >= MAX_RULES) {
            return null;
        }
        Rule rule = new Rule();
        rule.name = "\u89c4\u5219" + (this.rules.size() + 1);
        Condition condition = new Condition();
        condition.left = Channel.TEMPERATURE;
        condition.right = 6000.0D;
        rule.conditions.add(condition);
        rule.actions.add(Action.of(ActionType.ALARM));
        this.rules.add(rule);
        return rule;
    }

    public void remove(int index) {
        if (index >= 0 && index < this.rules.size()) {
            this.rules.remove(index);
        }
    }

    /** True when any enabled rule could act while nobody is watching. */
    public boolean needsBackgroundEval() {
        for (Rule rule : this.rules) {
            if (!rule.enabled) {
                continue;
            }
            for (Action action : rule.actions) {
                if (action.type == ActionType.REDSTONE) {
                    return true;
                }
            }
        }
        return false;
    }

    public int enabledCount() {
        int count = 0;
        for (Rule rule : this.rules) {
            if (rule.enabled) {
                count++;
            }
        }
        return count;
    }

    public void validate() {
        while (this.rules.size() > MAX_RULES) {
            this.rules.remove(this.rules.size() - 1);
        }
        for (Rule rule : this.rules) {
            rule.clamp();
        }
    }

    public LogicProgram copy() {
        LogicProgram copy = new LogicProgram();
        for (Rule rule : this.rules) {
            copy.rules.add(rule.copy());
        }
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        NBTTagList list = new NBTTagList();
        for (Rule rule : this.rules) {
            list.appendTag(rule.write());
        }
        tag.setTag("r", list);
        return tag;
    }

    public static LogicProgram read(NBTTagCompound tag) {
        LogicProgram program = new LogicProgram();
        NBTTagList list = tag.getTagList("r", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            program.rules.add(Rule.read(list.getCompoundTagAt(i)));
        }
        program.validate();
        return program;
    }
}

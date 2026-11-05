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

import net.minecraft.world.World;

/**
 * The server-side half-second: read every bound machine once, push the numbers
 * into the controls, then run the automation.
 *
 * <p>Deliberately not in the tile entity. The tile entity owns *state* - what
 * is on the panel, what it last read - while this class owns the *round*: one
 * sample per machine, one pass over the widgets, one pass over the rules. That
 * split is what keeps the expensive part (a reflective query per linked
 * machine) down to six calls per panel per tick instead of one per widget, and
 * it means the whole round can be reasoned about - and tested - without a world
 * in hand.</p>
 */
public final class CtrlEngine {

    /** Ticks between rounds. Half a second is fast enough to feel live. */
    public static final int TICK = 10;

    private CtrlEngine() {
    }

    /**
     * One round. Returns true when something an onlooker would notice changed,
     * which is the tile entity's cue to sync.
     *
     * @param now absolute world tick, used for rule cooldowns
     */
    public static boolean refresh(CtrlPanel panel, World world, long now) {
        if (panel == null || world == null) {
            return false;
        }
        MachineBridge.Sample[] samples = new MachineBridge.Sample[CtrlPanel.LINKS];
        for (int i = 0; i < CtrlPanel.LINKS; i++) {
            CtrlLink link = panel.links[i];
            if (!link.isEmpty()) {
                samples[i] = MachineBridge.sample(world, link.pos);
            }
        }

        boolean changed = false;
        for (CtrlWidget widget : panel.widgets) {
            changed |= refreshWidget(panel, widget, samples);
        }
        for (CtrlRule rule : panel.rules) {
            changed |= runRule(rule, panel, samples, world, now);
        }
        return changed;
    }

    private static boolean refreshWidget(CtrlPanel panel, CtrlWidget widget, MachineBridge.Sample[] samples) {
        if (widget.type.isTerminal()) {
            return refreshTerminal(panel, widget);
        }
        if (widget.isOperator()) {
            return false;
        }
        String text = "";
        double value = 0.0D;
        if (!widget.bound() || widget.key.isEmpty()) {
            text = "—";
        } else {
            MachineBridge.Sample sample = samples[widget.link];
            if (sample == null || !sample.has(widget.key)) {
                text = "—";
            } else if (widget.type == CtrlType.TEXT || widget.type == CtrlType.LABEL) {
                text = sample.text(widget.key);
            } else {
                double number = sample.number(widget.key);
                value = Double.isNaN(number) ? 0.0D : number;
            }
        }
        boolean changed = Math.abs(value - widget.value) > 1.0E-4D || !text.equals(widget.text);
        widget.value = value;
        widget.text = text;
        return changed;
    }

    /**
     * Keeps a terminal's target slot honest and its caption current.
     *
     * <p>The terminal addresses a slot chosen at runtime, so there are two ways
     * for it to end up pointing nowhere: it was just placed (link -1), or the
     * slot it was looking at was unbound while it was looking at it. Both heal
     * themselves by falling back to the first machine that is actually there -
     * a terminal showing "未接机器" on a panel with five live machines is a
     * control the operator has to fix by hand for no reason.</p>
     *
     * <p>Note that this writes a design field from the tick loop. That is safe
     * here and only here: the value is derived entirely from the panel's own
     * links, so it cannot disagree with what the player sees, and it stops
     * changing the moment a valid slot is selected.</p>
     */
    private static boolean refreshTerminal(CtrlPanel panel, CtrlWidget widget) {
        boolean changed = false;
        if (widget.link < 0 || widget.link >= CtrlPanel.LINKS || panel.links[widget.link].isEmpty()) {
            int pick = -1;
            for (int i = 0; i < CtrlPanel.LINKS; i++) {
                if (!panel.links[i].isEmpty()) {
                    pick = i;
                    break;
                }
            }
            if (pick != widget.link) {
                widget.link = pick;
                changed = true;
            }
        }
        String text;
        if (widget.link < 0) {
            text = "未接机器";
        } else {
            CtrlLink link = panel.links[widget.link];
            text = CtrlLink.letter(widget.link) + " · " + (link.label.isEmpty() ? "机器" : link.label);
        }
        if (!text.equals(widget.text)) {
            widget.text = text;
            changed = true;
        }
        return changed;
    }

    private static boolean runRule(CtrlRule rule, CtrlPanel panel, MachineBridge.Sample[] samples,
                                   World world, long now) {
        if (!rule.armed()) {
            return false;
        }
        MachineBridge.Sample source = samples[rule.srcLink];
        if (source == null || !source.has(rule.srcKey)) {
            return false;
        }
        double actual = source.number(rule.srcKey);
        if (Double.isNaN(actual) || !rule.matches(actual)) {
            return false;
        }
        if (rule.lastFire > 0L && now - rule.lastFire < rule.cooldown * 20L) {
            return false;
        }
        rule.lastFire = now;
        CtrlLink target = panel.links[rule.dstLink];
        if (target.isEmpty()) {
            return false;
        }
        MachineBridge.fire(world, target.pos, rule.dstEvent, rule.dstVar, rule.value, rule.text);
        return true;
    }

    /**
     * Turns a raw request into the value this control can actually hold:
     * clamped to its range, quantised if it is a flag, snapped to its detents
     * if it is a rotary switch.
     *
     * <p>Public because the window needs the identical arithmetic to show the
     * knob under the cursor while it is being dragged. Two copies of the detent
     * formula is exactly how a knob ends up sitting between two labels on the
     * client and on one on the server.</p>
     */
    public static double quantise(CtrlWidget widget, double value) {
        if (widget.type.isBooleanish()) {
            value = value >= 0.5D ? 1.0D : 0.0D;
        } else {
            value = Math.max(widget.min, Math.min(widget.max, value));
        }
        if (widget.type == CtrlType.ROTARY) {
            // Snap to the detents the panel was configured with, so the knob
            // never sits between two labelled positions.
            double step = (widget.max - widget.min) / Math.max(1, widget.steps - 1);
            double steps = Math.rint((value - widget.min) / step);
            value = widget.min + steps * step;
        }
        return value;
    }

    /**
     * Applies an operator's input to one control and, if it is bound, sends the
     * value to its machine.
     *
     * @return true when the panel changed
     */
    public static boolean operate(CtrlPanel panel, World world, int index, double value) {
        CtrlWidget widget = panel.get(index);
        if (widget == null || !widget.isOperator()) {
            return false;
        }
        value = quantise(widget, value);
        boolean changed = Math.abs(value - widget.value) > 1.0E-4D;
        widget.value = value;
        widget.text = "";
        if (widget.type == CtrlType.ESTOP) {
            widget.latched = value >= 0.5D;
        }
        if (widget.bound() && !widget.event.isEmpty()) {
            CtrlLink link = panel.links[widget.link];
            if (!link.isEmpty()) {
                MachineBridge.fire(world, link.pos, widget.event, widget.var, value, "");
            }
        }
        if (widget.type == CtrlType.BUTTON) {
            // Momentary, like the part it imitates: the event has gone out, so
            // the cap springs back. Leaving it at 1 would light the button
            // forever and make the next press look like it did nothing.
            widget.value = 0.0D;
        }
        return changed;
    }
}

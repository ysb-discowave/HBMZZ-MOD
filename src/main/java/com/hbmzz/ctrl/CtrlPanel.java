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
import net.minecraft.nbt.NBTTagList;

import java.util.ArrayList;
import java.util.List;

/**
 * The whole control panel: the controls bolted to it, the six machine slots
 * they talk to, and the rules that wire those together.
 *
 * <p>The grid is 32 by 18 cells of 8 pixels, which is the same 256x144 canvas
 * the in-world plate and the editor draw. Both use the same {@link CtrlPainter},
 * so what an operator arranges in the editor is literally what is painted on
 * the block - not a preview that has to be trusted.</p>
 */
public class CtrlPanel {

    public static final int COLS = 32;
    public static final int ROWS = 18;
    /** Pixels per cell on the virtual canvas. */
    public static final int GRID = 8;
    public static final int CANVAS_W = COLS * GRID;
    public static final int CANVAS_H = ROWS * GRID;

    public static final int LINKS = 6;
    public static final int MAX_WIDGETS = 24;
    public static final int MAX_RULES = 6;

    public String name = "机器控制面板";
    public final List<CtrlWidget> widgets = new ArrayList<CtrlWidget>();
    public final CtrlLink[] links = new CtrlLink[LINKS];
    public final List<CtrlRule> rules = new ArrayList<CtrlRule>();

    public CtrlPanel() {
        for (int i = 0; i < LINKS; i++) {
            this.links[i] = new CtrlLink();
        }
    }

    /**
     * A panel that explains itself: a caption, a lamp and a readout, none of
     * them bound yet. An empty grid would leave the player with a black
     * rectangle and no idea that the side bar is where the work happens.
     */
    public static CtrlPanel defaults() {
        CtrlPanel panel = new CtrlPanel();
        CtrlWidget title = CtrlWidget.of(CtrlType.LABEL, 0, 0);
        title.gw = 32;
        title.label = "机器控制面板 · 先在下方绑定 A–F";
        panel.widgets.add(title);

        CtrlWidget lamp = CtrlWidget.of(CtrlType.LAMP, 1, 3);
        lamp.label = "在线";
        panel.widgets.add(lamp);

        CtrlWidget seg = CtrlWidget.of(CtrlType.SEG7, 5, 3);
        seg.label = "读数";
        panel.widgets.add(seg);

        CtrlWidget knob = CtrlWidget.of(CtrlType.KNOB, 1, 7);
        knob.label = "输出";
        panel.widgets.add(knob);
        return panel;
    }

    public CtrlWidget get(int index) {
        return index >= 0 && index < this.widgets.size() ? this.widgets.get(index) : null;
    }

    /** Control at a grid cell, topmost first. */
    public int at(int cellX, int cellY) {
        for (int i = this.widgets.size() - 1; i >= 0; i--) {
            if (this.widgets.get(i).contains(cellX, cellY)) {
                return i;
            }
        }
        return -1;
    }

    public boolean blocked(int index, int x, int y, int w, int h) {
        if (x < 0 || y < 0 || x + w > COLS || y + h > ROWS) {
            return true;
        }
        for (int i = 0; i < this.widgets.size(); i++) {
            if (i != index && this.widgets.get(i).overlaps(x, y, w, h)) {
                return true;
            }
        }
        return false;
    }

    /** Adds a control at the first free spot that fits, top-left first. */
    public CtrlWidget add(CtrlType type) {
        if (this.widgets.size() >= MAX_WIDGETS) {
            return null;
        }
        int w = type.defW();
        int h = type.defH();
        for (int y = 0; y + h <= ROWS; y++) {
            for (int x = 0; x + w <= COLS; x++) {
                if (!blocked(-1, x, y, w, h)) {
                    CtrlWidget widget = CtrlWidget.of(type, x, y);
                    if (type.isBooleanish()) {
                        widget.min = 0.0D;
                        widget.max = 1.0D;
                    }
                    this.widgets.add(widget);
                    return widget;
                }
            }
        }
        return null;
    }

    public void remove(int index) {
        if (index >= 0 && index < this.widgets.size()) {
            this.widgets.remove(index);
        }
    }

    public CtrlRule addRule() {
        if (this.rules.size() >= MAX_RULES) {
            return null;
        }
        CtrlRule rule = new CtrlRule();
        rule.srcLink = firstBoundLink();
        rule.dstLink = firstBoundLink();
        this.rules.add(rule);
        return rule;
    }

    private int firstBoundLink() {
        for (int i = 0; i < LINKS; i++) {
            if (!this.links[i].isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    /** Drops every reference to a slot, used when that slot is unbound. */
    public void forgetLink(int slot) {
        this.links[slot].clear();
        for (CtrlWidget widget : this.widgets) {
            if (widget.link == slot) {
                widget.link = -1;
            }
        }
        for (CtrlRule rule : this.rules) {
            if (rule.srcLink == slot) {
                rule.srcLink = -1;
            }
            if (rule.dstLink == slot) {
                rule.dstLink = -1;
            }
        }
    }

    /**
     * Server-side sanity pass. The editor already prevents overlaps and
     * out-of-range values; this is what the server does when it does not
     * believe the editor, which is every time.
     */
    public void validate() {
        while (this.widgets.size() > MAX_WIDGETS) {
            this.widgets.remove(this.widgets.size() - 1);
        }
        while (this.rules.size() > MAX_RULES) {
            this.rules.remove(this.rules.size() - 1);
        }
        for (CtrlWidget widget : this.widgets) {
            widget.clamp();
        }
        for (CtrlRule rule : this.rules) {
            rule.clamp();
        }
        if (this.name == null || this.name.trim().isEmpty()) {
            this.name = "机器控制面板";
        }
        if (this.name.length() > 24) {
            this.name = this.name.substring(0, 24);
        }
    }

    /** True when any rule could act while nobody has the GUI open. */
    public boolean hasAutomation() {
        for (CtrlRule rule : this.rules) {
            if (rule.armed()) {
                return true;
            }
        }
        return false;
    }

    public CtrlPanel copy() {
        CtrlPanel copy = new CtrlPanel();
        copy.name = this.name;
        for (CtrlWidget widget : this.widgets) {
            copy.widgets.add(widget.copy());
        }
        for (int i = 0; i < LINKS; i++) {
            copy.links[i] = this.links[i].copy();
        }
        for (CtrlRule rule : this.rules) {
            copy.rules.add(rule.copy());
        }
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("n", this.name);
        NBTTagList list = new NBTTagList();
        for (CtrlWidget widget : this.widgets) {
            list.appendTag(widget.write());
        }
        tag.setTag("w", list);
        NBTTagList linkTags = new NBTTagList();
        for (int i = 0; i < LINKS; i++) {
            linkTags.appendTag(this.links[i].write());
        }
        tag.setTag("lk", linkTags);
        NBTTagList ruleTags = new NBTTagList();
        for (CtrlRule rule : this.rules) {
            ruleTags.appendTag(rule.write());
        }
        tag.setTag("r", ruleTags);
        return tag;
    }

    public static CtrlPanel read(NBTTagCompound tag) {
        CtrlPanel panel = new CtrlPanel();
        panel.name = tag.getString("n");
        NBTTagList list = tag.getTagList("w", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            panel.widgets.add(CtrlWidget.read(list.getCompoundTagAt(i)));
        }
        NBTTagList linkTags = tag.getTagList("lk", 10);
        for (int i = 0; i < linkTags.tagCount() && i < LINKS; i++) {
            panel.links[i].read(linkTags.getCompoundTagAt(i));
        }
        NBTTagList ruleTags = tag.getTagList("r", 10);
        for (int i = 0; i < ruleTags.tagCount(); i++) {
            panel.rules.add(CtrlRule.read(ruleTags.getCompoundTagAt(i)));
        }
        panel.validate();
        return panel;
    }
}

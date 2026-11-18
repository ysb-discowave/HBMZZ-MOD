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
 * The custom panel: a name and a list of controls, on a fixed grid.
 *
 * <p>The grid is the contract between the editor, the block renderer and the
 * save file. Both the studio canvas and the in-world panel draw the same grid
 * at different scales, so "one cell" means the same thing in the editor and on
 * a 4x3 screen - which is the only way a layout edited in a window can be
 * trusted to look right on a wall.</p>
 *
 * <p>Layout and program are stored separately on purpose. The layout is what
 * an operator rearranges; the program is what an engineer writes. Mixing them
 * into one blob would mean dragging a gauge down two rows also rewrites the
 * automation, and makes both harder to reason about.</p>
 */
public class PanelLayout {

    /** Virtual pixels per grid cell on the 256x192 panel canvas. */
    public static final int GRID = 8;
    /** Grid extent; chosen so the header (18) and the ticker (179) stay clear. */
    public static final int COLS = 31;
    public static final int ROWS = 19;
    /** Top-left corner of the grid on the virtual canvas. */
    public static final int ORIGIN_X = 4;
    public static final int ORIGIN_Y = 20;

    /** Hard cap: past this the panel is unreadable and the save file pointless. */
    public static final int MAX_WIDGETS = 24;

    public String name = "\u81ea\u5b9a\u4e49\u9762\u677f";
    public final List<Widget> widgets = new ArrayList<Widget>();

    public static PanelLayout defaults() {
        PanelLayout layout = new PanelLayout();
        layout.name = "\u9ed8\u8ba4\u4eea\u8868\u677f";

        Widget banner = Widget.of(WidgetType.BANNER, 0, 0);
        banner.gw = COLS;
        banner.gh = 2;
        layout.widgets.add(banner);

        Widget temp = Widget.of(WidgetType.READOUT, 0, 2);
        temp.title = "\u6e29\u5ea6";
        temp.channel = Channel.TEMPERATURE;
        temp.max = 12000.0D;
        temp.gw = 11;
        temp.gh = 5;
        temp.color = 0;
        layout.widgets.add(temp);

        Widget stab = Widget.of(WidgetType.READOUT, 11, 2);
        stab.title = "\u7a33\u5b9a\u5ea6";
        stab.channel = Channel.STABILITY;
        stab.min = 0.0D;
        stab.max = 100.0D;
        stab.gw = 10;
        stab.gh = 5;
        stab.color = 1;
        layout.widgets.add(stab);

        Widget damage = Widget.of(WidgetType.READOUT, 21, 2);
        damage.title = "\u50ac\u5316\u5242\u635f\u4f24";
        damage.channel = Channel.DAMAGE;
        damage.min = 0.0D;
        damage.max = 100.0D;
        damage.decimals = 1;
        damage.gw = 10;
        damage.gh = 5;
        damage.color = 2;
        layout.widgets.add(damage);

        Widget trend = Widget.of(WidgetType.TREND, 0, 7);
        trend.title = "\u6e29\u5ea6\u8d8b\u52bf";
        trend.channel = Channel.TEMPERATURE;
        trend.max = 12000.0D;
        trend.gw = 17;
        trend.gh = 6;
        trend.color = 0;
        layout.widgets.add(trend);

        Widget fuelA = Widget.of(WidgetType.BAR, 17, 7);
        fuelA.title = "\u71c3\u6599A";
        fuelA.channel = Channel.FUEL_A;
        fuelA.gw = 14;
        fuelA.gh = 2;
        fuelA.color = 0;
        layout.widgets.add(fuelA);

        Widget fuelB = Widget.of(WidgetType.BAR, 17, 9);
        fuelB.title = "\u71c3\u6599B";
        fuelB.channel = Channel.FUEL_B;
        fuelB.gw = 14;
        fuelB.gh = 2;
        fuelB.color = 0;
        layout.widgets.add(fuelB);

        Widget lamp = Widget.of(WidgetType.LAMP, 17, 11);
        lamp.title = "\u5806\u82af\u5728\u7ebf";
        lamp.channel = Channel.CORE_ONLINE;
        lamp.gw = 14;
        lamp.gh = 2;
        lamp.color = 1;
        layout.widgets.add(lamp);

        return layout;
    }

    public int size() {
        return this.widgets.size();
    }

    /** Control by index; the renderers walk the layout in order. */
    public Widget get(int index) {
        return this.widgets.get(index);
    }

    /**
     * Adds a control at the first place it fits, scanning top-left first.
     *
     * <p>Not at the cursor: the palette is a single click, and a click that
     * silently lands the control on top of another one reads as a bug. Landing
     * it in the first gap is predictable, and the operator drags it where they
     * want it anyway.</p>
     */
    public Widget add(WidgetType type) {
        if (this.widgets.size() >= MAX_WIDGETS) {
            return null;
        }
        int w = type.defW();
        int h = type.defH();
        for (int y = 0; y + h <= ROWS; y++) {
            for (int x = 0; x + w <= COLS; x++) {
                if (fits(x, y, w, h)) {
                    Widget widget = Widget.of(type, x, y);
                    this.widgets.add(widget);
                    return widget;
                }
            }
        }
        return null;
    }

    private boolean fits(int x, int y, int w, int h) {
        for (Widget widget : this.widgets) {
            if (widget.overlaps(x, y, w, h)) {
                return false;
            }
        }
        return true;
    }

    /** Topmost control under a grid cell, or -1. */
    public int at(int cellX, int cellY) {
        for (int i = this.widgets.size() - 1; i >= 0; i--) {
            if (this.widgets.get(i).contains(cellX, cellY)) {
                return i;
            }
        }
        return -1;
    }

    public void remove(int index) {
        if (index >= 0 && index < this.widgets.size()) {
            this.widgets.remove(index);
        }
    }

    /** True when moving this control to (x,y) would collide with another one. */
    public boolean blocked(int index, int x, int y, int w, int h) {
        for (int i = 0; i < this.widgets.size(); i++) {
            if (i != index && this.widgets.get(i).overlaps(x, y, w, h)) {
                return true;
            }
        }
        return false;
    }

    public PanelLayout copy() {
        PanelLayout copy = new PanelLayout();
        copy.name = this.name;
        for (Widget widget : this.widgets) {
            copy.widgets.add(widget.copy());
        }
        return copy;
    }

    /** Drops anything the server does not want to store: size, count, geometry. */
    public void validate() {
        while (this.widgets.size() > MAX_WIDGETS) {
            this.widgets.remove(this.widgets.size() - 1);
        }
        for (Widget widget : this.widgets) {
            widget.clamp();
        }
        if (this.name == null || this.name.trim().isEmpty()) {
            this.name = "\u81ea\u5b9a\u4e49\u9762\u677f";
        }
        if (this.name.length() > 24) {
            this.name = this.name.substring(0, 24);
        }
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("n", this.name);
        NBTTagList list = new NBTTagList();
        for (Widget widget : this.widgets) {
            list.appendTag(widget.write());
        }
        tag.setTag("w", list);
        return tag;
    }

    public static PanelLayout read(NBTTagCompound tag) {
        PanelLayout layout = new PanelLayout();
        layout.name = tag.getString("n");
        NBTTagList list = tag.getTagList("w", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            layout.widgets.add(Widget.read(list.getCompoundTagAt(i)));
        }
        layout.validate();
        return layout;
    }
}

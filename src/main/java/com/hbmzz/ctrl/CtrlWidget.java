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
 * One control on a machine panel: where it sits, what it is, which machine it
 * talks to, and what it currently reads.
 *
 * <p>The design fields (type, position, binding, range) are saved with the
 * world; {@link #value} and {@link #text} are runtime, refreshed from the
 * machine every half second and shipped to whoever is looking. Keeping both in
 * one object is deliberate: the renderer needs them together, and a separate
 * runtime map would have to be keyed by something that survives the control
 * list being edited - index, which changes the moment a control is deleted.</p>
 */
public class CtrlWidget {

    public CtrlType type = CtrlType.LAMP;
    public int gx;
    public int gy;
    public int gw = 2;
    public int gh = 2;
    /** Caption painted on the control. */
    public String label = "控件";
    /** Link slot index 0-5 (A-F), or -1 when not bound to anything. */
    public int link = -1;
    /** Machine data key this readout shows, e.g. {@code heat} or {@code level}. */
    public String key = "";
    /** Machine event this control fires, e.g. {@code rbmk_ctrl_set_level}. */
    public String event = "";
    /** Name of the variable inside that event, e.g. {@code level}. */
    public String var = "";
    public double min;
    public double max = 100.0D;
    public String unit = "";
    public int decimals;
    /** Positions for {@link CtrlType#ROTARY}; ignored by the others. */
    public int steps = 4;
    public int color;

    /** Runtime: what the machine currently reports, or what the player set. */
    public double value;
    public String text = "";
    /** Runtime: a pushed emergency stop stays pushed until released. */
    public boolean latched;

    public static CtrlWidget of(CtrlType type, int gx, int gy) {
        CtrlWidget widget = new CtrlWidget();
        widget.type = type;
        widget.gx = gx;
        widget.gy = gy;
        widget.gw = type.defW();
        widget.gh = type.defH();
        widget.label = type.label();
        widget.max = 100.0D;
        return widget;
    }

    public boolean isOperator() {
        return this.type.isOperator();
    }

    /** True when this control has somewhere to read from. */
    public boolean bound() {
        return this.link >= 0 && this.link < CtrlPanel.LINKS;
    }

    public boolean contains(int cellX, int cellY) {
        return cellX >= this.gx && cellX < this.gx + this.gw
                && cellY >= this.gy && cellY < this.gy + this.gh;
    }

    public boolean overlaps(int x, int y, int w, int h) {
        return this.gx < x + w && this.gx + this.gw > x
                && this.gy < y + h && this.gy + this.gh > y;
    }

    /** 0..1 position inside the range, clamped. Drives every dial and slider. */
    public double ratio() {
        double span = this.max - this.min;
        if (span <= 0.0D) {
            return 0.0D;
        }
        double r = (this.value - this.min) / span;
        return r < 0.0D ? 0.0D : (r > 1.0D ? 1.0D : r);
    }

    /** Value printed on the control, already carrying its unit. */
    public String displayText() {
        if (!this.text.isEmpty()) {
            return this.text;
        }
        if (this.type.isBooleanish()) {
            return this.value >= 0.5D ? "是" : "否";
        }
        String number = this.decimals <= 0
                ? String.valueOf((long) Math.rint(this.value))
                : String.format(java.util.Locale.ROOT, "%." + this.decimals + "f", this.value);
        return this.unit.isEmpty() ? number : number + this.unit;
    }

    /** Trims the geometry and the numeric range into something savable. */
    public void clamp() {
        this.gw = Math.max(this.type.minW(), Math.min(CtrlPanel.COLS, this.gw));
        this.gh = Math.max(this.type.minH(), Math.min(CtrlPanel.ROWS, this.gh));
        this.gx = Math.max(0, Math.min(CtrlPanel.COLS - this.gw, this.gx));
        this.gy = Math.max(0, Math.min(CtrlPanel.ROWS - this.gh, this.gy));
        this.link = this.link < 0 ? -1 : Math.min(CtrlPanel.LINKS - 1, this.link);
        if (this.max <= this.min) {
            this.max = this.min + 1.0D;
        }
        this.decimals = Math.max(0, Math.min(3, this.decimals));
        this.steps = Math.max(2, Math.min(16, this.steps));
        this.value = Math.max(this.min, Math.min(this.max, this.value));
        if (this.label == null || this.label.trim().isEmpty()) {
            this.label = this.type.label();
        }
        // Wide enough for a sentence on a full-width label, short enough that
        // a caption on a 2-cell lamp stays a caption. The painter truncates to
        // the box anyway; this only stops absurd strings from being saved.
        if (this.label.length() > 24) {
            this.label = this.label.substring(0, 24);
        }
        if (this.key == null) {
            this.key = "";
        }
        if (this.event == null) {
            this.event = "";
        }
        if (this.var == null) {
            this.var = "";
        }
        if (this.unit == null) {
            this.unit = "";
        }
        if (this.unit.length() > 6) {
            this.unit = this.unit.substring(0, 6);
        }
        if (this.text == null) {
            this.text = "";
        }
    }

    public CtrlWidget copy() {
        CtrlWidget copy = new CtrlWidget();
        copy.type = this.type;
        copy.gx = this.gx;
        copy.gy = this.gy;
        copy.gw = this.gw;
        copy.gh = this.gh;
        copy.label = this.label;
        copy.link = this.link;
        copy.key = this.key;
        copy.event = this.event;
        copy.var = this.var;
        copy.min = this.min;
        copy.max = this.max;
        copy.unit = this.unit;
        copy.decimals = this.decimals;
        copy.steps = this.steps;
        copy.color = this.color;
        copy.value = this.value;
        copy.text = this.text;
        copy.latched = this.latched;
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("t", this.type.name());
        tag.setInteger("x", this.gx);
        tag.setInteger("y", this.gy);
        tag.setInteger("w", this.gw);
        tag.setInteger("h", this.gh);
        tag.setString("l", this.label);
        tag.setInteger("k", this.link);
        tag.setString("ky", this.key);
        tag.setString("ev", this.event);
        tag.setString("vr", this.var);
        tag.setDouble("mn", this.min);
        tag.setDouble("mx", this.max);
        tag.setString("un", this.unit);
        tag.setInteger("dc", this.decimals);
        tag.setInteger("st", this.steps);
        tag.setInteger("cl", this.color);
        tag.setDouble("v", this.value);
        tag.setString("tx", this.text);
        tag.setBoolean("la", this.latched);
        return tag;
    }

    public static CtrlWidget read(NBTTagCompound tag) {
        CtrlWidget widget = new CtrlWidget();
        widget.type = CtrlType.byName(tag.getString("t"));
        widget.gx = tag.getInteger("x");
        widget.gy = tag.getInteger("y");
        widget.gw = tag.getInteger("w") > 0 ? tag.getInteger("w") : widget.type.defW();
        widget.gh = tag.getInteger("h") > 0 ? tag.getInteger("h") : widget.type.defH();
        widget.label = tag.getString("l");
        widget.link = tag.hasKey("k") ? tag.getInteger("k") : -1;
        widget.key = tag.getString("ky");
        widget.event = tag.getString("ev");
        widget.var = tag.getString("vr");
        widget.min = tag.getDouble("mn");
        widget.max = tag.getDouble("mx");
        widget.unit = tag.getString("un");
        widget.decimals = tag.getInteger("dc");
        widget.steps = tag.getInteger("st") > 0 ? tag.getInteger("st") : 4;
        widget.color = tag.getInteger("cl");
        widget.value = tag.getDouble("v");
        widget.text = tag.getString("tx");
        widget.latched = tag.getBoolean("la");
        widget.clamp();
        return widget;
    }
}

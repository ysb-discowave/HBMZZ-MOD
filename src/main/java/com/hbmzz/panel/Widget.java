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
 * One control on the custom panel: what it is, where it sits, what it reads.
 *
 * <p>Geometry is in grid cells of the panel's virtual canvas, not in pixels.
 * That is what lets the same layout survive a 1x1 screen and a 4x3 screen: the
 * canvas scales uniformly, the grid scales with it, and nothing is ever
 * stretched or re-flowed. A widget is never allowed to leave the grid, so a
 * layout that was valid on a small panel is still valid on a large one.</p>
 */
public class Widget {

    public WidgetType type = WidgetType.READOUT;
    public int gx;
    public int gy;
    public int gw = 8;
    public int gh = 4;

    public Channel channel = Channel.TEMPERATURE;
    public String title = "\u6e29\u5ea6";
    /** Static body text, only for {@link WidgetType#LABEL}. */
    public String text = "";

    /** Display range for bar and dial; a readout ignores it. */
    public double min;
    public double max = 10000.0D;
    public int decimals = 0;
    /** Palette index: 0 amber, 1 mint, 2 red, 3 cream, 4 dim. */
    public int color;

    public static Widget of(WidgetType type, int gx, int gy) {
        Widget widget = new Widget();
        widget.type = type;
        widget.gx = Math.max(0, gx);
        widget.gy = Math.max(0, gy);
        widget.gw = type.defW();
        widget.gh = type.defH();
        widget.max = widget.channel.max();
        switch (type) {
            case READOUT:
                widget.title = "\u6e29\u5ea6";
                widget.channel = Channel.TEMPERATURE;
                widget.max = 12000.0D;
                break;
            case BAR:
                widget.title = "\u7a33\u5b9a\u5ea6";
                widget.channel = Channel.STABILITY;
                widget.min = 0.0D;
                widget.max = 100.0D;
                break;
            case DIAL:
                widget.title = "\u6e29\u5ea6";
                widget.channel = Channel.TEMPERATURE;
                widget.max = 12000.0D;
                break;
            case LAMP:
                widget.title = "\u5806\u82af\u5728\u7ebf";
                widget.channel = Channel.CORE_ONLINE;
                widget.min = 0.0D;
                widget.max = 1.0D;
                break;
            case LABEL:
                widget.title = "";
                widget.text = "\u81ea\u5b9a\u4e49\u9762\u677f";
                break;
            case TREND:
                widget.title = "\u6e29\u5ea6\u8d8b\u52bf";
                widget.channel = Channel.TEMPERATURE;
                widget.max = 12000.0D;
                break;
            case BANNER:
                widget.title = "";
                break;
            default:
                break;
        }
        return widget;
    }

    /** Current reading of this widget's channel. */
    public double value(DFCSnapshot snapshot) {
        return this.channel == null ? 0.0D : this.channel.read(snapshot);
    }

    public double ratio(double value) {
        double span = this.max - this.min;
        if (span <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (value - this.min) / span));
    }

    /** Keeps the rectangle inside the grid, honouring the minimum useful size. */
    public void clamp() {
        this.gw = Math.max(minGw(), Math.min(PanelLayout.COLS - this.gx, this.gw));
        this.gh = Math.max(minGh(), Math.min(PanelLayout.ROWS - this.gy, this.gh));
        this.gx = Math.max(0, Math.min(PanelLayout.COLS - this.gw, this.gx));
        this.gy = Math.max(0, Math.min(PanelLayout.ROWS - this.gh, this.gy));
        if (this.max <= this.min) {
            this.max = this.min + 1.0D;
        }
        this.decimals = Math.max(0, Math.min(3, this.decimals));
        this.color = Math.max(0, Math.min(4, this.color));
    }

    public int minGw() {
        switch (this.type) {
            case DIAL:
                return 5;
            case BANNER:
                return 8;
            default:
                return 4;
        }
    }

    public int minGh() {
        switch (this.type) {
            case DIAL:
                return 5;
            case READOUT:
                return 3;
            default:
                return 2;
        }
    }

    public boolean overlaps(int x, int y, int w, int h) {
        return this.gx < x + w && x < this.gx + this.gw
                && this.gy < y + h && y < this.gy + this.gh;
    }

    public boolean contains(int cellX, int cellY) {
        return cellX >= this.gx && cellX < this.gx + this.gw
                && cellY >= this.gy && cellY < this.gy + this.gh;
    }

    public Widget copy() {
        Widget copy = new Widget();
        copy.type = this.type;
        copy.gx = this.gx;
        copy.gy = this.gy;
        copy.gw = this.gw;
        copy.gh = this.gh;
        copy.channel = this.channel;
        copy.title = this.title;
        copy.text = this.text;
        copy.min = this.min;
        copy.max = this.max;
        copy.decimals = this.decimals;
        copy.color = this.color;
        return copy;
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("t", this.type.name());
        tag.setInteger("x", this.gx);
        tag.setInteger("y", this.gy);
        tag.setInteger("w", this.gw);
        tag.setInteger("h", this.gh);
        tag.setString("ch", this.channel == null ? Channel.TEMPERATURE.name() : this.channel.name());
        tag.setString("ti", this.title == null ? "" : this.title);
        tag.setString("tx", this.text == null ? "" : this.text);
        tag.setDouble("mn", this.min);
        tag.setDouble("mx", this.max);
        tag.setInteger("dc", this.decimals);
        tag.setInteger("cl", this.color);
        return tag;
    }

    public static Widget read(NBTTagCompound tag) {
        Widget widget = new Widget();
        widget.type = WidgetType.byName(tag.getString("t"));
        widget.gx = tag.getInteger("x");
        widget.gy = tag.getInteger("y");
        widget.gw = tag.getInteger("w") > 0 ? tag.getInteger("w") : widget.type.defW();
        widget.gh = tag.getInteger("h") > 0 ? tag.getInteger("h") : widget.type.defH();
        widget.channel = Channel.byName(tag.getString("ch"));
        widget.title = tag.getString("ti");
        widget.text = tag.getString("tx");
        widget.min = tag.getDouble("mn");
        widget.max = tag.getDouble("mx") > 0.0D ? tag.getDouble("mx") : 100.0D;
        widget.decimals = tag.getInteger("dc");
        widget.color = tag.getInteger("cl");
        widget.clamp();
        return widget;
    }
}

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

package com.hbmzz.client.screen;

import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.panel.Action;
import com.hbmzz.panel.ActionType;
import com.hbmzz.panel.Channel;
import com.hbmzz.panel.Condition;
import com.hbmzz.panel.LogicProgram;
import com.hbmzz.panel.PanelLayout;
import com.hbmzz.panel.Rule;
import com.hbmzz.panel.Widget;
import com.hbmzz.panel.WidgetType;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The panel studio's drawing surface.
 *
 * <p>Same technique as the terminal: one AWT image per frame, uploaded once and
 * painted as a quad, with a hotspot list rebuilt every frame so a hit box can
 * never go stale after the layout changes. What is different is that this window
 * has three columns that change meaning with the active tab - and a canvas that
 * has to show the <em>real</em> panel, at a different scale, inside it.</p>
 *
 * <h3>The three views</h3>
 * <ul>
 *   <li><b>控件设计</b> - palette, grid canvas, property sheet. Drag to move,
 *       right click to delete.</li>
 *   <li><b>逻辑编排</b> - rule list, the selected rule as three stacked block
 *       groups (当 / 若 / 则), and an inspector for whatever block is selected.</li>
 *   <li><b>预设库</b> - saved designs, plus copy/paste between monitors.</li>
 * </ul>
 *
 * <p>Coordinates here are real GUI pixels. The only place a virtual grid
 * appears is inside {@link #canvas}, where the panel is drawn through a
 * translate+scale transform - which is exactly why the design view can show
 * the true rendering instead of an approximation.</p>
 */
public final class StudioCanvas {

    // ------------------------------------------------------------------ palette

    private static final int BG = 0xFF0A0908;
    private static final int PANEL = 0xFF15120E;
    private static final int PANEL_HI = 0xFF1E1A14;
    private static final int LINE = 0xFF3A3021;
    private static final int TXT = 0xFFEDE1C4;
    private static final int DIM = 0xFF9A8967;
    private static final int FAINT = 0xFF5E5140;
    private static final int AMBER = 0xFFFFB03A;
    private static final int MINT = 0xFF5FD9C0;
    private static final int CRIT = 0xFFFF5C3C;
    private static final int OK = 0xFF8CD96A;

    private static final int SS = 2;

    public static final int MODE_DESIGN = 0;
    public static final int MODE_LOGIC = 1;
    public static final int MODE_PRESET = 2;

    public static final int FIELD_NONE = 0;
    public static final int FIELD_WIDGET_TITLE = 1;
    public static final int FIELD_LABEL_TEXT = 2;
    public static final int FIELD_RULE_NAME = 3;
    public static final int FIELD_ACTION_TEXT = 4;
    public static final int FIELD_PRESET_NAME = 5;

    /** One clickable region. {@code id} is a small command string the GUI parses. */
    public static final class Hotspot {
        public final String id;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        Hotspot(String id, int x, int y, int w, int h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean hit(int mx, int my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    // ------------------------------------------------------------------- state

    private int w = 760;
    private int h = 440;
    private BufferedImage image;
    private int[] pixels;
    private Graphics2D g;
    private int texId;
    private ByteBuffer upload;
    private boolean dirty = true;

    private final List<Hotspot> spots = new ArrayList<Hotspot>();

    private PanelLayout layout;
    private LogicProgram program;
    private int mode;
    private int selWidget = -1;
    private int selRule;
    private int selCond = -1;
    private int selAction = -1;

    private DFCSnapshot live;
    private int alarmLevel = -1;
    private String alarmText = "";
    private String status = "";
    private List<String> presetNames = new ArrayList<String>();
    private int selPreset = -1;
    private boolean dragging;

    private int mouseX;
    private int mouseY;

    /** Text box the GUI has to overlay with a real text field. */
    private int fieldKind = FIELD_NONE;
    private int[] fieldRect = new int[]{0, 0, 0, 0};

    /** Transform of the design canvas, kept for hit testing after the frame. */
    private float panelScale = 1.0F;
    private int panelX;
    private int panelY;

    // =================================================================== surface

    public StudioCanvas() {
    }

    public void resize(int width, int height) {
        int ww = Math.max(480, width);
        int hh = Math.max(320, height);
        if (ww == this.w && hh == this.h && this.image != null) {
            return;
        }
        this.w = ww;
        this.h = hh;
        this.image = new BufferedImage(ww * SS, hh * SS, BufferedImage.TYPE_INT_ARGB);
        this.pixels = new int[ww * SS * hh * SS];
        this.g = this.image.createGraphics();
        this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        this.g.scale(SS, SS);
        this.dirty = true;
    }

    public int width() {
        return this.w;
    }

    public int height() {
        return this.h;
    }

    public int textureId() {
        return this.texId;
    }

    public void markDirty() {
        this.dirty = true;
    }

    public void setModel(PanelLayout layout, LogicProgram program) {
        this.layout = layout;
        this.program = program;
    }

    public void setView(int mode, int widget, int rule, int cond, int action) {
        if (this.mode != mode || this.selWidget != widget || this.selRule != rule
                || this.selCond != cond || this.selAction != action) {
            this.dirty = true;
        }
        this.mode = mode;
        this.selWidget = widget;
        this.selRule = rule;
        this.selCond = cond;
        this.selAction = action;
    }

    public void setLive(DFCSnapshot snapshot, int level, String text) {
        this.live = snapshot;
        this.alarmLevel = level;
        this.alarmText = text == null ? "" : text;
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
    }

    public void setPresets(List<String> names, int selected) {
        this.presetNames = names == null ? new ArrayList<String>() : names;
        this.selPreset = selected;
    }

    public void setDragging(boolean dragging) {
        this.dragging = dragging;
    }

    public int fieldKind() {
        return this.fieldKind;
    }

    public int[] fieldRect() {
        return this.fieldRect;
    }

    public String hitAt(int x, int y) {
        for (int i = this.spots.size() - 1; i >= 0; i--) {
            Hotspot spot = this.spots.get(i);
            if (spot.hit(x, y)) {
                return spot.id;
            }
        }
        return "";
    }

    /**
     * Grid cell under a screen point on the design canvas.
     *
     * @return {gx, gy}, or null when the point is outside the canvas
     */
    public int[] cellAt(int x, int y) {
        if (this.panelScale <= 0.0F) {
            return null;
        }
        double vx = (x - this.panelX) / this.panelScale;
        double vy = (y - this.panelY) / this.panelScale;
        int gx = (int) Math.floor((vx - PanelLayout.ORIGIN_X) / PanelLayout.GRID);
        int gy = (int) Math.floor((vy - PanelLayout.ORIGIN_Y) / PanelLayout.GRID);
        if (gx < 0 || gy < 0 || gx >= PanelLayout.COLS || gy >= PanelLayout.ROWS) {
            return null;
        }
        return new int[]{gx, gy};
    }

    // ==================================================================== frame

    public void render(int mouseX, int mouseY) {
        if (this.image == null || this.layout == null || this.program == null) {
            return;
        }
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.spots.clear();
        this.fieldKind = FIELD_NONE;
        this.fieldRect = new int[]{0, 0, 0, 0};

        this.g.setColor(new Color(BG, true));
        this.g.fillRect(0, 0, this.w, this.h);
        header();
        footer();

        int bodyY = 34;
        int bodyH = this.h - 34 - 36;
        int leftW = 130;
        int rightW = 174;
        int leftX = 8;
        int rightX = this.w - rightW - 8;
        int midX = leftX + leftW + 8;
        int midW = rightX - midX - 8;
        if (midW < 120) {
            midW = 120;
        }

        switch (this.mode) {
            case MODE_LOGIC:
                ruleList(leftX, bodyY, leftW, bodyH);
                ruleBlocks(midX, bodyY, midW, bodyH);
                inspectorLogic(rightX, bodyY, rightW, bodyH);
                break;
            case MODE_PRESET:
                presetList(leftX, bodyY, leftW, bodyH);
                presetSummary(midX, bodyY, midW, bodyH);
                presetActions(rightX, bodyY, rightW, bodyH);
                break;
            case MODE_DESIGN:
            default:
                widgetPalette(leftX, bodyY, leftW, bodyH);
                canvas(midX, bodyY, midW, bodyH);
                inspectorWidget(rightX, bodyY, rightW, bodyH);
                break;
        }
        this.dirty = true;
    }

    private void header() {
        rect(0, 0, this.w, 32, PANEL);
        hline(0, 31, this.w, LINE);
        rect(8, 9, 3, 14, AMBER);
        text("AHBSSMFI-OS", 16, 21, TXT, ScreenFonts.monoBold(11));
        text("\u9762\u677f\u5de5\u574a", 16 + width("AHBSSMFI-OS", ScreenFonts.monoBold(11)) + 6, 21,
                FAINT, ScreenFonts.cjk(8));

        String[] tabs = {"\u63a7\u4ef6\u8bbe\u8ba1", "\u903b\u8f91\u7f16\u6392", "\u9884\u8bbe\u5e93"};
        int tx = 172;
        for (int i = 0; i < tabs.length; i++) {
            boolean active = this.mode == i;
            boolean hover = in(tx, 6, 84, 21);
            rect(tx, 6, 84, 21, active ? 0xFF2A210F : (hover ? PANEL_HI : PANEL));
            frameRect(tx, 6, 84, 21, active ? AMBER : (hover ? 0xFF6B5A3E : LINE));
            if (active) {
                rect(tx, 6, 84, 2, AMBER);
            }
            textCenterIn(tabs[i], tx, 84, 21, active ? AMBER : (hover ? TXT : DIM),
                    ScreenFonts.cjk(9));
            this.spots.add(new Hotspot("tab:" + i, tx, 6, 84, 21));
            tx += 88;
        }

        int cx = this.w - 26;
        boolean closeHover = in(cx, 8, 18, 18);
        rect(cx, 8, 18, 18, closeHover ? 0xFF4A1A12 : PANEL_HI);
        frameRect(cx, 8, 18, 18, closeHover ? CRIT : LINE);
        textCenterIn("x", cx, 18, 19, closeHover ? 0xFFFFC8BC : DIM, ScreenFonts.mono(10));
        this.spots.add(new Hotspot("close", cx, 8, 18, 18));
    }

    private void footer() {
        int y = this.h - 36;
        rect(0, y, this.w, 36, PANEL);
        hline(0, y, this.w, LINE);

        String name = this.layout.name == null ? "" : this.layout.name;
        text("\u9762\u677f " + name, 10, y + 14, TXT, ScreenFonts.cjk(9));
        text(this.status, 10, y + 27, FAINT, ScreenFonts.cjk(7));

        String[] labels = {"\u8fd8\u539f", "\u590d\u5236", "\u7c98\u8d34", "\u4fdd\u5b58\u5e76\u5e94\u7528"};
        String[] ids = {"reset", "copy", "paste", "save"};
        int bw = 76;
        int bx = this.w - 10 - bw;
        for (int i = labels.length - 1; i >= 0; i--) {
            boolean primary = i == labels.length - 1;
            boolean hover = in(bx, y + 8, bw, 20);
            rect(bx, y + 8, bw, 20, primary ? (hover ? 0xFF3B2C10 : 0xFF2A210F)
                    : (hover ? PANEL_HI : PANEL));
            frameRect(bx, y + 8, bw, 20, primary ? AMBER : (hover ? 0xFF6B5A3E : LINE));
            textCenterIn(labels[i], bx, bw, y + 22,
                    primary ? AMBER : (hover ? TXT : DIM), ScreenFonts.cjk(8));
            this.spots.add(new Hotspot(ids[i], bx, y + 8, bw, 20));
            bx -= bw + 6;
        }
    }

    // =============================================================== design tab

    private void widgetPalette(int x, int y, int wid, int hei) {
        column(x, y, wid, hei, "\u63a7\u4ef6\u5e93");
        int rowY = y + 24;
        WidgetType[] types = WidgetType.values();
        for (WidgetType type : types) {
            if (rowY + 30 > y + hei - 4) {
                break;
            }
            boolean hover = in(x + 5, rowY, wid - 10, 28);
            rect(x + 5, rowY, wid - 10, 28, hover ? PANEL_HI : PANEL);
            frameRect(x + 5, rowY, wid - 10, 28, hover ? AMBER : LINE);
            text(type.label(), x + 11, rowY + 12, hover ? AMBER : TXT, ScreenFonts.cjk(9));
            text(type.desc(), x + 11, rowY + 22, FAINT, ScreenFonts.cjk(6));
            this.spots.add(new Hotspot("add:" + type.name(), x + 5, rowY, wid - 10, 28));
            rowY += 31;
        }
        text("\u70b9\u51fb\u6dfb\u52a0\u5230\u753b\u5e03", x + 6, y + hei - 6, FAINT, ScreenFonts.cjk(7));
    }

    /** The panel itself, drawn through the same painter the block uses. */
    private void canvas(int x, int y, int wid, int hei) {
        rect(x, y, wid, hei, 0xFF080706);
        frameRect(x, y, wid, hei, LINE);
        rect(x + 1, y + 1, wid - 2, 15, PANEL);
        hline(x + 1, y + 16, wid - 2, LINE);
        text("\u753b\u5e03  " + PanelLayout.COLS + " \u00d7 " + PanelLayout.ROWS + " \u683c",
                x + 6, y + 12, FAINT, ScreenFonts.cjk(8));
        textRight("\u62d6\u52a8\u79fb\u52a8  \u00b7  \u53f3\u952e\u5220\u9664", x + wid - 6, y + 12,
                FAINT, ScreenFonts.cjk(7));

        int cw = wid - 12;
        int ch = hei - 26;
        float k = Math.min(cw / (float) 256, ch / (float) 192);
        int pw = Math.round(256 * k);
        int ph = Math.round(192 * k);
        int px = x + 6 + (cw - pw) / 2;
        int py = y + 22 + (ch - ph) / 2;
        this.panelScale = k;
        this.panelX = px;
        this.panelY = py;

        java.awt.geom.AffineTransform old = this.g.getTransform();
        this.g.translate(px, py);
        this.g.scale(k, k);

        rect(0, 0, 256, 192, 0xFF0A0908);
        // Header and ticker, so the design view shows the real usable band.
        rect(0, 0, 256, 18, PANEL);
        hline(0, 17, 256, LINE);
        text(DfcScreen.OS_NAME, 12, 12, TXT, ScreenFonts.monoBold(8));
        text("04 \u81ea\u5b9a\u4e49", 71, 12, AMBER, ScreenFonts.cjk(8));
        hline(0, 179, 256, LINE);

        for (int gx = 0; gx <= PanelLayout.COLS; gx++) {
            int vx = PanelLayout.ORIGIN_X + gx * PanelLayout.GRID;
            vline(vx, PanelLayout.ORIGIN_Y, PanelLayout.ROWS * PanelLayout.GRID, 0x18FFFFFF);
        }
        for (int gy = 0; gy <= PanelLayout.ROWS; gy++) {
            int vy = PanelLayout.ORIGIN_Y + gy * PanelLayout.GRID;
            hline(PanelLayout.ORIGIN_X, vy, PanelLayout.COLS * PanelLayout.GRID, 0x18FFFFFF);
        }

        for (int i = 0; i < this.layout.size(); i++) {
            Widget widget = this.layout.get(i);
            WidgetPainter.Ctx ctx = new WidgetPainter.Ctx();
            ctx.value = widget.value(this.live);
            ctx.banner = this.alarmText;
            ctx.level = Math.max(0, this.alarmLevel);
            ctx.active = this.alarmLevel >= 0 && !this.alarmText.isEmpty();
            ctx.selected = i == this.selWidget;
            ctx.editor = true;
            if (widget.type == WidgetType.TREND) {
                ctx.trend = new double[0];
                ctx.trendCount = 0;
            }
            WidgetPainter.paint(this.g, PanelLayout.ORIGIN_X + widget.gx * PanelLayout.GRID,
                    PanelLayout.ORIGIN_Y + widget.gy * PanelLayout.GRID,
                    widget.gw * PanelLayout.GRID, widget.gh * PanelLayout.GRID, widget, ctx);
        }

        if (this.dragging) {
            int[] cell = cellAt(this.mouseX, this.mouseY);
            if (cell != null) {
                rect(PanelLayout.ORIGIN_X + cell[0] * PanelLayout.GRID,
                        PanelLayout.ORIGIN_Y + cell[1] * PanelLayout.GRID,
                        PanelLayout.GRID, PanelLayout.GRID, 0x40FFB03A);
            }
        }

        this.g.setTransform(old);
        frameRect(px, py, pw, ph, 0xFF6B5A3E);

        // Hit boxes in window pixels, added after the transform is restored.
        for (int i = 0; i < this.layout.size(); i++) {
            Widget widget = this.layout.get(i);
            int sx = px + Math.round((PanelLayout.ORIGIN_X + widget.gx * PanelLayout.GRID) * k);
            int sy = py + Math.round((PanelLayout.ORIGIN_Y + widget.gy * PanelLayout.GRID) * k);
            int sw = Math.round(widget.gw * PanelLayout.GRID * k);
            int sh = Math.round(widget.gh * PanelLayout.GRID * k);
            this.spots.add(new Hotspot("w:" + i, sx, sy, sw, sh));
        }

        if (this.layout.size() == 0) {
            textCenterIn("\u753b\u5e03\u4e3a\u7a7a\uff0c\u4ece\u5de6\u4fa7\u6dfb\u52a0\u63a7\u4ef6",
                    px, pw, py + ph / 2, WARN(), ScreenFonts.cjk(9));
        }
    }

    private void inspectorWidget(int x, int y, int wid, int hei) {
        column(x, y, wid, hei, "\u63a7\u4ef6\u5c5e\u6027");
        if (this.selWidget < 0 || this.selWidget >= this.layout.size()) {
            wrapText("\u5728\u753b\u5e03\u4e0a\u9009\u4e2d\u4e00\u4e2a\u63a7\u4ef6\uff0c"
                    + "\u8fd9\u91cc\u5c31\u4f1a\u663e\u793a\u5b83\u7684\u5c5e\u6027\u3002",
                    x + 8, y + 40, wid - 16, FAINT, ScreenFonts.cjk(7), 3);
            return;
        }
        Widget widget = this.layout.get(this.selWidget);
        int rowY = y + 26;
        int rowH = 24;

        text("\u7c7b\u578b", x + 8, rowY + 11, DIM, ScreenFonts.cjk(7));
        textRight(widget.type.label(), x + wid - 8, rowY + 11, AMBER, ScreenFonts.cjk(8));
        rowY += 18;

        if (widget.type.needsText()) {
            text("\u6587\u672c", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
            fieldBox(x + 8, rowY + 18, wid - 16, FIELD_LABEL_TEXT);
            rowY += 40;
        } else {
            text("\u6807\u9898", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
            fieldBox(x + 8, rowY + 18, wid - 16, FIELD_WIDGET_TITLE);
            rowY += 40;
        }

        if (widget.type.needsChannel()) {
            channelRow(x, rowY, wid, "\u901a\u9053", widget.channel, "ch");
            rowY += rowH;
            stepperRow(x, rowY, wid, "\u6700\u5c0f", fmt(widget.min), "min");
            rowY += rowH;
            stepperRow(x, rowY, wid, "\u6700\u5927", fmt(widget.max), "max");
            rowY += rowH;
        }
        stepperRow(x, rowY, wid, "\u5c0f\u6570", String.valueOf(widget.decimals), "dec");
        rowY += rowH;

        text("\u989c\u8272", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
        String[] colorNames = {"\u7425\u73c0", "\u8584\u8377", "\u544a\u8b66", "\u7c73\u767d", "\u6697\u91d1"};
        chip(x + 52, rowY + 3, wid - 60, 17, colorNames[Math.max(0, Math.min(4, widget.color))],
                "color", false);
        rowY += rowH;

        stepperRow(x, rowY, wid, "\u5bbd\u5ea6", widget.gw + " \u683c", "gw");
        rowY += rowH;
        stepperRow(x, rowY, wid, "\u9ad8\u5ea6", widget.gh + " \u683c", "gh");
        rowY += rowH + 4;

        chip(x + 8, rowY, wid - 16, 20, "\u5220\u9664\u63a7\u4ef6", "w:del", false);
    }

    // ================================================================ logic tab

    private void ruleList(int x, int y, int wid, int hei) {
        column(x, y, wid, hei, "\u89c4\u5219\u5217\u8868");
        int rowY = y + 24;
        for (int i = 0; i < this.program.rules.size(); i++) {
            if (rowY + 24 > y + hei - 28) {
                break;
            }
            Rule rule = this.program.rules.get(i);
            boolean active = i == this.selRule;
            boolean hover = in(x + 5, rowY, wid - 10, 22);
            rect(x + 5, rowY, wid - 10, 22, active ? 0xFF2A210F : (hover ? PANEL_HI : PANEL));
            frameRect(x + 5, rowY, wid - 10, 22, active ? AMBER : LINE);
            rect(x + 9, rowY + 8, 3, 6, rule.enabled ? OK : FAINT);
            text(clip(rule.name, wid - 34, ScreenFonts.cjk(8)), x + 16, rowY + 15,
                    active ? AMBER : TXT, ScreenFonts.cjk(8));
            this.spots.add(new Hotspot("rule:" + i, x + 5, rowY, wid - 10, 22));
            rowY += 24;
        }
        if (this.program.rules.size() < LogicProgram.MAX_RULES) {
            chip(x + 5, y + hei - 26, wid - 10, 20, "+ \u65b0\u589e\u89c4\u5219", "rule:add", false);
        }
    }

    /** The selected rule as three stacked block groups: 当 / 若 / 则. */
    private void ruleBlocks(int x, int y, int wid, int hei) {
        if (this.selRule < 0 || this.selRule >= this.program.rules.size()) {
            rect(x, y, wid, hei, 0xFF080706);
            frameRect(x, y, wid, hei, LINE);
            textCenterIn("\u5148\u5728\u5de6\u4fa7\u9009\u4e2d\u4e00\u6761\u89c4\u5219",
                    x, wid, y + hei / 2, FAINT, ScreenFonts.cjk(9));
            return;
        }
        Rule rule = this.program.rules.get(this.selRule);
        rect(x, y, wid, hei, 0xFF080706);
        frameRect(x, y, wid, hei, LINE);

        int px = x + 8;
        int pw = wid - 16;
        int cursor = y + 8;

        // ---- 当 ----
        cursor = groupHeader(px, cursor, pw, "\u5f53", "\u4e8b\u4ef6", AMBER);
        chip(px + 6, cursor, 96, 19, Rule.TRIGGER_NAMES[rule.trigger], "trig", true);
        String hint = Rule.TRIGGER_HINTS[rule.trigger];
        text(clip(hint, pw - 110, ScreenFonts.cjk(7)), px + 110, cursor + 13, DIM, ScreenFonts.cjk(7));
        cursor += 24;
        if (rule.trigger == Rule.TRIGGER_INTERVAL) {
            text("\u6bcf", px + 6, cursor + 13, DIM, ScreenFonts.cjk(7));
            stepperInline(px + 20, cursor, 74, rule.interval + " \u523b", "iv");
            cursor += 22;
        }
        text("\u51b7\u5374", px + 6, cursor + 13, DIM, ScreenFonts.cjk(7));
        stepperInline(px + 40, cursor, 74, rule.cooldown + " \u79d2", "cd");
        chip(px + pw - 58, cursor, 52, 18, rule.enabled ? "\u5df2\u542f\u7528" : "\u5df2\u505c\u7528",
                "rule:enabled", rule.enabled);
        cursor += 30;

        // ---- 若 ----
        if (cursor + 40 < y + hei) {
            cursor = groupHeader(px, cursor, pw, "\u82e5", "\u6761\u4ef6", MINT);
            String junction = rule.junction == 1 ? "\u6ee1\u8db3\u4efb\u610f" : "\u6ee1\u8db3\u5168\u90e8";
            chip(px + 6, cursor, 72, 18, junction, "junc", rule.junction == 1);
            text(rule.junction == 1 ? "\u4efb\u4e00\u6761\u4ef6\u6210\u7acb\u5373\u89e6\u53d1"
                            : "\u6240\u6709\u6761\u4ef6\u90fd\u6210\u7acb\u624d\u89e6\u53d1",
                    px + 84, cursor + 13, FAINT, ScreenFonts.cjk(7));
            cursor += 22;

            for (int i = 0; i < rule.conditions.size(); i++) {
                if (cursor + 20 > y + hei - 20) {
                    break;
                }
                Condition condition = rule.conditions.get(i);
                boolean active = this.selCond == i && this.selAction < 0;
                rect(px + 6, cursor, pw - 12, 19, active ? 0xFF26200F : PANEL);
                frameRect(px + 6, cursor, pw - 12, 19, active ? AMBER : LINE);
                text(condition.left.label(), px + 12, cursor + 13, active ? AMBER : TXT,
                        ScreenFonts.cjk(7));
                text(condition.opText(), px + 12 + 62, cursor + 13, DIM, ScreenFonts.mono(7));
                text(condition.rightText(), px + 12 + 86, cursor + 13, MINT, ScreenFonts.mono(7));
                this.spots.add(new Hotspot("cond:" + i, px + 6, cursor, pw - 12, 19));
                cursor += 21;
            }
            if (rule.conditions.size() < Rule.MAX_CONDITIONS) {
                chip(px + 6, cursor, 78, 17, "+ \u6dfb\u52a0\u6761\u4ef6", "cond:add", false);
                cursor += 22;
            }
            cursor += 6;
        }

        // ---- 则 ----
        if (cursor + 40 < y + hei) {
            cursor = groupHeader(px, cursor, pw, "\u5219", "\u52a8\u4f5c", CRIT);
            for (int i = 0; i < rule.actions.size(); i++) {
                if (cursor + 20 > y + hei - 6) {
                    break;
                }
                Action action = rule.actions.get(i);
                boolean active = this.selAction == i;
                rect(px + 6, cursor, pw - 12, 19, active ? 0xFF2A1512 : PANEL);
                frameRect(px + 6, cursor, pw - 12, 19, active ? CRIT : LINE);
                text(action.type.label(), px + 12, cursor + 13, active ? CRIT : TXT,
                        ScreenFonts.cjk(7));
                text(clip(action.describe(), pw - 110, ScreenFonts.cjk(7)),
                        px + 12 + 66, cursor + 13, DIM, ScreenFonts.cjk(7));
                this.spots.add(new Hotspot("act:" + i, px + 6, cursor, pw - 12, 19));
                cursor += 21;
            }
            if (rule.actions.size() < Rule.MAX_ACTIONS) {
                chip(px + 6, cursor, 78, 17, "+ \u6dfb\u52a0\u52a8\u4f5c", "act:add", false);
            }
        }
    }

    /** "当 · 事件" ribbon; returns the y to continue drawing at. */
    private int groupHeader(int x, int y, int wid, String glyph, String label, int color) {
        rect(x, y, wid, 18, 0xFF141210);
        hline(x, y, wid, LINE);
        rect(x, y, 3, 18, color);
        text(glyph, x + 9, y + 13, color, ScreenFonts.cjkBold(9));
        text(label, x + 26, y + 13, FAINT, ScreenFonts.cjk(7));
        vline(x + 46, y + 4, 10, LINE);
        return y + 22;
    }

    private void inspectorLogic(int x, int y, int wid, int hei) {
        column(x, y, wid, hei, "\u5c5e\u6027\u4e0e\u8fd0\u884c");
        if (this.selRule < 0 || this.selRule >= this.program.rules.size()) {
            return;
        }
        Rule rule = this.program.rules.get(this.selRule);
        int rowY = y + 26;

        if (this.selAction >= 0 && this.selAction < rule.actions.size()) {
            Action action = rule.actions.get(this.selAction);
            text("\u52a8\u4f5c", x + 8, rowY + 11, DIM, ScreenFonts.cjk(7));
            chip(x + 46, rowY, wid - 54, 18, action.type.label(), "a:type", true);
            rowY += 24;
            if (action.type.usesAmount()) {
                stepperRow(x, rowY, wid, action.type.amountLabel(), String.valueOf((int) action.amount), "a:amt");
                rowY += 24;
            }
            if (action.type.usesSeconds()) {
                stepperRow(x, rowY, wid, "\u6301\u7eed\u79d2", String.valueOf((int) action.seconds), "a:sec");
                rowY += 24;
            }
            if (action.type.usesPage()) {
                text("\u9875\u9762", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
                chip(x + 56, rowY + 2, wid - 64, 17, pageLabel(action.page), "a:page", false);
                rowY += 24;
            }
            if (action.type.usesText()) {
                text("\u6587\u5b57", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
                fieldBox(x + 8, rowY + 18, wid - 16, FIELD_ACTION_TEXT);
                rowY += 40;
            }
            chip(x + 8, rowY, wid - 16, 19, "\u5220\u9664\u52a8\u4f5c", "a:del", false);
            rowY += 26;
        } else if (this.selCond >= 0 && this.selCond < rule.conditions.size()) {
            Condition condition = rule.conditions.get(this.selCond);
            text("\u6761\u4ef6", x + 8, rowY + 11, DIM, ScreenFonts.cjk(7));
            chip(x + 46, rowY, wid - 54, 18, condition.left.label(), "c:ch", true);
            rowY += 24;
            text("\u6bd4\u8f83", x + 8, rowY + 11, DIM, ScreenFonts.cjk(7));
            chip(x + 46, rowY, wid - 54, 18, condition.opLabel() + "  " + condition.opText(),
                    "c:op", true);
            rowY += 24;
            text("\u5bf9\u6bd4", x + 8, rowY + 11, DIM, ScreenFonts.cjk(7));
            chip(x + 46, rowY, wid - 54, 18,
                    condition.rightChannel == null ? "\u5e38\u6570" : "\u901a\u9053 "
                            + condition.rightChannel.label(), "c:right", condition.rightChannel != null);
            rowY += 24;
            if (condition.rightChannel == null) {
                stepperRow(x, rowY, wid, "\u6570\u503c", condition.left.formatNumber(condition.right), "c:val");
                rowY += 24;
            }
            chip(x + 8, rowY, wid - 16, 19, "\u5220\u9664\u6761\u4ef6", "c:del", false);
            rowY += 26;
        } else {
            text("\u89c4\u5219\u540d", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
            fieldBox(x + 8, rowY + 18, wid - 16, FIELD_RULE_NAME);
            rowY += 40;
            text("\u89e6\u53d1", x + 8, rowY + 11, DIM, ScreenFonts.cjk(7));
            chip(x + 46, rowY, wid - 54, 18, Rule.TRIGGER_NAMES[rule.trigger], "trig", true);
            rowY += 24;
            stepperRow(x, rowY, wid, "\u95f4\u9694\u523b", String.valueOf(rule.interval), "iv");
            rowY += 24;
            stepperRow(x, rowY, wid, "\u51b7\u5374\u79d2", String.valueOf(rule.cooldown), "cd");
            rowY += 24;
            chip(x + 8, rowY, wid - 16, 19, rule.enabled ? "\u5df2\u542f\u7528 \u00b7 \u70b9\u51fb\u505c\u7528"
                    : "\u5df2\u505c\u7528 \u00b7 \u70b9\u51fb\u542f\u7528", "rule:enabled", rule.enabled);
            rowY += 26;
            chip(x + 8, rowY, wid - 16, 19, "\u5220\u9664\u89c4\u5219", "rule:del", false);
            rowY += 26;
        }

        // Live readout: what the rule is looking at, right now.
        int footY = y + hei - 92;
        if (footY > rowY + 4) {
            hline(x + 8, footY, wid - 16, LINE);
            text("\u5b9e\u65f6\u6570\u636e", x + 8, footY + 14, FAINT, ScreenFonts.cjk(7));
            rowKV(x, footY + 24, wid, "\u6e29\u5ea6", channelText(Channel.TEMPERATURE), AMBER);
            rowKV(x, footY + 35, wid, "\u7a33\u5b9a\u5ea6", channelText(Channel.STABILITY), MINT);
            rowKV(x, footY + 46, wid, "\u62a5\u8b66", this.alarmLevel < 0 ? "\u65e0"
                    : (this.alarmLevel >= 2 ? "\u7ea7\u522b 2" : "\u7ea7\u522b 1"), CRIT);
            rowKV(x, footY + 57, wid, "\u89c4\u5219", this.program.enabledCount() + "/"
                    + this.program.rules.size(), DIM);
        }
    }

    private void rowKV(int x, int y, int wid, String key, String value, int color) {
        text(key, x + 8, y + 9, DIM, ScreenFonts.cjk(7));
        textRight(value, x + wid - 8, y + 9, color, ScreenFonts.mono(7));
    }

    private String channelText(Channel channel) {
        return channel.format(channel.read(this.live));
    }

    // =============================================================== preset tab

    private void presetList(int x, int y, int wid, int hei) {
        column(x, y, wid, hei, "\u9884\u8bbe\u5e93");
        int rowY = y + 24;
        for (int i = 0; i < this.presetNames.size(); i++) {
            if (rowY + 22 > y + hei - 28) {
                break;
            }
            boolean active = i == this.selPreset;
            boolean hover = in(x + 5, rowY, wid - 10, 20);
            rect(x + 5, rowY, wid - 10, 20, active ? 0xFF2A210F : (hover ? PANEL_HI : PANEL));
            frameRect(x + 5, rowY, wid - 10, 20, active ? AMBER : LINE);
            text(clip(this.presetNames.get(i), wid - 22, ScreenFonts.cjk(8)), x + 11, rowY + 14,
                    active ? AMBER : TXT, ScreenFonts.cjk(8));
            this.spots.add(new Hotspot("preset:" + i, x + 5, rowY, wid - 10, 20));
            rowY += 22;
        }
        if (this.presetNames.isEmpty()) {
            wrapText("\u8fd8\u6ca1\u6709\u9884\u8bbe\u3002\u5728\u53f3\u4fa7\u8f93\u5165\u540d\u5b57\u540e"
                    + "\u70b9\u300c\u4fdd\u5b58\u4e3a\u9884\u8bbe\u300d\u3002", x + 8, y + 40,
                    wid - 16, FAINT, ScreenFonts.cjk(7), 4);
        }
        chip(x + 5, y + hei - 26, wid - 10, 20, "+ \u4fdd\u5b58\u4e3a\u9884\u8bbe", "preset:save", false);
    }

    private void presetSummary(int x, int y, int wid, int hei) {
        rect(x, y, wid, hei, 0xFF080706);
        frameRect(x, y, wid, hei, LINE);
        rect(x + 1, y + 1, wid - 2, 15, PANEL);
        hline(x + 1, y + 16, wid - 2, LINE);
        text("\u9884\u89c8\u4e0e\u8bf4\u660e", x + 6, y + 12, FAINT, ScreenFonts.cjk(8));

        text("\u5f53\u524d\u5de5\u4f5c\u533a", x + 10, y + 34, DIM, ScreenFonts.cjk(7));
        text(this.layout.name, x + 10, y + 48, TXT, ScreenFonts.cjk(11));
        text(this.layout.size() + " \u4e2a\u63a7\u4ef6  \u00b7  "
                + this.program.rules.size() + " \u6761\u89c4\u5219", x + 10, y + 62, FAINT,
                ScreenFonts.cjk(8));

        // Miniature of the layout: enough to recognise a design, not to read it.
        int px = x + 10;
        int py = y + 74;
        int pw = wid - 20;
        int ph = Math.min(hei - 140, Math.round(pw * 0.75F));
        if (ph > 40) {
            float k = Math.min(pw / 256.0F, ph / 192.0F);
            int ww = Math.round(256 * k);
            int hh = Math.round(192 * k);
            java.awt.geom.AffineTransform old = this.g.getTransform();
            this.g.translate(px, py);
            this.g.scale(k, k);
            rect(0, 0, 256, 192, 0xFF0A0908);
            frameRect(0, 0, 256, 192, LINE);
            for (Widget widget : this.layout.widgets) {
                WidgetPainter.Ctx ctx = new WidgetPainter.Ctx();
                ctx.value = widget.value(this.live);
                ctx.banner = this.alarmText;
                ctx.level = Math.max(0, this.alarmLevel);
                ctx.active = this.alarmLevel >= 0 && !this.alarmText.isEmpty();
                WidgetPainter.paint(this.g, PanelLayout.ORIGIN_X + widget.gx * PanelLayout.GRID,
                        PanelLayout.ORIGIN_Y + widget.gy * PanelLayout.GRID,
                        widget.gw * PanelLayout.GRID, widget.gh * PanelLayout.GRID, widget, ctx);
            }
            this.g.setTransform(old);
            frameRect(px, py, ww, hh, 0xFF6B5A3E);
        }

        int textY = py + Math.max(ph, 44) + 16;
        wrapText("\u9884\u8bbe\u4fdd\u5b58\u5728 config/hbmzz-panels.nbt\uff0c"
                        + "\u4e0e\u5b58\u6863\u65e0\u5173\uff0c\u53ef\u968f\u610f\u62f7\u8d1d\u7ed9\u522b\u4eba\u3002"
                        + "\u8f7d\u5165\u540e\u8fd8\u9700\u70b9\u300c\u4fdd\u5b58\u5e76\u5e94\u7528\u300d\u624d\u4f1a\u5199\u5165\u5c4f\u5e55\u3002",
                x + 10, textY, wid - 20, FAINT, ScreenFonts.cjk(7), 5);
    }

    private void presetActions(int x, int y, int wid, int hei) {
        column(x, y, wid, hei, "\u64cd\u4f5c");
        int rowY = y + 26;
        text("\u9884\u8bbe\u540d", x + 8, rowY + 12, DIM, ScreenFonts.cjk(7));
        fieldBox(x + 8, rowY + 18, wid - 16, FIELD_PRESET_NAME);
        rowY += 42;
        chip(x + 8, rowY, wid - 16, 20, "\u52a0\u8f7d\u9009\u4e2d\u9884\u8bbe", "preset:load", false);
        rowY += 24;
        chip(x + 8, rowY, wid - 16, 20, "\u5220\u9664\u9009\u4e2d\u9884\u8bbe", "preset:del", false);
        rowY += 30;
        hline(x + 8, rowY, wid - 16, LINE);
        rowY += 8;
        text("\u5feb\u901f\u590d\u7528", x + 8, rowY + 10, FAINT, ScreenFonts.cjk(7));
        rowY += 16;
        chip(x + 8, rowY, wid - 16, 20, "\u590d\u5236\u5f53\u524d\u9762\u677f", "copy", false);
        rowY += 24;
        chip(x + 8, rowY, wid - 16, 20, "\u7c98\u8d34\u5230\u5f53\u524d\u9762\u677f", "paste", false);
        rowY += 30;
        wrapText("\u590d\u5236\u540e\u53ef\u4ee5\u76f4\u63a5\u6253\u5f00\u53e6\u4e00\u53f0\u76d1\u63a7\u5c4f"
                        + "\u7684\u5de5\u574a\u7c98\u8d34\uff0c\u4e0d\u9700\u8981\u5148\u5b58\u9884\u8bbe\u3002",
                x + 8, rowY, wid - 16, FAINT, ScreenFonts.cjk(7), 4);
    }

    // ================================================================= helpers

    /** Column frame with a caption strip. */
    private void column(int x, int y, int wid, int hei, String title) {
        rect(x, y, wid, hei, PANEL);
        frameRect(x, y, wid, hei, LINE);
        rect(x + 1, y + 1, wid - 2, 17, PANEL_HI);
        hline(x + 1, y + 18, wid - 2, LINE);
        text(title, x + 7, y + 13, DIM, ScreenFonts.cjk(8));
    }

    private void channelRow(int x, int y, int wid, String label, Channel channel, String id) {
        text(label, x + 8, y + 12, DIM, ScreenFonts.cjk(7));
        int bx = x + 46;
        int bw = wid - 54;
        boolean hoverL = in(bx, y + 2, 16, 17);
        boolean hoverR = in(bx + bw - 16, y + 2, 16, 17);
        rect(bx, y + 2, 16, 17, hoverL ? PANEL_HI : PANEL);
        frameRect(bx, y + 2, 16, 17, hoverL ? AMBER : LINE);
        textCenterIn("<", bx, 16, y + 14, hoverL ? AMBER : DIM, ScreenFonts.monoBold(9));
        this.spots.add(new Hotspot(id + ":-", bx, y + 2, 16, 17));

        rect(bx + 17, y + 2, bw - 34, 17, PANEL);
        frameRect(bx + 17, y + 2, bw - 34, 17, LINE);
        textCenterIn(channel == null ? "--" : channel.label(), bx + 17, bw - 34, y + 14,
                AMBER, ScreenFonts.cjk(8));

        rect(bx + bw - 16, y + 2, 16, 17, hoverR ? PANEL_HI : PANEL);
        frameRect(bx + bw - 16, y + 2, 16, 17, hoverR ? AMBER : LINE);
        textCenterIn(">", bx + bw - 16, 16, y + 14, hoverR ? AMBER : DIM, ScreenFonts.monoBold(9));
        this.spots.add(new Hotspot(id + ":+", bx + bw - 16, y + 2, 16, 17));
    }

    private void stepperRow(int x, int y, int wid, String label, String value, String id) {
        text(label, x + 8, y + 12, DIM, ScreenFonts.cjk(7));
        stepperInline(x + 46, y + 2, wid - 54, value, id);
    }

    private void stepperInline(int x, int y, int wid, String value, String id) {
        boolean hoverL = in(x, y, 16, 17);
        boolean hoverR = in(x + wid - 16, y, 16, 17);
        rect(x, y, 16, 17, hoverL ? PANEL_HI : PANEL);
        frameRect(x, y, 16, 17, hoverL ? AMBER : LINE);
        textCenterIn("-", x, 16, y + 12, hoverL ? AMBER : DIM, ScreenFonts.monoBold(10));
        this.spots.add(new Hotspot(id + ":-", x, y, 16, 17));

        rect(x + 17, y, wid - 34, 17, PANEL);
        frameRect(x + 17, y, wid - 34, 17, LINE);
        textCenterIn(clip(value, wid - 40, ScreenFonts.mono(7)), x + 17, wid - 34, y + 12,
                TXT, ScreenFonts.mono(7));

        rect(x + wid - 16, y, 16, 17, hoverR ? PANEL_HI : PANEL);
        frameRect(x + wid - 16, y, 16, 17, hoverR ? AMBER : LINE);
        textCenterIn("+", x + wid - 16, 16, y + 12, hoverR ? AMBER : DIM, ScreenFonts.monoBold(10));
        this.spots.add(new Hotspot(id + ":+", x + wid - 16, y, 16, 17));
    }

    /**
     * Box for a real text field: the GUI draws a {@code GuiTextField} on top.
     *
     * <p>Only one box exists per frame - the property sheet shows exactly one
     * editable text at a time - so this records the rectangle unconditionally
     * instead of guessing which box is focused.</p>
     */
    private void fieldBox(int x, int y, int wid, int kind) {
        rect(x, y, wid, 17, 0xFF0D0B09);
        frameRect(x, y, wid, 17, AMBER);
        this.fieldKind = kind;
        this.fieldRect = new int[]{x, y, wid, 17};
    }

    private void chip(int x, int y, int wid, int h, String label, String id, boolean active) {
        boolean hover = in(x, y, wid, h);
        rect(x, y, wid, h, active ? 0xFF2A210F : (hover ? PANEL_HI : PANEL));
        frameRect(x, y, wid, h, active ? AMBER : (hover ? 0xFF6B5A3E : LINE));
        textCenterIn(clip(label, wid - 6, ScreenFonts.cjk(8)), x, wid, y + h / 2 + 4,
                active ? AMBER : (hover ? TXT : DIM), ScreenFonts.cjk(8));
        if (id != null && !id.isEmpty()) {
            this.spots.add(new Hotspot(id, x, y, wid, h));
        }
    }

    private static String pageLabel(int page) {
        int p = Math.max(0, Math.min(Action.PAGE_NAMES.length - 1, page));
        return "0" + (p + 1) + " " + Action.PAGE_NAMES[p];
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001D) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private boolean in(int x, int y, int wid, int h) {
        return this.mouseX >= x && this.mouseX < x + wid && this.mouseY >= y && this.mouseY < y + h;
    }

    private void rect(int x, int y, int wid, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.fillRect(x, y, wid, h);
    }

    private void frameRect(int x, int y, int wid, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new BasicStroke(1.0F));
        this.g.drawRect(x, y, wid - 1, h - 1);
    }

    private void hline(int x, int y, int wid, int argb) {
        rect(x, y, wid, 1, argb);
    }

    private void vline(int x, int y, int h, int argb) {
        rect(x, y, 1, h, argb);
    }

    private void text(String value, int x, int y, int argb, Font font) {
        this.g.setFont(font);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(value, x, y);
    }

    private void textRight(String value, int rightX, int y, int argb, Font font) {
        text(value, rightX - width(value, font), y, argb, font);
    }

    private void textCenterIn(String value, int x, int wid, int baseline, int argb, Font font) {
        text(value, x + wid / 2 - width(value, font) / 2, baseline, argb, font);
    }

    private int width(String value, Font font) {
        FontMetrics metrics = this.g.getFontMetrics(font);
        return metrics.stringWidth(value);
    }

    private String clip(String value, int maxWidth, Font font) {
        FontMetrics metrics = this.g.getFontMetrics(font);
        if (metrics.stringWidth(value) <= maxWidth) {
            return value;
        }
        int end = value.length();
        while (end > 0 && metrics.stringWidth(value.substring(0, end) + "..") > maxWidth) {
            end--;
        }
        return value.substring(0, Math.max(0, end)) + "..";
    }

    /** Greedy character wrap; Han text has no spaces to break on. */
    private void wrapText(String value, int x, int y, int maxWidth, int argb, Font font, int maxLines) {
        FontMetrics metrics = this.g.getFontMetrics(font);
        StringBuilder line = new StringBuilder();
        int drawn = 0;
        int cursorY = y;
        for (int i = 0; i < value.length() && drawn < maxLines; i++) {
            char ch = value.charAt(i);
            if (metrics.stringWidth(line.toString() + ch) > maxWidth && line.length() > 0) {
                text(line.toString(), x, cursorY, argb, font);
                line.setLength(0);
                cursorY += 11;
                drawn++;
            }
            line.append(ch);
        }
        if (drawn < maxLines && line.length() > 0) {
            text(line.toString(), x, cursorY, argb, font);
        }
    }

    // ===================================================================== gl

    public void upload() {
        if (!this.dirty || this.image == null) {
            return;
        }
        this.dirty = false;
        int texW = this.w * SS;
        int texH = this.h * SS;
        if (this.texId == 0) {
            this.texId = org.lwjgl.opengl.GL11.glGenTextures();
        }
        org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, this.texId);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);

        this.image.getRGB(0, 0, texW, texH, this.pixels, 0, texW);
        int needed = texW * texH * 4;
        if (this.upload == null || this.upload.capacity() < needed) {
            this.upload = ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder());
        }
        this.upload.clear();
        for (int argb : this.pixels) {
            this.upload.put((byte) (argb >> 16));
            this.upload.put((byte) (argb >> 8));
            this.upload.put((byte) argb);
            this.upload.put((byte) (argb >>> 24));
        }
        this.upload.flip();
        org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                org.lwjgl.opengl.GL11.GL_RGBA, texW, texH, 0, org.lwjgl.opengl.GL11.GL_RGBA,
                org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, this.upload);
    }

    public void dispose() {
        if (this.texId != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(this.texId);
            this.texId = 0;
        }
    }

    private static int WARN() {
        return 0xFFFFC24A;
    }
}

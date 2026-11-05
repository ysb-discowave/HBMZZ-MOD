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
 * Portions of the render structure and the offscreen-canvas pipeline are derived
 * from JiezhiMod by Shushang de Jiezhi, used under written permission to license
 * them under GPL-3.0. See AUTHORIZATION.md.
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client.screen;

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

import com.hbmzz.dfc.ConsoleRig;

/**
 * The terminal window: an AWT-drawn control surface with a live preview of the
 * panel on the right.
 *
 * <p>Same reason as {@link DfcScreen} - one texture, real font weights, real
 * hairlines - plus one more: the window and the in-world panel then share a
 * palette and a grid, so the terminal reads as the <em>remote control for that
 * screen</em> rather than a separate mod GUI bolted onto it.</p>
 *
 * <p><b>Layout contract:</b> sizes here are real GUI pixels, not a virtual
 * grid - the window is resizable, so the layout reflows instead of scaling.
 * The two view controls live in the left column; everything about the wall
 * (columns, rows, reset) lives in a bar pinned to the bottom edge, because the
 * previous version flowed that block downward and at 214px tall - the size
 * players actually drag to - it simply fell outside the window and the wall
 * size became unreachable.</p>
 */
public final class TerminalCanvas {

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
    private static final int OK = 0xFF8CD96A;
    private static final int CRIT = 0xFFFF5C3C;

    private static final int SS = 2;

    // ---------------------------------------------------------------- hit ids

    public static final int HIT_NONE = 0;
    public static final int HIT_CLOSE = 1;
    public static final int HIT_POWER = 2;
    public static final int HIT_PAGE_CORE = 3;
    public static final int HIT_PAGE_PARTS = 4;
    public static final int HIT_PAGE_GRAPH = 5;
    public static final int HIT_COLS_MINUS = 6;
    public static final int HIT_COLS_PLUS = 7;
    public static final int HIT_ROWS_MINUS = 8;
    public static final int HIT_ROWS_PLUS = 9;
    public static final int HIT_GRIP = 10;
    public static final int HIT_RESET = 11;
    public static final int HIT_TILT = 12;
    /** Fourth page: the operator's custom panel. */
    public static final int HIT_PAGE_CUSTOM = 13;
    /** Opens the panel studio window. */
    public static final int HIT_STUDIO = 14;

    /** One clickable rectangle. Rebuilt every frame, so it can never go stale. */
    public static final class Hotspot {
        public final int id;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        Hotspot(int id, int x, int y, int w, int h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean hit(int mx, int my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    // ------------------------------------------------------------------- state

    private int w = 480;
    private int h = 300;
    private BufferedImage image;
    private int[] pixels;
    private Graphics2D g;
    private int texId;
    private ByteBuffer upload;
    private boolean dirty = true;

    private final List<Hotspot> hotspots = new ArrayList<Hotspot>();

    /** Preview rectangle in GUI pixels, filled in by {@link #render} for the GL pass. */
    private int previewX;
    private int previewY;
    private int previewW;
    private int previewH;

    /** Tilt notch currently shown in the wall bar. */
    private int tilt = ConsoleRig.TILT_CEILING;

    /** Last drawn state, so an idle window costs no repaint at all. */
    private int lastMouseX = Integer.MIN_VALUE;
    private int lastMouseY = Integer.MIN_VALUE;
    private int lastPage = -1;
    private int lastCols = -1;
    private int lastRows = -1;
    private boolean lastPower;
    private boolean lastLinked;
    private boolean lastResizing;
    private boolean drawnOnce;

    // =================================================================== surface

    /**
     * Sets the tilt notch the wall bar reports. Marked dirty on change so a
     * window nobody is hovering still repaints when the posture moves.
     */
    public void setTilt(int tilt) {
        int clamped = ConsoleRig.clampTilt(tilt);
        if (clamped == this.tilt) {
            return;
        }
        this.tilt = clamped;
        this.dirty = true;
    }

    public int tilt() {
        return this.tilt;
    }

    public void resize(int width, int height) {
        int ww = Math.max(240, width);
        int hh = Math.max(180, height);
        if (ww == this.w && hh == this.h && this.image != null) {
            return;
        }
        this.w = ww;
        this.h = hh;
        this.image = new BufferedImage(ww * SS, hh * SS, BufferedImage.TYPE_INT_ARGB);
        this.pixels = new int[ww * SS * hh * SS];
        this.g = this.image.createGraphics();
        this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // Everything is authored in GUI pixels; one scale call does the
        // supersampling, so no drawing code has to know about SS.
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

    public boolean hasTexture() {
        return this.texId != 0;
    }

    /**
     * Where the GL pass may paint the live panel: inside the bezel and below the
     * caption strip, so the caption is never overwritten.
     */
    public int[] previewRect() {
        return new int[]{this.previewX + 1, this.previewY + 17,
                Math.max(0, this.previewW - 2), Math.max(0, this.previewH - 18)};
    }

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

    public void markDirty() {
        this.dirty = true;
    }

    /** Backing image, for the offline preview tool. */
    public BufferedImage image() {
        return this.image;
    }

    // ==================================================================== layout

    /**
     * Redraws the whole window and rebuilds the hit map.
     *
     * @param page      active page index, highlighted in the page column
     * @param powerOn   power switch state
     * @param cols      wall panel width in blocks
     * @param rows      wall panel height in blocks
     * @param coreLabel one-line description of the bound core ("-12, 64, 3")
     * @param mouseX    cursor position, in the same GUI pixels as the layout
     */
    public void render(int page, boolean powerOn, int cols, int rows, boolean linked,
                       String coreLabel, int mouseX, int mouseY, boolean resizing) {
        boolean idle = this.drawnOnce
                && !this.dirty
                && mouseX == this.lastMouseX && mouseY == this.lastMouseY
                && page == this.lastPage && cols == this.lastCols && rows == this.lastRows
                && powerOn == this.lastPower && linked == this.lastLinked
                && resizing == this.lastResizing;
        if (idle) {
            return;
        }
        this.drawnOnce = true;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.lastPage = page;
        this.lastCols = cols;
        this.lastRows = rows;
        this.lastPower = powerOn;
        this.lastLinked = linked;
        this.lastResizing = resizing;

        this.hotspots.clear();
        this.g.setColor(new Color(BG, true));
        this.g.fillRect(0, 0, this.w, this.h);

        int pad = 10;
        int headerH = 30;
        int barH = 26;
        int barY = this.h - pad - barH;
        drawHeader(headerH, linked, coreLabel, mouseX, mouseY);

        int columnW = Math.max(112, Math.min(140, Math.round(this.w * 0.30F)));
        int columnX = pad;
        int contentY = headerH + pad;
        int contentH = barY - 8 - contentY;
        drawControls(columnX, contentY, columnW, contentH, page, powerOn, mouseX, mouseY);

        this.previewX = columnX + columnW + pad;
        this.previewY = contentY;
        this.previewW = this.w - this.previewX - pad;
        this.previewH = contentH;
        drawPreviewFrame(this.previewX, this.previewY, this.previewW, this.previewH, cols, rows);

        drawWallBar(pad, barY, this.w - pad * 2, barH, cols, rows, mouseX, mouseY);

        if (resizing) {
            // Live readout while dragging, so the size is never a mystery.
            String size = this.w + " x " + this.h;
            int tw = textWidth(size, ScreenFonts.mono(8)) + 10;
            rect(this.w - tw - 4, barY - 18, tw, 13, 0xEE1E1A14);
            text(size, this.w - tw + 1, barY - 8, AMBER, ScreenFonts.mono(8));
        }
        this.dirty = true;
    }

    private void drawHeader(int headerH, boolean linked, String coreLabel,
                            int mouseX, int mouseY) {
        rect(0, 0, this.w, headerH, PANEL);
        hline(0, headerH - 1, this.w, LINE);
        // A four pixel accent bar does the job of a title icon.
        rect(10, 8, 3, 14, AMBER);
        String mark = DfcScreen.OS_NAME;
        text(mark, 19, 20, TXT, ScreenFonts.monoBold(12));
        int markW = textWidth(mark, ScreenFonts.monoBold(12));
        text("\u663e\u793a\u7ec8\u7aef", 19 + markW + 6, 20, FAINT, ScreenFonts.cjk(8));

        int closeSize = 16;
        int cx = this.w - 10 - closeSize;
        int cy = (headerH - closeSize) / 2;
        boolean hover = mouseX >= cx && mouseX < cx + closeSize && mouseY >= cy && mouseY < cy + closeSize;
        rect(cx, cy, closeSize, closeSize, hover ? 0xFF4A1A12 : PANEL_HI);
        frameRect(cx, cy, closeSize, closeSize, hover ? CRIT : LINE);
        textCenterIn("x", cx, closeSize, cy + 11, hover ? 0xFFFFC8BC : DIM, ScreenFonts.mono(10));
        this.hotspots.add(new Hotspot(HIT_CLOSE, cx, cy, closeSize, closeSize));

        // Status and binding share one line on the right, right to left.
        String status = linked ? "\u5df2\u8fde\u63a5" : "\u672a\u8fde\u63a5";
        int statusColor = linked ? OK : CRIT;
        Font statusFont = ScreenFonts.cjk(9);
        int statusW = textWidth(status, statusFont);
        int sx = cx - 12 - statusW;
        text(status, sx, 20, statusColor, statusFont);
        rect(sx - 9, 10, 5, 10, statusColor);

        if (coreLabel != null) {
            // The core label is a link tag, so it can be Chinese; asking the
            // monospaced face for Han gives a row of empty boxes.
            String bind = "\u00b7 " + coreLabel;
            Font bindFont = ScreenFonts.text(bind, 8);
            int bw = textWidth(bind, bindFont);
            if (sx - 14 - bw > 120) {
                text(bind, sx - 14 - bw, 19, FAINT, bindFont);
            }
        }
    }

    private void drawControls(int x, int y0, int w, int h, int page, boolean powerOn,
                              int mouseX, int mouseY) {
        int y = y0;
        button(x, y, w, 24, powerOn ? "\u5df2\u5f00\u673a" : "\u5df2\u5173\u673a", HIT_POWER,
                powerOn, mouseX, mouseY);
        y += 28;

        text("\u89c6\u56fe", x, y + 8, DIM, ScreenFonts.cjk(8));
        y += 12;

        // Four pages in a 2x2 grid. As a single column they used to eat 100 of
        // the 130 pixels a minimum-height window gives the control column, and
        // the fourth page would simply have fallen off the bottom.
        String[] pages = {"\u6838\u5fc3", "\u90e8\u4ef6", "\u66f2\u7ebf", "\u81ea\u5b9a\u4e49"};
        int[] ids = {HIT_PAGE_CORE, HIT_PAGE_PARTS, HIT_PAGE_GRAPH, HIT_PAGE_CUSTOM};
        int cellW = Math.max(38, (w - 4) / 2);
        for (int i = 0; i < ids.length; i++) {
            int cx = x + (i % 2) * (cellW + 4);
            int cy = y + (i / 2) * 22;
            chip(cx, cy, cellW, 19, (i + 1) + " " + pages[i], ids[i], page == i, mouseX, mouseY);
        }
        y += 48;

        // The studio is a separate window, so it gets a full-width entry of its
        // own rather than a fourth page slot - it edits the panel, it does not
        // switch to it.
        if (y + 22 <= y0 + h) {
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 22;
            rect(x, y, w, 22, hover ? 0xFF2A210F : PANEL);
            frameRect(x, y, w, 22, hover ? AMBER : LINE);
            rect(x, y, 2, 22, AMBER);
            text("\u9762\u677f\u5de5\u574a", x + 8, y + 15, hover ? TXT : DIM, ScreenFonts.cjk(9));
            this.hotspots.add(new Hotspot(HIT_STUDIO, x, y, w, 22));
            y += 26;
        }

        // Whatever room is left becomes the keyboard hint, bottom-up, so a tall
        // window does not look half empty and a short one just drops the hint.
        int hintY = y0 + h - 46;
        if (hintY > y + 6) {
            text("\u6eda\u8f6e \u7f29\u653e", x, hintY + 8, FAINT, ScreenFonts.cjk(7));
            text("\u62d6\u53f3\u4e0b\u89d2 \u62c9\u4f38", x, hintY + 19, FAINT, ScreenFonts.cjk(7));
            text("\u53cc\u51fb\u6807\u9898 \u6700\u5927\u5316", x, hintY + 30, FAINT, ScreenFonts.cjk(7));
        }
    }

    /** Compact page button: same affordances, one size down. */
    private void chip(int x, int y, int w, int h, String label, int id, boolean active,
                      int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        rect(x, y, w, h, active ? 0xFF2A210F : (hover ? PANEL_HI : PANEL));
        frameRect(x, y, w, h, active ? AMBER : (hover ? 0xFF6B5A3E : LINE));
        if (active) {
            rect(x, y, 2, h, AMBER);
        }
        textCenterIn(label, x + 2, w - 2, y + h / 2 + 4,
                active ? AMBER : (hover ? TXT : DIM), ScreenFonts.cjk(8));
        this.hotspots.add(new Hotspot(id, x, y, w, h));
    }

    /**
     * The wall bar: columns, rows and reset, pinned to the bottom edge.
     *
     * <p>These three controls used to flow after the page buttons, and the
     * "does it still fit" test silently skipped them on any window shorter than
     * about 300px - which is most of them. Pinned to the floor of the window
     * they are always there and always in the same place.</p>
     */
    private void drawWallBar(int x, int y, int w, int h, int cols, int rows,
                             int mouseX, int mouseY) {
        rect(x, y, w, h, PANEL);
        frameRect(x, y, w, h, LINE);

        text("\u5899\u5c4f", x + 8, y + 16, DIM, ScreenFonts.cjk(8));
        miniStepper(x + 36, y + 5, "\u5bbd", cols, HIT_COLS_MINUS, HIT_COLS_PLUS, mouseX, mouseY);
        miniStepper(x + 36 + 62, y + 5, "\u9ad8", rows, HIT_ROWS_MINUS, HIT_ROWS_PLUS,
                mouseX, mouseY);

        // Tilt control. A single cycling button rather than a third stepper:
        // the bar has to survive a 300px window, and three -/+ pairs plus the
        // reset button do not fit there. Skipped only if the window is so
        // narrow that it would collide with reset.
        int bw = 44;
        int bx = x + w - bw - 6;

        int tx = x + 36 + 62 + 62;
        int tw = 52;
        if (tx + tw + 6 <= bx) {
            boolean tHover = mouseX >= tx && mouseX < tx + tw && mouseY >= y + 4 && mouseY < y + 21;
            rect(tx, y + 4, tw, 17, tHover ? PANEL_HI : PANEL);
            frameRect(tx, y + 4, tw, 17, tHover ? AMBER : LINE);
            String label = "\u503e " + Math.round(ConsoleRig.tiltDegrees(this.tilt, rows)) + "\u00b0";
            textCenterIn(label, tx, tw, y + 16, tHover ? AMBER : DIM, ScreenFonts.cjk(8));
            this.hotspots.add(new Hotspot(HIT_TILT, tx, y + 4, tw, 17));
        }
        boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= y + 4 && mouseY < y + 21;
        rect(bx, y + 4, bw, 17, hover ? PANEL_HI : PANEL);
        frameRect(bx, y + 4, bw, 17, hover ? AMBER : LINE);
        textCenterIn("\u590d\u4f4d", bx, bw, y + 16, hover ? AMBER : DIM, ScreenFonts.cjk(8));
        this.hotspots.add(new Hotspot(HIT_RESET, bx, y + 4, bw, 17));
    }

    /** A labelled value with a minus on the left and a plus on the right. */
    private void miniStepper(int x, int y, String label, int value,
                             int idMinus, int idPlus, int mouseX, int mouseY) {
        text(label, x, y + 11, FAINT, ScreenFonts.cjk(7));
        int bx = x + 12;
        iconButton(bx, y, 16, 16, "-", idMinus, mouseX, mouseY);
        rect(bx + 17, y, 16, 16, PANEL_HI);
        frameRect(bx + 17, y, 16, 16, LINE);
        textCenterIn(String.valueOf(value), bx + 17, 16, y + 12, AMBER, ScreenFonts.monoBold(8));
        iconButton(bx + 34, y, 16, 16, "+", idPlus, mouseX, mouseY);
    }

    /**
     * Frame for the preview. The actual screen texture is drawn in the GL pass
     * (see {@code GuiDFCMonitor}) because it is a texture, not AWT pixels; this
     * only reserves the space and draws the bezel around it.
     */
    private void drawPreviewFrame(int x, int y, int w, int h, int cols, int rows) {
        rect(x, y, w, h, 0xFF080706);
        frameRect(x, y, w, h, LINE);

        // The caption needs its own strip: the GL pass paints the panel over the
        // whole rectangle, and a label inside that rectangle simply disappears.
        rect(x + 1, y + 1, w - 2, 15, PANEL);
        hline(x + 1, y + 16, w - 2, LINE);
        String label = "\u5b9e\u65f6\u9884\u89c8  " + cols + " x " + rows;
        text(label, x + 6, y + 12, FAINT, ScreenFonts.cjk(8));
        textRight("LIVE", x + w - 6, y + 12, 0xFF6B5A3E, ScreenFonts.mono(7));

        this.hotspots.add(new Hotspot(HIT_GRIP, x + w - 16, y + h - 16, 16, 16));
        // Grip dots: the only affordance that says "this window resizes".
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j <= i; j++) {
                rect(x + w - 12 + j * 4, y + h - 12 + (2 - i) * 4, 2, 2, 0xFF6B5A3E);
            }
        }
    }

    // ================================================================= primitives

    private void button(int x, int y, int w, int h, String label, int id, boolean active,
                        int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        int fill = active ? 0xFF2A210F : (hover ? PANEL_HI : PANEL);
        int border = active ? AMBER : (hover ? 0xFF6B5A3E : LINE);
        int color = active ? AMBER : (hover ? TXT : DIM);
        rect(x, y, w, h, fill);
        frameRect(x, y, w, h, border);
        if (active) {
            rect(x, y, 2, h, AMBER);
        }
        textCenterIn(label, x + 2, w - 2, y + h / 2 + 4, color, ScreenFonts.cjk(9));
        this.hotspots.add(new Hotspot(id, x, y, w, h));
    }

    private void iconButton(int x, int y, int w, int h, String glyph, int id, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        rect(x, y, w, h, hover ? 0xFF2A210F : PANEL_HI);
        frameRect(x, y, w, h, hover ? AMBER : LINE);
        textCenterIn(glyph, x, w, y + h / 2 + 4, hover ? AMBER : DIM, ScreenFonts.monoBold(11));
        this.hotspots.add(new Hotspot(id, x, y, w, h));
    }

    private void rect(int x, int y, int w, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.fillRect(x, y, w, h);
    }

    private void frameRect(int x, int y, int w, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new BasicStroke(1.0F));
        this.g.drawRect(x, y, w - 1, h - 1);
    }

    private void hline(int x, int y, int w, int argb) {
        rect(x, y, w, 1, argb);
    }

    private void text(String value, int x, int y, int argb, Font font) {
        this.g.setFont(font);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(value, x, y);
    }

    private void textRight(String value, int rightX, int y, int argb, Font font) {
        text(value, rightX - textWidth(value, font), y, argb, font);
    }

    private void textCenterIn(String value, int x, int w, int baseline, int argb, Font font) {
        text(value, x + w / 2 - textWidth(value, font) / 2, baseline, argb, font);
    }

    private int textWidth(String value, Font font) {
        FontMetrics metrics = this.g.getFontMetrics(font);
        return metrics.stringWidth(value);
    }

    public int hitTest(int x, int y) {
        for (int i = this.hotspots.size() - 1; i >= 0; i--) {
            Hotspot spot = this.hotspots.get(i);
            if (spot.hit(x, y)) {
                return spot.id;
            }
        }
        return HIT_NONE;
    }

    /** Exposed for the debug log line that reports which fonts actually loaded. */
    public static String fontReport() {
        return String.format(Locale.ROOT, "%s", ScreenFonts.describe());
    }
}

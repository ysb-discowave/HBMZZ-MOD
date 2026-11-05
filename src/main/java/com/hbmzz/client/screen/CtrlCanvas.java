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

import com.hbmzz.ctrl.CtrlLink;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlRule;
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The control panel window, drawn as one image.
 *
 * <p>Same trick as the monitor terminal: build the whole window with
 * {@code Graphics2D}, upload it once and blit it as a single texture. It buys
 * real typography, hairlines and anti-aliased circles - and, more importantly
 * here, it means the panel on the left is painted by the exact same
 * {@link CtrlPainter} a wall-mounted copy would use, so the editor cannot drift
 * away from the thing it is editing.</p>
 *
 * <p>Fixed logical size, scaled to whatever the screen allows. A layout that
 * reflows with the window would have to re-derive every hit rectangle twice a
 * frame; one fixed 640x400 sheet scaled by a single factor keeps the geometry
 * identical to the pixels, and keeps the clickable regions in one list that the
 * GUI and the painter both read.</p>
 */
public class CtrlCanvas {

    public static final int W = 640;
    public static final int H = 400;
    /** Supersampling factor: the sheet is built 3x and shown at 1x. */
    private static final int SS = 3;

    // ---- layout, in logical pixels ----------------------------------------
    public static final int PANEL_X = 14;
    public static final int PANEL_Y = 46;
    public static final int PANEL_W = 384;
    public static final int PANEL_H = 216;
    public static final int SIDE_X = 410;
    public static final int SIDE_W = 216;
    public static final int SIDE_Y = 46;
    public static final int SIDE_H = 338;
    public static final int TAB_Y = 12;
    public static final int TAB_W = 62;
    public static final int TAB_H = 20;
    public static final int[] TAB_X = {296, 296 + TAB_W + 4, 296 + (TAB_W + 4) * 2};

    public static final String[] TAB_NAMES = {"运行", "控件", "联动"};

    /** One clickable region. {@code id} is parsed by the GUI. */
    public static final class Hit {

        public final String id;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        Hit(String id, int x, int y, int w, int h) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(int px, int py) {
            return px >= this.x && px < this.x + this.w && py >= this.y && py < this.y + this.h;
        }
    }

    private final List<Hit> hits = new ArrayList<Hit>();
    /** Field currently being typed into, and its buffer. */
    private String editId = "";
    private String editText = "";
    private BufferedImage image;
    private int[] pixels;
    /** Previous frame, kept so an unchanged window costs no upload at all. */
    private int[] lastPixels;
    private int texId;
    private int[] uploadBufferCache;
    private ByteBuffer upload;
    private boolean dirty = true;
    private long lastUpload;

    public List<Hit> hits() {
        return this.hits;
    }

    /** Hit id under a logical point, or null. */
    public String hitAt(int px, int py) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (hit.contains(px, py)) {
                return hit.id;
            }
        }
        return null;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /**
     * Grid cell under a logical point, or null when the point is off the face.
     *
     * <p>The GUI needs the same geometry the painter used; deriving it in two
     * places is how a drag ends up one cell off from where the control was
     * drawn.</p>
     */
    public int[] cellAt(int px, int py) {
        if (px < PANEL_X || py < PANEL_Y || px >= PANEL_X + PANEL_W || py >= PANEL_Y + PANEL_H) {
            return null;
        }
        int cx = (px - PANEL_X) * CtrlPanel.COLS / PANEL_W;
        int cy = (py - PANEL_Y) * CtrlPanel.ROWS / PANEL_H;
        return new int[]{Math.min(CtrlPanel.COLS - 1, cx), Math.min(CtrlPanel.ROWS - 1, cy)};
    }

    /** Pixel rectangle of a control on the sheet: x, y, w, h. */
    public static int[] rectOf(CtrlWidget widget) {
        float sx = PANEL_W / (float) CtrlPanel.CANVAS_W;
        float sy = PANEL_H / (float) CtrlPanel.CANVAS_H;
        return new int[]{
                PANEL_X + Math.round(widget.gx * CtrlPanel.GRID * sx),
                PANEL_Y + Math.round(widget.gy * CtrlPanel.GRID * sy),
                Math.round(widget.gw * CtrlPanel.GRID * sx),
                Math.round(widget.gh * CtrlPanel.GRID * sy)};
    }

    public int textureId() {
        return this.texId;
    }

    /**
     * The painted sheet, for the offscreen preview tool. In game the pixels only
     * ever reach the screen through {@link #upload()}; this exists so the layout
     * can be checked without launching Minecraft.
     */
    public BufferedImage image() {
        return this.image;
    }

    /**
     * Paints the whole window.
     *
     * @param mode    0 运行, 1 控件, 2 联动
     * @param selWidget index of the selected control, -1 for none
     * @param selRule   index of the selected rule, -1 for none
     * @param dragIndex index of the control being dragged, -1 for none
     * @param mouseX   cursor in logical coordinates
     * @param mouseY   cursor in logical coordinates
     * @param status   one line shown at the bottom of the side bar
     */
    public void render(CtrlPanel panel, int mode, int selWidget, int selRule, int dragIndex,
                       int mouseX, int mouseY, String status, boolean machineApiReady,
                       String editId, String editText) {
        this.editId = editId == null ? "" : editId;
        this.editText = editText == null ? "" : editText;
        Graphics2D g = begin();
        try {
            this.hits.clear();
            paintBackground(g);
            paintHeader(g, panel, mode, mouseX, mouseY);
            if (panel == null) {
                CtrlPainter.text(g, "面板数据缺失", 20, 60, ScreenFonts.cjk(12), CtrlPainter.TEXT);
                return;
            }
            paintPanel(g, panel, mode, selWidget, dragIndex, mouseX, mouseY);
            paintSlots(g, panel, mode, mouseX, mouseY, machineApiReady);
            if (mode == 0) {
                paintRunSide(g, panel, status, mouseX, mouseY);
            } else if (mode == 1) {
                paintWidgetSide(g, panel, selWidget, mouseX, mouseY, status);
            } else {
                paintRuleSide(g, panel, selRule, mouseX, mouseY, status);
            }
        } finally {
            g.dispose();
        }
    }

    // ------------------------------------------------------------- scaffolding

    private Graphics2D begin() {
        int w = W * SS;
        int h = H * SS;
        if (this.image == null) {
            this.image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            this.pixels = new int[w * h];
        }
        Graphics2D g = this.image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(SS, SS);
        g.setColor(new Color(0x0B0F13));
        g.fillRect(0, 0, W, H);
        this.dirty = true;
        return g;
    }

    private void paintBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, new Color(0x151A20), 0, H, new Color(0x0B0E12)));
        g.fillRect(0, 0, W, H);
        g.setColor(new Color(0x2A323B));
        g.drawLine(0, 34, W, 34);
        // Corner marks: the window should read as a machine face, not a webpage.
        g.setColor(new Color(0x39424C));
        g.drawRect(4, 4, W - 9, H - 9);
    }

    private void paintHeader(Graphics2D g, CtrlPanel panel, int mode, int mouseX, int mouseY) {
        CtrlPainter.text(g, "机器控制面板", 14, 24, ScreenFonts.cjk(13), CtrlPainter.TEXT);
        if (panel != null) {
            CtrlPainter.text(g, "· " + panel.name, 104, 24, ScreenFonts.cjk(10), CtrlPainter.TEXT_DIM);
        }
        for (int i = 0; i < TAB_NAMES.length; i++) {
            int x = TAB_X[i];
            boolean active = mode == i;
            boolean hover = inside(mouseX, mouseY, x, TAB_Y, TAB_W, TAB_H);
            g.setColor(active ? new Color(0x2C3A46) : (hover ? new Color(0x1F272F) : new Color(0x171D23)));
            g.fill(new RoundRectangle2D.Float(x, TAB_Y, TAB_W, TAB_H, 4, 4));
            g.setColor(active ? new Color(0x6FD3FF) : new Color(0x333B44));
            g.setStroke(new BasicStroke(1F));
            g.draw(new RoundRectangle2D.Float(x + 0.5F, TAB_Y + 0.5F, TAB_W - 1, TAB_H - 1, 4, 4));
            CtrlPainter.centered(g, TAB_NAMES[i], x + TAB_W / 2, TAB_Y + 14,
                    ScreenFonts.cjk(10), active ? new Color(0xCDEFFF) : CtrlPainter.TEXT_DIM);
            add("tab:" + i, x, TAB_Y, TAB_W, TAB_H);
        }
        // Esc closes; say so, because a full-screen custom window has no X.
        CtrlPainter.text(g, "Esc 关闭", W - 58, 24, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
    }

    private void paintPanel(Graphics2D g, CtrlPanel panel, int mode, int selWidget, int dragIndex,
                            int mouseX, int mouseY) {
        // Housing: a brushed steel frame with four screws, then the recessed
        // face the controls actually sit on.
        g.setPaint(new GradientPaint(PANEL_X, PANEL_Y, new Color(0x2A323B),
                PANEL_X, PANEL_Y + PANEL_H, new Color(0x151A1F)));
        g.fill(new RoundRectangle2D.Float(PANEL_X - 6, PANEL_Y - 6, PANEL_W + 12, PANEL_H + 12, 6, 6));
        g.setColor(new Color(0x3C454F));
        g.draw(new RoundRectangle2D.Float(PANEL_X - 5.5F, PANEL_Y - 5.5F, PANEL_W + 11, PANEL_H + 11, 6, 6));
        for (int[] screw : new int[][]{{PANEL_X - 2, PANEL_Y - 2}, {PANEL_X + PANEL_W + 2, PANEL_Y - 2},
                {PANEL_X - 2, PANEL_Y + PANEL_H + 2}, {PANEL_X + PANEL_W + 2, PANEL_Y + PANEL_H + 2}}) {
            g.setColor(new Color(0x4A5560));
            g.fill(new Ellipse2D.Float(screw[0] - 2.5F, screw[1] - 2.5F, 5, 5));
            g.setColor(new Color(0x11151A));
            g.drawLine(screw[0] - 1, screw[1], screw[0] + 1, screw[1]);
        }
        g.setPaint(new GradientPaint(PANEL_X, PANEL_Y, new Color(0x1D242B),
                PANEL_X, PANEL_Y + PANEL_H, new Color(0x12171C)));
        g.fillRect(PANEL_X, PANEL_Y, PANEL_W, PANEL_H);

        float sx = PANEL_W / (float) CtrlPanel.CANVAS_W;
        float sy = PANEL_H / (float) CtrlPanel.CANVAS_H;

        if (mode != 0) {
            // Grid only in the editor: on a working panel it would read as
            // texture noise behind the controls.
            g.setColor(new Color(0xFFFFFF08, true));
            for (int x = 0; x <= CtrlPanel.COLS; x++) {
                int px = PANEL_X + Math.round(x * CtrlPanel.GRID * sx);
                g.drawLine(px, PANEL_Y, px, PANEL_Y + PANEL_H);
            }
            for (int y = 0; y <= CtrlPanel.ROWS; y++) {
                int py = PANEL_Y + Math.round(y * CtrlPanel.GRID * sy);
                g.drawLine(PANEL_X, py, PANEL_X + PANEL_W, py);
            }
        }

        for (int i = 0; i < panel.widgets.size(); i++) {
            CtrlWidget widget = panel.widgets.get(i);
            int[] rect = rectOf(widget);
            int x = rect[0];
            int y = rect[1];
            int w = rect[2];
            int h = rect[3];
            boolean hovered = inside(mouseX, mouseY, x, y, w, h);
            CtrlPainter.paint(g, widget, x, y, w, h, mode != 0 && i == selWidget, hovered);
            if (mode != 0) {
                add("widget:" + i, x, y, w, h);
                if (i == dragIndex) {
                    g.setColor(new Color(0x6FD3FFAA, true));
                    g.setStroke(new BasicStroke(1.5F));
                    g.draw(new RoundRectangle2D.Float(x - 1, y - 1, w + 2, h + 2, 5, 5));
                }
            } else if (widget.isOperator()) {
                // A working panel has to be clickable too. Readouts are left
                // out on purpose: clicking a gauge should do nothing rather
                // than swallow the click.
                add("run:" + i, x, y, w, h);
            } else if (widget.type.isTerminal()) {
                // A terminal is the other exception: it is the one control on
                // a working panel that opens something instead of moving
                // something.
                add("term:" + i, x, y, w, h);
            }
        }
        if (panel.widgets.isEmpty()) {
            CtrlPainter.centered(g, "面板是空的 — 到“控件”页添加一个", PANEL_X + PANEL_W / 2,
                    PANEL_Y + PANEL_H / 2, ScreenFonts.cjk(11), CtrlPainter.TEXT_DIM);
        }
    }

    // ------------------------------------------------------------------ slots

    private void paintSlots(Graphics2D g, CtrlPanel panel, int mode, int mouseX, int mouseY,
                            boolean machineApiReady) {
        int x = PANEL_X;
        int y = PANEL_Y + PANEL_H + 12;
        CtrlPainter.text(g, machineApiReady
                        ? (mode == 0 ? "机器链接 A–F · 24 格内" : "机器链接 A–F · 手持数据线右键机器记录坐标")
                        : "未检测到可控制机器接口（需要 NTM: 诅咒版）", x, y,
                ScreenFonts.cjk(9), machineApiReady ? CtrlPainter.TEXT_DIM : CtrlPainter.AMBER);
        y += 6;
        int rowH = 18;
        for (int i = 0; i < CtrlPanel.LINKS; i++) {
            CtrlLink link = panel.links[i];
            int ry = y + i * rowH;
            boolean hover = inside(mouseX, mouseY, x, ry, PANEL_W, rowH - 2);
            g.setColor(hover && mode != 0 ? new Color(0x1E262E) : new Color(0x141A20));
            g.fill(new RoundRectangle2D.Float(x, ry, PANEL_W, rowH - 2, 3, 3));
            g.setColor(new Color(0x2C343D));
            g.draw(new RoundRectangle2D.Float(x + 0.5F, ry + 0.5F, PANEL_W - 1, rowH - 3, 3, 3));

            g.setFont(ScreenFonts.mono(10));
            g.setColor(link.isEmpty() ? CtrlPainter.TEXT_DIM : new Color(0x6FD3FF));
            g.drawString(CtrlLink.letter(i), x + 6, ry + 13);

            String label = link.isEmpty() ? "未绑定" : link.label;
            int cx = x + 22;
            int room = 148;
            g.setFont(ScreenFonts.cjk(9));
            FontMetrics metrics = g.getFontMetrics();
            while (metrics.stringWidth(label) > room && label.length() > 1) {
                label = label.substring(0, label.length() - 1);
            }
            g.setColor(link.isEmpty() ? CtrlPainter.TEXT_DIM : CtrlPainter.TEXT);
            g.drawString(label, cx, ry + 13);

            if (!link.isEmpty()) {
                g.setFont(ScreenFonts.mono(8));
                g.setColor(CtrlPainter.TEXT_DIM);
                g.drawString(link.pos.getX() + "," + link.pos.getY() + "," + link.pos.getZ(), x + 176, ry + 13);
            }
            if (mode != 0) {
                int bx = x + PANEL_W - 76;
                chip(g, bx, ry + 3, 36, 12, "绑定", mouseX, mouseY, false);
                add("slot:" + i + ":bind", bx, ry + 3, 36, 12);
                int cxx = x + PANEL_W - 36;
                chip(g, cxx, ry + 3, 32, 12, "清空", mouseX, mouseY, false);
                add("slot:" + i + ":clear", cxx, ry + 3, 32, 12);
            }
        }
    }

    /** Small button, drawn the same everywhere so the eye learns it once. */
    private void chip(Graphics2D g, int x, int y, int w, int h, String label, int mouseX, int mouseY,
                      boolean active) {
        boolean hover = inside(mouseX, mouseY, x, y, w, h);
        g.setColor(active ? new Color(0x27414F) : (hover ? new Color(0x28323C) : new Color(0x1B222A)));
        g.fill(new RoundRectangle2D.Float(x, y, w, h, 3, 3));
        g.setColor(active ? new Color(0x6FD3FF) : new Color(0x39434D));
        g.setStroke(new BasicStroke(1F));
        g.draw(new RoundRectangle2D.Float(x + 0.5F, y + 0.5F, w - 1, h - 1, 3, 3));
        CtrlPainter.centered(g, label, x + w / 2, y + h - (h - 9) / 2,
                ScreenFonts.cjk(Math.max(8, Math.min(9, h - 3))), active ? new Color(0xCDEFFF) : CtrlPainter.TEXT);
    }

    // -------------------------------------------------------------- side: run

    private void paintRunSide(Graphics2D g, CtrlPanel panel, String status, int mouseX, int mouseY) {
        int x = SIDE_X;
        int y = SIDE_Y;
        CtrlPainter.text(g, "运行", x, y + 10, ScreenFonts.cjk(11), CtrlPainter.TEXT);
        y += 18;
        g.setColor(new Color(0x2A323B));
        g.drawLine(x, y, x + SIDE_W, y);
        y += 12;

        CtrlPainter.text(g, "操控方式", x, y, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
        y += 14;
        String[] lines = {
                "· 旋钮 / 滑杆:按住左键拖动",
                "· 开关 / 按钮:左键单击",
                "· 急停:单击按下,再单击复位",
                "· 终端:左键或右键开窗",
                "· 显示类控件只读",
        };
        for (String line : lines) {
            CtrlPainter.text(g, line, x, y, ScreenFonts.cjk(9), CtrlPainter.TEXT);
            y += 13;
        }
        y += 6;
        g.setColor(new Color(0x2A323B));
        g.drawLine(x, y, x + SIDE_W, y);
        y += 12;
        CtrlPainter.text(g, "当前状态", x, y, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
        y += 14;
        int bound = 0;
        int operators = 0;
        for (CtrlWidget widget : panel.widgets) {
            if (widget.bound()) {
                bound++;
            }
            if (widget.isOperator()) {
                operators++;
            }
        }
        CtrlPainter.text(g, "控件 " + panel.widgets.size() + " · 操作件 " + operators + " · 已接线 " + bound,
                x, y, ScreenFonts.cjk(9), CtrlPainter.TEXT);
        y += 13;
        CtrlPainter.text(g, "联动规则 " + panel.rules.size() + " 条", x, y, ScreenFonts.cjk(9), CtrlPainter.TEXT);
        y += 22;

        chip(g, x, y, 100, 20, "编辑控件", mouseX, mouseY, false);
        add("goto:1", x, y, 100, 20);
        chip(g, x + 108, y, 100, 20, "编辑联动", mouseX, mouseY, false);
        add("goto:2", x + 108, y, 100, 20);

        if (status != null && !status.isEmpty()) {
            CtrlPainter.text(g, status, x, SIDE_Y + SIDE_H - 6, ScreenFonts.cjk(9), CtrlPainter.AMBER);
        }
    }

    // ----------------------------------------------------------- side: widget

    private void paintWidgetSide(Graphics2D g, CtrlPanel panel, int selWidget, int mouseX, int mouseY,
                                 String status) {
        int x = SIDE_X;
        int y = SIDE_Y;
        CtrlPainter.text(g, "控件库", x, y + 10, ScreenFonts.cjk(11), CtrlPainter.TEXT);
        y += 20;

        // Palette: three columns. Two columns looked roomier per button but
        // pushed the inspector off the bottom of the sheet, and the inspector
        // is where the work happens. The row count is derived, not written
        // down: a literal here silently overlapped the inspector the first
        // time a type was added.
        int cellW = 68;
        int cellH = 18;
        int types = CtrlType.palette().length;
        for (int i = 0; i < types; i++) {
            int col = i % 3;
            int row = i / 3;
            int cx = x + col * (cellW + 6);
            int cy = y + row * (cellH + 3);
            CtrlType type = CtrlType.palette()[i];
            chip(g, cx, cy, cellW, cellH, type.label(), mouseX, mouseY, false);
            add("add:" + type.name(), cx, cy, cellW, cellH);
        }
        int paletteRows = (types + 2) / 3;
        y += paletteRows * (cellH + 3) + 6;
        g.setColor(new Color(0x2A323B));
        g.drawLine(x, y, x + SIDE_W, y);
        y += 14;

        CtrlWidget widget = panel.get(selWidget);
        if (widget == null) {
            CtrlPainter.text(g, "选中一个控件以编辑", x, y, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
            if (status != null && !status.isEmpty()) {
                CtrlPainter.text(g, status, x, SIDE_Y + SIDE_H - 6, ScreenFonts.cjk(9), CtrlPainter.AMBER);
            }
            return;
        }

        CtrlPainter.text(g, widget.type.label()
                        + (widget.type.isTerminal() ? "（终端）" : (widget.isOperator() ? "（操作件）" : "（显示件）")),
                x, y, ScreenFonts.cjk(9), new Color(0x6FD3FF));
        y += 14;

        y = field(g, x, y, "标签", widget.label, "field:label", mouseX, mouseY);

        // Slot stepper. Named wslot, not slot: the slots row below already owns
        // the "slot:<n>:bind" ids and the two must not be confused. A terminal
        // reads it as its target because that is what it means there - the
        // machine it is talking to right now, not a wire.
        y = stepper(g, x, y, widget.type.isTerminal() ? "目标" : "槽位",
                CtrlLink.letter(widget.link), "wslot:-", "wslot:+", mouseX, mouseY);

        if (widget.type.isTerminal()) {
            CtrlPainter.text(g, "在“运行”页右键此控件开窗", x, y + 10, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
            y += 14;
            CtrlPainter.text(g, "命令 ls / use / read / ev / set", x, y + 10, ScreenFonts.cjk(8),
                    CtrlPainter.TEXT_DIM);
            y += 16;
            chip(g, x, y, 92, 18, "打开终端", mouseX, mouseY, false);
            add("openterm", x, y, 92, 18);
            y += 24;
        } else {
            if (widget.type.usesKey()) {
                y = field(g, x, y, "数据键", widget.key.isEmpty() ? "（未选）" : widget.key, "field:key", mouseX, mouseY);
                int keys = widget.bound() ? panel.links[widget.link].keys.size() : 0;
                if (widget.bound() && keys > 0) {
                    chip(g, x + SIDE_W - 40, y - 15, 40, 13, "选键", mouseX, mouseY, false);
                    add("pickkey", x + SIDE_W - 40, y - 15, 40, 13);
                }
            }
            if (widget.type.usesEvent()) {
                y = field(g, x, y, "事件", widget.event.isEmpty() ? "（未选）" : widget.event, "field:event", mouseX, mouseY);
                int events = widget.bound() ? panel.links[widget.link].events.size() : 0;
                if (widget.bound() && events > 0) {
                    chip(g, x + SIDE_W - 40, y - 15, 40, 13, "选事件", mouseX, mouseY, false);
                    add("pickevent", x + SIDE_W - 40, y - 15, 40, 13);
                }
                y = field(g, x, y, "变量", widget.var.isEmpty() ? "（无参）" : widget.var, "field:var", mouseX, mouseY);
            }

            if (widget.type.isBooleanish()) {
                // A lamp has no range to speak of; say so instead of showing a
                // stepper that would do nothing.
                CtrlPainter.text(g, "量程", x, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
                CtrlPainter.text(g, "0 – 1（开关量）", x + 62, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT);
                y += 18;
            } else {
                CtrlPainter.text(g, "量程", x, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
                miniStepper(g, x + 62, y, String.valueOf((int) widget.min), "min:-", "min:+", mouseX, mouseY);
                miniStepper(g, x + 130, y, String.valueOf((int) widget.max), "max:-", "max:+", mouseX, mouseY);
                y += 18;
            }
            if (widget.type == CtrlType.ROTARY) {
                CtrlPainter.text(g, "档位", x, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
                miniStepper(g, x + 62, y, String.valueOf(widget.steps), "steps:-", "steps:+", mouseX, mouseY);
                y += 18;
            }
            CtrlPainter.text(g, "小数", x, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
            miniStepper(g, x + 62, y, String.valueOf(widget.decimals), "dec:-", "dec:+", mouseX, mouseY);
            CtrlPainter.text(g, "配色", x + 130, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
            miniStepper(g, x + 166, y, String.valueOf(widget.color), "color:-", "color:+", mouseX, mouseY);
            y += 20;
            y += 6;
        }

        chip(g, x, y, 62, 18, "删除", mouseX, mouseY, false);
        add("delwidget", x, y, 62, 18);
        chip(g, x + 70, y, 62, 18, "复制", mouseX, mouseY, false);
        add("copywidget", x + 70, y, 62, 18);
        chip(g, x + 140, y, 76, 18, "保存并应用", mouseX, mouseY, true);
        add("save", x + 140, y, 76, 18);

        if (status != null && !status.isEmpty()) {
            CtrlPainter.text(g, status, x, SIDE_Y + SIDE_H - 6, ScreenFonts.cjk(9), CtrlPainter.AMBER);
        }
    }

    // ------------------------------------------------------------- side: rule

    private void paintRuleSide(Graphics2D g, CtrlPanel panel, int selRule, int mouseX, int mouseY,
                               String status) {
        int x = SIDE_X;
        int y = SIDE_Y;
        CtrlPainter.text(g, "联动规则", x, y + 10, ScreenFonts.cjk(11), CtrlPainter.TEXT);
        chip(g, x + SIDE_W - 56, y, 56, 16, "+ 新增", mouseX, mouseY, false);
        add("addrule", x + SIDE_W - 56, y, 56, 16);
        y += 22;

        // Rule picker as a strip of numbers, not a list of sentences: six rules
        // stacked as three-line cards ate the whole side bar and left no room
        // for the thing being edited. The selected rule's sentence is shown
        // underneath instead, once.
        for (int i = 0; i < CtrlPanel.MAX_RULES; i++) {
            int cx = x + i * 36;
            boolean exists = i < panel.rules.size();
            boolean selected = i == selRule;
            if (exists) {
                CtrlRule rule = panel.rules.get(i);
                chip(g, cx, y, 32, 16, String.valueOf(i + 1), mouseX, mouseY, selected);
                // A dot in the corner for "enabled", readable without text.
                g.setColor(rule.enabled ? CtrlPainter.GREEN : new Color(0x3A434D));
                g.fill(new Ellipse2D.Float(cx + 26, y + 3, 3, 3));
                add("rule:" + i, cx, y, 32, 16);
            } else {
                g.setColor(new Color(0x141A20));
                g.fill(new RoundRectangle2D.Float(cx, y, 32, 16, 3, 3));
                add("rule:new", cx, y, 32, 16);
            }
        }
        y += 22;

        CtrlRule rule = selRule >= 0 && selRule < panel.rules.size() ? panel.rules.get(selRule) : null;
        if (rule == null) {
            CtrlPainter.text(g, panel.rules.isEmpty() ? "还没有规则 — 点“新增”"
                    : "点上面的编号选择规则", x, y + 8, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
            if (status != null && !status.isEmpty()) {
                CtrlPainter.text(g, status, x, SIDE_Y + SIDE_H - 6, ScreenFonts.cjk(9), CtrlPainter.AMBER);
            }
            return;
        }

        g.setFont(ScreenFonts.cjk(8));
        g.setColor(CtrlPainter.TEXT_DIM);
        String summary = rule.summary();
        FontMetrics metrics = g.getFontMetrics();
        int cut = summary.length();
        while (cut > 4 && metrics.stringWidth(summary.substring(0, cut)) > SIDE_W) {
            cut--;
        }
        g.drawString(summary.substring(0, cut), x, y + 6);
        y += 14;
        g.setColor(new Color(0x2A323B));
        g.drawLine(x, y, x + SIDE_W, y);
        y += 12;

        CtrlPainter.text(g, "当", x, y + 8, ScreenFonts.cjk(9), new Color(0x6FD3FF));
        y += 12;
        y = stepper(g, x, y, "源槽位", CtrlLink.letter(rule.srcLink), "rslot:-", "rslot:+", mouseX, mouseY);
        y = field(g, x, y, "源数据键", rule.srcKey.isEmpty() ? "（未选）" : rule.srcKey, "field:rkey", mouseX, mouseY);
        y = stepper(g, x, y, "比较", rule.opLabel(), "rop:-", "rop:+", mouseX, mouseY);
        y = stepper2(g, x, y, "阈值", trim(rule.threshold), "rth:-", "rth:+", mouseX, mouseY);

        CtrlPainter.text(g, "则", x, y + 6, ScreenFonts.cjk(9), new Color(0x6FD3FF));
        y += 12;
        y = stepper(g, x, y, "目标槽位", CtrlLink.letter(rule.dstLink), "rdslot:-", "rdslot:+", mouseX, mouseY);
        y = field(g, x, y, "事件", rule.dstEvent.isEmpty() ? "（未选）" : rule.dstEvent, "field:revent", mouseX, mouseY);
        y = field(g, x, y, "变量", rule.dstVar.isEmpty() ? "（无参）" : rule.dstVar, "field:rvar", mouseX, mouseY);
        y = stepper2(g, x, y, "发送值", trim(rule.value), "rval:-", "rval:+", mouseX, mouseY);
        y = stepper(g, x, y, "冷却", rule.cooldown + "s", "rcd:-", "rcd:+", mouseX, mouseY);

        chip(g, x, y + 2, 56, 18, rule.enabled ? "停用" : "启用", mouseX, mouseY, false);
        add("rtoggle", x, y + 2, 56, 18);
        chip(g, x + 64, y + 2, 56, 18, "删除", mouseX, mouseY, false);
        add("rdel", x + 64, y + 2, 56, 18);
        chip(g, x + 128, y + 2, 88, 18, "保存并应用", mouseX, mouseY, true);
        add("save", x + 128, y + 2, 88, 18);

        if (status != null && !status.isEmpty()) {
            CtrlPainter.text(g, status, x, SIDE_Y + SIDE_H - 6, ScreenFonts.cjk(9), CtrlPainter.AMBER);
        }
    }

    // --------------------------------------------------------- row primitives

    /** One "name + editable value" row. Returns the next y. */
    private int field(Graphics2D g, int x, int y, String name, String value, String id,
                      int mouseX, int mouseY) {
        CtrlPainter.text(g, name, x, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
        int bx = x + 62;
        int bw = SIDE_W - 62 - 46;
        boolean hover = inside(mouseX, mouseY, bx, y, bw, 16);
        g.setColor(hover ? new Color(0x1F272F) : new Color(0x141A20));
        g.fill(new RoundRectangle2D.Float(bx, y, bw, 16, 3, 3));
        g.setColor(new Color(0x39434D));
        g.draw(new RoundRectangle2D.Float(bx + 0.5F, y + 0.5F, bw - 1, 15, 3, 3));
        boolean active = id.equals(this.editId);
        g.setFont(ScreenFonts.cjk(9));
        FontMetrics metrics = g.getFontMetrics();
        String shown = active ? this.editText : value;
        while (metrics.stringWidth(shown) > bw - 8 && shown.length() > 1) {
            shown = shown.substring(shown.length() - 1);
        }
        g.setColor(active ? new Color(0xCDEFFF) : CtrlPainter.TEXT);
        g.drawString(shown, bx + 4, y + 12);
        if (active) {
            // Caret after the buffer, because the buffer is right-aligned when
            // it overflows - the cursor has to sit where the next character
            // will land, not where it would land in a shorter string.
            int caretX = bx + 4 + metrics.stringWidth(shown);
            if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
                g.setColor(new Color(0x6FD3FF));
                g.drawLine(caretX, y + 3, caretX, y + 13);
            }
        }
        add(id, bx, y, bw, 16);
        return y + 18;
    }

    /** Narrow value box with − / + beside it, for two-per-row layouts. */
    private int miniStepper(Graphics2D g, int x, int y, String value, String minusId, String plusId,
                            int mouseX, int mouseY) {
        int boxW = 30;
        g.setColor(new Color(0x141A20));
        g.fill(new RoundRectangle2D.Float(x, y, boxW, 16, 3, 3));
        g.setColor(new Color(0x39434D));
        g.draw(new RoundRectangle2D.Float(x + 0.5F, y + 0.5F, boxW - 1, 15, 3, 3));
        CtrlPainter.centered(g, value, x + boxW / 2, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT);
        chip(g, x + boxW + 2, y, 14, 16, "−", mouseX, mouseY, false);
        add(minusId, x + boxW + 2, y, 14, 16);
        chip(g, x + boxW + 18, y, 14, 16, "+", mouseX, mouseY, false);
        add(plusId, x + boxW + 18, y, 14, 16);
        return y + 18;
    }

    /** One "name + value + two arrows" row. */
    private int stepper(Graphics2D g, int x, int y, String name, String value,
                        String minusId, String plusId, int mouseX, int mouseY) {
        CtrlPainter.text(g, name, x, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT_DIM);
        int bx = x + 62;
        int bw = 92;
        g.setColor(new Color(0x141A20));
        g.fill(new RoundRectangle2D.Float(bx, y, bw, 16, 3, 3));
        g.setColor(new Color(0x39434D));
        g.draw(new RoundRectangle2D.Float(bx + 0.5F, y + 0.5F, bw - 1, 15, 3, 3));
        CtrlPainter.centered(g, value, bx + bw / 2, y + 12, ScreenFonts.cjk(9), CtrlPainter.TEXT);
        chip(g, bx + bw + 4, y, 18, 16, "−", mouseX, mouseY, false);
        add(minusId, bx + bw + 4, y, 18, 16);
        chip(g, bx + bw + 24, y, 18, 16, "+", mouseX, mouseY, false);
        add(plusId, bx + bw + 24, y, 18, 16);
        return y + 18;
    }

    private int stepper2(Graphics2D g, int x, int y, String name, String value,
                         String minusId, String plusId, int mouseX, int mouseY) {
        return stepper(g, x, y, name, value, minusId, plusId, mouseX, mouseY);
    }

    private void add(String id, int x, int y, int w, int h) {
        this.hits.add(new Hit(id, x, y, w, h));
    }

    private static boolean inside(int px, int py, int x, int y, int w, int h) {
        return px >= x && px < x + w && py >= y && py < y + h;
    }

    private static String trim(double value) {
        return Math.abs(value - Math.rint(value)) < 1.0E-4D
                ? String.valueOf((long) Math.rint(value))
                : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    // ------------------------------------------------------------- upload path

    /** Uploads the sheet. Render thread only. */
    public void upload() {
        if (this.image == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean forced = now - this.lastUpload > 4000L;
        if (!this.dirty && !forced) {
            return;
        }
        this.dirty = false;
        this.lastUpload = now;
        int w = W * SS;
        int h = H * SS;
        ensureTexture(w, h);
        this.image.getRGB(0, 0, w, h, this.pixels, 0, w);
        // Comparing 2.3M ints costs a fraction of pushing 9MB over the bus, and
        // an idle window (which is most of them) then uploads nothing at all.
        if (!forced && this.lastPixels != null
                && java.util.Arrays.equals(this.lastPixels, this.pixels)) {
            return;
        }
        if (this.lastPixels == null || this.lastPixels.length != this.pixels.length) {
            this.lastPixels = new int[this.pixels.length];
        }
        System.arraycopy(this.pixels, 0, this.lastPixels, 0, this.pixels.length);
        if (this.upload == null || this.upload.capacity() < this.pixels.length * 4) {
            this.upload = ByteBuffer.allocateDirect(this.pixels.length * 4).order(ByteOrder.nativeOrder());
        }
        this.upload.clear();
        this.upload.asIntBuffer().put(this.pixels);
        this.upload.position(0).limit(this.pixels.length * 4);
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
        net.minecraft.client.renderer.GlStateManager.bindTexture(this.texId);
        org.lwjgl.opengl.GL11.glTexSubImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, 0, 0, w, h,
                org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, this.upload);
    }

    private void ensureTexture(int w, int h) {
        if (this.texId == 0) {
            this.texId = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            net.minecraft.client.renderer.GlStateManager.bindTexture(this.texId);
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA8, w, h, 0,
                    org.lwjgl.opengl.GL12.GL_BGRA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            params();
        }
    }

    private void params() {
        net.minecraft.client.renderer.GlStateManager.bindTexture(this.texId);
        net.minecraft.client.renderer.GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        net.minecraft.client.renderer.GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
        net.minecraft.client.renderer.GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        net.minecraft.client.renderer.GlStateManager.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
    }

    public void dispose() {
        if (this.texId != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(this.texId);
            this.texId = 0;
        }
        this.image = null;
        this.pixels = null;
        this.lastPixels = null;
    }
}

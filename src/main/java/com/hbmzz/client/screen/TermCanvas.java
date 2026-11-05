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
import com.hbmzz.ctrl.TerminalShell;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * The terminal window: one AWT-painted sheet, uploaded as a single texture.
 *
 * <p>Same pipeline as the monitor's terminal and the panel editor, for the same
 * reason - the window is mostly text, and real typography at a real size beats
 * the vanilla bitmap font at GUI scale 2. It also keeps the three windows
 * speaking one visual language: amber phosphor, hairline borders, no chrome.</p>
 *
 * <p>Fixed logical size, scaled to fit. The layout is deliberately a stack of
 * full-width bands (header, machine strip, scrollback, input, hints), so the
 * only thing that ever changes size with the window is the scrollback - which
 * is exactly the part that should absorb it.</p>
 */
public class TermCanvas {

    public static final int W = 560;
    public static final int H = 360;
    private static final int SS = 2;

    // -------------------------------------------------------------- palette

    private static final int BG = 0xFF080706;
    private static final int PANEL = 0xFF15120E;
    private static final int WELL = 0xFF0C0A08;
    private static final int LINE = 0xFF3A3021;
    private static final int TXT = 0xFFEDE1C4;
    private static final int DIM = 0xFF9A8967;
    private static final int FAINT = 0xFF5E5140;
    private static final int AMBER = 0xFFFFB03A;
    private static final int MINT = 0xFF5FD9C0;
    private static final int OK = 0xFF8CD96A;
    private static final int CRIT = 0xFFFF5C3C;

    // --------------------------------------------------------------- layout

    private static final int PAD = 12;
    private static final int HEADER_H = 30;
    private static final int STRIP_Y = 34;
    private static final int STRIP_H = 20;
    private static final int LOG_Y = 62;
    private static final int INPUT_H = 22;
    private static final int FOOT_H = 22;
    private static final int LINE_H = 13;

    /** One line of scrollback, with the kind that decides its colour. */
    public static final class Line {

        public final String text;
        public final int kind;

        public Line(String text, int kind) {
            this.text = text;
            this.kind = kind;
        }
    }

    /**
     * Everything the window draws, assembled by the GUI.
     *
     * <p>A plain field bag rather than a dozen parameters: the canvas is a
     * renderer, and a renderer that remembers state is a renderer that can
     * disagree with the screen it is attached to.</p>
     */
    public static final class View {

        public final List<Line> lines = new ArrayList<Line>();
        /** Machine name per slot, null when unbound. */
        public final String[] slots = new String[LINKS];
        public int selected = -1;
        public String panelName = "";
        public String input = "";
        public int caret;
        public String prompt = ">";
        public String subtitle = "";
        public String status = "";
        public final List<String> suggest = new ArrayList<String>();
        public int suggestSel;
        /** 0 = pinned to the newest line; positive = lines scrolled back. */
        public int scroll;
    }

    /** Mirrors {@code CtrlPanel.LINKS} without dragging the whole ctrl package in. */
    public static final int LINKS = 6;

    // ---------------------------------------------------------------- state

    private BufferedImage image;
    private int[] pixels;
    private int texId;
    private ByteBuffer upload;
    private boolean dirty = true;
    private int lastHash;

    private final List<int[]> slotHits = new ArrayList<int[]>();
    private int[] closeHit;

    public void dispose() {
        if (this.texId != 0) {
            org.lwjgl.opengl.GL11.glDeleteTextures(this.texId);
            this.texId = 0;
        }
        this.image = null;
        this.pixels = null;
    }

    public int textureId() {
        return this.texId;
    }

    /**
     * The painted sheet, for the offline preview tool.
     *
     * <p>Same door {@code CtrlCanvas} opens: the window is the only part of the
     * terminal that reading the code cannot confirm, so it has to be viewable
     * without a GPU or a game.</p>
     */
    public java.awt.image.BufferedImage image() {
        return this.image;
    }

    public void markDirty() {
        this.dirty = true;
    }

    /** Slot index under a logical point, or -1. */
    public int slotAt(int x, int y) {
        for (int i = 0; i < this.slotHits.size(); i++) {
            int[] box = this.slotHits.get(i);
            if (x >= box[1] && x < box[1] + box[3] && y >= box[2] && y < box[2] + box[4]) {
                return box[0];
            }
        }
        return -1;
    }

    public boolean closeAt(int x, int y) {
        return this.closeHit != null
                && x >= this.closeHit[0] && x < this.closeHit[0] + this.closeHit[2]
                && y >= this.closeHit[1] && y < this.closeHit[1] + this.closeHit[3];
    }

    /** Scrollback window in logical pixels: x, y, w, h. Used for wheel bounds. */
    public static int[] logRect() {
        return new int[]{PAD, LOG_Y, W - PAD * 2, H - LOG_Y - INPUT_H - FOOT_H - 8};
    }

    /** How many whole lines fit in the scrollback. */
    public static int visibleLines() {
        return Math.max(1, logRect()[3] / LINE_H);
    }

    // ================================================================ painting

    /**
     * Paints the window.
     *
     * <p>Skips the whole frame when nothing it draws has changed: a terminal
     * sitting open while the player thinks must not cost a 2 MB texture upload
     * sixty times a second. The caret is the one thing that keeps moving, so it
     * is quantised to its blink phase rather than to the millisecond.</p>
     */
    public void render(View view, int mouseX, int mouseY) {
        if (view == null) {
            return;
        }
        int hash = hash(view, mouseX, mouseY);
        if (!this.dirty && hash == this.lastHash && this.image != null) {
            return;
        }
        this.lastHash = hash;
        this.dirty = false;

        Graphics2D g = begin();
        try {
            this.slotHits.clear();
            g.setColor(new Color(BG, true));
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(0x2A2419));
            g.drawRect(3, 3, W - 7, H - 7);

            paintHeader(g, view, mouseX, mouseY);
            paintStrip(g, view, mouseX, mouseY);
            paintLog(g, view);
            paintInput(g, view);
            paintFooter(g, view);
            paintSuggest(g, view);
        } finally {
            g.dispose();
        }
    }

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
        return g;
    }

    private void paintHeader(Graphics2D g, View view, int mouseX, int mouseY) {
        rect(g, 0, 0, W, HEADER_H, PANEL);
        rect(g, 0, HEADER_H - 1, W, 1, LINE);
        rect(g, PAD, 8, 3, 14, AMBER);
        text(g, "机器终端", PAD + 9, 20, TXT, ScreenFonts.cjkBold(12));
        int titleW = width(g, "机器终端", ScreenFonts.cjkBold(12));
        if (!view.panelName.isEmpty()) {
            text(g, "· " + view.panelName, PAD + 9 + titleW + 6, 20, FAINT, ScreenFonts.cjk(9));
        }

        int size = 16;
        int cx = W - PAD - size;
        int cy = (HEADER_H - size) / 2;
        boolean hover = mouseX >= cx && mouseX < cx + size && mouseY >= cy && mouseY < cy + size;
        rect(g, cx, cy, size, size, hover ? 0xFF4A1A12 : 0xFF1E1A14);
        frame(g, cx, cy, size, size, hover ? CRIT : LINE);
        center(g, "x", cx, size, cy + 11, hover ? 0xFFFFC8BC : DIM, ScreenFonts.mono(10));
        this.closeHit = new int[]{cx, cy, size, size};

        // Target on the right of the header, right to left, so the one number
        // that matters while typing is never the thing that gets clipped.
        if (!view.subtitle.isEmpty()) {
            String target = view.subtitle;
            Font font = ScreenFonts.hasCjk(target) ? ScreenFonts.cjk(9) : ScreenFonts.mono(9);
            int tw = width(g, target, font);
            int tx = Math.max(PAD + 120, cx - 14 - tw);
            text(g, target, tx, 20, MINT, font);
        }
    }

    /**
     * The six slots, as tabs.
     *
     * <p>This is the answer to "which machine am I typing at" that costs no
     * typing: the letters are the same ones the panel uses, the current one is
     * filled, and an unbound slot is drawn dead instead of being hidden - a
     * gap in the alphabet reads as a bug, a dimmed slot reads as "not wired".</p>
     */
    private void paintStrip(Graphics2D g, View view, int mouseX, int mouseY) {
        int gap = 4;
        int chipW = (W - PAD * 2 - gap * (LINKS - 1)) / LINKS;
        for (int i = 0; i < LINKS; i++) {
            int x = PAD + i * (chipW + gap);
            int y = STRIP_Y;
            String name = view.slots[i];
            boolean active = i == view.selected;
            boolean bound = name != null;
            boolean hover = bound && mouseX >= x && mouseX < x + chipW
                    && mouseY >= y && mouseY < y + STRIP_H;
            rect(g, x, y, chipW, STRIP_H, active ? 0xFF2A210F : (hover ? 0xFF1E1A14 : WELL));
            frame(g, x, y, chipW, STRIP_H, active ? AMBER : (hover ? 0xFF6B5A3E : LINE));
            if (active) {
                rect(g, x, y, 2, STRIP_H, AMBER);
            }
            Font letter = ScreenFonts.monoBold(9);
            text(g, CtrlLink.letter(i), x + 6, y + 14,
                    bound ? (active ? AMBER : TXT) : FAINT, letter);
            int room = chipW - 20;
            String label = bound ? name : "未绑定";
            Font font = ScreenFonts.cjk(8);
            text(g, clip(g, label, room, font), x + 17, y + 14,
                    bound ? (active ? TXT : DIM) : FAINT, font);
            if (bound) {
                this.slotHits.add(new int[]{i, x, y, chipW, STRIP_H});
            }
        }
    }

    private void paintLog(Graphics2D g, View view) {
        int[] box = logRect();
        rect(g, box[0], box[1], box[2], box[3], WELL);
        frame(g, box[0], box[1], box[2], box[3], LINE);

        int rows = visibleLines();
        int count = view.lines.size();
        int end = Math.max(0, count - Math.max(0, view.scroll));
        int start = Math.max(0, end - rows);
        int y = box[1] + 12;
        for (int i = start; i < end; i++) {
            Line line = view.lines.get(i);
            Font font = ScreenFonts.hasCjk(line.text) ? ScreenFonts.cjk(9) : ScreenFonts.mono(9);
            text(g, clip(g, line.text, box[2] - 12, font), box[0] + 6, y, colour(line.kind), font);
            y += LINE_H;
        }
        if (count == 0) {
            text(g, "输入 help 查看命令。", box[0] + 6, box[1] + 12, FAINT, ScreenFonts.cjk(9));
        }
        if (view.scroll > 0) {
            // Scrollback indicator, because a window that silently shows older
            // lines looks like it stopped updating.
            String mark = "↑ " + view.scroll;
            int tw = width(g, mark, ScreenFonts.mono(8));
            rect(g, box[0] + box[2] - tw - 12, box[1] + 3, tw + 8, 12, 0xFF1E1A14);
            text(g, mark, box[0] + box[2] - tw - 8, box[1] + 12, AMBER, ScreenFonts.mono(8));
        }
    }

    private void paintInput(Graphics2D g, View view) {
        int y = H - FOOT_H - INPUT_H - 4;
        rect(g, PAD, y, W - PAD * 2, INPUT_H, PANEL);
        frame(g, PAD, y, W - PAD * 2, INPUT_H, AMBER);
        rect(g, PAD, y, 2, INPUT_H, AMBER);

        int promptX = PAD + 7;
        Font promptFont = ScreenFonts.monoBold(10);
        text(g, view.prompt, promptX, y + 15, AMBER, promptFont);
        int textX = promptX + width(g, view.prompt, promptFont) + 5;

        Font font = ScreenFonts.hasCjk(view.input) ? ScreenFonts.cjk(10) : ScreenFonts.mono(10);
        int room = W - PAD - 8 - textX;
        String shown = view.input;
        int caret = Math.max(0, Math.min(shown.length(), view.caret));
        // Keep the caret visible on a long line by dropping from the front;
        // the input is a command, not a document, so horizontal scrolling is
        // one word of arithmetic instead of a viewport.
        int from = 0;
        while (from < caret && width(g, shown.substring(from, caret), font) > room - 6) {
            from++;
        }
        String visible = shown.substring(from);
        if (width(g, visible, font) > room) {
            while (visible.length() > 1 && width(g, visible, font) > room) {
                visible = visible.substring(0, visible.length() - 1);
            }
        }
        text(g, visible, textX, y + 15, TXT, font);
        if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
            int caretX = textX + width(g, shown.substring(from, caret), font);
            rect(g, caretX, y + 4, 1, 14, AMBER);
        }
    }

    private void paintFooter(Graphics2D g, View view) {
        int y = H - FOOT_H + 6;
        String hint = "Tab 补全   ↑↓ 历史   PgUp/PgDn 滚动   Esc 关闭";
        text(g, hint, PAD, y + 8, FAINT, ScreenFonts.cjk(8));
        if (!view.status.isEmpty()) {
            Font font = ScreenFonts.cjk(8);
            int tw = width(g, view.status, font);
            if (PAD + width(g, hint, ScreenFonts.cjk(8)) + 12 < W - PAD - tw) {
                text(g, view.status, W - PAD - tw, y + 8, DIM, font);
            }
        }
    }

    /**
     * Completion list, drawn above the input box.
     *
     * <p>Above, not below: the input sits near the bottom edge, and a list that
     * hangs off the window is the classic way to hide the one thing the player
     * asked for. It is anchored to the prompt so the eye does not have to
     * travel from the word being completed.</p>
     */
    private void paintSuggest(Graphics2D g, View view) {
        if (view.suggest.isEmpty()) {
            return;
        }
        Font font = ScreenFonts.cjk(9);
        int rows = Math.min(6, view.suggest.size());
        int hidden = view.suggest.size() - rows;
        // The "+N" badge needs its own column: drawn over the candidates it
        // lands on the first row's tail, where the eye reads it as part of the
        // longest candidate name.
        String more = hidden > 0 ? "+" + hidden : "";
        int badge = more.isEmpty() ? 0 : width(g, more, ScreenFonts.mono(8)) + 8;
        int boxW = 0;
        for (int i = 0; i < rows; i++) {
            boxW = Math.max(boxW, width(g, view.suggest.get(i), font));
        }
        boxW = Math.min(W - PAD * 2, boxW + 20 + badge);
        int boxH = rows * LINE_H + 6;
        int y = H - FOOT_H - INPUT_H - 4 - boxH - 2;

        rect(g, PAD, y, boxW, boxH, 0xFF110F0C);
        frame(g, PAD, y, boxW, boxH, AMBER);
        for (int i = 0; i < rows; i++) {
            boolean active = i == Math.max(0, Math.min(rows - 1, view.suggestSel));
            if (active) {
                rect(g, PAD + 1, y + 3 + i * LINE_H, boxW - 2, LINE_H, 0xFF2A210F);
                rect(g, PAD + 1, y + 3 + i * LINE_H, 2, LINE_H, AMBER);
            }
            text(g, clip(g, view.suggest.get(i), boxW - 14 - badge, font), PAD + 8, y + 12 + i * LINE_H,
                    active ? AMBER : DIM, font);
        }
        // More candidates than fit: say so, silently truncating looks like the
        // list was wrong.
        if (!more.isEmpty()) {
            text(g, more, PAD + boxW - width(g, more, ScreenFonts.mono(8)) - 4, y + 10,
                    FAINT, ScreenFonts.mono(8));
        }
    }

    private static int colour(int kind) {
        switch (kind) {
            case TerminalShell.ERR:
                return CRIT;
            case TerminalShell.ECHO:
                return AMBER;
            case TerminalShell.HINT:
                return DIM;
            case TerminalShell.OK:
                return OK;
            default:
                return TXT;
        }
    }

    // =============================================================== upload

    /** Uploads the sheet. Render thread only. */
    public void upload() {
        if (this.image == null) {
            return;
        }
        int w = W * SS;
        int h = H * SS;
        this.image.getRGB(0, 0, w, h, this.pixels, 0, w);
        if (this.texId == 0) {
            this.texId = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0);
            net.minecraft.client.renderer.GlStateManager.bindTexture(this.texId);
            org.lwjgl.opengl.GL11.glTexImage2D(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA8, w, h, 0, org.lwjgl.opengl.GL12.GL_BGRA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            net.minecraft.client.renderer.GlStateManager.glTexParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            net.minecraft.client.renderer.GlStateManager.glTexParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
        }
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

    // ============================================================ primitives

    private void rect(Graphics2D g, int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.fillRect(x, y, w, h);
    }

    private void frame(Graphics2D g, int x, int y, int w, int h, int argb) {
        g.setColor(new Color(argb, true));
        g.setStroke(new BasicStroke(1F));
        g.draw(new RoundRectangle2D.Float(x + 0.5F, y + 0.5F, w - 1, h - 1, 3, 3));
    }

    private void text(Graphics2D g, String value, int x, int y, int argb, Font font) {
        g.setFont(font);
        g.setColor(new Color(argb, true));
        g.drawString(value, x, y);
    }

    private void center(Graphics2D g, String value, int x, int w, int baseline, int argb, Font font) {
        text(g, value, x + w / 2 - width(g, value, font) / 2, baseline, argb, font);
    }

    private int width(Graphics2D g, String value, Font font) {
        FontMetrics metrics = g.getFontMetrics(font);
        return metrics.stringWidth(value);
    }

    /** Truncates with an ellipsis to a pixel budget. */
    private String clip(Graphics2D g, String value, int room, Font font) {
        FontMetrics metrics = g.getFontMetrics(font);
        if (metrics.stringWidth(value) <= room) {
            return value;
        }
        String out = value;
        while (out.length() > 1 && metrics.stringWidth(out + "…") > room) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }

    /**
     * A cheap fingerprint of everything the sheet depends on.
     *
     * <p>Cheaper than comparing 1.6 M pixels and cheaper still than uploading
     * them: a terminal is idle most of the time, and the only field that moves
     * on its own is the caret, which is folded in at its blink phase.</p>
     */
    private static int hash(View view, int mouseX, int mouseY) {
        int hash = 17;
        hash = hash * 31 + view.input.hashCode();
        hash = hash * 31 + view.caret;
        hash = hash * 31 + view.selected;
        hash = hash * 31 + view.scroll;
        hash = hash * 31 + view.status.hashCode();
        hash = hash * 31 + view.subtitle.hashCode();
        hash = hash * 31 + view.suggestSel;
        hash = hash * 31 + view.suggest.size();
        hash = hash * 31 + view.lines.size();
        hash = hash * 31 + (int) ((System.currentTimeMillis() / 500L) & 1L);
        for (Line line : view.lines) {
            hash = hash * 31 + line.text.hashCode();
            hash = hash * 31 + line.kind;
        }
        for (String slot : view.slots) {
            hash = hash * 31 + (slot == null ? 0 : slot.hashCode());
        }
        for (String candidate : view.suggest) {
            hash = hash * 31 + candidate.hashCode();
        }
        hash = hash * 31 + mouseX;
        hash = hash * 31 + mouseY;
        return hash;
    }
}

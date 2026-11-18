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

import com.hbmzz.panel.Channel;
import com.hbmzz.panel.Widget;
import com.hbmzz.panel.WidgetType;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.geom.Arc2D;
import java.util.Locale;

/**
 * Draws one custom-panel control.
 *
 * <p>Shared by the wall panel and by the studio's canvas - and that is the
 * whole point. If the editor had its own painter, a gauge would eventually look
 * one way while you were dragging it and another way once it was on the screen,
 * which is exactly the class of bug this feature cannot afford. The studio
 * draws the same routine inside a scaled transform, so what you see while
 * designing is literally the pixels that will be painted.</p>
 */
public final class WidgetPainter {

    private static final int BG = 0xFF15120E;
    private static final int BG_HI = 0xFF1E1A14;
    private static final int LINE = 0xFF3A3021;
    private static final int TXT = 0xFFEDE1C4;
    private static final int DIM = 0xFF9A8967;
    private static final int FAINT = 0xFF5E5140;
    private static final int OFF = 0xFF262119;
    private static final int AMBER = 0xFFFFB03A;
    private static final int MINT = 0xFF5FD9C0;
    private static final int CRIT = 0xFFFF5C3C;
    private static final int WARN = 0xFFFFC24A;
    private static final int OK = 0xFF8CD96A;

    private static final int[] PALETTE = {AMBER, MINT, CRIT, TXT, DIM};

    private WidgetPainter() {
    }

    /** Everything a widget needs to know about the world, gathered once. */
    public static final class Ctx {
        public double value;
        public double[] trend;
        public int trendCount;
        /** Current alarm / banner line, shown by {@link WidgetType#BANNER}. */
        public String banner = "";
        public int level;
        public boolean active;
        /** Editor only: draw the selection frame. */
        public boolean selected;
        /** Editor only: draw placeholder text for empty fields. */
        public boolean editor;
    }

    public static void paint(Graphics2D g, int x, int y, int w, int h, Widget widget, Ctx ctx) {
        if (w < 8 || h < 8 || widget == null) {
            return;
        }
        int accent = PALETTE[Math.max(0, Math.min(PALETTE.length - 1, widget.color))];
        g.setColor(new Color(BG, true));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(LINE, true));
        g.setStroke(new BasicStroke(1.0F));
        g.drawRect(x, y, w - 1, h - 1);
        g.setColor(new Color(accent, true));
        g.fillRect(x, y, 2, Math.min(h, 4));

        switch (widget.type) {
            case BAR:
                paintBar(g, x, y, w, h, widget, ctx, accent);
                break;
            case DIAL:
                paintDial(g, x, y, w, h, widget, ctx, accent);
                break;
            case LAMP:
                paintLamp(g, x, y, w, h, widget, ctx, accent);
                break;
            case LABEL:
                paintLabel(g, x, y, w, h, widget, accent);
                break;
            case TREND:
                paintTrend(g, x, y, w, h, widget, ctx, accent);
                break;
            case BANNER:
                paintBanner(g, x, y, w, h, ctx);
                break;
            case READOUT:
            default:
                paintReadout(g, x, y, w, h, widget, ctx, accent);
                break;
        }

        if (ctx != null && ctx.selected) {
            g.setColor(new Color(AMBER, true));
            g.setStroke(new BasicStroke(1.5F));
            g.drawRect(x - 2, y - 2, w + 3, h + 3);
            g.setStroke(new BasicStroke(1.0F));
        }
    }

    // ------------------------------------------------------------------ types

    private static void paintReadout(Graphics2D g, int x, int y, int w, int h,
                                     Widget widget, Ctx ctx, int accent) {
        double value = ctx == null ? 0.0D : ctx.value;
        String title = widget.title == null ? "" : widget.title;
        boolean ticked = h >= 22 && !title.isEmpty();
        if (ticked) {
            drawText(g, clip(g, title, w - 10, ScreenFonts.cjk(7)), x + 6, y + 11, DIM, ScreenFonts.cjk(7));
        }

        String number = number(widget, value);
        String unit = widget.channel == null ? "" : widget.channel.unit();
        int unitW = unit.isEmpty() ? 0 : width(g, unit, ScreenFonts.cjk(6)) + 2;
        int room = w - 12 - unitW;
        int top = ticked ? y + 13 : y + 3;
        int availH = h - (ticked ? 14 : 6);
        Font font = fit(g, number, room, Math.min(24, availH + 3), 7, true);
        int baseline = top + (availH + ascender(g, font)) / 2 - 1;
        drawText(g, number, x + 6, baseline, accent, font);
        if (!unit.isEmpty()) {
            drawText(g, unit, x + 6 + width(g, number, font) + 2,
                    baseline, FAINT, ScreenFonts.cjk(6));
        }
    }

    private static void paintBar(Graphics2D g, int x, int y, int w, int h,
                                 Widget widget, Ctx ctx, int accent) {
        double value = ctx == null ? 0.0D : ctx.value;
        String title = widget.title == null ? "" : widget.title;
        String number = number(widget, value);
        Font small = ScreenFonts.cjk(7);
        int numberW = width(g, number, ScreenFonts.monoBold(8)) + 2;
        if (h >= 18) {
            drawText(g, clip(g, title, w - numberW - 12, small), x + 6, y + 10, DIM, small);
            drawText(g, number, x + w - 6 - width(g, number, ScreenFonts.monoBold(8)), y + 10,
                    accent, ScreenFonts.monoBold(8));
        }
        int barY = h >= 18 ? y + h - 8 : y + h / 2 - 2;
        segBar(g, x + 5, barY, w - 10, Math.max(3, Math.min(6, h / 4)),
                14, widget.ratio(value), accent);
    }

    private static void paintDial(Graphics2D g, int x, int y, int w, int h,
                                  Widget widget, Ctx ctx, int accent) {
        double value = ctx == null ? 0.0D : ctx.value;
        int cx = x + w / 2;
        int cy = y + h / 2 + (widget.title == null || widget.title.isEmpty() ? 1 : 3);
        int radius = Math.max(9, Math.min(w, h) / 2 - 6);
        double ratio = widget.ratio(value);

        g.setColor(new Color(OFF, true));
        g.setStroke(new BasicStroke(3.0F, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(cx - radius, cy - radius, radius * 2.0D, radius * 2.0D,
                210.0D, -240.0D, Arc2D.OPEN));
        g.setColor(new Color(accent, true));
        if (ratio > 0.002D) {
            g.draw(new Arc2D.Double(cx - radius, cy - radius, radius * 2.0D, radius * 2.0D,
                    210.0D, -240.0D * ratio, Arc2D.OPEN));
        }
        g.setStroke(new BasicStroke(1.0F));

        // Needle at the tip of the lit arc.
        double angle = Math.toRadians(210.0D - 240.0D * ratio);
        g.setColor(new Color(TXT, true));
        g.drawLine(cx + (int) Math.round(Math.cos(angle) * (radius - 3)),
                cy - (int) Math.round(Math.sin(angle) * (radius - 3)),
                cx + (int) Math.round(Math.cos(angle) * (radius + 2)),
                cy - (int) Math.round(Math.sin(angle) * (radius + 2)));

        String number = number(widget, value);
        Font font = fit(g, number, radius * 2 - 4, 12, 7, true);
        drawText(g, number, cx - width(g, number, font) / 2, cy + 4, TXT, font);
        if (widget.title != null && !widget.title.isEmpty() && h >= 40) {
            drawText(g, clip(g, widget.title, w - 10, ScreenFonts.cjk(7)),
                    x + 6, y + 10, DIM, ScreenFonts.cjk(7));
        }
        String unit = widget.channel == null ? "" : widget.channel.unit();
        if (!unit.isEmpty() && h >= 46) {
            drawText(g, unit, cx - width(g, unit, ScreenFonts.cjk(6)) / 2, cy + 14, FAINT,
                    ScreenFonts.cjk(6));
        }
    }

    private static void paintLamp(Graphics2D g, int x, int y, int w, int h,
                                  Widget widget, Ctx ctx, int accent) {
        double value = ctx == null ? 0.0D : ctx.value;
        boolean on = value >= 0.5D;
        int lamp = Math.max(5, Math.min(8, h - 8));
        int lx = x + 6;
        int ly = y + (h - lamp) / 2;
        int color = !on ? OFF : (widget.color == 2 ? CRIT : OK);
        g.setColor(new Color(color, true));
        g.fillRect(lx, ly, lamp, lamp);
        g.setColor(new Color(on ? (widget.color == 2 ? CRIT : OK) : LINE, true));
        g.drawRect(lx, ly, lamp, lamp);

        String title = widget.title == null ? "" : widget.title;
        String state = widget.channel != null && widget.channel.isBoolean()
                ? (on ? "\u662f" : "\u5426") : (on ? "ON" : "OFF");
        int stateW = width(g, state, ScreenFonts.mono(7)) + 4;
        drawText(g, clip(g, title, w - lamp - stateW - 18, ScreenFonts.cjk(7)),
                lx + lamp + 4, y + h / 2 + 4, on ? TXT : DIM, ScreenFonts.cjk(7));
        drawText(g, state, x + w - 5 - width(g, state, ScreenFonts.mono(7)),
                y + h / 2 + 4, on ? accent : FAINT, ScreenFonts.mono(7));
    }

    private static void paintLabel(Graphics2D g, int x, int y, int w, int h, Widget widget, int accent) {
        String text = widget.text == null ? "" : widget.text;
        if (text.isEmpty()) {
            if (widget.title != null && !widget.title.isEmpty()) {
                drawText(g, clip(g, widget.title, w - 10, ScreenFonts.cjk(8)), x + 6, y + h / 2 + 4,
                        TXT, ScreenFonts.cjk(8));
            }
            return;
        }
        // Two lines when there is room: the first reads as a heading.
        Font font = ScreenFonts.cjk(8);
        String[] lines = wrap(g, text, w - 10, font, h >= 24 ? 2 : 1);
        int lineH = 10;
        int total = lines.length * lineH;
        int baseline = y + (h - total) / 2 + 9;
        for (int i = 0; i < lines.length; i++) {
            drawText(g, lines[i], x + 6, baseline + i * lineH, i == 0 ? TXT : DIM, font);
        }
        g.setColor(new Color(accent, true));
        g.fillRect(x + 6, y + h - 4, Math.min(12, w - 12), 1);
    }

    private static void paintTrend(Graphics2D g, int x, int y, int w, int h,
                                   Widget widget, Ctx ctx, int accent) {
        double value = ctx == null ? 0.0D : ctx.value;
        String title = widget.title == null ? "" : widget.title;
        String number = number(widget, value);
        if (h >= 20) {
            drawText(g, clip(g, title, w - 46, ScreenFonts.cjk(7)), x + 6, y + 10, DIM,
                    ScreenFonts.cjk(7));
            drawText(g, number, x + w - 5 - width(g, number, ScreenFonts.mono(7)), y + 10,
                    accent, ScreenFonts.mono(7));
        }
        int px = x + 6;
        int py = y + (h >= 20 ? 13 : 4);
        int pw = w - 12;
        int ph = h - (h >= 20 ? 17 : 8);
        if (pw < 6 || ph < 4) {
            return;
        }
        g.setColor(new Color(0x14FFFFFF, true));
        for (int i = 1; i < 3; i++) {
            g.fillRect(px, py + ph * i / 3, pw, 1);
        }
        int count = ctx == null ? 0 : ctx.trendCount;
        double[] trend = ctx == null ? null : ctx.trend;
        if (count < 2 || trend == null) {
            drawText(g, "\u91c7\u6837\u4e2d", px + 2, py + ph / 2 + 3, FAINT, ScreenFonts.cjk(6));
            return;
        }
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            lo = Math.min(lo, trend[i]);
            hi = Math.max(hi, trend[i]);
        }
        double span = Math.max(1.0E-6D, hi - lo);
        int lastX = px;
        int lastY = py + ph;
        g.setColor(new Color(accent, true));
        g.setStroke(new BasicStroke(1.2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 1; i < count; i++) {
            int x0 = px + pw * (i - 1) / Math.max(1, count - 1);
            int x1 = px + pw * i / Math.max(1, count - 1);
            int y0 = py + ph - (int) Math.round((trend[i - 1] - lo) / span * ph);
            int y1 = py + ph - (int) Math.round((trend[i] - lo) / span * ph);
            g.drawLine(x0, y0, x1, y1);
            lastX = x1;
            lastY = y1;
        }
        g.setStroke(new BasicStroke(1.0F));
        g.setColor(new Color(TXT, true));
        g.fillRect(Math.min(px + pw - 2, lastX) - 1, lastY - 1, 3, 3);
    }

    private static void paintBanner(Graphics2D g, int x, int y, int w, int h, Ctx ctx) {
        int level = ctx == null ? 0 : ctx.level;
        String text = ctx == null || ctx.banner == null ? "" : ctx.banner;
        boolean active = ctx != null && ctx.active && !text.isEmpty();
        int color;
        int fill;
        String shown;
        if (!active) {
            color = OK;
            fill = 0xFF101A10;
            shown = "\u7cfb\u7edf\u6b63\u5e38";
        } else if (level >= 2) {
            color = CRIT;
            fill = 0xFF24100B;
            shown = "!! " + text;
        } else {
            color = WARN;
            fill = 0xFF201A0C;
            shown = "! " + text;
        }
        g.setColor(new Color(fill, true));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(color, true));
        g.setStroke(new BasicStroke(1.0F));
        g.drawRect(x, y, w - 1, h - 1);
        g.fillRect(x, y, 3, h);
        Font font = ScreenFonts.cjk(8);
        drawText(g, clip(g, shown, w - 14, font), x + 8, y + h / 2 + 4, active ? color : DIM, font);
    }

    // ------------------------------------------------------------- primitives

    private static void segBar(Graphics2D g, int x, int y, int w, int h, int cells,
                               double ratio, int color) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        int lit = (int) Math.round(cells * clamped);
        int cellW = Math.max(1, (w - (cells - 1)) / cells);
        for (int i = 0; i < cells; i++) {
            g.setColor(new Color(i < lit ? color : OFF, true));
            g.fillRect(x + i * (cellW + 1), y, cellW, h);
        }
    }

    private static void drawText(Graphics2D g, String text, int x, int y, int argb, Font font) {
        if (text == null || text.isEmpty()) {
            return;
        }
        g.setFont(font);
        g.setColor(new Color(argb, true));
        g.drawString(text, x, y);
    }

    private static int width(Graphics2D g, String text, Font font) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return g.getFontMetrics(font).stringWidth(text);
    }

    private static int ascender(Graphics2D g, Font font) {
        return g.getFontMetrics(font).getAscent();
    }

    /** Largest monospace size whose width fits, never above {@code maxSize}. */
    private static Font fit(Graphics2D g, String text, int maxWidth, int maxSize, int minSize, boolean bold) {
        FontMetrics probe;
        for (int size = Math.max(minSize, maxSize); size > minSize; size--) {
            Font font = bold ? ScreenFonts.monoBold(size) : ScreenFonts.mono(size);
            probe = g.getFontMetrics(font);
            if (probe.stringWidth(text) <= maxWidth) {
                return font;
            }
        }
        return bold ? ScreenFonts.monoBold(minSize) : ScreenFonts.mono(minSize);
    }

    private static String clip(Graphics2D g, String text, int maxWidth, Font font) {
        if (text == null) {
            return "";
        }
        FontMetrics metrics = g.getFontMetrics(font);
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String dots = "..";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end) + dots) > maxWidth) {
            end--;
        }
        return text.substring(0, Math.max(0, end)) + dots;
    }

    /** Greedy wrap by character - correct for Han text, adequate for Latin. */
    private static String[] wrap(Graphics2D g, String text, int maxWidth, Font font, int maxLines) {
        FontMetrics metrics = g.getFontMetrics(font);
        java.util.List<String> lines = new java.util.ArrayList<String>();
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                continue;
            }
            if (metrics.stringWidth(line.toString() + ch) > maxWidth && line.length() > 0) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(ch);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        while (lines.size() > maxLines) {
            lines.remove(lines.size() - 1);
            if (!lines.isEmpty()) {
                String last = lines.get(lines.size() - 1);
                lines.set(lines.size() - 1, last.endsWith("..") ? last : last + "..");
            }
        }
        return lines.toArray(new String[0]);
    }

    /** Number as the widget wants it: decimals honoured, big values shortened. */
    public static String number(Widget widget, double value) {
        Channel channel = widget == null ? null : widget.channel;
        if (channel != null && channel.isBoolean()) {
            return value >= 0.5D ? "\u662f" : "\u5426";
        }
        if (Math.abs(value) >= 100000.0D) {
            return String.format(Locale.ROOT, "%.2fM", value / 1000000.0D);
        }
        if (Math.abs(value) >= 10000.0D) {
            return String.format(Locale.ROOT, "%.1fk", value / 1000.0D);
        }
        return String.format(Locale.ROOT, "%." + Math.max(0, Math.min(3, widget.decimals)) + "f", value);
    }
}

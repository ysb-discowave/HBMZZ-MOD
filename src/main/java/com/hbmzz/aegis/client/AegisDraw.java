/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis.client;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-level drawing helpers shared by the terminal's pages. All coordinates are
 * logical canvas pixels; the backing bitmap is created at a higher resolution
 * and scaled once, so callers never deal with the supersampling factor.
 */
final class AegisDraw {

    private final Graphics2D g;
    private final int width;
    private final int height;

    AegisDraw(BufferedImage image, int width, int height) {
        this.g = image.createGraphics();
        this.width = width;
        this.height = height;
        this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    Graphics2D graphics() {
        return this.g;
    }

    int w() {
        return this.width;
    }

    int h() {
        return this.height;
    }

    void dispose() {
        this.g.dispose();
    }

    void rect(int x, int y, int w, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.fillRect(x, y, w, h);
    }

    void frame(int x, int y, int w, int h, int argb, float thickness) {
        this.g.setColor(new Color(argb, true));
        Stroke old = this.g.getStroke();
        this.g.setStroke(new BasicStroke(thickness));
        this.g.drawRect(x, y, w, h);
        this.g.setStroke(old);
    }

    void line(int x1, int y1, int x2, int y2, int argb, float thickness) {
        this.g.setColor(new Color(argb, true));
        Stroke old = this.g.getStroke();
        this.g.setStroke(new BasicStroke(thickness));
        this.g.drawLine(x1, y1, x2, y2);
        this.g.setStroke(old);
    }

    void circle(int cx, int cy, int radius, int argb, boolean filled) {
        this.g.setColor(new Color(argb, true));
        int d = radius * 2;
        if (filled) {
            this.g.fillOval(cx - radius, cy - radius, d, d);
        } else {
            this.g.drawOval(cx - radius, cy - radius, d, d);
        }
    }

    int text(String value, int x, int y, int size, int argb, boolean bold) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        Font font = AegisFont.get(value, size, bold);
        this.g.setFont(font);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(value, x, y);
        return measure(value, size, bold);
    }

    int textRight(String value, int right, int y, int size, int argb, boolean bold) {
        int w = measure(value, size, bold);
        text(value, right - w, y, size, argb, bold);
        return w;
    }

    int textCenter(String value, int centerX, int y, int size, int argb, boolean bold) {
        int w = measure(value, size, bold);
        text(value, centerX - w / 2, y, size, argb, bold);
        return w;
    }

    int measure(String value, int size, boolean bold) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        Font font = AegisFont.get(value, size, bold);
        FontMetrics fm = this.g.getFontMetrics(font);
        return fm.stringWidth(value);
    }

    String fit(String value, int size, boolean bold, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (measure(value, size, bold) <= maxWidth) {
            return value;
        }
        String out = value;
        while (out.length() > 1 && measure(out + "\u2026", size, bold) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "\u2026";
    }

    /** Horizontal fill bar with track, coloured fill and an optional marker. */
    void bar(int x, int y, int w, int h, double fraction, int fill, double markerFraction, int marker) {
        rect(x, y, w, h, AegisPalette.BAR_TRACK);
        frame(x, y, w, h, AegisPalette.FRAME_SOFT, 1.0F);
        int inner = Math.max(0, w - 2);
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * inner);
        if (filled > 0) {
            rect(x + 1, y + 1, filled, h - 2, fill);
        }
        if (markerFraction >= 0.0D && markerFraction <= 1.0D) {
            int mx = x + 1 + (int) Math.round(markerFraction * inner);
            line(mx, y - 1, mx, y + h + 1, marker, 1.5F);
        }
    }

    /** A row of tick marks, used along gauge scales. */
    void ticks(int x, int y, int w, int count, int argb) {
        for (int i = 0; i <= count; i++) {
            int tx = x + (int) Math.round((double) i / count * w);
            line(tx, y, tx, y + (i % 5 == 0 ? 7 : 4), argb, 1.0F);
        }
    }

    /** Polyline over a float ring buffer. Values are 0..1, oldest first. */
    void spark(float[] values, int count, int x, int y, int w, int h, int colour) {
        if (count < 2) {
            return;
        }
        int stride = Math.max(1, count / Math.max(1, w));
        List<int[]> points = new ArrayList<int[]>();
        for (int i = 0; i < count; i += stride) {
            int px = x + (int) Math.round((double) i / (count - 1) * w);
            float v = Math.max(0.0F, Math.min(1.0F, values[i]));
            int py = y + h - (int) Math.round(v * h);
            points.add(new int[]{px, py});
        }
        for (int i = 1; i < points.size(); i++) {
            int[] a = points.get(i - 1);
            int[] b = points.get(i);
            line(a[0], a[1], b[0], b[1], colour, 1.6F);
        }
    }

    /** Subtle scanline overlay to make a flat panel read as a lit display. */
    void scanlines(int argb, int step) {
        for (int y = 0; y < this.height; y += step) {
            line(0, y, this.width, y, argb, 1.0F);
        }
    }

    // ------------------------------------------------------------- fx primitives

    /** Vertical linear gradient rectangle (top -> bottom). */
    void gradient(int x, int y, int w, int h, int topArgb, int bottomArgb) {
        if (w <= 0 || h <= 0) {
            return;
        }
        Paint old = this.g.getPaint();
        this.g.setPaint(new GradientPaint(x, y, new Color(topArgb, true),
                x, y + h, new Color(bottomArgb, true)));
        this.g.fillRect(x, y, w, h);
        this.g.setPaint(old);
    }

    /** Soft radial halo centred on (cx,cy), fading to fully transparent at r. */
    void radialGlow(int cx, int cy, float r, int coreArgb) {
        if (r <= 0.0F) {
            return;
        }
        Color core = new Color(coreArgb, true);
        float a = core.getAlpha() / 255.0F;
        if (a <= 0.0F) {
            return;
        }
        Color edge = new Color(core.getRed(), core.getGreen(), core.getBlue(), 0);
        Paint old = this.g.getPaint();
        this.g.setPaint(new RadialGradientPaint(
                new Point2D.Float(cx, cy), r,
                new float[]{0.0F, 0.45F, 1.0F},
                new Color[]{core, new Color(core.getRed(), core.getGreen(), core.getBlue(),
                        (int) (a * 90.0F)), edge}));
        this.g.fillOval((int) (cx - r), (int) (cy - r), (int) (r * 2.0F), (int) (r * 2.0F));
        this.g.setPaint(old);
    }

    /**
     * Text with an additive glow: the same glyphs are stamped a few times at
     * low alpha around the core so bright text reads as an emissive display.
     */
    void glowText(String value, int x, int y, int size, int argb, boolean bold) {
        if (value == null || value.isEmpty()) {
            return;
        }
        Composite old = this.g.getComposite();
        this.g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.16F));
        int halo = (argb & 0xFFFFFF) | 0x66000000;
        text(value, x - 1, y, size, halo, bold);
        text(value, x + 1, y, size, halo, bold);
        text(value, x, y - 1, size, halo, bold);
        text(value, x, y + 1, size, halo, bold);
        this.g.setComposite(old);
        text(value, x, y, size, argb, bold);
    }

    /** Filled area under a sparkline, drawn beneath the polyline itself. */
    void areaSpark(float[] values, int count, int x, int y, int w, int h,
                   int lineColour, int fillTop, int fillBottom) {
        if (count < 2) {
            return;
        }
        int stride = Math.max(1, count / Math.max(1, w));
        List<int[]> pts = new ArrayList<int[]>();
        for (int i = 0; i < count; i += stride) {
            int px = x + (int) Math.round((double) i / (count - 1) * w);
            float v = Math.max(0.0F, Math.min(1.0F, values[i]));
            int py = y + h - (int) Math.round(v * h);
            pts.add(new int[]{px, py});
        }
        if (pts.isEmpty()) {
            return;
        }
        Paint old = this.g.getPaint();
        int[] xs = new int[pts.size() + 2];
        int[] ys = new int[pts.size() + 2];
        xs[0] = x; ys[0] = y + h;
        for (int i = 0; i < pts.size(); i++) {
            xs[i + 1] = pts.get(i)[0];
            ys[i + 1] = pts.get(i)[1];
        }
        xs[xs.length - 1] = x + w; ys[ys.length - 1] = y + h;
        this.g.setPaint(new GradientPaint(0, y, new Color(fillTop, true),
                0, y + h, new Color(fillBottom, true)));
        this.g.fillPolygon(xs, ys, xs.length);
        this.g.setPaint(old);
        for (int i = 1; i < pts.size(); i++) {
            int[] a = pts.get(i - 1);
            int[] b = pts.get(i);
            line(a[0], a[1], b[0], b[1], lineColour, 1.6F);
        }
    }

    /** Dashed line, handy for scan guides and grid rulers. */
    void dashedLine(int x1, int y1, int x2, int y2, int argb,
                    float thickness, float dash, float gap) {
        this.g.setColor(new Color(argb, true));
        Stroke old = this.g.getStroke();
        this.g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_BUTT,
                BasicStroke.JOIN_MITER, 10.0F, new float[]{dash, gap}, 0.0F));
        this.g.drawLine(x1, y1, x2, y2);
        this.g.setStroke(old);
    }

    /** Four HUD corner brackets around a rectangle. */
    void corners(int x, int y, int w, int h, int len, int argb, float thickness) {
        line(x, y, x + len, y, argb, thickness);
        line(x, y, x, y + len, argb, thickness);
        line(x + w - len, y, x + w, y, argb, thickness);
        line(x + w, y, x + w, y + len, argb, thickness);
        line(x, y + h - len, x, y + h, argb, thickness);
        line(x, y + h, x + len, y + h, argb, thickness);
        line(x + w - len, y + h, x + w, y + h, argb, thickness);
        line(x + w, y + h - len, x + w, y + h, argb, thickness);
    }

    /** Perspective-ish background grid; offset scrolls it for motion. */
    void grid(int x, int y, int w, int h, int step, int offset, int argb) {
        if (step <= 0) {
            return;
        }
        int startX = x + (offset % step);
        for (int gx = startX; gx < x + w; gx += step) {
            line(gx, y, gx, y + h, argb, 1.0F);
        }
        for (int gy = y; gy < y + h; gy += step) {
            line(x, gy, x + w, gy, argb, 1.0F);
        }
    }

    /** Ring arc from startDeg to endDeg (degrees, 0 = up, clockwise). */
    void arc(int cx, int cy, int radius, double startDeg, double endDeg,
             int argb, float thickness) {
        if (radius <= 0 || endDeg <= startDeg) {
            return;
        }
        this.g.setColor(new Color(argb, true));
        Stroke old = this.g.getStroke();
        this.g.setStroke(new BasicStroke(thickness, BasicStroke.CAP_ROUND,
                BasicStroke.JOIN_ROUND));
        int d = (int) (radius * 2.0F);
        this.g.drawArc(cx - (int) radius, cy - (int) radius, d, d,
                (int) startDeg - 90, (int) (endDeg - startDeg));
        this.g.setStroke(old);
    }
}

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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
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
}

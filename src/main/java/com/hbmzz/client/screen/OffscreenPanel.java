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

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * A panel painted with AWT and uploaded as one texture.
 *
 * <p>The same arrangement the DFC monitor uses, reduced to what the two new
 * screens actually need: an offscreen image, a graphics context, and a dirty
 * flag. What is deliberately <em>not</em> here is any OpenGL - uploading and
 * disposal live in {@code PanelTexture} - so a screen can be rendered to a PNG
 * and looked at without a game, which is the only practical way to check a
 * layout at this size.</p>
 *
 * <p>All coordinates below are logical panel pixels. The backing image is
 * {@code SS} times larger and the graphics context is scaled once at creation,
 * so every draw call stays in the same units as the layout.</p>
 */
public class OffscreenPanel {

    /** Logical width. A 1x1 wall panel is square, so the height is the same. */
    public static final int BASE = 256;

    /**
     * Supersampling factor for the backing image. Two, not three: these panels
     * are small and numerous, and a 768x768 ARGB image per screen is 2.4 MB of
     * heap for a device that is legible at 512.
     */
    public static final int SS = 2;

    protected BufferedImage image;
    protected Graphics2D g;
    protected int width;
    protected int height;
    private boolean dirty = true;

    public OffscreenPanel(int size) {
        resize(size, size);
    }

    protected final void resize(int w, int h) {
        this.width = Math.max(32, w);
        this.height = Math.max(32, h);
        this.image = new BufferedImage(this.width * SS, this.height * SS, BufferedImage.TYPE_INT_ARGB);
        this.g = this.image.createGraphics();
        this.g.scale(SS, SS);
        this.g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        this.g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        this.g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        this.dirty = true;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    /** The backing image. Only the offline preview and the texture uploader need it. */
    public BufferedImage image() {
        return this.image;
    }

    public boolean isDirty() {
        return this.dirty;
    }

    public void clearDirty() {
        this.dirty = false;
    }

    protected void markDirty() {
        this.dirty = true;
    }

    // ------------------------------------------------------------- primitives

    protected void rect(int x, int y, int w, int h, int argb) {
        this.g.setColor(new Color(argb, true));
        this.g.fillRect(x, y, w, h);
    }

    protected void frame(int x, int y, int w, int h, int argb, float thickness) {
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new java.awt.BasicStroke(thickness));
        this.g.drawRect(x, y, w, h);
    }

    protected void line(int x1, int y1, int x2, int y2, int argb, float thickness) {
        this.g.setColor(new Color(argb, true));
        this.g.setStroke(new java.awt.BasicStroke(thickness));
        this.g.drawLine(x1, y1, x2, y2);
    }

    protected int text(String value, int x, int y, int size, int argb, boolean bold) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        Font font = bold ? ScreenFonts.textBold(value, size) : ScreenFonts.text(value, size);
        this.g.setFont(font);
        this.g.setColor(new Color(argb, true));
        this.g.drawString(value, x, y);
        return this.g.getFontMetrics(font).stringWidth(value);
    }

    protected int textRight(String value, int right, int y, int size, int argb, boolean bold) {
        int w = measure(value, size, bold);
        text(value, right - w, y, size, argb, bold);
        return w;
    }

    protected int textCenter(String value, int centerX, int y, int size, int argb, boolean bold) {
        int w = measure(value, size, bold);
        text(value, centerX - w / 2, y, size, argb, bold);
        return w;
    }

    /**
     * Draws text at the largest size in {@code sizes} that fits, clipping with an
     * ellipsis when even the smallest does not.
     *
     * <p>The code words have very different widths - {@code RED} is three
     * characters, {@code COUNTDOWN} is nine - and the caption beside them has to
     * follow whichever one is showing. Sizing the caption once and hoping was
     * exactly how DELTA's English subtitle ended up running off the panel.</p>
     */
    protected void textFit(String value, int x, int y, int[] sizes, int argb, boolean bold, int maxWidth) {
        if (value == null || value.isEmpty()) {
            return;
        }
        for (int size : sizes) {
            if (measure(value, size, bold) <= maxWidth) {
                text(value, x, y, size, argb, bold);
                return;
            }
        }
        int smallest = sizes[sizes.length - 1];
        text(clip(value, smallest, bold, maxWidth), x, y, smallest, argb, bold);
    }

    /** Shortens until it fits, appending an ellipsis. */
    protected String clip(String value, int size, boolean bold, int maxWidth) {
        String out = value;
        while (out.length() > 1 && measure(out + "\u2026", size, bold) > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "\u2026";
    }

    protected int measure(String value, int size, boolean bold) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        Font font = bold ? ScreenFonts.textBold(value, size) : ScreenFonts.text(value, size);
        FontMetrics metrics = this.g.getFontMetrics(font);
        return metrics.stringWidth(value);
    }

    /**
     * Greedy word wrap that also breaks long unspaced runs.
     *
     * <p>Chinese has no spaces, so a wrap that only breaks on whitespace would
     * put a whole sentence on one line and run it off the panel. Breaking
     * per character when a "word" does not fit is what makes a mixed
     * Chinese/ASCII caption behave.</p>
     */
    protected List<String> wrap(String value, int size, boolean bold, int maxWidth) {
        List<String> lines = new ArrayList<String>();
        if (value == null || value.isEmpty()) {
            return lines;
        }
        Font font = bold ? ScreenFonts.textBold(value, size) : ScreenFonts.text(value, size);
        FontMetrics metrics = this.g.getFontMetrics(font);
        String rest = value;
        while (!rest.isEmpty()) {
            int fit = rest.length();
            while (fit > 1 && metrics.stringWidth(rest.substring(0, fit)) > maxWidth) {
                fit--;
            }
            if (fit < rest.length()) {
                // Prefer a space break when one is close to the edge.
                int space = rest.lastIndexOf(' ', fit);
                if (space > fit / 2) {
                    fit = space;
                }
            }
            lines.add(rest.substring(0, fit).trim());
            rest = rest.substring(fit).trim();
        }
        return lines;
    }

    /** A soft grid: the thing that makes a dark panel read as a screen. */
    protected void grid(int argb, int step) {
        for (int x = 0; x < this.width; x += step) {
            line(x, 0, x, this.height, argb, 1.0F);
        }
        for (int y = 0; y < this.height; y += step) {
            line(0, y, this.width, y, argb, 1.0F);
        }
    }

    /** Horizontal progress bar, used for the fuse countdown. */
    protected void bar(int x, int y, int w, int h, double fraction, int trackArgb, int fillArgb) {
        rect(x, y, w, h, trackArgb);
        int filled = (int) Math.round(Math.max(0.0D, Math.min(1.0D, fraction)) * (w - 2));
        if (filled > 0) {
            rect(x + 1, y + 1, filled, h - 2, fillArgb);
        }
    }

    /** Formats a tick count as {@code T+mm:ss}. */
    public static String clock(long ticks) {
        long seconds = Math.max(0L, ticks / 20L);
        return String.format(java.util.Locale.ROOT, "T+%02d:%02d", seconds / 60L, seconds % 60L);
    }

    /** Formats a tick count as a one-decimal second count. */
    public static String seconds(long ticks) {
        return String.format(java.util.Locale.ROOT, "%.1fs", Math.max(0L, ticks) / 20.0D);
    }
}

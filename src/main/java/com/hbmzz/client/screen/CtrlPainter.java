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

import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Paints one control.
 *
 * <p>One painter for both places the panel appears - the window and, later, the
 * block face - because a control that looks like a knob in the editor and like a
 * rounded square in the world is a control the operator cannot trust. Every
 * drawing decision is made from the widget's type and its current value, never
 * from where it is being drawn.</p>
 *
 * <p>The look is deliberately physical: a plate, a bezel, a lens with a hot
 * centre. A flat rectangle with text on it would be easier, but a panel is read
 * at a glance from across a reactor hall, and shape carries that better than
 * wording - you can tell a switch from a lamp from three blocks away.</p>
 */
public final class CtrlPainter {

    // ---- industrial palette ------------------------------------------------
    static final Color FACE = new Color(0x1B2027);
    static final Color FACE_HI = new Color(0x262D36);
    static final Color BEZEL = new Color(0x3C454F);
    static final Color BEZEL_DARK = new Color(0x11151A);
    static final Color TEXT = new Color(0xC7D0DA);
    static final Color TEXT_DIM = new Color(0x78848F);
    static final Color GREEN = new Color(0x74E88C);
    static final Color AMBER = new Color(0xF2B23E);
    static final Color RED = new Color(0xE8584C);
    static final Color CYAN = new Color(0x6FD3FF);

    /** Accent colours a control may be painted with, index = widget.color. */
    static final Color[] ACCENTS = {GREEN, AMBER, RED, CYAN};

    private CtrlPainter() {
    }

    static Color accent(int index) {
        return ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, index))];
    }

    /**
     * Draws {@code widget} into the pixel rectangle given.
     *
     * @param selected true when the editor has it selected
     * @param hovered  true when the cursor is over it
     */
    public static void paint(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h,
                             boolean selected, boolean hovered) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        try {
            switch (widget.type) {
                case LABEL:
                    paintLabel(g2, widget, x, y, w, h);
                    break;
                case LAMP:
                case LAMP_RGB:
                    paintLamp(g2, widget, x, y, w, h);
                    break;
                case SEG7:
                    paintSeg7(g2, widget, x, y, w, h);
                    break;
                case TEXT:
                    paintText(g2, widget, x, y, w, h);
                    break;
                case DIAL:
                    paintDial(g2, widget, x, y, w, h);
                    break;
                case BAR:
                    paintBar(g2, widget, x, y, w, h);
                    break;
                case BUTTON:
                    paintButton(g2, widget, x, y, w, h);
                    break;
                case ESTOP:
                    paintEstop(g2, widget, x, y, w, h);
                    break;
                case SWITCH:
                    paintSwitch(g2, widget, x, y, w, h);
                    break;
                case ROTARY:
                case KNOB:
                    paintKnob(g2, widget, x, y, w, h, widget.type == CtrlType.ROTARY);
                    break;
                case SLIDER:
                    paintSlider(g2, widget, x, y, w, h);
                    break;
                default:
                    paintPlate(g2, x, y, w, h, false);
                    break;
            }
            if (selected) {
                g2.setColor(new Color(0x6FD3FF));
                g2.setStroke(new BasicStroke(1.5F));
                g2.draw(new RoundRectangle2D.Float(x + 0.75F, y + 0.75F, w - 1.5F, h - 1.5F, 4, 4));
            } else if (hovered) {
                g2.setColor(new Color(0xFFFFFF33, true));
                g2.setStroke(new BasicStroke(1F));
                g2.draw(new RoundRectangle2D.Float(x + 0.5F, y + 0.5F, w - 1F, h - 1F, 4, 4));
            }
        } finally {
            g2.dispose();
        }
    }

    // ---------------------------------------------------------------- shared

    /** The recessed plate every control except a bare label sits on. */
    private static void paintPlate(Graphics2D g, int x, int y, int w, int h, boolean lit) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x, y, w, h, 4, 4);
        g.setPaint(new GradientPaint(x, y, lit ? FACE_HI : FACE, x, y + h, BEZEL_DARK));
        g.fill(shape);
        g.setColor(BEZEL);
        g.setStroke(new BasicStroke(1F));
        g.draw(new RoundRectangle2D.Float(x + 0.5F, y + 0.5F, w - 1F, h - 1F, 4, 4));
        // Top highlight: one pixel of uplift, which is what separates a control
        // from a hole in the panel.
        g.setColor(new Color(0xFFFFFF18, true));
        g.drawLine(x + 2, y + 1, x + w - 3, y + 1);
    }

    static void text(Graphics2D g, String value, float x, float y, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        g.drawString(value, x, y);
    }

    static void centered(Graphics2D g, String value, int cx, int baseline, Font font, Color color) {
        g.setFont(font);
        g.setColor(color);
        FontMetrics metrics = g.getFontMetrics();
        g.drawString(value, cx - metrics.stringWidth(value) / 2F, baseline);
    }

    /** Caption in the top-left of a control, when the box is tall enough. */
    private static void caption(Graphics2D g, CtrlWidget widget, int x, int y, int h) {
        if (h < 22 || widget.label.isEmpty()) {
            return;
        }
        Font font = ScreenFonts.cjk(9);
        g.setFont(font);
        g.setColor(TEXT_DIM);
        String value = widget.label;
        FontMetrics metrics = g.getFontMetrics();
        while (metrics.stringWidth(value) > 200 && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        g.drawString(value, x + 5, y + 12);
    }

    // --------------------------------------------------------------- readouts

    private static void paintLabel(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        Font font = ScreenFonts.cjk(Math.max(9, Math.min(14, h - 6)));
        g.setFont(font);
        g.setColor(TEXT);
        g.drawString(widget.label, x + 2, y + h / 2F + font.getSize() / 3F);
    }

    private static void paintLamp(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        boolean on = widget.value >= 0.5D;
        Color lit = widget.type == CtrlType.LAMP_RGB ? rgbFor(widget.value) : accent(widget.color);
        int cx = x + w / 2;
        int cy = y + h / 2 + (h >= 26 ? 3 : 0);
        int r = Math.min(w, h) / 2 - 4;
        if (r < 3) {
            r = 3;
        }
        if (on) {
            // Halo first, so the lens sits inside its own glow.
            for (int i = 3; i >= 1; i--) {
                g.setColor(new Color(lit.getRed(), lit.getGreen(), lit.getBlue(), 26 * i));
                g.fill(new Ellipse2D.Float(cx - r - i * 2, cy - r - i * 2, (r + i * 2) * 2, (r + i * 2) * 2));
            }
        }
        g.setColor(on ? lit : new Color(0x2A3138));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.setColor(on ? lit.brighter() : new Color(0x3C454F));
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        if (on) {
            g.setColor(new Color(255, 255, 255, 170));
            g.fill(new Ellipse2D.Float(cx - r * 0.45F, cy - r * 0.55F, r * 0.5F, r * 0.4F));
        }
        caption(g, widget, x, y, h);
    }

    private static Color rgbFor(double value) {
        if (value < 33.0D) {
            return GREEN;
        }
        return value < 66.0D ? AMBER : RED;
    }

    private static void paintSeg7(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        caption(g, widget, x, y, h);
        String value = widget.displayText();
        int digitH = (int) (h * 0.52F);
        int digitW = (int) (digitH * 0.56F);
        int gap = Math.max(2, digitW / 6);
        int textW = value.length() * digitW + Math.max(0, value.length() - 1) * gap;
        int startX = x + (w - textW) / 2;
        int top = y + (h - digitH) / 2 + (h >= 26 ? 3 : 0);
        Color lit = accent(widget.color);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            int dx = startX + i * (digitW + gap);
            if (c >= '0' && c <= '9') {
                drawDigit(g, dx, top, digitW, digitH, c - '0', lit);
            } else if (c == '-' || c == '.') {
                g.setColor(lit);
                g.fillRect(dx, top + digitH - Math.max(2, digitH / 8), Math.max(2, digitW / 2), Math.max(2, digitH / 8));
            } else {
                g.setColor(lit);
                g.setFont(ScreenFonts.mono(Math.max(9, digitH - 4)));
                g.drawString(String.valueOf(c), dx, top + digitH - 2);
            }
        }
    }

    /**
     * Seven-segment digit. Segments are laid out on a 3x5 lattice:
     * <pre>
     *    aaa
     *   f   b
     *    ggg
     *   e   c
     *    ddd
     * </pre>
     */
    private static void drawDigit(Graphics2D g, int x, int y, int w, int h, int digit, Color lit) {
        boolean[] seg = SEGMENTS[Math.max(0, Math.min(9, digit))];
        int t = Math.max(1, Math.min(w, h) / 7);
        g.setColor(lit);
        if (seg[0]) {
            g.fillRect(x + t, y, w - t * 2, t);
        }
        if (seg[1]) {
            g.fillRect(x + w - t, y + t, t, h / 2 - t);
        }
        if (seg[2]) {
            g.fillRect(x + w - t, y + h / 2 + t / 2, t, h / 2 - t);
        }
        if (seg[3]) {
            g.fillRect(x + t, y + h - t, w - t * 2, t);
        }
        if (seg[4]) {
            g.fillRect(x, y + h / 2 + t / 2, t, h / 2 - t);
        }
        if (seg[5]) {
            g.fillRect(x, y + t, t, h / 2 - t);
        }
        if (seg[6]) {
            g.fillRect(x + t, y + h / 2 - t / 2, w - t * 2, t);
        }
    }

    /** a b c d e f g for each digit. */
    private static final boolean[][] SEGMENTS = {
            {true, true, true, true, true, true, false},
            {false, true, true, false, false, false, false},
            {true, true, false, true, true, false, true},
            {true, true, true, true, false, false, true},
            {false, true, true, false, false, true, true},
            {true, false, true, true, false, true, true},
            {true, false, true, true, true, true, true},
            {true, true, true, false, false, false, false},
            {true, true, true, true, true, true, true},
            {true, true, true, true, false, true, true}
    };

    private static void paintText(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        caption(g, widget, x, y, h);
        String value = widget.displayText();
        Font font = ScreenFonts.cjk(Math.max(10, Math.min(16, h - 10)));
        g.setFont(font);
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(value) > w - 12) {
            while (value.length() > 1 && metrics.stringWidth(value + "…") > w - 12) {
                value = value.substring(0, value.length() - 1);
            }
            value = value + "…";
        }
        g.setColor(accent(widget.color));
        g.drawString(value, x + 6, y + h / 2F + font.getSize() / 3F + (h >= 26 ? 3 : 0));
    }

    private static void paintDial(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        caption(g, widget, x, y, h);
        int cx = x + w / 2;
        int cy = y + h / 2 + (h >= 26 ? 3 : 0);
        int r = Math.min(w, h) / 2 - 4;
        if (r < 4) {
            r = 4;
        }
        g.setColor(new Color(0x0F1318));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.setColor(accent(widget.color));
        g.setStroke(new BasicStroke(1.4F));
        g.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, 225, -270, Arc2D.OPEN));
        // Ticks at every tenth of the scale.
        for (int i = 0; i <= 10; i++) {
            double angle = Math.toRadians(225 - i * 27);
            int inner = i % 5 == 0 ? r - 5 : r - 3;
            g.setColor(i % 5 == 0 ? TEXT : TEXT_DIM);
            g.drawLine(cx + (int) (Math.cos(angle) * inner), cy - (int) (Math.sin(angle) * inner),
                    cx + (int) (Math.cos(angle) * r), cy - (int) (Math.sin(angle) * r));
        }
        double angle = Math.toRadians(225 - widget.ratio() * 270);
        g.setColor(RED);
        g.setStroke(new BasicStroke(1.8F));
        g.drawLine(cx, cy, cx + (int) (Math.cos(angle) * (r - 3)), cy - (int) (Math.sin(angle) * (r - 3)));
        g.fill(new Ellipse2D.Float(cx - 2, cy - 2, 4, 4));
    }

    private static void paintBar(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        caption(g, widget, x, y, h);
        int inset = 5;
        int trackY = y + (h >= 26 ? h / 2 + 3 : h / 2) - 3;
        int trackH = 6;
        int trackW = w - inset * 2;
        g.setColor(new Color(0x0F1318));
        g.fill(new RoundRectangle2D.Float(x + inset, trackY, trackW, trackH, 3, 3));
        int fill = (int) (trackW * widget.ratio());
        g.setColor(accent(widget.color));
        if (fill > 0) {
            g.fill(new RoundRectangle2D.Float(x + inset, trackY, Math.max(3, fill), trackH, 3, 3));
        }
        String value = widget.displayText();
        g.setFont(ScreenFonts.mono(9));
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(TEXT_DIM);
        g.drawString(value, x + w - inset - metrics.stringWidth(value), trackY - 3);
    }

    // ------------------------------------------------------ operator controls

    private static void paintButton(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        boolean down = widget.value >= 0.5D;
        RoundRectangle2D cap = new RoundRectangle2D.Float(x + 3, y + 3 + (down ? 1 : 0), w - 6, h - 6, 4, 4);
        g.setPaint(new GradientPaint(x, y, down ? new Color(0x2C333B) : BEZEL,
                x, y + h, down ? new Color(0x181D23) : new Color(0x262D36)));
        g.fill(cap);
        g.setColor(BEZEL_DARK);
        g.setStroke(new BasicStroke(1F));
        g.draw(cap);
        g.setFont(ScreenFonts.cjk(Math.max(9, Math.min(13, h - 8))));
        FontMetrics metrics = g.getFontMetrics();
        String value = widget.label;
        while (metrics.stringWidth(value) > w - 10 && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        g.setColor(down ? accent(widget.color) : TEXT);
        g.drawString(value, x + (w - metrics.stringWidth(value)) / 2F, y + h / 2F + metrics.getAscent() / 3F);
    }

    private static void paintEstop(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        boolean pressed = widget.latched || widget.value >= 0.5D;
        int cx = x + w / 2;
        int cy = y + h / 2;
        int r = Math.min(w, h) / 2 - 3;
        if (r < 5) {
            r = 5;
        }
        // Yellow hazard ring.
        g.setColor(pressed ? new Color(0x6B5A18) : new Color(0xD8B32A));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.setColor(new Color(0x0F1318));
        g.fill(new Ellipse2D.Float(cx - r + 2, cy - r + 2, (r - 2) * 2, (r - 2) * 2));
        // Mushroom head, darker and slightly smaller when pushed in.
        int mr = pressed ? r - 5 : r - 3;
        g.setPaint(new GradientPaint(cx - mr, cy - mr, pressed ? new Color(0x7A1A14) : new Color(0xE24A3C),
                cx + mr, cy + mr, pressed ? new Color(0x4A0F0B) : new Color(0x8E241C)));
        g.fill(new Ellipse2D.Float(cx - mr, cy - mr, mr * 2, mr * 2));
        g.setColor(new Color(0xFFFFFF33, true));
        g.fill(new Ellipse2D.Float(cx - mr * 0.6F, cy - mr * 0.65F, mr * 0.5F, mr * 0.35F));
        if (h >= 26) {
            g.setFont(ScreenFonts.cjk(8));
            g.setColor(TEXT_DIM);
            String label = widget.label;
            FontMetrics metrics = g.getFontMetrics();
            if (metrics.stringWidth(label) <= w) {
                g.drawString(label, x + (w - metrics.stringWidth(label)) / 2F, y + h - 2);
            }
        }
    }

    private static void paintSwitch(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        boolean on = widget.value >= 0.5D;
        int cx = x + w / 2;
        int top = y + (h >= 26 ? 14 : 4);
        int bottom = y + h - 4;
        int mid = (top + bottom) / 2;
        int slotW = Math.max(4, w / 4);
        g.setColor(new Color(0x0F1318));
        g.fill(new RoundRectangle2D.Float(cx - slotW / 2F, top, slotW, bottom - top, slotW, slotW));
        // Lever: up for on, down for off - the direction the label sits.
        int knobH = Math.max(6, (bottom - top) / 2);
        int knobY = on ? top : bottom - knobH;
        g.setPaint(new GradientPaint(cx, knobY, on ? accent(widget.color).darker() : BEZEL,
                cx, knobY + knobH, BEZEL_DARK));
        g.fill(new RoundRectangle2D.Float(cx - slotW / 2F - 1, knobY, slotW + 2, knobH, 3, 3));
        g.setColor(on ? accent(widget.color) : BEZEL);
        g.draw(new RoundRectangle2D.Float(cx - slotW / 2F - 1, knobY, slotW + 2, knobH, 3, 3));
        if (h >= 26) {
            g.setFont(ScreenFonts.cjk(8));
            g.setColor(TEXT_DIM);
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(widget.label, x + (w - metrics.stringWidth(widget.label)) / 2F, y + 11);
        }
        // On/off marks beside the slot.
        g.setColor(on ? accent(widget.color) : new Color(0x39424C));
        g.fillRect(x + 3, top, 2, 3);
        g.setColor(!on ? accent(widget.color) : new Color(0x39424C));
        g.fillRect(x + 3, bottom - 3, 2, 3);
        // Keep the mid variable meaningful for the compiler's sake: the slot
        // ends are what matters, not its centre.
        if (mid < 0) {
            g.fillRect(0, 0, 0, 0);
        }
    }

    private static void paintKnob(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h, boolean detents) {
        paintPlate(g, x, y, w, h, false);
        int cx = x + w / 2;
        int cy = y + h / 2 + (h >= 26 ? 3 : 0);
        int r = Math.min(w, h) / 2 - 4;
        if (r < 4) {
            r = 4;
        }
        if (detents) {
            // A detent ring: one notch per position, so the knob reads as
            // "which of N" rather than "how far round".
            int steps = Math.max(2, widget.steps);
            for (int i = 0; i < steps; i++) {
                double angle = Math.toRadians(225 - i * 270.0D / (steps - 1));
                int inner = r - 4;
                g.setColor(widget.ratio() >= (double) i / (steps - 1) - 1.0E-3D ? accent(widget.color) : BEZEL);
                g.drawLine(cx + (int) (Math.cos(angle) * inner), cy - (int) (Math.sin(angle) * inner),
                        cx + (int) (Math.cos(angle) * r), cy - (int) (Math.sin(angle) * r));
            }
            r -= 3;
        }
        g.setPaint(new GradientPaint(cx - r, cy - r, new Color(0x39424C), cx + r, cy + r, new Color(0x14181D)));
        g.fill(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        g.setColor(BEZEL);
        g.setStroke(new BasicStroke(1F));
        g.draw(new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2));
        // Knurling: a few arcs around the rim, which is what makes a disc read
        // as something you grip.
        g.setColor(new Color(0xFFFFFF14, true));
        for (int i = 0; i < 8; i++) {
            double a0 = Math.toRadians(i * 45 + 6);
            double a1 = Math.toRadians(i * 45 + 32);
            Path2D.Double arc = new Path2D.Double();
            arc.append(new Arc2D.Double(cx - r + 2, cy - r + 2, (r - 2) * 2, (r - 2) * 2,
                    Math.toDegrees(a0), Math.toDegrees(a1 - a0), Arc2D.OPEN), false);
            g.draw(arc);
        }
        double angle = Math.toRadians(225 - widget.ratio() * 270);
        g.setColor(accent(widget.color));
        g.setStroke(new BasicStroke(2F, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine(cx, cy, cx + (int) (Math.cos(angle) * (r - 2)), cy - (int) (Math.sin(angle) * (r - 2)));
        if (h >= 26) {
            caption(g, widget, x, y, h);
        }
    }

    private static void paintSlider(Graphics2D g, CtrlWidget widget, int x, int y, int w, int h) {
        paintPlate(g, x, y, w, h, false);
        int cx = x + w / 2;
        int top = y + (h >= 26 ? 16 : 6);
        int bottom = y + h - 6;
        int trackW = Math.max(4, w / 3);
        g.setColor(new Color(0x0F1318));
        g.fill(new RoundRectangle2D.Float(cx - trackW / 2F, top, trackW, bottom - top, 3, 3));
        // Filled part below the handle, so the amount is visible at a glance.
        int handleH = 8;
        int travel = bottom - top - handleH;
        int handleY = bottom - handleH - (int) (travel * widget.ratio());
        g.setColor(new Color(accent(widget.color).getRed(), accent(widget.color).getGreen(),
                accent(widget.color).getBlue(), 90));
        g.fill(new RoundRectangle2D.Float(cx - trackW / 2F, handleY,
                trackW, bottom - handleY, 3, 3));
        g.setPaint(new GradientPaint(cx - w / 2F, handleY, new Color(0x4A5560),
                cx + w / 2F, handleY + handleH, new Color(0x22282F)));
        g.fill(new RoundRectangle2D.Float(cx - w / 2F + 2, handleY, w - 4, handleH, 3, 3));
        g.setColor(accent(widget.color));
        g.draw(new RoundRectangle2D.Float(cx - w / 2F + 2, handleY, w - 4, handleH, 3, 3));
        if (h >= 26) {
            caption(g, widget, x, y, h);
        }
    }
}

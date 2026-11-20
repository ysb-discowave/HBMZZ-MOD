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

/** Central ARGB palette so every element on the terminal shares one palette. */
public final class AegisPalette {

    public static final int CANVAS = 0xFF070C10;
    public static final int CANVAS_ALT = 0xFF0C151B;
    public static final int GRID = 0x14FFFFFF;
    public static final int FRAME = 0xFF1E3A46;
    public static final int FRAME_SOFT = 0xFF14262F;

    public static final int TEXT = 0xFFDCEBF2;
    public static final int TEXT_DIM = 0xFF7E99A6;
    public static final int TEXT_FAINT = 0xFF4A6472;

    public static final int CYAN = 0xFF39D6E8;
    public static final int CYAN_DARK = 0xFF12707E;
    public static final int GREEN = 0xFF5BE08A;
    public static final int AMBER = 0xFFF2B23C;
    public static final int RED = 0xFFFF5A56;
    public static final int VIOLET = 0xFFB08CFF;

    public static final int BAR_TRACK = 0xFF0A1116;
    public static final int SHADOW = 0xB0000000;
    public static final int GLASS = 0x0AFFFFFF;

    private AegisPalette() {
    }

    /** Colour for a heat fraction, using the configured warning thresholds. */
    public static int heat(double fraction) {
        if (fraction >= com.hbmzz.aegis.AegisConfig.dangerHeat) {
            return RED;
        }
        if (fraction >= com.hbmzz.aegis.AegisConfig.warnHeat) {
            return AMBER;
        }
        return CYAN;
    }

    /** Colour for a severity level: 0 info, 1 warn, 2 danger. */
    public static int severity(int level) {
        if (level >= 2) {
            return RED;
        }
        if (level == 1) {
            return AMBER;
        }
        return GREEN;
    }

    /** Blends two ARGB colours with t in 0..1. */
    public static int mix(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        int aa = ((a >>> 24) & 0xFF);
        return (aa << 24) | (r << 16) | (g << 8) | bl;
    }
}

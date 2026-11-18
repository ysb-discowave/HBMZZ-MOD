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

package com.hbmzz.client;

/**
 * Shared palette for the terminal, the block face and the HUD, so the three
 * views always look like the same piece of hardware.
 */
public final class MonitorPalette {

    public static final int BG = 0xF00A1210;
    public static final int BG_OUTER = 0xB0040A08;
    public static final int CARD = 0x50122A1E;
    public static final int CARD_EDGE = 0x66306B48;
    public static final int TRACK = 0xFF0C1A12;
    public static final int BORDER = 0xFF2E7A4A;
    public static final int BORDER_DIM = 0x552E7A4A;
    public static final int GRID = 0x1E2E7A4A;

    public static final int GREEN = 0xFF6BF29A;
    public static final int GREEN_DIM = 0xFF3C8B5C;
    public static final int MUTED = 0xFF5E8C71;
    public static final int TEXT = 0xFFD8F5E2;
    public static final int AMBER = 0xFFFFC24B;
    public static final int RED = 0xFFFF5A5A;
    public static final int CYAN = 0xFF5FD8E8;

    private MonitorPalette() {
    }

    public static int alpha(int color, float factor) {
        int a = (int) (((color >>> 24) & 0xFF) * Math.max(0.0F, Math.min(1.0F, factor)));
        return (a << 24) | (color & 0x00FFFFFF);
    }

    /** Mixes toward black to build cheap gradients without extra colours. */
    public static int shade(int color, float factor) {
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (color & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static int lerp(int from, int to, float t) {
        float k = Math.max(0.0F, Math.min(1.0F, t));
        int a = (int) (((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * k);
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * k);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * k);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * k);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}

/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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

package com.hbmzz.cecp.nodes.addon;

/**
 * Shortens a number with an SI prefix: {@code 1500 -> "1.5k"}.
 *
 * <p>Transcribed from {@code com.llib.math.SIPfx} in CE Addon (Leafia), which is
 * where the "SIPfx" node gets its text. Copied rather than linked because that
 * class ships in the addon, not in CE, and the node has to work without it.</p>
 */
public final class SIPfx {

    public static final String[] PREFIX = {"k", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q"};
    public static final String[] PREFIX_FULL = {
            "Kilo", "Mega", "Giga", "Terra", "Peta", "Exa", "Zetta", "Yotta", "Ronna", "Quetta"};

    private SIPfx() {
    }

    public static double round(double num, int decimals) {
        double pow = Math.pow(10.0D, decimals);
        return Math.floor(num * pow + 0.5D) / pow;
    }

    /** How many times 1000 divides into {@code x}, clamped to the prefix table. */
    public static byte exponent(double x) {
        return (byte) Math.min(Math.floor(Math.log(x) / Math.log(1000.0D)), 9.0D);
    }

    public static double scale(double x, double exponent) {
        return x / Math.pow(1000.0D, exponent);
    }

    public static String auto(double x, boolean full) {
        byte level = exponent(x);
        if (level <= 0) {
            return String.valueOf(x);
        }
        return String.valueOf(round(scale(x, level), 2)) + (full ? PREFIX_FULL : PREFIX)[level - 1];
    }

    public static String auto(double x) {
        return auto(x, false);
    }
}

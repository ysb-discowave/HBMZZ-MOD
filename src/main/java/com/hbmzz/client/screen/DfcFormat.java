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

import java.util.Locale;

/**
 * Number formatting for the monitor panel.
 *
 * <p>All of it lives here instead of being sprinkled through the drawing code
 * because the same value is printed on three surfaces (block face, terminal
 * preview, HUD) and they must never disagree. It also keeps the thresholds in
 * one place: "is this temperature dangerous" has exactly one answer.</p>
 */
public final class DfcFormat {

    /** Stabilization reads out of NTM as an unbounded accumulator; this is full scale. */
    public static final double STAB_FULL_SCALE = 10.0D;

    private DfcFormat() {
    }

    // ------------------------------------------------------------- temperature

    /** 812.50°C, or 12.4k°C once the value stops fitting. */
    public static String temperature(double celsius) {
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            return "--";
        }
        if (Math.abs(celsius) >= 10000.0D) {
            return String.format(Locale.ROOT, "%.2fk\u00b0C", celsius / 1000.0D);
        }
        return String.format(Locale.ROOT, "%.2f\u00b0C", celsius);
    }

    /** Same number without the unit, for rows that carry the unit in the label. */
    public static String celsius(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    /**
     * Whole degrees with the single-glyph unit, for the log column and one-line
     * hints.
     *
     * <p>The monospace faces render "50&#176;C" cleanly but the CJK face spaces
     * the degree sign away from the letter ("100.00 &#176; C"), which reads as a
     * typo on a panel this small. U+2103 is one glyph in the CJK face, so it is
     * used wherever that face is - and only there, because the monospace face
     * has no glyph for it at all and would paint a box.</p>
     */
    public static String celsiusMark(double celsius) {
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.0f\u2103", celsius);
    }

    /** Whole degrees with the two-character unit, for monospace contexts. */
    public static String temperatureLog(double celsius) {
        if (Double.isNaN(celsius) || Double.isInfinite(celsius)) {
            return "--";
        }
        return String.format(Locale.ROOT, "%.0f\u00b0C", celsius);
    }

    // --------------------------------------------------------------------- rates

    /** Signed per-second rate: +50.00/s. */
    public static String rate(double perTick) {
        double perSecond = perTick * 20.0D;
        return String.format(Locale.ROOT, "%+.2f/s", perSecond);
    }

    /** Per-second rate without the sign, for labels that already say "output". */
    public static String rateAbs(double perTick) {
        return String.format(Locale.ROOT, "%.2f/s", perTick * 20.0D);
    }

    // -------------------------------------------------------------------- ratios

    public static String percent(double ratio) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        return String.format(Locale.ROOT, "%.2f%%", clamped * 100.0D);
    }

    public static String percentShort(double ratio) {
        double clamped = Math.max(0.0D, Math.min(1.0D, ratio));
        return String.format(Locale.ROOT, "%.0f%%", clamped * 100.0D);
    }

    /** 0..100 damage value straight to "37.5%". */
    public static String percentOf100(double value) {
        return String.format(Locale.ROOT, "%.1f%%", Math.max(0.0D, Math.min(100.0D, value)));
    }

    /** Stabilization 0..10 -> 0..1. */
    public static double stabRatio(double stabilization) {
        if (stabilization <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, stabilization / STAB_FULL_SCALE);
    }

    public static String stabilization(double stabilization) {
        return percent(stabRatio(stabilization));
    }

    // -------------------------------------------------------------------- energy

    /**
     * DFC energy in SPK.
     *
     * <p>NTM's core keeps its buffer in internal units and every readout the
     * author exposes multiplies by 1000 to reach SPK, so the panel does the
     * same - otherwise the monitor shows numbers a thousand times smaller than
     * the game's own core GUI and looks broken.</p>
     */
    public static String spk(double internal) {
        double value = internal * 1000.0D;
        return scaled(value) + "SPK";
    }

    /** Same, per second: the value is per tick internally. */
    public static String spkRate(double internalPerTick) {
        double value = internalPerTick * 1000.0D * 20.0D;
        return scaled(value) + "SPK/s";
    }

    /** 1000-based prefix with two decimals, no unit. */
    private static String scaled(double value) {
        double abs = Math.abs(value);
        if (abs >= 1.0E9D) {
            return String.format(Locale.ROOT, "%.2f G", value / 1.0E9D);
        }
        if (abs >= 1.0E6D) {
            return String.format(Locale.ROOT, "%.2f M", value / 1.0E6D);
        }
        if (abs >= 1.0E3D) {
            return String.format(Locale.ROOT, "%.2f k", value / 1.0E3D);
        }
        return String.format(Locale.ROOT, "%.2f ", value);
    }

    /** Potential gain is a bare multiplier starting at 1, so no unit. */
    public static String gain(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    // ---------------------------------------------------------------------- time

    /** Seconds -> [171:28:03]. Used for uptime. */
    public static String uptime(long ticks) {
        long total = Math.max(0L, ticks / 20L);
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        return String.format(Locale.ROOT, "[%d:%02d:%02d]", hours, minutes, seconds);
    }

    /** Seconds -> 03:21, for countdowns. */
    public static String countdown(double seconds) {
        if (seconds < 0.0D) {
            return "--:--";
        }
        int total = (int) Math.ceil(seconds);
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int rest = total % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, rest);
        }
        return String.format(Locale.ROOT, "%02d:%02d", minutes, rest);
    }

    // ------------------------------------------------------------------ severity

    /**
     * Heat severity against the core's own melting point.
     *
     * <p>Absolute thresholds would be wrong: a core running on low-tier fuel
     * melts at 1200°C and one on antimatter at 9000°C, so "2000°C" is an
     * emergency for one and idle for the other. Everything is relative.</p>
     */
    public static int heatLevel(double temperature, double meltingPoint) {
        if (meltingPoint <= 0.0D) {
            return 0;
        }
        double ratio = temperature / meltingPoint;
        if (ratio >= 0.95D) {
            return 2;
        }
        return ratio >= 0.80D ? 1 : 0;
    }

    public static int stabLevel(double ratio) {
        if (ratio <= 0.0D) {
            return 2;
        }
        if (ratio < 0.25D) {
            return 2;
        }
        return ratio < 0.60D ? 1 : 0;
    }

    public static int collapseLevel(double ratio) {
        if (ratio >= 0.5D) {
            return 2;
        }
        return ratio > 0.0D ? 1 : 0;
    }

    public static String heatLabel(int level) {
        return level == 2 ? "\u5371\u6025" : level == 1 ? "\u504f\u9ad8" : "\u6b63\u5e38";
    }

    public static String stabLabel(int level) {
        return level == 2 ? "\u5371\u6025" : level == 1 ? "\u504f\u4f4e" : "\u6b63\u5e38";
    }
}

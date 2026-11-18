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

package com.hbmzz.panel;

import com.hbmzz.dfc.DFCSnapshot;

import java.util.Locale;

/**
 * A readable value of the DFC, as addressed by the custom panel.
 *
 * <p>Channels are the vocabulary of the whole feature: a widget says "show me
 * {@link #TEMPERATURE}", a condition says "compare {@link #TEMPERATURE}", and
 * the log line says "温度". All three read the same number from the same
 * method, so a gauge and the rule watching it can never disagree.</p>
 *
 * <p>The list is deliberately short and flat. A programming surface made of
 * two dozen half-understood sensors is worse than one made of twelve honest
 * ones, and every channel here maps to a field the monitor already ships in
 * {@link DFCSnapshot} - nothing is invented, nothing has to be polled twice.</p>
 */
public enum Channel {

    TEMPERATURE("\u6e29\u5ea6", "\u00b0C", 0.0D, 15000.0D, 0, false),
    MELTING("\u7194\u70b9", "\u00b0C", 0.0D, 15000.0D, 0, false),
    STABILITY("\u7a33\u5b9a\u5ea6", "%", 0.0D, 100.0D, 0, false),
    CONTAINED("\u50a8\u80fd", "TU", 0.0D, 10000000.0D, 0, false),
    EXPELLING("\u8f93\u51fa\u529f\u7387", "TU/t", 0.0D, 1000000.0D, 1, false),
    GAIN("\u589e\u76ca", "TU/t", 0.0D, 1000000.0D, 1, false),
    DAMAGE("\u50ac\u5316\u5242\u635f\u4f24", "%", 0.0D, 100.0D, 1, false),
    PARTS("\u7ed3\u6784\u4ef6", "\u4ef6", 0.0D, 60.0D, 0, false),
    FUEL_A("\u71c3\u6599A \u6db2\u4f4d", "%", 0.0D, 100.0D, 0, false),
    FUEL_B("\u71c3\u6599B \u6db2\u4f4d", "%", 0.0D, 100.0D, 0, false),
    COUNTDOWN("\u7194\u6bc1\u5012\u8ba1\u65f6", "s", 0.0D, 3600.0D, 0, false),
    CORE_ONLINE("\u5806\u82af\u5728\u7ebf", "", 0.0D, 1.0D, 0, true),
    COLLAPSING("\u574d\u7f29\u4e2d", "", 0.0D, 1.0D, 0, true),
    HAS_CORE("\u71c3\u6599\u88c5\u5165", "", 0.0D, 1.0D, 0, true);

    /**
     * Same full scale as {@code DfcFormat.STAB_FULL_SCALE}. Duplicated rather
     * than imported because this package has to load on a dedicated server,
     * where the client's drawing helpers must not even be referenced.
     */
    private static final double STAB_FULL_SCALE = 10.0D;

    /** Below this the core has not lit off; the monitor draws it as offline. */
    private static final double ONLINE_MIN_TEMP = 100.0D;

    private final String label;
    private final String unit;
    private final double min;
    private final double max;
    private final int decimals;
    private final boolean bool;

    Channel(String label, String unit, double min, double max, int decimals, boolean bool) {
        this.label = label;
        this.unit = unit;
        this.min = min;
        this.max = max;
        this.decimals = decimals;
        this.bool = bool;
    }

    public String label() {
        return this.label;
    }

    public String unit() {
        return this.unit;
    }

    public double min() {
        return this.min;
    }

    public double max() {
        return this.max;
    }

    public int decimals() {
        return this.decimals;
    }

    public boolean isBoolean() {
        return this.bool;
    }

    /** Current value of this channel, 0 when nothing is bound. */
    public double read(DFCSnapshot d) {
        if (d == null) {
            return 0.0D;
        }
        switch (this) {
            case TEMPERATURE:
                return d.temperature;
            case MELTING:
                return d.meltingPoint;
            case STABILITY:
                return Math.max(0.0D, Math.min(1.0D, d.stabilization / STAB_FULL_SCALE)) * 100.0D;
            case CONTAINED:
                return d.containedEnergy;
            case EXPELLING:
                return d.expellingEnergy;
            case GAIN:
                return d.potentialGain;
            case DAMAGE:
                return Math.max(d.damageA, d.damageB);
            case PARTS:
                return d.stabilizers + d.absorbers + d.pulsers + d.emitters
                        + d.injectors + d.exchangers + d.cemiters;
            case FUEL_A:
                return ratio(d.fuel1Fill, d.fuel1Max) * 100.0D;
            case FUEL_B:
                return ratio(d.fuel2Fill, d.fuel2Max) * 100.0D;
            case COUNTDOWN:
                if (d.predictedSeconds > 0) {
                    return d.predictedSeconds;
                }
                return d.explosionIn > 0.0D ? d.explosionIn : 0.0D;
            case CORE_ONLINE:
                return d.coreFound && d.temperature >= ONLINE_MIN_TEMP ? 1.0D : 0.0D;
            case COLLAPSING:
                return d.collapsing ? 1.0D : 0.0D;
            case HAS_CORE:
                return d.hasCore ? 1.0D : 0.0D;
            default:
                return 0.0D;
        }
    }

    private static double ratio(double fill, double max) {
        if (max <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, fill / max));
    }

    /** True when the reading is meaningful; a cold core prints "--" instead of 0. */
    public boolean available(DFCSnapshot d) {
        return d != null && d.coreFound;
    }

    /** Value with its own unit, used by readouts and by the rule summary line. */
    public String format(double value) {
        if (this.bool) {
            return value >= 0.5D ? "\u662f" : "\u5426";
        }
        String number;
        if (Math.abs(value) >= 100000.0D) {
            number = String.format(Locale.ROOT, "%.2fM", value / 1000000.0D);
        } else if (Math.abs(value) >= 10000.0D) {
            number = String.format(Locale.ROOT, "%.1fk", value / 1000.0D);
        } else {
            number = String.format(Locale.ROOT, "%." + this.decimals + "f", value);
        }
        return this.unit.isEmpty() ? number : number + this.unit;
    }

    /** Bare number, for a stepper that owns the unit in its own label. */
    public String formatNumber(double value) {
        if (this.bool) {
            return value >= 0.5D ? "1" : "0";
        }
        if (Math.abs(value - Math.rint(value)) < 0.0001D) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.2f", value);
    }

    public static Channel byName(String name) {
        for (Channel channel : values()) {
            if (channel.name().equals(name)) {
                return channel;
            }
        }
        return TEMPERATURE;
    }

    public Channel next(int delta) {
        Channel[] all = values();
        int index = (ordinal() + delta + all.length) % all.length;
        return all[index];
    }

    /** Channels a widget may display: everything, minus the ones that are events. */
    public static Channel[] displayable() {
        return values();
    }
}

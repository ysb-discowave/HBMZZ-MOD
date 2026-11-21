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

package com.hbmzz.alert;

/**
 * The selectable power and fuse tiers of a nuclear device.
 *
 * <p>Both ladders are small and fixed on purpose. A player standing at a bomb
 * cycles them with a gesture and wants to know what the next click will give
 * before clicking, so the values are readable names and whole numbers rather
 * than a free-form field that could hide a typo. The tiers live here rather
 * than in the tile entity so the client can paint the current tier from the
 * yield/fuse numbers it already receives - no extra packet field is needed.</p>
 *
 * <p>The power ladder deliberately top-ends at {@link #ANNIHILATION}, which
 * raises {@link AlertCode#DELTA} ("facility annihilation") instead of the
 * ordinary ALPHA a warhead gets, and hands off to {@link NukeAnnihilation} to
 * erase the loaded facility. That is what "毁档" means here: not a bigger crater,
 * but the site being wiped out of the save entirely.</p>
 */
public final class NukeTiers {

    private NukeTiers() {
    }

    /**
     * Destructive power, in increasing order. {@code power} is vanilla explosion
     * power (TNT is 4); the server still clamps it to the configured blast cap so
     * an operator who needs a smaller crater can keep one.
     */
    public enum Power {
        TEST(0, "试验档", "TEST", 1, 8.0F, AlertCode.ALPHA),
        TACTICAL(1, "战术档", "TACTICAL", 25, 20.0F, AlertCode.ALPHA),
        STRATEGIC(2, "战略档", "STRATEGIC", 100, 40.0F, AlertCode.ALPHA),
        HEAVY(3, "重型档", "HEAVY", 500, 64.0F, AlertCode.ALPHA),
        CORE_BREAKER(4, "破芯档", "CORE BREAKER", 2000, 90.0F, AlertCode.OMEGA),
        /**
         * The top tier: the whole site is written off. Drives the DELTA code and
         * starts a {@link NukeAnnihilation} wave that clears the loaded facility.
         */
        ANNIHILATION(5, "毁档", "ANNIHILATION", 10000, 120.0F, AlertCode.DELTA);

        public final int id;
        /** Short Chinese name shown on monitors and in chat. */
        public final String zh;
        /** Latin name, used where the display has no CJK font. */
        public final String en;
        /** Nominal yield in kilotons, kept for the readout and blast record. */
        public final int yieldKt;
        /** Vanilla explosion power before the config cap is applied. */
        public final float power;
        /** Facility code raised when this tier goes off. */
        public final AlertCode code;

        Power(int id, String zh, String en, int yieldKt, float power, AlertCode code) {
            this.id = id;
            this.zh = zh;
            this.en = en;
            this.yieldKt = yieldKt;
            this.power = power;
            this.code = code;
        }

        public Power next() {
            Power[] all = values();
            return all[(this.id + 1) % all.length];
        }

        public static Power byId(int id) {
            Power[] all = values();
            return all[Math.max(0, Math.min(all.length - 1, id))];
        }

        /** Closest tier to a legacy yield, so old saves and configs still pick a sane tier. */
        public static Power nearestYield(int yieldKt) {
            Power best = TEST;
            int distance = Integer.MAX_VALUE;
            for (Power tier : values()) {
                int d = Math.abs(tier.yieldKt - yieldKt);
                if (d < distance) {
                    distance = d;
                    best = tier;
                }
            }
            return best;
        }

        public static int count() {
            return values().length;
        }
    }

    /** Fuse length, in increasing order. The default sits at 30 seconds. */
    public enum Fuse {
        S5(0, 100, "5 秒", "5 s"),
        S15(1, 300, "15 秒", "15 s"),
        S30(2, 600, "30 秒", "30 s"),
        S60(3, 1200, "60 秒", "60 s"),
        S120(4, 2400, "120 秒", "120 s"),
        S300(5, 6000, "300 秒", "300 s");

        public final int id;
        /** Fuse length in ticks. */
        public final int ticks;
        public final String zh;
        public final String en;

        Fuse(int id, int ticks, String zh, String en) {
            this.id = id;
            this.ticks = ticks;
            this.zh = zh;
            this.en = en;
        }

        public Fuse next() {
            Fuse[] all = values();
            return all[(this.id + 1) % all.length];
        }

        public static Fuse byId(int id) {
            Fuse[] all = values();
            return all[Math.max(0, Math.min(all.length - 1, id))];
        }

        /** Closest fuse to a legacy tick count, for old saves and configs. */
        public static Fuse nearestTicks(int ticks) {
            Fuse best = S30;
            int distance = Integer.MAX_VALUE;
            for (Fuse tier : values()) {
                int d = Math.abs(tier.ticks - ticks);
                if (d < distance) {
                    distance = d;
                    best = tier;
                }
            }
            return best;
        }

        public static int count() {
            return values().length;
        }
    }
}

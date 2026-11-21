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

package com.hbmzz.aegis;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

/** Dedicated settings for the Aegis terminal, stored under its own config key. */
public final class AegisConfig {

    public static int pollTicks = 10;
    public static int viewRange = 40;
    public static int scanRadius = 48;
    public static int gridCols = 2;
    public static int gridRows = 2;
    public static boolean glow = true;
    public static boolean showDiagnostics = true;
    /** Heat fraction (0..1) above which the readout turns amber. */
    public static float warnHeat = 0.72F;
    /** Heat fraction (0..1) above which the readout turns red. */
    public static float dangerHeat = 0.9F;

    private AegisConfig() {
    }

    public static void load(File suggested) {
        if (suggested == null) {
            return;
        }
        File file = new File(suggested.getParentFile(), "hbmzz-aegis.cfg");
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();
            pollTicks = cfg.getInt("pollTicks", "terminal", 10, 2, 100,
                    "Ticks between telemetry refreshes sent to nearby viewers.");
            viewRange = cfg.getInt("viewRange", "terminal", 40, 4, 128,
                    "Radius in blocks within which a placed terminal is fed telemetry.");
            scanRadius = cfg.getInt("scanRadius", "terminal", 48, 4, 128,
                    "Radius searched for a DFC core when none is explicitly bound. "
                    + "Large bases can place the core tens of blocks from the terminal; "
                    + "16 was too small and made distant cores look un-connectable.");
            // One-time migration: existing worlds carried scanRadius=16 from the
            // old default. Forge keeps the on-disk value, so without this a
            // rebuilt jar would still only search 16 blocks. Bump any legacy
            // narrow value up to the new 48-block minimum and persist it.
            if (scanRadius < 48) {
                scanRadius = 48;
                cfg.get("terminal", "scanRadius", 48).set(48);
            }
            gridCols = cfg.getInt("gridCols", "terminal", 2, 1, 6,
                    "Default wall width of a terminal, in blocks.");
            gridRows = cfg.getInt("gridRows", "terminal", 2, 1, 4,
                    "Default wall height of a terminal, in blocks.");
            glow = cfg.getBoolean("glow", "display", true,
                    "Emit a little light from an active terminal.");
            showDiagnostics = cfg.getBoolean("showDiagnostics", "display", true,
                    "Show the component and wear page among the selectable pages.");
            warnHeat = cfg.getFloat("warnHeat", "display", 0.72F, 0.1F, 1.0F,
                    "Fraction of the melt point at which the heat gauge goes amber.");
            dangerHeat = cfg.getFloat("dangerHeat", "display", 0.9F, 0.1F, 1.0F,
                    "Fraction of the melt point at which the heat gauge goes red.");
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }

    public static int clampCols(int value) {
        return Math.max(1, Math.min(6, value));
    }

    public static int clampRows(int value) {
        return Math.max(1, Math.min(4, value));
    }
}

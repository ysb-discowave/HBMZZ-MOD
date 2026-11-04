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

package com.hbmzz;

import net.minecraftforge.common.config.Configuration;

import java.io.File;

public final class HBMZZConfig {

    public static int scanRadius = 16;

    // ---- terminal window ----
    public static int defaultWidth = 420;
    public static int defaultHeight = 280;
    public static int minWidth = 300;
    public static int minHeight = 214;
    public static int maxWidth = 1200;
    public static int maxHeight = 800;
    public static int updateInterval = 10;
    public static boolean bootLog = true;

    // ---- live screen ----
    public static int displayInterval = 10;
    public static int displayRange = 32;
    public static boolean liveScreen = true;
    public static boolean hudOverlay = true;
    public static int hudRange = 16;
    public static int defaultScreenCols = 2;
    public static int defaultScreenRows = 2;
    public static int maxScreenCols = 4;
    public static int maxScreenRows = 3;

    // ---- video broadcaster ----
    public static int videoCooldownTicks = 20;
    public static boolean videoAllowEsc = false;
    public static boolean videoOperatorOnly = false;
    public static boolean videoAudio = true;
    public static int videoCacheFrames = 6;
    public static int videoMaxWidth = 960;
    public static int videoMaxHeight = 540;

    private HBMZZConfig() {
    }

    public static void load(File file) {
        Configuration cfg = new Configuration(file);
        try {
            cfg.load();
            scanRadius = cfg.getInt("scanRadius", "monitor", 16, 4, 64,
                    "Radius (blocks) used to search for a DFC core when none is bound.");
            defaultWidth = cfg.getInt("defaultWidth", "monitor", 420, 300, 1200,
                    "Default width of the terminal window, in GUI pixels.");
            defaultHeight = cfg.getInt("defaultHeight", "monitor", 280, 214, 800,
                    "Default height of the terminal window, in GUI pixels.");
            minWidth = cfg.getInt("minWidth", "monitor", 300, 240, 1200,
                    "Smallest width the terminal can be dragged to.");
            minHeight = cfg.getInt("minHeight", "monitor", 214, 170, 800,
                    "Smallest height the terminal can be dragged to.");
            maxWidth = cfg.getInt("maxWidth", "monitor", 1200, 300, 1600,
                    "Largest width the terminal can be dragged to (the screen is the hard cap).");
            maxHeight = cfg.getInt("maxHeight", "monitor", 800, 214, 1200,
                    "Largest height the terminal can be dragged to (the screen is the hard cap).");
            updateInterval = cfg.getInt("updateInterval", "monitor", 10, 2, 100,
                    "Ticks between terminal data requests.");
            bootLog = cfg.getBoolean("bootLog", "monitor", true,
                    "Play the simulated Lua boot log when the terminal opens.");
            displayInterval = cfg.getInt("displayInterval", "display", 10, 2, 100,
                    "Ticks between telemetry pushes to the live screen, HUD and terminal.");
            displayRange = cfg.getInt("displayRange", "display", 32, 4, 64,
                    "Players within this radius (blocks) receive live screen updates.");
            liveScreen = cfg.getBoolean("liveScreen", "display", true,
                    "Render the animated panel directly on the monitor block face.");
            hudOverlay = cfg.getBoolean("hudOverlay", "display", true,
                    "Show a compact DFC panel while looking at a monitor.");
            hudRange = cfg.getInt("hudRange", "display", 16, 4, 64,
                    "Maximum distance (blocks) at which the HUD panel appears.");
            defaultScreenCols = cfg.getInt("defaultScreenCols", "display", 2, 1, 4,
                    "How many blocks wide a freshly placed wall screen is.");
            defaultScreenRows = cfg.getInt("defaultScreenRows", "display", 2, 1, 3,
                    "How many blocks tall a freshly placed wall screen is.");
            maxScreenCols = cfg.getInt("maxScreenCols", "display", 4, 1, 6,
                    "Hard cap for the wall screen width, in blocks.");
            maxScreenRows = cfg.getInt("maxScreenRows", "display", 3, 1, 6,
                    "Hard cap for the wall screen height, in blocks.");
            videoCooldownTicks = cfg.getInt("cooldownTicks", "video", 20, 1, 200,
                    "Minimum ticks between two broadcasts; a faster redstone clock is ignored.");
            videoAllowEsc = cfg.getBoolean("allowEsc", "video", false,
                    "Let players press ESC to leave a broadcast. False keeps the full-screen takeover until the clip ends.");
            videoOperatorOnly = cfg.getBoolean("operatorOnly", "video", false,
                    "Require command level 2 (operator) to save settings or test-fire. The redstone trigger has no player behind it and is unaffected.");
            videoAudio = cfg.getBoolean("audio", "video", true,
                    "Play the optional audio.wav track. Silently skipped when the mixer refuses it.");
            videoCacheFrames = cfg.getInt("cacheFrames", "video", 6, 2, 24,
                    "Decoded frames kept ahead of the playhead. Higher is smoother and uses more RAM.");
            videoMaxWidth = cfg.getInt("maxWidth", "video", 960, 128, 1920,
                    "Video frames wider than this are scaled down on decode.");
            videoMaxHeight = cfg.getInt("maxHeight", "video", 540, 128, 1080,
                    "Video frames taller than this are scaled down on decode.");
        } finally {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        }
    }

    public static int clampWidth(int w) {
        return Math.max(minWidth, Math.min(maxWidth, w));
    }

    public static int clampHeight(int h) {
        return Math.max(minHeight, Math.min(maxHeight, h));
    }

    public static int clampCols(int cols) {
        return Math.max(1, Math.min(Math.max(1, maxScreenCols), cols));
    }

    public static int clampRows(int rows) {
        return Math.max(1, Math.min(Math.max(1, maxScreenRows), rows));
    }
}

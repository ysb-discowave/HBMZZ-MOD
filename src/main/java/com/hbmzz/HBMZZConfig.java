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

    /** Staged RBMK blow-up. Off means the base mod's one-tick meltdown stands. */
    public static boolean rbmkSurge = true;
    public static int rbmkSurgeHeat = 1000;
    public static int rbmkSurgeTicks = 1200;
    public static float rbmkSurgePower = 10.0F;
    public static boolean rbmkSurgeMelt = true;
    /** The explosion the falling lid sets off when it lands back on the core. */
    public static float rbmkLidBlast = 30.0F;
    /** Camera roll, hotbar displacement and the ground ring. */
    public static boolean rbmkShake = true;

    // ---- zd_tx cinematic ----
    /**
     * Degrees per tick the camera travels around the target. 0.72 is a full
     * lap in 500 ticks (25 s), which is about how long a rod-jump run has left
     * when the warning starts. The client clamps at 12 (a lap in 1.7 s), past
     * which the shot is unwatchable rather than dramatic.
     */
    public static float cinematicSpeed = 0.72F;
    /** Same for the DFC, whose final window is only five seconds and needs a faster orbit to read. */
    public static float cinematicDfcSpeed = 2.0F;
    /** Distance from the target, in blocks. Small enough to still see the machine. */
    public static int cinematicRadius = 18;
    /** Height above the target's core. Positive looks down on it from above. */
    public static int cinematicHeight = 7;

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
            rbmkSurge = cfg.getBoolean("surge", "rbmk", true,
                    "Replace the base mod's one-tick RBMK meltdown with the staged one: jumping rods, then the lid thrown off twice. False restores the base behaviour exactly.");
            rbmkSurgeHeat = cfg.getInt("heat", "rbmk", 1000, 100, 100000,
                    "Heat above which a reactor is considered ready to blow. The base mod melts a column at maxHeat() (1500 by default); below that line there is time to stage a warning.");
            rbmkSurgeTicks = cfg.getInt("jumpTicks", "rbmk", 1200, 20, 24000,
                    "Ticks the rods jump before the lid leaves. 1200 is one minute. The jump animation needs the Cursed Edition addon; without it the countdown still runs, silently.");
            rbmkSurgePower = (float) cfg.getFloat("blastPower", "rbmk", 10.0F, 1.0F, 30.0F,
                    "Strength of the two scripted explosions. Vanilla explosion scale, so 10 is roughly a charged creeper going off in the reactor hall.");
            rbmkSurgeMelt = cfg.getBoolean("melt", "rbmk", true,
                    "Hand the wrecked reactor to the base mod's own meltdown once the lid has settled, so the aftermath is the wreckage players already know.");
            rbmkLidBlast = (float) cfg.getFloat("lidBlastPower", "rbmk", 30.0F, 1.0F, 120.0F,
                    "Explosion set off when the falling lid lands back on the core. This is the second bang, the one that throws the plate up again. Vanilla explosion scale: 30 makes a real crater.");
            rbmkShake = cfg.getBoolean("shake", "rbmk", true,
                    "Shake the camera and the hotbar, and draw the expanding ground ring. Client-side only; the server sends a position and a strength, the client animates it.");
            cinematicSpeed = (float) cfg.getFloat("rbmkSpeed", "cinematic", 0.72F, 0.0F, 12.0F,
                    "Degrees per tick the camera orbits an RBMK that has started jumping its rods. The gamerule zd_tx has to be on for this to happen at all. 0.72 is one lap in about 25 seconds; 0 leaves the camera alone.");
            cinematicDfcSpeed = (float) cfg.getFloat("dfcSpeed", "cinematic", 2.0F, 0.0F, 12.0F,
                    "Degrees per tick the camera orbits a DFC in the last five seconds before it goes. Faster than the RBMK default because that window is short.");
            cinematicRadius = cfg.getInt("radius", "cinematic", 18, 6, 40,
                    "Distance the camera keeps from the target, in blocks.");
            cinematicHeight = cfg.getInt("height", "cinematic", 7, -8, 40,
                    "Height of the camera above the target. Negative looks up from below.");
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

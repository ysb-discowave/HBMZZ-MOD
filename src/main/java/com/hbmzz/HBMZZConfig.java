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

import com.hbmzz.hack.HackState;
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

    // ---- hacker tablet ----
    /**
     * Master switch for the intrusion storyline.
     *
     * <p>Off makes the tablet refuse every command, and the monitor never
     * enters an intrusion state. A server that does not want the mechanic has
     * to be able to take it out without uninstalling the item, because the
     * item may already be sitting in somebody's world.</p>
     */
    public static boolean hackEnabled = true;

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

    // ---- nuclear accident system ----
    /**
     * Yield, in kilotons, copied into a device when it is placed. The default is
     * a strategic warhead rather than a tactical one: this is the block that
     * exists to end a facility, and a small one would make the ALPHA code look
     * like an overreaction.
     */
    public static int nukeYieldKt = 100;
    /** Fuse, in ticks. 100 = five seconds, which is long enough for the siren. */
    public static int nukeFuseTicks = 100;
    /** Vanilla explosion power for a zero-yield device; the curve starts here. */
    public static double nukeBlastBase = 6.0D;
    /** Additional explosion power per kiloton. */
    public static double nukeBlastPerKt = 0.28D;
    /** Hard ceiling: past ~50 one explosion re-checks the whole crater in one tick. */
    public static double nukeBlastCap = 48.0D;
    /** Ticks between catch-up pushes from a display to the players near it. */
    public static int alertRefreshTicks = 20;
    /** Facility-wide klaxon on a code broadcast. Client-side sound, server-side trigger. */
    public static boolean alertKlaxon = true;
    /**
     * The safe level for the rotating alarm beacon: it lights and spins only when
     * the active code is strictly above this level. Default 2 (ORANGE) means the
     * beacon wakes at RED or worse. Lower it to fire the beacon earlier, raise it
     * to only the catastrophic codes.
     */
    public static int alertSafeLevel = 2;

    // ---- DFC blast sensor ----
    /** Master switch for the sensor block; off means it never sees a blast. */
    public static boolean dfcSensorEnabled = true;
    /**
     * How far a sensor still answers a blast, in blocks (same dimension). 0 = unlimited.
     * The point of a number here is that a base can be big: the DFC hall and the
     * emergency generator room are usually not next door.
     */
    public static int dfcSensorRange = 512;
    /**
     * Radius searched for a DFC core when a nuclear blast entity joins the world.
     * This is what separates "the DFC went up" from "someone set off a bomb in the
     * next hall" - the blast has to have happened on top of a core.
     */
    public static int dfcSensorCoreRange = 12;
    /**
     * Treat every nuclear blast in the dimension as a DFC blast, without looking
     * for a core. For packs where the DFC detonates somewhere else entirely.
     */
    public static boolean dfcSensorAnyBlast = false;
    /** Ticks the signal stays at full strength before it starts stepping down. */
    public static int dfcSensorHoldTicks = 100;
    /** Ticks the signal spends stepping from full down to 1, then it goes out. */
    public static int dfcSensorDecayTicks = 200;
    /** Peak redstone strength, 1..15. */
    public static int dfcSensorStrength = 15;

    // ---- DFC auto shutdown ----
    /**
     * Master switch for the whole auto-shutdown procedure. Off leaves the
     * monitor's toggle visible but inert, and nothing ever cools a core.
     */
    public static boolean dfcShutdownEnabled = true;
    /**
     * Seconds before detonation at which the retraction starts. The spec's
     * number: the last minute. Making it configurable is not an invitation to
     * widen it - the effect reads as a last-ditch procedure because it is short.
     */
    public static int dfcShutdownRetractSeconds = 60;
    /** Seconds the core is held at zero after the ramp completes. */
    public static int dfcShutdownHoldSeconds = 5;
    /**
     * If the core reports no countdown while collapsing, start the retraction
     * after this many ticks anyway. Without it a core whose `explosionIn`
     * getter is missing would keep the aura forever and never cool.
     */
    public static int dfcShutdownFallbackTicks = 1200;
    /**
     * Try to clear the core's own collapse state once the temperature has been
     * brought to zero. Best effort: the field names belong to another mod, and
     * only a short explicit list is ever written. Off means the procedure only
     * cools the core and lets the upstream countdown do what it does.
     */
    public static boolean dfcShutdownForceStop = true;
    /** How often the blue aura emits, in ticks. */
    public static int dfcShutdownAuraTicks = 5;
    /** How often an energy burst fires during the aura, in ticks. */
    public static int dfcShutdownBurstTicks = 40;
    /** Radius of the effect cloud around the core, in blocks. */
    public static int dfcShutdownFxRange = 7;
    /** Players within this distance receive the effect packets, in blocks. */
    public static int dfcShutdownWatchRange = 64;

    // ---- zd_tx cinematic ----
    /**
     * Degrees per tick the camera travels around the target. 0.72 is a full
     * lap in 500 ticks (25 s), which is about how long a rod-jump run has left
     * when the warning starts. The client clamps at 12 (a lap in 1.7 s), past
     * which the shot is unwatchable rather than dramatic.
     */
    public static float cinematicSpeed = 0.72F;
    /** Same for the DFC finale, slowed from 2.0 because the orbit read as too fast. */
    public static float cinematicDfcSpeed = 1.0F;
    /** Distance from the target, in blocks. Small enough to still see the machine. */
    public static int cinematicRadius = 18;
    /** Height above the target's core. Positive looks down on it from above. */
    public static int cinematicHeight = 7;

    // ---- DFC-TSTX (three-second orbit at the start of a collapse) ----
    /**
     * Degrees per tick for the three-second shot. A full lap is 90 ticks, so
     * 2.0 sweeps a third of a circle in the three seconds the rule allows -
     * enough to read as an orbit, slow enough not to whip past the core.
     */
    public static float tstxSpeed = 2.0F;
    /** Distance from the core for the three-second shot. */
    public static int tstxRadius = 16;
    /** Height above the core for the three-second shot. */
    public static int tstxHeight = 6;
    /** Ticks the shot lasts. 60 = exactly three seconds at 20 tps. */
    public static int tstxTicks = 60;
    /** Players within this distance are put behind the camera. */
    public static int tstxRange = 96;

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
                    "Default for 强制限制返回 on a freshly placed player: false means the lock starts ON."
                            + " The lock is per-block, set by sneak-right-clicking the player, and it only"
                            + " applies while no wall screen is connected. This option seeds new blocks and"
                            + " blocks from saves that never stored the flag.");
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
            hackEnabled = cfg.getBoolean("enabled", "hacker", true,
                    "Allow the hacker tablet to write machine fields. Off makes every command refuse.");
            // Written straight into the state class rather than copied here:
            // the canvas reads the same field to size its recovery bar, so one
            // value has to be the truth for both the server and the screen.
            HackState.disconnectTicks = cfg.getInt("disconnectTicks", "hacker", 60, 10, 400,
                    "Ticks a monitor shows the broken-node page after a value is written. 60 = 3 s.");
            HackState.degradedTicks = cfg.getInt("degradedTicks", "hacker", 600, 20, 12000,
                    "Ticks the tampered readouts keep showing Err afterwards. 600 = 30 s.");
            HackState.cutTicks = cfg.getInt("cutTicks", "hacker", 1200, 20, 24000,
                    "Ticks a cut core signal lasts before the feed returns on its own. 1200 = 60 s.");
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
            nukeYieldKt = cfg.getInt("yield", "nuke", 100, 1, 10000,
                    "Yield in kilotons, copied into a device when it is placed. Blast power is derived from it.");
            nukeFuseTicks = cfg.getInt("fuseTicks", "nuke", 100, 0, 1200,
                    "Fuse in ticks between the signal and the bang. 100 = 5 s; 0 detonates on the signal.");
            nukeBlastBase = cfg.getFloat("blastBase", "nuke", 6.0F, 1.0F, 60.0F,
                    "Vanilla explosion power at zero yield. TNT is 4.");
            nukeBlastPerKt = cfg.getFloat("blastPerKt", "nuke", 0.28F, 0.0F, 4.0F,
                    "Explosion power added per kiloton of yield.");
            nukeBlastCap = cfg.getFloat("blastCap", "nuke", 48.0F, 4.0F, 120.0F,
                    "Hard ceiling on explosion power. Above ~50 the crater is expensive to compute.");
            alertRefreshTicks = cfg.getInt("refreshTicks", "alert", 20, 5, 200,
                    "Ticks between catch-up pushes from an accident display to nearby players.");
            alertKlaxon = cfg.getBoolean("klaxon", "alert", true,
                    "Play a facility-wide sound when a code is broadcast.");
            alertSafeLevel = cfg.getInt("safeLevel", "alert", 2, 0, 7,
                    "The level at which the rotating alarm beacon wakes: it lights and spins only when the active code is strictly above this level. 2 (ORANGE) wakes the beacon at RED or worse; 0 makes every code a warning.");
            dfcSensorEnabled = cfg.getBoolean("enabled", "dfcSensor", true,
                    "The DFC blast sensor block. Off means it never sees a blast and never emits.");
            dfcSensorRange = cfg.getInt("range", "dfcSensor", 512, 0, 4096,
                    "How far a sensor still answers a blast, in blocks, within the same dimension. 0 = unlimited. The blast itself is recorded once on the server, so this only decides who is close enough to react.");
            dfcSensorCoreRange = cfg.getInt("coreRange", "dfcSensor", 12, 1, 64,
                    "Radius searched for a DFC core when a nuclear blast entity appears. A blast only counts as a DFC blast when it happened on top of a core, which is what keeps a nuke set off elsewhere in the base from tripping the sensors.");
            dfcSensorAnyBlast = cfg.getBoolean("anyBlast", "dfcSensor", false,
                    "Count every nuclear blast in the dimension as a DFC blast, without looking for a core. Only useful in packs where the DFC detonates without spawning the usual blast entity.");
            dfcSensorHoldTicks = cfg.getInt("holdTicks", "dfcSensor", 100, 0, 2400,
                    "Ticks the signal stays at full strength. 100 = 5 s, enough for a piston door to cycle and a generator to come online.");
            dfcSensorDecayTicks = cfg.getInt("decayTicks", "dfcSensor", 200, 0, 2400,
                    "Ticks spent stepping the signal down from full to 1 before it goes out. 0 switches off the decay and drops it straight to zero after the hold.");
            dfcSensorStrength = cfg.getInt("strength", "dfcSensor", 15, 1, 15,
                    "Peak redstone strength. 15 is full power; anything lower makes the sensor unable to drive long wire runs directly.");
            dfcShutdownEnabled = cfg.getBoolean("enabled", "dfcShutdown", true,
                    "The DFC auto-shutdown procedure. Off makes the monitor toggle inert: no effects, no cooling.");
            dfcShutdownRetractSeconds = cfg.getInt("retractSeconds", "dfcShutdown", 60, 5, 600,
                    "Seconds before detonation at which the retraction starts and the core begins cooling. 60 is the last minute.");
            dfcShutdownHoldSeconds = cfg.getInt("holdSeconds", "dfcShutdown", 5, 0, 120,
                    "Seconds the core is held at 0 C after the ramp finishes, before the procedure releases it.");
            dfcShutdownFallbackTicks = cfg.getInt("fallbackTicks", "dfcShutdown", 1200, 40, 24000,
                    "If the core never reports a countdown while collapsing, start the retraction after this many ticks anyway. 1200 = one minute.");
            dfcShutdownForceStop = cfg.getBoolean("forceStop", "dfcShutdown", true,
                    "After the core reaches 0 C, also clear its own collapse flag if the upstream mod exposes it. Best effort: only a short explicit list of setter methods and numeric fields is ever written. Off means the procedure only cools the core.");
            dfcShutdownAuraTicks = cfg.getInt("auraTicks", "dfcShutdown", 5, 1, 40,
                    "Ticks between blue aura emissions while the core is collapsing.");
            dfcShutdownBurstTicks = cfg.getInt("burstTicks", "dfcShutdown", 40, 10, 400,
                    "Ticks between energy bursts during the aura phase.");
            dfcShutdownFxRange = cfg.getInt("fxRange", "dfcShutdown", 7, 2, 24,
                    "Radius of the effect cloud around the core, in blocks.");
            dfcShutdownWatchRange = cfg.getInt("watchRange", "dfcShutdown", 64, 16, 256,
                    "Players within this distance receive the effect packets, in blocks.");
            tstxSpeed = (float) cfg.getFloat("speed", "dfcTstx", 2.0F, 0.0F, 12.0F,
                    "Degrees per tick the camera orbits a DFC that has just entered collapse, while the DFC-TSTX gamerule is on. 2.0 sweeps about a third of a circle in the three seconds the shot lasts.");
            tstxRadius = cfg.getInt("radius", "dfcTstx", 16, 6, 40,
                    "Distance the three-second shot keeps from the core, in blocks.");
            tstxHeight = cfg.getInt("height", "dfcTstx", 6, -8, 40,
                    "Height of the three-second shot above the core, in blocks.");
            tstxTicks = cfg.getInt("ticks", "dfcTstx", 60, 10, 400,
                    "How long the forced orbit lasts. 60 ticks is exactly three seconds.");
            tstxRange = cfg.getInt("range", "dfcTstx", 96, 8, 256,
                    "Players within this distance are put behind the camera, in blocks.");
            cinematicSpeed = (float) cfg.getFloat("rbmkSpeed", "cinematic", 0.72F, 0.0F, 12.0F,
                    "Degrees per tick the camera orbits an RBMK that has started jumping its rods. The gamerule zd_tx has to be on for this to happen at all. 0.72 is one lap in about 25 seconds; 0 leaves the camera alone.");
            cinematicDfcSpeed = (float) cfg.getFloat("dfcSpeed", "cinematic", 1.0F, 0.0F, 12.0F,
                    "Degrees per tick the camera orbits a DFC in the last five seconds before it goes. Still faster than the RBMK default, but slowed so the orbit reads.");
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

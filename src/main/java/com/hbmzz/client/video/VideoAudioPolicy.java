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
 * A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client.video;

import com.hbmzz.client.GuiVideoScreen;
import com.hbmzz.tile.TileEntityVideoScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;

/**
 * Who is allowed to be heard, decided once per client tick.
 *
 * <h3>The bug this exists to prevent</h3>
 * <p>Three things can hold a soundtrack at the same time: a wall in the world
 * (one decoder per screen), the full-screen window, and - before this class - the
 * two of them at once. A player standing at a broadcaster whose lever was up
 * heard the wall, opened a settings window over it, and the wall kept playing;
 * right-clicking into the player opened a second decoder with a second copy of
 * the same music. Nothing was broken individually; the sum was two songs at
 * once, offset by a second, and no single place was responsible for saying
 * "only one of you speaks".</p>
 *
 * <h3>The rule</h3>
 * <ul>
 *   <li><b>A settings window is open</b> - nobody speaks. The player is reading
 *       numbers, not watching a film.</li>
 *   <li><b>The full-screen takeover is open</b> - only it speaks; every wall is
 *       muted. It has its own decoder precisely because it may be showing a film
 *       no wall is showing.</li>
 *   <li><b>大屏展示 (the window that mirrors a wall)</b> - that wall speaks, and
 *       only that wall. The window is a magnifying glass, not a second
 *       player.</li>
 *   <li><b>No window</b> - the nearest playing wall within earshot speaks. Not
 *       all of them: four screens in one room are one film, not four.</li>
 * </ul>
 *
 * <p>Muting never pauses anything. The picture and the wall's clock keep running
 * for everybody; only the track is held, and unmuting puts it back on the film
 * time the wall is actually showing. That is what keeps a window opened for two
 * seconds from putting the room a second behind.</p>
 */
public final class VideoAudioPolicy {

    /**
     * How far a wall can be heard from, in blocks.
     *
     * <p>The same reach the remote uses to connect one. Past this the wall still
     * shows the film - it is in the world, that is its job - but this client is
     * not standing in the room any more.</p>
     */
    private static final double RANGE = 64.0D;

    private VideoAudioPolicy() {
    }

    /** Called once per client tick, after the world's decoders were ticked. */
    public static void tick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        // A window that is no longer the current screen must not keep its decoder
        // (and therefore its soundtrack) alive: leaving a world, a packet opening
        // another screen, or a crash path that skipped onGuiClosed would all leave
        // one running forever, with no window to close it from.
        GuiVideoScreen.reap(mc);

        GuiScreen screen = mc.currentScreen;
        TileEntityVideoScreen target = null;
        if (screen instanceof GuiVideoScreen) {
            GuiVideoScreen film = (GuiVideoScreen) screen;
            if (film.mirrors()) {
                target = film.mirroredWall();
            }
            // A takeover speaks for itself; the walls stay silent (target stays null).
        } else if (screen == null) {
            target = nearest(mc);
        }
        // Anything else - settings, the import window, the wall's playhead window,
        // the pause menu - is a window somebody is reading. Silence.
        VideoScreenPlayback.setAudible(target);
    }

    /** Forgets every decision; called when the client leaves a world. */
    public static void reset() {
        VideoScreenPlayback.setAudible(null);
        GuiVideoScreen.abandonLive();
    }

    /**
     * The closest wall that is actually playing a film this client has.
     *
     * <p>Chosen per tick rather than remembered, because "which wall is nearest"
     * is a fact about where the player is standing and nothing else.</p>
     */
    private static TileEntityVideoScreen nearest(Minecraft mc) {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        TileEntityVideoScreen best = null;
        double bestDistance = RANGE * RANGE;
        try {
            java.util.List<TileEntity> all = mc.world.loadedTileEntityList;
            for (int i = 0; i < all.size(); i++) {
                TileEntity te = all.get(i);
                if (!(te instanceof TileEntityVideoScreen)) {
                    continue;
                }
                TileEntityVideoScreen wall = (TileEntityVideoScreen) te;
                if (!wall.isPlaying() || wall.getVideo().isEmpty()) {
                    continue;
                }
                double distance = te.getDistanceSq(mc.player.posX, mc.player.posY, mc.player.posZ);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = wall;
                }
            }
        } catch (Throwable ignored) {
            // The list is being rebuilt underneath us (chunk load in the same
            // tick). Keeping the previous answer for one tick costs nothing.
            return VideoScreenPlayback.audible();
        }
        return best;
    }
}

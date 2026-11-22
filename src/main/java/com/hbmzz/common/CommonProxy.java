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

package com.hbmzz.common;

import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
    }

    public void init(FMLInitializationEvent event) {
    }

    public void openMonitor(BlockPos pos) {
    }

    /** Opens the Aegis terminal's configuration window (client only). */
    public void openAegis(BlockPos pos) {
    }

    /**
     * Opens the machine control panel window. Server side this is a no-op: the
     * panel is operated through its own packets, so a dedicated server never
     * needs to know a GUI exists.
     */
    public void openControlPanel(BlockPos pos) {
    }

    /**
     * Opens the video broadcaster's settings window. Client only; a dedicated
     * server never needs to know the window exists, it just carries the two
     * packets.
     */
    public void openVideoBroadcaster(BlockPos pos) {
    }

    /**
     * Opens the code broadcaster's custom-state window. Client only; a dedicated
     * server never needs to know the window exists, it just carries the settings
     * packet.
     */
    public void openCodeBroadcaster(BlockPos pos) {
    }

    /**
     * Opens the wall's size / speed window. Client only, like every other window
     * here: the server has no pixels, it just owns the numbers.
     */
    public void openVideoScreenConfig(BlockPos pos) {
    }

    /** Opens the wall's playhead window: seek, pause, rewind. */
    public void openVideoSeek(BlockPos pos) {
    }

    /**
     * Opens the wall's television window: the picture in a set-shaped window in
     * the middle of the screen. This is the plain right-click on a wall; F in
     * that window goes fullscreen and F again comes back to the set.
     */
    public void openVideoWatch(BlockPos pos) {
    }

    /** Same window, already switched to fullscreen (the menu's 「全屏观看」). */
    public void openVideoFullscreen(BlockPos pos) {
    }

    /** Opens the player's volume / exit-lock window. */
    public void openVideoPlayerConfig(BlockPos pos) {
    }

    /**
     * Opens the playback window straight from the block's plain right-click.
     *
     * <p>Client-only: the server has no window to open, and the packet path used
     * by a redstone broadcast is a different one on purpose - that one is a
     * takeover, this one is the player choosing to watch.</p>
     */
    public void openVideoPlayback(BlockPos pos) {
    }

    /**
     * Opens the command window of one terminal control.
     *
     * <p>{@code kind} says which panel the control lives on - this mod's own
     * ({@code KIND_PANEL}) or the ported console ({@code KIND_CONSOLE}) - because
     * the two speak to different tile entities over the same packets.</p>
     */
    public void openTerminal(int kind, BlockPos pos, int index) {
    }

    /**
     * Opens the hacker tablet's terminal. Client only, like every other window
     * this mod has: a dedicated server only ever sees the two packets, and the
     * command itself is parsed against the server's own world.
     */
    public void openHackerTablet() {
    }

    /** Opens the AI terminal's command window. Client only; the server just
     * carries the command and reply packets. */
    public void openAITerminal() {
    }
}
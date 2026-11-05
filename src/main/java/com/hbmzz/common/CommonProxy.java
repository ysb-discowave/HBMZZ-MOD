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
     * Opens the command window of one terminal control.
     *
     * <p>{@code kind} says which panel the control lives on - this mod's own
     * ({@code KIND_PANEL}) or the ported console ({@code KIND_CONSOLE}) - because
     * the two speak to different tile entities over the same packets.</p>
     */
    public void openTerminal(int kind, BlockPos pos, int index) {
    }
}
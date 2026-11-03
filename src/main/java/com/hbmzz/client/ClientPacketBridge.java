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

package com.hbmzz.client;

import com.hbmzz.dfc.DFCSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.client.multiplayer.WorldClient;

/**
 * Loaded only on the physical client, via reflection from the packet handler.
 *
 * <p>Every telemetry sample is stored on the monitor tile entity, which is what
 * the block renderer and the HUD read from, so the screen stays live even while
 * no GUI is open.</p>
 */
public final class ClientPacketBridge {

    private ClientPacketBridge() {
    }

    public static void onData(BlockPos pos, DFCSnapshot snapshot) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            WorldClient world = mc.world;
            if (world != null && pos != null) {
                TileEntity te = world.getTileEntity(pos);
                if (te instanceof com.hbmzz.tile.TileEntityDFCMonitor) {
                    ((com.hbmzz.tile.TileEntityDFCMonitor) te).acceptData(snapshot);
                }
            }
            GuiScreen screen = mc.currentScreen;
            if (screen instanceof GuiDFCMonitor && ((GuiDFCMonitor) screen).matches(pos)) {
                ((GuiDFCMonitor) screen).onData(snapshot);
            }
        });
    }
}

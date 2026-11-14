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
import com.hbmzz.alert.AlertSyncMessage;
import com.hbmzz.network.NukeDataMessage;
import com.hbmzz.network.HackerReplyMessage;
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

    /**
     * Hands a command reply to the tablet window, if that is what is open.
     *
     * <p>The window is checked rather than assumed: the reply arrives on the
     * client tick thread some time after the request, and the player may have
     * closed the tablet or been kicked to another screen in between. Replying
     * into a window that is no longer open would at best paint one frame of
     * stale output and at worst drive a widget that has already been disposed.</p>
     */
    /**
     * Stores the facility record and repaints nothing directly: every display
     * reads the same static fields on its next frame, so there is exactly one
     * place the value can be and no list of screens to keep in step.
     */
    public static void onAlertSync(AlertSyncMessage message) {
        if (message == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> ClientAlertState.accept(message));
    }

    /** Hands a nuke sample to the monitor tile entity that asked for it. */
    public static void onNukeData(NukeDataMessage message) {
        if (message == null || message.pos() == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            WorldClient world = mc.world;
            if (world == null) {
                return;
            }
            TileEntity te = world.getTileEntity(message.pos());
            if (te instanceof com.hbmzz.tile.TileEntityNukeMonitor) {
                ((com.hbmzz.tile.TileEntityNukeMonitor) te).accept(message.snapshot());
            }
        });
    }

    public static void onHackerReply(HackerReplyMessage message) {
        if (message == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            GuiScreen screen = mc.currentScreen;
            if (screen instanceof GuiHackerTablet) {
                ((GuiHackerTablet) screen).accept(message);
            }
        });
    }
}

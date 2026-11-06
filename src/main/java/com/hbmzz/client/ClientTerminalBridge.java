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

import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;

/**
 * Loaded only on the physical client, by reflection from the packet handler.
 *
 * <p>A terminal reply is produced on the server thread and arrives on the netty
 * thread, but it is addressed to one open window and only appends text to it -
 * so there is nothing to store on the tile, and nothing to do when that window
 * is closed. That is the whole difference from {@link ClientPacketBridge},
 * which exists because monitor samples are state the block face also reads.</p>
 */
public final class ClientTerminalBridge {

    private ClientTerminalBridge() {
    }

    public static void onReply(int kind, BlockPos pos, int index, int slot, boolean clear,
                               String kinds, String text, String labels, String names,
                               String keys, String events, String vars) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(() -> {
            if (mc.currentScreen instanceof GuiCtrlTerminal) {
                ((GuiCtrlTerminal) mc.currentScreen)
                        .onReply(kind, pos, index, slot, clear, kinds, text, labels, names,
                                keys, events, vars);
            }
        });
    }
}

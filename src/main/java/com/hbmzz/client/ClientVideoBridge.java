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

/**
 * Loaded only on the physical client, via reflection from the packet handler -
 * the same arrangement {@code ClientPacketBridge} uses, and for the same reason:
 * the handler class also has to exist on a dedicated server.
 *
 * <p>The actual takeover happens on the client thread. Swapping the current
 * screen from the netty thread is the classic way to produce a
 * {@code ConcurrentModificationException} in the middle of a render pass.</p>
 */
public final class ClientVideoBridge {

    private ClientVideoBridge() {
    }

    public static void onBroadcast(String video) {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(() -> {
            if (mc.world == null || mc.player == null) {
                // Disconnected in the same tick the packet arrived; there is no
                // screen left to take over.
                return;
            }
            mc.displayGuiScreen(new GuiVideoScreen(video));
        });
    }
}

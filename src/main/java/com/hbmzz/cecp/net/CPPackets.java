/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.net;

import com.hbmzz.network.NetworkHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

/**
 * Thin facade over this mod's {@code SimpleNetworkWrapper}, exposing the two
 * entry points NTM: Cursed Edition's control panel code calls on
 * {@code com.hbm.handler.threading.PacketThreading}.
 *
 * <p>CE dispatches packets through a thread pool to keep the server tick free;
 * a plain {@code sendTo} is used here instead - the messages are tiny and the
 * panel only sends them when a player actually turns a knob.</p>
 */
public final class CPPackets {

    private CPPackets() {
    }

    public static void createSendToServerThreadedPacket(IMessage message) {
        NetworkHandler.INSTANCE.sendToServer(message);
    }

    public static void createSendToAllTrackingThreadedPacket(IMessage message, TargetPoint point) {
        NetworkHandler.INSTANCE.sendToAllAround(message, point);
    }

    public static void createSendToAllAroundThreadedPacket(IMessage message, TargetPoint point) {
        NetworkHandler.INSTANCE.sendToAllAround(message, point);
    }

    public static void createSendToThreadedPacket(IMessage message, net.minecraft.entity.player.EntityPlayerMP player) {
        NetworkHandler.INSTANCE.sendTo(message, player);
    }
}

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

package com.hbmzz.network;

import com.hbmzz.HBMZZ;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class NetworkHandler {

    public static SimpleNetworkWrapper INSTANCE;

    private NetworkHandler() {
    }

    public static void init() {
        INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(HBMZZ.MODID);
        INSTANCE.registerMessage(DFCRequestMessage.Handler.class, DFCRequestMessage.class, 0, Side.SERVER);
        INSTANCE.registerMessage(DFCSizeMessage.Handler.class, DFCSizeMessage.class, 1, Side.SERVER);
        // Registered on both sides so the server can serialize it; the handler
        // touches client classes only through a reflective bridge.
        INSTANCE.registerMessage(DFCDataMessage.Handler.class, DFCDataMessage.class, 2, Side.CLIENT);
        INSTANCE.registerMessage(JetpackThrustMessage.Handler.class, JetpackThrustMessage.class, 3, Side.SERVER);
        INSTANCE.registerMessage(GunTriggerMessage.Handler.class, GunTriggerMessage.class, 4, Side.SERVER);
        INSTANCE.registerMessage(DFCScreenMessage.Handler.class, DFCScreenMessage.class, 5, Side.SERVER);
        INSTANCE.registerMessage(DFCPageMessage.Handler.class, DFCPageMessage.class, 6, Side.SERVER);
        INSTANCE.registerMessage(DFCTiltMessage.Handler.class, DFCTiltMessage.class, 7, Side.SERVER);
        // Whole-design save from the panel studio. Server-authoritative: the
        // design is world state, so it cannot live in the editor window.
        INSTANCE.registerMessage(PanelEditMessage.Handler.class, PanelEditMessage.class, 8, Side.SERVER);
        // Machine control panel: turn a control, save a design.
        INSTANCE.registerMessage(CtrlUseMessage.Handler.class, CtrlUseMessage.class, 9, Side.SERVER);
        INSTANCE.registerMessage(CtrlDesignMessage.Handler.class, CtrlDesignMessage.class, 10, Side.SERVER);
        // Cursed Edition style console: one NBT blob up, one delta/full state down.
        INSTANCE.registerMessage(com.hbmzz.cecp.net.NBTControlPacket.Handler.class,
                com.hbmzz.cecp.net.NBTControlPacket.class, 11, Side.SERVER);
        INSTANCE.registerMessage(com.hbmzz.cecp.net.ControlPanelUpdatePacket.Handler.class,
                com.hbmzz.cecp.net.ControlPanelUpdatePacket.class, 12, Side.CLIENT);
        // Video broadcaster: settings up, "play this clip" down. The clip itself
        // never travels - the client already has it in config/hbmzz/videos/.
        INSTANCE.registerMessage(VideoConfigMessage.Handler.class, VideoConfigMessage.class, 13, Side.SERVER);
        INSTANCE.registerMessage(VideoBroadcastMessage.Handler.class, VideoBroadcastMessage.class, 14, Side.CLIENT);
        // Panel terminal: one typed line up, the lines it printed back down.
        INSTANCE.registerMessage(TerminalInputMessage.Handler.class, TerminalInputMessage.class, 15, Side.SERVER);
        INSTANCE.registerMessage(TerminalReplyMessage.Handler.class, TerminalReplyMessage.class, 16, Side.CLIENT);
        // RBMK shockwave: position, strength, duration, and whether the client
        // should also animate a dust ring. The ring is drawn locally.
        INSTANCE.registerMessage(ShakeMessage.Handler.class, ShakeMessage.class, 17, Side.CLIENT);
        // Cinematic camera: start orbiting this, or stop. Two packets per event.
        INSTANCE.registerMessage(CinematicMessage.Handler.class, CinematicMessage.class, 18, Side.CLIENT);
    }

    public static void sendTo(EntityPlayerMP player, IMessage message) {
        if (INSTANCE != null) {
            INSTANCE.sendTo(message, player);
        }
    }

    public static void sendToServer(IMessage message) {
        if (INSTANCE != null) {
            INSTANCE.sendToServer(message);
        }
    }
}
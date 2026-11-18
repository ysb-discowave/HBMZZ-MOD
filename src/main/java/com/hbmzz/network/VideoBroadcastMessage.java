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

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * "Take over this player's screen with clip X."
 *
 * <p>Twenty bytes on the wire, even though it produces a full-screen picture.
 * The frames are not sent: they are already on the client, in its own
 * {@code config/hbmzz/videos/} folder, and the packet only says which one. That
 * is the same bargain resource packs make, and it is the only one that stays
 * affordable - a 30 second 480p clip is tens of megabytes, which is several
 * minutes of a player's bandwidth per broadcast.</p>
 *
 * <p>The name is not trusted by the receiver either: see
 * {@link com.hbmzz.video.VideoLibrary#isValidName}.</p>
 */
public class VideoBroadcastMessage implements IMessage {

    private String video = "";

    public VideoBroadcastMessage() {
    }

    public VideoBroadcastMessage(String video) {
        this.video = video == null ? "" : video;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.video = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.video);
    }

    /**
     * Loadable on a dedicated server because it is what registers the
     * discriminator there; all client work goes through reflection, exactly like
     * {@link DFCDataMessage}.
     */
    public static class Handler implements IMessageHandler<VideoBroadcastMessage, IMessage> {

        @Override
        public IMessage onMessage(VideoBroadcastMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientVideoBridge")
                            .getMethod("onBroadcast", String.class)
                            .invoke(null, message.video);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}

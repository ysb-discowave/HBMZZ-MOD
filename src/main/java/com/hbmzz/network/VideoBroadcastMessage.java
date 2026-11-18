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
import net.minecraft.util.math.BlockPos;
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

    private BlockPos pos = BlockPos.ORIGIN;
    private String video = "";
    private int volume = 100;
    private boolean restrictBack = true;

    public VideoBroadcastMessage() {
    }

    public VideoBroadcastMessage(BlockPos pos, String video, int volume, boolean restrictBack) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos.toImmutable();
        this.video = video == null ? "" : video;
        this.volume = volume;
        this.restrictBack = restrictBack;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.video = ByteBufUtils.readUTF8String(buf);
        this.volume = buf.readShort();
        this.restrictBack = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        ByteBufUtils.writeUTF8String(buf, this.video);
        buf.writeShort(this.volume);
        buf.writeBoolean(this.restrictBack);
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
                            .getMethod("onBroadcast", BlockPos.class, String.class, int.class, boolean.class)
                            .invoke(null, message.pos, message.video, message.volume, message.restrictBack);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}

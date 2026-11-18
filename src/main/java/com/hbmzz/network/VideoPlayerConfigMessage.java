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

import com.hbmzz.tile.TileEntityVideoBroadcaster;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Volume and the "block exit until the film ends" switch, for one broadcaster.
 *
 * <p>These two settings live on the player, not on the wall, because they are
 * about the film rather than about the panel it is shown on: the same clip on a
 * wall and on a full-screen takeover should sound the same, and the exit lock is
 * a property of the broadcast, not of a screen that may not even exist.</p>
 */
public class VideoPlayerConfigMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int volume = 100;
    private boolean restrictBack = true;

    public VideoPlayerConfigMessage() {
    }

    public VideoPlayerConfigMessage(BlockPos pos, int volume, boolean restrictBack) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.volume = volume;
        this.restrictBack = restrictBack;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.volume = buf.readShort();
        this.restrictBack = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeShort(this.volume);
        buf.writeBoolean(this.restrictBack);
    }

    public static class Handler implements IMessageHandler<VideoPlayerConfigMessage, IMessage> {

        @Override
        public IMessage onMessage(VideoPlayerConfigMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP sender = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int volume = message.volume;
            final boolean restrictBack = message.restrictBack;
            sender.mcServer.addScheduledTask(() -> {
                if (sender.getDistanceSqToCenter(pos) > 10.0D * 10.0D) {
                    return;
                }
                TileEntity te = sender.world.getTileEntity(pos);
                if (!(te instanceof TileEntityVideoBroadcaster)) {
                    return;
                }
                TileEntityVideoBroadcaster broadcaster = (TileEntityVideoBroadcaster) te;
                broadcaster.applyPlayback(volume, restrictBack);
                sender.sendStatusMessage(new TextComponentString(
                        "播放器：音量 " + broadcaster.getVolume() + "%"
                                + (broadcaster.restrictBackApplies()
                                        ? " · 未接大屏，播放结束前禁止返回"
                                        : broadcaster.isRestrictBack()
                                        ? " · 已接大屏，本项不生效"
                                        : " · 允许 ESC 退出")), true);
            });
            return null;
        }
    }
}

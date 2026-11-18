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
import com.hbmzz.tile.TileEntityVideoScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * "Bind that wall to that broadcaster", sent by the 大屏遥控器.
 *
 * <p>Both positions come from the client, so both are checked here: the player
 * must still be within reach of the screen, both blocks must still be what the
 * client thought they were, and the broadcaster must be within
 * {@link #MAX_DISTANCE} of the wall. The last one is the interesting check - a
 * remote that can bind a screen on the other side of the world is not a
 * connection, it is a hidden wire with no length.</p>
 */
public class VideoLinkMessage implements IMessage {

    /** Furthest a broadcaster may sit from the wall it feeds. */
    public static final int MAX_DISTANCE = 64;

    private BlockPos screen = BlockPos.ORIGIN;
    private BlockPos player = BlockPos.ORIGIN;

    public VideoLinkMessage() {
    }

    public VideoLinkMessage(BlockPos screen, BlockPos player) {
        this.screen = screen == null ? BlockPos.ORIGIN : screen;
        this.player = player == null ? BlockPos.ORIGIN : player;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.screen = BlockPos.fromLong(buf.readLong());
        this.player = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.screen.toLong());
        buf.writeLong(this.player.toLong());
    }

    public static class Handler implements IMessageHandler<VideoLinkMessage, IMessage> {

        @Override
        public IMessage onMessage(VideoLinkMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP sender = ctx.getServerHandler().player;
            final BlockPos screenPos = message.screen;
            final BlockPos playerPos = message.player;
            sender.mcServer.addScheduledTask(() -> {
                String status;
                if (sender.getDistanceSqToCenter(screenPos) > 12.0D * 12.0D) {
                    status = "大屏太远，够不着";
                } else if (screenPos.distanceSq(playerPos) > (double) MAX_DISTANCE * MAX_DISTANCE) {
                    status = "播放器离大屏太远（上限 " + MAX_DISTANCE + " 格）";
                } else {
                    TileEntity screenTe = sender.world.getTileEntity(screenPos);
                    TileEntity playerTe = sender.world.getTileEntity(playerPos);
                    if (!(screenTe instanceof TileEntityVideoScreen)) {
                        status = "那块大屏已经不在了";
                    } else if (!(playerTe instanceof TileEntityVideoBroadcaster)) {
                        status = "那个播放器已经不在了";
                    } else {
                        TileEntityVideoScreen screen = (TileEntityVideoScreen) screenTe;
                        TileEntityVideoBroadcaster player = (TileEntityVideoBroadcaster) playerTe;
                        screen.setPlayerPos(playerPos);
                        screen.setVideo(player.getVideo());
                        screen.setVolume(player.getVolume());
                        screen.setPlaying(false);
                        player.setScreenPos(screenPos);
                        status = player.getVideo().isEmpty()
                                ? "大屏已接上播放器，但它还没设置视频"
                                : "大屏已接上播放器：" + player.getVideo();
                    }
                }
                sender.sendStatusMessage(new TextComponentString(status), true);
            });
            return null;
        }
    }
}

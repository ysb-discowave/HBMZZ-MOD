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

import com.hbmzz.HBMZZConfig;
import com.hbmzz.tile.TileEntityVideoBroadcaster;
import com.hbmzz.video.VideoBroadcast;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Saves the two fields of a broadcaster, or test-fires them without saving.
 *
 * <p>The window's own checks are a convenience; this is the boundary. A client
 * can send anything, so the arriving name is re-validated against the whitelist
 * and the selector is parsed here again. That matters more than usual: the
 * text lands in a block that will later order an arbitrary player's screen
 * replaced, so it is not a place to trust an editor's opinion of its own
 * input.</p>
 */
public class VideoConfigMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private String video = "";
    private String selector = "";
    private boolean test;

    public VideoConfigMessage() {
    }

    public VideoConfigMessage(BlockPos pos, String video, String selector, boolean test) {
        this.pos = pos;
        this.video = video == null ? "" : video;
        this.selector = selector == null ? "" : selector;
        this.test = test;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.video = ByteBufUtils.readUTF8String(buf);
        this.selector = ByteBufUtils.readUTF8String(buf);
        this.test = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        ByteBufUtils.writeUTF8String(buf, this.video);
        ByteBufUtils.writeUTF8String(buf, this.selector);
        buf.writeBoolean(this.test);
    }

    public static class Handler implements IMessageHandler<VideoConfigMessage, IMessage> {

        @Override
        public IMessage onMessage(VideoConfigMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final String video = message.video;
            final String selector = message.selector;
            final boolean test = message.test;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 8.0D * 8.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (!(te instanceof TileEntityVideoBroadcaster)) {
                    return;
                }
                String status;
                TileEntityVideoBroadcaster broadcaster = (TileEntityVideoBroadcaster) te;
                if (HBMZZConfig.videoOperatorOnly && !player.canUseCommand(2, "hbmzz.video")) {
                    status = "服务器只允许管理员使用视频广播器";
                } else if (test) {
                    // Same call the redstone edge makes, so the test cannot
                    // pass while the real trigger fails.
                    status = VideoBroadcast.fire(player.world, pos, video, selector);
                } else if (!broadcaster.applyConfig(video, selector)) {
                    status = "视频名不合法，只能是字母、数字、下划线、点和短横";
                } else {
                    status = "已保存：" + (video.isEmpty() ? "（无视频）" : video)
                            + " → " + (selector.isEmpty() ? "（无目标）" : selector);
                }
                broadcaster.setStatus(status);
                player.sendStatusMessage(new TextComponentString(status), true);
            });
            return null;
        }
    }
}

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

import com.hbmzz.tile.TileEntityCodeBroadcaster;
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
 * Saves a broadcaster's custom-state settings, server-authoritatively.
 *
 * <p>The window's own checks are a convenience; this is the boundary. A client
 * can send anything, so the level is re-clamped to 0..7 and the texts re-trimmed
 * here, exactly the way the tile entity would have done it to itself. That keeps
 * a saved block honest no matter what the editor window let through.</p>
 */
public class CustomCodeConfigMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private boolean active;
    private int level;
    private String label = "";
    private int rgb;
    private String zhTitle = "";
    private String enTitle = "";
    private String zhDetail = "";
    private String enDetail = "";

    public CustomCodeConfigMessage() {
    }

    public CustomCodeConfigMessage(BlockPos pos, boolean active, int level, String label, int rgb,
                                   String zhTitle, String enTitle, String zhDetail, String enDetail) {
        this.pos = pos;
        this.active = active;
        this.level = level;
        this.label = label == null ? "" : label;
        this.rgb = rgb;
        this.zhTitle = zhTitle == null ? "" : zhTitle;
        this.enTitle = enTitle == null ? "" : enTitle;
        this.zhDetail = zhDetail == null ? "" : zhDetail;
        this.enDetail = enDetail == null ? "" : enDetail;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.active = buf.readBoolean();
        this.level = buf.readInt();
        this.label = ByteBufUtils.readUTF8String(buf);
        this.rgb = buf.readInt();
        this.zhTitle = ByteBufUtils.readUTF8String(buf);
        this.enTitle = ByteBufUtils.readUTF8String(buf);
        this.zhDetail = ByteBufUtils.readUTF8String(buf);
        this.enDetail = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeBoolean(this.active);
        buf.writeInt(this.level);
        ByteBufUtils.writeUTF8String(buf, this.label);
        buf.writeInt(this.rgb);
        ByteBufUtils.writeUTF8String(buf, this.zhTitle);
        ByteBufUtils.writeUTF8String(buf, this.enTitle);
        ByteBufUtils.writeUTF8String(buf, this.zhDetail);
        ByteBufUtils.writeUTF8String(buf, this.enDetail);
    }

    public static class Handler implements IMessageHandler<CustomCodeConfigMessage, IMessage> {

        @Override
        public IMessage onMessage(CustomCodeConfigMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final boolean active = message.active;
            final int level = message.level;
            final String label = message.label;
            final int rgb = message.rgb;
            final String zhTitle = message.zhTitle;
            final String enTitle = message.enTitle;
            final String zhDetail = message.zhDetail;
            final String enDetail = message.enDetail;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 8.0D * 8.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (!(te instanceof TileEntityCodeBroadcaster)) {
                    return;
                }
                TileEntityCodeBroadcaster broadcaster = (TileEntityCodeBroadcaster) te;
                boolean ok = broadcaster.applyCustom(active, level, label, rgb,
                        zhTitle, enTitle, zhDetail, enDetail);
                String status = ok
                        ? "自定义状态已保存：" + (active ? "启用，等级 " + level : "停用（改用预设）")
                        : "等级必须是 0 到 7 之间";
                player.sendStatusMessage(new TextComponentString(status), true);
            });
            return null;
        }
    }
}

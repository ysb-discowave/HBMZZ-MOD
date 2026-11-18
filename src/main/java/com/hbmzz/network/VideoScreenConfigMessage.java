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
 * Size and playback rate of one wall, from its configuration window.
 *
 * <p>Size arrives as two integers and speed as a float, and all three are clamped
 * again on this side: the window's steppers are the convenient path, not the
 * boundary. A wall is a physical thing - it places and removes blocks - so the
 * server has to be the one deciding how far 1..32 goes.</p>
 */
public class VideoScreenConfigMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int cols = 1;
    private int rows = 1;
    private float speed = 1.0F;

    public VideoScreenConfigMessage() {
    }

    public VideoScreenConfigMessage(BlockPos pos, int cols, int rows, float speed) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.cols = cols;
        this.rows = rows;
        this.speed = speed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.cols = buf.readShort();
        this.rows = buf.readShort();
        this.speed = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeShort(this.cols);
        buf.writeShort(this.rows);
        buf.writeFloat(this.speed);
    }

    public static class Handler implements IMessageHandler<VideoScreenConfigMessage, IMessage> {

        @Override
        public IMessage onMessage(VideoScreenConfigMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP sender = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int cols = message.cols;
            final int rows = message.rows;
            final float speed = message.speed;
            sender.mcServer.addScheduledTask(() -> {
                if (sender.getDistanceSqToCenter(pos) > 10.0D * 10.0D) {
                    return;
                }
                TileEntity te = sender.world.getTileEntity(pos);
                if (!(te instanceof TileEntityVideoScreen)) {
                    return;
                }
                TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
                screen.applySize(cols, rows);
                screen.applySpeed(speed);
                sender.sendStatusMessage(new TextComponentString(
                        "大屏：" + screen.getCols() + " × " + screen.getRows()
                                + " · " + formatSpeed(screen.getSpeed())), true);
            });
            return null;
        }
    }

    /** "1.25x" / "1x", without the locale's decimal comma and without trailing zeros. */
    public static String formatSpeed(float speed) {
        String text = String.format(java.util.Locale.ROOT, "%.2f", speed);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "x";
    }
}

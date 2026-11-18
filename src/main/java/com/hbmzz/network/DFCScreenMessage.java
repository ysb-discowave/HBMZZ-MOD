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

import com.hbmzz.tile.TileEntityDFCMonitor;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client request to change how many blocks the wall panel spans. The server is
 * authoritative so two players editing different terminals cannot desync the
 * stored value.
 */
public class DFCScreenMessage implements IMessage {

    private BlockPos pos;
    private int cols;
    private int rows;

    public DFCScreenMessage() {
    }

    public DFCScreenMessage(BlockPos pos, int cols, int rows) {
        this.pos = pos;
        this.cols = cols;
        this.rows = rows;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.cols = buf.readByte();
        this.rows = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeByte(this.cols);
        buf.writeByte(this.rows);
    }

    public static class Handler implements IMessageHandler<DFCScreenMessage, IMessage> {

        @Override
        public IMessage onMessage(DFCScreenMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int cols = message.cols;
            final int rows = message.rows;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 64.0D * 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (te instanceof TileEntityDFCMonitor) {
                    TileEntityDFCMonitor monitor = (TileEntityDFCMonitor) te;
                    monitor.setScreenSize(cols, rows);
                    // Growing the panel has to fill the new cells as well;
                    // without this a 2x2 panel resized to 4x3 would render over
                    // ordinary air that is not part of the structure.
                    net.minecraft.block.state.IBlockState state = player.world.getBlockState(pos);
                    if (state.getBlock() instanceof com.hbmzz.block.BlockDFCMonitor) {
                        com.hbmzz.block.BlockDFCMonitor.ensureParts(player.world, pos,
                                state.getValue(com.hbmzz.block.BlockDFCMonitor.FACING),
                                monitor.getScreenCols(), monitor.getScreenRows());
                    }
                    NetworkHandler.sendTo(player, new DFCDataMessage(pos, monitor.snapshot()));
                }
            });
            return null;
        }
    }
}

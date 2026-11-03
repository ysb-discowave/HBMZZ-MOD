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
 * Client request to change which page the monitor shows, or to flip its power
 * switch.
 *
 * <p>Server-authoritative for the same reason the panel size is: the value is
 * stored in the tile entity and pushed to every nearby player, so two people
 * with the terminal open must not each keep their own idea of the page.</p>
 */
public class DFCPageMessage implements IMessage {

    public static final int ACTION_PAGE = 0;
    public static final int ACTION_POWER = 1;

    private BlockPos pos;
    private int action;
    private int value;

    public DFCPageMessage() {
    }

    public DFCPageMessage(BlockPos pos, int action, int value) {
        this.pos = pos;
        this.action = action;
        this.value = value;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.value = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeByte(this.action);
        buf.writeByte(this.value);
    }

    public static class Handler implements IMessageHandler<DFCPageMessage, IMessage> {

        @Override
        public IMessage onMessage(DFCPageMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int action = message.action;
            final int value = message.value;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 64.0D * 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (!(te instanceof TileEntityDFCMonitor)) {
                    return;
                }
                TileEntityDFCMonitor monitor = (TileEntityDFCMonitor) te;
                if (action == ACTION_POWER) {
                    monitor.setPowered(value != 0);
                } else {
                    monitor.setPage(value);
                }
                NetworkHandler.sendTo(player, new DFCDataMessage(pos, monitor.snapshot()));
            });
            return null;
        }
    }
}

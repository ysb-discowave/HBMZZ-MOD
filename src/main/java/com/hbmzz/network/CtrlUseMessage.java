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

import com.hbmzz.tile.TileEntityCtrlPanel;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * "The operator turned control #3 to 42."
 *
 * <p>Only the control index and the requested value travel. The client does not
 * send the event name, the variable or the target machine: those live in the
 * server's copy of the panel, so a modified client can turn a knob but cannot
 * turn it into an arbitrary command for an arbitrary block. That is the whole
 * reason the panel's design is world state rather than a client-side document.</p>
 */
public class CtrlUseMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int index = -1;
    private double value;

    public CtrlUseMessage() {
    }

    public CtrlUseMessage(BlockPos pos, int index, double value) {
        this.pos = pos;
        this.index = index;
        this.value = value;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.index = buf.readShort();
        this.value = buf.readDouble();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeShort(this.index);
        buf.writeDouble(this.value);
    }

    public static class Handler implements IMessageHandler<CtrlUseMessage, IMessage> {

        @Override
        public IMessage onMessage(CtrlUseMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            final EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int index = message.index;
            final double value = message.value;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 8.0D * 8.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (te instanceof TileEntityCtrlPanel) {
                    ((TileEntityCtrlPanel) te).operate(index, value);
                }
            });
            return null;
        }
    }
}

/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.network;

import com.hbmzz.aegis.TileAegisTerminal;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Client to server: a page switch or power toggle initiated at the terminal. */
public final class AegisControlPacket implements IMessage {

    private BlockPos pos;
    private int page;
    private boolean powered;

    public AegisControlPacket() {
    }

    public AegisControlPacket(BlockPos pos, int page, boolean powered) {
        this.pos = pos;
        this.page = page;
        this.powered = powered;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.page = buf.readInt();
        this.powered = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeInt(this.page);
        buf.writeBoolean(this.powered);
    }

    public static final class Handler implements IMessageHandler<AegisControlPacket, IMessage> {
        @Override
        public IMessage onMessage(AegisControlPacket message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos at = message.pos;
            final int page = message.page;
            final boolean powered = message.powered;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(at) > 12.0D * 12.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(at);
                if (!(te instanceof TileAegisTerminal)) {
                    return;
                }
                TileAegisTerminal terminal = (TileAegisTerminal) te;
                terminal.setPage(page);
                terminal.setPowered(powered);
            });
            return null;
        }
    }
}

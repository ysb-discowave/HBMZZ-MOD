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

/** Client to server: ask for an immediate frame (used as a catch-up pull). */
public final class AegisRequestPacket implements IMessage {

    private BlockPos pos;

    public AegisRequestPacket() {
    }

    public AegisRequestPacket(BlockPos pos) {
        this.pos = pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
    }

    public static final class Handler implements IMessageHandler<AegisRequestPacket, IMessage> {
        @Override
        public IMessage onMessage(AegisRequestPacket message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos at = message.pos;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(at) > 96.0D * 96.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(at);
                if (!(te instanceof TileAegisTerminal)) {
                    return;
                }
                com.hbmzz.aegis.AegisTelemetry frame = ((TileAegisTerminal) te).sampleNow();
                if (frame != null) {
                    NetworkHandler.sendTo(player, new AegisDataPacket(at, frame));
                }
            });
            return null;
        }
    }
}

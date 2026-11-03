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

import com.hbmzz.HBMZZ;
import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.tile.TileEntityDFCMonitor;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class DFCRequestMessage implements IMessage {

    private BlockPos pos;

    public DFCRequestMessage() {
    }

    public DFCRequestMessage(BlockPos pos) {
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

    public static class Handler implements IMessageHandler<DFCRequestMessage, IMessage> {

        @Override
        public IMessage onMessage(DFCRequestMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            // SimpleImpl runs handlers off the main thread; walking the world from
            // here can trip over the tile entity list being modified. Hop over.
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 64.0D * 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (!(te instanceof TileEntityDFCMonitor)) {
                    return;
                }
                DFCSnapshot sample;
                try {
                    sample = ((TileEntityDFCMonitor) te).snapshot();
                } catch (Throwable throwable) {
                    // snapshot() reads a third party's internals by reflection.
                    // Throwing from a task queued on the server thread would take
                    // the whole tick loop down, so a failed read just leaves the
                    // client polling - which is exactly what it already does.
                    HBMZZ.logger.error("DFC monitor at " + pos + " could not answer a pull", throwable);
                    return;
                }
                NetworkHandler.sendTo(player, new DFCDataMessage(pos, sample));
            });
            return null;
        }
    }
}
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
import com.hbmzz.tile.TileEntityDFCMonitor;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Client request to tip the console to a different notch.
 *
 * <p>The posture is part of the machine, not part of anyone's view, so it lives
 * on the server: two players standing in front of the same screen must see it
 * at the same angle. Kept separate from {@link DFCScreenMessage} so the terminal
 * can send one without having to restate the other.</p>
 */
public class DFCTiltMessage implements IMessage {

    private BlockPos pos;
    private int tilt;

    public DFCTiltMessage() {
    }

    public DFCTiltMessage(BlockPos pos, int tilt) {
        this.pos = pos;
        this.tilt = tilt;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.tilt = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        buf.writeByte(this.tilt);
    }

    public static class Handler implements IMessageHandler<DFCTiltMessage, IMessage> {

        @Override
        public IMessage onMessage(DFCTiltMessage message, MessageContext ctx) {
            if (!ctx.side.isServer()) {
                return null;
            }
            EntityPlayerMP player = ctx.getServerHandler().player;
            final BlockPos pos = message.pos;
            final int tilt = message.tilt;
            player.mcServer.addScheduledTask(() -> {
                if (player.getDistanceSqToCenter(pos) > 64.0D * 64.0D) {
                    return;
                }
                TileEntity te = player.world.getTileEntity(pos);
                if (!(te instanceof TileEntityDFCMonitor)) {
                    return;
                }
                try {
                    ((TileEntityDFCMonitor) te).setTilt(tilt);
                } catch (Throwable throwable) {
                    // This runs on the server tick thread; letting anything out
                    // of here takes the whole world down, not just the screen.
                    HBMZZ.logger.error("DFC monitor at " + pos + " could not change tilt", throwable);
                }
            });
            return null;
        }
    }
}

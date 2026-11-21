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

import com.hbmzz.aegis.AegisTelemetry;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** Server to client: one telemetry frame for one terminal. */
public final class AegisDataPacket implements IMessage {

    private BlockPos pos;
    private AegisTelemetry frame;

    public AegisDataPacket() {
    }

    public AegisDataPacket(BlockPos pos, AegisTelemetry frame) {
        this.pos = pos;
        this.frame = frame;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.frame = AegisTelemetry.decode(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        this.frame.encode(buf);
    }

    public static final class Handler implements IMessageHandler<AegisDataPacket, IMessage> {
        @Override
        public IMessage onMessage(AegisDataPacket message, MessageContext ctx) {
            if (!ctx.side.isClient()) {
                return null;
            }
            // This handler also lives on a dedicated server. Reach the client
            // world only through a reflection bridge so no client class is
            // linked here, mirroring the other telemetry packets in this mod.
            try {
                Class.forName("com.hbmzz.aegis.client.AegisClientBridge")
                        .getMethod("onData", BlockPos.class, AegisTelemetry.class)
                        .invoke(null, message.pos, message.frame);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}

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

import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Server to client: open the Aegis terminal's settings window.
 *
 * <p>Emitted after the link tool writes a core binding so the operator goes
 * straight into the geometry menu. The menu itself is client-only, so the
 * server asks for it through a reflection bridge — the same trick the other
 * client-side packets use, which keeps client classes off the server's class
 * path on a dedicated server.</p>
 */
public final class AegisOpenGuiPacket implements IMessage {

    private BlockPos pos;

    public AegisOpenGuiPacket() {
    }

    public AegisOpenGuiPacket(BlockPos pos) {
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

    public static final class Handler implements IMessageHandler<AegisOpenGuiPacket, IMessage> {
        @Override
        public IMessage onMessage(AegisOpenGuiPacket message, MessageContext ctx) {
            if (!ctx.side.isClient()) {
                return null;
            }
            try {
                Class.forName("com.hbmzz.aegis.client.AegisClientBridge")
                        .getMethod("openAegisGui", BlockPos.class)
                        .invoke(null, message.pos);
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}

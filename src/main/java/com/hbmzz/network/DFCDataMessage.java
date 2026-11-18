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

import com.hbmzz.dfc.DFCSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Telemetry sample tagged with the monitor it belongs to. The position matters
 * because the same message feeds the live block face, the HUD and the terminal,
 * and the client has to know which monitor a sample is for.
 */
public class DFCDataMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private DFCSnapshot snapshot;

    public DFCDataMessage() {
    }

    public DFCDataMessage(BlockPos pos, DFCSnapshot snapshot) {
        this.pos = pos;
        this.snapshot = snapshot;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.snapshot = DFCSnapshot.read(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        this.snapshot.write(buf);
    }

    /**
     * The handler itself must be loadable on a dedicated server (it is needed to
     * register the discriminator for outgoing packets), so all client-only work
     * is isolated behind a reflective call.
     */
    public static class Handler implements IMessageHandler<DFCDataMessage, IMessage> {

        @Override
        public IMessage onMessage(DFCDataMessage message, MessageContext ctx) {
            if (ctx.side.isClient() && message.snapshot != null) {
                try {
                    Class.forName("com.hbmzz.client.ClientPacketBridge")
                            .getMethod("onData", BlockPos.class, DFCSnapshot.class)
                            .invoke(null, message.pos, message.snapshot);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}

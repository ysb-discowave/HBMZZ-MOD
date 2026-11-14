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

import com.hbmzz.alert.NukeSnapshot;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * One nuke monitor's readout, to the players close enough to read it.
 *
 * <p>Carries the monitor's own position as well as the sample: several monitors
 * can be watching the same device, and the client has to know which tile entity
 * to hand the sample to. The block face, and any window that later wants the
 * same numbers, then read one place.</p>
 */
public class NukeDataMessage implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private NukeSnapshot snapshot = new NukeSnapshot();

    public NukeDataMessage() {
    }

    public NukeDataMessage(BlockPos pos, NukeSnapshot snapshot) {
        this.pos = pos;
        this.snapshot = snapshot == null ? new NukeSnapshot() : snapshot;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public NukeSnapshot snapshot() {
        return this.snapshot;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.snapshot = NukeSnapshot.read(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(this.pos.toLong());
        this.snapshot.write(buf);
    }

    /** Client-only delivery, behind a reflective call for dedicated servers. */
    public static class Handler implements IMessageHandler<NukeDataMessage, IMessage> {

        @Override
        public IMessage onMessage(NukeDataMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientPacketBridge")
                            .getMethod("onNukeData", NukeDataMessage.class)
                            .invoke(null, message);
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}

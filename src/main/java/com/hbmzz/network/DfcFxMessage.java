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

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * One emission of the auto-shutdown's particle show.
 *
 * <h3>Why the particles are spawned on the client</h3>
 * <p>The server cannot draw: it can only ask vanilla to broadcast one of its
 * sixteen particle types, and none of them is blue. The alternative is to send
 * positions and let the client pick the colour, which also means the client can
 * push the particles inward for the suction rings - a server-side spawn has no
 * way to aim a velocity.</p>
 *
 * <p>One packet describes a whole burst, not one particle: the client decides
 * how many points to place, so a 48-particle implosion costs 25 bytes. The
 * packet is sent at most every other tick and only to players close enough to
 * see the core, which is what keeps a minute-long effect from being a bandwidth
 * problem.</p>
 */
public class DfcFxMessage implements IMessage {

    /** Ambient blue cloud while the core is collapsing. */
    public static final byte AURA = 0;
    /** An outward ring of sparks, the "energy burst". */
    public static final byte BURST = 1;
    /** A contracting ring; the radius is what shrinks. */
    public static final byte RING = 2;
    /** Everything at once, pulled into the core. Fired when the retraction starts. */
    public static final byte IMPLODE = 3;
    /** Enhanced collapse: dense multi-colour storm. Only sent when the monitor's enhanced FX is on. */
    public static final byte ENHANCED_COLLAPSE = 4;
    /** Totem-of-undying style burst with the HBMZZ logo. Fired at the explosion moment. */
    public static final byte TOTEM_EXPLODE = 5;

    private byte mode;
    private double x;
    private double y;
    private double z;
    private float radius;
    /** 0..1 through the current phase, so the client can fade or tint. */
    private float progress;

    public DfcFxMessage() {
    }

    public DfcFxMessage(byte mode, double x, double y, double z, float radius, float progress) {
        this.mode = mode;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.progress = progress;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.mode = buf.readByte();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.radius = buf.readFloat();
        this.progress = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.mode);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeFloat(this.radius);
        buf.writeFloat(this.progress);
    }

    public static class Handler implements IMessageHandler<DfcFxMessage, IMessage> {

        @Override
        public IMessage onMessage(DfcFxMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientDfcFx")
                            .getMethod("onMessage", byte.class, double.class, double.class, double.class,
                                    float.class, float.class)
                            .invoke(null, message.mode, message.x, message.y, message.z,
                                    message.radius, message.progress);
                } catch (Throwable ignored) {
                    // A missing effect is survivable; a crash on an effect packet is not.
                }
            }
            return null;
        }
    }
}

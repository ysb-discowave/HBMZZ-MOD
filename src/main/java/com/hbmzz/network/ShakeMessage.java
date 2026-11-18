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
 * "Something went off here, at this strength, for this long."
 *
 * <p>37 bytes, sent once per blast, and the client does the rest: camera roll,
 * hotbar displacement, and the expanding dust ring are all generated locally
 * from these numbers. Animating the ring on the server would mean a particle
 * packet per point per tick per player - the same effect for thousands of times
 * the bandwidth, with the additional problem that a lagging connection would
 * make the shockwave arrive after the crater.</p>
 *
 * <p>{@code ring} is separate from the strength because the two do not always
 * come together: the rod impacts shake without throwing dust, the lid hitting
 * the ground does both.</p>
 */
public class ShakeMessage implements IMessage {

    private double x;
    private double y;
    private double z;
    private float intensity;
    private float range;
    private int duration;
    private boolean ring;

    public ShakeMessage() {
    }

    public ShakeMessage(double x, double y, double z, float intensity, float range, int duration, boolean ring) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.intensity = intensity;
        this.range = range;
        this.duration = duration;
        this.ring = ring;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.intensity = buf.readFloat();
        this.range = buf.readFloat();
        this.duration = buf.readInt();
        this.ring = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeFloat(this.intensity);
        buf.writeFloat(this.range);
        buf.writeInt(this.duration);
        buf.writeBoolean(this.ring);
    }

    /**
     * Loadable on a dedicated server: the handler is what registers the
     * discriminator there, and everything client-side goes through reflection,
     * the same arrangement {@link VideoBroadcastMessage} uses.
     */
    public static class Handler implements IMessageHandler<ShakeMessage, IMessage> {

        @Override
        public IMessage onMessage(ShakeMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientShake")
                            .getMethod("onShake", double.class, double.class, double.class,
                                    float.class, float.class, int.class, boolean.class)
                            .invoke(null, message.x, message.y, message.z,
                                    message.intensity, message.range, message.duration, message.ring);
                } catch (Throwable ignored) {
                    // A client that cannot shake is still a client that can play.
                }
            }
            return null;
        }
    }
}

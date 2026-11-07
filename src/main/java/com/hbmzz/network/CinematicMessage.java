/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3.
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
 * "Start orbiting this, and keep going until I say stop" - or stop.
 *
 * <p>Two packets per event: one at the start, one at the end. The orbit itself
 * is integrated on the client every tick, so a five-second shot costs 30 bytes
 * rather than 300 position updates - and, more importantly, it cannot stutter
 * when the connection does. A camera whose motion is driven by packets looks
 * exactly as bad as the network is.</p>
 *
 * <p>{@code radius}, {@code height} and {@code speed} are sent rather than read
 * from the client's own config, because the server may be running different
 * numbers and the shot has to match the machine the server knows about.</p>
 */
public class CinematicMessage implements IMessage {

    public static final byte STOP = 0;
    public static final byte START = 1;

    private byte action;
    private double x;
    private double y;
    private double z;
    private float radius;
    private float height;
    private float speed;

    public CinematicMessage() {
    }

    public CinematicMessage(byte action, double x, double y, double z, float radius, float height, float speed) {
        this.action = action;
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.height = height;
        this.speed = speed;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.action = buf.readByte();
        this.x = buf.readDouble();
        this.y = buf.readDouble();
        this.z = buf.readDouble();
        this.radius = buf.readFloat();
        this.height = buf.readFloat();
        this.speed = buf.readFloat();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(this.action);
        buf.writeDouble(this.x);
        buf.writeDouble(this.y);
        buf.writeDouble(this.z);
        buf.writeFloat(this.radius);
        buf.writeFloat(this.height);
        buf.writeFloat(this.speed);
    }

    public static class Handler implements IMessageHandler<CinematicMessage, IMessage> {

        @Override
        public IMessage onMessage(CinematicMessage message, MessageContext ctx) {
            if (ctx.side.isClient()) {
                try {
                    Class.forName("com.hbmzz.client.ClientCinematic")
                            .getMethod("onMessage", byte.class, double.class, double.class, double.class,
                                    float.class, float.class, float.class)
                            .invoke(null, message.action, message.x, message.y, message.z,
                                    message.radius, message.height, message.speed);
                } catch (Throwable ignored) {
                    // No camera is survivable; a crash on a camera packet is not.
                }
            }
            return null;
        }
    }
}

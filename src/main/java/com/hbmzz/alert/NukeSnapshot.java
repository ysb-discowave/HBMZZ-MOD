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

package com.hbmzz.alert;

import io.netty.buffer.ByteBuf;

/**
 * What a nuke monitor shows about its device, one sample at a time.
 *
 * <p>A flat value object with no references to the world, deliberately: the
 * monitor builds one on the server, it travels as-is, and the client paints it.
 * A monitor whose device is in a chunk that has since unloaded still paints the
 * last sample rather than going blank, and a device that no longer exists paints
 * {@code hasDevice == false} - which is a state the screen has a frame for,
 * because "the bomb is gone" is information, not a failure to render.</p>
 */
public class NukeSnapshot {

    /** No device at the bound position. */
    public static final int STATE_GONE = 0;
    /** Placed, idle, waiting for a signal. */
    public static final int STATE_IDLE = 1;
    /** Counting down to detonation. */
    public static final int STATE_COUNTDOWN = 2;

    public boolean hasDevice;
    public int x;
    public int y;
    public int z;
    public int dim;

    public int state = STATE_GONE;
    /** Yield in kilotons. */
    public int yield;
    /** Configured fuse for this device, in ticks. */
    public int fuse;
    /** Ticks left; 0 when not counting down. */
    public int countdown;

    public boolean hasController;
    public int cx;
    public int cy;
    public int cz;
    public int cdim;

    /** Server world tick this sample was taken at, so the client can show ages. */
    public long nowTick;

    public void write(ByteBuf buf) {
        buf.writeBoolean(this.hasDevice);
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        buf.writeInt(this.dim);
        buf.writeByte(this.state);
        buf.writeInt(this.yield);
        buf.writeInt(this.fuse);
        buf.writeInt(this.countdown);
        buf.writeBoolean(this.hasController);
        buf.writeInt(this.cx);
        buf.writeInt(this.cy);
        buf.writeInt(this.cz);
        buf.writeInt(this.cdim);
        buf.writeLong(this.nowTick);
    }

    public static NukeSnapshot read(ByteBuf buf) {
        NukeSnapshot s = new NukeSnapshot();
        s.hasDevice = buf.readBoolean();
        s.x = buf.readInt();
        s.y = buf.readInt();
        s.z = buf.readInt();
        s.dim = buf.readInt();
        s.state = buf.readByte();
        s.yield = buf.readInt();
        s.fuse = buf.readInt();
        s.countdown = buf.readInt();
        s.hasController = buf.readBoolean();
        s.cx = buf.readInt();
        s.cy = buf.readInt();
        s.cz = buf.readInt();
        s.cdim = buf.readInt();
        s.nowTick = buf.readLong();
        return s;
    }
}

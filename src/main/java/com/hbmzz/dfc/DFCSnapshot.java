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

package com.hbmzz.dfc;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/**
 * One sample of the DFC telemetry shown by the terminal.
 */
public class DFCSnapshot {

    public int panelW = 380;
    public int panelH = 240;
    public long tick;

    public boolean coreFound;
    public boolean hasCore;
    public boolean meterBound;
    public boolean collapsing;

    public String coreType = "NONE";
    public String fuel1Name = "EMPTY";
    public String fuel2Name = "EMPTY";

    public double temperature;
    public double meltingPoint = 2250.0D;
    public double stabilization;
    public double containedEnergy;
    public double expellingEnergy;
    public double potentialGain;

    public double fuel1Fill;
    public double fuel1Max = 1.0D;
    public double fuel2Fill;
    public double fuel2Max = 1.0D;

    public int stabilizers;
    public int absorbers;
    public int pulsers;
    public int emitters;
    public int injectors;
    public int exchangers;
    public int cemiters;

    /** Seconds until the core detonates, -1 when the core is stable. */
    public double explosionIn = -1.0D;
    /** Catalyst damage, 0..100. */
    public double damageA;
    public double damageB;

    public long meterPerTick;
    public long meterPerSecond;

    /** 0 = unknown, 1 = collapse countdown, 2 = predicted from catalyst damage. */
    public int predictionMode;
    public int predictedSeconds = -1;

    // ---- intrusion state ----------------------------------------------------
    //
    // Carried on the sample rather than on a separate packet on purpose: every
    // consumer of a sample (the block face, the HUD, the terminal, the window)
    // already paints from this object, so one field here makes all four agree
    // without a second round trip or a second source of truth.

    /**
     * {@code HackState} value for the core this sample came from: 0 normal,
     * 1 link lost, 2 degraded, 3 signal cut.
     */
    public int hackState;

    /** Ticks left in the current intrusion state, for the on-screen countdown. */
    public int hackTicksLeft;

    public void write(ByteBuf buf) {
        buf.writeInt(panelW);
        buf.writeInt(panelH);
        buf.writeLong(tick);
        buf.writeBoolean(coreFound);
        buf.writeBoolean(hasCore);
        buf.writeBoolean(meterBound);
        buf.writeBoolean(collapsing);
        ByteBufUtils.writeUTF8String(buf, coreType);
        ByteBufUtils.writeUTF8String(buf, fuel1Name);
        ByteBufUtils.writeUTF8String(buf, fuel2Name);
        buf.writeDouble(temperature);
        buf.writeDouble(meltingPoint);
        buf.writeDouble(stabilization);
        buf.writeDouble(containedEnergy);
        buf.writeDouble(expellingEnergy);
        buf.writeDouble(potentialGain);
        buf.writeDouble(fuel1Fill);
        buf.writeDouble(fuel1Max);
        buf.writeDouble(fuel2Fill);
        buf.writeDouble(fuel2Max);
        buf.writeInt(stabilizers);
        buf.writeInt(absorbers);
        buf.writeInt(pulsers);
        buf.writeInt(emitters);
        buf.writeInt(injectors);
        buf.writeInt(exchangers);
        buf.writeInt(cemiters);
        buf.writeDouble(explosionIn);
        buf.writeDouble(damageA);
        buf.writeDouble(damageB);
        buf.writeLong(meterPerTick);
        buf.writeLong(meterPerSecond);
        buf.writeInt(predictionMode);
        buf.writeInt(predictedSeconds);
        buf.writeByte(hackState);
        buf.writeInt(hackTicksLeft);
    }

    public static DFCSnapshot read(ByteBuf buf) {
        DFCSnapshot s = new DFCSnapshot();
        s.panelW = buf.readInt();
        s.panelH = buf.readInt();
        s.tick = buf.readLong();
        s.coreFound = buf.readBoolean();
        s.hasCore = buf.readBoolean();
        s.meterBound = buf.readBoolean();
        s.collapsing = buf.readBoolean();
        s.coreType = ByteBufUtils.readUTF8String(buf);
        s.fuel1Name = ByteBufUtils.readUTF8String(buf);
        s.fuel2Name = ByteBufUtils.readUTF8String(buf);
        s.temperature = buf.readDouble();
        s.meltingPoint = buf.readDouble();
        s.stabilization = buf.readDouble();
        s.containedEnergy = buf.readDouble();
        s.expellingEnergy = buf.readDouble();
        s.potentialGain = buf.readDouble();
        s.fuel1Fill = buf.readDouble();
        s.fuel1Max = buf.readDouble();
        s.fuel2Fill = buf.readDouble();
        s.fuel2Max = buf.readDouble();
        s.stabilizers = buf.readInt();
        s.absorbers = buf.readInt();
        s.pulsers = buf.readInt();
        s.emitters = buf.readInt();
        s.injectors = buf.readInt();
        s.exchangers = buf.readInt();
        s.cemiters = buf.readInt();
        s.explosionIn = buf.readDouble();
        s.damageA = buf.readDouble();
        s.damageB = buf.readDouble();
        s.meterPerTick = buf.readLong();
        s.meterPerSecond = buf.readLong();
        s.predictionMode = buf.readInt();
        s.predictedSeconds = buf.readInt();
        s.hackState = buf.readByte();
        s.hackTicksLeft = buf.readInt();
        return s;
    }
}
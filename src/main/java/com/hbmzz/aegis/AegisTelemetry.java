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

package com.hbmzz.aegis;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/**
 * Immutable-by-convention telemetry frame produced by {@link AegisProbe} and
 * shipped to the client once per refresh. Every value is a plain snapshot: the
 * reader never keeps a live reference to a foreign tile entity, so serializing
 * this object on the server thread is safe.
 *
 * <p>The three {@code trend*} arrays form a fixed-length ring. Index 0 is the
 * oldest retained sample, {@link #trendCount} is how many slots are populated.
 * Keeping the history in the frame itself means any late-joining viewer gets
 * the same graph as everybody else on the very first packet.</p>
 */
public final class AegisTelemetry {

    /** Number of retained history slots. */
    public static final int TREND_CAPACITY = 64;
    /** Number of selectable pages; shared by both sides so neither imports the other. */
    public static final int PAGE_COUNT = 3;

    public long worldTick;

    /** Whether any core object could be located at all. */
    public boolean linked;
    /** Whether the located core actually reports a loaded AMS core item. */
    public boolean loaded;
    /** Whether a power meter is bound and answering. */
    public boolean metered;
    /** True while the core is in its terminal collapse sequence. */
    public boolean collapsing;

    /** Short machine designation, already upper-cased and trimmed by the reader. */
    public String coreTag = "NONE";
    public String coolantTag = "EMPTY";
    public String moderatorTag = "EMPTY";

    public double heatC;
    public double meltC = 2250.0D;
    public float stability;
    public double coreEnergy;
    public double exhaustEnergy;
    public double gainEnergy;

    public double coolantFill;
    public double coolantCap = 1.0D;
    public double moderatorFill;
    public double moderatorCap = 1.0D;

    public int nStabiliser;
    public int nAbsorber;
    public int nPulsar;
    public int nEmitter;
    public int nInjector;
    public int nExchanger;
    public int nCEmitter;

    /** Seconds to detonation, or a negative value when no countdown is active. */
    public double detonationSeconds = -1.0D;
    /** Catalyst wear in the range 0..100. */
    public double wearSlotA;
    public double wearSlotB;

    public long powerPerTick;
    public long powerPerSecond;

    /** 0 = none, 1 = live countdown, 2 = wear-based estimate. */
    public int forecastKind;
    public int forecastSeconds = -1;

    /** Foreign-writer tamper state: 0 clean, 1 link lost, 2 degraded, 3 cut. */
    public int tamperState;
    public int tamperTicks;

    public final float[] trendHeat = new float[TREND_CAPACITY];
    public final float[] trendEnergy = new float[TREND_CAPACITY];
    public final float[] trendStability = new float[TREND_CAPACITY];
    public int trendCount;

    /** Heat change between the two newest samples, in degrees per tick. */
    public float heatSlope;

    public void encode(ByteBuf buf) {
        buf.writeLong(this.worldTick);
        buf.writeBoolean(this.linked);
        buf.writeBoolean(this.loaded);
        buf.writeBoolean(this.metered);
        buf.writeBoolean(this.collapsing);
        ByteBufUtils.writeUTF8String(buf, this.coreTag);
        ByteBufUtils.writeUTF8String(buf, this.coolantTag);
        ByteBufUtils.writeUTF8String(buf, this.moderatorTag);
        buf.writeDouble(this.heatC);
        buf.writeDouble(this.meltC);
        buf.writeFloat(this.stability);
        buf.writeDouble(this.coreEnergy);
        buf.writeDouble(this.exhaustEnergy);
        buf.writeDouble(this.gainEnergy);
        buf.writeDouble(this.coolantFill);
        buf.writeDouble(this.coolantCap);
        buf.writeDouble(this.moderatorFill);
        buf.writeDouble(this.moderatorCap);
        buf.writeInt(this.nStabiliser);
        buf.writeInt(this.nAbsorber);
        buf.writeInt(this.nPulsar);
        buf.writeInt(this.nEmitter);
        buf.writeInt(this.nInjector);
        buf.writeInt(this.nExchanger);
        buf.writeInt(this.nCEmitter);
        buf.writeDouble(this.detonationSeconds);
        buf.writeDouble(this.wearSlotA);
        buf.writeDouble(this.wearSlotB);
        buf.writeLong(this.powerPerTick);
        buf.writeLong(this.powerPerSecond);
        buf.writeInt(this.forecastKind);
        buf.writeInt(this.forecastSeconds);
        buf.writeByte(this.tamperState);
        buf.writeInt(this.tamperTicks);
        buf.writeInt(this.trendCount);
        buf.writeFloat(this.heatSlope);
        for (int i = 0; i < TREND_CAPACITY; i++) {
            buf.writeFloat(this.trendHeat[i]);
            buf.writeFloat(this.trendEnergy[i]);
            buf.writeFloat(this.trendStability[i]);
        }
    }

    public static AegisTelemetry decode(ByteBuf buf) {
        AegisTelemetry t = new AegisTelemetry();
        t.worldTick = buf.readLong();
        t.linked = buf.readBoolean();
        t.loaded = buf.readBoolean();
        t.metered = buf.readBoolean();
        t.collapsing = buf.readBoolean();
        t.coreTag = ByteBufUtils.readUTF8String(buf);
        t.coolantTag = ByteBufUtils.readUTF8String(buf);
        t.moderatorTag = ByteBufUtils.readUTF8String(buf);
        t.heatC = buf.readDouble();
        t.meltC = buf.readDouble();
        t.stability = buf.readFloat();
        t.coreEnergy = buf.readDouble();
        t.exhaustEnergy = buf.readDouble();
        t.gainEnergy = buf.readDouble();
        t.coolantFill = buf.readDouble();
        t.coolantCap = buf.readDouble();
        t.moderatorFill = buf.readDouble();
        t.moderatorCap = buf.readDouble();
        t.nStabiliser = buf.readInt();
        t.nAbsorber = buf.readInt();
        t.nPulsar = buf.readInt();
        t.nEmitter = buf.readInt();
        t.nInjector = buf.readInt();
        t.nExchanger = buf.readInt();
        t.nCEmitter = buf.readInt();
        t.detonationSeconds = buf.readDouble();
        t.wearSlotA = buf.readDouble();
        t.wearSlotB = buf.readDouble();
        t.powerPerTick = buf.readLong();
        t.powerPerSecond = buf.readLong();
        t.forecastKind = buf.readInt();
        t.forecastSeconds = buf.readInt();
        t.tamperState = buf.readByte();
        t.tamperTicks = buf.readInt();
        t.trendCount = Math.max(0, Math.min(TREND_CAPACITY, buf.readInt()));
        t.heatSlope = buf.readFloat();
        for (int i = 0; i < TREND_CAPACITY; i++) {
            t.trendHeat[i] = buf.readFloat();
            t.trendEnergy[i] = buf.readFloat();
            t.trendStability[i] = buf.readFloat();
        }
        return t;
    }

    /** Fraction of the melt point already reached, clamped to 0..1. */
    public float heatFraction() {
        double span = Math.max(1.0D, this.meltC);
        return (float) Math.max(0.0D, Math.min(1.0D, this.heatC / span));
    }

    /** Worst of the two catalysts, 0..100. */
    public double worstWear() {
        return Math.max(this.wearSlotA, this.wearSlotB);
    }
}

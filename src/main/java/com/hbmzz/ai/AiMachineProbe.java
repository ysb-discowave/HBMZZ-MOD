/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import com.hbmzz.aegis.AegisProbe;
import com.hbmzz.aegis.AegisTelemetry;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads a bound machine into a flat map of named numbers the AI can reason over.
 *
 * <p>Two layers, in priority order:</p>
 * <ol>
 *   <li><b>Known machines</b> - a DFC core is sampled through {@link AegisProbe},
 *       the same reader the Aegis terminal uses. That yields meaningful, named
 *       values (heat, stability, energy, coolant) rather than raw NBT keys.</li>
 *   <li><b>Generic tile entities</b> - for anything else, walk the tile's
 *       {@code writeToNBT} and flatten numeric tags. Key names are the NBT keys,
 *       prefixed by dimension and position. This is a best-effort fallback: a
 *       machine that hides its state in non-NBT fields will simply report few
 *       numbers, which the AI sees as "little data" rather than a crash.</li>
 * </ol>
 *
 * <p>All reads happen on the server thread from the node's tick, never on the
 * client and never on the TTS worker. The returned map is a fresh snapshot of
 * primitives - no live reference to a foreign tile entity escapes.</p>
 */
public final class AiMachineProbe {

    private AiMachineProbe() {
    }

    /** Cap on flattened NBT entries, so a huge inventory cannot flood the payload. */
    private static final int MAX_NBT_ENTRIES = 96;

    /**
     * Sample one bound position into a flat snapshot.
     *
     * @return never null; {@code snapshot.ok=false} when nothing could be read
     */
    public static Snapshot sample(World world, BlockPos bound, int scanRadius) {
        Snapshot snap = new Snapshot();
        snap.dimension = world == null ? 0 : world.provider.getDimension();
        snap.x = bound.getX();
        snap.y = bound.getY();
        snap.z = bound.getZ();

        if (world == null || bound == null) {
            snap.error = "no bound machine";
            return snap;
        }
        TileEntity te = world.getTileEntity(bound);
        if (te == null) {
            snap.error = "no tile entity at bound position";
            return snap;
        }

        // Layer 1: known DFC core -> rich, named telemetry.
        if (AegisProbe.isCore(te)) {
            AegisTelemetry t = AegisProbe.sample(world, bound, bound, null, scanRadius);
            snap.machineType = t.coreTag == null ? "DFC_CORE" : t.coreTag;
            snap.linked = t.linked;
            put(snap, "heatC", t.heatC);
            put(snap, "temperatureC", t.heatC); // explicit alias for AI prompts
            put(snap, "meltC", t.meltC);
            put(snap, "heatFraction", t.heatFraction());
            put(snap, "stability", t.stability);
            put(snap, "stabilityPct", t.stability <= 1.0F ? t.stability * 100.0F : t.stability);
            // Component counts: the AI can decide to switch a stabiliser /
            // receiver / emitter from the same snapshot it reasons over.
            put(snap, "stabilisers", t.nStabiliser);
            put(snap, "receivers", t.nAbsorber);
            put(snap, "emitters", t.nEmitter);
            put(snap, "coreEnergy", t.coreEnergy);
            put(snap, "exhaustEnergy", t.exhaustEnergy);
            put(snap, "gainEnergy", t.gainEnergy);
            put(snap, "coolantFill", t.coolantFill);
            put(snap, "coolantCap", t.coolantCap);
            put(snap, "moderatorFill", t.moderatorFill);
            put(snap, "moderatorCap", t.moderatorCap);
            put(snap, "powerPerTick", t.powerPerTick);
            put(snap, "powerPerSecond", t.powerPerSecond);
            put(snap, "detonationSeconds", t.detonationSeconds);
            put(snap, "forecastSeconds", t.forecastSeconds);
            put(snap, "worstWear", t.worstWear());
            put(snap, "heatSlope", t.heatSlope);
            putFlag(snap, "collapsing", t.collapsing);
            putFlag(snap, "scramming", t.scramming);
            putFlag(snap, "tampered", t.tamperState > 0);
            snap.ok = t.linked;
            if (!snap.ok) {
                snap.error = "core not reporting telemetry";
            }
            return snap;
        }

        // Layer 2: generic tile entity -> flatten numeric NBT tags.
        snap.machineType = te.getClass().getSimpleName();
        try {
            NBTTagCompound tag = te.writeToNBT(new NBTTagCompound());
            flatten(tag, snap, "");
            snap.ok = !snap.values.isEmpty();
            if (!snap.ok) {
                snap.error = "no numeric fields exposed via NBT";
            }
        } catch (Throwable t) {
            snap.error = "failed to read NBT: " + t;
        }
        return snap;
    }

    private static void flatten(NBTTagCompound tag, Snapshot snap, String prefix) {
        Set<String> keys = tag.getKeySet();
        // KeySet order is not guaranteed; sort for a stable, diffable payload.
        String[] sorted = keys.toArray(new String[0]);
        java.util.Arrays.sort(sorted);
        for (String key : sorted) {
            if (snap.values.size() >= MAX_NBT_ENTRIES) {
                return;
            }
            NBTBase base = tag.getTag(key);
            String name = prefix.isEmpty() ? key : prefix + "." + key;
            if (base instanceof net.minecraft.nbt.NBTTagByte) {
                put(snap, name, ((net.minecraft.nbt.NBTTagByte) base).getByte());
            } else if (base instanceof net.minecraft.nbt.NBTTagShort) {
                put(snap, name, ((net.minecraft.nbt.NBTTagShort) base).getShort());
            } else if (base instanceof net.minecraft.nbt.NBTTagInt) {
                put(snap, name, ((net.minecraft.nbt.NBTTagInt) base).getInt());
            } else if (base instanceof net.minecraft.nbt.NBTTagLong) {
                put(snap, name, ((net.minecraft.nbt.NBTTagLong) base).getLong());
            } else if (base instanceof net.minecraft.nbt.NBTTagFloat) {
                put(snap, name, ((net.minecraft.nbt.NBTTagFloat) base).getFloat());
            } else if (base instanceof net.minecraft.nbt.NBTTagDouble) {
                put(snap, name, ((net.minecraft.nbt.NBTTagDouble) base).getDouble());
            } else if (base instanceof net.minecraft.nbt.NBTTagCompound) {
                // One level deep is enough; deeper nesting is rarely a reading.
                if (prefix.isEmpty()) {
                    flatten((NBTTagCompound) base, snap, key);
                }
            }
            // Lists / strings / byte arrays are intentionally skipped: they are
            // inventories, names, or blobs - not scalar readings.
        }
    }

    private static void put(Snapshot snap, String key, Number value) {
        if (value != null) {
            snap.values.put(key, value.doubleValue());
        }
    }

    private static void putFlag(Snapshot snap, String key, boolean value) {
        snap.values.put(key, value ? 1.0D : 0.0D);
    }

    /** Plain-data snapshot of one machine. */
    public static final class Snapshot {
        public int dimension;
        public int x;
        public int y;
        public int z;
        public String machineType = "UNKNOWN";
        public boolean linked;
        public boolean ok;
        public String error = "";
        public final Map<String, Double> values = new LinkedHashMap<String, Double>();
    }
}

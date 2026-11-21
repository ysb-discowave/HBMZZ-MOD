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

import com.hbmzz.hack.HackRegistry;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pulls a {@link AegisTelemetry} frame out of the third-party DFC tile entity
 * without a compile-time dependency on its jar.
 *
 * <p>The base mod and its cursed fork expose their readings through public
 * {@code getDFC*} methods and a handful of public fields. Those names are a
 * wire protocol we cannot change; everything else in this class — method
 * resolution, fallbacks, caching of reflected members and the defensive
 * copying of foreign-owned collections — is implemented here from scratch.</p>
 *
 * <p>Every sample walks the live class reflectively. Polls run on a slow clock
 * (default ten ticks) and only while a viewer is nearby, so a small method scan
 * is cheaper than maintaining a cache that would go stale when a DFC update
 * swaps in a new core subclass. All lookups are defensive: a missing method or
 * field degrades to the documented fallback rather than throwing.</p>
 */
public final class AegisProbe {

    /** Fully qualified name of the machine whose internals we read. */
    private static final String CORE_TYPE = "com.hbm.tileentity.machine.TileEntityCore";
    /** Marker substring in the class name of a compatible power meter. */
    private static final String METER_MARK = "CableGauge";

    private AegisProbe() {
    }

    /** True when the tile entity is the DFC machine or a subclass of it. */
    public static boolean isCore(TileEntity te) {
        if (te == null) {
            return false;
        }
        for (Class<?> c = te.getClass(); c != null; c = c.getSuperclass()) {
            if (CORE_TYPE.equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Finds the closest core to an anchor, searching the loaded-TE list. */
    public static TileEntity nearestCore(World world, BlockPos anchor, int radius) {
        if (world == null || anchor == null) {
            return null;
        }
        TileEntity winner = null;
        double best = Double.MAX_VALUE;
        double limit = (double) radius * (double) radius;
        List<TileEntity> loaded = world.loadedTileEntityList;
        // Indexed with a live bound: another mod may mutate this list from a
        // different thread while we walk it.
        for (int i = 0, size = loaded.size(); i < size; i++) {
            TileEntity candidate;
            try {
                candidate = loaded.get(i);
            } catch (IndexOutOfBoundsException raced) {
                break;
            }
            if (!isCore(candidate)) {
                continue;
            }
            BlockPos p = candidate.getPos();
            if (p == null) {
                continue;
            }
            double d = p.distanceSq(anchor);
            if (d <= limit && d < best) {
                best = d;
                winner = candidate;
            }
        }
        return winner;
    }

    /**
     * Builds a complete frame. The returned object holds only primitives and
     * strings — nothing that points back into the world.
     */
    public static AegisTelemetry sample(World world, BlockPos anchor, BlockPos boundCore,
                                        BlockPos boundMeter, int radius) {
        AegisTelemetry frame = new AegisTelemetry();
        if (world == null) {
            return frame;
        }
        frame.worldTick = world.getTotalWorldTime();

        TileEntity core = boundCore == null ? null : world.getTileEntity(boundCore);
        if (!isCore(core)) {
            core = nearestCore(world, anchor, radius);
        }
        if (core == null) {
            return frame;
        }

        frame.linked = true;
        frame.loaded = boolOf(call(core, "isDFCHasCore"), true);
        frame.heatC = doubleOf(call(core, "getDFCTemperature"), field(core, "heat"), 0.0D);
        frame.meltC = positive(doubleOf(call(core, "getDFCMeltingPoint"), null, 2250.0D), 2250.0D);
        frame.stability = (float) Math.max(0.0D, Math.min(1.0D,
                doubleOf(call(core, "getDFCStabilization"), null, 0.0D)));
        frame.coreEnergy = doubleOf(call(core, "getDFCContainedEnergy"), null, 0.0D);
        frame.exhaustEnergy = doubleOf(call(core, "getDFCExpellingEnergy"), null, 0.0D);
        frame.gainEnergy = doubleOf(call(core, "getDFCPotentialGain"), null, 0.0D);
        frame.collapsing = doubleOf(call(core, "getDFCCollapsing"), null, 0.0D) > 0.0D;
        frame.detonationSeconds = doubleOf(call(core, "getDFCExplosionIn"), null, -1.0D);

        readTanks(core, frame);
        readCoreTag(core, frame);
        readComponents(world, core, frame);
        readWear(core, frame);
        readMeter(world, boundMeter, frame);
        readForecast(frame);
        // Key tamper state on the core we actually read, not the optional
        // explicit binding: in auto-scan mode boundCore is null, and keying on
        // null would hide every per-core DISCONNECT/DEGRADED mark (only the
        // world-wide CUT would still show). core is guaranteed non-null here.
        readTamper(world, core.getPos(), frame);
        return frame;
    }

    // ------------------------------------------------------------------ readers

    private static void readTanks(TileEntity core, AegisTelemetry frame) {
        Object tanks = field(core, "tanks");
        if (!(tanks instanceof Object[])) {
            return;
        }
        Object[] array = (Object[]) tanks;
        if (array.length > 0 && array[0] != null) {
            frame.coolantFill = doubleOf(call(array[0], "getFill"), null, 0.0D);
            frame.coolantCap = positive(doubleOf(call(array[0], "getMaxFill"), null, 1.0D), 1.0D);
            frame.coolantTag = fluidLabel(array[0]);
        }
        if (array.length > 1 && array[1] != null) {
            frame.moderatorFill = doubleOf(call(array[1], "getFill"), null, 0.0D);
            frame.moderatorCap = positive(doubleOf(call(array[1], "getMaxFill"), null, 1.0D), 1.0D);
            frame.moderatorTag = fluidLabel(array[1]);
        }
    }

    private static String fluidLabel(Object tank) {
        Object kind = call(tank, "getTankType");
        if (kind == null) {
            return "空";
        }
        Object label = call(kind, "getName");
        if (label == null) {
            label = call(kind, "getLocalizedName");
        }
        if (label == null) {
            return "空";
        }
        String text = String.valueOf(label).trim();
        return text.isEmpty() || "null".equals(text) ? "空" : text;
    }

    private static void readCoreTag(TileEntity core, AegisTelemetry frame) {
        ItemStack coreItem = stackAt(core, 1);
        if (coreItem == null || coreItem.isEmpty() || coreItem.getItem().getRegistryName() == null) {
            frame.coreTag = frame.loaded ? "未知" : "无";
            return;
        }
        String path = coreItem.getItem().getRegistryName().getResourcePath();
        if ("glitch".equals(path)) {
            frame.coreTag = "故障核心";
            return;
        }
        frame.coreTag = path.replace("ams_core_", "").replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static void readComponents(World world, TileEntity core, AegisTelemetry frame) {
        Object raw = call(core, "getDFCComponentPositions");
        if (!(raw instanceof List)) {
            raw = field(core, "componentPositions");
        }
        if (raw instanceof List) {
            List<?> entries;
            try {
                entries = new ArrayList<Object>((List<?>) raw);
            } catch (Throwable concurrent) {
                entries = new ArrayList<Object>();
            }
            for (Object entry : entries) {
                if (!(entry instanceof BlockPos)) {
                    continue;
                }
                TileEntity part = world.getTileEntity((BlockPos) entry);
                if (part == null) {
                    continue;
                }
                String name = part.getClass().getSimpleName();
                if (name.contains("CEmitter")) {
                    frame.nCEmitter++;
                } else if (name.contains("CoreEmitter")) {
                    frame.nEmitter++;
                } else if (name.contains("CoreInjector")) {
                    frame.nInjector++;
                } else if (name.contains("CoreReceiver")) {
                    frame.nAbsorber++;
                } else if (name.contains("CoreStabilizer")) {
                    frame.nStabiliser++;
                } else if (name.contains("CoreDetonator")) {
                    frame.nPulsar++;
                } else if (name.contains("CoreExchanger")) {
                    frame.nExchanger++;
                }
            }
        }
        int reported = (int) doubleOf(call(core, "getDFCStabilizers"), null, frame.nStabiliser);
        if (reported > 0) {
            frame.nStabiliser = reported;
        }
        int pulsers = sizeOf(call(core, "getDFCPulsers"));
        if (pulsers > 0) {
            frame.nPulsar = pulsers;
        }
        int absorbers = sizeOf(call(core, "getDFCAbsorbers"));
        if (absorbers > 0) {
            frame.nAbsorber = absorbers;
        }
    }

    private static void readWear(TileEntity core, AegisTelemetry frame) {
        frame.wearSlotA = catalystWear(stackAt(core, 0));
        frame.wearSlotB = catalystWear(stackAt(core, 2));
    }

    private static double catalystWear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0D;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? 0.0D : tag.getDouble("damage");
    }

    private static void readMeter(World world, BlockPos meterPos, AegisTelemetry frame) {
        if (meterPos == null) {
            return;
        }
        TileEntity meter = world.getTileEntity(meterPos);
        if (meter == null || !meter.getClass().getName().contains(METER_MARK)) {
            return;
        }
        frame.metered = true;
        frame.powerPerTick = (long) doubleOf(null, field(meter, "deltaTick"), 0.0D);
        frame.powerPerSecond = (long) doubleOf(null, field(meter, "deltaLastSecond"), 0.0D);
    }

    private static void readForecast(AegisTelemetry frame) {
        if (frame.collapsing || frame.detonationSeconds >= 0.0D) {
            frame.forecastKind = 1;
            frame.forecastSeconds = (int) Math.ceil(Math.max(0.0D, frame.detonationSeconds));
        }
    }

    private static void readTamper(World world, BlockPos corePos, AegisTelemetry frame) {
        frame.tamperState = HackRegistry.stateFor(world, corePos);
        frame.tamperTicks = HackRegistry.ticksLeft(world, corePos);
    }

    // -------------------------------------------------------------- reflection

    private static Object call(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                continue;
            }
            try {
                return m.invoke(target, args);
            } catch (Throwable ignored) {
                // Try the next overload.
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException absent) {
                // Walk up to the superclass.
            } catch (Throwable refused) {
                return null;
            }
        }
        return null;
    }

    private static ItemStack stackAt(TileEntity core, int slot) {
        Object inventory = field(core, "inventory");
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        Object item = call(inventory, "getStackInSlot", slot);
        return item instanceof ItemStack ? (ItemStack) item : ItemStack.EMPTY;
    }

    private static double doubleOf(Object direct, Object fallbackNumber, double otherwise) {
        if (direct instanceof Number) {
            return ((Number) direct).doubleValue();
        }
        if (fallbackNumber instanceof Number) {
            return ((Number) fallbackNumber).doubleValue();
        }
        return otherwise;
    }

    private static boolean boolOf(Object value, boolean otherwise) {
        return value instanceof Boolean ? (Boolean) value : otherwise;
    }

    private static double positive(double value, double otherwise) {
        return value > 0.0D ? value : otherwise;
    }

    private static int sizeOf(Object value) {
        if (!(value instanceof List)) {
            return 0;
        }
        try {
            return ((List<?>) value).size();
        } catch (Throwable raced) {
            return 0;
        }
    }
}

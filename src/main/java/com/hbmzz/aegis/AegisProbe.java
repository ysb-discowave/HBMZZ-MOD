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

import com.hbmzz.HBMZZ;
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
    /** One-line diagnostic of real core readings for the first few samples. */
    private static final int DIAGNOSTIC_SAMPLES = 8;
    private static int diagnosticCount;
    /** Repeating "no core found" diagnostic, throttled to once every N samples. */
    private static final int NO_CORE_LOG_EVERY = 200;
    private static int noCoreSamples;

    private AegisProbe() {
    }

    /**
     * True when the tile entity is the DFC machine or a compatible fork.
     *
     * <p>The exact class name is tried first (cheap, no reflection). NTM:CE and
     * the Cursed Addon reshuffle core subclasses, so the name alone is not
     * enough: a core also carries the {@code getDFCTemperature} accessor (added
     * by the addon mixin) or the base mod's {@code heat}/{@code field} pair.
     * Falling back to those features keeps the monitor reading a real core
     * after a fork rename — which, when it fails, looks exactly like "temperature
     * stopped working" because sampling returns early with no diagnostic.</p>
     */
    public static boolean isCore(TileEntity te) {
        if (te == null) {
            return false;
        }
        for (Class<?> c = te.getClass(); c != null; c = c.getSuperclass()) {
            if (CORE_TYPE.equals(c.getName())) {
                return true;
            }
            // CE addon marks every real fusion core with one of these marker
            // interfaces. Checking them (by suffix, so a package move is fine)
            // recognises forks that rename the concrete class entirely.
            for (Class<?> iface : c.getInterfaces()) {
                String name = iface.getName();
                if (name.endsWith("IDFCBase") || name.endsWith("IMixinTileEntityCore")) {
                    return true;
                }
            }
        }
        // Simple-name match covers forks that relocate TileEntityCore but keep
        // its name (CE addon still uses this simple name under the hood).
        if ("TileEntityCore".equals(te.getClass().getSimpleName())) {
            return true;
        }
        if (hasMethod(te, "getDFCTemperature") || hasMethod(te, "isDFCHasCore")) {
            return true;
        }
        return field(te, "heat") instanceof Number && field(te, "field") instanceof Number;
    }

    private static boolean hasMethod(Object target, String name) {
        if (target == null) {
            return false;
        }
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals(name)) {
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
        boolean explicitBound = core != null && isCore(core);
        if (!explicitBound) {
            core = nearestCore(world, anchor, radius);
        }
        if (core == null) {
            // A terminal that cannot find a core used to fail in total silence,
            // which is indistinguishable from a broken temperature reader. Log
            // why: an out-of-range terminal, a stale binding or a core the
            // feature check no longer recognises are three different problems.
            if ((noCoreSamples++) % NO_CORE_LOG_EVERY == 0) {
                HBMZZ.logger.info("Aegis sample @" + anchor + " NO CORE"
                        + " bound=" + boundCore + " boundValid=" + explicitBound
                        + " scanRadius=" + radius
                        + " loadedTE=" + world.loadedTileEntityList.size());
            }
            return frame;
        }

        frame.linked = true;
        // Reflect the live automatic-shutdown session onto the telemetry frame so
        // the client can show an "自动停堆" badge above the temperature readout.
        // Key on the core we actually read (auto-scan may differ from the binding).
        frame.scramming = com.hbmzz.guard.CoreGuard.isArmed(world, core.getPos());
        // NTM-CE 2.5.0 + Cursed addon: the addon's mixin adds getDFC* accessors
        // to TileEntityCore. Those are preferred, but a core that has just been
        // placed (or a fork without the mixin) leaves the addon fields at 0, so
        // fall back to the base mod's real fields:
        //   int heat        - this tick's heat (0..100 scale)
        //   int field       - collapse countdown / threshold
        //   boolean meltdownTick
        //   boolean hasCore()
        frame.loaded = boolOf(call(core, "isDFCHasCore"),
                boolOf(call(core, "hasCore"), field(core, "heat") instanceof Number
                        && ((Number) field(core, "heat")).intValue() > 0));
        frame.heatC = temperature(core);
        frame.meltC = meltingPoint(core);
        frame.stability = (float) Math.max(0.0D, Math.min(1.0D,
                doubleOf(call(core, "getDFCStabilization"), null, 0.0D)));
        frame.coreEnergy = doubleOf(call(core, "getDFCContainedEnergy"), null, 0.0D);
        frame.exhaustEnergy = doubleOf(call(core, "getDFCExpellingEnergy"), null, 0.0D);
        frame.gainEnergy = doubleOf(call(core, "getDFCPotentialGain"), null, 0.0D);
        frame.collapsing = collapsing(core);
        frame.detonationSeconds = explosionIn(core);

        if (diagnosticCount < DIAGNOSTIC_SAMPLES) {
            diagnosticCount++;
            HBMZZ.logger.info("Aegis sample @" + core.getPos()
                    + " class=" + core.getClass().getName()
                    + " linked=true loaded=" + frame.loaded
                    + " temp=" + frame.heatC + " melt=" + frame.meltC
                    + " stab=" + frame.stability
                    + " collapsing=" + frame.collapsing
                    + " explosionIn=" + frame.detonationSeconds
                    + " heatF=" + field(core, "heat")
                    + " fieldF=" + field(core, "field")
                    + " meltdownTick=" + field(core, "meltdownTick"));
        }

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

    // --------------------------------------------------- core reading with fallbacks
    // The Cursed addon mixin adds getDFC* accessors on top of the base mod's
    // TileEntityCore. On a freshly placed core (or without the mixin) the addon
    // fields stay at 0, so each reading prefers the accessor but falls back to
    // the base fields the accessor itself derives from.

    /** Core temperature, in the same scale as {@link #meltingPoint}. */
    private static double temperature(TileEntity core) {
        Object addon = call(core, "getDFCTemperature");
        if (addon instanceof Number && ((Number) addon).doubleValue() > 0.0D) {
            return ((Number) addon).doubleValue();
        }
        // Base mod: int heat is this tick's temperature. When the addon field
        // is still 0 the core is cold OR the mixin is absent; using heat keeps a
        // reading alive, paired with the `field` threshold in meltingPoint().
        Object heat = field(core, "heat");
        return heat instanceof Number ? ((Number) heat).doubleValue() : 0.0D;
    }

    /** Melt threshold paired with whatever {@link #temperature} returned. */
    private static double meltingPoint(TileEntity core) {
        Object addon = call(core, "getDFCMeltingPoint");
        if (addon instanceof Number && ((Number) addon).doubleValue() > 0.0D) {
            return ((Number) addon).doubleValue();
        }
        Object fieldValue = field(core, "field");
        if (fieldValue instanceof Number && ((Number) fieldValue).doubleValue() > 0.0D) {
            return ((Number) fieldValue).doubleValue();
        }
        return 2250.0D;
    }

    /** True only while the core is actually in terminal collapse. */
    private static boolean collapsing(TileEntity core) {
        Object addon = call(core, "getDFCCollapsing");
        if (addon instanceof Number) {
            return ((Number) addon).doubleValue() > 0.0D;
        }
        if (boolField(core, "meltdownTick", false)) {
            return true;
        }
        Object heat = field(core, "heat");
        Object fieldValue = field(core, "field");
        return heat instanceof Number && fieldValue instanceof Number
                && ((Number) heat).doubleValue() > 0.0D
                && ((Number) heat).doubleValue() >= ((Number) fieldValue).doubleValue();
    }

    /** Seconds until detonation, or -1 when no countdown is running. */
    private static double explosionIn(TileEntity core) {
        Object addon = call(core, "getDFCExplosionIn");
        if (addon instanceof Number) {
            double value = ((Number) addon).doubleValue();
            return value >= 0.0D ? value : -1.0D;
        }
        Object fieldValue = field(core, "field");
        if (fieldValue instanceof Number && ((Number) fieldValue).doubleValue() > 0.0D) {
            return ((Number) fieldValue).doubleValue();
        }
        return -1.0D;
    }

    private static boolean boolField(Object target, String name, boolean fallback) {
        Object value = field(target, name);
        return value instanceof Boolean ? (Boolean) value : fallback;
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

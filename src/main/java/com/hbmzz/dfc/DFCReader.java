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

import com.hbmzz.HBMZZConfig;
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
 * Reads the DFC and power-meter state through reflection.
 *
 * <p>NTM method names are stable on 1.12.2, and the Cursed Addon exposes its
 * reworked values through the public {@code getDFC*} accessors, so no
 * compile-time dependency on either jar is required.</p>
 */
public final class DFCReader {

    public static final String CORE_CLASS = "com.hbm.tileentity.machine.TileEntityCore";

    private DFCReader() {
    }

    public static DFCSnapshot read(World world, BlockPos corePos, BlockPos meterPos, int panelW, int panelH) {
        return read(world, corePos, corePos, meterPos, panelW, panelH);
    }

    /**
     * @param reference where the reading is taken from (the monitor itself); it
     *                  anchors the automatic core search when nothing is bound,
     *                  and must never be null.
     */
    public static DFCSnapshot read(World world, BlockPos reference, BlockPos corePos, BlockPos meterPos,
                                   int panelW, int panelH) {
        DFCSnapshot s = new DFCSnapshot();
        s.panelW = panelW;
        s.panelH = panelH;
        if (world == null) {
            return s;
        }
        s.tick = world.getTotalWorldTime();

        TileEntity core = null;
        if (corePos != null) {
            TileEntity candidate = world.getTileEntity(corePos);
            if (isCore(candidate)) {
                core = candidate;
            }
        }
        if (core == null) {
            core = findCore(world, reference != null ? reference : corePos, HBMZZConfig.scanRadius);
        }
        if (core == null) {
            return s;
        }

        s.coreFound = true;
        s.hasCore = asBool(invoke(core, "isDFCHasCore"), true);
        s.temperature = asDouble(invoke(core, "getDFCTemperature"), fieldDouble(core, "heat", 0.0D));
        s.meltingPoint = asDouble(invoke(core, "getDFCMeltingPoint"), 2250.0D);
        s.stabilization = asDouble(invoke(core, "getDFCStabilization"), 0.0D);
        s.containedEnergy = asDouble(invoke(core, "getDFCContainedEnergy"), 0.0D);
        s.expellingEnergy = asDouble(invoke(core, "getDFCExpellingEnergy"), 0.0D);
        s.potentialGain = asDouble(invoke(core, "getDFCPotentialGain"), 0.0D);
        s.collapsing = asDouble(invoke(core, "getDFCCollapsing"), 0.0D) > 0.0D;
        s.explosionIn = asDouble(invoke(core, "getDFCExplosionIn"), -1.0D);

        readTanks(core, s);
        readCoreType(core, s);
        readComponents(world, core, s);
        readCatalysts(core, s);
        readMeter(world, meterPos, s);
        predict(s);
        return s;
    }

    public static TileEntity findCore(World world, BlockPos around, int radius) {
        // A null anchor used to be passed straight into BlockPos.distanceSq and
        // took the whole server down with an NPE; an unbound monitor now simply
        // reports "no core".
        if (world == null || around == null) {
            return null;
        }
        TileEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        double max = (double) radius * (double) radius;
        // Indexed loop with a live size check: this runs while the world is
        // already iterating the very same list, and other mods may add or drop
        // entries from another thread while their tile entities tick.
        List<TileEntity> list = world.loadedTileEntityList;
        for (int i = 0; i < list.size(); i++) {
            TileEntity te;
            try {
                te = list.get(i);
            } catch (IndexOutOfBoundsException raced) {
                break;
            }
            if (!isCore(te)) {
                continue;
            }
            BlockPos tePos = te.getPos();
            if (tePos == null) {
                continue;
            }
            double distance = tePos.distanceSq(around);
            if (distance <= max && distance < bestDistance) {
                best = te;
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * Matches the core class or any subclass of it, so the Cursed Addon and
     * other forks that extend the core still register as a DFC.
     */
    public static boolean isCore(TileEntity te) {
        if (te == null) {
            return false;
        }
        for (Class<?> type = te.getClass(); type != null; type = type.getSuperclass()) {
            if (CORE_CLASS.equals(type.getName())) {
                return true;
            }
        }
        // CE / Cursed forks reshuffle core subclasses; accept the DFC feature
        // signature as a fallback so a rename does not silently break the
        // linker and the auto-shutdown's target check.
        for (Method m : te.getClass().getMethods()) {
            if ("getDFCTemperature".equals(m.getName()) || "isDFCHasCore".equals(m.getName())) {
                return true;
            }
        }
        return field(te, "heat") instanceof Number && field(te, "field") instanceof Number;
    }

    public static boolean isPowerMeter(TileEntity te) {
        return te != null && te.getClass().getName().contains("CableGauge");
    }

    /**
     * Seconds until this core detonates, or -1 when it is not counting down.
     *
     * <p>Exposed for the cinematic camera, which starts orbiting at five
     * seconds. The monitor already reads this number for its countdown; the
     * only difference is who is asking.</p>
     */
    public static double explosionIn(TileEntity core) {
        return asDouble(invoke(core, "getDFCExplosionIn"), -1.0D);
    }

    /** True while the core is in its terminal collapse. */
    public static boolean collapsing(TileEntity core) {
        return asDouble(invoke(core, "getDFCCollapsing"), 0.0D) > 0.0D;
    }

    /** Temperature only, without paying for tanks, components and catalysts. */
    public static double temperature(TileEntity core) {
        return asDouble(invoke(core, "getDFCTemperature"), fieldDouble(core, "heat", 0.0D));
    }

    /**
     * Forces the core's temperature, for the automatic shutdown.
     *
     * <p>There is no public setter in the base mod, so this tries one first -
     * an addon may well expose `setDFCTemperature` - and otherwise writes the
     * field the getter reads. `prevHeat` is written alongside `heat` because
     * the core's own tick interpolates from it; leaving it behind makes the
     * temperature gauge jump for one tick, which looks exactly like the
     * procedure having failed.</p>
     *
     * <p>Server thread only. This writes another mod's field; that is already
     * what the hacker tablet does, and the alternative is not having the
     * feature at all.</p>
     *
     * @return true when at least one write landed
     */
    public static boolean writeTemperature(TileEntity core, double celsius) {
        if (core == null) {
            return false;
        }
        double clamped = Math.max(0.0D, celsius);
        if (callNumber(core, "setDFCTemperature", clamped)
                || callNumber(core, "setDFCHeat", clamped)) {
            return true;
        }
        boolean wrote = false;
        Field heat = findField(core.getClass(), "heat");
        if (writeNumber(core, heat, clamped)) {
            wrote = true;
            // The display lerps from prevHeat; keep it on the same step.
            writeNumber(core, findField(core.getClass(), "prevHeat"), clamped);
        }
        for (String name : new String[]{"dfcHeat", "dfcTemperature", "temperature"}) {
            wrote |= writeNumber(core, findField(core.getClass(), name), clamped);
        }
        return wrote;
    }

    /**
     * Clears the upstream collapse, as far as anybody is willing to admit it
     * can be cleared.
     *
     * <p>Only setter methods with a known name and numeric fields on an
     * explicit whitelist are touched. Nothing is guessed from a name that
     * merely looks similar: writing the wrong double in a meltdown sequence is
     * worse than a blast that still happens.</p>
     *
     * @return true when a hook was found and written
     */
    public static boolean clearCollapse(TileEntity core) {
        if (core == null) {
            return false;
        }
        if (callNumber(core, "setDFCCollapsing", 0.0D)
                || callNumber(core, "setCollapsing", 0.0D)
                || callVoid(core, "abortCollapse")
                || callVoid(core, "stopDFC")) {
            return true;
        }
        boolean wrote = false;
        for (String name : new String[]{"dfcCollapsing", "collapsing"}) {
            wrote |= writeNumber(core, findField(core.getClass(), name), 0.0D);
        }
        for (String name : new String[]{"dfcExplosionIn", "explosionIn"}) {
            wrote |= writeNumber(core, findField(core.getClass(), name), -1.0D);
        }
        return wrote;
    }

    private static boolean writeNumber(Object target, Field field, double value) {
        if (field == null) {
            return false;
        }
        try {
            field.setAccessible(true);
            Class<?> type = field.getType();
            if (type == double.class || type == Double.class) {
                field.setDouble(target, value);
            } else if (type == float.class || type == Float.class) {
                field.setFloat(target, (float) value);
            } else if (type == int.class || type == Integer.class) {
                field.setInt(target, (int) Math.round(value));
            } else if (type == long.class || type == Long.class) {
                field.setLong(target, Math.round(value));
            } else if (type == short.class || type == Short.class) {
                field.setShort(target, (short) Math.round(value));
            } else if (type == byte.class || type == Byte.class) {
                field.setByte(target, (byte) Math.round(value));
            } else {
                return false;
            }
            return true;
        } catch (Throwable refused) {
            // A final field, a security manager, a module wall: all the same
            // answer - this core cannot be written from here.
            return false;
        }
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> type = start; type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {
                // keep walking up
            } catch (Throwable refused) {
                return null;
            }
        }
        return null;
    }

    private static void readTanks(TileEntity core, DFCSnapshot s) {
        Object tanksValue = field(core, "tanks");
        if (!(tanksValue instanceof Object[])) {
            return;
        }
        Object[] tanks = (Object[]) tanksValue;
        if (tanks.length > 0 && tanks[0] != null) {
            s.fuel1Fill = asDouble(invoke(tanks[0], "getFill"), 0.0D);
            s.fuel1Max = Math.max(1.0D, asDouble(invoke(tanks[0], "getMaxFill"), 1.0D));
            s.fuel1Name = fluidName(tanks[0]);
        }
        if (tanks.length > 1 && tanks[1] != null) {
            s.fuel2Fill = asDouble(invoke(tanks[1], "getFill"), 0.0D);
            s.fuel2Max = Math.max(1.0D, asDouble(invoke(tanks[1], "getMaxFill"), 1.0D));
            s.fuel2Name = fluidName(tanks[1]);
        }
    }

    private static String fluidName(Object tank) {
        Object type = invoke(tank, "getTankType");
        if (type == null) {
            return "EMPTY";
        }
        Object name = invoke(type, "getName");
        if (name == null) {
            name = invoke(type, "getLocalizedName");
        }
        String text = name == null ? "EMPTY" : String.valueOf(name);
        if (text.isEmpty() || "null".equals(text)) {
            return "EMPTY";
        }
        return text;
    }

    private static void readCoreType(TileEntity core, DFCSnapshot s) {
        ItemStack stack = stackInSlot(core, 1);
        if (stack == null || stack.isEmpty() || stack.getItem().getRegistryName() == null) {
            s.coreType = s.hasCore ? "UNKNOWN" : "NONE";
            return;
        }
        String path = stack.getItem().getRegistryName().getResourcePath();
        if ("glitch".equals(path)) {
            s.coreType = "GLITCH";
            return;
        }
        s.coreType = path.replace("ams_core_", "").replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static void readCatalysts(TileEntity core, DFCSnapshot s) {
        s.damageA = catalystDamage(stackInSlot(core, 0));
        s.damageB = catalystDamage(stackInSlot(core, 2));
    }

    private static double catalystDamage(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return 0.0D;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? 0.0D : tag.getDouble("damage");
    }

    private static void readComponents(World world, TileEntity core, DFCSnapshot s) {
        Object value = invoke(core, "getDFCComponentPositions");
        if (!(value instanceof List)) {
            value = field(core, "componentPositions");
        }
        if (value instanceof List) {
            // The addon hands out its live list and mutates it from its own
            // threads; copy under a guard instead of walking it in place.
            List<?> entries;
            try {
                entries = new ArrayList<Object>((List<?>) value);
            } catch (Throwable raced) {
                entries = java.util.Collections.emptyList();
            }
            for (Object entry : entries) {
                if (!(entry instanceof BlockPos)) {
                    continue;
                }
                TileEntity component = world.getTileEntity((BlockPos) entry);
                if (component == null) {
                    continue;
                }
                String name = component.getClass().getSimpleName();
                if (name.contains("CEmitter")) {
                    s.cemiters++;
                } else if (name.contains("CoreEmitter")) {
                    s.emitters++;
                } else if (name.contains("CoreInjector")) {
                    s.injectors++;
                } else if (name.contains("CoreReceiver")) {
                    s.absorbers++;
                } else if (name.contains("CoreStabilizer")) {
                    s.stabilizers++;
                } else if (name.contains("CoreDetonator")) {
                    s.pulsers++;
                } else if (name.contains("CoreExchanger")) {
                    s.exchangers++;
                }
            }
        }
        int reportedStabilizers = (int) asDouble(invoke(core, "getDFCStabilizers"), s.stabilizers);
        if (reportedStabilizers > 0) {
            s.stabilizers = reportedStabilizers;
        }
        int pulsers = safeSize(invoke(core, "getDFCPulsers"));
        if (pulsers > 0) {
            s.pulsers = pulsers;
        }
        int absorbers = safeSize(invoke(core, "getDFCAbsorbers"));
        if (absorbers > 0) {
            s.absorbers = absorbers;
        }
    }

    /** Size of a live list owned by another mod, or 0 if it changed under us. */
    private static int safeSize(Object value) {
        if (!(value instanceof List)) {
            return 0;
        }
        try {
            return ((List<?>) value).size();
        } catch (Throwable raced) {
            return 0;
        }
    }

    private static void readMeter(World world, BlockPos meterPos, DFCSnapshot s) {
        if (meterPos == null) {
            return;
        }
        TileEntity meter = world.getTileEntity(meterPos);
        if (!isPowerMeter(meter)) {
            return;
        }
        s.meterBound = true;
        s.meterPerTick = (long) fieldDouble(meter, "deltaTick", 0.0D);
        s.meterPerSecond = (long) fieldDouble(meter, "deltaLastSecond", 0.0D);
    }

    private static void predict(DFCSnapshot s) {
        if (s.collapsing || s.explosionIn >= 0.0D) {
            s.predictionMode = 1;
            s.predictedSeconds = (int) Math.ceil(Math.max(0.0D, s.explosionIn));
        }
        // Mode 2 (estimated time until the catalysts fail) needs a damage rate,
        // which is only meaningful across samples; the monitor tile entity adds
        // it from its own history.
    }

    private static ItemStack stackInSlot(TileEntity core, int slot) {
        Object inventory = field(core, "inventory");
        if (inventory == null) {
            return ItemStack.EMPTY;
        }
        Object result = invoke(inventory, "getStackInSlot", slot);
        return result instanceof ItemStack ? (ItemStack) result : ItemStack.EMPTY;
    }

    /**
     * Calls a one-argument setter if it exists, converting to whatever numeric
     * type it declares. Returns true only when a method actually ran.
     */
    private static boolean callNumber(Object target, String name, double value) {
        if (target == null) {
            return false;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> type = method.getParameterTypes()[0];
            Object argument;
            if (type == double.class || type == Double.class) {
                argument = value;
            } else if (type == float.class || type == Float.class) {
                argument = (float) value;
            } else if (type == int.class || type == Integer.class) {
                argument = (int) Math.round(value);
            } else if (type == long.class || type == Long.class) {
                argument = Math.round(value);
            } else {
                continue;
            }
            try {
                method.invoke(target, argument);
                return true;
            } catch (Throwable ignored) {
                // try the next overload
            }
        }
        return false;
    }

    /** Calls a no-argument action if it exists. */
    private static boolean callVoid(Object target, String name) {
        if (target == null) {
            return false;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 0) {
                continue;
            }
            try {
                method.invoke(target);
                return true;
            } catch (Throwable ignored) {
                // try the next overload
            }
        }
        return false;
    }

    private static Object invoke(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                continue;
            }
            try {
                return method.invoke(target, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static double fieldDouble(Object target, String name, double fallback) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static boolean asBool(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
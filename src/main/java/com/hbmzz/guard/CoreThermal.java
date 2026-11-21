/*
 * HBMZZ - independent DFC core thermal guard.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */

package com.hbmzz.guard;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Standalone reflection layer for a DFC core's heat. Deliberately shares no
 * code with the telemetry terminal: it talks straight to the core's real
 * accessors. On the Cursed Edition the temperature lives in a {@code double
 * temperature} field behind {@code getDFCTemperature()} / {@code
 * setDFCTemperature(double)} (confirmed by decompiling the core mixin), NOT in
 * the vanilla {@code int heat} field - writing heat there does nothing, which
 * is exactly why the old shutdown appeared to have no effect.
 */
public final class CoreThermal {

    public static final String CORE_CLASS = "com.hbm.tileentity.machine.TileEntityCore";

    private CoreThermal() {
    }

    /** True for the base core class, its forks, and anything exposing the DFC heat API. */
    public static boolean isCore(TileEntity te) {
        if (te == null) {
            return false;
        }
        Class<?> cls = te.getClass();
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            if (CORE_CLASS.equals(c.getName())) {
                return true;
            }
            for (Class<?> iface : c.getInterfaces()) {
                String name = iface.getName();
                if (name.endsWith("IDFCBase") || name.endsWith("IMixinTileEntityCore")) {
                    return true;
                }
            }
        }
        if ("TileEntityCore".equals(cls.getSimpleName())) {
            return true;
        }
        try {
            for (Method m : cls.getMethods()) {
                String n = m.getName();
                if ("getDFCTemperature".equals(n) || "setDFCTemperature".equals(n)
                        || "isDFCHasCore".equals(n)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return field(te, "temperature") instanceof Number || field(te, "heat") instanceof Number;
    }

    /** Nearest DFC core around {@code around}, or null. */
    public static TileEntity findCore(World world, BlockPos around, int radius) {
        if (world == null || around == null) {
            return null;
        }
        TileEntity best = null;
        double bestSq = Double.MAX_VALUE;
        double max = (double) radius * (double) radius;
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
            BlockPos p = te.getPos();
            if (p == null) {
                continue;
            }
            double d = p.distanceSq(around);
            if (d <= max && d < bestSq) {
                best = te;
                bestSq = d;
            }
        }
        return best;
    }

    /** Real core temperature in degrees, or NaN when it cannot be read. */
    public static double temperature(TileEntity core) {
        if (core == null) {
            return Double.NaN;
        }
        Number n = number(invoke(core, "getDFCTemperature"));
        if (n == null) {
            n = number(field(core, "temperature"));
        }
        if (n == null) {
            n = number(field(core, "heat"));
        }
        return n == null ? Double.NaN : n.doubleValue();
    }

    /**
     * Forces the real temperature. Prefers the accessor the getter reads, then
     * writes the backing field directly. Never below zero.
     *
     * @return true when a write landed
     */
    public static boolean writeTemperature(TileEntity core, double celsius) {
        if (core == null) {
            return false;
        }
        double v = Math.max(0.0D, celsius);
        if (callNumber(core, "setDFCTemperature", v)) {
            return true;
        }
        boolean wrote = writeField(core, "temperature", v);
        wrote |= writeField(core, "prevHeat", v);
        wrote |= writeField(core, "heat", v);
        return wrote;
    }

    /** Melting point in degrees (CE reports an int; 2900 is the known default). */
    public static double meltingPoint(TileEntity core) {
        Number n = number(invoke(core, "getDFCMeltingPoint"));
        return n == null ? 2900.0D : n.doubleValue();
    }

    public static boolean collapsing(TileEntity core) {
        Number n = number(invoke(core, "getDFCCollapsing"));
        return n != null && n.doubleValue() > 0.0D;
    }

    public static double explosionIn(TileEntity core) {
        Number n = number(invoke(core, "getDFCExplosionIn"));
        return n == null ? -1.0D : n.doubleValue();
    }

    // ------------------------------------------------------------- reflection

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static Object invoke(Object target, String name, Object... args) {
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
            }
        }
        return null;
    }

    private static boolean callNumber(Object target, String name, double value) {
        if (target == null) {
            return false;
        }
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> t = m.getParameterTypes()[0];
            Object arg;
            if (t == double.class || t == Double.class) {
                arg = value;
            } else if (t == float.class || t == Float.class) {
                arg = (float) value;
            } else if (t == int.class || t == Integer.class) {
                arg = (int) Math.round(value);
            } else if (t == long.class || t == Long.class) {
                arg = Math.round(value);
            } else {
                continue;
            }
            try {
                m.invoke(target, arg);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
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
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean writeField(Object target, String name, double value) {
        if (target == null) {
            return false;
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                Class<?> t = f.getType();
                if (t == double.class || t == Double.class) {
                    f.setDouble(target, value);
                } else if (t == float.class || t == Float.class) {
                    f.setFloat(target, (float) value);
                } else if (t == int.class || t == Integer.class) {
                    f.setInt(target, (int) Math.round(value));
                } else if (t == long.class || t == Long.class) {
                    f.setLong(target, Math.round(value));
                } else {
                    return false;
                }
                return true;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}

/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.ai;

import com.hbmzz.dfc.DFCReader;
import com.hbmzz.guard.CoreGuard;
import com.hbmzz.guard.CoreThermal;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reflection-based control of a third-party DFC core and its components.
 *
 * <p>The DFC machine lives in another mod, so this module cannot import it.
 * Like {@link CoreThermal} and {@link DFCReader}, it talks to the core through
 * a small protocol of well-known method and field names and degrades cleanly
 * when a fork renames them. Three kinds of operation are supported:</p>
 * <ol>
 *   <li><b>Read</b> stability and temperature — the numbers the AI reasons
 *       over (see {@link #read(World, BlockPos, int)}).</li>
 *   <li><b>Component control</b> — enable / disable the stabiliser, receiver
 *       and emitter blocks around the core via their public
 *       {@code setActive(boolean)} / {@code active} field, the same switch the
 *       base mod's redstone link drives.</li>
 *   <li><b>Core control</b> — start the core ({@code setDFCIgnition(true)} on
 *       forks that expose it) and SCRAM it (via {@link CoreGuard}, which writes
 *       the real temperature field).</li>
 * </ol>
 *
 * <p>Every write runs on the server thread (callers are the node callback and
 * the terminal command handler, both already scheduled there) and every result
 * is reported as text — a failed hook is never silently treated as success.</p>
 */
public final class DfcControl {

    /** Component kinds the AI may switch. */
    public enum Part {
        STABILISER("stabiliser", new String[]{"CoreStabilizer", "CoreStabilisator", "Stabilizer"}),
        RECEIVER("receiver", new String[]{"CoreReceiver", "Receiver"}),
        EMITTER("emitter", new String[]{"CoreEmitter", "CoreCEmitter", "Emitter"}),
        ABSORBER("absorber", new String[]{"CoreAbsorber"}),
        INJECTOR("injector", new String[]{"CoreInjector", "Injector"}),
        DETONATOR("detonator", new String[]{"CoreDetonator"});

        private final String key;
        private final String[] markers;

        Part(String key, String[] markers) {
            this.key = key;
            this.markers = markers;
        }

        public String key() {
            return this.key;
        }

        static Part byKey(String word) {
            if (word == null) {
                return null;
            }
            String w = word.toLowerCase(Locale.ROOT);
            for (Part p : values()) {
                if (p.key.equals(w) || p.name().toLowerCase(Locale.ROOT).equals(w)) {
                    return p;
                }
            }
            // Common aliases operators actually type.
            if ("稳定".equals(word) || "稳定器".equals(word) || "stab".equals(w)) {
                return STABILISER;
            }
            if ("接收".equals(word) || "接收器".equals(word)) {
                return RECEIVER;
            }
            if ("发射".equals(word) || "发射器".equals(word)) {
                return EMITTER;
            }
            return null;
        }

        boolean matches(String className) {
            for (String marker : this.markers) {
                if (className.contains(marker)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A plain snapshot of the readings the AI acts on. */
    public static final class Reading {
        public boolean linked;
        public boolean loaded;
        public double temperature = Double.NaN;
        public double meltPoint = Double.NaN;
        public double stability = Double.NaN;
        public boolean collapsing;
        public double explosionIn = -1.0D;
        public int stabilisers;
        public int receivers;
        public int emitters;

        public String describe() {
            if (!this.linked) {
                return "未检测到 DFC 核心。";
            }
            StringBuilder sb = new StringBuilder("核心");
            if (!Double.isNaN(this.temperature)) {
                sb.append(String.format(Locale.ROOT, " 温度 %.0f", this.temperature));
                if (!Double.isNaN(this.meltPoint)) {
                    sb.append(String.format(Locale.ROOT, "/%.0f°C", this.meltPoint));
                }
            }
            if (!Double.isNaN(this.stability)) {
                sb.append(String.format(Locale.ROOT, " 稳定度 %.0f%%", this.stability));
            }
            sb.append(" 稳定器").append(this.stabilisers)
                    .append(" 接收器").append(this.receivers)
                    .append(" 发射器").append(this.emitters);
            if (this.collapsing) {
                sb.append(" [坍缩中]");
            }
            return sb.toString();
        }
    }

    /** Outcome of one control action; always human-readable. */
    public static final class Result {
        public final boolean ok;
        public final String message;

        private Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }

        static Result yes(String message) {
            return new Result(true, message);
        }

        static Result no(String message) {
            return new Result(false, message);
        }
    }

    private DfcControl() {
    }

    /** Finds the nearest DFC core to an anchor within {@code radius}. */
    public static TileEntity core(World world, BlockPos anchor, int radius) {
        return CoreThermal.findCore(world, anchor, radius);
    }

    /** Reads stability, temperature and component counts from a core. */
    public static Reading read(World world, BlockPos anchor, int radius) {
        Reading r = new Reading();
        TileEntity core = core(world, anchor, radius);
        if (core == null) {
            return r;
        }
        r.linked = true;
        r.loaded = DFCReader.isCore(core);
        r.temperature = CoreThermal.temperature(core);
        r.meltPoint = CoreThermal.meltingPoint(core);
        // getDFCStabilization() is 0..1 in the base mod; report it as a percent
        // so operators and AI prompts read the same number.
        Object raw = invoke(core, "getDFCStabilization");
        if (raw instanceof Number) {
            double s = ((Number) raw).doubleValue();
            r.stability = s <= 1.0D ? s * 100.0D : s;
        }
        r.collapsing = DFCReader.collapsing(core);
        r.explosionIn = CoreThermal.explosionIn(core);
        List<TileEntity> parts = components(world, core);
        for (TileEntity part : parts) {
            String name = part.getClass().getSimpleName();
            if (Part.STABILISER.matches(name)) {
                r.stabilisers++;
            } else if (Part.RECEIVER.matches(name)) {
                r.receivers++;
            } else if (Part.EMITTER.matches(name)) {
                r.emitters++;
            }
        }
        return r;
    }

    /**
     * All component tile entities registered around a core, via the addon's
     * accessor or the base mod's field. Defensive copy, as the list is owned by
     * the foreign mod and may be mutated while we walk it.
     */
    private static List<TileEntity> components(World world, TileEntity core) {
        List<TileEntity> out = new ArrayList<TileEntity>();
        if (world == null || core == null) {
            return out;
        }
        Object raw = invoke(core, "getDFCComponentPositions");
        if (!(raw instanceof List)) {
            raw = field(core, "componentPositions");
        }
        if (!(raw instanceof List)) {
            return out;
        }
        List<?> positions;
        try {
            positions = new ArrayList<Object>((List<?>) raw);
        } catch (Throwable raced) {
            return out;
        }
        for (Object entry : positions) {
            if (!(entry instanceof BlockPos)) {
                continue;
            }
            TileEntity part = world.getTileEntity((BlockPos) entry);
            if (part != null) {
                out.add(part);
            }
        }
        return out;
    }

    /**
     * Enables or disables every component of a kind around the nearest core.
     *
     * <p>The public contract of a DFC component is a boolean {@code active}
     * with a {@code setActive(boolean)} setter; both base mod and Cursed forks
     * follow it. When neither is present the call reports honestly instead of
     * guessing — flipping the wrong field in a live core is worse than no-op.</p>
     */
    public static Result setPart(World world, BlockPos anchor, int radius,
                                 Part part, boolean active) {
        if (part == null) {
            return Result.no("未知组件类型。");
        }
        TileEntity core = core(world, anchor, radius);
        if (core == null) {
            return Result.no("未检测到 DFC 核心，无法控制" + part.key() + "。");
        }
        List<TileEntity> matched = new ArrayList<TileEntity>();
        for (TileEntity te : components(world, core)) {
            if (part.matches(te.getClass().getSimpleName())) {
                matched.add(te);
            }
        }
        if (matched.isEmpty()) {
            return Result.no("核心周围没有 " + part.key() + " 组件。");
        }
        int changed = 0;
        for (TileEntity te : matched) {
            if (setActive(te, active)) {
                changed++;
            }
        }
        if (changed == 0) {
            return Result.no("找到 " + matched.size() + " 个 " + part.key()
                    + "，但此版本没有可用的开关方法。");
        }
        return Result.yes("已将 " + changed + "/" + matched.size() + " 个 "
                + part.key() + " 设为 " + (active ? "开启" : "关闭"));
    }

    /** Starts a core. Forks that expose an ignition hook write it directly. */
    public static Result start(World world, BlockPos anchor, int radius) {
        TileEntity core = core(world, anchor, radius);
        if (core == null) {
            return Result.no("未检测到 DFC 核心，无法启动。");
        }
        if (!DFCReader.isCore(core)) {
            return Result.no("目标不是已装载的 DFC 核心。");
        }
        // Whitelisted ignition hooks, most specific first. A no-arg ignite()
        // is preferred over a boolean setter so we never guess a value.
        if (callVoid(core, "igniteDFC") || callVoid(core, "startDFC")
                || callVoid(core, "ignite") || callVoid(core, "startReactor")) {
            return Result.yes("核心已点火启动。");
        }
        if (callBool(core, "setDFCIgnition", true)
                || callBool(core, "setDFCActive", true)
                || callBool(core, "setIgnition", true)
                || callBool(core, "setActive", true)) {
            return Result.yes("核心点火信号已置为开启。");
        }
        // Some forks use a plain boolean field rather than a setter.
        for (String name : new String[]{"dfcIgnition", "ignition", "isActive", "active"}) {
            if (writeBool(core, name, true)) {
                return Result.yes("核心启动字段 " + name + " 已置为 true。");
            }
        }
        return Result.no("此 DFC 版本没有可用的启动钩子；请在核心端输入红石信号启动。");
    }

    /** SCRAMs a core through the independent thermal guard. */
    public static Result scram(World world, BlockPos anchor, int radius) {
        TileEntity core = core(world, anchor, radius);
        if (core == null) {
            return Result.no("未检测到 DFC 核心，无法停堆。");
        }
        String status = CoreGuard.arm(world, core.getPos());
        DFCReader.clearCollapse(core);
        return Result.yes("已接管核心并强制冷却：" + status);
    }

    /** Forces a core temperature, for the AI's direct numeric control. */
    public static Result setTemperature(World world, BlockPos anchor, int radius, double celsius) {
        TileEntity core = core(world, anchor, radius);
        if (core == null) {
            return Result.no("未检测到 DFC 核心，无法设置温度。");
        }
        boolean ok = CoreThermal.writeTemperature(core, Math.max(0.0D, celsius));
        return ok ? Result.yes("核心温度已设为 " + Math.max(0.0D, celsius) + "°C")
                : Result.no("此 DFC 版本没有可用的温度写入钩子。");
    }

    // ------------------------------------------------------------- reflection

    /** True when a boolean setter/field on a component was written. */
    private static boolean setActive(TileEntity part, boolean active) {
        for (String name : new String[]{"setActive", "setDFCActive", "setEnabled", "setPowered"}) {
            if (callBool(part, name, active)) {
                return true;
            }
        }
        for (String name : new String[]{"active", "isActive", "enabled"}) {
            if (writeBool(part, name, active)) {
                return true;
            }
        }
        return false;
    }

    private static boolean callBool(Object target, String name, boolean value) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 1) {
                continue;
            }
            Class<?> t = m.getParameterTypes()[0];
            if (t != boolean.class && t != Boolean.class) {
                continue;
            }
            try {
                m.invoke(target, Boolean.valueOf(value));
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean callVoid(Object target, String name) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != 0) {
                continue;
            }
            try {
                m.invoke(target);
                return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static boolean writeBool(Object target, String name, boolean value) {
        Field f = findField(target.getClass(), name);
        if (f == null || f.getType() != boolean.class && f.getType() != Boolean.class) {
            return false;
        }
        try {
            f.setBoolean(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> start, String name) {
        for (Class<?> c = start; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            } catch (Throwable refused) {
                return null;
            }
        }
        return null;
    }

    private static Object invoke(Object target, String name, Object... args) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) {
                continue;
            }
            try {
                return m.invoke(target, args);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object field(Object target, String name) {
        Field f = findField(target.getClass(), name);
        if (f == null) {
            return null;
        }
        try {
            return f.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

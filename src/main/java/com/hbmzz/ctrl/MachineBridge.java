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

package com.hbmzz.ctrl;

import com.hbmzz.HBMZZ;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The bridge between this panel and the machines of HBM's Nuclear Tech Mod.
 *
 * <p>NTM: Cursed Edition gives a machine exactly two verbs. It can <b>report</b>
 * - {@code IControllable.getQueryData()} hands back a map of named numbers and
 * strings - and it can be <b>told</b>: {@code IControllable.receiveEvent(pos,
 * event)} where the event is a named bag of variables. That is the entire
 * contract, and it is the same one CE's own control panel speaks. Everything
 * this class does is those two calls.</p>
 *
 * <p>Reflection rather than a compile dependency, for the same reason the DFC
 * reader is reflective: this mod is a small addon that must survive the player
 * running a different NTM build, and NTM is not on any Maven that Gradle can
 * reach. The cost is one lookup per verb, cached; the win is that a missing or
 * renamed interface degrades to "面板读不到数据" instead of a crash on startup.</p>
 *
 * <p>The event vocabulary is not invented here either - it is read back off the
 * machines themselves, and {@link #KNOWN_EVENTS} documents the ones shipped
 * with CE 2.5 so the editor can offer them before the first binding exists.</p>
 */
public final class MachineBridge {

    // ---- CE 2.5 event vocabulary, transcribed from the machines themselves --
    //
    //   rbmk_ctrl_set_level    level   (0-100, 控制棒插入百分比)
    //   rbmk_ctrl_set_color    color
    //   rbmk_crane_move        up / down / left / right
    //   rbmk_crane_load        -
    //   tank_set_mode          mode    (0-3)
    //   turret_switch          isOn
    //   turret_set_target      players / hostile / passive / machines
    //   siren_set_state        isOn
    //   door_toggle            -
    //   door_open_state        state
    //   lever_toggle           -
    //   spinny_light_power     isOn
    //
    // Keys, likewise, are whatever the machine puts in getQueryData().

    /** Suggested (event, variable) pairs per machine class, for the editor. */
    private static final Map<String, String[][]> KNOWN_EVENTS = new LinkedHashMap<String, String[][]>();

    static {
        KNOWN_EVENTS.put("TileEntityRBMKControlManual", new String[][]{
                {"rbmk_ctrl_set_level", "level"}, {"rbmk_ctrl_set_color", "color"}});
        KNOWN_EVENTS.put("TileEntityRBMKControl", new String[][]{
                {"rbmk_ctrl_set_level", "level"}});
        KNOWN_EVENTS.put("TileEntityRBMKCraneConsole", new String[][]{
                {"rbmk_crane_move", "up"}, {"rbmk_crane_move", "down"},
                {"rbmk_crane_move", "left"}, {"rbmk_crane_move", "right"},
                {"rbmk_crane_load", ""}});
        KNOWN_EVENTS.put("TileEntityMachineFluidTank", new String[][]{
                {"tank_set_mode", "mode"}});
        KNOWN_EVENTS.put("TileEntityTurretBaseNT", new String[][]{
                {"turret_switch", "isOn"}, {"turret_set_target", "players"},
                {"turret_set_target", "hostile"}, {"turret_set_target", "passive"},
                {"turret_set_target", "machines"}});
        KNOWN_EVENTS.put("TileEntityMachineSiren", new String[][]{
                {"siren_set_state", "isOn"}});
        KNOWN_EVENTS.put("TileEntityBlastDoor", new String[][]{
                {"door_toggle", ""}});
        KNOWN_EVENTS.put("TileEntityDoorGeneric", new String[][]{
                {"door_toggle", ""}});
        KNOWN_EVENTS.put("TileEntityBMPowerBox", new String[][]{
                {"lever_toggle", ""}});
        KNOWN_EVENTS.put("TileEntitySpinnyLight", new String[][]{
                {"spinny_light_power", "isOn"}});
    }

    /** Chinese names for the classes we know, so the sidebar reads like a panel. */
    private static final Map<String, String> NAMES = new LinkedHashMap<String, String>();

    static {
        NAMES.put("TileEntityRBMKControlManual", "RBMK 控制棒（手动）");
        NAMES.put("TileEntityRBMKControlAuto", "RBMK 控制棒（自动）");
        NAMES.put("TileEntityRBMKControl", "RBMK 控制棒");
        NAMES.put("TileEntityRBMKBoiler", "RBMK 蒸汽发生器");
        NAMES.put("TileEntityRBMKCraneConsole", "RBMK 吊车控制台");
        NAMES.put("TileEntityRBMKBase", "RBMK 通道");
        NAMES.put("TileEntityMachineFluidTank", "流体储罐");
        NAMES.put("TileEntityTurretBaseNT", "炮塔");
        NAMES.put("TileEntityMachineSiren", "警报器");
        NAMES.put("TileEntityBlastDoor", "防爆闸门");
        NAMES.put("TileEntityDoorGeneric", "门");
        NAMES.put("TileEntityBMPowerBox", "电源盒");
        NAMES.put("TileEntitySpinnyLight", "旋转警示灯");
        NAMES.put("TileEntityControlPanel", "控制面板");
    }

    private static boolean probed;
    private static boolean available;

    private static Class<?> controllableClass;
    private static Class<?> eventClass;
    private static Class<?> dataValueClass;
    private static Method getQueryData;
    private static Method receiveEvent;
    private static Method getInEvents;
    private static Method getOutEvents;
    private static Method newEvent;
    private static Constructor<?> eventConstructor;
    private static Method setVarFloat;
    private static Method setVarBool;
    private static Method setVarString;
    private static Method dataNumber;
    private static Method dataBoolean;
    private static Method dataType;

    private MachineBridge() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            controllableClass = Class.forName("com.hbm.inventory.control_panel.IControllable");
            eventClass = Class.forName("com.hbm.inventory.control_panel.ControlEvent");
            dataValueClass = Class.forName("com.hbm.inventory.control_panel.types.DataValue");
            getQueryData = controllableClass.getMethod("getQueryData");
            getInEvents = controllableClass.getMethod("getInEvents");
            getOutEvents = controllableClass.getMethod("getOutEvents");
            receiveEvent = controllableClass.getMethod("receiveEvent", BlockPos.class, eventClass);
            newEvent = eventClass.getMethod("newEvent", String.class);
            eventConstructor = eventClass.getConstructor(String.class);
            setVarFloat = eventClass.getMethod("setVar", String.class, float.class);
            setVarBool = eventClass.getMethod("setVar", String.class, boolean.class);
            setVarString = eventClass.getMethod("setVar", String.class, String.class);
            dataNumber = dataValueClass.getMethod("getNumber");
            dataBoolean = dataValueClass.getMethod("getBoolean");
            dataType = dataValueClass.getMethod("getType");
            available = true;
        } catch (Throwable throwable) {
            // Not an error: NTM may simply not be installed, or a future build
            // may have renamed the interface. The panel then shows "--" and
            // says why, which is a better failure than a startup crash.
            available = false;
            controllableClass = null;
        }
    }

    public static boolean available() {
        probe();
        return available;
    }

    public static boolean isControllable(TileEntity tile) {
        probe();
        return available && tile != null && controllableClass.isInstance(tile);
    }

    public static boolean isControllable(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        return isControllable(world.getTileEntity(pos));
    }

    /** Display name for a tile, falling back to the class name. */
    public static String describe(TileEntity tile) {
        if (tile == null) {
            return "空";
        }
        String simple = tile.getClass().getSimpleName();
        String known = NAMES.get(simple);
        if (known != null) {
            return known;
        }
        for (Map.Entry<String, String> entry : NAMES.entrySet()) {
            if (simple.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return simple;
    }

    /**
     * Fills a link slot from whatever machine is at its position: the label,
     * the data keys it reports and the events it listens for.
     *
     * @return false when the block is gone or is not a controllable machine
     */
    public static boolean describe(CtrlLink link, World world) {
        if (link == null || link.isEmpty() || world == null) {
            return false;
        }
        TileEntity tile = world.isBlockLoaded(link.pos) ? world.getTileEntity(link.pos) : null;
        if (!isControllable(tile)) {
            link.label = "（不是可控机器）";
            link.keys.clear();
            link.events.clear();
            return false;
        }
        link.label = describe(tile);
        link.keys.clear();
        link.keys.addAll(keysOf(tile));
        link.events.clear();
        link.events.addAll(eventsOf(tile));
        link.vars.clear();
        link.vars.putAll(varsOf(tile));
        return true;
    }

    /**
     * Event name to the variable it carries, for the machines we have a
     * transcribed vocabulary for.
     *
     * <p>Only the known table can answer this: NTM's {@code IControllable}
     * announces <em>which</em> events it takes but not what a caller is expected
     * to put in them, so an addon machine's variable name is genuinely
     * unknowable without reading its code. An empty answer is therefore normal
     * and means "type the variable yourself", not "there is none".</p>
     */
    public static Map<String, String> varsOf(TileEntity tile) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String[][] known = KNOWN_EVENTS.get(tile == null ? "" : tile.getClass().getSimpleName());
        if (known != null) {
            for (String[] pair : known) {
                if (pair.length > 1 && !pair[1].isEmpty()) {
                    out.put(pair[0], pair[1]);
                }
            }
        }
        return out;
    }

    /** Data keys the machine reports right now; empty when it reports nothing. */
    public static List<String> keysOf(TileEntity tile) {
        Sample sample = sample(tile);
        return sample == null ? Collections.<String>emptyList() : new ArrayList<String>(sample.numbers.keySet());
    }

    /** In-events the machine accepts, plus the ones we know for its class. */
    public static List<String> eventsOf(TileEntity tile) {
        Set<String> out = new LinkedHashSet<String>();
        probe();
        if (available && tile != null) {
            try {
                Object result = getInEvents.invoke(tile);
                if (result instanceof List) {
                    for (Object name : (List<?>) result) {
                        if (name != null) {
                            out.add(String.valueOf(name));
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        String[][] known = KNOWN_EVENTS.get(tile == null ? "" : tile.getClass().getSimpleName());
        if (known != null) {
            for (String[] pair : known) {
                out.add(pair[0]);
            }
        }
        return new ArrayList<String>(out);
    }

    /**
     * Out-events the machine announces.
     *
     * <p>These are the events a machine pushes at the panel - a door reporting it
     * opened, a siren reporting its state. Some machines declare none, and some
     * declare them only at runtime, so an empty list here is normal.</p>
     */
    public static List<String> outEventsOf(TileEntity tile) {
        probe();
        List<String> out = new ArrayList<String>();
        if (!available || tile == null) {
            return out;
        }
        try {
            Object result = getOutEvents.invoke(tile);
            if (result instanceof List) {
                for (Object name : (List<?>) result) {
                    if (name != null) {
                        out.add(String.valueOf(name));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** The default variable name for an event of this machine, or "". */    public static String defaultVar(TileEntity tile, String event) {
        String[][] known = KNOWN_EVENTS.get(tile == null ? "" : tile.getClass().getSimpleName());
        if (known != null) {
            for (String[] pair : known) {
                if (pair[0].equals(event)) {
                    return pair[1];
                }
            }
        }
        return "";
    }

    /**
     * One reading of a machine: its numbers and its strings.
     *
     * <p>Both maps come from a single {@code getQueryData()} call, because that
     * call allocates a fresh map every time and a panel with twelve readouts on
     * one machine would otherwise allocate twelve of them per tick.</p>
     */
    public static final class Sample {

        public final Map<String, Double> numbers = new LinkedHashMap<String, Double>();
        public final Map<String, String> texts = new LinkedHashMap<String, String>();

        public boolean has(String key) {
            return this.numbers.containsKey(key) || this.texts.containsKey(key);
        }

        public double number(String key) {
            Double value = this.numbers.get(key);
            if (value != null) {
                return value.doubleValue();
            }
            String text = this.texts.get(key);
            if (text == null) {
                return Double.NaN;
            }
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }

        public String text(String key) {
            String text = this.texts.get(key);
            if (text != null) {
                return text;
            }
            Double value = this.numbers.get(key);
            return value == null ? "" : trim(value.doubleValue());
        }

        private static String trim(double value) {
            return Math.abs(value - Math.rint(value)) < 1.0E-4D
                    ? String.valueOf((long) Math.rint(value))
                    : String.format(java.util.Locale.ROOT, "%.2f", value);
        }
    }

    /** Reads a machine, or null when it cannot be read. */
    public static Sample sample(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            return null;
        }
        return sample(world.getTileEntity(pos));
    }

    public static Sample sample(TileEntity tile) {
        probe();
        if (!available || !isControllable(tile)) {
            return null;
        }
        Sample sample = new Sample();
        try {
            Object data = getQueryData.invoke(tile);
            if (!(data instanceof Map)) {
                return sample;
            }
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) data).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                Object value = entry.getValue();
                // Ask the value what it is rather than guessing: NTM's string
                // value still answers getNumber() (with 0), so probing by
                // number would turn a fluid name into a plain zero.
                boolean number = "NUMBER".equals(typeName(value));
                if (number) {
                    try {
                        double num = ((Number) dataNumber.invoke(value)).doubleValue();
                        sample.numbers.put(key, Double.valueOf(num));
                    } catch (Throwable throwable) {
                        number = false;
                    }
                }
                if (!number) {
                    sample.texts.put(key, String.valueOf(value));
                }
            }
        } catch (Throwable throwable) {
            warn("读取机器数据失败", throwable);
            return null;
        }
        return sample;
    }

    /**
     * Sends one control event to a machine.
     *
     * <p>The value is delivered as a number unless {@code text} is set, in
     * which case it goes as a string - that is how a machine that wants a name
     * (a fluid type) is told apart from one that wants a percentage. Flag
     * variables ({@code isOn}, {@code state}) are numbers too: NTM's
     * {@code DataValueFloat} answers {@code getBoolean()} for them, so 1 and 0
     * are the honest encoding and the extra overload would only be a second way
     * to say the same thing.</p>
     */
    public static boolean fire(World world, BlockPos pos, String event, String var, double value, String text) {
        probe();
        if (!available || world == null || pos == null || event == null || event.isEmpty()) {
            return false;
        }
        if (!world.isBlockLoaded(pos)) {
            return false;
        }
        TileEntity tile = world.getTileEntity(pos);
        if (!isControllable(tile)) {
            return false;
        }
        try {
            Object controlEvent = buildEvent(event);
            if (controlEvent == null) {
                return false;
            }
            if (var != null && !var.isEmpty()) {
                if (text != null && !text.isEmpty()) {
                    setVarString.invoke(controlEvent, var, text);
                } else {
                    setVarFloat.invoke(controlEvent, var, Float.valueOf((float) value));
                }
            }
            receiveEvent.invoke(tile, pos, controlEvent);
            return true;
        } catch (Throwable throwable) {
            warn("发送机器事件 " + event + " 失败", throwable);
            return false;
        }
    }

    /**
     * Sends one event with several variables at once.
     *
     * <p>{@link #fire} carries a single variable because that is all the DFC
     * bridge needed. A control panel event is a bag - {@code turret_set_target}
     * alone carries four flags - and sending them one call at a time would build
     * and dispatch a separate event object per variable, each of which the
     * machine would treat as a complete command.</p>
     *
     * <p>Values are {@link String} for text (a fluid name, a target group) and
     * {@link Number} for everything else.</p>
     */
    public static boolean fireAll(World world, BlockPos pos, String event, Map<String, ?> vars) {
        probe();
        if (!available || world == null || pos == null || event == null || event.isEmpty()) {
            return false;
        }
        if (!world.isBlockLoaded(pos)) {
            return false;
        }
        TileEntity tile = world.getTileEntity(pos);
        if (!isControllable(tile)) {
            return false;
        }
        try {
            Object controlEvent = buildEvent(event);
            if (controlEvent == null) {
                return false;
            }
            if (vars != null) {
                for (Map.Entry<String, ?> entry : vars.entrySet()) {
                    Object value = entry.getValue();
                    if (value instanceof String) {
                        setVarString.invoke(controlEvent, entry.getKey(), value);
                    } else if (value instanceof Number) {
                        setVarFloat.invoke(controlEvent, entry.getKey(),
                                Float.valueOf(((Number) value).floatValue()));
                    }
                }
            }
            receiveEvent.invoke(tile, pos, controlEvent);
            return true;
        } catch (Throwable throwable) {
            warn("发送机器事件 " + event + " 到 " + describe(tile) + " 失败（变量 "
                    + (vars == null ? "-" : vars.keySet()) + "）", throwable);
            return false;
        }
    }

    /**
     * A fresh upstream event object named {@code name}.
     *
     * <p>{@code ControlEvent.newEvent} is the registry's own factory: it looks
     * the name up and throws when it is missing. That is fine for the stock
     * table but not for us - an addon machine can announce an event the table
     * never had, and a graph saved before the table was mirrored can hold one of
     * those names. Upstream's event object is just a name plus a variable bag,
     * so the plain constructor carries exactly the same information; falling
     * back to it keeps the command deliverable instead of dropping it into a
     * once-per-session warning.</p>
     */
    private static Object buildEvent(String name) {
        try {
            return newEvent.invoke(null, name);
        } catch (Throwable notRegistered) {
            try {
                return eventConstructor == null ? null : eventConstructor.newInstance(name);
            } catch (Throwable broken) {
                return null;
            }
        }
    }

    /** Enum constant name of a DataValue's type, e.g. "NUMBER"; "" when unknown. */
    private static String typeName(Object dataValue) {
        try {
            Object type = dataType.invoke(dataValue);
            return type instanceof Enum ? ((Enum<?>) type).name() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void warn(String what, Throwable throwable) {
        // Once per distinct failure, not once per session: a machine that throws
        // for one event says nothing about the next one, and a blanket "already
        // warned" flag is what hid the real cause here - every event that failed
        // to deliver looked exactly like an event that was never sent.
        Throwable cause = throwable instanceof java.lang.reflect.InvocationTargetException
                && throwable.getCause() != null ? throwable.getCause() : throwable;
        if (!WARNED.add(what + " | " + cause.getClass().getName())) {
            return;
        }
        HBMZZ.logger.warn("HBMZZ control panel: " + what + "（同类错误不再重复记录）", cause);
    }

    private static final Set<String> WARNED = new LinkedHashSet<String>();
}

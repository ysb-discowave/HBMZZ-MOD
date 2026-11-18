/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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

package com.hbmzz.cecp.compat;

import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mirrors NTM: Cursed Edition's live control-event registry into this mod's own
 * one.
 *
 * <p>Why this exists: the port used to carry a hand-copied list of the sixteen
 * stock events, transcribed from {@code ControlEvent.init()}. That list is only
 * correct for a bare CE install. CE addons extend the same table at runtime -
 * CE Addon (Leafia) mixes a tail-hook into {@code ControlEvent.init()} and
 * registers seventeen more events ({@code set_booster_level},
 * {@code pwr_ctrl_set_level}, {@code set_pump_pressure}, ...). A machine that
 * declares one of those still links up, but the name is unknown to the copied
 * table, so {@code getOrRegister} quietly hands back an event with no
 * variables: the broadcast node shows only {@code Cancel}, and the event-data
 * node has nothing to read. That is exactly the "参数只剩一个" symptom.</p>
 *
 * <p>Copying a list can never stay in sync, so the list is not copied at all:
 * the live registry is read reflectively and any event this mod does not yet
 * know is added with its variables intact. Reflection is used because NTM is
 * not a compile dependency; when it is absent nothing happens.</p>
 */
public final class CEEventTable {

    private static boolean probed;
    private static boolean available;
    private static Field registryField;
    private static Field varsField;
    private static Method typeOf;
    private static Method numberOf;

    private CEEventTable() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            Class<?> eventClass = Class.forName("com.hbm.inventory.control_panel.ControlEvent");
            registryField = eventClass.getDeclaredField("REGISTRY");
            registryField.setAccessible(true);
            varsField = eventClass.getField("vars");
            Class<?> valueClass = Class.forName("com.hbm.inventory.control_panel.types.DataValue");
            typeOf = valueClass.getMethod("getType");
            numberOf = valueClass.getMethod("getNumber");
            available = true;
        } catch (Throwable ignored) {
            available = false;
        }
    }

    /** True when CE's control-panel classes could be reached. */
    public static boolean isAvailable() {
        probe();
        return available;
    }

    /**
     * Adds every event of CE's registry that this mod does not know yet.
     *
     * @return how many events were added; {@code 0} when CE is missing or the
     *         registry already matches
     */
    public static int mirror() {
        probe();
        if (!available) {
            return 0;
        }
        int added = 0;
        try {
            Object table = registryField.get(null);
            if (!(table instanceof Map)) {
                return 0;
            }
            Map<?, ?> registry = (Map<?, ?>) table;
            List<Object> keys = new ArrayList<Object>(registry.keySet());
            for (Object key : keys) {
                String name = String.valueOf(key);
                // An empty definition is not a definition: getOrRegister invents
                // one for every unknown name, so anything the panel touched before
                // the mirror ran sits in the table with no variables and would
                // otherwise shadow the real one forever.
                ControlEvent existing = ControlEvent.peek(name);
                if (existing != null && !existing.vars.isEmpty()) {
                    continue;
                }
                Object upstream = registry.get(key);
                if (upstream == null) {
                    continue;
                }
                ControlEvent local = new ControlEvent(name);
                Object vars = varsField.get(upstream);
                if (vars instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) vars).entrySet()) {
                        DataValue converted = convert(entry.getValue());
                        if (converted != null) {
                            local.vars.put(String.valueOf(entry.getKey()), converted);
                        }
                    }
                }
                ControlEvent.register(local);
                added++;
            }
        } catch (Throwable error) {
            CPEnv.logger.warn("Could not mirror the CE control-event table", error);
        }
        return added;
    }

    /**
     * Converts one upstream {@code DataValue} into this mod's equivalent.
     *
     * <p>Enum values come across as their ordinal, which is how NTM itself
     * transports them; everything else keeps number-or-string identity. A value
     * that cannot be converted is dropped rather than guessed at, so a broken
     * addon entry cannot produce a port with a nonsense default.</p>
     */
    private static DataValue convert(Object value) {
        if (value == null) {
            return null;
        }
        try {
            Object type = typeOf.invoke(value);
            String kind = type instanceof Enum ? ((Enum<?>) type).name() : "NUMBER";
            if ("STRING".equals(kind)) {
                return new DataValueString(String.valueOf(value));
            }
            if ("COMPOSITE".equals(kind)) {
                return null;
            }
            Object num = numberOf.invoke(value);
            return num instanceof Number ? new DataValueFloat(((Number) num).floatValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}

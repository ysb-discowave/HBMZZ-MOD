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

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reads a CE RBMK column's status for the RBMK display control.
 *
 * <p>The addon calls {@code TileEntityRBMKBase.getConsoleData()} and reads the
 * returned {@code RBMKColumn} directly. Neither class is a dependency here, so
 * the column is read reflectively and flattened into {@link Column}. A tile that
 * is not an RBMK part simply yields {@code null} and the display draws nothing
 * for that cell - which is also what upstream does for empty columns.</p>
 */
public final class RBMKColumns {

    private static final java.util.Map<Class<?>, Method> READERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<Class<?>> NO_READER =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Class<?>, Boolean>());

    /** One cell of the RBMK map, already flattened to primitives. */
    public static final class Column {
        public double heat;
        public double maxHeat;
        /** CONTROL / CONTROL_AUTO: rod insertion 0…1. */
        public double level;
        /** FUEL / FUEL_SIM: enrichment 0…1. */
        public double enrichment;
        /** Control rod colour code, -1 when the rod has none. */
        public int color = -1;
        public String type = "";

        public boolean isFuel() {
            return "FUEL".equals(type) || "FUEL_SIM".equals(type);
        }

        public boolean isControl() {
            return "CONTROL".equals(type);
        }

        public boolean isControlAuto() {
            return "CONTROL_AUTO".equals(type);
        }
    }

    /** The console readout for {@code pos}, or null when it is not an RBMK part. */
    public static Column at(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        TileEntity tile = world.getTileEntity(pos);
        if (tile == null) {
            return null;
        }
        Object raw = consoleData(tile);
        if (raw == null) {
            return null;
        }

        Column out = new Column();
        out.heat = number(raw, "heat", 0);
        out.maxHeat = number(raw, "maxHeat", 0);
        Object type = field(raw, "type");
        out.type = type == null ? "" : type.toString();
        // Both live on subclasses (ControlColumn / FuelColumn), so a lookup that
        // only walks the declaring class would miss them.
        out.level = number(raw, "level", 0);
        out.enrichment = number(raw, "enrichment", 0);
        Object color = field(raw, "color");
        if (color instanceof Number) {
            out.color = ((Number) color).intValue();
        }
        return out;
    }

    private static Object consoleData(TileEntity tile) {
        Class<?> type = tile.getClass();
        if (NO_READER.contains(type)) {
            return null;
        }
        Method method = READERS.get(type);
        if (method == null) {
            try {
                method = type.getMethod("getConsoleData");
            } catch (Throwable absent) {
                // Remember the miss so a map full of non-RBMK tiles does not
                // re-probe the same class every frame.
                NO_READER.add(type);
                return null;
            }
            READERS.put(type, method);
        }
        try {
            return method.invoke(tile);
        } catch (Throwable failed) {
            return null;
        }
    }

    private static Object field(Object target, String name) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getField(name);
                return f.get(target);
            } catch (NoSuchFieldException next) {
                // keep walking up
            } catch (Throwable failed) {
                return null;
            }
        }
        return null;
    }

    private static double number(Object target, String name, double fallback) {
        Object value = field(target, name);
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private RBMKColumns() {
    }
}

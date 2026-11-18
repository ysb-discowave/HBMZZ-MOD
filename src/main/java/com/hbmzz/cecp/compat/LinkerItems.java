/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

import com.hbmzz.item.ItemMonitorLinker;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.lang.reflect.Method;

/**
 * The two items that can carry machine coordinates into the panel's linker tab.
 *
 * <p>In NTM: Cursed Edition that tab takes exactly one item - the multi detonator
 * ({@code com.hbm.items.tool.ItemMultiDetonator}), whose {@code NBT "pos"} list of
 * longs is written by right-clicking machines and read back by
 * {@code getLocations}. This mod has no detonator, so the port shipped its own
 * monitor data line with the same NBT layout. Refusing the detonator meant a
 * player following the upstream workflow - record with the detonator, put it in
 * the slot - got nothing, and the machines never entered the panel.</p>
 *
 * <p>Both are accepted now. The detonator is reached reflectively for the usual
 * reason: NTM is not a compile dependency, and a missing class must degrade to
 * "only the data line works" instead of a crash on startup.</p>
 */
public final class LinkerItems {

    private static boolean probed;
    private static Class<?> multiDetonator;
    private static Method multiLocations;

    private LinkerItems() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            multiDetonator = Class.forName("com.hbm.items.tool.ItemMultiDetonator");
            multiLocations = multiDetonator.getMethod("getLocations", ItemStack.class);
        } catch (Throwable throwable) {
            multiDetonator = null;
            multiLocations = null;
        }
    }

    /** True for the monitor data line and for NTM's multi detonator. */
    public static boolean isLinker(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        probe();
        Item item = stack.getItem();
        if (item instanceof ItemMonitorLinker) {
            return true;
        }
        return multiDetonator != null && multiDetonator.isInstance(item);
    }

    /**
     * {@code {{x...}, {y...}, {z...}}} of every coordinate recorded on the item,
     * or {@code null} when it carries none.
     */
    public static int[][] locations(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        probe();
        Item item = stack.getItem();
        if (item instanceof ItemMonitorLinker) {
            return ItemMonitorLinker.getLocations(stack);
        }
        if (multiDetonator != null && multiDetonator.isInstance(item)) {
            try {
                Object result = multiLocations.invoke(null, stack);
                return result instanceof int[][] ? (int[][]) result : null;
            } catch (Throwable throwable) {
                return null;
            }
        }
        return null;
    }

    /** Name used in the linker tab's status line. */
    public static String name(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        probe();
        if (multiDetonator != null && multiDetonator.isInstance(stack.getItem())) {
            return "多发起爆器";
        }
        return stack.getDisplayName();
    }
}

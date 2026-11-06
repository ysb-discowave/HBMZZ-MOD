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

package com.hbmzz.rbmk;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Everything this mod knows about somebody else's reactor, by reflection.
 *
 * <p>The RBMK is not ours. {@code TileEntityRBMKBase} lives in the base mod -
 * and its jump animation lives in a second addon that may or may not be
 * installed - so this mod ships without a compile-time dependency on either.
 * That is the same arrangement {@code MachineBridge} uses for machine tinkering,
 * and for the same reason: this jar has to load on a pack without Cursed
 * Edition, and a {@code NoClassDefFoundError} in a mod loader is not something
 * a player can do anything about.</p>
 *
 * <p>Every accessor degrades to a harmless default. A missing field means
 * "this install cannot jump", not "crash", and the caller reads that as a
 * reason to skip the animation rather than to skip the explosion.</p>
 */
final class RbmkParts {

    /** Base-mod reactor column. */
    private static final String BASE = "com.hbm.tileentity.machine.rbmk.TileEntityRBMKBase";
    /** Base-mod block, where the lid metadata and the column texture live. */
    private static final String BLOCK = "com.hbm.blocks.machine.rbmk.RBMKBase";
    /** Dial rules, where the column height lives. */
    private static final String DIALS = "com.hbm.tileentity.machine.rbmk.RBMKDials";

    /** Damage field added by the Cursed Edition addon. Its jump reads this. */
    private static final String JUMP_FIELD = "leafia$damage";

    /** Capacity the base mod declares. Only a fallback - see RbmkTiming. */
    private static final int FALLBACK_STEAM = 16000;
    private static final int FALLBACK_COLUMN_HEIGHT = 4;

    private static boolean looked;
    private static Class<?> baseClass;
    private static Class<?> blockClass;
    private static Class<?> dialsClass;

    private static Field fieldHeat;
    private static Field fieldSteam;
    private static Field fieldJump;
    private static Field fieldColumnTexture;
    private static Field fieldMaxSteam;
    private static Field fieldNoLid;

    private static Method methodMaxHeat;
    private static Method methodHasLid;
    private static Method methodMeltdown;
    private static Method methodMetaToLid;
    private static Method methodColumnHeight;
    private static Method methodHasOwnLid;

    private RbmkParts() {
    }

    /** True when the base mod's reactor class is on the classpath. */
    static boolean available() {
        resolve();
        return baseClass != null;
    }

    /** True when the Cursed Edition's jump field exists on the column. */
    static boolean canJump() {
        resolve();
        return fieldJump != null;
    }

    /** True when this tile entity is a reactor column. */
    static boolean isColumn(TileEntity te) {
        resolve();
        return baseClass != null && te != null && baseClass.isInstance(te);
    }

    static double heat(Object column) {
        return readDouble(fieldHeat, column);
    }

    /** Writes heat back. Used only to hold a column under the base meltdown line. */
    static void setHeat(Object column, double value) {
        writeDouble(fieldHeat, column, value);
    }

    static int steam(Object column) {
        return readInt(fieldSteam, column, 0);
    }

    static double maxHeat(Object column) {
        Object value = invoke(methodMaxHeat, column);
        return value instanceof Double ? (Double) value : 1500.0D;
    }

    static boolean hasLid(Object column) {
        Object value = invoke(methodHasLid, column);
        return value instanceof Boolean ? (Boolean) value : true;
    }

    /**
     * Hands the Cursed Edition a jump amplitude.
     *
     * <p>The field is that addon's own damage counter: it rises, the jump height
     * rises with it, the rod leaves the block and comes back down. Writing it
     * directly is how this mod reuses that animation instead of drawing a second
     * one next to it - two jump animations on one rod would fight.</p>
     *
     * @return false when the addon is absent, in which case nothing jumps and
     *         the caller should not pretend otherwise.
     */
    static boolean jump(Object column, int damage) {
        if (fieldJump == null) {
            return false;
        }
        int value = Math.max(0, Math.min(RbmkTiming.DAMAGE_END, damage));
        return writeInt(fieldJump, column, value);
    }

    /** Declared steam capacity, or the base mod's 16000 when unreadable. */
    static int maxSteam() {
        resolve();
        if (fieldMaxSteam == null) {
            return FALLBACK_STEAM;
        }
        try {
            return fieldMaxSteam.getInt(null);
        } catch (Throwable ignored) {
            return FALLBACK_STEAM;
        }
    }

    /** Column height in blocks, as the base mod's dial says for this world. */
    static int columnHeight(World world) {
        resolve();
        if (methodColumnHeight == null || world == null) {
            return FALLBACK_COLUMN_HEIGHT;
        }
        try {
            Object value = methodColumnHeight.invoke(null, world);
            return value instanceof Integer ? Math.max(1, (Integer) value) : FALLBACK_COLUMN_HEIGHT;
        } catch (Throwable ignored) {
            return FALLBACK_COLUMN_HEIGHT;
        }
    }

    /** Metadata value that means "this column has no lid". */
    static int lidNoneMeta() {
        resolve();
        if (fieldNoLid == null) {
            return -1;
        }
        try {
            Object value = fieldNoLid.get(null);
            if (value instanceof Enum) {
                // The lid type is carried in the block metadata as a facing
                // ordinal offset by ten, so "no lid" is an ordinal, not a bit.
                return ((Enum<?>) value).ordinal() + 10;
            }
            return -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 0 = no lid, 1 = steel cover, 2 = glass, -1 = not a lid column. */
    static int lidType(int meta) {
        resolve();
        if (methodMetaToLid == null) {
            return -1;
        }
        try {
            Object value = methodMetaToLid.invoke(null, meta);
            return value instanceof Integer ? (Integer) value : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * The column texture name, e.g. {@code rbmk_boiler}.
     *
     * <p>The lid of a column is drawn from that column's own cover sprites, so
     * a plate that carries the lids of six different column types has to
     * remember which is which - this is the "which".</p>
     */
    static String columnTexture(Block block) {
        resolve();
        if (fieldColumnTexture == null || block == null) {
            return "";
        }
        try {
            Object value = fieldColumnTexture.get(block);
            return value instanceof String ? (String) value : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * True for columns that draw their own lid.
     *
     * <p>Control rods do: the lid is part of the rod model, because the rod has
     * to slide through it. Those columns have nothing to throw, so a plate that
     * claimed their lid would leave a rod hovering over an open channel.</p>
     */
    static boolean hasOwnLid(Block block) {
        if (block == null) {
            return false;
        }
        Object value = invoke(methodHasOwnLid, block);
        return value instanceof Boolean && (Boolean) value;
    }

    /** Destroys the reactor the way the base mod does it, debris and all. */
    static void meltdown(Object column) {
        invoke(methodMeltdown, column);
    }

    // ================================================================= reading

    private static void resolve() {
        if (looked) {
            return;
        }
        looked = true;
        baseClass = load(BASE);
        blockClass = load(BLOCK);
        dialsClass = load(DIALS);
        if (baseClass == null) {
            return;
        }
        fieldHeat = field(baseClass, "heat");
        fieldSteam = field(baseClass, "reasimSteam");
        fieldMaxSteam = field(baseClass, "maxSteam");
        // Optional: only exists when the Cursed Edition addon is installed.
        fieldJump = field(baseClass, JUMP_FIELD);
        methodMaxHeat = method(baseClass, "maxHeat");
        methodHasLid = method(baseClass, "hasLid");
        methodMeltdown = method(baseClass, "meltdown");
        if (blockClass != null) {
            fieldColumnTexture = field(blockClass, "columnTexture");
            fieldNoLid = field(blockClass, "DIR_NO_LID");
            methodMetaToLid = method(blockClass, "metaToLid", int.class);
            methodHasOwnLid = method(blockClass, "hasOwnLid");
        }
        if (dialsClass != null) {
            methodColumnHeight = method(dialsClass, "getColumnHeight", World.class);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field found = owner.getField(name);
            found.setAccessible(true);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) {
        try {
            Method found = owner.getMethod(name, args);
            found.setAccessible(true);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double readDouble(Field field, Object target) {
        if (field == null || target == null) {
            return 0.0D;
        }
        try {
            return field.getDouble(target);
        } catch (Throwable ignored) {
            return 0.0D;
        }
    }

    private static int readInt(Field field, Object target, int fallback) {
        if (field == null || target == null) {
            return fallback;
        }
        try {
            return field.getInt(target);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static void writeDouble(Field field, Object target, double value) {
        if (field == null || target == null) {
            return;
        }
        try {
            field.setDouble(target, value);
        } catch (Throwable ignored) {
            // A field that cannot be written is the same as a field that is not
            // there: the caller already treats a missing jump as "no animation".
        }
    }

    private static boolean writeInt(Field field, Object target, int value) {
        if (field == null || target == null) {
            return false;
        }
        try {
            field.setInt(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Object invoke(Method method, Object target) {
        if (method == null || target == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }
}

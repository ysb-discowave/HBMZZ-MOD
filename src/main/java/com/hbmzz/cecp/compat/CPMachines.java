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

import com.hbmzz.cecp.IControllable;
import com.hbmzz.ctrl.MachineBridge;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * "What controllable machine is at this position?" for both kinds of machine.
 *
 * <p>The ported panel was written against its own {@link IControllable}, and only
 * the console implements it here - every NTM machine in the world is a
 * {@code com.hbm...IControllable}, a different type. Before this class the linker
 * tab dropped every NTM machine on the floor ({@code te instanceof IControllable}
 * was simply false), which in turn emptied every machine-dependent dropdown in the
 * node editor.</p>
 *
 * <p>A second, quieter half of the same problem: large NTM machines are
 * multi-block. The block you can actually click is a {@code BlockDummyable}
 * filler whose tile entity is a {@code TileEntityDummy} pointing at the real one,
 * so looking up "the tile entity at the position the item recorded" finds a
 * placeholder with none of the control methods. Upstream's linker tab resolves
 * that chain explicitly ({@code findCore}, then {@code TileEntityDummy.target});
 * {@link #coreOf} is the same walk, done reflectively because those classes are
 * not on our compile path.</p>
 *
 * <p>Every place that used to cast a tile entity to {@code IControllable} asks
 * here instead: native controllables are returned as they are, NTM machines come
 * back wrapped in a {@link BridgedControllable} that is cached per world+position
 * (the event system keeps them in identity sets, and one wrapper per tick per
 * machine would otherwise be a lot of garbage).</p>
 */
public final class CPMachines {

    private static final Map<World, Long2ObjectOpenHashMap<IControllable>> CACHE = new WeakHashMap<>();

    /** {@code com.hbm.blocks.BlockDummyable}, or null when NTM is not installed. */
    private static final Class<?> DUMMYABLE = lookup("com.hbm.blocks.BlockDummyable");
    /** {@code com.hbm.tileentity.machine.TileEntityDummy}. */
    private static final Class<?> DUMMY_TE = lookup("com.hbm.tileentity.machine.TileEntityDummy");
    private static final Method FIND_CORE = method(DUMMYABLE, "findCore",
            IBlockAccess.class, int.class, int.class, int.class);
    private static final Field DUMMY_TARGET = field(DUMMY_TE, "target");

    private CPMachines() {
    }

    /**
     * The machine at {@code pos}, or null when the block is not controllable at
     * all. {@code pos} may be any part of a multi-block machine.
     */
    public static IControllable at(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        if (!world.isBlockLoaded(pos)) {
            return null;
        }
        BlockPos core = coreOf(world, pos);
        TileEntity tile = world.getTileEntity(core);
        if (tile == null) {
            return null;
        }
        if (tile instanceof IControllable) {
            return (IControllable) tile;
        }
        if (MachineBridge.isControllable(tile)) {
            return bridge(world, core);
        }
        return null;
    }

    /** True when this position holds anything the panel could talk to. */
    public static boolean isMachine(World world, BlockPos pos) {
        return at(world, pos) != null;
    }

    /**
     * Maps any block of a multi-block machine onto the position that owns the
     * tile entity: the {@code BlockDummyable} core, and then the target of a
     * {@code TileEntityDummy} if the core itself is only a filler. Anything that
     * is not part of such a machine comes back unchanged.
     */
    public static BlockPos coreOf(World world, BlockPos pos) {
        if (world == null || pos == null || DUMMYABLE == null || FIND_CORE == null) {
            return pos;
        }
        IBlockState state = world.getBlockState(pos);
        Block block = state == null ? null : state.getBlock();
        if (block == null || !DUMMYABLE.isInstance(block)) {
            return pos;
        }
        BlockPos owner = pos;
        try {
            int[] core = (int[]) FIND_CORE.invoke(block, world, pos.getX(), pos.getY(), pos.getZ());
            if (core != null && core.length >= 3) {
                owner = new BlockPos(core[0], core[1], core[2]);
            }
        } catch (Throwable ignored) {
            // Not a dummyable we understand: fall through with the original pos.
        }
        BlockPos target = dummyTarget(world.getTileEntity(owner));
        return target != null ? target : owner;
    }

    /** The {@code TileEntityDummy.target} of a filler tile entity, or null. */
    private static BlockPos dummyTarget(TileEntity tile) {
        if (tile == null || DUMMY_TE == null || DUMMY_TARGET == null || !DUMMY_TE.isInstance(tile)) {
            return null;
        }
        try {
            Object value = DUMMY_TARGET.get(tile);
            return value instanceof BlockPos ? (BlockPos) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Name for a linked machine, falling back to the block's own name. */
    public static String describe(World world, BlockPos pos) {
        IControllable controllable = at(world, pos);
        if (controllable instanceof BridgedControllable) {
            return ((BridgedControllable) controllable).describe();
        }
        if (controllable != null) {
            return "控制台";
        }
        BlockPos immutable = coreOf(world, pos);
        return world == null
                ? "（未知方块）"
                : world.getBlockState(immutable).getBlock().getLocalizedName();
    }

    private static synchronized IControllable bridge(World world, BlockPos pos) {
        Long2ObjectOpenHashMap<IControllable> perWorld = CACHE.get(world);
        if (perWorld == null) {
            perWorld = new Long2ObjectOpenHashMap<>();
            CACHE.put(world, perWorld);
        }
        long key = pos.toLong();
        IControllable existing = perWorld.get(key);
        if (existing != null) {
            return existing;
        }
        IControllable created = new BridgedControllable(world, pos);
        perWorld.put(key, created);
        return created;
    }

    private static Class<?> lookup(String name) {
        try {
            // Not initialised on purpose: NTM's block and tile-entity classes
            // touch a lot of static state, and all we need is the type handle.
            return Class.forName(name, false, CPMachines.class.getClassLoader());
        } catch (Throwable missing) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) {
        if (owner == null) {
            return null;
        }
        try {
            Method found = owner.getMethod(name, args);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        if (owner == null) {
            return null;
        }
        try {
            Field found = owner.getField(name);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            return null;
        }
    }
}

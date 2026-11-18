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

package com.hbmzz.hack;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side bookkeeping for the intrusion storyline: where a value was last
 * written, which dimension has its core signal cut, and what each operator has
 * aimed the tablet at.
 *
 * <h3>Why this is a static registry rather than tile entity state</h3>
 * <p>The thing being tracked is not a property of any one block. A write lands
 * on a core; the monitor that has to notice it is a different tile entity,
 * possibly a different chunk, and the tablet that caused it is an item in
 * somebody's hand. There is no block whose NBT could own this, and putting it
 * on the core would mean asking a third party's tile entity to carry our
 * fields. A process-wide table keyed by dimension is the honest shape.</p>
 *
 * <h3>Lifetime</h3>
 * <p>Nothing here is persisted. The intrusion is a live event: a server restart
 * clears every trace, the screens come back clean, and no save file is left
 * holding a flag that a later version would have to migrate. Entries are
 * dropped as they age out, so a long session with a lot of poking does not grow
 * the table without bound.</p>
 *
 * <h3>Threading</h3>
 * <p>Every writer runs inside {@code MinecraftServer.addScheduledTask} and every
 * reader runs in the monitor's tick, which is the same thread. The maps are
 * plain {@link HashMap}s on purpose - a concurrent map here would only hide a
 * caller that had escaped the server thread.</p>
 */
public final class HackRegistry {

    /**
     * One aim per operator, so the tablet can say "TARGET 128 64 -32" without
     * the client having to be trusted with it.
     */
    public static final class Aim {

        public final int dimension;
        public final BlockPos pos;

        Aim(int dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos;
        }
    }

    /** dimension -> (position -> tick of the last write). */
    private static final Map<Integer, Map<BlockPos, Long>> TOUCHED =
            new HashMap<Integer, Map<BlockPos, Long>>();

    /** dimension -> tick the cut expires at; absent when the feed is fine. */
    private static final Map<Integer, Long> CUT_UNTIL = new HashMap<Integer, Long>();

    /** operator -> what their tablet is pointed at. */
    private static final Map<UUID, Aim> AIMS = new HashMap<UUID, Aim>();

    private HackRegistry() {
    }

    // ------------------------------------------------------------------ writes

    /**
     * Records that {@code pos} had a value written straight into its fields.
     *
     * <p>This is what the monitor polls. It is called after the write has
     * already succeeded, so a refused field never lights up a screen.</p>
     */
    public static void touch(World world, BlockPos pos) {
        if (world == null || world.isRemote || pos == null) {
            return;
        }
        long now = world.getTotalWorldTime();
        Map<BlockPos, Long> map = TOUCHED.get(Integer.valueOf(world.provider.getDimension()));
        if (map == null) {
            map = new HashMap<BlockPos, Long>();
            TOUCHED.put(Integer.valueOf(world.provider.getDimension()), map);
        }
        map.put(pos, Long.valueOf(now));
    }

    /** Cuts every screen in this dimension for {@link HackState#cutTicks}. */
    public static void cut(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        CUT_UNTIL.put(Integer.valueOf(world.provider.getDimension()),
                Long.valueOf(world.getTotalWorldTime() + HackState.cutTicks));
    }

    /**
     * Brings the feed back and wipes every intrusion mark in this dimension.
     *
     * <p>One command, because a player who just wants their screens working
     * again should not have to wait out two separate timers.</p>
     */
    public static void restore(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        Integer key = Integer.valueOf(world.provider.getDimension());
        CUT_UNTIL.remove(key);
        TOUCHED.remove(key);
    }

    // ------------------------------------------------------------------- reads

    /** True while this dimension's core signal is cut. */
    public static boolean isCut(World world) {
        if (world == null) {
            return false;
        }
        Long until = CUT_UNTIL.get(Integer.valueOf(world.provider.getDimension()));
        if (until == null) {
            return false;
        }
        if (world.getTotalWorldTime() > until.longValue()) {
            CUT_UNTIL.remove(Integer.valueOf(world.provider.getDimension()));
            return false;
        }
        return true;
    }

    /**
     * The state a monitor watching {@code pos} should paint right now.
     *
     * <p>Order matters: a cut outranks everything, because a cut screen has no
     * data at all and "3 seconds until the link returns" would be a lie while
     * the uplink is severed.</p>
     *
     * <p>{@code pos} may be null - a monitor with nothing bound still has to
     * show a cut, since the cut is a property of the world rather than of any
     * particular core.</p>
     */
    public static int stateFor(World world, BlockPos pos) {
        if (world == null) {
            return HackState.NORMAL;
        }
        if (isCut(world)) {
            return HackState.CUT;
        }
        if (pos == null) {
            return HackState.NORMAL;
        }
        Map<BlockPos, Long> map = TOUCHED.get(Integer.valueOf(world.provider.getDimension()));
        if (map == null) {
            return HackState.NORMAL;
        }
        Long last = map.get(pos);
        if (last == null) {
            return HackState.NORMAL;
        }
        long age = world.getTotalWorldTime() - last.longValue();
        // A save reloaded underneath us, or the clock wrapped: the mark refers
        // to a past that no longer exists. Drop it rather than showing a screen
        // dark for 30 seconds on world load.
        if (age < 0L) {
            map.remove(pos);
            return HackState.NORMAL;
        }
        if (age < HackState.disconnectTicks) {
            return HackState.DISCONNECT;
        }
        if (age < HackState.degradedTicks) {
            return HackState.DEGRADED;
        }
        map.remove(pos);
        return HackState.NORMAL;
    }

    /**
     * Ticks left in the current state, for the countdown on screen and the
     * tablet's status strip. 0 when the state has no clock (NORMAL).
     */
    public static int ticksLeft(World world, BlockPos pos) {
        if (world == null) {
            return 0;
        }
        Long until = CUT_UNTIL.get(Integer.valueOf(world.provider.getDimension()));
        if (until != null && isCut(world)) {
            return (int) Math.max(0L, until.longValue() - world.getTotalWorldTime());
        }
        if (pos == null) {
            return 0;
        }
        Map<BlockPos, Long> map = TOUCHED.get(Integer.valueOf(world.provider.getDimension()));
        if (map == null) {
            return 0;
        }
        Long last = map.get(pos);
        if (last == null) {
            return 0;
        }
        long age = world.getTotalWorldTime() - last.longValue();
        if (age < HackState.disconnectTicks) {
            return (int) (HackState.disconnectTicks - age);
        }
        if (age < HackState.degradedTicks) {
            return (int) (HackState.degradedTicks - age);
        }
        return 0;
    }

    /**
     * Drops marks that have aged past the degraded window.
     *
     * <p>Called from the monitor's poll so the cleanup happens where the reads
     * happen, without a server tick handler whose only job is to walk a map
     * that is empty most of the time.</p>
     */
    public static void prune(World world, BlockPos around) {
        if (world == null || world.isRemote) {
            return;
        }
        Map<BlockPos, Long> map = TOUCHED.get(Integer.valueOf(world.provider.getDimension()));
        if (map == null || map.isEmpty()) {
            return;
        }
        long now = world.getTotalWorldTime();
        Iterator<Map.Entry<BlockPos, Long>> it = map.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> entry = it.next();
            long age = now - entry.getValue().longValue();
            if (age < 0L || age > HackState.degradedTicks * 4L) {
                it.remove();
            }
        }
        if (map.isEmpty()) {
            TOUCHED.remove(Integer.valueOf(world.provider.getDimension()));
        }
    }

    // -------------------------------------------------------------------- aims

    public static Aim aimOf(UUID player) {
        return player == null ? null : AIMS.get(player);
    }

    public static void aim(UUID player, int dimension, BlockPos pos) {
        if (player == null) {
            return;
        }
        if (pos == null) {
            AIMS.remove(player);
            return;
        }
        AIMS.put(player, new Aim(dimension, pos));
    }

    public static void clearAim(UUID player) {
        if (player != null) {
            AIMS.remove(player);
        }
    }

    /**
     * Forgets everything. Called when the server stops.
     *
     * <p>Static tables outlive a world in a client that switches between single
     * player saves without restarting the JVM, which is the normal way this mod
     * is played. Without this the next world would inherit the previous one's
     * intrusion marks - and because those marks are tick-stamped, they would
     * also fire immediately on load.</p>
     */
    public static void clearAll() {
        TOUCHED.clear();
        CUT_UNTIL.clear();
        AIMS.clear();
    }
}

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

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Makes one part's value change land on every same-named part of the same terminal.
 *
 * <p>A control panel drives a machine by sending it an event. With four control
 * rods wired to four slots that means four knobs, four rules, four node edges -
 * and four chances to leave one rod where the last accident put it. The
 * operator's mental model is "the control rods", not "rod at 41, 12, -3"; this
 * class makes the machine layer agree with that model.</p>
 *
 * <h3>What counts as "bound together"</h3>
 * <p>Only machines linked to the same panel or console. That is registered
 * here, per panel, whenever the panel loads or its links change -
 * {@link #bind}. A machine that merely stands next to a reactor but is wired to
 * nothing is never written to, which is the difference between a control system
 * and a world edit.</p>
 *
 * <h3>What counts as "the same part"</h3>
 * <p>Same family (RBMK, DFC, ...) and same part name (the machine type, e.g.
 * {@code RBMK 控制棒（手动）}), both among the machines some panel links
 * <em>together with the source</em>. Geometry is not consulted: two rods at
 * opposite ends of a 30-block DFC are the same part, because they are wired to
 * the same terminal. Two reactors are kept apart by giving them two terminals,
 * which is also how the person who built them describes them.</p>
 *
 * <p>The consequence worth knowing: if one panel is wired to two reactors, one
 * write moves both. That is the price of not guessing at geometry, and the fix
 * is a second panel rather than a rebuild.</p>
 *
 * <h3>When it does not fire</h3>
 * <p>When the write already covers the group. Two entry points do that: a
 * {@link PartGroup} writing to all of its members, and the node graph
 * broadcasting one event to a list of positions. Both set the scope guard
 * around their own loop, so the fan-out below leaves them alone - otherwise a
 * four-rod group write would deliver sixteen events and a momentary
 * {@code door_toggle} would close the door it just opened.</p>
 */
public final class PartBroadcast {

    /** Most peers one write will copy to. A 64-rod reactor is already generous. */
    private static final int MAX_PEERS = 32;

    /** Panel position to the machines it links, per world. */
    private static final Map<World, Map<BlockPos, Set<BlockPos>>> DOMAINS =
            new WeakHashMap<World, Map<BlockPos, Set<BlockPos>>>();

    /**
     * Set while a caller is doing its own multi-machine write.
     *
     * <p>A depth, not a flag: a group write can pass through the node graph,
     * which sets the guard itself, and a flag would let the inner
     * {@link #endScope()} clear the outer one halfway through the loop.</p>
     */
    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<Integer>();

    private PartBroadcast() {
    }

    // -------------------------------------------------------------- registry

    /**
     * Records (or replaces) one panel's set of linked machines.
     *
     * <p>Called on load and after every edit, so it is idempotent and cheap:
     * a panel has at most a handful of links and this runs when a player saves a
     * design, not per tick.</p>
     */
    public static void bind(World world, BlockPos owner, Collection<BlockPos> positions) {
        if (world == null || owner == null) {
            return;
        }
        Set<BlockPos> members = new LinkedHashSet<BlockPos>();
        if (positions != null) {
            for (BlockPos pos : positions) {
                if (pos != null) {
                    members.add(pos.toImmutable());
                }
            }
        }
        synchronized (DOMAINS) {
            Map<BlockPos, Set<BlockPos>> panels = DOMAINS.get(world);
            if (panels == null) {
                panels = new LinkedHashMap<BlockPos, Set<BlockPos>>();
                DOMAINS.put(world, panels);
            }
            if (members.isEmpty()) {
                panels.remove(owner);
            } else {
                panels.put(owner.toImmutable(), members);
            }
        }
    }

    /** Forgets one panel. Called when it is broken. */
    public static void unbind(World world, BlockPos owner) {
        if (world == null || owner == null) {
            return;
        }
        synchronized (DOMAINS) {
            Map<BlockPos, Set<BlockPos>> panels = DOMAINS.get(world);
            if (panels != null) {
                panels.remove(owner);
            }
        }
    }

    // ------------------------------------------------------------ scope guard

    /** "I am writing to several machines myself; do not fan out." */
    public static void beginScope() {
        DEPTH.set(Integer.valueOf(depth() + 1));
    }

    public static void endScope() {
        int depth = depth() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(Integer.valueOf(depth));
        }
    }

    public static boolean scoped() {
        return depth() > 0;
    }

    private static int depth() {
        Integer depth = DEPTH.get();
        return depth == null ? 0 : depth.intValue();
    }

    // -------------------------------------------------------------- fan-out

    /**
     * Copies a just-delivered event to the source machine's same-name peers.
     *
     * <p>Called after the event has already reached its target, so a failure
     * here costs the peers their update but not the machine the operator aimed
     * at.</p>
     */
    public static void fanOut(World world, BlockPos source, String event, Map<String, ?> vars) {
        if (world == null || world.isRemote || source == null || event == null || event.isEmpty() || scoped()) {
            return;
        }
        List<BlockPos> peers = peersOf(world, source);
        if (peers.isEmpty()) {
            return;
        }
        beginScope();
        try {
            for (BlockPos peer : peers) {
                MachineBridge.fireAll(world, peer, event, vars);
            }
        } finally {
            endScope();
        }
    }

    /**
     * Same-name parts wired to the same terminal as {@code source}, excluding
     * itself.
     *
     * <p>Public because the window reports the scope of an operation before it
     * happens ({@code parts}) and because a wrong peer set is the kind of bug
     * that has to be readable offline.</p>
     */
    public static List<BlockPos> peersOf(World world, BlockPos source) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        if (world == null || source == null) {
            return out;
        }
        Set<BlockPos> members = membersAround(world, source);
        if (members.size() < 2) {
            return out;
        }
        String family = PartTypes.familyAt(world, source);
        String name = PartTypes.partNameOf(PartTypes.tileAt(world, source));
        if (family.isEmpty() || name.isEmpty()) {
            return out;
        }
        for (BlockPos pos : members) {
            if (out.size() >= MAX_PEERS) {
                break;
            }
            if (pos.equals(source)) {
                continue;
            }
            if (!family.equals(PartTypes.familyAt(world, pos))) {
                continue;
            }
            if (!name.equals(PartTypes.partNameOf(PartTypes.tileAt(world, pos)))) {
                continue;
            }
            out.add(pos);
        }
        return out;
    }

    /** Every linked position of every panel that links {@code source}. */
    private static Set<BlockPos> membersAround(World world, BlockPos source) {
        Set<BlockPos> members = new LinkedHashSet<BlockPos>();
        synchronized (DOMAINS) {
            Map<BlockPos, Set<BlockPos>> panels = DOMAINS.get(world);
            if (panels == null) {
                return members;
            }
            // A panel that is gone leaves its links behind; drop the whole
            // domain rather than letting a broken panel keep affecting a world
            // it is no longer part of.
            Iterator<Map.Entry<BlockPos, Set<BlockPos>>> it = panels.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Set<BlockPos>> entry = it.next();
                BlockPos owner = entry.getKey();
                if (!world.isBlockLoaded(owner) || world.getTileEntity(owner) == null) {
                    it.remove();
                    continue;
                }
                if (entry.getValue().contains(source)) {
                    members.addAll(entry.getValue());
                }
            }
        }
        return members;
    }
}

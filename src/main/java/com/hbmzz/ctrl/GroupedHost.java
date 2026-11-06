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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A terminal host whose slots are machine families instead of machines.
 *
 * <p>The shell already speaks to a panel through {@link TerminalHost}, so the
 * cheapest way to turn "twelve tabs for one reactor" into "one tab per 部件类型"
 * is to put a host in front of the host: same interface, fewer slots, and every
 * verb keeps working without knowing anything changed.</p>
 *
 * <p>Folding is per family, over the slots this host itself owns - see
 * {@link PartTypes#byFamily}. Distance plays no part, so a reactor spread over
 * thirty blocks folds exactly like a compact one, and a DFC whose core is
 * bigger than any radius worth hard-coding still fits in one slot. Two reactors
 * wired to the same panel do land in the same slot; the answer to that is a
 * second terminal, not a bigger gap between the machines.</p>
 *
 * <p>A family with a single linked part is <em>not</em> folded: its slot keeps
 * the tag it already had. Folding it would rename {@code A} to {@code RBMK} for
 * no gain, and a panel with six unrelated machines - the common case for the
 * six-slot board - would otherwise relabel every one of them.</p>
 *
 * <p>Unbound slots stay in place and in order, so the strip still reads
 * A B C D E F rather than collapsing to "the three that are wired".</p>
 */
public final class GroupedHost implements TerminalHost {

    private final TerminalHost base;
    private final World world;

    private final List<List<Integer>> entries = new ArrayList<List<Integer>>();
    private final List<PartGroup> groups = new ArrayList<PartGroup>();
    private final List<String> labels = new ArrayList<String>();
    private final List<String> names = new ArrayList<String>();
    private final List<Boolean> bound = new ArrayList<Boolean>();
    /** Base slot to entry index, or -1. */
    private int[] entryOf;
    private boolean folded;

    private GroupedHost(TerminalHost base, World world) {
        this.base = base;
        this.world = world;
    }

    /**
     * The grouped view of {@code base}, or {@code base} itself when nothing
     * would fold.
     *
     * <p>Returning the original object in the common case is deliberate: an
     * identity-preserving pass-through cannot regress a panel that has one
     * machine per slot, and it keeps the extra mapping out of every command.
     * </p>
     */
    public static TerminalHost of(TerminalHost base, World world) {
        if (base == null) {
            return null;
        }
        GroupedHost host = new GroupedHost(base, world);
        host.build();
        return host.folded ? host : base;
    }

    // ------------------------------------------------------------------ build

    private void build() {
        this.entryOf = new int[this.base.slots()];
        for (int i = 0; i < this.entryOf.length; i++) {
            this.entryOf[i] = -1;
        }
        // Positions first: a slot's position is the only thing a panel and a
        // console both expose, and it is what the family comes from.
        List<BlockPos> positions = new ArrayList<BlockPos>();
        List<Integer> loose = new ArrayList<Integer>();
        for (int slot = 0; slot < this.base.slots(); slot++) {
            BlockPos pos = this.base.bound(slot) ? this.base.pos(slot) : null;
            if (pos == null) {
                loose.add(Integer.valueOf(slot));
            } else {
                positions.add(pos);
            }
        }
        Map<BlockPos, List<Integer>> slotsAt = new LinkedHashMap<BlockPos, List<Integer>>();
        for (int slot = 0; slot < this.base.slots(); slot++) {
            BlockPos pos = this.base.bound(slot) ? this.base.pos(slot) : null;
            if (pos == null) {
                continue;
            }
            List<Integer> list = slotsAt.get(pos);
            if (list == null) {
                list = new ArrayList<Integer>();
                slotsAt.put(pos, list);
            }
            list.add(Integer.valueOf(slot));
        }

        List<List<Integer>> built = new ArrayList<List<Integer>>();
        for (List<BlockPos> family : PartTypes.byFamily(this.world, positions)) {
            List<Integer> slots = new ArrayList<Integer>();
            for (BlockPos pos : family) {
                List<Integer> here = slotsAt.get(pos);
                if (here != null) {
                    for (Integer slot : here) {
                        if (!slots.contains(slot)) {
                            slots.add(slot);
                        }
                    }
                }
            }
            if (!slots.isEmpty()) {
                Collections.sort(slots);
                built.add(slots);
            }
        }
        for (Integer slot : loose) {
            built.add(Collections.singletonList(slot));
        }
        // First-seen order of the base panel, so A stays left of B even after a
        // group swallows both.
        Collections.sort(built, (a, b) -> a.get(0).intValue() - b.get(0).intValue());

        Set<String> used = new LinkedHashSet<String>();
        for (List<Integer> slots : built) {
            boolean isBound = slots.size() > 1 || this.base.bound(slots.get(0).intValue());
            List<String> partNames = new ArrayList<String>();
            for (Integer slot : slots) {
                partNames.add(partNameOf(slot.intValue()));
            }
            String label;
            String name;
            PartGroup group = null;
            if (isBound && slots.size() > 1) {
                int first = slots.get(0).intValue();
                BlockPos pos = this.base.pos(first);
                String family = PartTypes.familyAt(this.world, pos);
                if (family.isEmpty()) {
                    family = "class:?" + System.identityHashCode(pos);
                }
                String familyLabel = PartTypes.labelOf(family, PartTypes.tileAt(this.world, pos));
                label = unique(familyLabel, used);
                String title = PartTypes.titleOf(family, PartTypes.tileAt(this.world, pos));
                name = title + " · " + slots.size() + " 部件";
                group = new PartGroup(family, title, this.base, slots, partNames);
                this.folded = true;
            } else if (isBound) {
                label = unique(this.base.label(slots.get(0).intValue()), used);
                name = this.base.name(slots.get(0).intValue());
            } else {
                label = unique(this.base.label(slots.get(0).intValue()), used);
                name = "未绑定";
            }
            int index = this.entries.size();
            this.entries.add(slots);
            this.groups.add(group);
            this.labels.add(label);
            this.names.add(name);
            this.bound.add(Boolean.valueOf(isBound));
            for (Integer slot : slots) {
                if (slot.intValue() >= 0 && slot.intValue() < this.entryOf.length) {
                    this.entryOf[slot.intValue()] = index;
                }
            }
        }
    }

    private String partNameOf(int slot) {
        BlockPos pos = this.base.pos(slot);
        String name = PartTypes.partNameOf(PartTypes.tileAt(this.world, pos));
        if (name.isEmpty()) {
            // Offline or unloading: the panel's cached label is the next best
            // answer, and it is the one the operator sees.
            name = this.base.name(slot);
        }
        return name == null ? "" : name;
    }

    private static String unique(String label, Set<String> used) {
        String text = label == null || label.isEmpty() ? "机器" : label;
        if (used.add(text)) {
            return text;
        }
        for (int i = 2; i < 100; i++) {
            String candidate = text + "#" + i;
            if (used.add(candidate)) {
                return candidate;
            }
        }
        return text;
    }

    // ------------------------------------------------------------- TerminalHost

    @Override
    public int slots() {
        return this.entries.size();
    }

    @Override
    public String label(int slot) {
        return slot >= 0 && slot < this.labels.size() ? this.labels.get(slot) : "-";
    }

    @Override
    public String name(int slot) {
        return slot >= 0 && slot < this.names.size() ? this.names.get(slot) : "";
    }

    @Override
    public boolean bound(int slot) {
        return slot >= 0 && slot < this.bound.size() && this.bound.get(slot).booleanValue();
    }

    @Override
    public int target() {
        int baseTarget = this.base.target();
        if (baseTarget < 0 || baseTarget >= this.entryOf.length) {
            return -1;
        }
        return this.entryOf[baseTarget];
    }

    /**
     * Points the panel at the first machine of the slot.
     *
     * <p>A group has no single machine to store, and it does not need one: the
     * panel keeps a concrete link and {@link #target()} maps that link back to
     * the group it belongs to. Storing a group index instead would mean two
     * meanings for the same number in the saved design.</p>
     */
    @Override
    public boolean select(int slot) {
        if (!bound(slot)) {
            return false;
        }
        return this.base.select(this.entries.get(slot).get(0).intValue());
    }

    @Override
    public List<String> keys(int slot) {
        PartGroup group = group(slot);
        return group == null ? this.base.keys(single(slot)) : group.keys();
    }

    @Override
    public List<String> events(int slot) {
        PartGroup group = group(slot);
        return group == null ? this.base.events(single(slot)) : group.events();
    }

    @Override
    public Map<String, String> vars(int slot) {
        PartGroup group = group(slot);
        return group == null ? this.base.vars(single(slot)) : group.vars();
    }

    @Override
    public Map<String, String> read(int slot) {
        PartGroup group = group(slot);
        return group == null ? this.base.read(single(slot)) : group.read();
    }

    @Override
    public int fire(int slot, String event, Map<String, Object> vars) {
        PartGroup group = group(slot);
        return group == null ? this.base.fire(single(slot), event, vars) : group.fire(event, vars);
    }

    @Override
    public boolean available() {
        return this.base.available();
    }

    @Override
    public BlockPos pos(int slot) {
        PartGroup group = group(slot);
        return group == null ? this.base.pos(single(slot)) : group.pos();
    }

    @Override
    public int scope(int slot) {
        PartGroup group = group(slot);
        return group == null ? 1 : group.size();
    }

    @Override
    public Map<String, Integer> parts(int slot) {
        PartGroup group = group(slot);
        if (group == null) {
            return Collections.emptyMap();
        }
        return group.census();
    }

    private PartGroup group(int slot) {
        return slot >= 0 && slot < this.groups.size() ? this.groups.get(slot) : null;
    }

    private int single(int slot) {
        return slot >= 0 && slot < this.entries.size() ? this.entries.get(slot).get(0).intValue() : -1;
    }
}

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

package com.hbmzz.cecp;

import net.minecraft.util.math.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The linker tab's actual work, kept away from the GUI.
 *
 * <p>Turning "the coordinates this item recorded" into "the machines this control
 * is linked to" is the one step everything else hangs off: no links means no
 * machines, which means the node editor's event, key and target lists are all
 * empty and half of the stock nodes look like they do not exist. It used to be
 * inline in a click handler that silently dropped anything that was not a
 * console, so there was nothing to test and nothing to see.</p>
 *
 * <p>Living here it takes a {@link Resolver} instead of a {@code World}, which is
 * what makes it verifiable outside a running game - see the self-check in
 * {@code tools/}. The GUI supplies a resolver that goes through
 * {@code CPMachines}, so production and verification walk the same code.</p>
 */
public final class LinkerImport {

    /** Where a coordinate's machine comes from, and what to call it. */
    public interface Resolver {

        /** The controllable machine at this position, or null when there is none. */
        IControllable at(BlockPos pos);

        /** Row label for the linker list; the default is the coordinate itself. */
        default String label(BlockPos pos, IControllable machine) {
            return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        }
    }

    /** What one press of ">" did. */
    public static final class Result {

        /** Coordinates found on the item. */
        public final int recorded;
        /** Newly added rows. */
        public final int added;
        /** Coordinates that resolved to a machine but were already linked. */
        public final int duplicate;
        /** Coordinates that resolved to nothing the panel can talk to. */
        public final int rejected;

        Result(int recorded, int added, int duplicate, int rejected) {
            this.recorded = recorded;
            this.added = added;
            this.duplicate = duplicate;
            this.rejected = rejected;
        }

        public int machines() {
            return this.added + this.duplicate;
        }

        /** Coordinates that did resolve to a machine, repeats included. */
        public int recognized() {
            return this.recorded - this.rejected;
        }

        public boolean isEmpty() {
            return this.recorded == 0;
        }
    }

    private LinkerImport() {
    }

    public static Result apply(int[][] locations, Resolver resolver, Set<BlockPos> linkedPositions,
                               Map<BlockPos, String> tags) {
        if (resolver == null || linkedPositions == null || tags == null) {
            return new Result(0, 0, 0, 0);
        }
        int count = count(locations);
        int recorded = 0;
        int added = 0;
        int duplicate = 0;
        int rejected = 0;
        Set<BlockPos> seen = new HashSet<>();
        for (int i = 0; i < count; i++) {
            BlockPos pos = new BlockPos(locations[0][i], locations[1][i], locations[2][i]);
            recorded++;
            if (!seen.add(pos)) {
                // The same machine recorded twice on one item: it is still on the
                // item, so it is reported as a repeat rather than dropped silently.
                duplicate++;
                continue;
            }
            IControllable machine = resolver.at(pos);
            if (machine == null) {
                rejected++;
                continue;
            }
            BlockPos target = machine.getControlPos();
            String label = resolver.label(pos, machine);
            if (linkedPositions.add(target)) {
                tags.put(target, label);
                added++;
            } else {
                tags.putIfAbsent(target, label);
                duplicate++;
            }
        }
        return new Result(recorded, added, duplicate, rejected);
    }

    /** Length of the shortest of the three coordinate arrays. */
    private static int count(int[][] locations) {
        if (locations == null || locations.length < 3
                || locations[0] == null || locations[1] == null || locations[2] == null) {
            return 0;
        }
        return Math.min(locations[0].length, Math.min(locations[1].length, locations[2].length));
    }
}

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

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which columns belong to which reactor.
 *
 * <p>An RBMK is not a machine with a block entity; it is a floor plan. The base
 * mod's own assembler walks the four horizontal neighbours from a column and
 * repeats, which is the only definition that matches how operators build one:
 * columns are placed shoulder to shoulder, and a gap is a wall, not a reactor.</p>
 *
 * <p>That walk is reproduced here rather than borrowed because it has to run on
 * a list we already collected, off-thread of the tile entity tick, and because
 * a reactor that has just lost half its columns still has to be recognisable as
 * the same reactor for the few seconds the lid is in the air.</p>
 */
public final class RbmkCluster {

    /** No reactor is bigger than this in practice; the cap bounds the pass. */
    private static final int MAX_COLUMNS = 4096;

    private RbmkCluster() {
    }

    /**
     * Splits columns into reactors by horizontal adjacency.
     *
     * <p>Four neighbours, same Y, no diagonals - the same rule the base mod
     * uses to find a reactor for a meltdown. Diagonals would join two reactors
     * that touch at a corner, which is exactly the pair an operator keeps
     * separate on purpose.</p>
     *
     * <p>Order is first-seen so a scan does not reshuffle the list between
     * ticks; callers keep timers keyed on the first column.</p>
     */
    public static List<List<BlockPos>> byAdjacency(List<BlockPos> positions) {
        List<List<BlockPos>> out = new ArrayList<List<BlockPos>>();
        if (positions == null || positions.isEmpty()) {
            return out;
        }
        List<BlockPos> unique = new ArrayList<BlockPos>();
        Map<Long, Integer> index = new LinkedHashMap<Long, Integer>();
        for (BlockPos pos : positions) {
            if (pos == null) {
                continue;
            }
            Long packed = Long.valueOf(key(pos));
            if (!index.containsKey(packed)) {
                index.put(packed, Integer.valueOf(unique.size()));
                unique.add(pos);
            }
            if (unique.size() >= MAX_COLUMNS) {
                break;
            }
        }
        int count = unique.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < count; i++) {
            BlockPos pos = unique.get(i);
            join(parent, i, index, pos.getX() + 1, pos.getY(), pos.getZ());
            join(parent, i, index, pos.getX() - 1, pos.getY(), pos.getZ());
            join(parent, i, index, pos.getX(), pos.getY(), pos.getZ() + 1);
            join(parent, i, index, pos.getX(), pos.getY(), pos.getZ() - 1);
        }
        Map<Integer, List<BlockPos>> buckets = new LinkedHashMap<Integer, List<BlockPos>>();
        for (int i = 0; i < count; i++) {
            int root = find(parent, i);
            Integer id = Integer.valueOf(root);
            List<BlockPos> bucket = buckets.get(id);
            if (bucket == null) {
                bucket = new ArrayList<BlockPos>();
                buckets.put(id, bucket);
            }
            bucket.add(unique.get(i));
        }
        out.addAll(buckets.values());
        return out;
    }

    /** Packing has to match {@link #key(BlockPos)} exactly or lookups miss. */
    private static long key(BlockPos pos) {
        return ((long) pos.getX() & 0x3FFFFFFL) << 38
                | ((long) pos.getY() & 0xFFFL) << 26
                | ((long) pos.getZ() & 0x3FFFFFFL);
    }

    private static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) y & 0xFFFL) << 26
                | ((long) z & 0x3FFFFFFL);
    }

    private static void join(int[] parent, int from, Map<Long, Integer> index, int x, int y, int z) {
        Integer other = index.get(Long.valueOf(key(x, y, z)));
        if (other != null) {
            union(parent, from, other.intValue());
        }
    }

    private static int find(int[] parent, int i) {
        int root = i;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[i] != root) {
            int next = parent[i];
            parent[i] = root;
            i = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            // Lowest index wins, so the root - and therefore the bucket order -
            // is the earliest column the scan saw.
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }
}

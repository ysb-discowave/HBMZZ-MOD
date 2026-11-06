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

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What kind of machine this is, and which reactor it belongs to.
 *
 * <p>An RBMK is not one block. A working reactor is a column of fuel channels,
 * a row of control rods, a boiler or three, and a crane console - a dozen
 * tile entities that a panel has to be wired to one by one, and then operated
 * one by one. The terminal made that bearable but not small: twelve tags, twelve
 * tabs, twelve knobs. This class supplies the two facts that let the terminal
 * fold them into one:</p>
 *
 * <ol>
 *   <li><b>family</b> - the machine family a part belongs to, read off its tile
 *       entity class ({@code TileEntityRBMKControlManual} is RBMK,
 *       {@code TileEntityMachineDFC...} is DFC). All members of a family are one
 *       {@code 部件类型} in the window.</li>
 *   <li><b>cluster</b> - which physical reactor those parts belong to. Parts of
 *       one family that are within {@link #CLUSTER} blocks of each other form
 *       one reactor, chained, so a 20-block-wide core is still one cluster while
 *       two reactors with a gap between them are two.</li>
 * </ol>
 *
 * <p>Both are geometric/class-based rather than tag-based on purpose: the
 * operator should not have to invent a naming convention before the window
 * works, and a tag is free text that the linker lets people rename at any time.
 * The cost is that two reactors built flush against each other read as one -
 * see {@link #CLUSTER} for why the radius is what it is.</p>
 */
public final class PartTypes {

    /**
     * Chebyshev distance within which two parts of one family belong to the
     * same reactor.
     *
     * <p>Six, not six hundred: a reactor's parts are adjacent or a few blocks
     * apart, and a pair of reactors is normally further apart than that. It is
     * applied along a chain rather than as a bounding box, so distance measures
     * the gap between neighbours, not the size of the machine - a 7x7x7 core is
     * still one cluster even though its corners are twelve blocks apart.</p>
     */
    public static final int CLUSTER = 6;

    /** Most parts one family slot will fold together. Guards the O(n^2) pass. */
    public static final int MAX_MEMBERS = 64;

    // Family keys. Not display strings: they go into saved targets and into
    // comparisons, so they stay lowercase and stable.
    public static final String RBMK = "rbmk";
    public static final String DFC = "dfc";

    /**
     * Class-name needle, family key, tab label, long title.
     *
     * <p>Ordered, and matched with {@code contains} rather than
     * {@code startsWith}: the CE classes are {@code TileEntityRBMKControlManual},
     * {@code TileEntityMachineFluidTank}, {@code TileEntityBlastDoor} and so on,
     * and a prefix rule would miss two of those three. RBMK and DFC come first
     * because their own subclasses sometimes carry another family's word.</p>
     */
    private static final String[][] FAMILIES = {
            {"RBMK", RBMK, "RBMK", "RBMK 反应堆"},
            {"DFC", DFC, "DFC", "DFC 反应堆"},
            {"FluidTank", "tank", "储罐", "流体储罐"},
            {"Turret", "turret", "炮塔", "炮塔"},
            {"Siren", "siren", "警报", "警报器"},
            {"Door", "door", "门", "门"},
            {"BMPowerBox", "power", "电源", "电源盒"},
            {"SpinnyLight", "light", "警示灯", "警示灯"},
            {"ControlPanel", "console", "控制台", "控制面板"},
            {"CtrlConsole", "console", "控制台", "控制面板"},
    };

    private PartTypes() {
    }

    // ================================================================== naming

    /** Tile entity at a position, or null when it is gone or not loaded. */
    public static TileEntity tileAt(World world, BlockPos pos) {
        if (world == null || pos == null || !world.isBlockLoaded(pos)) {
            return null;
        }
        return world.getTileEntity(pos);
    }

    /** Family key of a tile, or "" when it cannot be told. */
    public static String familyOf(TileEntity tile) {
        return tile == null ? "" : familyOfClass(tile.getClass().getSimpleName());
    }

    /** Family key from a tile entity class name. */
    public static String familyOfClass(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) {
            return "";
        }
        for (String[] family : FAMILIES) {
            if (simpleName.contains(family[0])) {
                return family[1];
            }
        }
        // One family per unknown class rather than one bucket for all of them:
        // two addon machines have nothing in common just because neither is in
        // the table, and merging them would silently broadcast between them.
        return "class:" + simpleName;
    }

    /** Family key of the machine at a position, or "" when there is none. */
    public static String familyAt(World world, BlockPos pos) {
        return familyOf(tileAt(world, pos));
    }

    /** Short tab label for a family: what the operator types after {@code use}. */
    public static String labelOf(String family, TileEntity tile) {
        if (family == null || family.isEmpty()) {
            return "机器";
        }
        for (String[] entry : FAMILIES) {
            if (entry[1].equals(family)) {
                return entry[2];
            }
        }
        return clip(describeOf(tile), 6);
    }

    /** Long name for the ls line and the window header. */
    public static String titleOf(String family, TileEntity tile) {
        if (family == null || family.isEmpty()) {
            return "机器";
        }
        for (String[] entry : FAMILIES) {
            if (entry[1].equals(family)) {
                return entry[3];
            }
        }
        return describeOf(tile);
    }

    /**
     * The part's name, which is what "same name" means for broadcasting.
     *
     * <p>Deliberately the machine type ("RBMK 控制棒（手动）") rather than the
     * linker tag: the tag is per position and exists precisely to tell two rods
     * apart, while the type is the thing four rods have in common and the thing
     * an operator means by "the control rods".</p>
     */
    public static String partNameOf(TileEntity tile) {
        return tile == null ? "" : MachineBridge.describe(tile);
    }

    private static String describeOf(TileEntity tile) {
        if (tile == null) {
            return "机器";
        }
        String name = MachineBridge.describe(tile);
        if (name.startsWith("TileEntity")) {
            name = name.substring("TileEntity".length());
        }
        return name.isEmpty() ? "机器" : name;
    }

    // =============================================================== clustering

    /** Within {@code radius} on every axis. */
    public static boolean near(BlockPos a, BlockPos b, int radius) {
        return Math.abs(a.getX() - b.getX()) <= radius
                && Math.abs(a.getY() - b.getY()) <= radius
                && Math.abs(a.getZ() - b.getZ()) <= radius;
    }

    /**
     * Splits positions into reactors: same family, chained within
     * {@link #CLUSTER}.
     *
     * <p>Chained, not mutual: if A is near B and B is near C then all three are
     * one reactor even when A and C are far apart. That is what makes a long
     * core one cluster instead of a row of pairs. Positions whose machine cannot
     * be identified stay alone, because guessing a family for them would put an
     * unloaded chunk in the same slot as a live reactor.</p>
     *
     * <p>The result keeps first-seen order, so a window's tabs do not jump
     * around between two commands.</p>
     */
    public static List<List<BlockPos>> clusters(World world, List<BlockPos> positions) {
        List<BlockPos> unique = new ArrayList<BlockPos>();
        Set<BlockPos> seen = new LinkedHashSet<BlockPos>();
        if (positions != null) {
            for (BlockPos pos : positions) {
                if (pos != null && unique.size() < MAX_MEMBERS && seen.add(pos)) {
                    unique.add(pos);
                }
            }
        }
        int n = unique.size();
        int[] parent = new int[n];
        String[] family = new String[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
            family[i] = familyAt(world, unique.get(i));
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (family[i].isEmpty() || !family[i].equals(family[j])) {
                    continue;
                }
                if (near(unique.get(i), unique.get(j), CLUSTER)) {
                    union(parent, i, j);
                }
            }
        }
        Map<Integer, List<BlockPos>> byRoot = new LinkedHashMap<Integer, List<BlockPos>>();
        for (int i = 0; i < n; i++) {
            Integer root = Integer.valueOf(find(parent, i));
            List<BlockPos> cluster = byRoot.get(root);
            if (cluster == null) {
                cluster = new ArrayList<BlockPos>();
                byRoot.put(root, cluster);
            }
            cluster.add(unique.get(i));
        }
        return new ArrayList<List<BlockPos>>(byRoot.values());
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
            // The lower index wins so the root is the earliest position in the
            // list, which is what keeps the output order stable.
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}

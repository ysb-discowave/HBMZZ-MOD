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
 * What kind of machine this is, and what a 部件类型 is made of.
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
 *   <li><b>part name</b> - the machine type, which is what "same part" means
 *       both for the {@code parts} roster and for broadcasting.</li>
 * </ol>
 *
 * <p>Grouping is by family alone, over the parts <em>one terminal</em> is bound
 * to. There is no distance test, and that is deliberate: a DFC core can be
 * thirty blocks across, an RBMK's parts can sit on both sides of a wall, and
 * neither is a reason to refuse to fold them. Two reactors wired to one panel
 * therefore do fold together - the way to keep them apart is a second terminal,
 * which is also the way an operator already thinks about two reactors.</p>
 *
 * <p>Class-based rather than tag-based on purpose: the operator should not have
 * to invent a naming convention before the window works, and a tag is free text
 * that the linker lets people rename at any time.</p>
 */
public final class PartTypes {

    /** Most parts one family slot will fold together. Guards the grouping pass. */
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
     *
     * <p>The DFC entries are not one needle but eight, and that is not
     * redundancy: NTM names its DFC parts after what they do, not after the
     * reactor - {@code TileEntityCoreEmitter}, {@code TileEntityCoreInjector},
     * {@code TileEntityCoreReceiver}, {@code TileEntityCoreStabilizer},
     * {@code TileEntityCoreDetonator}, {@code TileEntityCoreExchanger}, the CE
     * variant {@code ...CEmitter}, and the core itself, {@code TileEntityCore}.
     * A single "DFC" needle would match none of them, and a big DFC would fall
     * apart into one slot per component class - which is precisely the machine
     * this table exists to make one slot.</p>
     *
     * <p>A needle starting with {@code =} is an exact match instead of a
     * substring. Only the core uses it: {@code TileEntityCore} is a prefix of
     * every other DFC component, so as a substring rule it would swallow the
     * whole {@code TileEntityCore*} namespace - including whatever an addon
     * puts there next.</p>
     */
    private static final String[][] FAMILIES = {
            {"RBMK", RBMK, "RBMK", "RBMK 反应堆"},
            {"CEmitter", DFC, "DFC", "DFC 反应堆"},
            {"CoreEmitter", DFC, "DFC", "DFC 反应堆"},
            {"CoreInjector", DFC, "DFC", "DFC 反应堆"},
            {"CoreReceiver", DFC, "DFC", "DFC 反应堆"},
            {"CoreStabilizer", DFC, "DFC", "DFC 反应堆"},
            {"CoreDetonator", DFC, "DFC", "DFC 反应堆"},
            {"CoreExchanger", DFC, "DFC", "DFC 反应堆"},
            {"DFC", DFC, "DFC", "DFC 反应堆"},
            {"=TileEntityCore", DFC, "DFC", "DFC 反应堆"},
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
            if (matches(family[0], simpleName)) {
                return family[1];
            }
        }
        // One family per unknown class rather than one bucket for all of them:
        // two addon machines have nothing in common just because neither is in
        // the table, and merging them would silently broadcast between them.
        return "class:" + simpleName;
    }

    /** {@code =Foo} means the whole simple name must be {@code Foo}. */
    private static boolean matches(String needle, String simpleName) {
        if (needle.startsWith("=")) {
            return needle.substring(1).equals(simpleName);
        }
        return simpleName.contains(needle);
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

    // ================================================================= grouping

    /** Where a part's family comes from: the world in production, a table in tests. */
    public interface FamilyOf {
        String familyOf(BlockPos pos);
    }

    /**
     * Splits one terminal's bound positions into 部件类型 slots: same family,
     * no geometry involved.
     *
     * <p>Family only, because the alternative - measuring distances - answers a
     * question the operator did not ask. A 30-block DFC, a reactor with its
     * boilers in the next room, a chain of tanks along a pipe: all of them are
     * one thing to the person holding the wrench, and none of them would survive
     * a radius test. What separates two reactors is that they are two terminals
     * - which is a fact the player states by binding, not one the code has to
     * infer from coordinates.</p>
     *
     * <p>Positions whose machine cannot be identified stay alone instead of
     * being pooled under a shared "unknown" family, because two unrelated
     * addon machines have nothing in common just because neither is in the
     * table - and pooling them would broadcast between them.</p>
     *
     * <p>The result keeps first-seen order, so a window's tabs do not jump
     * around between two commands.</p>
     */
    public static List<List<BlockPos>> byFamily(List<BlockPos> positions, FamilyOf source) {
        List<BlockPos> unique = new ArrayList<BlockPos>();
        Set<BlockPos> seen = new LinkedHashSet<BlockPos>();
        if (positions != null) {
            for (BlockPos pos : positions) {
                if (pos != null && unique.size() < MAX_MEMBERS && seen.add(pos)) {
                    unique.add(pos);
                }
            }
        }
        Map<String, List<BlockPos>> groups = new LinkedHashMap<String, List<BlockPos>>();
        for (int i = 0; i < unique.size(); i++) {
            BlockPos pos = unique.get(i);
            String family = source == null ? "" : source.familyOf(pos);
            // Unidentified parts get a key of their own, derived from the index
            // rather than the position so it cannot collide with a real family
            // key and cannot repeat.
            String key = family == null || family.isEmpty() ? "?" + i : family;
            List<BlockPos> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<BlockPos>();
                groups.put(key, group);
            }
            group.add(pos);
        }
        return new ArrayList<List<BlockPos>>(groups.values());
    }

    /** {@link #byFamily} against a real world. */
    public static List<List<BlockPos>> byFamily(final World world, List<BlockPos> positions) {
        return byFamily(positions, new FamilyOf() {
            @Override
            public String familyOf(BlockPos pos) {
                return familyAt(world, pos);
            }
        });
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}

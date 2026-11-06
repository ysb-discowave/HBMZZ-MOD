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

import com.hbmzz.rbmk.RbmkCluster;
import com.hbmzz.rbmk.RbmkLayout;
import com.hbmzz.rbmk.RbmkTiming;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Offline check of the staged RBMK blow-up's decision layer.
 *
 * <p>Three questions decide whether a reactor blows up well, and all three are
 * answerable without a world: when does it start, how long is the warning, and
 * which columns are one reactor. Getting any of them wrong is invisible in
 * single-player testing - a trip that fires a second late or a reactor that
 * merges with its neighbour still produces an explosion - and unfixable in
 * hindsight, because the save is gone. So they live here.</p>
 *
 * <p>Nothing in this file needs Minecraft beyond {@code BlockPos}, which the
 * shared stand-in in {@code tools/linkercheck/stubs} provides.</p>
 */
public final class RbmkCheck {

    private static int checks;
    private static int failed;

    public static void main(String[] args) {
        timing();
        steam();
        trip();
        clustering();
        layout();

        System.out.println();
        System.out.println(checks + " checks, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ================================================================= timing

    private static void timing() {
        System.out.println("-- timing --");
        RbmkTiming.Phase idle = RbmkTiming.Phase.IDLE;
        check("a cold reactor stays idle", RbmkTiming.advance(idle, false, 0) == idle);
        check("a tripped reactor starts the warm-up",
                RbmkTiming.advance(idle, true, 0) == RbmkTiming.Phase.WARMUP);
        check("the warm-up is not cut short at 1199 ticks",
                RbmkTiming.advance(RbmkTiming.Phase.WARMUP, false, 1199) == RbmkTiming.Phase.WARMUP);
        check("one minute is one minute",
                RbmkTiming.JUMP_TICKS == 1200,
                "JUMP_TICKS=" + RbmkTiming.JUMP_TICKS);
        check("the lid leaves at 1200 ticks",
                RbmkTiming.advance(RbmkTiming.Phase.WARMUP, false, 1200) == RbmkTiming.Phase.SPENT);
        check("a spent reactor never restarts its countdown",
                RbmkTiming.advance(RbmkTiming.Phase.SPENT, true, 0) == RbmkTiming.Phase.SPENT);
        check("a configured countdown is honoured",
                RbmkTiming.advance(RbmkTiming.Phase.WARMUP, true, 599, 600) == RbmkTiming.Phase.WARMUP
                        && RbmkTiming.advance(RbmkTiming.Phase.WARMUP, true, 600, 600) == RbmkTiming.Phase.SPENT);
        check("a zero-length countdown still fires instead of hanging",
                RbmkTiming.advance(RbmkTiming.Phase.WARMUP, true, 1, 0) == RbmkTiming.Phase.SPENT);

        check("the jump starts quiet",
                RbmkTiming.jumpDamage(0) == RbmkTiming.DAMAGE_START,
                "damage=" + RbmkTiming.jumpDamage(0));
        check("the jump ends loud",
                RbmkTiming.jumpDamage(RbmkTiming.JUMP_TICKS) == RbmkTiming.DAMAGE_END,
                "damage=" + RbmkTiming.jumpDamage(RbmkTiming.JUMP_TICKS));
        // The addon melts the reactor down at 1200 of its own damage. Asking for
        // anything at or above that would hand the trigger to the wrong mod.
        check("the jump stops short of the addon's meltdown line",
                RbmkTiming.DAMAGE_END < 1200,
                "DAMAGE_END=" + RbmkTiming.DAMAGE_END);
        int previous = -1;
        boolean monotonic = true;
        for (int tick = 0; tick <= RbmkTiming.JUMP_TICKS; tick++) {
            int damage = RbmkTiming.jumpDamage(tick);
            if (damage < previous) {
                monotonic = false;
                break;
            }
            previous = damage;
        }
        check("the jump never goes backwards", monotonic);
        int midpoint = RbmkTiming.jumpDamage(RbmkTiming.JUMP_TICKS / 2);
        int linear = (RbmkTiming.DAMAGE_START + RbmkTiming.DAMAGE_END) / 2;
        check("the jump is eased in, not linear", midpoint > linear,
                "mid=" + midpoint + " linear=" + linear);

        double first = RbmkTiming.apex(RbmkTiming.LID_LAUNCH_VY);
        double second = RbmkTiming.apex(RbmkTiming.LID_REBLAST_VY);
        check("the first throw clears the column", first > 20.0D, "apex=" + first);
        check("the second throw goes higher than the first", second > first,
                first + " vs " + second);
        check("the flight limit is longer than the fall",
                RbmkTiming.FLIGHT_LIMIT > 100, "FLIGHT_LIMIT=" + RbmkTiming.FLIGHT_LIMIT);
    }

    // ================================================================== steam

    private static void steam() {
        System.out.println();
        System.out.println("-- steam --");
        int capacity = 16000;
        check("every steam-holding column at capacity is full",
                RbmkTiming.steamFull(new int[]{capacity, capacity}, capacity));
        check("one column short is not full",
                !RbmkTiming.steamFull(new int[]{capacity, capacity - 1}, capacity));
        check("columns that hold no steam are not counted",
                RbmkTiming.steamFull(new int[]{capacity, 0, 0}, capacity));
        check("a reactor holding no steam at all is never full",
                !RbmkTiming.steamFull(new int[]{0, 0}, capacity));
        check("no steam columns is never full",
                !RbmkTiming.steamFull(new int[0], capacity));
        check("a null steam array is never full",
                !RbmkTiming.steamFull(null, capacity));

        check("a stalled total counts as full",
                RbmkTiming.steamStalled(500L, 500L, 500L));
        check("a rising total does not",
                !RbmkTiming.steamStalled(400L, 500L, 600L));
        check("an empty reactor does not count as stalled",
                !RbmkTiming.steamStalled(0L, 0L, 0L));
        check("the plateau test is only right for two full scans",
                !RbmkTiming.steamStalled(500L, 500L, 700L));
    }

    // =================================================================== trip

    private static void trip() {
        System.out.println();
        System.out.println("-- trip --");
        double threshold = RbmkTiming.HEAT_TRIP;
        int capacity = 16000;
        double[] hot = {threshold + 1.0D};
        double[] warm = {threshold - 1.0D};
        int[] full = {capacity};
        int[] partial = {capacity / 2};

        check("heat alone does not trip",
                !RbmkTiming.tripped(hot, partial, capacity, threshold));
        check("steam alone does not trip",
                !RbmkTiming.tripped(warm, full, capacity, threshold));
        check("heat and full steam together trip",
                RbmkTiming.tripped(hot, full, capacity, threshold));
        check("a stalled total trips a reactor that is not at the declared capacity",
                RbmkTiming.tripped(hot, partial, capacity, threshold, 8000L, 8000L, 8000L));
        check("a rising total does not trip on its own",
                !RbmkTiming.tripped(hot, partial, capacity, threshold, 4000L, 6000L, 8000L));
        check("the heat line is a threshold, not a constant",
                RbmkTiming.tripped(new double[]{900.0D}, full, capacity, 800.0D)
                        && !RbmkTiming.tripped(new double[]{900.0D}, full, capacity, 1000.0D));
        check("an empty reactor never trips",
                !RbmkTiming.tripped(new double[0], new int[0], capacity, threshold));

        // 1500 is the base mod's maxHeat for most columns; 1000 has to be
        // strictly below it or the sequence would start at the same moment the
        // base mod destroys the reactor.
        check("the trip line sits below the base mod's meltdown",
                RbmkTiming.HEAT_TRIP < 1500.0D,
                "HEAT_TRIP=" + RbmkTiming.HEAT_TRIP);
    }

    // ============================================================== clustering

    private static void clustering() {
        System.out.println();
        System.out.println("-- clustering --");
        List<List<BlockPos>> two = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)));
        check("two neighbouring columns are one reactor",
                two.size() == 1 && two.get(0).size() == 2, describe(two));

        List<List<BlockPos>> diagonal = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(1, 64, 1)));
        check("diagonals are not neighbours", diagonal.size() == 2, describe(diagonal));

        List<List<BlockPos>> heights = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(1, 65, 0)));
        check("a column one block higher is another reactor",
                heights.size() == 2, describe(heights));

        List<List<BlockPos>> chain = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(2, 64, 0), new BlockPos(1, 64, 0)));
        check("a chain is one reactor however the list is ordered",
                chain.size() == 1 && chain.get(0).size() == 3, describe(chain));

        List<List<BlockPos>> apart = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(1, 64, 0),
                new BlockPos(8, 64, 0), new BlockPos(9, 64, 0)));
        check("two reactors stay two",
                apart.size() == 2 && apart.get(0).size() == 2 && apart.get(1).size() == 2,
                describe(apart));

        List<List<BlockPos>> duplicate = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(0, 64, 0), new BlockPos(0, 64, 0)));
        check("a position listed twice is still one column",
                duplicate.size() == 1 && duplicate.get(0).size() == 1, describe(duplicate));

        check("no columns is no reactors", RbmkCluster.byAdjacency(new ArrayList<BlockPos>()).isEmpty());
        check("a null list is no reactors", RbmkCluster.byAdjacency(null).isEmpty());

        // Order matters: the first column of a group becomes the key a timer
        // hangs off, so a reshuffle between scans would restart the countdown.
        List<List<BlockPos>> ordered = RbmkCluster.byAdjacency(Arrays.asList(
                new BlockPos(5, 64, 5), new BlockPos(6, 64, 5)));
        check("a group keeps the order the scan saw it",
                ordered.size() == 1 && ordered.get(0).get(0).getX() == 5, describe(ordered));
    }

    // ================================================================= layout

    private static void layout() {
        System.out.println();
        System.out.println("-- layout --");
        List<String> textures = Arrays.asList("blocks/rbmk/rbmk_boiler", "blocks/rbmk/rbmk_fuel");
        List<int[]> entries = new ArrayList<int[]>();
        entries.add(new int[]{-4, 0, 1, 0});
        entries.add(new int[]{4, 2, 2, 1});
        String encoded = RbmkLayout.encode(entries, textures);

        List<int[]> decoded = new ArrayList<int[]>();
        List<String> names = new ArrayList<String>();
        RbmkLayout.decode(encoded, decoded, names);
        check("a plate round-trips through the wire format",
                decoded.size() == 2 && names.size() == 2, encoded);
        check("the round trip preserves half-block offsets",
                decoded.get(0)[0] == -4 && decoded.get(1)[0] == 4, Arrays.toString(decoded.get(0)));
        check("the round trip preserves the lid kind",
                decoded.get(1)[2] == 2 && decoded.get(1)[3] == 1, Arrays.toString(decoded.get(1)));
        check("a texture can be read without decoding the lids",
                RbmkLayout.texturesOf(encoded).equals(textures),
                RbmkLayout.texturesOf(encoded).toString());

        List<int[]> corrupt = new ArrayList<int[]>();
        List<String> corruptNames = new ArrayList<String>();
        RbmkLayout.decode("tex#1,2,1,0;oops;3,4,2,0", corrupt, corruptNames);
        check("a corrupt lid costs one lid, not the plate",
                corrupt.size() == 2 && corruptNames.size() == 1, "decoded=" + corrupt.size());

        List<int[]> empty = new ArrayList<int[]>();
        List<String> emptyNames = new ArrayList<String>();
        RbmkLayout.decode("", empty, emptyNames);
        check("an empty layout decodes to nothing", empty.isEmpty() && emptyNames.isEmpty());

        List<int[]> noHead = new ArrayList<int[]>();
        List<String> noHeadNames = new ArrayList<String>();
        RbmkLayout.decode(null, noHead, noHeadNames);
        check("a null layout is not an exception", noHead.isEmpty());
    }

    // ================================================================ harness

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        checks++;
        if (ok) {
            System.out.println("  PASS  " + what);
            return;
        }
        failed++;
        System.out.println("  FAIL  " + what + (detail.isEmpty() ? "" : "  [" + detail + "]"));
    }

    private static String describe(List<List<BlockPos>> groups) {
        StringBuilder out = new StringBuilder();
        for (List<BlockPos> group : groups) {
            out.append('{');
            for (BlockPos pos : group) {
                out.append(pos.getX()).append('/').append(pos.getY()).append('/').append(pos.getZ()).append(' ');
            }
            out.append("} ");
        }
        return out.toString().trim();
    }
}

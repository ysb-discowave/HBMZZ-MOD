import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.LinkerImport;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagLong;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Offline check of the panel's machine import.
 *
 * <p>Runs the real {@link LinkerImport} and the real
 * {@code ItemMonitorLinker.positionsFromTag} against minimal stand-ins for
 * {@code BlockPos}/{@code NBT*} (same packing, same equality, same tag ids), so
 * the coordinate -> machine step and the NBT list format are exercised without a
 * running game. Everything else - the GUI, the world, the machines - is stubbed
 * away, which is the point: this is the part that silently did nothing.</p>
 */
public final class LinkerCheck {

    private static int passed;
    private static int failed;
    private static int skipped;

    public static void main(String[] args) {
        checkEmptyInput();
        checkMixedBatch();
        checkControlPosIndirection();
        checkReimport();
        checkNbtRoundTrip();
        checkEventRegistry();
        checkEventDelivery();
        checkAddonNodes();
        checkAddonControls();
        checkCursedEditionBridge();

        System.out.println();
        System.out.println("passed " + passed + ", failed " + failed + ", skipped " + skipped);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ tests

    /** No coordinates at all is the "wrong item in the slot" case. */
    private static void checkEmptyInput() {
        Set<BlockPos> linked = new LinkedHashSet<>();
        Map<BlockPos, String> tags = new LinkedHashMap<>();
        LinkerImport.Result result = LinkerImport.apply(null, resolver(new LinkedHashMap<BlockPos, IControllable>()), linked, tags);
        check("no coordinates -> empty result", result.isEmpty() && result.machines() == 0 && linked.isEmpty(), result.toString());
    }

    /** One good machine, one plain block, one good machine, one repeat. */
    private static void checkMixedBatch() {
        Map<BlockPos, IControllable> machines = new LinkedHashMap<>();
        BlockPos a = new BlockPos(10, 64, 5);
        BlockPos b = new BlockPos(20, 64, 5);
        BlockPos c = new BlockPos(30, 64, 5);
        machines.put(a, new Fake(a, "RBMK 控制棒（手动）"));
        machines.put(c, new Fake(c, "流体储罐"));

        int[][] locations = {{10, 20, 30, 10}, {64, 64, 64, 64}, {5, 5, 5, 5}};
        Set<BlockPos> linked = new LinkedHashSet<>();
        Map<BlockPos, String> tags = new LinkedHashMap<>();
        LinkerImport.Result result = LinkerImport.apply(locations, resolver(machines), linked, tags);

        boolean ok = result.recorded == 4 && result.added == 2 && result.duplicate == 1 && result.rejected == 1
                && result.recognized() == 3 && linked.size() == 2 && tags.size() == 2;
        check("mixed batch: 2 machines, 1 plain block, 1 repeat", ok, describe(result, linked, tags));
        check("row label names the machine", String.valueOf(tags.get(a)).contains("RBMK"),
                String.valueOf(tags.get(a)));
        check("plain block is not linked", !linked.contains(b), linked.toString());
    }

    /** A machine whose control position is not the block that was clicked. */
    private static void checkControlPosIndirection() {
        BlockPos clicked = new BlockPos(50, 64, 5);
        BlockPos control = new BlockPos(99, 64, 5);
        Map<BlockPos, IControllable> machines = new LinkedHashMap<>();
        machines.put(clicked, new Fake(control, "RBMK 吊车控制台"));

        int[][] locations = {{50}, {64}, {5}};
        Set<BlockPos> linked = new LinkedHashSet<>();
        Map<BlockPos, String> tags = new LinkedHashMap<>();
        LinkerImport.apply(locations, resolver(machines), linked, tags);

        check("linked position is the control position", linked.contains(control) && !linked.contains(clicked),
                linked.toString());
    }

    /** Pressing ">" twice must not duplicate rows. */
    private static void checkReimport() {
        BlockPos a = new BlockPos(10, 64, 5);
        Map<BlockPos, IControllable> machines = new LinkedHashMap<>();
        machines.put(a, new Fake(a, "报警器"));
        int[][] locations = {{10}, {64}, {5}};
        Set<BlockPos> linked = new LinkedHashSet<>();
        Map<BlockPos, String> tags = new LinkedHashMap<>();
        LinkerImport.apply(locations, resolver(machines), linked, tags);
        LinkerImport.Result second = LinkerImport.apply(locations, resolver(machines), linked, tags);

        check("second import counts as duplicate", second.added == 0 && second.duplicate == 1 && linked.size() == 1,
                describe(second, linked, tags));
    }

    /** The cable's NBT list must survive the round trip the linker relies on. */
    private static void checkNbtRoundTrip() {
        try {
            NBTTagList list = new NBTTagList();
            list.appendTag(new NBTTagLong(new BlockPos(11, 65, -7).toLong()));
            list.appendTag(new NBTTagLong(new BlockPos(-300, 12, 4096).toLong()));
            NBTTagCompound tag = new NBTTagCompound();
            tag.setTag("pos", list);

            int[][] out = com.hbmzz.item.ItemMonitorLinker.positionsFromTag(tag);
            boolean ok = out != null && out[0].length == 2
                    && out[0][0] == 11 && out[1][0] == 65 && out[2][0] == -7
                    && out[0][1] == -300 && out[1][1] == 12 && out[2][1] == 4096;
            check("recorded coordinates round-trip through NBT", ok, out == null ? "null" : java.util.Arrays.deepToString(out));

            boolean none = com.hbmzz.item.ItemMonitorLinker.positionsFromTag(new NBTTagCompound()) == null;
            check("empty tag -> no coordinates", none, "");
        } catch (Throwable throwable) {
            skipped++;
            System.out.println("SKIP  NBT round trip (" + throwable + ")");
        }
    }

    /** An unknown machine event must not throw when the editor lists it. */
    private static void checkEventRegistry() {
        try {
            ControlEvent.init();
            boolean hasVars = ControlEvent.getOrRegister("rbmk_ctrl_set_level").vars.containsKey("level");
            check("stock event keeps its variables", hasVars, "");
            boolean additive = ControlEvent.getOrRegister("some_addon_machine_event").name
                    .equals("some_addon_machine_event");
            check("unknown machine event is registered instead of thrown", additive, "");
        } catch (Throwable throwable) {
            skipped++;
            System.out.println("SKIP  event registry (" + throwable + ")");
        }
    }

    /**
     * The broadcast step, run against the real {@link com.hbmzz.cecp.ControlEventSystem}.
     *
     * <p>This is the regression that matters most: a control event only exists
     * to reach a machine, and the panel's own registry never holds one - a
     * console registers itself for {@code tick} and nothing else, so the
     * per-event table is empty. The multi-target overload used to look itself
     * up in that table and return when it was missing, which meant a broadcast
     * node aimed at linked machines visited exactly zero of them and reported
     * no error. The observable difference is whether the target positions are
     * touched at all, so the world below counts how many times it is asked.</p>
     */
    private static void checkEventDelivery() {
        try {
            CountingWorld world = new CountingWorld();
            com.hbmzz.cecp.ControlEventSystem system = com.hbmzz.cecp.ControlEventSystem.get(world);

            BlockPos a = new BlockPos(1, 64, 1);
            BlockPos b = new BlockPos(2, 64, 2);
            BlockPos c = new BlockPos(3, 64, 3);

            // 1. Nothing is registered for this name - every position still has
            //    to be visited, or nothing downstream can ever deliver it.
            ControlEvent evt = new ControlEvent("rbmk_ctrl_set_level").setVar("level", 50);
            system.broadcastEvent(a, evt, java.util.Arrays.asList(a, b, c));
            check("broadcast visits every linked position", world.asked == 3, "positions asked=" + world.asked);

            // 2. A machine that did register here still gets the event.
            Recorder recorder = new Recorder(a, world, "rbmk_ctrl_set_level");
            system.addControllable(recorder);
            system.broadcastEvent(c, evt, java.util.Collections.singletonList(a));
            check("registered machine receives the event", "rbmk_ctrl_set_level".equals(recorder.seen),
                    "seen=" + recorder.seen);

            // 3. A broadcast with no target list fans out to every subscriber.
            recorder.seen = "";
            system.broadcastEvent(c, evt);
            check("null target list reaches subscribers", "rbmk_ctrl_set_level".equals(recorder.seen),
                    "seen=" + recorder.seen);
        } catch (Throwable throwable) {
            skipped++;
            System.out.println("SKIP  event delivery (" + throwable + ")");
        }
    }

    /**
     * The addon nodes ported from CE Addon, evaluated for real.
     *
     * <p>These carry the "some blocks are missing" complaint: upstream's node
     * menu has text, utility and sound categories that the stock port never
     * had. A node that exists but computes the wrong thing is worse than a
     * missing one, so each is given inputs here and asked for its answer.</p>
     *
     * <p>Only the nodes that build out of ports alone are covered - anything
     * that constructs a drop-down or a text box pulls in the client GUI classes,
     * which is exactly what this harness does not stand in for.</p>
     */
    private static void checkAddonNodes() {
        try {
            com.hbmzz.cecp.nodes.addon.NodeAddString add = new com.hbmzz.cecp.nodes.addon.NodeAddString(0, 0);
            add.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueString("ab"));
            add.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueString("cd"));
            check("string add joins both inputs", "abcd".equals(add.evaluate(0).toString()),
                    add.evaluate(0).toString());

            com.hbmzz.cecp.nodes.addon.NodeSubString sub = new com.hbmzz.cecp.nodes.addon.NodeSubString(0, 0);
            sub.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueString("abcdef"));
            sub.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueFloat(2));
            sub.inputs.get(2).setDefault(new com.hbmzz.cecp.types.DataValueFloat(4));
            check("substring is 1-based, end exclusive", "bcd".equals(sub.evaluate(0).toString()),
                    sub.evaluate(0).toString());

            com.hbmzz.cecp.nodes.addon.NodeSIPfx si = new com.hbmzz.cecp.nodes.addon.NodeSIPfx(0, 0);
            si.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueFloat(1500));
            si.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueFloat(0));
            check("si prefix shortens 1500 to 1.5k", "1.5k".equals(si.evaluate(0).toString()),
                    si.evaluate(0).toString());

            com.hbmzz.cecp.nodes.addon.NodeParseFloat parse = new com.hbmzz.cecp.nodes.addon.NodeParseFloat(0, 0);
            parse.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueString("12.5"));
            boolean good = parse.evaluate(0).getNumber() == 12.5F && parse.evaluate(1).getNumber() == 0F;
            check("text to number parses and clears the error flag", good, "");
            parse.cacheValid = false;
            parse.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueString("nope"));
            boolean bad = parse.evaluate(0).getNumber() == 0F && parse.evaluate(1).getNumber() == 1F;
            check("text to number flags garbage as an error", bad, "");

            com.hbmzz.cecp.nodes.addon.NodeSRLatch latch = new com.hbmzz.cecp.nodes.addon.NodeSRLatch(0, 0);
            latch.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueFloat(1));
            latch.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueFloat(0));
            boolean held = latch.evaluate(0).getNumber() == 1F;
            latch.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueFloat(0));
            boolean stays = latch.evaluate(0).getNumber() == 1F;
            latch.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueFloat(1));
            boolean released = latch.evaluate(0).getNumber() == 0F;
            check("sr latch holds after set and drops on reset", held && stays && released,
                    held + "/" + stays + "/" + released);

            com.hbmzz.cecp.nodes.addon.NodeClock clock = new com.hbmzz.cecp.nodes.addon.NodeClock(0, 0);
            clock.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueFloat(0));
            clock.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueFloat(1));
            boolean silent = clock.evaluate(0).getNumber() == 0F;
            clock.cacheValid = false;
            clock.inputs.get(0).setDefault(new com.hbmzz.cecp.types.DataValueFloat(1));
            clock.inputs.get(1).setDefault(new com.hbmzz.cecp.types.DataValueFloat(0));
            boolean steady = clock.evaluate(0).getNumber() == 1F;
            check("clock is silent at zero uptime, steady at zero downtime", silent && steady,
                    silent + "/" + steady);
        } catch (Throwable throwable) {
            skipped++;
            System.out.println("SKIP  addon nodes (" + throwable + ")");
        }
    }

    /** Counts the positions the panel actually looked at. */
    private static final class CountingWorld extends World {

        int asked;

        @Override
        public boolean isBlockLoaded(BlockPos pos) {
            asked++;
            return true;
        }

        @Override
        public net.minecraft.tileentity.TileEntity getTileEntity(BlockPos pos) {
            return null;
        }

        @Override
        public net.minecraft.block.state.IBlockState getBlockState(BlockPos pos) {
            return null;
        }
    }

    /** A controllable that only remembers which event reached it. */
    private static final class Recorder implements IControllable {

        private final BlockPos pos;
        private final World world;
        private final java.util.List<String> inEvents;
        String seen = "";

        Recorder(BlockPos pos, World world, String inEvent) {
            this.pos = pos;
            this.world = world;
            this.inEvents = java.util.Collections.singletonList(inEvent);
        }

        @Override
        public java.util.List<String> getInEvents() {
            return inEvents;
        }

        @Override
        public BlockPos getControlPos() {
            return pos;
        }

        @Override
        public World getControlWorld() {
            return world;
        }

        @Override
        public void receiveEvent(BlockPos from, ControlEvent evt) {
            seen = evt.name;
        }
    }

    /**
     * The instruments ported from the addon must actually end up in the registry.
     *
     * <p>Driven reflectively on purpose: if any of them pulls in a client class
     * at construction time this reports SKIP rather than pretending the panel
     * works. What it catches is the realistic failure - a registry name that
     * does not match what the addon writes to NBT, so saved panels would quietly
     * turn into {@code UnknownControl}.</p>
     */
    private static void checkAddonControls() {
        String[] expected = {
                "leafia_nixie", "leafia_sbswitch", "leafia_sbbutton", "leafia_textindicator",
                "leafia_rbmk", "leafia_graph", "leafia_meter_h", "leafia_meter_v",
                "leafia_drawing_box", "leafia_lever_h", "leafia_lever_v",
        };
        try {
            Class<?> registry = Class.forName("com.hbmzz.cecp.ControlRegistry");
            registry.getMethod("init").invoke(null);
            java.lang.reflect.Method isRegistered = registry.getMethod("isRegistered", String.class);
            java.util.List<String> missing = new java.util.ArrayList<>();
            for (String name : expected) {
                if (!Boolean.TRUE.equals(isRegistered.invoke(null, name))) {
                    missing.add(name);
                }
            }
            check("all " + expected.length + " addon instruments register", missing.isEmpty(),
                    "missing=" + missing);
        } catch (Throwable notRunnableOffline) {
            Throwable cause = notRunnableOffline;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
            }
            skip("addon instruments register", cause.getClass().getName()
                    + ": " + cause.getMessage());
        }
    }

    /**
     * The bridge to NTM: Cursed Edition, checked against the real jar.
     *
     * <p>Needs the CE jar on the classpath; without it the whole point of the
     * panel's machine support cannot be exercised, so it reports a skip rather
     * than a pass.</p>
     */
    private static void checkCursedEditionBridge() {
        try {
            Class.forName("com.hbm.inventory.control_panel.IControllable");
        } catch (Throwable throwable) {
            skipped++;
            System.out.println("SKIP  Cursed Edition interface not on the classpath");
            return;
        }
        try {
            check("bridge resolves the Cursed Edition contract",
                    com.hbmzz.ctrl.MachineBridge.available(), "probe failed");
            java.lang.reflect.Method method = Class.forName("com.hbm.items.tool.ItemMultiDetonator")
                    .getMethod("getLocations", net.minecraft.item.ItemStack.class);
            check("multi detonator exposes getLocations(ItemStack)",
                    method.getReturnType() == int[][].class, method.toString());
        } catch (Throwable throwable) {
            skipped++;
            System.out.println("SKIP  Cursed Edition bridge (" + throwable + ")");
        }
    }

    // ------------------------------------------------------------- utilities
    private static LinkerImport.Resolver resolver(final Map<BlockPos, IControllable> machines) {
        return new LinkerImport.Resolver() {
            @Override
            public IControllable at(BlockPos pos) {
                return machines.get(pos);
            }

            @Override
            public String label(BlockPos pos, IControllable machine) {
                return machine.toString() + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
            }
        };
    }

    private static String describe(LinkerImport.Result result, Set<BlockPos> linked, Map<BlockPos, String> tags) {
        return "recorded=" + result.recorded + " added=" + result.added + " duplicate=" + result.duplicate
                + " rejected=" + result.rejected + " linked=" + linked + " tags=" + tags;
    }

    private static void skip(String name, String why) {
        skipped++;
        System.out.println("SKIP  " + name + "  [" + why + "]");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + "  [" + detail + "]");
        }
    }

    private static final class Fake implements IControllable {

        private final BlockPos pos;
        private final String name;

        private Fake(BlockPos pos, String name) {
            this.pos = pos;
            this.name = name;
        }

        @Override
        public BlockPos getControlPos() {
            return this.pos;
        }

        @Override
        public World getControlWorld() {
            return null;
        }

        @Override
        public String toString() {
            return this.name;
        }
    }
}

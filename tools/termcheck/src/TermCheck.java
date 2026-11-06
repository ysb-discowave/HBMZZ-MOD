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

import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlPanelHost;
import com.hbmzz.ctrl.GroupedHost;
import com.hbmzz.ctrl.PartGroup;
import com.hbmzz.ctrl.PartTypes;
import com.hbmzz.ctrl.SnapshotHost;
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;
import com.hbmzz.ctrl.TerminalHost;
import com.hbmzz.ctrl.TerminalShell;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline check of the terminal's grammar, completion and command dispatch.
 *
 * <p>Runs the real {@link TerminalShell} against the real {@link CtrlPanel} and
 * {@link CtrlWidget}, with the same stand-ins for {@code BlockPos}/{@code NBT*}
 * the linker check uses. What that buys is the part nobody can see in a
 * screenshot: whether Tab replaces the right span of text, whether a completion
 * list is filtered by the prefix or by the whole line, and whether the verb
 * table and the help text agree.</p>
 *
 * <p>Machine I/O is out of scope on purpose - {@code MachineBridge} needs NTM's
 * interfaces, which are not present here, so every path that would touch a
 * machine reports "读不到" instead. That is itself worth asserting: a terminal
 * with no machine interface must say so rather than print silence.</p>
 */
public final class TermCheck {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        tokens();
        completion();
        dispatch();
        hosts();
        groups();

        System.out.println();
        System.out.println("passed " + passed + ", failed " + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------- fixtures

    /**
     * A panel with two bound machines and one terminal pointing at the first.
     * Slot C stays empty on purpose: the completion must not offer it.
     */
    private static CtrlPanel panel() {
        CtrlPanel panel = new CtrlPanel();
        panel.name = "测试面板";
        panel.links[0].set(new BlockPos(1, 2, 3), "RBMK 控制棒");
        panel.links[0].keys.add("heat");
        panel.links[0].keys.add("level");
        panel.links[0].events.add("rbmk_ctrl_set_level");
        panel.links[0].vars.put("rbmk_ctrl_set_level", "level");
        panel.links[1].set(new BlockPos(4, 5, 6), "蒸汽发生器");
        panel.links[1].keys.add("steam");
        CtrlWidget terminal = CtrlWidget.of(CtrlType.TERMINAL, 0, 0);
        terminal.link = 0;
        terminal.label = "终端";
        panel.widgets.add(terminal);
        return panel;
    }

    private static CtrlWidget terminal(CtrlPanel panel) {
        return panel.widgets.get(0);
    }

    /** The terminal's view of that panel, which is what the shell talks to. */
    private static CtrlPanelHost host(CtrlPanel panel) {
        return new CtrlPanelHost(panel, 0, null);
    }

    private static TerminalShell.Sink run(CtrlPanel panel, String line) {
        TerminalShell.Sink sink = new TerminalShell.Sink();
        TerminalShell.execute(host(panel), line, sink);
        return sink;
    }

    // ---------------------------------------------------------------- tokens

    private static void tokens() {
        check("empty line has no tokens", TerminalShell.tokens("").length == 0);
        check("whitespace collapses", TerminalShell.tokens("  a   b\tc ").length == 3);
        check("slot A", TerminalShell.slotOf("A") == 0);
        check("slot F", TerminalShell.slotOf("F") == 5);
        check("slot 3", TerminalShell.slotOf("3") == 3);
        check("slot G is invalid", TerminalShell.slotOf("G") < 0);
        check("slot 7 is invalid", TerminalShell.slotOf("7") < 0);
        check("long argument is clipped",
                TerminalShell.tokens(repeat("x", 80))[0].length() == TerminalShell.MAX_ARG);
    }

    // ------------------------------------------------------------ completion

    private static void completion() {
        CtrlPanel panel = panel();

        TerminalShell.Completion all = TerminalShell.complete(host(panel), "", 0);
        check("empty line completes every verb", all.candidates.size() == TerminalShell.COMMANDS.length,
                all.candidates.toString());
        check("empty line starts at 0", all.start == 0);

        TerminalShell.Completion re = TerminalShell.complete(host(panel), "re", 2);
        check("prefix filters verbs", re.candidates.size() == 1 && re.candidates.get(0).equals("read"),
                re.candidates.toString());

        TerminalShell.Completion space = TerminalShell.complete(host(panel), "use ", 4);
        check("use completes only bound slots",
                space.candidates.size() == 2 && space.candidates.contains("A") && space.candidates.contains("B"),
                space.candidates.toString());
        check("use completion starts after the space", space.start == 4);

        TerminalShell.Completion mid = TerminalShell.complete(host(panel), "use A", 5);
        check("a complete word is still offered back",
                mid.candidates.size() == 1 && mid.candidates.get(0).equals("A"), mid.candidates.toString());

        TerminalShell.Completion keys = TerminalShell.complete(host(panel), "read ", 5);
        check("read completes data keys",
                keys.candidates.size() == 2 && keys.candidates.contains("heat") && keys.candidates.contains("level"),
                keys.candidates.toString());

        TerminalShell.Completion keyPrefix = TerminalShell.complete(host(panel), "read le", 7);
        check("key prefix filters", keyPrefix.candidates.size() == 1
                && keyPrefix.candidates.get(0).equals("level"), keyPrefix.candidates.toString());
        check("key completion starts at the token, not the line", keyPrefix.start == 5);

        TerminalShell.Completion events = TerminalShell.complete(host(panel), "ev ", 3);
        check("ev completes events", events.candidates.size() == 1
                && events.candidates.get(0).equals("rbmk_ctrl_set_level"), events.candidates.toString());

        String withEvent = "ev rbmk_ctrl_set_level ";
        TerminalShell.Completion vars = TerminalShell.complete(host(panel), withEvent, withEvent.length());
        check("ev completes the variable of that event",
                vars.candidates.size() == 1 && vars.candidates.get(0).equals("level="),
                vars.candidates.toString());

        TerminalShell.Completion setVars = TerminalShell.complete(host(panel), "set ", 4);
        check("set completes known variable names",
                setVars.candidates.size() == 1 && setVars.candidates.get(0).equals("level"),
                setVars.candidates.toString());

        TerminalShell.Completion none = TerminalShell.complete(host(panel), "zzz", 3);
        check("no match offers nothing", none.candidates.isEmpty(), none.candidates.toString());

        // A caret in the middle of a line must complete the word it is in, not
        // the last word on the line.
        TerminalShell.Completion middle = TerminalShell.complete(host(panel), "use A extra", 4);
        check("caret in the middle completes that word",
                middle.start == 4 && middle.candidates.contains("A"), middle.start + " " + middle.candidates);
    }

    // -------------------------------------------------------------- dispatch

    private static void dispatch() {
        CtrlPanel panel = panel();

        TerminalShell.Sink help = run(panel, "help");
        check("help lists every verb", help.lines.size() >= TerminalShell.COMMANDS.length + 2,
                Integer.toString(help.lines.size()));
        boolean everyVerbNamed = true;
        for (String verb : TerminalShell.COMMANDS) {
            boolean found = false;
            for (String line : help.lines) {
                if (line.trim().startsWith(verb)) {
                    found = true;
                    break;
                }
            }
            everyVerbNamed &= found;
        }
        check("help names every verb", everyVerbNamed, help.lines.toString());

        TerminalShell.Sink topic = run(panel, "help ev");
        check("help <verb> prints one line", topic.lines.size() == 1
                && topic.lines.get(0).contains("ev <"), topic.lines.toString());

        TerminalShell.Sink useB = run(panel, "use B");
        check("use B moves the target", terminal(panel).link == 1, "link=" + terminal(panel).link);

        CtrlWidget widget = terminal(panel);
        TerminalShell.Sink useBAgain = new TerminalShell.Sink();
        TerminalShell.Result again = TerminalShell.execute(host(panel), "use b", useBAgain);
        check("use b is a no-op when already there",
                !again.dirty && useBAgain.lines.get(0).contains("已经在"), useBAgain.lines.toString());

        TerminalShell.Result moved = TerminalShell.execute(host(panel), "use A", new TerminalShell.Sink());
        check("selecting a machine marks the panel dirty", moved.dirty && widget.link == 0);

        TerminalShell.Sink empty = run(panel, "use C");
        check("an unbound slot is refused", kindOf(empty, 0) == TerminalShell.ERR, empty.lines.toString());

        TerminalShell.Sink bad = run(panel, "use Z");
        check("a bad slot name is refused", kindOf(bad, 0) == TerminalShell.ERR, bad.lines.toString());

        TerminalShell.Sink unknown = run(panel, "frobnicate");
        check("an unknown verb is refused", kindOf(unknown, 0) == TerminalShell.ERR
                && unknown.lines.size() == 2, unknown.lines.toString());

        TerminalShell.Sink clear = run(panel, "clear");
        check("clear asks the window to drop its scrollback", clear.lines.size() <= 1);

        TerminalShell.Result cleared = TerminalShell.execute(host(panel), "clear", new TerminalShell.Sink());
        check("clear sets the flag", cleared.clear);

        // Machine I/O without NTM: every read path must explain itself instead
        // of printing nothing.
        TerminalShell.Sink read = run(panel, "read");
        check("read without a machine interface says so", kindOf(read, 0) == TerminalShell.ERR,
                read.lines.toString());
        TerminalShell.Sink events = run(panel, "events");
        check("events lists what the machine announced", events.lines.size() == 2
                && events.lines.get(1).contains("rbmk_ctrl_set_level"), events.lines.toString());
        TerminalShell.Sink fire = run(panel, "ev rbmk_ctrl_set_level level=40");
        check("a failed event is reported", kindOf(fire, fire.lines.size() - 1) == TerminalShell.ERR,
                fire.lines.toString());
        TerminalShell.Sink set = run(panel, "set level 40");
        check("set resolves the variable to its event and reports the failure",
                kindOf(set, set.lines.size() - 1) == TerminalShell.ERR, set.lines.toString());

        TerminalShell.Sink noTarget = new TerminalShell.Sink();
        CtrlWidget orphan = CtrlWidget.of(CtrlType.TERMINAL, 0, 0);
        orphan.link = -1;
        CtrlPanel bare = new CtrlPanel();
        TerminalShell.execute(new CtrlPanelHost(bare, 0, null), "read", noTarget);
        check("no target is explained", kindOf(noTarget, 0) == TerminalShell.ERR,
                noTarget.lines.toString());
    }

    // --------------------------------------------------------- host switch

    /**
     * The same shell driven by a snapshot instead of a panel.
     *
     * <p>That is exactly the ported console's situation: its window completes
     * against whatever the server shipped, and a slot label there is a linker
     * tag rather than a letter.</p>
     */
    private static void hosts() {
        CtrlPanel panel = panel();
        check("a slot resolves by its machine name",
                TerminalShell.resolve(host(panel), "RBMK 控制棒") == 0);
        check("a slot resolves by a unique prefix",
                TerminalShell.resolve(host(panel), "蒸汽") == 1);
        check("a slot still resolves by letter", TerminalShell.resolve(host(panel), "b") == 1);
        check("an unknown name resolves to nothing", TerminalShell.resolve(host(panel), "锅炉") < 0);

        run(panel, "use B");
        TerminalShell.Sink named = run(panel, "use RBMK 控制棒");
        check("use accepts a name with a space in it", terminal(panel).link == 0, named.lines.toString());

        SnapshotHost snapshot = new SnapshotHost();
        snapshot.add("反应堆", "RBMK", true);
        snapshot.add("主泵", "泵", true);
        snapshot.target(0);
        java.util.List<String> keys = new java.util.ArrayList<String>();
        keys.add("heat");
        snapshot.vocab(0, keys, new java.util.ArrayList<String>(),
                new java.util.LinkedHashMap<String, String>());

        TerminalShell.Completion slots = TerminalShell.complete(snapshot, "use ", 4);
        check("a snapshot completes its own labels",
                slots.candidates.size() == 2 && slots.candidates.contains("反应堆"),
                slots.candidates.toString());
        TerminalShell.Completion snapKeys = TerminalShell.complete(snapshot, "read ", 5);
        check("a snapshot completes the vocabulary it was given",
                snapKeys.candidates.size() == 1 && snapKeys.candidates.get(0).equals("heat"),
                snapKeys.candidates.toString());

        TerminalShell.Sink list = new TerminalShell.Sink();
        TerminalShell.execute(snapshot, "ls", list);
        boolean marked = false;
        for (String line : list.lines) {
            if (line.trim().startsWith("*")) {
                marked = true;
            }
        }
        check("ls marks the current target", marked, list.lines.toString());
    }

    // --------------------------------------------------------------- groups

    /**
     * One 部件类型 in front of several machines.
     *
     * <p>What is asserted is the contract the window and the broadcast rest on,
     * not Minecraft: a write reaches the parts that declare the event and only
     * those, it reaches each of them once, and a read shows the spread instead
     * of one member's number pretending to be the whole reactor.</p>
     */
    private static void groups() {
        FakeHost machines = new FakeHost();
        for (int i = 0; i < 4; i++) {
            machines.add("杆" + i, "RBMK 控制棒（手动）", new BlockPos(i, 64, 0),
                    40.0D + i, "rbmk_ctrl_set_level", "level");
        }
        machines.add("锅炉 1", "RBMK 蒸汽发生器", new BlockPos(0, 66, 0), 100.0D, "rbmk_boiler_purge", "");
        machines.add("锅炉 2", "RBMK 蒸汽发生器", new BlockPos(1, 66, 0), 100.0D, "rbmk_boiler_purge", "");

        List<Integer> reactorSlots = new ArrayList<Integer>();
        List<String> reactorNames = new ArrayList<String>();
        for (int i = 0; i < 6; i++) {
            reactorSlots.add(Integer.valueOf(i));
            reactorNames.add(i < 4 ? "RBMK 控制棒（手动）" : "RBMK 蒸汽发生器");
        }
        PartGroup reactor = new PartGroup("rbmk", "RBMK 反应堆", machines, reactorSlots, reactorNames);

        check("a group counts its parts by name",
                reactor.census().size() == 2
                        && reactor.census().get("RBMK 控制棒（手动）").intValue() == 4
                        && reactor.census().get("RBMK 蒸汽发生器").intValue() == 2,
                reactor.census().toString());
        check("a group names itself by reactor and size",
                reactor.describe().equals("RBMK 反应堆 · 6 部件"), reactor.describe());
        check("a group reports the spread across the whole reactor",
                "40–100".equals(reactor.read().get("level")), reactor.read().toString());

        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put("level", Double.valueOf(40));
        machines.fired.clear();
        int sent = reactor.fire("rbmk_ctrl_set_level", vars);
        check("a group write reaches every member that takes the event",
                sent == 4 && machines.fired.size() == 4, "sent=" + sent + " " + machines.fired);
        check("a group write never reaches a part that does not",
                !machines.fired.contains("4:rbmk_ctrl_set_level"), machines.fired.toString());
        check("a group write delivers once per member, not once per pair",
                machines.fired.size() == 4, machines.fired.toString());

        machines.fired.clear();
        int purged = reactor.fire("rbmk_boiler_purge", new LinkedHashMap<String, Object>());
        check("a group write splits by part name through the event itself",
                purged == 2 && machines.fired.size() == 2
                        && machines.fired.get(0).startsWith("4:"), machines.fired.toString());

        PartGroup alone = new PartGroup("rbmk", "RBMK 反应堆", machines,
                java.util.Collections.singletonList(Integer.valueOf(0)),
                java.util.Collections.singletonList("RBMK 控制棒（手动）"));
        check("a one-part group describes the part, not a count",
                alone.describe().equals("RBMK 控制棒（手动）"), alone.describe());
        machines.fired.clear();
        alone.fire("rbmk_ctrl_set_level", vars);
        check("a one-part group is still one delivery", machines.fired.size() == 1, machines.fired.toString());

        GroupHost host = new GroupHost(reactor);

        TerminalShell.Sink parts = new TerminalShell.Sink();
        TerminalShell.execute(host, "parts", parts);
        check("parts lists the names of a group with their counts",
                parts.lines.size() == 4 && parts.lines.get(1).contains("控制棒")
                        && parts.lines.get(1).contains("×4"), parts.lines.toString());

        machines.fired.clear();
        TerminalShell.Sink fired = new TerminalShell.Sink();
        TerminalShell.execute(host, "ev rbmk_ctrl_set_level level=55", fired);
        String last = fired.lines.isEmpty() ? "" : fired.lines.get(fired.lines.size() - 1);
        check("a group write reports how many parts it moved", last.endsWith("×4"), last);

        TerminalShell.Sink read = new TerminalShell.Sink();
        TerminalShell.execute(host, "read", read);
        check("read shows the group's spread, not one member", joined(read.lines).contains("40–100"),
                read.lines.toString());

        TerminalShell.Sink list = new TerminalShell.Sink();
        TerminalShell.execute(host, "ls", list);
        check("ls says how many parts a slot covers", joined(list.lines).contains("6 部件"),
                list.lines.toString());

        // Folding needs machines to look at. Without a world every slot stays
        // itself, which is what keeps a six-slot panel of six different
        // machines exactly as it was.
        TerminalHost plain = new CtrlPanelHost(panel(), 0, null);
        check("no world means no folding", GroupedHost.of(plain, null) == plain);

        // Grouping is a family test, not a distance test. The two rods below are
        // 120 blocks apart and still one slot - which is the whole point for a
        // DFC whose core is wider than any radius worth hard-coding. What
        // separates two reactors is two terminals, so the DFC part at x=200
        // stays in a slot of its own here only because it is another family.
        List<BlockPos> spread = new ArrayList<BlockPos>();
        spread.add(new BlockPos(0, 64, 0));
        spread.add(new BlockPos(120, 64, 0));
        spread.add(new BlockPos(200, 64, 0));
        List<List<BlockPos>> folded = PartTypes.byFamily(spread,
                pos -> pos.getX() < 150 ? PartTypes.RBMK : PartTypes.DFC);
        check("same-family parts fold together however far apart they are",
                folded.size() == 2 && folded.get(0).size() == 2 && folded.get(1).size() == 1,
                folded.toString());
        check("a folded slot keeps the order the panel lists it in",
                folded.size() == 2 && folded.get(0).get(0).getX() == 0
                        && folded.get(0).get(1).getX() == 120,
                folded.size() > 0 ? folded.get(0).toString() : "no groups");

        // An unreadable machine gets a slot of its own rather than being pooled
        // with every other unreadable machine, because two unknown classes have
        // nothing in common and pooling them would broadcast between them.
        List<BlockPos> mystery = new ArrayList<BlockPos>();
        mystery.add(new BlockPos(5, 64, 5));
        mystery.add(new BlockPos(6, 64, 5));
        check("parts whose family cannot be told stay in their own slots",
                PartTypes.byFamily(mystery, pos -> "").size() == 2,
                PartTypes.byFamily(mystery, pos -> "").toString());

        // A machine bound twice must not become two parts: the panel allows it,
        // and counting it twice would make a two-rod reactor report ×4.
        List<BlockPos> twice = new ArrayList<BlockPos>();
        twice.add(new BlockPos(0, 64, 0));
        twice.add(new BlockPos(0, 64, 0));
        check("a position bound twice is still one part",
                PartTypes.byFamily(twice, pos -> PartTypes.RBMK).get(0).size() == 1,
                PartTypes.byFamily(twice, pos -> PartTypes.RBMK).toString());

        // The names NTM actually gives its DFC parts. Not one of them contains
        // the string "DFC" - they are named after what the part does - so a
        // single "DFC" needle matched nothing and a large DFC fell apart into
        // one slot per component class, which is the exact machine this feature
        // exists to make one slot.
        String[] dfc = {"TileEntityCoreEmitter", "TileEntityCoreInjector",
                "TileEntityCoreReceiver", "TileEntityCoreStabilizer",
                "TileEntityCoreDetonator", "TileEntityCoreExchanger", "TileEntityCore"};
        StringBuilder notDfc = new StringBuilder();
        for (String name : dfc) {
            if (!PartTypes.DFC.equals(PartTypes.familyOfClass(name))) {
                notDfc.append(name).append(' ');
            }
        }
        check("every DFC component class lands in the DFC family",
                notDfc.length() == 0, "not DFC: " + notDfc);

        // "TileEntityCore" is a prefix of every other DFC part, so as a
        // substring needle it would swallow the whole namespace - including
        // whatever an addon puts there next.
        check("the core entry matches the core, not everything starting with it",
                PartTypes.DFC.equals(PartTypes.familyOfClass("TileEntityCore"))
                        && !PartTypes.DFC.equals(PartTypes.familyOfClass("TileEntityCorelessWidget")),
                PartTypes.familyOfClass("TileEntityCorelessWidget"));

        check("an RBMK part is RBMK, and an unknown class is its own family",
                PartTypes.RBMK.equals(PartTypes.familyOfClass("TileEntityRBMKControlManual"))
                        && PartTypes.familyOfClass("TileEntityThingamajig").startsWith("class:"),
                PartTypes.familyOfClass("TileEntityThingamajig"));
    }

    /** A host that is one group, so the shell's own words can be read back. */
    private static final class GroupHost implements TerminalHost {

        private final PartGroup group;

        GroupHost(PartGroup group) {
            this.group = group;
        }

        @Override
        public int slots() {
            return 1;
        }

        @Override
        public String label(int slot) {
            return "RBMK";
        }

        @Override
        public String name(int slot) {
            return this.group.describe();
        }

        @Override
        public boolean bound(int slot) {
            return slot == 0;
        }

        @Override
        public int target() {
            return 0;
        }

        @Override
        public boolean select(int slot) {
            return false;
        }

        @Override
        public List<String> keys(int slot) {
            return this.group.keys();
        }

        @Override
        public List<String> events(int slot) {
            return this.group.events();
        }

        @Override
        public Map<String, String> vars(int slot) {
            return this.group.vars();
        }

        @Override
        public Map<String, String> read(int slot) {
            return this.group.read();
        }

        @Override
        public int fire(int slot, String event, Map<String, Object> vars) {
            return this.group.fire(event, vars);
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public BlockPos pos(int slot) {
            return this.group.pos();
        }

        @Override
        public int scope(int slot) {
            return this.group.size();
        }

        @Override
        public Map<String, Integer> parts(int slot) {
            return this.group.census();
        }
    }

    /** Machines with no world behind them: enough to exercise a group. */
    private static final class FakeHost implements TerminalHost {

        private static final class Machine {
            String label;
            String name;
            BlockPos pos;
            double level;
            String event;
            String var;
        }

        private final List<Machine> machines = new ArrayList<Machine>();
        final List<String> fired = new ArrayList<String>();

        void add(String label, String name, BlockPos pos, double level, String event, String var) {
            Machine machine = new Machine();
            machine.label = label;
            machine.name = name;
            machine.pos = pos;
            machine.level = level;
            machine.event = event;
            machine.var = var;
            this.machines.add(machine);
        }

        @Override
        public int slots() {
            return this.machines.size();
        }

        @Override
        public String label(int slot) {
            return this.machines.get(slot).label;
        }

        @Override
        public String name(int slot) {
            return this.machines.get(slot).name;
        }

        @Override
        public boolean bound(int slot) {
            return slot >= 0 && slot < this.machines.size();
        }

        @Override
        public int target() {
            return 0;
        }

        @Override
        public boolean select(int slot) {
            return false;
        }

        @Override
        public List<String> keys(int slot) {
            return new ArrayList<String>(read(slot).keySet());
        }

        @Override
        public List<String> events(int slot) {
            List<String> out = new ArrayList<String>();
            out.add(this.machines.get(slot).event);
            return out;
        }

        @Override
        public Map<String, String> vars(int slot) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            out.put(this.machines.get(slot).event, this.machines.get(slot).var);
            return out;
        }

        @Override
        public Map<String, String> read(int slot) {
            Map<String, String> out = new LinkedHashMap<String, String>();
            double value = this.machines.get(slot).level;
            out.put("level", Math.abs(value - Math.rint(value)) < 1.0E-4D
                    ? String.valueOf((long) Math.rint(value)) : String.valueOf(value));
            return out;
        }

        @Override
        public int fire(int slot, String event, Map<String, Object> vars) {
            this.fired.add(slot + ":" + event);
            return 1;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public BlockPos pos(int slot) {
            return this.machines.get(slot).pos;
        }
    }

    // --------------------------------------------------------------- helpers

    private static String joined(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        return text.toString();
    }

    private static int kindOf(TerminalShell.Sink sink, int index) {
        if (index < 0 || index >= sink.kinds.size()) {
            return -1;
        }
        return sink.kinds.get(index).intValue();
    }

    private static String repeat(String text, int count) {
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            parts.add(text);
        }
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            out.append(part);
        }
        return out.toString();
    }

    private static void check(String name, boolean ok) {
        check(name, ok, "");
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name + (detail.isEmpty() ? "" : "  -- " + detail));
        }
    }
}

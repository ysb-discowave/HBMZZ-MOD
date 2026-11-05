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
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;
import com.hbmzz.ctrl.TerminalShell;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

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

    private static TerminalShell.Sink run(CtrlPanel panel, String line) {
        TerminalShell.Sink sink = new TerminalShell.Sink();
        TerminalShell.execute(panel, terminal(panel), null, line, sink);
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

        TerminalShell.Completion all = TerminalShell.complete(panel, terminal(panel), "", 0);
        check("empty line completes every verb", all.candidates.size() == TerminalShell.COMMANDS.length,
                all.candidates.toString());
        check("empty line starts at 0", all.start == 0);

        TerminalShell.Completion re = TerminalShell.complete(panel, terminal(panel), "re", 2);
        check("prefix filters verbs", re.candidates.size() == 1 && re.candidates.get(0).equals("read"),
                re.candidates.toString());

        TerminalShell.Completion space = TerminalShell.complete(panel, terminal(panel), "use ", 4);
        check("use completes only bound slots",
                space.candidates.size() == 2 && space.candidates.contains("A") && space.candidates.contains("B"),
                space.candidates.toString());
        check("use completion starts after the space", space.start == 4);

        TerminalShell.Completion mid = TerminalShell.complete(panel, terminal(panel), "use A", 5);
        check("a complete word is still offered back",
                mid.candidates.size() == 1 && mid.candidates.get(0).equals("A"), mid.candidates.toString());

        TerminalShell.Completion keys = TerminalShell.complete(panel, terminal(panel), "read ", 5);
        check("read completes data keys",
                keys.candidates.size() == 2 && keys.candidates.contains("heat") && keys.candidates.contains("level"),
                keys.candidates.toString());

        TerminalShell.Completion keyPrefix = TerminalShell.complete(panel, terminal(panel), "read le", 7);
        check("key prefix filters", keyPrefix.candidates.size() == 1
                && keyPrefix.candidates.get(0).equals("level"), keyPrefix.candidates.toString());
        check("key completion starts at the token, not the line", keyPrefix.start == 5);

        TerminalShell.Completion events = TerminalShell.complete(panel, terminal(panel), "ev ", 3);
        check("ev completes events", events.candidates.size() == 1
                && events.candidates.get(0).equals("rbmk_ctrl_set_level"), events.candidates.toString());

        String withEvent = "ev rbmk_ctrl_set_level ";
        TerminalShell.Completion vars = TerminalShell.complete(panel, terminal(panel), withEvent, withEvent.length());
        check("ev completes the variable of that event",
                vars.candidates.size() == 1 && vars.candidates.get(0).equals("level="),
                vars.candidates.toString());

        TerminalShell.Completion setVars = TerminalShell.complete(panel, terminal(panel), "set ", 4);
        check("set completes known variable names",
                setVars.candidates.size() == 1 && setVars.candidates.get(0).equals("level"),
                setVars.candidates.toString());

        TerminalShell.Completion none = TerminalShell.complete(panel, terminal(panel), "zzz", 3);
        check("no match offers nothing", none.candidates.isEmpty(), none.candidates.toString());

        // A caret in the middle of a line must complete the word it is in, not
        // the last word on the line.
        TerminalShell.Completion middle = TerminalShell.complete(panel, terminal(panel), "use A extra", 4);
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
        TerminalShell.Result again = TerminalShell.execute(panel, widget, null, "use b", useBAgain);
        check("use b is a no-op when already there",
                !again.dirty && useBAgain.lines.get(0).contains("已经在"), useBAgain.lines.toString());

        TerminalShell.Result moved = TerminalShell.execute(panel, widget, null, "use A", new TerminalShell.Sink());
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

        TerminalShell.Result cleared = TerminalShell.execute(panel, widget, null, "clear", new TerminalShell.Sink());
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
        TerminalShell.execute(bare, orphan, null, "read", noTarget);
        check("no target is explained", kindOf(noTarget, 0) == TerminalShell.ERR,
                noTarget.lines.toString());
    }

    // --------------------------------------------------------------- helpers

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

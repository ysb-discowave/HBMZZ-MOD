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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The language the terminal speaks: a handful of verbs over the same two calls
 * a control makes - {@code getQueryData()} in, {@code receiveEvent()} out.
 *
 * <p>Two halves, deliberately separate. {@link #complete} needs no world, so the
 * window can complete a keystroke with zero latency against the panel copy it
 * already holds; {@link #execute} needs the world, so it only ever runs on the
 * server, against the server's own copy of the panel. That split is what keeps
 * the client from being able to name a machine the panel is not bound to: it
 * sends a line of text, and the server decides what that text can reach.</p>
 *
 * <p>The grammar is small on purpose. A terminal that can do everything is a
 * second, worse editor; this one does the four things a person at a reactor
 * actually wants at speed - see which machines are wired, switch between them,
 * read their numbers, and push a value - without opening the panel editor or
 * dragging a knob through 0-100%.</p>
 */
public final class TerminalShell {

    /** Longest accepted command line. Long enough for six variables, short
     *  enough that a malicious client cannot use the echo as a megaphone. */
    public static final int MAX_LINE = 96;
    /** Longest accepted single argument. */
    public static final int MAX_ARG = 32;
    /** Longest reply, in lines. */
    public static final int MAX_REPLY = 40;

    // Line kinds, mirrored by the reply packet and the painter.
    public static final int OUT = 0;
    public static final int ERR = 1;
    public static final int ECHO = 2;
    public static final int HINT = 3;
    public static final int OK = 4;

    /** Verbs, in the order {@code help} lists them. */
    public static final String[] COMMANDS = {
            "help", "ls", "use", "read", "keys", "events", "ev", "set", "clear"
    };

    private static final Map<String, String> USAGE = new LinkedHashMap<String, String>();

    static {
        USAGE.put("help", "help [命令] — 命令列表，或某条命令的用法");
        USAGE.put("ls", "ls — 列出 A–F 上绑定的机器，▸ 是当前目标");
        USAGE.put("use", "use <A–F> — 切换当前目标机器");
        USAGE.put("read", "read [键] — 读当前机器的全部数据，或某一个键");
        USAGE.put("keys", "keys — 只列可读的数据键名");
        USAGE.put("events", "events — 列出当前机器接受的事件及其变量");
        USAGE.put("ev", "ev <事件> [变量=值]… — 发一个事件，如 ev rbmk_ctrl_set_level level=40");
        USAGE.put("set", "set <变量> <值> — 按变量名发事件，自动找对应事件");
        USAGE.put("clear", "clear — 清屏（历史仍可用 ↑ 取回）");
    }

    private TerminalShell() {
    }

    // ==================================================================== sink

    /** Where a command writes. Kept as two parallel lists so it serialises as-is. */
    public static final class Sink {

        public final List<String> lines = new ArrayList<String>();
        public final List<Integer> kinds = new ArrayList<Integer>();

        public void add(String text, int kind) {
            if (this.lines.size() >= MAX_REPLY) {
                return;
            }
            this.lines.add(text == null ? "" : text);
            this.kinds.add(Integer.valueOf(kind));
        }

        public void out(String text) {
            add(text, OUT);
        }

        public void ok(String text) {
            add(text, OK);
        }

        public void err(String text) {
            add(text, ERR);
        }

        public void hint(String text) {
            add(text, HINT);
        }
    }

    /** What a command changed besides the text it printed. */
    public static final class Result {

        /** The client should drop its scrollback. */
        public boolean clear;
        /** The panel's design changed (a slot was selected), so save it. */
        public boolean dirty;
    }

    // ================================================================ completion

    /**
     * Candidate completions for the token under the caret.
     *
     * <p>Returns the token's start offset plus full replacement strings, so the
     * window only has to splice the line - it never needs to know the grammar.
     * An empty candidate list means "nothing to offer", which the window shows
     * as silence rather than as an error.</p>
     */
    public static final class Completion {

        public final int start;
        public final List<String> candidates;

        Completion(int start, List<String> candidates) {
            this.start = start;
            this.candidates = candidates;
        }
    }

    /**
     * Completes the word the caret sits in.
     *
     * @param input the whole command line as typed
     * @param caret caret position, clamped into {@code input}
     */
    public static Completion complete(CtrlPanel panel, CtrlWidget widget, String input, int caret) {
        String line = input == null ? "" : input;
        int at = Math.max(0, Math.min(line.length(), caret));
        int start = at;
        while (start > 0 && !Character.isWhitespace(line.charAt(start - 1))) {
            start--;
        }
        String prefix = line.substring(start, at);
        // Everything before the word decides which vocabulary applies.
        String before = line.substring(0, start).trim();
        String[] head = before.isEmpty() ? new String[0] : tokens(before);
        String lower = prefix.toLowerCase(Locale.ROOT);

        List<String> pool;
        boolean command = head.length == 0;
        if (command) {
            pool = new ArrayList<String>();
            Collections.addAll(pool, COMMANDS);
        } else {
            pool = vocabulary(panel, widget, head);
        }
        List<String> out = new ArrayList<String>();
        for (String candidate : pool) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower) && !candidate.equalsIgnoreCase(prefix)) {
                out.add(candidate);
            }
        }
        // A word that is already a complete command still deserves to be
        // confirmed, otherwise pressing Tab on "read" looks broken.
        if (out.isEmpty() && !prefix.isEmpty()) {
            for (String candidate : pool) {
                if (candidate.equalsIgnoreCase(prefix)) {
                    out.add(candidate);
                }
            }
        }
        return new Completion(start, out);
    }

    /** What may appear where, given the words already typed. */
    private static List<String> vocabulary(CtrlPanel panel, CtrlWidget widget, String[] head) {
        List<String> pool = new ArrayList<String>();
        String cmd = head[0].toLowerCase(Locale.ROOT);
        CtrlLink link = target(panel, widget);
        switch (cmd) {
            case "use":
            case "sel":
            case "select":
                for (int i = 0; i < CtrlPanel.LINKS; i++) {
                    if (!panel.links[i].isEmpty()) {
                        pool.add(CtrlLink.letter(i));
                    }
                }
                break;
            case "help":
                Collections.addAll(pool, COMMANDS);
                break;
            case "read":
            case "get":
                if (link != null) {
                    pool.addAll(link.keys);
                }
                break;
            case "ev":
            case "fire":
                if (head.length == 1) {
                    if (link != null) {
                        pool.addAll(link.events);
                    }
                } else if (link != null) {
                    // Past the event name: offer "var=" for the variables that
                    // event is known to carry.
                    String var = link.vars.get(head[1]);
                    if (var != null && !var.isEmpty()) {
                        pool.add(var + "=");
                    }
                }
                break;
            case "set":
                if (link != null && head.length == 1) {
                    Set<String> seen = new LinkedHashSet<String>();
                    for (String var : link.vars.values()) {
                        if (!var.isEmpty()) {
                            seen.add(var);
                        }
                    }
                    pool.addAll(seen);
                }
                break;
            default:
                break;
        }
        return pool;
    }

    /** The machine the terminal is looking at, or null. */
    public static CtrlLink target(CtrlPanel panel, CtrlWidget widget) {
        if (panel == null || widget == null) {
            return null;
        }
        if (widget.link < 0 || widget.link >= CtrlPanel.LINKS) {
            return null;
        }
        CtrlLink link = panel.links[widget.link];
        return link.isEmpty() ? null : link;
    }

    // ================================================================= execution

    /**
     * Runs one command line against the server's panel.
     *
     * <p>Never throws: every failure becomes a line the operator can read. A
     * terminal that silently does nothing is worse than one that says why -
     * the usual reason is simply that no machine is selected yet.</p>
     */
    public static Result execute(CtrlPanel panel, CtrlWidget widget, World world, String raw, Sink sink) {
        Result result = new Result();
        if (panel == null || widget == null || sink == null) {
            return result;
        }
        String line = raw == null ? "" : raw.trim();
        if (line.length() > MAX_LINE) {
            line = line.substring(0, MAX_LINE);
        }
        if (line.isEmpty()) {
            return result;
        }
        String[] args = tokens(line);
        String cmd = args[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "help":
            case "?":
                help(args, sink);
                return result;
            case "ls":
            case "list":
            case "dir":
                list(panel, widget, world, sink);
                return result;
            case "use":
            case "sel":
            case "select":
                result.dirty = select(panel, widget, args, sink);
                return result;
            case "read":
            case "get":
            case "cat":
                read(panel, widget, world, args, sink);
                return result;
            case "keys":
                keys(panel, widget, sink);
                return result;
            case "events":
            case "evs":
                events(panel, widget, sink);
                return result;
            case "ev":
            case "fire":
                fire(panel, widget, world, args, sink);
                return result;
            case "set":
                set(panel, widget, world, args, sink);
                return result;
            case "clear":
            case "cls":
                result.clear = true;
                sink.hint("已清屏。");
                return result;
            default:
                sink.err("未知命令：" + clip(args[0]));
                sink.hint("输入 help 查看可用命令。");
                return result;
        }
    }

    private static void help(String[] args, Sink sink) {
        if (args.length > 1) {
            String topic = args[1].toLowerCase(Locale.ROOT);
            String usage = USAGE.get(alias(topic));
            if (usage == null) {
                sink.err("没有这条命令：" + clip(args[1]));
            } else {
                sink.out(usage);
            }
            return;
        }
        sink.hint("可用命令 — Tab 补全，↑ 取回历史");
        for (String command : COMMANDS) {
            String usage = USAGE.get(command);
            if (usage != null) {
                sink.out("  " + usage);
            }
        }
        sink.hint("目标机器用 use A–F 切换，也可以点窗口上方的标签。");
    }

    private static void list(CtrlPanel panel, CtrlWidget terminal, World world, Sink sink) {
        if (!MachineBridge.available()) {
            sink.err("没有可用的机器接口：需要 NTM: 诅咒版。");
            return;
        }
        boolean any = false;
        for (int i = 0; i < CtrlPanel.LINKS; i++) {
            CtrlLink link = panel.links[i];
            String mark = i == terminal.link ? "▸" : " ";
            if (link.isEmpty()) {
                sink.hint("  " + mark + " " + CtrlLink.letter(i) + "  未绑定");
                continue;
            }
            any = true;
            boolean live = MachineBridge.isControllable(world, link.pos);
            sink.add("  " + mark + " " + CtrlLink.letter(i) + "  " + link.label
                    + (live ? "" : "  （离线）"), i == terminal.link ? OK : OUT);
            BlockPos pos = link.pos;
            sink.hint("       " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                    + " · 可读 " + link.keys.size() + " 键 · 可发 " + link.events.size() + " 事件");
        }
        if (!any) {
            sink.err("这个面板还没有绑定任何机器。");
            sink.hint("手持监控数据线右键机器记录，再右键面板绑定。");
        }
    }

    private static boolean select(CtrlPanel panel, CtrlWidget terminal, String[] args, Sink sink) {
        if (args.length < 2) {
            CtrlLink current = target(panel, terminal);
            if (current == null) {
                sink.err("当前没有目标。用法：use A–F");
            } else {
                sink.out("当前目标：" + CtrlLink.letter(terminal.link) + " · " + current.label);
            }
            return false;
        }
        String token = args[1].toUpperCase(Locale.ROOT);
        int slot = slotOf(token);
        if (slot < 0) {
            sink.err("不是有效的槽位：" + clip(args[1]));
            sink.hint("槽位是 A–F，用 ls 看哪些已绑定。");
            return false;
        }
        CtrlLink link = panel.links[slot];
        if (link.isEmpty()) {
            sink.err("槽位 " + CtrlLink.letter(slot) + " 上没有机器。");
            return false;
        }
        if (terminal.link == slot) {
            sink.hint("已经在 " + CtrlLink.letter(slot) + " · " + link.label + " 上。");
            return false;
        }
        terminal.link = slot;
        sink.ok("目标 → " + CtrlLink.letter(slot) + " · " + link.label);
        return true;
    }

    private static void read(CtrlPanel panel, CtrlWidget terminal, World world, String[] args, Sink sink) {
        CtrlLink link = requireTarget(panel, terminal, sink);
        if (link == null) {
            return;
        }
        MachineBridge.Sample sample = MachineBridge.sample(world, link.pos);
        if (sample == null) {
            sink.err("读不到 " + CtrlLink.letter(terminal.link) + "：机器已不在或不受控制面板管理。");
            return;
        }
        if (args.length > 1) {
            String key = args[1];
            if (!sample.has(key)) {
                sink.err("这台机器没有上报 " + clip(key) + "。");
                sink.hint(sample.numbers.isEmpty() && sample.texts.isEmpty()
                        ? "它现在什么也没上报。" : "用 keys 看可读的键名。");
                return;
            }
            sink.out("  " + key + " = " + sample.text(key));
            return;
        }
        List<String> all = new ArrayList<String>();
        all.addAll(sample.numbers.keySet());
        all.addAll(sample.texts.keySet());
        Collections.sort(all);
        if (all.isEmpty()) {
            sink.hint("这台机器没有上报任何数据。");
            return;
        }
        sink.hint(CtrlLink.letter(terminal.link) + " · " + link.label + " — " + all.size() + " 项");
        for (String key : all) {
            sink.out("  " + pad(key, 16) + " = " + sample.text(key));
        }
    }

    private static void keys(CtrlPanel panel, CtrlWidget terminal, Sink sink) {
        CtrlLink link = requireTarget(panel, terminal, sink);
        if (link == null) {
            return;
        }
        if (link.keys.isEmpty()) {
            sink.hint("这台机器没有上报可读数据。");
            return;
        }
        sink.out(join(link.keys));
    }

    private static void events(CtrlPanel panel, CtrlWidget terminal, Sink sink) {
        CtrlLink link = requireTarget(panel, terminal, sink);
        if (link == null) {
            return;
        }
        if (link.events.isEmpty()) {
            sink.hint("这台机器没有公布可接受的事件。");
            return;
        }
        sink.hint("事件 — 变量（没有变量的用 ev <事件> 直接发）");
        for (String event : link.events) {
            String var = link.vars.get(event);
            sink.out("  " + pad(event, 22) + (var == null || var.isEmpty() ? "" : " " + var));
        }
    }

    private static void fire(CtrlPanel panel, CtrlWidget terminal, World world, String[] args, Sink sink) {
        CtrlLink link = requireTarget(panel, terminal, sink);
        if (link == null) {
            return;
        }
        if (args.length < 2) {
            sink.err("用法：ev <事件> [变量=值]…");
            sink.hint("用 events 看这台机器接受哪些事件。");
            return;
        }
        String event = args[1];
        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            if (token.length() > MAX_ARG) {
                token = token.substring(0, MAX_ARG);
            }
            int eq = token.indexOf('=');
            if (eq < 0) {
                // A bare word is a flag: "turret_set_target passive" reads
                // better than "passive=1", and means the same thing.
                vars.put(token, Double.valueOf(1.0D));
                continue;
            }
            String var = token.substring(0, eq);
            String value = token.substring(eq + 1);
            if (var.isEmpty()) {
                sink.err("变量名是空的：" + clip(token));
                return;
            }
            vars.put(var, number(value));
        }
        if (!link.events.contains(event)) {
            sink.hint("注意：该机器没有公布 " + clip(event) + "，仍按原样发送。");
        }
        boolean sent = MachineBridge.fireAll(world, link.pos, event, vars);
        if (sent) {
            sink.ok("→ " + CtrlLink.letter(terminal.link) + " " + event
                    + (vars.isEmpty() ? "" : " " + describeVars(vars)));
        } else {
            sink.err("发送失败：" + event + "（机器已不在，或它拒绝了该事件）");
        }
    }

    private static void set(CtrlPanel panel, CtrlWidget terminal, World world, String[] args, Sink sink) {
        CtrlLink link = requireTarget(panel, terminal, sink);
        if (link == null) {
            return;
        }
        if (args.length < 3) {
            sink.err("用法：set <变量> <值>，例如 set level 40");
            String vars = knownVars(link);
            if (!vars.isEmpty()) {
                sink.hint("这台机器已知的变量：" + vars);
            }
            return;
        }
        String var = args[1];
        List<String> matches = new ArrayList<String>();
        for (Map.Entry<String, String> entry : link.vars.entrySet()) {
            if (var.equalsIgnoreCase(entry.getValue())) {
                matches.add(entry.getKey());
            }
        }
        if (matches.isEmpty()) {
            sink.err("不知道变量 " + clip(var) + " 属于哪个事件。");
            sink.hint("用 events 看事件与变量，或直接 ev <事件> " + clip(var) + "=<值>。");
            return;
        }
        if (matches.size() > 1) {
            sink.err("变量 " + clip(var) + " 对应多个事件：" + join(matches));
            sink.hint("请用 ev <事件> " + clip(var) + "=<值> 明确指定。");
            return;
        }
        String event = matches.get(0);
        String value = args[2];
        Map<String, Object> vars = new LinkedHashMap<String, Object>();
        vars.put(var, number(value));
        boolean sent = MachineBridge.fireAll(world, link.pos, event, vars);
        if (sent) {
            sink.ok("→ " + CtrlLink.letter(terminal.link) + " " + event + " " + var + "=" + clip(value));
        } else {
            sink.err("发送失败：" + event + "（机器已不在，或它拒绝了该事件）");
        }
    }

    // =================================================================== helpers

    private static CtrlLink requireTarget(CtrlPanel panel, CtrlWidget terminal, Sink sink) {
        CtrlLink link = target(panel, terminal);
        if (link == null) {
            sink.err("还没有选择目标机器。");
            sink.hint("用 ls 看已绑定的槽位，use A–F 选一个。");
            return null;
        }
        return link;
    }

    /** Splits on runs of whitespace; never returns an empty array for non-blank input. */
    public static String[] tokens(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        String[] parts = trimmed.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].length() > MAX_ARG) {
                parts[i] = parts[i].substring(0, MAX_ARG);
            }
        }
        return parts;
    }

    /** A–F (or 1–6) to 0..5, or -1. */
    public static int slotOf(String token) {
        if (token == null || token.isEmpty()) {
            return -1;
        }
        char first = token.charAt(0);
        if (first >= 'A' && first <= 'F') {
            return first - 'A';
        }
        if (first >= '0' && first <= '5' && token.length() == 1) {
            return first - '0';
        }
        return -1;
    }

    /**
     * A value as the machine wants it: a number whenever it looks like one,
     * otherwise a string. Kept in one place because "40" must not become the
     * string "40" - NTM's float variable would read that as zero.
     */
    private static Object number(String value) {
        String text = value == null ? "" : value.trim();
        if ("true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)) {
            return Double.valueOf(1.0D);
        }
        if ("false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)) {
            return Double.valueOf(0.0D);
        }
        try {
            return Double.valueOf(Double.parseDouble(text));
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static String describeVars(Map<String, Object> vars) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            if (text.length() > 0) {
                text.append(' ');
            }
            Object value = entry.getValue();
            text.append(entry.getKey()).append('=');
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                text.append(Math.abs(number - Math.rint(number)) < 1.0E-4D
                        ? String.valueOf((long) Math.rint(number))
                        : String.format(Locale.ROOT, "%.2f", number));
            } else {
                text.append(value);
            }
        }
        return text.toString();
    }

    private static String knownVars(CtrlLink link) {
        Set<String> vars = new LinkedHashSet<String>();
        for (String var : link.vars.values()) {
            if (!var.isEmpty()) {
                vars.add(var);
            }
        }
        return join(new ArrayList<String>(vars));
    }

    private static String join(List<String> parts) {
        StringBuilder text = new StringBuilder();
        for (String part : parts) {
            if (text.length() > 0) {
                text.append("  ");
            }
            text.append(part);
        }
        return text.toString();
    }

    /** Fixed-width column for the two-line readouts, so values line up. */
    private static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) {
            out.append(' ');
        }
        return out.toString();
    }

    /** Keeps a reflected string short enough to sit on one line. */
    private static String clip(String text) {
        String value = text == null ? "" : text;
        return value.length() > MAX_ARG ? value.substring(0, MAX_ARG) + "…" : value;
    }

    private static String alias(String command) {
        switch (command) {
            case "?":
                return "help";
            case "list":
            case "dir":
                return "ls";
            case "sel":
            case "select":
                return "use";
            case "get":
            case "cat":
                return "read";
            case "evs":
                return "events";
            case "fire":
                return "ev";
            case "cls":
                return "clear";
            default:
                return command;
        }
    }
}

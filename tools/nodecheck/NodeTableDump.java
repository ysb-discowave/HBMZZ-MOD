/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.  See LICENSE.
 */

package com.hbmzz.tools;

import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.nodes.addon.*;
import com.hbmzz.cecp.types.DataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dumps the addon node contract: for every node type the NBT loader knows, its
 * display name, colour and port layout.
 *
 * <p>A rewrite of the node classes must not change that contract — a saved graph
 * names its ports by index, so a shuffled port is a silently rewired machine.
 * There is no Minecraft here, so this is the only way to hold a rewrite to it:
 * dump the table, rewrite, dump again, compare.</p>
 *
 * <pre>
 *   javac -d tmp/nodecheck -cp build/classes/java/main tools/nodecheck/NodeTableDump.java
 *   java -cp "build/classes/java/main;tmp/nodecheck" com.hbmzz.tools.NodeTableDump \
 *        --check tools/nodecheck/node-table.baseline
 * </pre>
 */
public final class NodeTableDump {

    /** Mirrors {@code AddonNodeLoader}: the type string a saved graph carries, and how to build it. */
    private static Node build(String type) {
        switch (type) {
            case "sounder": return new NodeSounder(0, 0);
            case "leafia_pulse": return new NodePulse(0, 0);
            case "leafia_sr_latch": return new NodeSRLatch(0, 0);
            case "addString": return new NodeAddString(0, 0);
            case "subString": return new NodeSubString(0, 0);
            case "sipfx": return new NodeSIPfx(0, 0);
            case "format": return new NodeFormat(0, 0);
            case "leafia_cache": return new NodeCache(0, 0);
            case "leafia_clock": return new NodeClock(0, 0);
            case "leafia_parse_float": return new NodeParseFloat(0, 0);
            case "leafia_summarizer": return new NodeSummarizer(0, 0);
            default: return null;   // the three that need a world or a system
        }
    }

    private static final String[] TYPES = {
            "sounder", "leafia_pulse", "leafia_sr_latch", "addString", "subString", "sipfx",
            "format", "leafia_cache", "leafia_clock", "leafia_parse_float", "leafia_summarizer",
            "leafia_rng", "leafia_rng_d", "bulkQuery", "leafia_graph", "leafia_graph_bounds"};

    public static void main(String[] args) throws Exception {
        boolean check = args.length > 1 && "--check".equals(args[0]);
        String now = dump();
        if (!check) {
            java.nio.file.Files.write(java.nio.file.Paths.get(args.length > 0 ? args[0] : "node-table.txt"),
                    now.getBytes("UTF-8"));
            System.out.println("wrote " + now.split("\n").length + " 行");
            return;
        }
        String expected = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get(args[args.length - 1])), "UTF-8");
        if (expected.trim().equals(now.trim())) {
            System.out.println("OK  " + now.split("\n").length + " 行，与基准一致");
            return;
        }
        List<String> a = lines(expected);
        List<String> b = lines(now);
        int shown = 0;
        for (int i = 0; i < Math.max(a.size(), b.size()) && shown < 12; i++) {
            String x = i < a.size() ? a.get(i) : "<缺>";
            String y = i < b.size() ? b.get(i) : "<缺>";
            if (!x.equals(y)) {
                System.out.println("DIFF\n  基准 " + x + "\n  现在 " + y);
                shown++;
            }
        }
        System.out.println("FAIL 节点契约变了");
        System.exit(1);
    }

    private static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line.trim());
            }
        }
        return out;
    }

    private static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("# type | display | colour | spread | inputs | outputs\n");
        for (String type : TYPES) {
            Node node;
            try {
                node = build(type);
            } catch (Throwable t) {
                sb.append(type).append(" | NEEDS-CONTEXT ").append(t.getClass().getSimpleName()).append('\n');
                continue;
            }
            if (node == null) {
                sb.append(type).append(" | NEEDS-CONTEXT skipped\n");
                continue;
            }
            sb.append(type).append(" | ").append(node.getDisplayName());
            float[] c = node.getColor();
            sb.append(" | ").append(String.format(Locale.ROOT, "%d,%d,%d", (int) (c[0] * 255),
                    (int) (c[1] * 255), (int) (c[2] * 255)));
            sb.append(" | ").append(String.format(Locale.ROOT, "%.2f", node.size));
            sb.append(" | ").append(ports(node.inputs));
            sb.append(" | ").append(ports(node.outputs));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String ports(List<NodeConnection> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            NodeConnection c = list.get(i);
            if (i > 0) {
                sb.append(';');
            }
            DataValue d = c.defaultValue;
            sb.append(c.name).append(':').append(c.type == null ? "?" : c.type.name())
                    .append(':').append(d == null ? "null" : d.toString());
        }
        return sb.toString();
    }
}

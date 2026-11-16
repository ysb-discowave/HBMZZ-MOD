/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.  See LICENSE.
 */

package com.hbmzz.tools;

import com.hbmzz.client.ConsoleModel;
import com.hbmzz.client.ConsoleModel.Face;
import com.hbmzz.dfc.ConsoleRig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Proves that a change to {@link ConsoleModel} did not move the machine.
 *
 * <p>There is no Minecraft instance on this machine, so a renderer change cannot
 * be eyeballed. What <em>can</em> be checked is the geometry the renderer is
 * handed: build it for a sweep of panel sizes, tilts and mount types, and
 * compare. Vertices are facts (positions and colours); the code that produces
 * them is what a rewrite replaces.</p>
 *
 * <p>Two modes, because a full dump of the sweep is 17,000 lines (2 MB) and a
 * repository should not carry that:</p>
 *
 * <pre>
 *   # 120-line fingerprint of the whole sweep — this is what gets committed
 *   javac -d tmp/rigcheck -cp build/classes/java/main tools/rigcheck/GeometryDump.java
 *   java -cp "build/classes/java/main;tmp/rigcheck" com.hbmzz.tools.GeometryDump \
 *        --hash tools/rigcheck/geometry.digest
 *
 *   # compare against the committed fingerprint (exit 1 on any change)
 *   java -cp "build/classes/java/main;tmp/rigcheck" com.hbmzz.tools.GeometryDump \
 *        --hash --check tools/rigcheck/geometry.digest
 *
 *   # full vertex dump, for working out *what* moved
 *   java -cp "build/classes/java/main;tmp/rigcheck" com.hbmzz.tools.GeometryDump \
 *        --dump tmp/rigcheck/geometry.txt
 * </pre>
 *
 * <p>Faces are sorted before hashing: emitting the same solid in a different
 * order is not a behaviour change and must not fail the check.</p>
 */
public final class GeometryDump {

    /** Every panel size the pack ships, every tilt notch, both mounts, on/off. */
    private static final int[][] SIZES = {{1, 1}, {2, 1}, {2, 2}, {3, 2}, {4, 3}, {1, 3}};

    public static void main(String[] args) throws Exception {
        boolean check = false;
        boolean dump = false;
        boolean hash = false;
        List<String> positional = new ArrayList<>();
        for (String a : args) {
            if ("--check".equals(a)) {
                check = true;
            } else if ("--dump".equals(a)) {
                dump = true;
            } else if ("--hash".equals(a)) {
                hash = true;
            } else if (!a.startsWith("--")) {
                positional.add(a);
            }
        }
        if (!dump && !hash) {
            dump = true;
        }
        File target = positional.isEmpty() ? null : new File(positional.get(0));

        if (dump) {
            String text = fullDump();
            if (target == null) {
                System.out.print(text);
            } else {
                write(target, text);
                System.out.println("wrote " + target + " (" + text.split("\n").length + " 行)");
            }
            if (!hash) {
                return;
            }
        }

        String now = digestText();
        if (!check) {
            if (target == null) {
                System.out.print(now);
            } else {
                write(target, now);
                System.out.println("wrote " + target + " (" + now.split("\n").length + " 行指纹)");
            }
            return;
        }

        String expected = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        List<String> a = nonEmpty(expected);
        List<String> b = nonEmpty(now);
        if (a.equals(b)) {
            System.out.println("OK  " + b.size() + " 组参数，几何与基准一致");
            return;
        }
        int shown = 0;
        for (int i = 0; i < Math.max(a.size(), b.size()) && shown < 10; i++) {
            String x = i < a.size() ? a.get(i) : "<缺>";
            String y = i < b.size() ? b.get(i) : "<缺>";
            if (!x.equals(y)) {
                System.out.println("DIFF\n  基准 " + x + "\n  现在 " + y);
                shown++;
            }
        }
        System.out.println("FAIL 几何变了 —— 用 --dump 导出完整顶点，按组合参数定位");
        System.exit(1);
    }

    private static void write(File target, String text) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new java.io.IOException("cannot create " + parent);
        }
        Files.write(target.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> nonEmpty(String text) {
        List<String> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line.trim());
            }
        }
        Collections.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ sweep

    /** One line per parameter set: the parameters, then a fingerprint of its faces. */
    private static String digestText() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# cols rows tilt powered ceiling  sha256(faces)\n");
        for (int[] size : SIZES) {
            for (int tilt = 0; tilt < ConsoleRig.TILT_STEPS; tilt++) {
                for (int variant = 0; variant < 4; variant++) {
                    boolean powered = (variant & 1) != 0;
                    boolean ceiling = (variant & 2) != 0;
                    String head = size[0] + " " + size[1] + " " + tilt + " "
                            + powered + " " + ceiling;
                    sb.append(String.format(Locale.ROOT, "%-26s %s", head,
                            sha256(comboLines(size[0], size[1], tilt, powered, ceiling))));
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String fullDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("# cols rows tilt powered ceiling | argb x0 y0 z0 .. x3 y3 z3 (glass rows included)\n");
        for (int[] size : SIZES) {
            for (int tilt = 0; tilt < ConsoleRig.TILT_STEPS; tilt++) {
                for (int variant = 0; variant < 4; variant++) {
                    boolean powered = (variant & 1) != 0;
                    boolean ceiling = (variant & 2) != 0;
                    sb.append("## ").append(size[0]).append(' ').append(size[1]).append(' ')
                            .append(tilt).append(' ').append(powered).append(' ').append(ceiling).append('\n');
                    for (String line : comboLines(size[0], size[1], tilt, powered, ceiling)) {
                        sb.append(line).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Sorted face descriptions plus the glass corners for one parameter set. */
    private static List<String> comboLines(int cols, int rows, int tilt, boolean powered, boolean ceiling) {
        List<String> lines = new ArrayList<>();
        for (Face f : ConsoleModel.build(cols, rows, tilt, powered, ceiling)) {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "%08x", f.argb));
            for (int i = 0; i < 4; i++) {
                sb.append(String.format(Locale.ROOT, " %.6f %.6f %.6f", f.xs[i], f.ys[i], f.zs[i]));
            }
            lines.add(sb.toString());
        }
        // The glass is what the renderer textures; it is part of the contract
        // even though it is not a Face.
        for (float[] p : ConsoleModel.panelCorners(cols, rows, tilt)) {
            lines.add(String.format(Locale.ROOT, "glass %.6f %.6f %.6f", p[0], p[1], p[2]));
        }
        Collections.sort(lines);
        return lines;
    }

    private static String sha256(List<String> lines) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (String line : lines) {
            md.update(line.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString().substring(0, 16);
    }
}

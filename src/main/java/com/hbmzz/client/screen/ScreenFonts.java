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

package com.hbmzz.client.screen;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fonts used by the AWT-rendered monitor panel.
 *
 * <p>The panel is drawn with {@code Graphics2D} rather than the vanilla
 * {@code FontRenderer}, which buys real sub-pixel anti-aliasing, several
 * weights at once and true circle/arc drawing. The price is that font names
 * must be probed instead of assumed: a missing family silently falls back to
 * something ugly and never raises an error.</p>
 *
 * <p>Monospaced faces are used for every number so columns stay aligned; CJK
 * text needs its own family because almost no monospaced font ships Han
 * glyphs, and asking one for 温度 yields a row of boxes.</p>
 */
public final class ScreenFonts {

    private static final String[] MONO_CANDIDATES = {
            "Consolas", "DejaVu Sans Mono", "Liberation Mono", "Courier New", "Monospaced"
    };
    private static final String[] CJK_CANDIDATES = {
            "Microsoft YaHei", "\u5fae\u8f6f\u96c5\u9ed1", "SimHei", "\u9ed1\u4f53",
            "Noto Sans CJK SC", "Source Han Sans SC", "SimSun", "Dialog"
    };

    private static String monoFamily = "Monospaced";
    private static String cjkFamily = "Dialog";
    private static boolean probed;

    private static final Map<Integer, Font> MONO = new HashMap<Integer, Font>();
    private static final Map<Integer, Font> MONO_BOLD = new HashMap<Integer, Font>();
    private static final Map<Integer, Font> CJK = new HashMap<Integer, Font>();
    private static final Map<Integer, Font> CJK_BOLD = new HashMap<Integer, Font>();

    private ScreenFonts() {
    }

    private static void probe() {
        if (probed) {
            return;
        }
        probed = true;
        try {
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            monoFamily = first(names, MONO_CANDIDATES, "Monospaced", false);
            cjkFamily = first(names, CJK_CANDIDATES, "Dialog", true);
        } catch (Throwable ignored) {
            monoFamily = "Monospaced";
            cjkFamily = "Dialog";
        }
    }

    private static String first(String[] available, String[] candidates, String fallback, boolean needCjk) {
        for (String candidate : candidates) {
            for (String name : available) {
                if (!name.equalsIgnoreCase(candidate)) {
                    continue;
                }
                if (needCjk && !new Font(name, Font.PLAIN, 12).canDisplay('\u6838')) {
                    // "核" - if the face cannot draw it, it is not a CJK face.
                    continue;
                }
                return name;
            }
        }
        return fallback;
    }

    public static String describe() {
        probe();
        return "mono=" + monoFamily + " cjk=" + cjkFamily;
    }

    public static Font mono(int size) {
        probe();
        Font font = MONO.get(size);
        if (font == null) {
            font = new Font(monoFamily, Font.PLAIN, size);
            MONO.put(size, font);
        }
        return font;
    }

    public static Font monoBold(int size) {
        probe();
        Font font = MONO_BOLD.get(size);
        if (font == null) {
            font = new Font(monoFamily, Font.BOLD, size);
            MONO_BOLD.put(size, font);
        }
        return font;
    }

    /**
     * Regular weight is deliberate for small CJK labels: at 9-10 logical
     * pixels a bold Han glyph turns into a smear because the strokes fuse.
     */
    public static Font cjk(int size) {
        probe();
        Font font = CJK.get(size);
        if (font == null) {
            font = new Font(cjkFamily, Font.PLAIN, size);
            CJK.put(size, font);
        }
        return font;
    }

    public static Font cjkBold(int size) {
        probe();
        Font font = CJK_BOLD.get(size);
        if (font == null) {
            font = new Font(cjkFamily, Font.BOLD, size);
            CJK_BOLD.put(size, font);
        }
        return font;
    }

    /** True when the string contains anything outside Latin-1 (needs the CJK face). */
    public static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) >= 0x2E80) {
                return true;
            }
        }
        return false;
    }

    /**
     * The face for a string: the CJK one when it needs Han, monospaced otherwise.
     *
     * <p>This is the only sanctioned way to pick a font for text that might be
     * user data. Getting it wrong does not throw: AWT draws the face's
     * "notdef" glyph, so a Han character set in Consolas comes out as an empty
     * box and the string looks like a row of tofu. Every dynamic label in the
     * terminal goes through here for that reason - link tags are free text, and
     * an operator naming a machine "反应堆" is the normal case, not the edge.</p>
     */
    public static Font text(String value, int size) {
        return hasCjk(value) ? cjk(size) : mono(size);
    }

    /**
     * Bold variant. A "bold" request only means bold for Latin: at 9-10 logical
     * pixels a bold Han glyph fuses its strokes into a smear, which reads worse
     * than regular weight - so CJK falls back to the regular face.
     */
    public static Font textBold(String value, int size) {
        return hasCjk(value) ? cjk(size) : monoBold(size);
    }
}

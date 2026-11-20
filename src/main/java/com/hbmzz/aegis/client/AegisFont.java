/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis.client;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.HashMap;
import java.util.Map;

/**
 * Font provider for the offscreen canvas. Picks a monospace family for numerals
 * and a font that can actually render CJK for the Chinese labels, then caches
 * the derived {@link Font} objects by size and style.
 */
public final class AegisFont {

    private static final String[] MONO = {
            "Consolas", "DejaVu Sans Mono", "Liberation Mono", "Courier New", "Monospaced"
    };
    private static final String[] CJK = {
            "Microsoft YaHei", "\u5fae\u8f6f\u96c5\u9ed1", "SimHei", "\u9ed1\u4f53",
            "Noto Sans CJK SC", "Source Han Sans SC", "SimSun", "Dialog"
    };

    private static final Map<Long, Font> CACHE = new HashMap<Long, Font>();
    private static String monoFamily = "Monospaced";
    private static String cjkFamily = "Dialog";
    private static boolean ready;

    private AegisFont() {
    }

    private static synchronized void probe() {
        if (ready) {
            return;
        }
        ready = true;
        try {
            String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getAvailableFontFamilyNames();
            monoFamily = pick(available, MONO, "Monospaced", false);
            cjkFamily = pick(available, CJK, "Dialog", true);
        } catch (Throwable ignored) {
            monoFamily = "Monospaced";
            cjkFamily = "Dialog";
        }
    }

    private static String pick(String[] available, String[] wanted, String fallback, boolean cjk) {
        for (String name : wanted) {
            for (String have : available) {
                if (!have.equalsIgnoreCase(name)) {
                    continue;
                }
                if (cjk && !new Font(have, Font.PLAIN, 12).canDisplay('\u6838')) {
                    continue;
                }
                return have;
            }
        }
        return fallback;
    }

    /** True when the text contains a character outside basic Latin. */
    public static boolean needsCjk(String text) {
        if (text == null) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0xFF) {
                return true;
            }
        }
        return false;
    }

    public static Font get(String text, int size, boolean bold) {
        probe();
        boolean cjk = needsCjk(text);
        String family = cjk ? cjkFamily : monoFamily;
        long key = ((long) family.hashCode()) * 1000003L + size * 2L + (bold ? 1L : 0L);
        Font font = CACHE.get(key);
        if (font == null) {
            font = new Font(family, bold ? Font.BOLD : Font.PLAIN, size);
            CACHE.put(key, font);
        }
        return font;
    }

    public static String describe() {
        probe();
        return "mono=" + monoFamily + " cjk=" + cjkFamily;
    }
}

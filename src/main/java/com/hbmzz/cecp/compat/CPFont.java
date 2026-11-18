/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.compat;

import net.minecraft.client.gui.FontRenderer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Text helpers for the node graph.
 *
 * <p>The upstream control panel lays every label out in fixed, very narrow
 * boxes: 40 units for a node, 94 for a parameter row, 32 for a dropdown. Those
 * numbers are tuned to the ASCII font, where a character is 4-6 units wide. A
 * full-width Chinese glyph costs roughly 9, so a four-character label already
 * fills a node title and the five-character ones the port used to have ran
 * straight past the border.</p>
 *
 * <p>{@link #clip} measures the real string on the font that is actually
 * bound, so it works whether the caller left the unicode sheet on or off.</p>
 */
@SideOnly(Side.CLIENT)
public final class CPFont {

    private CPFont() {
    }

    /** Truncates {@code text} with a trailing {@code ..} until it fits {@code maxWidth}. */
    public static String clip(FontRenderer font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        String tail = "..";
        int room = maxWidth - font.getStringWidth(tail);
        if (room <= 0) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int w = font.getStringWidth(ch);
            if (width + w > room) {
                break;
            }
            out.append(ch);
            width += w;
        }
        return out.append(tail).toString();
    }

    /** Same as {@link #clip} but leaves off the ellipsis; used where it would look wrong. */
    public static String cut(FontRenderer font, String text, int maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (font.getStringWidth(text) <= maxWidth) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            int w = font.getStringWidth(ch);
            if (width + w > maxWidth) {
                break;
            }
            out.append(ch);
            width += w;
        }
        return out.toString();
    }
}

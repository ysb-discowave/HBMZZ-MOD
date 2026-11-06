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

package com.hbmzz.rbmk;

import java.util.ArrayList;
import java.util.List;

/**
 * The plate's contents, on the wire as one string.
 *
 * <p>A plate is a set of one-block lids arranged the way they sat on the
 * reactor. The client needs that arrangement before the first frame, and the
 * spawn packet is the only place it can come from - so it travels as a string
 * small enough to sit in an entity data parameter:</p>
 *
 * <pre>
 *   texture|texture|...#dx,dz,lid,textureIndex;dx,dz,lid,textureIndex;...
 * </pre>
 *
 * <p>Offsets are stored <b>doubled</b> because the plate is centred on its
 * reactor, which for any even-width core lands on a half block. Storing the
 * half-block offset as an integer keeps the whole layout integer-encoded and
 * still lets a five-wide plate be exactly centred; storing whole blocks would
 * bias every even-sized plate half a block to one side.</p>
 *
 * <p>Parsing never throws. A corrupt entry costs one lid; a corrupt plate
 * costs the plate. The alternative - an exception inside a render pass - costs
 * the frame, and a frame in a render loop is not something a player can
 * recover from.</p>
 */
public final class RbmkLayout {

    /** Most texture names one plate may carry. Six column types is already a lot. */
    private static final int MAX_TEXTURES = 8;

    private RbmkLayout() {
    }

    public static String encode(List<int[]> entries, List<String> textures) {
        StringBuilder out = new StringBuilder();
        List<String> names = textures == null ? new ArrayList<String>() : textures;
        for (int i = 0; i < names.size() && i < MAX_TEXTURES; i++) {
            if (i > 0) {
                out.append('|');
            }
            out.append(names.get(i));
        }
        out.append('#');
        if (entries == null) {
            return out.toString();
        }
        boolean first = true;
        for (int[] entry : entries) {
            if (entry == null || entry.length != 4) {
                continue;
            }
            if (!first) {
                out.append(';');
            }
            first = false;
            out.append(entry[0]).append(',').append(entry[1]).append(',')
                    .append(entry[2]).append(',').append(entry[3]);
        }
        return out.toString();
    }

    public static void decode(String layout, List<int[]> entries, List<String> textures) {
        if (layout == null || layout.isEmpty() || entries == null || textures == null) {
            return;
        }
        int split = layout.indexOf('#');
        if (split >= 0) {
            String head = layout.substring(0, split);
            if (!head.isEmpty()) {
                for (String name : head.split("\\|", -1)) {
                    textures.add(name);
                }
            }
        }
        if (split < 0) {
            return;
        }
        String tail = layout.substring(split + 1);
        if (tail.isEmpty()) {
            return;
        }
        for (String item : tail.split(";")) {
            String[] parts = item.split(",");
            if (parts.length != 4) {
                continue;
            }
            try {
                entries.add(new int[]{
                        Integer.parseInt(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3])});
            } catch (NumberFormatException ignored) {
                // One bad lid, not one bad plate.
            }
        }
    }

    /** Texture names of a layout, without decoding the lids. */
    public static List<String> texturesOf(String layout) {
        List<String> textures = new ArrayList<String>();
        int split = layout == null ? -1 : layout.indexOf('#');
        if (split <= 0) {
            return textures;
        }
        for (String name : layout.substring(0, split).split("\\|", -1)) {
            textures.add(name);
        }
        return textures;
    }
}

/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Lives in the package ported from NTM: Cursed Edition's
 * com.hbm.inventory.control_panel, and is built on its Control/ControlEvent
 * API - but this file itself is new work, not a port. See NOTICE.md.
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

package com.hbmzz.cecp;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A control's linked machines, in the order the terminal addresses them.
 *
 * <p>{@link Control#taggedLinks} is an insertion-ordered map keyed by whatever
 * tag the linker tab was given, and the linker rebuilds it from a plain
 * {@code HashMap} of positions - so two loads of the same panel can hand the
 * entries back in different orders. A terminal whose slot 2 is the reactor
 * today and the turbine tomorrow would be worse than useless, so the terminal
 * sorts: tag first, position as the tie-break. The saved target is an index
 * into this order, which makes it stable across reloads as long as nobody
 * renames a tag.</p>
 */
public final class TerminalLinks {

    private TerminalLinks() {
    }

    /** Tags, sorted, one per linked machine. */
    public static List<String> tagsOf(Control control) {
        List<String> tags = new ArrayList<String>();
        if (control == null) {
            return tags;
        }
        tags.addAll(control.taggedLinks.keySet());
        Collections.sort(tags, String.CASE_INSENSITIVE_ORDER);
        return tags;
    }

    /** Positions in the same order as {@link #tagsOf(Control)}. */
    public static List<BlockPos> positionsOf(Control control) {
        List<BlockPos> positions = new ArrayList<BlockPos>();
        if (control == null) {
            return positions;
        }
        for (String tag : tagsOf(control)) {
            BlockPos pos = control.taggedLinks.get(tag);
            // Always one position per tag: the two lists are indexed together,
            // and a gap here would silently shift every slot after it.
            positions.add(pos == null ? BlockPos.ORIGIN : pos);
        }
        return positions;
    }
}

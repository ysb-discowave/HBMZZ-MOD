/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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

package com.hbmzz.cecp.nodes.addon;

import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.types.DataValue.DataType;

/**
 * A port drawn eight pixels higher than its slot.
 *
 * <p>Straight from CE Addon's {@code NodeRetarded}. Nodes that mix text boxes
 * with ports (format, bulk query) end up with the first output sitting exactly
 * where a text box is drawn; nudging the port up is how upstream solves it.</p>
 */
public class NodeRetarded extends NodeConnection {

    public NodeRetarded(String name, com.hbmzz.cecp.nodes.Node p, int idx, boolean isInput,
                        DataType type, com.hbmzz.cecp.types.DataValue defaultVal) {
        super(name, p, idx, isInput, type, defaultVal);
    }

    @Override
    public void resetOffset() {
        super.resetOffset();
        this.offsetY -= 8.0F;
    }
}

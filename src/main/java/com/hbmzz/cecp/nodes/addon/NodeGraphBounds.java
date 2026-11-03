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

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.controls.addon.Graph;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Highest and lowest sample currently on the chart - lets a graph scale itself.
 *
 * <p>Ported from CE Addon's {@code NodeGraphBounds}.</p>
 */
public class NodeGraphBounds extends Node {

    public Control ctrl;

    public NodeGraphBounds(float x, float y, Control ctrl) {
        super(x, y);
        this.ctrl = ctrl;
        outputs.add(new NodeConnection("最大值", this, outputs.size(), false, DataType.NUMBER, new DataValueFloat(1)));
        outputs.add(new NodeConnection("最小值", this, outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        recalcSize();
    }

    @Override
    public DataValue evaluate(int i) {
        if (ctrl instanceof Graph) {
            Graph graph = (Graph) ctrl;
            Double max = null;
            Double min = null;
            for (double value : graph.values) {
                if (max == null || value > max) {
                    max = value;
                }
                if (min == null || value < min) {
                    min = value;
                }
            }
            if (max == null) {
                max = 0D;
            }
            if (min == null) {
                min = 0D;
            }
            if (i == 0) {
                return new DataValueFloat(max.floatValue());
            }
            if (i == 1) {
                return new DataValueFloat(min.floatValue());
            }
        }
        return new DataValueFloat(0);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorInput;
    }

    @Override
    public String getDisplayName() {
        return "图表区间";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_graph_bounds");
        return super.writeToNBT(tag, sys);
    }
}

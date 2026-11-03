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
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.controls.addon.Graph;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.NodeOutput;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/**
 * Feeds one sample per execution into the chart it belongs to.
 *
 * <p>Ported from CE Addon's {@code NodeGraphAdd}. Returns {@code false} like
 * upstream: this node only ever exists inside the chart's own receiver graph, so
 * "cancel the receiver" here means "do not broadcast to machines", which is
 * exactly right for a display.</p>
 */
public class NodeGraphAdd extends NodeOutput {

    public Control ctrl;

    public NodeGraphAdd(float x, float y, Control ctrl) {
        super(x, y);
        this.ctrl = ctrl;
        inputs.add(new NodeConnection("Value", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        recalcSize();
    }

    @Override
    public boolean doOutput(IControllable from, Map<String, NodeSystem> sendNodeMap, Map<String, BlockPos> links) {
        if (inputs.get(0) == null) {
            return false;
        }
        if (ctrl instanceof Graph) {
            ((Graph) ctrl).pushValue(inputs.get(0).evaluate().getNumber());
        }
        return false;
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorOutput;
    }

    @Override
    public String getDisplayName() {
        return "写入图表";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_graph");
        return super.writeToNBT(tag, sys);
    }
}

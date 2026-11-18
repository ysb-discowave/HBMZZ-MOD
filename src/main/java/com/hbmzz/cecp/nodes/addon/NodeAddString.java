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
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.nbt.NBTTagCompound;

/** Glues two pieces of text together, ported from CE Addon's {@code NodeAddString}. */
public class NodeAddString extends Node {

    public NodeAddString(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Output", this, this.outputs.size(), false, DataType.STRING, new DataValueString("")));
        this.inputs.add(new NodeConnection("Input 1", this, this.inputs.size(), true, DataType.STRING, new DataValueString("")));
        this.inputs.add(new NodeConnection("Input 2", this, this.inputs.size(), true, DataType.STRING, new DataValueString("")));
        this.evalCache = new DataValue[1];
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int idx) {
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        this.cacheValid = true;
        DataValue in1 = this.inputs.get(0).evaluate();
        DataValue in2 = this.inputs.get(1).evaluate();
        return in1 != null && in2 != null
                ? (this.evalCache[0] = new DataValueString(in1.toString() + in2.toString()))
                : null;
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorText;
    }

    @Override
    public String getDisplayName() {
        return "文本拼接";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "addString");
        return super.writeToNBT(tag, sys);
    }
}

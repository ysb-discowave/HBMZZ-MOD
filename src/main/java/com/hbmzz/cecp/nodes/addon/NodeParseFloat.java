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
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Text to number, with a second output that says whether the text was a number
 * at all.
 *
 * <p>Ported from CE Addon's {@code NodeParseFloat}. The error flag matters
 * because {@code Float.parseFloat} answers 0 for both "0" and "garbage", and a
 * graph that reacts to a level needs to tell those apart.</p>
 */
public class NodeParseFloat extends Node {

    public NodeParseFloat(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Output", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.outputs.add(new NodeConnection("Is Error", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Input", this, this.inputs.size(), true, DataType.STRING, new DataValueString("0")));
        this.recalcSize();
        this.evalCache = new DataValue[2];
    }

    @Override
    public DataValue evaluate(int i) {
        if (this.cacheValid) {
            return this.evalCache[i];
        }
        DataValue in = this.inputs.get(0).evaluate();
        if (in == null) {
            return null;
        }
        this.cacheValid = true;
        float value = 0;
        float error = 0;
        try {
            value = Float.parseFloat(in.toString());
        } catch (NumberFormatException ignored) {
            error = 1;
        }
        this.evalCache[0] = new DataValueFloat(value);
        this.evalCache[1] = new DataValueFloat(error);
        return this.evalCache[i];
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorUtility;
    }

    @Override
    public String getDisplayName() {
        return "文本转数字";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_parse_float");
        return super.writeToNBT(tag, sys);
    }
}

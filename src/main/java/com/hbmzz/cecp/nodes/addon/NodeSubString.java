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
 * A slice of text, ported from CE Addon's {@code NodeSubString}.
 *
 * <p>Positions are 1-based and may count backwards from the end (negative), so
 * {@code 1..-1} is the whole string and {@code -3..-1} is its last three
 * characters. A slice that runs off either end answers {@code ERROR} rather than
 * throwing inside the graph.</p>
 */
public class NodeSubString extends Node {

    public NodeSubString(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Output", this, this.outputs.size(), false, DataType.STRING, new DataValueString("")));
        this.inputs.add(new NodeConnection("Input", this, this.inputs.size(), true, DataType.STRING, new DataValueString("")));
        this.inputs.add(new NodeConnection("Start", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(1)));
        this.inputs.add(new NodeConnection("End", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(-1)));
        this.evalCache = new DataValue[1];
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int idx) {
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        this.cacheValid = true;
        DataValue in = this.inputs.get(0).evaluate();
        DataValue start0 = this.inputs.get(1).evaluate();
        DataValue end0 = this.inputs.get(2).evaluate();
        if (in == null || start0 == null || end0 == null) {
            return null;
        }
        String s = in.toString();
        int start = (int) start0.getNumber();
        int end = (int) end0.getNumber();
        if (start < 0) {
            start = s.length() - Math.abs(start);
        } else {
            start = Math.max(0, start - 1);
        }
        if (end < 0) {
            end = s.length() - Math.abs(end) + 1;
        }
        try {
            s = s.substring(start, end);
        } catch (IndexOutOfBoundsException e) {
            s = "ERROR";
        }
        return this.evalCache[0] = new DataValueString(s);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorText;
    }

    @Override
    public String getDisplayName() {
        return "取子串";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "subString");
        return super.writeToNBT(tag, sys);
    }
}

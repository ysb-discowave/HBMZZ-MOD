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

/** Number to shortened text: {@code 1500} becomes {@code 1.5k}. Ported from CE Addon's {@code NodeSIPfx}. */
public class NodeSIPfx extends Node {

    public NodeSIPfx(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Shortened", this, this.outputs.size(), false, DataType.STRING, new DataValueString("")));
        this.inputs.add(new NodeConnection("Input", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Show Full", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
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
        DataValue full = this.inputs.get(1).evaluate();
        return in != null && full != null
                ? (this.evalCache[0] = new DataValueString(SIPfx.auto(in.getNumber(), full.getBoolean())))
                : null;
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorText;
    }

    @Override
    public String getDisplayName() {
        return "数值缩写";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "sipfx");
        return super.writeToNBT(tag, sys);
    }
}

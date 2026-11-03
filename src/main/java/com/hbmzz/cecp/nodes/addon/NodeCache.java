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
import net.minecraft.nbt.NBTTagCompound;

/**
 * Holds one reading for the rest of the evaluation.
 *
 * <p>Ported from CE Addon's {@code NodeCache}. Same value as its input, but
 * evaluated once per pass, so a query that changes every call (a sensor, a
 * random) is read a single time no matter how many nodes downstream ask.</p>
 */
public class NodeCache extends Node {

    public NodeCache(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Output", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Input", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.evalCache = new DataValue[1];
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int i) {
        if (this.inputs.get(0) == null) {
            return null;
        }
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        DataValue value = this.inputs.get(0).evaluate();
        this.evalCache[0] = value;
        this.cacheValid = true;
        return value;
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorUtility;
    }

    @Override
    public String getDisplayName() {
        return "缓存";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_cache");
        return super.writeToNBT(tag, sys);
    }
}

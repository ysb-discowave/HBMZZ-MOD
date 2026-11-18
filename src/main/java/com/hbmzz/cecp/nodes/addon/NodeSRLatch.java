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

/** Set/reset latch, ported from CE Addon's {@code NodeSRLatch}. */
public class NodeSRLatch extends Node {

    boolean state = false;

    public NodeSRLatch(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Output", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Set", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Reset", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int i) {
        DataValue set = this.inputs.get(0).evaluate();
        DataValue reset = this.inputs.get(1).evaluate();
        if (set != null && set.getBoolean()) {
            this.state = true;
        }
        if (reset != null && reset.getBoolean()) {
            this.state = false;
        }
        return new DataValueFloat(this.state ? 1 : 0);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorBoolean;
    }

    @Override
    public String getDisplayName() {
        return "SR 锁存";
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        super.readFromNBT(tag, sys);
        this.state = tag.getBoolean("state");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_sr_latch");
        tag.setBoolean("state", this.state);
        return super.writeToNBT(tag, sys);
    }
}

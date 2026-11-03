/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM: Cursed Edition's com.hbm.inventory.control_panel;
 * see NOTICE.md for the list of changes.
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

package com.hbmzz.cecp.nodes;

import com.hbmzz.cecp.*;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;

public class NodeConditional extends Node {

    public NodeConditional(float x, float y) {
        super(x, y);

        this.inputs.add(new NodeConnection("启用", this, inputs.size(), true, DataValue.DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("输入", this, inputs.size(), true, DataValue.DataType.GENERIC, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("默认值", this, inputs.size(), true, DataValue.DataType.GENERIC, new DataValueFloat(0)));
        this.outputs.add(new NodeConnection("输出", this, outputs.size(), false, DataValue.DataType.GENERIC, new DataValueFloat(0)));
        evalCache = new DataValue[1];
        recalcSize();
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorLogic;
    }

    @Override
    public String getDisplayName() {
        return "条件判断";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "conditional");
        return super.writeToNBT(tag, sys);
    }

    @Override
    public DataValue evaluate(int idx) {
        if (cacheValid)
            return evalCache[0];
        cacheValid = true;

        DataValue enable = inputs.get(0).evaluate();
        DataValue in = inputs.get(1).evaluate();
        DataValue defaul = inputs.get(2).evaluate();
        if (enable == null || in == null || defaul == null)
            return null;

        if (enable.getBoolean()) {
            return evalCache[0] = in;
        }

        return evalCache[0] = defaul;
    }
}

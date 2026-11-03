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
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;

public class NodeBuffer extends Node {

    private int internalCount;

    public NodeBuffer(float x, float y) {
        super(x, y);
        this.inputs.add(new NodeConnection("输入", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("延迟", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.outputs.add(new NodeConnection("输出", this, outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        evalCache = new DataValue[1];
        recalcSize();
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorLogic;
    }

    @Override
    public String getDisplayName() {
        return "缓冲器";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "buffer");
        tag.setInteger("internalCount", this.internalCount);
        return super.writeToNBT(tag, sys);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        this.internalCount = tag.getInteger("internalCount");
        super.readFromNBT(tag, sys);
    }

    @Override
    public DataValue evaluate(int inx) {
        if (cacheValid)
            return evalCache[0];
        cacheValid = true;

        DataValue in = inputs.get(0).evaluate();
        DataValue delay = inputs.get(1).evaluate();
        if (in == null || delay == null)
            return null;
        int delayInt = Math.abs(Math.round(delay.getNumber()));

        if (in.getBoolean() || (internalCount != 0)) {
            if (internalCount == delayInt) {
                internalCount = 0;
                return evalCache[0] = in;
            }
            internalCount++;
        }

        return evalCache[0] = new DataValueFloat(0);
    }
}

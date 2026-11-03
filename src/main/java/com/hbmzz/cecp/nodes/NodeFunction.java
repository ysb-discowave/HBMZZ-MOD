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
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class NodeFunction extends Node {

    public NodeFunction(float x, float y) {
        super(x, y);

        this.inputs.add(new NodeConnection("启用", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.otherElements.add(new NodeButton("Edit Body", this, otherElements.size()) {
            @SideOnly(Side.CLIENT)
            @Override
            public void onClicked(SubElement subElement) {
                if (subElement instanceof SubElementNodeEditor) {
                    ((SubElementNodeEditor) subElement).descendSubsystem(this.parent);
                }
            }
        });
        this.recalcSize();

        evalCache = new DataValue[1];
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorLogic;
    }

    @Override
    public String getDisplayName() {
        return "函数";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "function");
        return super.writeToNBT(tag, sys);
    }

    @Override
    public DataValue evaluate(int idx) {
        if (cacheValid)
            return evalCache[0];
        cacheValid = true;

        DataValue enable = inputs.get(0).evaluate();
        if (enable == null)
            return null;

        return evalCache[0] = new DataValueFloat(enable.getBoolean());
    }

}

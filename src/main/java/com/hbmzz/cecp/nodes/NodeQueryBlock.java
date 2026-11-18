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
import com.hbmzz.cecp.compat.CPMachines;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class NodeQueryBlock extends Node {

    public Control ctrl;

    public String tag = "";
    public String dataName = "";
    public NodeDropdown tagSelector;
    public NodeDropdown dataSelector;

    public NodeQueryBlock(float x, float y, Control ctrl) {
        super(x, y);
        this.ctrl = ctrl;

        this.outputs.add(new NodeConnection("输出", this, outputs.size(), false, DataValue.DataType.GENERIC, new DataValueFloat(0)));

        tagSelector = new NodeDropdown(this, otherElements.size(),s -> {
            tag = s;
            dataName = "";
            setDataSelector();
            return null;
        }, () ->tag);
        this.otherElements.add(tagSelector);
        setBlockPosSelector();

        dataSelector = new NodeDropdown(this, otherElements.size(), s -> {
            dataName = s;
            this.outputs.get(0).type = evaluate(0).getType();
            return null;
        }, () -> dataName);
        setDataSelector();
        this.otherElements.add(dataSelector);

        dataName = "";
        recalcSize();
    }

    private BlockPos getPos(String pos) {
        String[] coords = pos.split(", ");
        return new BlockPos(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
    }

    private void setBlockPosSelector() {
        tagSelector.list.itemNames.clear();
        for (String s : ctrl.taggedLinks.keySet()) {
            tagSelector.list.addItems(s);
        }
    }

    private void setDataSelector() {
        dataSelector.list.itemNames.clear();
        if (tag == null || tag.isEmpty()) return;
        BlockPos pos = ctrl.taggedLinks.get(tag);
        World world = ctrl.panel.parent.getControlWorld();
        if (world == null || pos == null || !world.isBlockLoaded(pos)) return;
        // Through CPMachines, so an NTM machine linked into this control offers
        // its own keys instead of nothing.
        IControllable te = CPMachines.at(world, pos);
        if(te != null) {
            dataSelector.list.addItems(te.getQueryData().keySet());
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "queryBlock");
        tag.setString("blockPos",this.tag);
        tag.setString("dataName", dataName);

        return super.writeToNBT(tag, sys);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        this.tag = tag.getString("blockPos");
        dataName = tag.getString("dataName");
        setBlockPosSelector();
        setDataSelector();
        super.readFromNBT(tag, sys);
    }

    @Override
    public DataValue evaluate(int inx) {
        if (!dataName.isEmpty() && ctrl.taggedLinks.containsKey(tag)) {
            BlockPos pos = ctrl.taggedLinks.get(tag);
            World world = ctrl.panel.parent.getControlWorld();
            if(world != null && pos != null && world.isBlockLoaded(pos)) {
                IControllable te = CPMachines.at(world, pos);
                if(te != null) {
                    DataValue v = te.getQueryData().get(dataName);
                    if (v != null) return v;
                }
            }
            setDataSelector();
        }
        return new DataValueFloat(0);
    }

    public NodeQueryBlock setData(String blockPos) {
        this.tag = blockPos;
        return this;
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorInput;
    }

    @Override
    public String getDisplayName() {
        return "查询方块";
    }
}

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

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeDropdown;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.compat.CPMachines;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

/**
 * One figure over every linked machine at once: total, average, highest...
 *
 * <p>Ported from CE Addon's {@code NodeBulkQuery}. This is the node that makes
 * "how hot is the whole reactor" a single block instead of one query per rod:
 * it walks the control's linked positions, reads the same key from each, and
 * folds the results together.</p>
 *
 * <p>The lookup goes through {@link CPMachines} instead of an {@code instanceof}
 * check on this mod's own interface. Upstream's version only ever saw machines
 * from its own jar; here every linked block is resolved to a controllable
 * whichever mod owns it, which is also what makes the key list show an RBMK
 * rod's {@code level} rather than nothing.</p>
 */
public class NodeBulkQuery extends Node {

    public Control ctrl;
    public CombineMethod combineMethod = CombineMethod.ADD;
    public String dataName = "";
    public NodeDropdown dataSelector;

    public NodeBulkQuery(float x, float y, Control ctrl) {
        super(x, y);
        this.ctrl = ctrl;
        this.outputs.add(new NodeConnection("Output", this, this.outputs.size(), false, DataType.GENERIC, new DataValueFloat(0)));
        NodeDropdown combineSelector = new NodeDropdown(this, this.otherElements.size(), s -> {
            try {
                this.combineMethod = CombineMethod.valueOf(s);
            } catch (IllegalArgumentException ignored) {
            }
            return null;
        }, () -> this.combineMethod.name());
        this.otherElements.add(combineSelector);
        for (CombineMethod value : CombineMethod.values()) {
            combineSelector.list.addItems(value.name());
        }
        this.dataSelector = new NodeDropdown(this, this.otherElements.size(), s -> {
            this.dataName = s;
            this.outputs.get(0).type = this.evaluate(0).getType();
            return null;
        }, () -> this.dataName);
        this.setDataSelector();
        this.otherElements.add(this.dataSelector);
        this.dataName = "";
        this.recalcSize();
    }

    /** Fills the key dropdown from every numeric key the linked machines report. */
    private void setDataSelector() {
        this.dataSelector.list.itemNames.clear();
        World world = this.ctrl.panel.parent.getControlWorld();
        Set<String> alreadyAdded = new HashSet<>();
        for (BlockPos pos : this.ctrl.taggedLinks.values()) {
            IControllable te = CPMachines.at(world, pos);
            if (te == null) {
                continue;
            }
            for (Entry<String, DataValue> var : te.getQueryData().entrySet()) {
                if (!alreadyAdded.contains(var.getKey())) {
                    if (var.getValue() instanceof DataValueFloat) {
                        this.dataSelector.list.addItems(var.getKey());
                    }
                    alreadyAdded.add(var.getKey());
                }
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "bulkQuery");
        tag.setString("combineMethod", this.combineMethod.name());
        tag.setString("dataName", this.dataName);
        return super.writeToNBT(tag, sys);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        try {
            this.combineMethod = CombineMethod.valueOf(tag.getString("combineMethod"));
        } catch (IllegalArgumentException ignored) {
        }
        this.dataName = tag.getString("dataName");
        this.setDataSelector();
        super.readFromNBT(tag, sys);
    }

    @Override
    public DataValue evaluate(int inx) {
        if (this.dataName.isEmpty()) {
            return new DataValueFloat(0);
        }
        float value = this.combineMethod == CombineMethod.MULTIPLY ? 1 : 0;
        int total = 0;
        boolean firstTime = true;
        World world = this.ctrl.panel.parent.getControlWorld();
        for (BlockPos pos : this.ctrl.taggedLinks.values()) {
            IControllable te = CPMachines.at(world, pos);
            if (te == null) {
                continue;
            }
            DataValue data = te.getQueryData().get(this.dataName);
            if (!(data instanceof DataValueFloat)) {
                continue;
            }
            float curValue = data.getNumber();
            switch (this.combineMethod) {
                case ADD:
                    value += curValue;
                    break;
                case MULTIPLY:
                    value *= curValue;
                    break;
                case AVERAGE:
                    value += curValue;
                    total++;
                    break;
                case HIGHEST:
                    value = firstTime ? curValue : Math.max(curValue, value);
                    firstTime = false;
                    break;
                case LOWEST:
                    value = firstTime ? curValue : Math.min(curValue, value);
                    firstTime = false;
                    break;
            }
        }
        if (total > 0 && this.combineMethod == CombineMethod.AVERAGE) {
            value /= total;
        }
        return new DataValueFloat(value);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorInput;
    }

    @Override
    public String getDisplayName() {
        return "批量查询";
    }

    public enum CombineMethod {
        ADD, MULTIPLY, AVERAGE, HIGHEST, LOWEST
    }
}

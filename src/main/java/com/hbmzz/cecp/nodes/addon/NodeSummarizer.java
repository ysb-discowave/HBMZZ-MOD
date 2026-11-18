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
import com.hbmzz.cecp.NodeDropdown;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.MathHelper;

import java.util.Arrays;

/**
 * Keeps the last N values and reports one figure over them: sum, mean, min, max
 * or product.
 *
 * <p>Ported from CE Addon's {@code NodeSummarizer}. It is the only node that
 * remembers a window of history, which is what a moving average or a "highest in
 * the last 20 samples" alarm needs. {@code Trim Count} is resampled on every
 * pass, so shrinking the window drops the oldest slots and growing it starts
 * from zero rather than from stale data.</p>
 */
public class NodeSummarizer extends Node {

    public NodeBulkQuery.CombineMethod combineMethod = NodeBulkQuery.CombineMethod.AVERAGE;
    public float[] internalList = new float[40];
    public int needle = 0;

    public NodeSummarizer(float x, float y) {
        super(x, y);
        NodeDropdown combineSelector = new NodeDropdown(this, this.otherElements.size(), s -> {
            try {
                this.combineMethod = NodeBulkQuery.CombineMethod.valueOf(s);
            } catch (IllegalArgumentException ignored) {
            }
            return null;
        }, () -> this.combineMethod.name());
        this.otherElements.add(combineSelector);
        for (NodeBulkQuery.CombineMethod value : NodeBulkQuery.CombineMethod.values()) {
            combineSelector.list.addItems(value.name());
        }
        this.outputs.add(new NodeConnection("Summary", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Enable", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("New Value", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Clear", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Trim Count", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(20)));
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int unused) {
        if (this.inputs.get(0) == null || this.inputs.get(1) == null
                || this.inputs.get(2) == null || this.inputs.get(3) == null) {
            return null;
        }
        int length = (int) MathHelper.clamp(this.inputs.get(3).evaluate().getNumber(), 1.0F, 500.0F);
        if (length != this.internalList.length) {
            this.internalList = new float[length];
        }
        if (this.inputs.get(0).evaluate().getBoolean()) {
            if (this.needle >= this.internalList.length) {
                for (int i = 0; i < this.internalList.length - 1; i++) {
                    this.internalList[i] = this.internalList[i + 1];
                }
                this.internalList[this.internalList.length - 1] = this.inputs.get(1).evaluate().getNumber();
            } else {
                this.internalList[this.needle] = this.inputs.get(1).evaluate().getNumber();
                this.needle++;
            }
        }
        if (this.inputs.get(2).evaluate().getBoolean()) {
            Arrays.fill(this.internalList, 0);
            this.needle = 0;
        }
        float value = 0;
        int total = 0;
        boolean firstTime = true;
        for (float v : this.internalList) {
            boolean stopLoop = false;
            switch (this.combineMethod) {
                case ADD:
                    value += v;
                    break;
                case MULTIPLY:
                    value *= v;
                    break;
                case AVERAGE:
                    value += v;
                    if (++total >= this.needle - 1) {
                        stopLoop = true;
                    }
                    break;
                case HIGHEST:
                    value = firstTime ? v : Math.max(v, value);
                    firstTime = false;
                    break;
                case LOWEST:
                    value = firstTime ? v : Math.min(v, value);
                    firstTime = false;
                    break;
            }
            if (stopLoop) {
                break;
            }
        }
        if (total > 0 && this.combineMethod == NodeBulkQuery.CombineMethod.AVERAGE) {
            value /= total;
        }
        return new DataValueFloat(value);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorUtility;
    }

    @Override
    public String getDisplayName() {
        return "汇总";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_summarizer");
        tag.setString("combineMethod", this.combineMethod.name());
        NBTTagList list = new NBTTagList();
        for (float v : this.internalList) {
            list.appendTag(new NBTTagFloat(v));
        }
        tag.setTag("list", list);
        tag.setInteger("needle", this.needle);
        return super.writeToNBT(tag, sys);
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        try {
            this.combineMethod = NodeBulkQuery.CombineMethod.valueOf(tag.getString("combineMethod"));
        } catch (IllegalArgumentException ignored) {
        }
        if (tag.hasKey("list")) {
            NBTTagList list = tag.getTagList("list", 5);
            this.internalList = new float[list.tagCount()];
            for (int i = 0; i < this.internalList.length; i++) {
                this.internalList[i] = list.getFloatAt(i);
            }
        }
        this.needle = tag.getInteger("needle");
        super.readFromNBT(tag, sys);
    }
}

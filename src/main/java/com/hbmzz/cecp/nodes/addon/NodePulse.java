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

/**
 * Turns a level into a single tick of 1 on a rising (or falling) edge.
 *
 * <p>Ported from CE Addon's {@code NodePulse}. The latch it keeps lives in the
 * node, not in the graph, which is what makes "pressed once -> one pulse"
 * possible at all: the graph alone cannot tell a held button from a fresh
 * press.</p>
 */
public class NodePulse extends Node {

    public enum PulseMode {
        OFF_ON, ON_OFF, ALWAYS
    }

    boolean wasOn = false;
    PulseMode mode = PulseMode.OFF_ON;

    public NodePulse(float x, float y) {
        super(x, y);
        NodeDropdown modeSelector = new NodeDropdown(this, this.otherElements.size(), s -> {
            try {
                this.mode = PulseMode.valueOf(s.replace(" -> ", "_"));
            } catch (IllegalArgumentException ignored) {
            }
            return null;
        }, () -> this.mode.name().replace("_", " -> "));
        for (PulseMode value : PulseMode.values()) {
            modeSelector.list.addItems(value.name().replace("_", " -> "));
        }
        this.otherElements.add(modeSelector);
        this.outputs.add(new NodeConnection("Pulse", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Input", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(1)));
        this.evalCache = new DataValue[1];
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int i) {
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        DataValue d0 = this.inputs.get(0).evaluate();
        if (d0 == null) {
            return null;
        }
        this.cacheValid = true;
        boolean value = d0.getBoolean();
        float output = 0;
        if (value && !this.wasOn) {
            if (this.mode == PulseMode.ALWAYS || this.mode == PulseMode.OFF_ON) {
                output = 1;
            }
        } else if (!value && this.wasOn && (this.mode == PulseMode.ALWAYS || this.mode == PulseMode.ON_OFF)) {
            output = 1;
        }
        this.wasOn = value;
        return this.evalCache[0] = new DataValueFloat(output);
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorBoolean;
    }

    @Override
    public String getDisplayName() {
        return "脉冲";
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        super.readFromNBT(tag, sys);
        this.wasOn = tag.getBoolean("wasOn");
        try {
            this.mode = PulseMode.valueOf(tag.getString("mode"));
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_pulse");
        tag.setBoolean("wasOn", this.wasOn);
        tag.setString("mode", this.mode.name());
        return super.writeToNBT(tag, sys);
    }
}

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
import net.minecraft.world.World;

/**
 * Fractional random in [min, max).
 *
 * <p>Ported from CE Addon's {@code NodeRNGDecimal}. Unlike the integer version
 * this one stops short of max, because it scales {@code nextFloat()} by the
 * width of the range rather than picking an index.</p>
 */
public class NodeRNGDecimal extends Node {

    private final World world;

    public NodeRNGDecimal(float x, float y, World world) {
        super(x, y);
        this.world = world;
        this.outputs.add(new NodeConnection("Random", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Min", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Max", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(1)));
        this.recalcSize();
        this.evalCache = new DataValue[1];
    }

    @Override
    public DataValue evaluate(int i) {
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        DataValue min = this.inputs.get(0).evaluate();
        DataValue max = this.inputs.get(1).evaluate();
        if (min == null || max == null) {
            return null;
        }
        float value = Float.NaN;
        try {
            value = this.world.rand.nextFloat() * (max.getNumber() - min.getNumber()) + min.getNumber();
        } catch (Exception ignored) {
            // Sane ranges only; anything else stays NaN.
        }
        this.evalCache[0] = new DataValueFloat(value);
        return this.evalCache[0];
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorUtility;
    }

    @Override
    public String getDisplayName() {
        return "随机小数";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_rng_d");
        return super.writeToNBT(tag, sys);
    }
}

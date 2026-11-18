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

import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueComposite;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.nbt.NBTTagCompound;

public class NodeCompositeWrite extends Node {
	public NodeCompositeWrite(float x,float y) {
		super(x,y);
		outputs.add(new NodeConnection("修正信号",this,outputs.size(),false,DataType.COMPOSITE,new DataValueComposite()));
		inputs.add(new NodeConnection("启用",this,inputs.size(),true,DataType.NUMBER,new DataValueFloat(1)));
		inputs.add(new NodeConnection("键",this,inputs.size(),true,DataType.STRING,new DataValueString("")));
		inputs.add(new NodeConnection("值",this,inputs.size(),true,DataType.STRING,new DataValueString("")));
		inputs.add(new NodeConnection("基准信号",this,inputs.size(),true,DataType.COMPOSITE,new DataValueComposite()));
		recalcSize();
		evalCache = new DataValue[1];
	}
	@Override
	public DataValue evaluate(int idx) {
		if (cacheValid)
			return evalCache[0];
		DataValueComposite signal = null;
		if (inputs.get(3).evaluate() instanceof DataValueComposite)
			signal = (DataValueComposite)((DataValueComposite)inputs.get(3).evaluate()).copy();
		if (signal == null)
			signal = new DataValueComposite();
		DataValue enable = inputs.get(0).evaluate();
		DataValue key = inputs.get(1).evaluate();
		DataValue value = inputs.get(2).evaluate();
		if (enable != null && key != null && value != null) {
			if (enable.getBoolean())
				signal.setValueOf(key.toString(),value.toString());
		}
		evalCache[0] = signal;
		cacheValid = true;
		return signal;
	}
	@Override
	public float[] getColor() {
		return DataType.COMPOSITE.getColor();
	}
	@Override
	public String getDisplayName() {
		return "写数据包";
	}
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag,NodeSystem sys) {
		tag.setString("nodeType","composite_write");
		return super.writeToNBT(tag,sys);
	}
}

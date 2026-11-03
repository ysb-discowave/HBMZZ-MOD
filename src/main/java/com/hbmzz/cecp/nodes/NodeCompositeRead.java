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
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.nbt.NBTTagCompound;

public class NodeCompositeRead extends Node {
	public NodeCompositeRead(float x,float y) {
		super(x,y);
		outputs.add(new NodeConnection("值",this,outputs.size(),false,DataType.STRING,new DataValueString("")));
		inputs.add(new NodeConnection("信号",this,inputs.size(),true,DataType.COMPOSITE,new DataValueComposite()));
		inputs.add(new NodeConnection("键",this,inputs.size(),true,DataType.STRING,new DataValueString("")));
		recalcSize();
		evalCache = new DataValue[1];
	}
	@Override
	public DataValue evaluate(int idx) {
		if (cacheValid)
			return evalCache[0];
		DataValue signal = inputs.get(0).evaluate();
		DataValue key = inputs.get(1).evaluate();
		if (key != null && signal != null) {
			evalCache[0] = new DataValueString(signal.getValueOf(key.toString()));
		} else
			evalCache[0] = new DataValueString("ERROR");
		cacheValid = true;
		return evalCache[0];
	}
	@Override
	public float[] getColor() {
		return DataType.COMPOSITE.getColor();
	}
	@Override
	public String getDisplayName() {
		return "读数据包";
	}
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag,NodeSystem sys) {
		tag.setString("nodeType","composite_read");
		return super.writeToNBT(tag,sys);
	}
}

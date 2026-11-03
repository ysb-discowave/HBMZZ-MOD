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

package com.hbmzz.cecp;

import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueComposite;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.nbt.NBTTagCompound;

public class UnknownNode extends Node {
	NBTTagCompound tagBase;
	public UnknownNode(float x,float y,NBTTagCompound tag) {
		super(x,y);
		tagBase = tag;
		NBTTagCompound inputs = tag.getCompoundTag("in");
		for(int i = 0; i < inputs.getKeySet().size(); i ++){
			NodeConnection c = new NodeConnection(null, this, 0, false, null, new DataValueFloat(0));
			c.buildFromNBT(inputs.getCompoundTag("con"+i));
			this.inputs.add(c);
		}
		NBTTagCompound outputs = tag.getCompoundTag("out");
		for(int i = 0; i < outputs.getKeySet().size(); i ++){
			NodeConnection c = new NodeConnection(null, this, 0, false, null, new DataValueFloat(0));
			c.buildFromNBT(outputs.getCompoundTag("con"+i));
			this.outputs.add(c);
		}
		recalcSize();
	}
	@Override
	public DataValue evaluate(int idx) {
		DataValue.DataType type = outputs.get(idx).type;
		switch(type) {
			case ENUM:
				throw new UnsupportedOperationException("Control panel contains unresolved node type!");
			case COMPOSITE:
				return new DataValueComposite();
			case NUMBER:
				return new DataValueFloat(Float.NaN);
			case STRING:
			case GENERIC:
				return new DataValueString("ERROR");
			default:
				return null;
		}
	}
	@Override
	public float[] getColor() {
		// Magenta, same as the missing-control placeholder on the board, so a node
		// this build does not know reads as "missing" instead of "broken white".
		return new float[]{0.85F, 0.1F, 0.85F};
	}
	@Override
	public String getDisplayName() {
		return "未知节点";
	}
	@Override
	public void readFromNBT(NBTTagCompound tag,NodeSystem sys) {
		super.readFromNBT(tag,sys);
	}
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag,NodeSystem sys) {
		for (String s : tagBase.getKeySet())
			tag.setTag(s,tagBase.getTag(s));
		return super.writeToNBT(tag,sys);
	}
}

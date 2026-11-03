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

import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.modular.StockNodesRegister;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

public class NodeInput extends Node {

	public static final String EVENT_DATA = "Event Data";

	public String name;
	
	public NodeInput(float x, float y, String name){
		super(x, y);
		this.name = name;
	}

	@Override
	public DataValue evaluate(int idx){
		return outputs.get(idx).defaultValue;
	}
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		tag.setString("nodeType", "input");
		tag.setString("name", name);
		return super.writeToNBT(tag, sys);
	}

	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		this.name = tag.getString("name");
		super.readFromNBT(tag, sys);
	}

	@Override
	public float[] getColor() {
		return StockNodesRegister.colorInput;
	}
	
	@Override
	public String getDisplayName(){
		// "name" is the protocol key - NodeSystem.canPasteFromNBT compares it against
		// "Event Data", and it is what gets written to NBT - so it stays English here
		// and only the caption is translated.
		return EVENT_DATA.equals(name) ? "事件数据" : name;
	}
	
	public NodeInput setVars(Map<String, DataValue> vars){
		outputs.clear();
		for(Entry<String, DataValue> e : vars.entrySet()){
			NodeConnection c = new NodeConnection(e.getKey(), this, outputs.size(), false, e.getValue().getType(), e.getValue().copy());
			outputs.add(c);
		}
		this.recalcSize();
		return this;
	}
	
	public NodeInput setOutputFromVars(Map<String, DataValue> vars){
		for(NodeConnection c : outputs){
			if (Objects.equals(c.name, "from index")) {
				c.enumSelector = null;
				continue;
			}
			// An event whose definition lists a variable it no longer ships would
			// hand null to setDefault and take the whole receive path down with an
			// NPE; keep the port's current default instead.
			DataValue value = vars.get(c.name);
			if (value != null) {
				c.setDefault(value);
			}
		}
		return this;
	}
}

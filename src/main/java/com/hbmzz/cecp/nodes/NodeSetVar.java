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
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public class NodeSetVar extends NodeOutput {

	public Control ctrl;
	public boolean global = false;
	public NodeDropdown varSelector;
	public String varName;
	
	public NodeSetVar(float x, float y, Control ctrl){
		super(x, y);
		this.ctrl = ctrl;
		this.inputs.add(new NodeConnection("输入", this, 0, true, DataType.GENERIC, new DataValueFloat(0)));
		NodeDropdown globalSelector = new NodeDropdown(this, otherElements.size(), s -> {
			if(s.equals("全局") && !global){
				global = true;
				varName = "";
			}
			if(s.equals("局部") && global){
				global = false;
				varName = "";
			}
			setVarSelector();
			return null;
		}, () -> (global ? "全局" : "局部"));
		globalSelector.list.addItems("全局", "局部");
		this.otherElements.add(globalSelector);
		
		varSelector = new NodeDropdown(this, otherElements.size(), s -> {
			varName = s;
			DataValue val = global ? ctrl.getGlobalVar(s) : ctrl.getVar(s);
			this.inputs.get(0).type = val.getType();
			this.inputs.get(0).setDefault(val);
			return null;
		}, () -> varName);
		setVarSelector();
		this.otherElements.add(varSelector);
		
		varName = "";
		recalcSize();
	}
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		tag.setString("nodeType", "setVar");
		tag.setBoolean("global", global);
		tag.setString("varName", varName);
		return super.writeToNBT(tag, sys);
	}
	
	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		global = tag.getBoolean("global");
		varName = tag.getString("varName");
		super.readFromNBT(tag, sys);
		refreshVariableReference();
	}

	public void setVarSelector(){
		varSelector.list.itemNames.clear();
		if(global){
			for(String name : ctrl.panel.globalVars.keySet()){
				varSelector.list.addItems(name);
			}
		} else {
			for(String name : ctrl.vars.keySet()){
				varSelector.list.addItems(name);
			}
		}
	}
	
	@Override
	public boolean doOutput(IControllable from, Map<String, NodeSystem> sendNodeMap, Map<String,BlockPos> positions){
		if(varName.isEmpty())
			return true;
		if(global){
			ctrl.panel.globalVars.put(varName, inputs.get(0).evaluate());
		} else {
			ctrl.vars.put(varName, inputs.get(0).evaluate());
		}
		return true;
	}

	public NodeSetVar setData(String varName, boolean isGlobal) {
		this.varName = varName;
		this.global = isGlobal;
		refreshVariableReference();
		return this;
	}

	private void refreshVariableReference() {
		setVarSelector();
		if(varName.isEmpty()) {
			return;
		}
		DataValue val = global ? ctrl.panel.globalVars.get(varName) : ctrl.vars.get(varName);
		if(val == null) {
			return;
		}
		this.inputs.get(0).type = val.getType();
		this.inputs.get(0).setDefault(val);
	}


	@Override
	public float[] getColor() {
		return StockNodesRegister.colorOutput;
	}

	@Override
	public String getDisplayName(){
		return "设置变量";
	}

}

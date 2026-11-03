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

public class NodeGetVar extends Node {

	public Control ctrl;
	public boolean global = false;
	public String varName;
	public NodeDropdown varSelector;
	
	public NodeGetVar(float x, float y, Control ctrl){
		super(x, y);
		this.ctrl = ctrl;
		this.outputs.add(new NodeConnection("输出", this, outputs.size(), false, DataType.GENERIC, new DataValueFloat(0)));
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
			this.outputs.get(0).type = evaluate(0).getType();
			return null;
		}, () -> varName);
		setVarSelector();
		this.otherElements.add(varSelector);
		varName = "";
		recalcSize();
	}
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		tag.setString("nodeType", "getVar");
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
	public DataValue evaluate(int idx){
		if(varName.isEmpty())
			return new DataValueFloat(0);
		if(global){
			return ctrl.getGlobalVar(varName);
		} else {
			return ctrl.getVar(varName);
		}
	}

	public NodeGetVar setData(String varName, boolean isGlobal) {
		this.varName = varName;
		this.global = isGlobal;
		refreshVariableReference();
		return this;
	}

	private void refreshVariableReference() {
		setVarSelector();
		if(!varName.isEmpty()) {
			this.outputs.get(0).type = evaluate(0).getType();
		}
	}

	@Override
	public float[] getColor() {
		return StockNodesRegister.colorInput;
	}

	@Override
	public String getDisplayName(){
		return "读取变量";
	}

}

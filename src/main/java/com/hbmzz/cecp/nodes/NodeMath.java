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
import net.minecraft.util.math.MathHelper;

public class NodeMath extends Node {

	public Operation op = Operation.ADD;
	
	public NodeMath(float x, float y){
		super(x, y);
		this.outputs.add(new NodeConnection("输出", this, outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
		NodeDropdown opSelector = new NodeDropdown(this, otherElements.size(), s -> {
			Operation op = Operation.getByName(s);
			if(op != null){
				setOperation(op);
			}
			return null;
		}, () -> op.name);
		for(Operation op : Operation.VALUES){
			opSelector.list.addItems(op.name);
		}
		this.otherElements.add(opSelector);
		setOperation(Operation.ADD);
		evalCache = new DataValue[1];
	}
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		tag.setString("nodeType", "math");
		tag.setInteger("op", op.ordinal());
		return super.writeToNBT(tag, sys);
	}
	
	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		op = Operation.VALUES[tag.getInteger("op")%Operation.VALUES.length];
		super.readFromNBT(tag, sys);
	}
	
	@Override
	public DataValue evaluate(int idx){
		if(cacheValid)
			return evalCache[0];
		cacheValid = true;
		DataValue[] evals = new DataValue[inputs.size()];
		for(int i = 0; i < evals.length; i ++){
			evals[i] = inputs.get(i).evaluate();
			if(evals[i] == null)
				return null;
		}
		switch(op) {
			case ADD:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber()+evals[1].getNumber());
			case SUB:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber()-evals[1].getNumber());
			case MULT:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber()*evals[1].getNumber());
			case DIV:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber()/evals[1].getNumber());
			case MOD:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber()%evals[1].getNumber());
			case POW:
				return evalCache[0] = new DataValueFloat((float)Math.pow(evals[0].getNumber(), evals[1].getNumber()));
			case LOG:
				return evalCache[0] = new DataValueFloat((float)(Math.log(evals[0].getNumber())/Math.log(evals[1].getNumber())));
			case EXP:
				return evalCache[0] = new DataValueFloat((float)Math.exp(evals[0].getNumber()));
			case SQRT:
				return evalCache[0] = new DataValueFloat((float)Math.sqrt(evals[0].getNumber()));
			case ABS:
				return evalCache[0] = new DataValueFloat(Math.abs(evals[0].getNumber()));
			case EQUAL:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber() == evals[1].getNumber() ? 1 : 0);
			case GREATER:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber() > evals[1].getNumber() ? 1 : 0);
			case LESS:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber() < evals[1].getNumber() ? 1 : 0);
			case GEQUAL:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber() >= evals[1].getNumber() ? 1 : 0);
			case LEQUAL:
				return evalCache[0] = new DataValueFloat(evals[0].getNumber() <= evals[1].getNumber() ? 1 : 0);
			case CLAMP:
				return evalCache[0] = new DataValueFloat(MathHelper.clamp(evals[0].getNumber(), evals[1].getNumber(), evals[2].getNumber()));
			case MAX:
				return evalCache[0] = new DataValueFloat(Math.max(evals[0].getNumber(),evals[1].getNumber()));
			case MIN:
				return evalCache[0] = new DataValueFloat(Math.min(evals[0].getNumber(),evals[1].getNumber()));
			case FLOOR:
				return evalCache[0] = new DataValueFloat((float)Math.floor(evals[0].getNumber()));
			case CEIL:
				return evalCache[0] = new DataValueFloat((float)Math.ceil(evals[0].getNumber()));
			case ROUND:
				return evalCache[0] = new DataValueFloat((float)Math.round(evals[0].getNumber()));
		}
		return evalCache[0] = null;
	}

	public NodeMath setData(Operation op) {
		setOperation(op);
		return this;
	}

	public void setOperation(Operation op){
		this.op = op;
		for(NodeConnection c : inputs){
			c.removeConnection();
		}
		this.inputs.clear();
		String s1 = "Input 1";
		String s2 = "Input 2";
		switch(op) {
			case EQUAL:
			case GREATER:
			case LESS:
			case GEQUAL:
			case LEQUAL:
			case ADD:
			case SUB:
			case MULT:
			case DIV:
			case MOD:
			case POW:
			case MIN:
			case MAX:
			case LOG:
				if(op == Operation.POW){
					s1 = "Base";
					s2 = "指数";
				} else if(op == Operation.LOG){
					s1 = "Value";
					s2 = "Base";
				}
				inputs.add(new NodeConnection(s1, this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				inputs.add(new NodeConnection(s2, this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				break;
			case EXP:
			case SQRT:
			case ABS:
				inputs.add(new NodeConnection("输入", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				break;
			case FLOOR:
			case CEIL:
			case ROUND:
				inputs.add(new NodeConnection("输入", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				break;
			case CLAMP:
				inputs.add(new NodeConnection("值", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				inputs.add(new NodeConnection("最小", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				inputs.add(new NodeConnection("最大", this, inputs.size(), true, DataType.NUMBER, new DataValueFloat(0)));
				break;
		}
		recalcSize();
	}

	@Override
	public float[] getColor() {
		return StockNodesRegister.colorMath;
	}
	
	@Override
	public String getDisplayName(){
		return op.name;
	}
	
	public static enum Operation {
		ADD("加"),
		SUB("减"),
		MULT("乘"),
		DIV("除"),
		MOD("取余"),
		POW("幂"),
		LOG("对数"),
		EXP("指数"),
		SQRT("平方根"),
		ABS("绝对值"),
		EQUAL("等于"),
		GREATER("大于"),
		LESS("小于"),
		GEQUAL("大于等于"),
		LEQUAL("小于等于"),
		CLAMP("限幅"),
		MAX("最大"),
		MIN("最小"),
		FLOOR("向下取整"),
		CEIL("向上取整"),
		ROUND("四舍五入");

        public static final Operation[] VALUES = values();

		public String name;
		private Operation(String name){
			this.name = name;
		}
		
		public static Operation getByName(String name){
			for(Operation o : Operation.VALUES){
				if(o.name.equals(name)){
					return o;
				}
			}
			return null;
		}
	}

}

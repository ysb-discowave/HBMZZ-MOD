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
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeDropdown;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

public class NodeRedstoneInput extends Node {

	private final Control ctrl;
	private EnumFacing facing = EnumFacing.NORTH;
	private ReadMode mode = ReadMode.POWER;

	public NodeRedstoneInput(float x, float y, Control ctrl) {
		super(x, y);
		this.ctrl = ctrl;
		this.outputs.add(new NodeConnection("输出", this, outputs.size(), false, DataValue.DataType.NUMBER, new DataValueFloat(0)));

		NodeDropdown faceSelector = new NodeDropdown(this, otherElements.size(), s -> {
			for(EnumFacing candidate : EnumFacing.VALUES) {
				if(candidate.getName().equalsIgnoreCase(s)) {
					facing = candidate;
					cacheValid = false;
					break;
				}
			}
			return null;
		}, () -> facing.getName());
		for(EnumFacing candidate : EnumFacing.VALUES) {
			faceSelector.list.addItems(candidate.getName());
		}
		this.otherElements.add(faceSelector);

		NodeDropdown modeSelector = new NodeDropdown(this, otherElements.size(), s -> {
			for(ReadMode candidate : ReadMode.values()) {
				if(candidate.label.equals(s)) {
					mode = candidate;
					cacheValid = false;
					break;
				}
			}
			return null;
		}, () -> mode.label);
		for(ReadMode candidate : ReadMode.values()) {
			modeSelector.list.addItems(candidate.label);
		}
		this.otherElements.add(modeSelector);

		evalCache = new DataValue[1];
		recalcSize();
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
		tag.setString("nodeType", "redstoneInput");
		tag.setInteger("facing", facing.getIndex());
		tag.setInteger("mode", mode.ordinal());
		return super.writeToNBT(tag, sys);
	}

	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
		facing = EnumFacing.getFront(tag.getInteger("facing"));
		mode = ReadMode.values()[tag.getInteger("mode") % ReadMode.values().length];
		super.readFromNBT(tag, sys);
	}

	@Override
	public DataValue evaluate(int idx) {
		if(cacheValid) {
			return evalCache[0];
		}
		cacheValid = true;
		if(!(ctrl.panel.parent instanceof TileEntityCtrlConsole)) {
			return evalCache[0] = new DataValueFloat(0);
		}
		TileEntityCtrlConsole panel = (TileEntityCtrlConsole) ctrl.panel.parent;

		switch(mode) {
			case POWER:
				return evalCache[0] = new DataValueFloat(panel.getRedstoneInputPower(facing));
			case WEAK:
				return evalCache[0] = new DataValueFloat(panel.getRedstoneInputWeak(facing));
			case STRONG:
				return evalCache[0] = new DataValueFloat(panel.getRedstoneInputStrong(facing));
			case IS_POWERED:
				return evalCache[0] = new DataValueFloat(panel.getRedstoneInputPower(facing) > 0);
			case IS_WEAKLY_POWERED:
				return evalCache[0] = new DataValueFloat(panel.getRedstoneInputWeak(facing) > 0);
			case IS_STRONGLY_POWERED:
				return evalCache[0] = new DataValueFloat(panel.getRedstoneInputStrong(facing) > 0);
			default:
				return evalCache[0] = new DataValueFloat(0);
		}
	}

	@Override
	public float[] getColor() {
		return StockNodesRegister.colorInput;
	}

	@Override
	public String getDisplayName() {
		return "红石输入";
	}

	private enum ReadMode {
		POWER("红石强度"),
		WEAK("弱充能值"),
		STRONG("强充能值"),
		IS_POWERED("是否通电"),
		IS_WEAKLY_POWERED("是否弱充能"),
		IS_STRONGLY_POWERED("是否强充能");

		private final String label;

		ReadMode(String label) {
			this.label = label;
		}
	}
}

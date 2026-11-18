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
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeDropdown;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public class NodeRedstoneOutput extends NodeOutput {

	private EnumFacing facing = EnumFacing.NORTH;
	private OutputMode mode = OutputMode.WEAK;

	public NodeRedstoneOutput(float x, float y) {
		super(x, y);
		this.inputs.add(new NodeConnection("强度", this, inputs.size(), true, DataValue.DataType.NUMBER, new DataValueFloat(0)));

		NodeDropdown faceSelector = new NodeDropdown(this, otherElements.size(), s -> {
			for(EnumFacing candidate : EnumFacing.VALUES) {
				if(candidate.getName().equalsIgnoreCase(s)) {
					facing = candidate;
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
			for(OutputMode candidate : OutputMode.values()) {
				if(candidate.label.equals(s)) {
					mode = candidate;
					break;
				}
			}
			return null;
		}, () -> mode.label);
		for(OutputMode candidate : OutputMode.values()) {
			modeSelector.list.addItems(candidate.label);
		}
		this.otherElements.add(modeSelector);

		recalcSize();
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
		tag.setString("nodeType", "redstoneOutput");
		tag.setInteger("facing", facing.getIndex());
		tag.setInteger("mode", mode.ordinal());
		return super.writeToNBT(tag, sys);
	}

	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
		facing = EnumFacing.getFront(tag.getInteger("facing"));
		mode = OutputMode.values()[tag.getInteger("mode") % OutputMode.values().length];
		super.readFromNBT(tag, sys);
	}

	@Override
	public boolean doOutput(IControllable from, Map<String, NodeSystem> sendNodeMap, Map<String,BlockPos> positions) {
		if(from instanceof TileEntityCtrlConsole) { TileEntityCtrlConsole panel = (TileEntityCtrlConsole) from;
			int strength = Math.round(inputs.get(0).evaluate().getNumber());
			if(mode == OutputMode.WEAK) {
				panel.setWeakRedstoneOutput(facing, strength);
			} else {
				panel.setStrongRedstoneOutput(facing, strength);
			}
		}
		return true;
	}

	@Override
	public float[] getColor() {
		return StockNodesRegister.colorOutput;
	}

	@Override
	public String getDisplayName() {
		return "红石输出";
	}

	private enum OutputMode {
		WEAK("弱充能"),
		STRONG("强充能");

		private final String label;

		OutputMode(String label) {
			this.label = label;
		}
	}
}

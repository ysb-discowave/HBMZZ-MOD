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

package com.hbmzz.cecp.modular;

import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.nodes.*;
import net.minecraft.nbt.NBTTagCompound;

import java.util.ArrayList;
import java.util.List;

public class StockNodeLoader implements INodeLoader {
	@Override
	public Node nodeFromNBT(NBTTagCompound tag,NodeSystem sys) {
		Node node = null;
		switch(tag.getString("nodeType")){
			case "cancelEvent":
				node = new NodeCancelEvent(0, 0);
				break;
			case "eventBroadcast":
				NBTTagCompound list = tag.getCompoundTag("itemList");
				List<ControlEvent> l = new ArrayList<>();
				for(int i = 0; i < list.getKeySet().size(); i ++){
					l.add(ControlEvent.getRegisteredEvent(list.getString("item"+i)));
				}
				node = new NodeEventBroadcast(0, 0, l);
				break;
			case "getVar":
				node = new NodeGetVar(0, 0, sys.parent);
				break;
			case "queryBlock":
				node = new NodeQueryBlock(0, 0, sys.parent);
				break;
			case "redstoneInput":
				node = new NodeRedstoneInput(0, 0, sys.parent);
				break;
			case "math":
				node = new NodeMath(0, 0);
				break;
			case "boolean":
				node = new NodeBoolean(0, 0);
				break;
			case "function":
				node = new NodeFunction(0, 0);
				break;
			case "buffer":
				node = new NodeBuffer(0, 0);
				break;
			case "conditional":
				node = new NodeConditional(0, 0);
				break;
			case "setVar":
				node = new NodeSetVar(0, 0, sys.parent);
				break;
			case "redstoneOutput":
				node = new NodeRedstoneOutput(0, 0);
				break;
			case "input":
				node = new NodeInput(0, 0, "");
				break;
			case "composite_read":
				node = new NodeCompositeRead(0,0);
				break;
			case "composite_write":
				node = new NodeCompositeWrite(0,0);
				break;
		}
		return node;
	}
}

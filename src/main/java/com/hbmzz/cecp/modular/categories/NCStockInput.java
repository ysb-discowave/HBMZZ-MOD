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

package com.hbmzz.cecp.modular.categories;

import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.cecp.ItemList;
import com.hbmzz.cecp.SubElementNodeEditor;
import com.hbmzz.cecp.modular.INodeMenuCreator;
import com.hbmzz.cecp.nodes.*;

import java.util.LinkedHashMap;
import java.util.Map;

public class NCStockInput implements INodeMenuCreator {
	@Override
	public Node selectItem(String s2,float x,float y,SubElementNodeEditor editor) {
		if(s2.equals("事件数据")){
			Map<String,DataValue> vars = new LinkedHashMap<>(editor.currentEvent.vars);
			if(editor.sendEvents == null) {
				vars.put("to index", new DataValueFloat(0));
			} else {
				vars.put("tag", new DataValueString(""));
				vars.put("from index", new DataValueFloat(0));
			}
			return new NodeInput(x, y, NodeInput.EVENT_DATA).setVars(vars);
		} else if(s2.equals("读取变量")){
			return new NodeGetVar(x, y, editor.currentSystem.parent);
		} else if (s2.equals("查询方块")) {
			return new NodeQueryBlock(x, y, editor.currentSystem.parent);
		} else if (s2.equals("红石输入")) {
			return new NodeRedstoneInput(x, y, editor.currentSystem.parent);
		} else if (s2.equals("批量查询")) {
			return new com.hbmzz.cecp.nodes.addon.NodeBulkQuery(x, y, editor.currentSystem.parent);
		} else if (s2.equals("图表区间")) {
			return new com.hbmzz.cecp.nodes.addon.NodeGraphBounds(x, y, editor.currentSystem.parent);
		}
		return null;
	}
	@Override
	public void addItems(ItemList list,float x,float y,SubElementNodeEditor editor) {
		list.addItems("事件数据");
		list.addItems("读取变量");
		list.addItems("查询方块");
		list.addItems("红石输入");
		list.addItems("批量查询");
		if(editor.currentSystem.parent instanceof com.hbmzz.cecp.controls.addon.Graph)
			list.addItems("图表区间");
	}
}

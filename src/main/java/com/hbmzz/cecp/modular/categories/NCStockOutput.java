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

import com.hbmzz.cecp.ItemList;
import com.hbmzz.cecp.SubElementNodeEditor;
import com.hbmzz.cecp.modular.INodeMenuCreator;
import com.hbmzz.cecp.nodes.*;

public class NCStockOutput implements INodeMenuCreator {
	@Override
	public Node selectItem(String s2,float x,float y,SubElementNodeEditor editor) {
		if(s2.equals("广播事件")){
			return new NodeEventBroadcast(x, y, editor.sendEvents);
		} else if(s2.equals("取消事件")){
			return new NodeCancelEvent(x, y);
		} else if(s2.equals("设置变量")){
			return new NodeSetVar(x, y, editor.currentSystem.parent);
		} else if(s2.equals("红石输出")){
			return new NodeRedstoneOutput(x, y);
		} else if(s2.equals("播放声音")){
			return new com.hbmzz.cecp.nodes.addon.NodeSounder(x, y);
		} else if(s2.equals("写入图表")){
			return new com.hbmzz.cecp.nodes.addon.NodeGraphAdd(x, y, editor.currentSystem.parent);
		}
		return null;
	}
	@Override
	public void addItems(ItemList list,float x,float y,SubElementNodeEditor editor) {
		if(editor.sendEvents != null){
			if(!editor.sendEvents.isEmpty())
				list.addItems("广播事件");
		} else {
			list.addItems("取消事件");
		}
		list.addItems("设置变量");
		list.addItems("红石输出");
		list.addItems("播放声音");
		// Only meaningful inside a chart's own graph, which is where upstream
		// offers it too.
		if(editor.currentSystem.parent instanceof com.hbmzz.cecp.controls.addon.Graph)
			list.addItems("写入图表");
	}
}

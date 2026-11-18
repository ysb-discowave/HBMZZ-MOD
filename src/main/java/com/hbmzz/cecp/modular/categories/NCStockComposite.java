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
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.nodes.NodeCompositeRead;
import com.hbmzz.cecp.nodes.NodeCompositeWrite;

public class NCStockComposite implements INodeMenuCreator {
	@Override
	public Node selectItem(String s2,float x,float y,SubElementNodeEditor editor) {
		if(s2.equals("读取数据包")){
			return new NodeCompositeRead(x, y);
		} else if(s2.equals("写入数据包")){
			return new NodeCompositeWrite(x, y);
		}
		return null;
	}
	@Override
	public void addItems(ItemList list,float x,float y,SubElementNodeEditor editor) {
		list.addItems("读取数据包");
		list.addItems("写入数据包");
	}
}

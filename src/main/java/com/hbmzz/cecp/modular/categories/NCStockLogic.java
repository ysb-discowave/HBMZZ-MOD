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

public class NCStockLogic implements INodeMenuCreator {
	@Override
	public Node selectItem(String s2,float x,float y,SubElementNodeEditor editor) {
		if (s2.equals("函数")) {
			return new NodeFunction(x, y);
		}
		else if (s2.equals("缓冲器")) {
			return new NodeBuffer(x, y);
		}
		else if (s2.equals("条件判断")) {
			return new NodeConditional(x, y);
		}
		return null;
	}
	@Override
	public void addItems(ItemList list,float x,float y,SubElementNodeEditor editor) {
		list.addItems("函数");
		list.addItems("缓冲器");
		list.addItems("条件判断");
	}
}

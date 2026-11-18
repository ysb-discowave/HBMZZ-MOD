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
import com.hbmzz.cecp.nodes.addon.*;
import net.minecraft.world.World;

/** Utility nodes, from CE Addon's "Utility" category. */
public class NCAddonUtility implements INodeMenuCreator {

	@Override
	public Node selectItem(String s, float x, float y, SubElementNodeEditor editor) {
		if (s.equals("缓存")) {
			return new NodeCache(x, y);
		} else if (s.equals("时钟")) {
			return new NodeClock(x, y);
		} else if (s.equals("汇总")) {
			return new NodeSummarizer(x, y);
		} else if (s.equals("文本转数字")) {
			return new NodeParseFloat(x, y);
		} else if (s.equals("随机整数")) {
			return new NodeRNG(x, y, world(editor));
		} else if (s.equals("随机小数")) {
			return new NodeRNGDecimal(x, y, world(editor));
		}
		return null;
	}

	/** The world the panel lives in - the two random nodes need its RNG. */
	private static World world(SubElementNodeEditor editor) {
		return editor.currentSystem.parent.panel.parent.getControlWorld();
	}

	@Override
	public void addItems(ItemList itemList, float x, float y, SubElementNodeEditor editor) {
		itemList.addItems("缓存");
		itemList.addItems("时钟");
		itemList.addItems("随机整数");
		itemList.addItems("随机小数");
		itemList.addItems("汇总");
		itemList.addItems("文本转数字");
	}
}

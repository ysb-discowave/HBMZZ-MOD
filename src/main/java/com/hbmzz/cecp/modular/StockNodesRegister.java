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

import com.hbmzz.cecp.modular.categories.*;

import java.util.ArrayList;
import java.util.Collections;

public class StockNodesRegister {
	//Input for the system, such as the in variables for the event or a single value
	public static final float[] colorInput = new float[]{0, 1, 0};
	//Intermediate node for math. Has a dropdown menu for operations like add, multiply, sin, abs, etc.
	public static final float[] colorMath = new float[]{1, 1, 0};
	//for boolean functions: and, not, xor etc.
	public static final float[] colorBoolean = new float[]{1, 0, 1};
	//Intermediate nodes for logic such as for loops and if statements. Each contains child node systems, which possibly produce outputs.
	public static final float[] colorLogic = new float[]{.4F, .4F, 1};
	//The outputs that get evaluated, such as broadcasting a new event or setting an arbitrary variable
	public static final float[] colorOutput = new float[]{1, 0, 0};
	//Composite series
	public static final float[] colorComposite = new float[]{1F,0.399F,0.842F};
	//Text handling, from CE Addon's "Text" category
	public static final float[] colorText = new float[]{0.2F, 0.8F, 1.0F};
	//Utility nodes - cache/clock/random/summarizer, from CE Addon's "Utility" category
	public static final float[] colorUtility = new float[]{1.0F, 0.614F, 0.189F};

	public static void register() {
		NTMControlPanelRegistry.addMenuCategories.add("输入");
		NTMControlPanelRegistry.addMenuCategories.add("输出");
		NTMControlPanelRegistry.addMenuCategories.add("数学");
		NTMControlPanelRegistry.addMenuCategories.add("布尔");
		NTMControlPanelRegistry.addMenuCategories.add("逻辑");
		NTMControlPanelRegistry.addMenuCategories.add("数据包");
		NTMControlPanelRegistry.addMenuCategories.add("文本");
		NTMControlPanelRegistry.addMenuCategories.add("实用");
		NTMControlPanelRegistry.addMenuControl.put("输入",new ArrayList<>(Collections.singletonList(new NCStockInput())));
		NTMControlPanelRegistry.addMenuControl.put("输出",new ArrayList<>(Collections.singletonList(new NCStockOutput())));
		NTMControlPanelRegistry.addMenuControl.put("数学",new ArrayList<>(Collections.singletonList(new NCStockMath())));
		NTMControlPanelRegistry.addMenuControl.put("布尔",new ArrayList<>(Collections.singletonList(new NCStockBoolean())));
		NTMControlPanelRegistry.addMenuControl.put("逻辑",new ArrayList<>(Collections.singletonList(new NCStockLogic())));
		NTMControlPanelRegistry.addMenuControl.put("数据包",new ArrayList<>(Collections.singletonList(new NCStockComposite())));
		NTMControlPanelRegistry.addMenuControl.put("文本",new ArrayList<>(Collections.singletonList(new NCAddonText())));
		NTMControlPanelRegistry.addMenuControl.put("实用",new ArrayList<>(Collections.singletonList(new NCAddonUtility())));
		NTMControlPanelRegistry.nbtNodeLoaders.add(new StockNodeLoader());
		NTMControlPanelRegistry.nbtNodeLoaders.add(new AddonNodeLoader());
	}
}

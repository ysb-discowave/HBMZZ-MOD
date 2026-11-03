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

package com.hbmzz.cecp;

import com.hbmzz.cecp.controls.ControlType;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public class SubElementItemChoice extends SubElement {
	public static ResourceLocation bg_tex = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/gui_base.png");

	public GuiButton pageLeft;
	public GuiButton pageRight;
	public int currentPage = 1;
	public int numPages = 1;
	public List<GuiButton> buttons = new ArrayList<>();
	
	public SubElementItemChoice(GuiControlEdit gui){
		super(gui);
	}
	
	@Override
	protected void initGui(){
		int cX = gui.width/2;
		int cY = gui.height/2;
		pageLeft = gui.addButton(new GuiButton(gui.currentButtonId(), cX-80, cY+92, 15, 20, "<"));
		pageRight = gui.addButton(new GuiButton(gui.currentButtonId(), cX+65, cY+92, 15, 20, ">"));

		int id = 1000;
		int pos = 0;
		for (ControlType control : ControlType.ALL_VALUES) {
			if (!ControlRegistry.getAllControlsOfType(control).isEmpty()) {
				buttons.add(gui.addButton(
						new GuiButton(id++,
								cX-80,(cY-90)+((pos++)%7)*25,
							160,20,
							control.getDisplayName()
						))
				);
			} else
				id++; // shift id anyway because the control types are gathered from button IDs
		}

		numPages = (buttons.size()+6)/7;
		super.initGui();
	}
	
	@Override
	protected void drawScreen(){
		int cX = gui.width/2;
		int cY = gui.height/2;
		String text = currentPage + "/" + numPages;
		gui.getFontRenderer().drawString(text, cX - gui.getFontRenderer().getStringWidth(text) / 2F, cY+98, 0xFF777777, false);
		text = "选择控件类型";
		gui.getFontRenderer().drawString(text, cX - gui.getFontRenderer().getStringWidth(text) / 2F, cY-110, 0xFF777777, false);
	}

	@Override
	protected void renderBackground() {
		gui.mc.getTextureManager().bindTexture(bg_tex);
		gui.drawTexturedModalRect(gui.getGuiLeft(), gui.getGuiTop(), 0, 0, gui.getXSize(), gui.getYSize());
	}
	
	private void recalculateVisibleButtons(){
		for(GuiButton b : buttons){
			b.visible = false;
			b.enabled = false;
		}
		int idx = (currentPage-1)*7;
		for(int i = idx; i < idx+7; i ++){
			if(i >= buttons.size())
				break;
			buttons.get(i).visible = true;
			buttons.get(i).enabled = true;
		}
		boolean showPaging = numPages > 1;
		pageLeft.visible = showPaging && currentPage > 1;
		pageLeft.enabled = pageLeft.visible;
		pageRight.visible = showPaging && currentPage < numPages;
		pageRight.enabled = pageRight.visible;
	}

	@Override
	protected void actionPerformed(GuiButton button){
		if(button == pageLeft){
			currentPage = Math.max(1, currentPage - 1);
			recalculateVisibleButtons();
		} else if(button == pageRight){
			currentPage = Math.min(numPages, currentPage + 1);
			recalculateVisibleButtons();
		} else {
			int typeIndex = button.id - 1000;
			if(typeIndex < 0 || typeIndex >= ControlType.ALL_VALUES.size()) return;

			ControlType type = ControlType.ALL_VALUES.get(typeIndex);
			gui.itemConfig.variants = ControlRegistry.getAllControlsOfType(type);
			if(gui.itemConfig.variants.isEmpty()) return;

			gui.currentEditControl = ControlRegistry.getNew(gui.itemConfig.variants.get(0), gui.control.panel);
			gui.pushElement(gui.itemConfig);
		}
	}
	
	@Override
	protected void enableButtons(boolean enable) {
		if (enable) {
			recalculateVisibleButtons();
		} else {
			for (GuiButton b : buttons) {
				b.visible = false;
				b.enabled = false;
			}
		}
		if(enable){
			pageLeft.visible = numPages > 1 && currentPage > 1;
			pageLeft.enabled = pageLeft.visible;
			pageRight.visible = numPages > 1 && currentPage < numPages;
			pageRight.enabled = pageRight.visible;
		} else {
			pageLeft.visible = false;
			pageLeft.enabled = false;
			pageRight.visible = false;
			pageRight.enabled = false;
		}
	}
}

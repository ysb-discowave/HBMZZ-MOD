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

import net.minecraft.client.gui.GuiButton;

public class SubElement {
	
	public GuiControlEdit gui;
	
	public boolean lock = false;
	
	public SubElement(GuiControlEdit gui) {
		this.gui = gui;
	}
	protected void actionPerformed(GuiButton button){
	}
	protected void initGui(){
		enableButtons(false);
	}
	protected void drawScreen(){
	}
	protected void renderBackground(){
	}
	protected void enableButtons(boolean enable){
	}
	protected void keyTyped(char typedChar, int code){
	}
	protected void mouseClicked(int mouseX, int mouseY, int button){
	}
	protected void mouseReleased(int mouseX, int mouseY, int state){
	}
	protected void update(){
	}
	public void onClose(){
	}
	public void onElementOpen() {
	}
	public void onElementClose() {
	}
}
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

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;

public class GuiLinkerButton extends GuiButton {
	public int actualX;
	public int actualWidth;
	public GuiTextField field;
	FontRenderer font;
	public GuiLinkerButton(FontRenderer font,int buttonId,int x,int y,int widthIn,int heightIn,String buttonText) {
		super(buttonId,x+widthIn-20,y,20,heightIn,"X");
		actualX = x;
		actualWidth = widthIn;
		this.font = font;
		field = new GuiTextField(
				-1,
				font,x+1,y+1,
				widthIn-20-2,heightIn-2
		);
		field.setText(buttonText);
	}
	public void update() {
		field.updateCursorCounter();
	}
	@Override
	public void drawButton(Minecraft mc,int mouseX,int mouseY,float partialTicks) {
		super.drawButton(mc,mouseX,mouseY,partialTicks);
		field.drawTextBox();
	}
	public void keyTyped(char key,int code) {
		if (enabled)
			field.textboxKeyTyped(key,code);
	}
	public void mouseClicked(int x,int y,int i) {
		if (enabled)
			field.mouseClicked(x,y,i);
	}
	public void setVisible(boolean visible) {
		this.visible = visible;
		field.setVisible(visible);
	}
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
		field.setVisible(enabled);
		if (!enabled)
			field.setFocused(false);
	}
}

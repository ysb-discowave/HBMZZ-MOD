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

import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.compat.GuiFx;
import com.hbmzz.cecp.compat.CtrlImm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.nbt.NBTTagCompound;
import org.lwjgl.opengl.GL11;

import java.util.function.Function;
import java.util.function.Supplier;

public class NodeDropdown extends NodeElement {

	public ItemList list;
	public Supplier<String> nameGetter;
	
	public NodeDropdown(Node parent, int idx, Function<String, ItemList> func, Supplier<String> nameGetter){
		super(parent, idx);
		list = new ItemList(0, 0, 32, func);
		list.alpha = 0.95F;
		list.r = list.g = list.b = 0.3F;
		list.textColor = 0xFFDFDFDF;
		list.close();
		resetOffset();
		this.nameGetter = nameGetter;
	}
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		throw new RuntimeException("Dropdown node should not be serialized!");
	}
	
	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		throw new RuntimeException("Dropdown node should not be serialized!");
	}
	
	@Override
	public void resetOffset(){
		super.resetOffset();
		if(list != null){
			list.posX = this.offsetX+4;
			list.posY = this.offsetY+14;
		}
	}
	
	public void setOffset(float x, float y){
		this.offsetX = x;
		this.offsetY = y;
		list.posX = x+4;
		list.posY = y+14;
	}
	
	@Override
	public void render(float mX, float mY){
		Minecraft.getMinecraft().getTextureManager().bindTexture(NodeSystem.node_tex);
		CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
		float x = offsetX+4;
		float y = offsetY+8;
		GuiFx.drawGuiRectBatchedColor(x, y, 0F, 0.890625F, 32, 6, 0.609375F, 0.984375F, 1, 1, 1, 1);
		CtrlImm.INSTANCE.draw();
		list.render(mX, mY);
		
		FontRenderer font = Minecraft.getMinecraft().fontRenderer;
		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, 0);
		GlStateManager.scale(0.35, 0.35, 0.35);
		GlStateManager.translate(-x, -y, 0);
		String s = nameGetter.get();
		font.drawString(s, x+43-font.getStringWidth(s)/2, y+5, 0xFF5F5F5F, false);
		GlStateManager.popMatrix();
		GlStateManager.pushMatrix();
		float width = font.getCharWidth('^');
		float height = font.FONT_HEIGHT;
		x = x+27.5F;
		y = y+2.5F;
		GlStateManager.translate(x+width*0.4F, y+height*0.2F, 0);
		GlStateManager.scale(0.5, 0.5, 0.5);
		if(list.isClosed)
			GlStateManager.rotate(180, 0, 0, 1);
		GlStateManager.translate(-x-width*0.4F, -y-height*0.2F, 0);
		font.drawString("^", x, y, 0xFF5F5F5F, false);
		GlStateManager.popMatrix();
	}
	
	@Override
	public boolean onClick(float x, float y){
		if(!list.isClosed && GuiFx.intersects2DBox(x, y, list.getBoundingBox())){
			if(list.mouseClicked(x, y)){
				list.close();
				return true;
			}
		}
		if(GuiFx.intersects2DBox(x, y, getBox())){
			if(list.isClosed){
				list.open();
			} else {
				list.close();
			}
			return true;
		}
		return false;
	}
	
	public float[] getBox(){
		return new float[]{3+offsetX, -3+offsetY+10, 37+offsetX, 3+offsetY+10};
	}
	
}

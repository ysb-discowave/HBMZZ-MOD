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

import net.minecraft.client.renderer.GlStateManager;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.cecp.compat.GuiFx;
import com.hbmzz.cecp.compat.CtrlImm;
import com.hbmzz.cecp.compat.CPFont;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.util.function.Consumer;

public class NodeTextBox extends NodeElement implements ITypableNode {

	public String name;

	public String value;
	public StringBuilder builder;
	public boolean isTyping;
	public Consumer<String> changed = null;

	public NodeTextBox(String name,Node p,int idx,String defaultVal,Consumer<String> func){
		super(p, idx);
		this.name = name;
		this.changed = func;
		value = defaultVal;
	}
	
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		super.writeToNBT(tag, sys);
//		tag.setString("eleType", "connection");
		tag.setString("name", name);
		tag.setString("D", value);
		return tag;
	}
	
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		super.readFromNBT(tag, sys);
		name = tag.getString("name");
		value = tag.getString("D");
		builder = null;
		isTyping = false;
	}
	
	@Override
	public void resetOffset(){
		super.resetOffset();
		offsetY += parent.otherElements.size()*8;
	}
	
	@Override
	@SideOnly(Side.CLIENT)
	public void render(float mX, float mY){
		Minecraft.getMinecraft().getTextureManager().bindTexture(NodeSystem.node_tex);
		CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
		float x = offsetX+38-40;
		float y = offsetY+8;
		float[] color = GuiFx.intersects2DBox(mX, mY, this.getValueBox()) && !isTyping ? new float[]{1, 1, 1} : new float[]{0.6F, 0.6F, 0.6F};
		GuiFx.drawGuiRectBatchedColor(x, y-1, 0, 0.203125F, 40, 6, 0.625F, 0.296875F, color[0], color[1], color[2], 1);
		CtrlImm.INSTANCE.draw();

		FontRenderer font = Minecraft.getMinecraft().fontRenderer;
		GlStateManager.pushMatrix();
		GlStateManager.translate(x, y, 0);
		GlStateManager.scale(0.4, 0.4, 0.4);
		GlStateManager.translate(-x, -y, 0);
		if(isTyping){
			String s = builder.toString() + (Minecraft.getMinecraft().world.getTotalWorldTime()%20 > 10 ? "_" : "");
			font.drawString(CPFont.cut(font, s, 74), x+16, y+1F, 0xFFAFAFAF, false);
		} else {
			int hex =  0xFFAFAFAF;
			/*
			 * The row is 40 units wide and drawn at 0.4, so the unscaled span
			 * runs from x+16 to x+94 - 78 units in total. CE hard-trims the
			 * value to five characters, which is 30 units for the ASCII font but
			 * 45 for four Chinese glyphs; measuring instead of counting keeps
			 * the label and the value from overlapping whichever script is in
			 * use.
			 */
			font.drawString(CPFont.cut(font, name, 44), x+16, y+1F, hex, false);
			String s = CPFont.cut(font, value.toString(), 30);
			font.drawString(s, x+94-font.getStringWidth(s), y+1, 0xFFAFAFAF, false);
		}
		GlStateManager.popMatrix();
	}
	
	//minX, minY, maxX, maxY
	@SideOnly(Side.CLIENT)
	public float[] getPortBox(){
		float oX = offsetX;
		return new float[]{-2+oX, -2+offsetY+10, 2+oX, 2+offsetY+10};
	}
	
	@SideOnly(Side.CLIENT)
	@Override
	public float[] getValueBox(){
		return new float[]{3+offsetX, -3+offsetY+10, 37+offsetX, 3+offsetY+10};
	}

	public DataValue evaluate(){
		return new DataValueString(value);
	}

	@SideOnly(Side.CLIENT)
	@Override
	public boolean isTyping() {
		return isTyping;
	}

	@SideOnly(Side.CLIENT)
	@Override
	public void startTyping(){
		isTyping = true;
		builder = new StringBuilder();
		builder.append(value.toString());
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	@SideOnly(Side.CLIENT)
	@Override
	public void stopTyping(){
		value = builder.toString();
		builder = null;
		isTyping = false;
		changed.accept(value);
	}
	
	@SideOnly(Side.CLIENT)
	@Override
	public void keyTyped(char c, int key){
		if(key == Keyboard.KEY_BACK){
			if(builder.length() > 0)
				builder.deleteCharAt(builder.length()-1);
		} else if(key == Keyboard.KEY_RETURN){
			stopTyping();
		} else if (key != Keyboard.KEY_LSHIFT && key != Keyboard.KEY_RSHIFT) {
			builder.append(c);
		}
	}
}

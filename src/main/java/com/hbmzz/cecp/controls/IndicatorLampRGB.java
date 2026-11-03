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

package com.hbmzz.cecp.controls;

import com.hbmzz.cecp.*;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.compat.ObjModel;
import com.hbmzz.cecp.compat.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;


public class IndicatorLampRGB extends Control {

	public IndicatorLampRGB(String name,String registryName,ControlPanel panel) {
		super(name,registryName, panel);
		vars.put("red", new DataValueFloat(0));
		vars.put("green", new DataValueFloat(0));
		vars.put("blue", new DataValueFloat(0));
	}

	@Override
	public ControlType getControlType() {
		return ControlType.INDICATOR;
	}

	@Override
	public float[] getSize() {
		return new float[] {.5F, .5F, .18F};
	}

	@Override
	public void render() {
		GlStateManager.shadeModel(GL11.GL_SMOOTH);
		Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.ctrl_button_push_tex);

		ObjModel model = (ObjModel) getModel();
		float red = getVar("red").getNumber()/255F;
		float green = getVar("green").getNumber()/255F;
		float blue = getVar("blue").getNumber()/255F;

		float lX = OpenGlHelper.lastBrightnessX;
		float lY = OpenGlHelper.lastBrightnessY;

		// --- Base (untextured dark plastic) ---
		GlStateManager.pushMatrix();
		GlStateManager.disableTexture2D();
		GlStateManager.translate(posX,0.0,posY);
		GlStateManager.color(0.3F,0.3F,0.3F,1.0F);
		model.renderPart("base");
		GlStateManager.enableTexture2D();
		GlStateManager.popMatrix();

		// --- Lamp emissive ---

		GlStateManager.pushMatrix();
		{
			// base
			GlStateManager.translate(posX,0.0,posY);
			GlStateManager.color(0.25f,0.25f,0.25f,1.0F);
			model.renderPart("lamp");

			// overlay
			OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,240,240);
			GlStateManager.scale(1.01,1.01,1.01);
			boolean blendEnabled = RenderUtil.isBlendEnabled();
			int srcFactor = RenderUtil.getBlendSrcFactor();
			int dstFactor = RenderUtil.getBlendDstFactor();
			GlStateManager.enableBlend();
			GlStateManager.blendFunc(SourceFactor.SRC_ALPHA,DestFactor.ONE);
			GlStateManager.color(red,green,blue,1.0F);
			model.renderPart("lamp");
			GlStateManager.blendFunc(srcFactor,dstFactor);
			if (!blendEnabled)
				GlStateManager.disableBlend();
		}
		GlStateManager.popMatrix();

		OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit,lX,lY);

		GlStateManager.shadeModel(GL11.GL_FLAT);
		GlStateManager.color(1.0F,1.0F,1.0F,1.0F);
	}


	@Override
	@SideOnly(Side.CLIENT)
	public IModelCustom getModel() {
		return CtrlRes.ctrl_indicator_lamp;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public ResourceLocation getGuiTexture() {
		return CtrlRes.ctrl_indicator_lamp_gui_tex;
	}

	@Override
	public AxisAlignedBB getBoundingBox() {
		return null;
	}

	@Override
	public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
	}

	@Override
	public Control newControl(ControlPanel panel) {
		return new IndicatorLampRGB(name,registryName,panel);
	}
}

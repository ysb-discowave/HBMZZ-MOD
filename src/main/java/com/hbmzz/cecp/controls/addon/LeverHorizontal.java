/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Ported from NTM Cursed Addon (CE Addon); see NOTICE.md for the list
 * of changes.
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

package com.hbmzz.cecp.controls.addon;

import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.compat.AddonRes;
import com.hbmzz.cecp.compat.IModelCustom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

/** Lever lying on its side: 2 wide, 1 deep. */
public class LeverHorizontal extends LeverBase {

    public LeverHorizontal(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
    }

    @Override
    public float[] getSize() {
        return new float[]{2F, 1F, 1.25F};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        advanceVisual();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1F, 1F, 1F);
        IModelCustom mdl = getModel();
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0F, posY);
        GlStateManager.rotate(-90F, 0F, 1F, 0F);
        GlStateManager.scale(0.5F, 0.5F, 0.5F);
        GlStateManager.color(0.306F, 0.306F, 0.306F);
        mdl.renderPart("Base");
        GlStateManager.color(0.765F, 0.765F, 0.765F);
        mdl.renderPart("Base.001");
        GlStateManager.translate(0, 0.1, 0);
        GlStateManager.rotate(-50F * (curLevel / 50F - 1F), 1F, 0F, 0F);
        GlStateManager.translate(0, -0.1, 0);
        GlStateManager.color(0.533F, 0.533F, 0.533F);
        mdl.renderPart("Lever");
        GlStateManager.popMatrix();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return AddonRes.lever;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return AddonRes.lever_h_gui_tex;
    }

    @Override
    public com.hbmzz.cecp.Control newControl(ControlPanel panel) {
        return new LeverHorizontal(name, registryName, panel);
    }
}

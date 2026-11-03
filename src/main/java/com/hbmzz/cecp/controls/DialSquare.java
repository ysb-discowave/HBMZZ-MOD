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

import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.controls.configs.SubElementDialSquare;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.cecp.compat.ObjModel;
import com.hbmzz.cecp.*;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;

public class DialSquare extends Control {

    private String label = "功率   (红石/10)";

    public DialSquare(String name,String registryName,ControlPanel panel) {
        super(name,registryName, panel);
        vars.put("value", new DataValueFloat(0));
        configMap.put("label", new DataValueString(label));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui,Map<String,DataValue> configs) {
        return new SubElementDialSquare(gui,configs);
    }

    @Override
    public ControlType getControlType() {
        return ControlType.DIAL;
    }

    @Override
    public float[] getSize() {
        return new float[] {2.25F, 2.25F, .4F};
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String,DataValue> e : configMap.entrySet()) {
            switch (e.getKey()) {
                case "label": {
                    label = e.getValue().toString();
                    break;
                }
            }
        }
    }

    @Override
    public void render() {
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.ctrl_dial_square_tex);

        ObjModel model = (ObjModel) getModel();
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;

        int value = (int) getVar("value").getNumber();

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0.0, posY);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        model.renderPart("base");
        GlStateManager.popMatrix();

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX + 0.77F, 0.0F, posY + 0.77F);
        GlStateManager.rotate(
                (float) -MathHelper.clamp(value * ((90F) / 100F), 0F, 90F),
                0F, 1F, 0F
        );
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        model.renderPart("dial");
        GlStateManager.popMatrix();

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0.07F, posY);
        GlStateManager.scale(0.023F, 0.023F, 0.023F);
        GlStateManager.rotate(90F, 1F, 0F, 0F);
        GlStateManager.glNormal3f(0F, 0F, -1F);

        for (int i = 0; i < 11; i++) {
            double angle = (Math.PI / 1.8) / 11F * i;
            float r = 68F;

            double x = r * Math.cos(angle - Math.PI);
            double y = r * Math.sin(angle - Math.PI);

            String txt = (i % 2 != 0) ? "·" : Integer.toString(i);
            font.drawString(txt, (float) (28 + x), (float) (29.5F + y), 0x303030, false);
        }

        font.drawSplitString(label, -8, -5, 50, 0x303030);

        GlStateManager.popMatrix();

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }



    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return CtrlRes.ctrl_dial_square;
    }

    @Override
    public ResourceLocation getGuiTexture() {
        return CtrlRes.ctrl_dial_square_gui_tex;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new DialSquare(name,registryName,panel);
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {

    }
}

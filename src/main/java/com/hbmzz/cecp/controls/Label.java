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
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.controls.configs.SubElementLabel;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Map;

public class Label extends Control {

    private float[] color = new float[] {1, 1, 1};
    private String text = "标签";
    private int scale = 25;

    float width = 0;
    float height = 0;

    public Label(String name,String registryName,ControlPanel panel) {
        super(name,registryName, panel);
        vars.put("isLit", new DataValueFloat(0));
        configMap.put("colorR", new DataValueFloat(color[0]));
        configMap.put("colorG", new DataValueFloat(color[1]));
        configMap.put("colorB", new DataValueFloat(color[2]));
        configMap.put("text", new DataValueString(text));
        configMap.put("scale", new DataValueFloat(scale));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui,Map<String,DataValue> configs) {
        return new SubElementLabel(gui,configs);
    }

    @Override
    public ControlType getControlType() {
        return ControlType.LABEL;
    }

    @Override
    public float[] getSize() {
        return new float[] {0, 0, 0};
    }

    @Override
    public void fillBox(float[] box) {
        box[0] = posX;
        box[1] = posY;
        box[2] = posX + (width * scale / 500F);
        box[3] = posY + (height * scale / 500F);
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String,DataValue> e : configMap.entrySet()) {
            switch (e.getKey()) {
                case "colorR" : {
                    color[0] = e.getValue().getNumber();
                    break;
                }
                case "colorG" : {
                    color[1] = e.getValue().getNumber();
                    break;
                }
                case "colorB" : {
                    color[2] = e.getValue().getNumber();
                    break;
                }
                case "text" : {
                    text = e.getValue().toString();
                    break;
                }
                case "scale" : {
                    scale = (int) e.getValue().getNumber();
                    break;
                }
            }
        }
    }

    @Override
    public void render() {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;

        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0, posY);
        GlStateManager.depthMask(false);

        boolean isLit = getVar("isLit").getBoolean();
        width = font.getStringWidth(text);
        height = font.FONT_HEIGHT;

        float s = scale/500F;
        GlStateManager.translate(0, .03F, 0);
        GlStateManager.scale(s, -s, s);
        GlStateManager.color(0.0F, 0.0F, -1.0F);
        GlStateManager.rotate(90, 1, 0, 0);

        int r = (int) (color[0]*255);
        int g = (int) (color[1]*255);
        int b = (int) (color[2]*255);
        int rgb = (r << 16) | (g << 8) | b;

        if (isLit) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        }

        GlStateManager.disableLighting();
        font.drawString(text, 0, 0, rgb, false);
        GlStateManager.enableLighting();

        if (isLit) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);
        }

        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return CtrlRes.ctrl_display_seven_seg;
    }

    @Override
    public ResourceLocation getGuiTexture() {
        return CtrlRes.ctrl_display_seven_seg_gui_tex;
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderControl(float[] renderBox,Control selectedControl,GuiControlEdit gui) {
        String text = getConfigs().get("text").toString();
        float scale = getConfigs().get("scale").getNumber()/500F;

        int r = (int) (getConfigs().get("colorR").getNumber()*255);
        int g = (int) (getConfigs().get("colorG").getNumber()*255 * ((this == selectedControl) ? .5F : 1F));
        int b = (int) (getConfigs().get("colorB").getNumber()*255);
        int rgb2 = (r << 16) | (g << 8) | b;

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, posY, 0);
        GlStateManager.scale(scale, scale, scale);
        GlStateManager.translate(-posX, -posY, 0);
        gui.getFontRenderer().drawString(text, posX, posY, rgb2, false);
        GlStateManager.popMatrix();
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new Label(name,registryName,panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {

    }
}

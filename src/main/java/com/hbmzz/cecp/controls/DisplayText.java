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
import com.hbmzz.cecp.controls.configs.SubElementDisplayText;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueEnum;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.compat.CtrlBuf;
import com.hbmzz.cecp.compat.CtrlImm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Map;

public class DisplayText extends Control {

    private int scale = 25;
    private float width = 20;
    private float textWidth = 0; //TODO: stop all-too-long text
    private float height = 0;

    public DisplayText(String name,String registryName,ControlPanel panel) {
        super(name,registryName, panel);
        vars.put("isLit", new DataValueFloat(0));
        vars.put("text", new DataValueString("text"));
        vars.put("color", new DataValueEnum<>(EnumDyeColor.WHITE));
        configMap.put("scale", new DataValueFloat(scale));
        configMap.put("width", new DataValueFloat(width));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui,Map<String,DataValue> configs) {
        return new SubElementDisplayText(gui,configs);
    }

    @Override
    public ControlType getControlType() {
        return ControlType.DISPLAY;
    }

    @Override
    public float[] getSize() {
        return new float[] {0, 0, 0};
    }

    @Override
    public void fillBox(float[] box) {
        float d = .1F;
        box[0] = posX - d;
        box[1] = posY - d;
        box[2] = posX + (width * 1.5F * scale / 500F) + d;
        box[3] = posY + (height * scale / 500F) + d;
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> e : configMap.entrySet()) {
            switch (e.getKey()) {
                case "scale": {
                    scale = (int) e.getValue().getNumber();
                    break;
                }
                case "width": {
                    width = (int) e.getValue().getNumber();
                    break;
                }
            }
        }
    }

    @Override
    public void render() {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;

        String text = getVar("text").toString();
        boolean isLit = getVar("isLit").getBoolean();
        EnumDyeColor dyeColor = getVar("color").getEnum(EnumDyeColor.class);
        int color = dyeColor.getColorValue();

        textWidth = font.getStringWidth(text);
        height = font.FONT_HEIGHT;
        float s = scale/500F;

        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0, posY);

        GlStateManager.translate(0, .03F, 0);
        GlStateManager.scale(s, -s, s);
        GlStateManager.color(0.0F, 0.0F, -1.0F);
        GlStateManager.rotate(90, 1, 0, 0);

        if (isLit) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        }

        GlStateManager.disableLighting();
        font.drawString(text, 0, 0, color, false);
        GlStateManager.enableLighting();

        if (isLit) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);
        }

        GlStateManager.popMatrix();

        GlStateManager.pushMatrix();
        GlStateManager.rotate(90, 1, 0, 0);
        GlStateManager.translate(0, 0, -.01F);

        GlStateManager.disableTexture2D();
        CtrlBuf buf = CtrlImm.INSTANCE.beginPositionTexColorQuads(2);
        float[] box = getBox();
        float[] rgb = new float[]{0, 0, 0};
        float d = 0;
        int packedColor = CtrlBuf.packColor(rgb[0], rgb[1], rgb[2], 1);
        buf.appendPositionTexColorQuadUnchecked(
                box[0]-d, box[1]-d, -0.01F, 0, 0, packedColor,
                box[0]-d, box[3], -0.01F, 0, 1, packedColor,
                box[2]+d, box[3], -0.01F, 1, 1, packedColor,
                box[2]+d, box[1]-d, -0.01F, 1, 0, packedColor
        );
        rgb = new float[]{.3F, .3F, .3F};
        d = .05F;
        packedColor = CtrlBuf.packColor(rgb[0], rgb[1], rgb[2], 1);
        buf.appendPositionTexColorQuadUnchecked(
                box[0]-d, box[1]-d, 0, 0, 0, packedColor,
                box[0]-d, box[3]+d, 0, 0, 1, packedColor,
                box[2]+d, box[3]+d, 0, 1, 1, packedColor,
                box[2]+d, box[1]-d, 0, 1, 0, packedColor
        );
        CtrlImm.INSTANCE.draw();
        GlStateManager.enableTexture2D();
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
        String text = getVar("text").toString();
        float scale = getConfigs().get("scale").getNumber()/500F;

        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.white);
        CtrlBuf buf = CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
        int packedColor = CtrlBuf.packColor(0.2F, this == selectedControl ? 0.1F : 0.2F, 0.2F, 1.0F);
        appendGuiQuad(buf, renderBox[0], renderBox[1], renderBox[2], renderBox[3], 0.0F, 0.0F, 1.0F, 1.0F, packedColor);
        CtrlImm.INSTANCE.draw();

        EnumDyeColor dyeColor = getVar("color").getEnum(EnumDyeColor.class);
        int color = dyeColor.getColorValue();

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, posY, 0);
        GlStateManager.scale(scale, scale, scale);
        GlStateManager.translate(-posX, -posY, 0);
        gui.getFontRenderer().drawString(text, posX, posY, color, false);
        GlStateManager.popMatrix();
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new DisplayText(name,registryName,panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {

    }

}

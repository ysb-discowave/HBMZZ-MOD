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

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.GuiControlEdit;
import com.hbmzz.cecp.compat.AddonRes;
import com.hbmzz.cecp.compat.Brush;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.controls.configs.addon.SubElementDrawing;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;
import java.util.Map;

/**
 * A coloured box with optional caption - the addon's "Boxed Label", used to
 * group or annotate parts of a panel.
 *
 * <p>Ported from CE Addon's {@code Drawing}.</p>
 */
public class Drawing extends Control {

    private int r = 100;
    private int g = 100;
    private int b = 100;
    private float width;
    private float height;
    private int scale = 25;
    private String text = "";
    private boolean white = false;

    public Drawing(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        configMap.put("red", new DataValueFloat(r));
        configMap.put("green", new DataValueFloat(g));
        configMap.put("blue", new DataValueFloat(b));
        configMap.put("width", new DataValueFloat(0.2F));
        configMap.put("height", new DataValueFloat(0.2F));
        configMap.put("scale", new DataValueFloat(scale));
        configMap.put("text", new DataValueString(text));
        configMap.put("white", new DataValueFloat(0));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui, Map<String, DataValue> configs) {
        return new SubElementDrawing(gui, configs);
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> entry : configMap.entrySet()) {
            switch (entry.getKey()) {
                case "red":
                    r = (int) MathHelper.clamp(entry.getValue().getNumber(), 0F, 100F);
                    break;
                case "green":
                    g = (int) MathHelper.clamp(entry.getValue().getNumber(), 0F, 100F);
                    break;
                case "blue":
                    b = (int) MathHelper.clamp(entry.getValue().getNumber(), 0F, 100F);
                    break;
                case "width":
                    width = entry.getValue().getNumber();
                    break;
                case "height":
                    height = entry.getValue().getNumber();
                    break;
                case "scale":
                    scale = (int) entry.getValue().getNumber();
                    break;
                case "text":
                    text = entry.getValue().toString();
                    break;
                case "white":
                    white = entry.getValue().getNumber() >= 1F;
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public ControlType getControlType() {
        return ControlType.LABEL;
    }

    @Override
    public float[] getSize() {
        return new float[]{1F, 1F, 0.01F};
    }

    @Override
    public void fillBox(float[] box) {
        box[0] = posX;
        box[1] = posY;
        box[2] = posX + width;
        box[3] = posY + height;
    }

    @SideOnly(Side.CLIENT)
    void draw(float greenTint) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        Brush brush = Brush.instance;
        GlStateManager.translate(0, 0, -0.002);
        GlStateManager.color(r / 100F, g / 100F * greenTint, b / 100F);
        brush.startDrawingQuads();
        brush.addVertexWithUV(0, height, 0, 0, 1);
        brush.addVertexWithUV(width, height, 0, 1, 1);
        brush.addVertexWithUV(width, 0, 0, 1, 0);
        brush.addVertexWithUV(0, 0, 0, 0, 0);
        brush.draw();
        if (text != null && !text.trim().isEmpty()) {
            float s = scale / 500F;
            GlStateManager.translate(width / 2F, height / 2F, -0.002);
            GlStateManager.scale(s, s, s);
            GlStateManager.translate(-font.getStringWidth(text) / 2F, 0, 0);
            font.drawString(text, 0, -3, white ? 0xFFFFFF : 0);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        GlStateManager.color(1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX - 0.5F, 0, posY - 0.5F);
        GlStateManager.rotate(90F, 1F, 0F, 0F);
        draw(1F);
        GlStateManager.popMatrix();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.translate(renderBox[0], renderBox[1], 0);
        GlStateManager.scale((renderBox[2] - renderBox[0]) / width, (renderBox[3] - renderBox[1]) / height, 1F);
        draw(this == selectedControl ? 0.8F : 1F);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return AddonRes.solid;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new Drawing(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
    }
}

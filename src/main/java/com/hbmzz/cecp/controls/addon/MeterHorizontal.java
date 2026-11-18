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
import com.hbmzz.cecp.controls.configs.addon.SubElementMeterHorizontal;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;

/**
 * Wide horizontal gauge: coloured bar, ticks with numbers, a needle.
 *
 * <p>Ported from CE Addon's {@code MeterHorizontal}. No model - every part of it
 * is drawn from quads over the flat-white texture, which is why it needs
 * {@code AddonRes.solid} unbound/rebound around the tick marks.</p>
 */
public class MeterHorizontal extends Control {

    private int length = 5;
    private int r = 255;
    private int g = 153;
    private int b = 0;
    private float min = 0F;
    private float max = 5F;
    private int divisions = 6;
    private int subdivisions = 5;
    private int decimals = 0;

    public MeterHorizontal(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("value", new DataValueFloat(0));
        configMap.put("length", new DataValueFloat(length));
        configMap.put("r", new DataValueFloat(r));
        configMap.put("g", new DataValueFloat(g));
        configMap.put("b", new DataValueFloat(b));
        configMap.put("minValue", new DataValueFloat(min));
        configMap.put("maxValue", new DataValueFloat(max));
        configMap.put("divisions", new DataValueFloat(divisions));
        configMap.put("subdivisions", new DataValueFloat(subdivisions));
        configMap.put("decimals", new DataValueFloat(decimals));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui, Map<String, DataValue> configs) {
        return new SubElementMeterHorizontal(gui, configs);
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> entry : configMap.entrySet()) {
            switch (entry.getKey()) {
                case "length":
                    length = (int) entry.getValue().getNumber();
                    break;
                case "r":
                    r = (int) entry.getValue().getNumber();
                    break;
                case "g":
                    g = (int) entry.getValue().getNumber();
                    break;
                case "b":
                    b = (int) entry.getValue().getNumber();
                    break;
                case "minValue":
                    min = entry.getValue().getNumber();
                    break;
                case "maxValue":
                    max = entry.getValue().getNumber();
                    break;
                case "decimals":
                    decimals = (int) entry.getValue().getNumber();
                    break;
                case "divisions":
                    divisions = (int) entry.getValue().getNumber();
                    break;
                case "subdivisions":
                    subdivisions = (int) entry.getValue().getNumber();
                    break;
                default:
                    break;
            }
        }
    }

    @Override
    public ControlType getControlType() {
        return ControlType.METER;
    }

    @Override
    public float[] getSize() {
        return new float[]{5F, 1F, 0.01F};
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    public void fillBox(float[] box) {
        float height = getSize()[1];
        box[0] = posX;
        box[1] = posY;
        box[2] = posX + length;
        box[3] = posY + height;
    }

    @SideOnly(Side.CLIENT)
    void draw(float greenTint, float value) {
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
        boolean uni = font.getUnicodeFlag();
        font.setUnicodeFlag(false);
        Brush brush = Brush.instance;
        GlStateManager.translate(0, 0, -0.002);

        // Backplate, then the inset lighter face.
        GlStateManager.color(0.1F, 0.1F * greenTint, 0.1F);
        brush.startDrawingQuads();
        brush.addVertexWithUV(0, 1, 0, 0, 1);
        brush.addVertexWithUV(length, 1, 0, 1, 1);
        brush.addVertexWithUV(length, 0, 0, 1, 0);
        brush.addVertexWithUV(0, 0, 0, 0, 0);
        brush.draw();

        GlStateManager.translate(0, 0, -0.002);
        float length1 = length - 0.2F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.1, 0.1, 0);
        GlStateManager.scale(1, 0.8, 1);
        GlStateManager.color(1F, greenTint, 1F);
        brush.startDrawingQuads();
        brush.addVertexWithUV(0, 1, 0, 0, 1);
        brush.addVertexWithUV(length1, 1, 0, 1, 1);
        brush.addVertexWithUV(length1, 0, 0, 1, 0);
        brush.addVertexWithUV(0, 0, 0, 0, 0);
        brush.draw();
        GlStateManager.popMatrix();

        GlStateManager.translate(0, 0, -0.002);
        GlStateManager.color(0, 0, 0);
        float padding = 0.4F;
        float endpoint = length - padding;
        GlStateManager.translate(0, 0.1, 0);
        int startY = 22;

        for (int i0 = 0; i0 < divisions; i0++) {
            float i = (float) i0 / (divisions - 1);
            GlStateManager.pushMatrix();
            if (subdivisions > 0 && i0 < divisions - 1) {
                boolean isOdd = subdivisions % 2 == 1;
                for (int j = 1; j <= subdivisions; j++) {
                    float increment = 1F / (divisions - 1) / (subdivisions + 1);
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(lerp(padding, endpoint, i + increment * j), 0, 0);
                    GlStateManager.scale(0.025F, 0.025F, 0.025F);
                    GlStateManager.translate(-0.5, 0, 0);
                    int height = 3;
                    if (isOdd && j - 1 == subdivisions / 2) {
                        height = 5;
                    }
                    brush.startDrawingQuads();
                    brush.addVertexWithUV(0, startY + 3, 0, 0, 1);
                    brush.addVertexWithUV(1, startY + 3, 0, 1, 1);
                    brush.addVertexWithUV(1, startY - height, 0, 1, 0);
                    brush.addVertexWithUV(0, startY - height, 0, 0, 0);
                    brush.draw();
                    GlStateManager.popMatrix();
                }
            }

            GlStateManager.translate(lerp(padding, endpoint, i), 0, 0);
            GlStateManager.scale(0.025F, 0.025F, 0.025F);
            GlStateManager.translate(-0.5, 0, 0);
            brush.startDrawingQuads();
            brush.addVertexWithUV(0, startY + 3, 0, 0, 1);
            brush.addVertexWithUV(1, startY + 3, 0, 1, 1);
            brush.addVertexWithUV(1, startY - 7, 0, 1, 0);
            brush.addVertexWithUV(0, startY - 7, 0, 0, 0);
            brush.draw();
            String s = String.format("%01." + decimals + "f", lerp(min, max, (float) i0 / (divisions - 1)));
            font.drawString(s, -(font.getStringWidth(s) - 1) / 2, startY - 8 - 7, 0);
            Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
            GlStateManager.popMatrix();
        }

        font.setUnicodeFlag(uni);
        float needleWidth = 0.05F;
        GlStateManager.translate(0, 0, -0.002);
        float needleHeight = 0.7F;
        brush.startDrawingQuads();
        brush.addVertexWithUV(padding - needleWidth, needleHeight, 0, 0, 1);
        brush.addVertexWithUV(endpoint + needleWidth, needleHeight, 0, 1, 1);
        brush.addVertexWithUV(endpoint + needleWidth, needleHeight - needleWidth * 2F, 0, 1, 0);
        brush.addVertexWithUV(padding - needleWidth, needleHeight - needleWidth * 2F, 0, 0, 0);
        brush.draw();

        // Needle: a black outline fan, then the lit colour on top of it.
        GlStateManager.translate(0, 0, -0.002);
        float needleOutline = needleWidth / 3F;
        brush.startDrawing(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_TEX);
        brush.addVertexWithUV(lerp(padding, endpoint, value) - needleWidth - needleOutline, needleHeight - needleWidth * 2F - needleOutline, 0, 0, 0);
        brush.addVertexWithUV(lerp(padding, endpoint, value) - needleWidth - needleOutline, needleHeight + needleOutline, 0, 0, 1);
        brush.addVertexWithUV(lerp(padding, endpoint, value) + needleWidth + needleOutline, needleHeight + needleOutline, 0, 1, 1);
        brush.addVertexWithUV(lerp(padding, endpoint, value) + needleWidth + needleOutline, needleHeight - needleWidth * 2F - needleOutline, 0, 1, 0);
        brush.addVertexWithUV(lerp(padding, endpoint, value), needleHeight - needleWidth * 3F - needleOutline, 0, 0, 0);
        brush.draw();

        GlStateManager.translate(0, 0, -0.002);
        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid_e);
        GlStateManager.color(r / 255F, g / 255F * greenTint, b / 255F);
        GlStateManager.disableLighting();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        brush.startDrawing(GL11.GL_TRIANGLE_FAN, DefaultVertexFormats.POSITION_TEX);
        brush.addVertexWithUV(lerp(padding, endpoint, value) - needleWidth, needleHeight - needleWidth * 2F, 0, 0, 0);
        brush.addVertexWithUV(lerp(padding, endpoint, value) - needleWidth, needleHeight, 0, 0, 1);
        brush.addVertexWithUV(lerp(padding, endpoint, value) + needleWidth, needleHeight, 0, 1, 1);
        brush.addVertexWithUV(lerp(padding, endpoint, value) + needleWidth, needleHeight - needleWidth * 2F, 0, 1, 0);
        brush.addVertexWithUV(lerp(padding, endpoint, value), needleHeight - needleWidth * 3F, 0, 0, 0);
        brush.draw();
        GlStateManager.enableLighting();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);
        GlStateManager.color(1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1F, 1F, 1F);
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX - 2.5F, 0, posY - 0.5F);
        GlStateManager.rotate(90F, 1F, 0F, 0F);
        draw(1F, MathHelper.clamp(ratio(min, max, getVar("value").getNumber()), 0F, 1F));
        GlStateManager.popMatrix();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.translate(renderBox[0], renderBox[1], 0);
        GlStateManager.scale((renderBox[2] - renderBox[0]) / length, renderBox[3] - renderBox[1], 1F);
        draw(this == selectedControl ? 0.8F : 1F, 0F);
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    static float ratio(float a, float b, float t) {
        float delta = b - a;
        return delta == 0F ? a : (t - a) / delta;
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
        return new MeterHorizontal(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
    }
}

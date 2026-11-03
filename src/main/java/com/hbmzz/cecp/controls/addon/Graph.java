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
import com.hbmzz.cecp.controls.configs.addon.SubElementGraph;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagFloat;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.nio.DoubleBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rolling line chart.
 *
 * <p>Ported from CE Addon's {@code Graph}. Node-graph output feeds it through
 * {@link #pushValue}, which runs on the server; the samples are mirrored to
 * clients through the {@code latest} variable rather than the addon's own sync
 * packet, and each client replays them into its own ring buffer at 20 Hz.</p>
 */
public class Graph extends Control {

    private float width = 6F;
    private float height = 4F;
    private int verticalLineSpan = 1;
    private int horizontalLineDivision = 10;
    private int segments = 40;
    private int decimalLength = 2;
    private float textOffset = 0.5F;
    private float red = 0F;
    private float green = 255F;
    private float blue = 0F;
    private GraphSkin skin = GraphSkin.CONSOLE;

    public double[] values = new double[segments];

    private static DoubleBuffer dbuf = null;
    private long lastSample;

    public Graph(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("minValue", new DataValueFloat(0));
        vars.put("maxValue", new DataValueFloat(1));
        vars.put("latest", new DataValueFloat(0));
        configMap.put("red", new DataValueFloat(red));
        configMap.put("green", new DataValueFloat(green));
        configMap.put("blue", new DataValueFloat(blue));
        configMap.put("skin", new DataValueString("Console"));
        configMap.put("segments", new DataValueFloat(40));
        configMap.put("verticalLineSpan", new DataValueFloat(1));
        configMap.put("horizontalLineDivision", new DataValueFloat(10));
        configMap.put("decimalLength", new DataValueFloat(2));
        configMap.put("textOffset", new DataValueFloat(0.5F));
        configMap.put("width", new DataValueFloat(6F));
        configMap.put("height", new DataValueFloat(4F));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui, Map<String, DataValue> configs) {
        return new SubElementGraph(gui, configs);
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> entry : configMap.entrySet()) {
            switch (entry.getKey()) {
                case "red":
                    red = MathHelper.clamp(entry.getValue().getNumber(), 0F, 255F);
                    break;
                case "green":
                    green = MathHelper.clamp(entry.getValue().getNumber(), 0F, 255F);
                    break;
                case "blue":
                    blue = MathHelper.clamp(entry.getValue().getNumber(), 0F, 255F);
                    break;
                case "skin":
                    for (GraphSkin s : GraphSkin.skins) {
                        if (s.name.equals(entry.getValue().toString())) {
                            skin = s;
                            break;
                        }
                    }
                    break;                case "segments":
                    segments = (int) entry.getValue().getNumber();
                    rebuildArray();
                    break;
                case "verticalLineSpan":
                    verticalLineSpan = (int) entry.getValue().getNumber();
                    break;
                case "horizontalLineDivision":
                    horizontalLineDivision = (int) entry.getValue().getNumber();
                    break;
                case "decimalLength":
                    decimalLength = MathHelper.clamp((int) entry.getValue().getNumber(), 0, 5);
                    break;
                case "textOffset":
                    textOffset = entry.getValue().getNumber();
                    break;
                case "width":
                    width = entry.getValue().getNumber();
                    break;
                case "height":
                    height = entry.getValue().getNumber();
                    break;
                default:
                    break;
            }
        }
    }

    public double getMinValue() {
        return vars.get("minValue").getNumber();
    }

    public double getMaxValue() {
        return vars.get("maxValue").getNumber();
    }

    @Override
    public ControlType getControlType() {
        return AddonControlType.GRAPH;
    }

    @Override
    public float[] getSize() {
        return new float[]{width + 0.2F, height + 0.2F, 0.1F};
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    /** Appends a sample. Runs on the server; clients replay it from {@code latest}. */
    public void pushValue(double value) {
        for (int i = 0; i < values.length - 1; i++) {
            values[i] = values[i + 1];
        }
        values[values.length - 1] = value;
        vars.put("latest", new DataValueFloat((float) value));
    }

    public double scaleValue(double value) {
        double minValue = getMinValue();
        double maxValue = getMaxValue();
        return maxValue == minValue ? maxValue : (value - minValue) / (maxValue - minValue);
    }

    public void rebuildArray() {
        if (values.length != segments) {
            values = new double[segments];
        }
    }

    /** Clients advance their own buffer off the mirrored sample. */
    @SideOnly(Side.CLIENT)
    private void sampleClient() {
        long now = System.currentTimeMillis();
        if (now - lastSample < 50L) {
            return;
        }
        lastSample = now;
        if (segments != values.length) {
            rebuildArray();
        }
        double latest = vars.containsKey("latest") ? vars.get("latest").getNumber() : 0;
        for (int i = 0; i < values.length - 1; i++) {
            values[i] = values[i + 1];
        }
        values[values.length - 1] = latest;
    }

    @SideOnly(Side.CLIENT)
    void drawPreview(float greenTint) {
        Brush brush = Brush.instance;
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
        GlStateManager.color(0.25F, 0.25F * greenTint, 0.25F);
        brush.startDrawingQuads();
        brush.addVertexWithUV(0, 1, 0, 0, 1);
        brush.addVertexWithUV(1, 1, 0, 1, 1);
        brush.addVertexWithUV(1, 0, 0, 1, 0);
        brush.addVertexWithUV(0, 0, 0, 0, 0);
        brush.draw();
        GlStateManager.translate(0.5, 0.5, 0);
        double totalWidth = width + 0.2F;
        double totalHeight = height + 0.2F;
        GlStateManager.scale(width / totalWidth, height / totalHeight, 1F);
        GlStateManager.translate(-0.5, -0.5, 0);
        GlStateManager.color(channel(skin.bgColor, 16), channel(skin.bgColor, 8) * greenTint, channel(skin.bgColor, 0));
        brush.startDrawingQuads();
        brush.addVertexWithUV(0, 1, 0, 0, 1);
        brush.addVertexWithUV(1, 1, 0, 1, 1);
        brush.addVertexWithUV(1, 0, 0, 1, 0);
        brush.addVertexWithUV(0, 0, 0, 0, 0);
        brush.draw();
        GlStateManager.pushMatrix();
        totalWidth = width - textOffset;
        GlStateManager.scale(totalWidth / width, 1F, 1F);
        if (skin.hasGrid) {
            GlStateManager.color(channel(skin.gridColor, 16), channel(skin.gridColor, 8) * greenTint, channel(skin.gridColor, 0));
            brush.startDrawing(GL11.GL_LINES, DefaultVertexFormats.POSITION);
            for (int i = 0; i < segments; i += verticalLineSpan) {
                double x = i / (segments - 1.0);
                brush.addVertex(x, 0, 0);
                brush.addVertex(x, 1, 0);
            }
            if (horizontalLineDivision > 0) {
                for (int i = 0; i <= horizontalLineDivision; i++) {
                    double y = (double) i / horizontalLineDivision;
                    brush.addVertex(0, y, 0);
                    brush.addVertex(1, y, 0);
                }
            }
            brush.draw();
        }
        GlStateManager.color(red / 255F, green / 255F * greenTint, blue / 255F);
        brush.startDrawing(GL11.GL_LINES, DefaultVertexFormats.POSITION);
        brush.addVertex(0, 0.5, 0);
        brush.addVertex(0.2, 0.5, 0);
        brush.addVertex(0.2, 0.5, 0);
        brush.addVertex(0.4, 1, 0);
        brush.addVertex(0.4, 1, 0);
        brush.addVertex(0.6, 0, 0);
        brush.addVertex(0.6, 0, 0);
        brush.addVertex(0.8, 0.5, 0);
        brush.addVertex(0.8, 0.5, 0);
        brush.addVertex(1, 0.5, 0);
        brush.draw();
        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(renderBox[0], renderBox[1], 0);
        GlStateManager.scale(renderBox[2] - renderBox[0], renderBox[3] - renderBox[1], 1F);
        drawPreview(this == selectedControl ? 0.8F : 1F);
        GlStateManager.popMatrix();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        sampleClient();
        if (dbuf == null) {
            dbuf = GLAllocation.createDirectByteBuffer(64).asDoubleBuffer();
        }

        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX - width / 2F, 0.001F, posY - height / 2F);
        Brush brush = Brush.instance;
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
        GlStateManager.color(0.25F, 0.25F, 0.25F);
        brush.startDrawingQuads();
        brush.addVertexWithUV(-0.1F, 0, height + 0.1F, 0, 1);
        brush.addVertexWithUV(width + 0.1F, 0, height + 0.1F, 1, 1);
        brush.addVertexWithUV(width + 0.1F, 0, -0.1F, 1, 0);
        brush.addVertexWithUV(-0.1F, 0, -0.1F, 0, 0);
        brush.draw();
        GlStateManager.translate(0, 0.001F, 0);
        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;

        // Four clip planes fence the plot area so the trace cannot spill over
        // the frame; upstream does the same rather than computing intersections.
        GL11.glEnable(GL11.GL_CLIP_PLANE0);
        clip(0, new double[]{1, 0, 0, 0});
        GL11.glEnable(GL11.GL_CLIP_PLANE1);
        clip(1, new double[]{-1, 0, 0, width});
        GL11.glEnable(GL11.GL_CLIP_PLANE2);
        clip(2, new double[]{0, 0, 1, 0});
        GL11.glEnable(GL11.GL_CLIP_PLANE3);
        clip(3, new double[]{0, 0, -1, height});

        double minValue = getMinValue();
        double maxValue = getMaxValue();
        GlStateManager.color(channel(skin.bgColor, 16), channel(skin.bgColor, 8), channel(skin.bgColor, 0));
        brush.startDrawingQuads();
        brush.addVertexWithUV(0, 0, height, 0, 1);
        brush.addVertexWithUV(width, 0, height, 1, 1);
        brush.addVertexWithUV(width, 0, 0, 1, 0);
        brush.addVertexWithUV(0, 0, 0, 0, 0);
        brush.draw();
        GlStateManager.translate(0, 0.001F, 0);

        GlStateManager.color(channel(skin.gridColor, 16), channel(skin.gridColor, 8), channel(skin.gridColor, 0));
        double thickness = 0.01;
        brush.startDrawingQuads();
        for (int i = 0; i < values.length; i += verticalLineSpan) {
            double x = i / (values.length - 1.0);
            double sc = (width - textOffset) / width;
            brush.addVertexWithUV(x * width * sc - thickness, 0, height, 0, 1);
            brush.addVertexWithUV(x * width * sc + thickness, 0, height, 1, 1);
            brush.addVertexWithUV(x * width * sc + thickness, 0, 0, 1, 0);
            brush.addVertexWithUV(x * width * sc - thickness, 0, 0, 0, 0);
        }
        brush.draw();

        if (horizontalLineDivision > 0) {
            for (int i = 0; i <= horizontalLineDivision; i++) {
                brush.startDrawingQuads();
                double y = (double) i / horizontalLineDivision;
                brush.addVertexWithUV(0, 0, y * height + thickness, 0, 1);
                brush.addVertexWithUV(width - textOffset, 0, y * height + thickness, 1, 1);
                brush.addVertexWithUV(width - textOffset, 0, y * height - thickness, 1, 0);
                brush.addVertexWithUV(0, 0, y * height - thickness, 0, 0);
                brush.draw();
                double value = maxValue + (minValue - maxValue) / horizontalLineDivision * i;
                GlStateManager.pushMatrix();
                GlStateManager.translate(width - textOffset, 0, y * height);
                GlStateManager.scale(0.02F, 0.02F, 0.02F);
                GlStateManager.rotate(90F, 1F, 0F, 0F);
                int ypos = -3;
                if (i == 0) {
                    ypos = 0;
                }
                if (i == horizontalLineDivision) {
                    ypos = -7;
                }
                font.drawString(String.format("%01." + decimalLength + "f", value), 1, ypos, skin.textColor);
                GlStateManager.color(channel(skin.gridColor, 16), channel(skin.gridColor, 8), channel(skin.gridColor, 0));
                GlStateManager.popMatrix();
                Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.solid);
            }
        }

        GlStateManager.translate(0, 0.001F, 0);
        GlStateManager.color(red / 255F, green / 255F, blue / 255F);
        if (skin.emissive) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GlStateManager.disableLighting();
        }

        // Trace: a quad strip with mitred joins, so the line keeps its width
        // through direction changes instead of thinning at the corners.
        double halfWidth = 0.05;
        brush.startDrawingQuads();
        double sc = (width - textOffset) / width;
        double x0 = -1;
        double y0 = -1;
        double delta0 = 0;
        double value0 = 0;
        double spacing = 1.0 / (values.length - 1.0);
        double upperPointX = 0;
        double upperPointY = 0;
        double lowerPointX = 0;
        double lowerPointY = 0;

        for (int i = 0; i < values.length; i++) {
            double x = spacing * i;
            double y = scaleValue(values[i]);
            double x1 = x * width;
            double y1 = height * (1.0 - y);
            double delta1 = scaleValue(values[i]) - value0;
            if (i == 0) {
                delta1 = 0;
            }

            if (x0 >= 0) {
                double ang1 = Math.atan2(delta1, spacing);
                double up = ang1 + (Math.PI / 2);
                double upX = Math.cos(up) * halfWidth;
                double upY = -Math.sin(up) * halfWidth;
                if (delta1 > delta0) {
                    brush.addVertexWithUV(x0 * sc, 0, y0, 0, 1);
                    brush.addVertexWithUV(lowerPointX, 0, lowerPointY, 1, 1);
                    brush.addVertexWithUV(x0 * sc - upX, 0, y0 - upY, 1, 0);
                    brush.addVertexWithUV(x0 * sc, 0, y0, 0, 0);
                } else if (delta1 < delta0) {
                    brush.addVertexWithUV(x0 * sc, 0, y0, 0, 1);
                    brush.addVertexWithUV(x0 * sc + upX, 0, y0 + upY, 1, 1);
                    brush.addVertexWithUV(upperPointX, 0, upperPointY, 1, 0);
                    brush.addVertexWithUV(x0 * sc, 0, y0, 0, 0);
                }
                brush.addVertexWithUV(x0 * sc - upX, 0, y0 - upY, 0, 1);
                brush.addVertexWithUV(x1 * sc - upX, 0, y1 - upY, 1, 1);
                brush.addVertexWithUV(x1 * sc + upX, 0, y1 + upY, 1, 0);
                lowerPointX = x1 * sc - upX;
                lowerPointY = y1 - upY;
                upperPointX = x1 * sc + upX;
                upperPointY = y1 + upY;
                brush.addVertexWithUV(x0 * sc + upX, 0, y0 + upY, 0, 0);
            }

            delta0 = delta1;
            value0 = scaleValue(values[i]);
            x0 = x1;
            y0 = y1;
        }
        brush.draw();

        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);
        GlStateManager.enableLighting();
        for (int plane = 0; plane < 4; plane++) {
            GL11.glDisable(GL11.GL_CLIP_PLANE0 + plane);
        }
        GlStateManager.color(1F, 1F, 1F);
        GlStateManager.popMatrix();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    @SideOnly(Side.CLIENT)
    private void clip(int plane, double[] equation) {
        dbuf.clear();
        dbuf.put(equation);
        dbuf.flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0 + plane, dbuf);
    }

    private static float channel(int color, int shift) {
        return ((color >> shift) & 0xFF) / 255F;
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
        return new Graph(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);
        if (tag.hasKey("graphData")) {
            NBTTagList list = tag.getTagList("graphData", 5);
            if (list.tagCount() != segments) {
                return;
            }
            segments = list.tagCount();
            rebuildArray();
            for (int i = 0; i < list.tagCount(); i++) {
                values[i] = list.getFloatAt(i);
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (double value : values) {
            list.appendTag(new NBTTagFloat((float) value));
        }
        tag.setTag("graphData", list);
        return super.writeToNBT(tag);
    }

    /** Colour scheme presets. Names are the strings stored in the config map. */
    public static class GraphSkin {
        public final String name;
        public final boolean emissive;
        public final boolean hasGrid;
        public final int bgColor;
        public final int gridColor;
        public final int textColor;

        public static final List<GraphSkin> skins = new ArrayList<>();

        public static final GraphSkin CONSOLE = registerOrGet("Console", true, false, 0x000000, 0x000000, 0x000000);
        public static final GraphSkin LIGHT = registerOrGet("Light", false, true, 0xFFFFFF, 0xCCCCCC, 0xAAAAAA);
        public static final GraphSkin DARK = registerOrGet("Dark", true, true, 0x1A1A1A, 0x3A3A3A, 0xCCCCCC);

        GraphSkin(String name, boolean emissive, boolean hasGrid, int bgColor, int gridColor, int textColor) {
            this.name = name;
            this.emissive = emissive;
            this.hasGrid = hasGrid;
            this.bgColor = bgColor;
            this.gridColor = gridColor;
            this.textColor = textColor;
        }

        public static GraphSkin registerOrGet(String name, boolean emissive, boolean hasGrid,
                                             int bgColor, int gridColor, int textColor) {
            for (GraphSkin s : skins) {
                if (s.name.equals(name)) {
                    return s;
                }
            }
            GraphSkin s = new GraphSkin(name, emissive, hasGrid, bgColor, gridColor, textColor);
            skins.add(s);
            return s;
        }
    }
}

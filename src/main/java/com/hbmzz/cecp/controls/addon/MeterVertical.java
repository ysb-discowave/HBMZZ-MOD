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
import com.hbmzz.cecp.controls.configs.addon.SubElementMeterVertical;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.nio.DoubleBuffer;
import java.util.List;
import java.util.Map;

/**
 * Tall circular gauge with a sweeping needle and a clipped scale.
 *
 * <p>Ported from CE Addon's {@code MeterVertical}. The needle's angle used to be
 * synced by a custom packet each tick; here the client eases towards the synced
 * {@code value}, which is all that packet carried.</p>
 */
public class MeterVertical extends Control {

    private int needleOffset = 0;
    private float max = 1F;
    private float min = -1F;
    private int divisions = 20;
    private int subdivisions = 3;
    private int decimals = 1;
    private boolean clamp = false;

    private float curAngle = 0F;
    private long lastStep;

    private static DoubleBuffer dbuf = null;

    public MeterVertical(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("value", new DataValueFloat(0));
        configMap.put("needleOffset", new DataValueFloat(needleOffset));
        configMap.put("minValue", new DataValueFloat(min));
        configMap.put("maxValue", new DataValueFloat(max));
        configMap.put("decimals", new DataValueFloat(decimals));
        configMap.put("divisions", new DataValueFloat(divisions));
        configMap.put("subdivisions", new DataValueFloat(subdivisions));
        configMap.put("clamp", new DataValueFloat(0));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui, Map<String, DataValue> configs) {
        return new SubElementMeterVertical(gui, configs);
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> entry : configMap.entrySet()) {
            switch (entry.getKey()) {
                case "needleOffset":
                    needleOffset = (int) entry.getValue().getNumber();
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
                case "clamp":
                    clamp = entry.getValue().getBoolean();
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
        return new float[]{1.7F, 5F, 1.25F};
    }

    @Override
    public AxisAlignedBB getBoundingBox() {
        return null;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return AddonRes.meter_v;
    }

    /** Needle easing: up to half the gap per tick, never less than 2 degrees. */
    @SideOnly(Side.CLIENT)
    private void advanceVisual() {
        long now = System.currentTimeMillis();
        if (now - lastStep < 50L) {
            return;
        }
        lastStep = now;
        float val = vars.get("value").getNumber();
        if (clamp) {
            val = min < max ? Math.min(Math.max(val, min), max) : Math.min(Math.max(val, max), min);
        }
        float desired = -getAngleForRatio(ratio(min, max, val));
        float delta = desired - curAngle;
        float threshold = 2F;
        if (Math.abs(delta) <= threshold) {
            curAngle = desired;
        } else {
            curAngle += Math.max(Math.abs(delta / 2.5F), threshold) * Math.signum(delta);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        advanceVisual();
        if (dbuf == null) {
            dbuf = GLAllocation.createDirectByteBuffer(64).asDoubleBuffer();
        }

        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1F, 1F, 1F);
        TextureManager texmg = Minecraft.getMinecraft().getTextureManager();
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        IModelCustom mdl = getModel();
        texmg.bindTexture(AddonRes.solid);
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0, posY);
        boolean uni = font.getUnicodeFlag();
        font.setUnicodeFlag(false);
        GlStateManager.color(0.1F, 0.1F, 0.1F);
        mdl.renderPart("Base");

        // Clip everything to the half-space behind the dial face, so the needle
        // never pokes out of the rim.
        GL11.glEnable(GL11.GL_CLIP_PLANE0);
        dbuf.clear();
        dbuf.put(new double[]{0, 1, 0, 0});
        dbuf.flip();
        GL11.glClipPlane(GL11.GL_CLIP_PLANE0, dbuf);

        GlStateManager.color(1F, 1F, 1F);
        GlStateManager.translate(0, -1.75F, 0);
        GlStateManager.rotate(-needleOffset * 11.25F, 1F, 0F, 0F);
        GlStateManager.translate(0, 1.75F, 0);
        mdl.renderPart("Needle");
        GlStateManager.translate(0, -1.75F, 0);
        GlStateManager.rotate(curAngle, 1F, 0F, 0F);
        GlStateManager.translate(0, 1.75F, 0);
        mdl.renderPart("Meter");

        Brush brush = Brush.instance;
        for (int i0 = 0; i0 <= divisions; i0++) {
            float i = (float) i0 / divisions;
            GlStateManager.pushMatrix();
            GlStateManager.translate(0, -1.75F, 0);
            GlStateManager.rotate(getAngleForRatio(i), 1F, 0F, 0F);
            texmg.bindTexture(AddonRes.solid);
            GlStateManager.color(0, 0, 0);
            int offset = 5;
            int startX = 26 - offset + 1;
            int endX = 18 - offset - 3;
            if (subdivisions > 0 && i0 < divisions) {
                for (int j = 1; j <= subdivisions; j++) {
                    GlStateManager.pushMatrix();
                    int endX2 = 22 - offset - 3;
                    float increment = 1F / divisions / (subdivisions + 1);
                    GlStateManager.rotate(getAngleForRatio(increment * j + 0.5F), 1F, 0F, 0F);
                    GlStateManager.translate(0, 2.9F, 0);
                    GlStateManager.rotate(90F, 1F, 0F, 0F);
                    GlStateManager.scale(0.025F, 0.025F, 0.025F);
                    GlStateManager.translate(0, -0.5F, 0);
                    brush.startDrawingQuads();
                    brush.addVertexWithUV(endX2, 1, 0, 0, 1);
                    brush.addVertexWithUV(startX, 1, 0, 1, 1);
                    brush.addVertexWithUV(startX, 0, 0, 1, 0);
                    brush.addVertexWithUV(endX2, 0, 0, 0, 0);
                    brush.draw();
                    GlStateManager.popMatrix();
                }
            }

            GlStateManager.translate(0, 2.9F, 0);
            GlStateManager.rotate(90F, 1F, 0F, 0F);
            GlStateManager.scale(0.025F, 0.025F, 0.025F);
            GlStateManager.translate(0, -0.5F, 0);
            brush.startDrawingQuads();
            brush.addVertexWithUV(endX, 1, 0, 0, 1);
            brush.addVertexWithUV(startX, 1, 0, 1, 1);
            brush.addVertexWithUV(startX, 0, 0, 1, 0);
            brush.addVertexWithUV(endX, 0, 0, 0, 0);
            brush.draw();
            String s = String.format("%01." + decimals + "f", lerp(min, max, (float) i0 / divisions));
            font.drawString(s, 12 - font.getStringWidth(s), -3, 0);
            GlStateManager.popMatrix();
        }

        font.setUnicodeFlag(uni);
        GL11.glDisable(GL11.GL_CLIP_PLANE0);
        GlStateManager.color(1F, 1F, 1F);
        GlStateManager.popMatrix();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    float getAngleForRatio(float ratio) {
        ratio -= 0.5F;
        return -360F / (divisions + 1F) * ratio * divisions;
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
    public ResourceLocation getGuiTexture() {
        return addonGuiTexture(needleOffset);
    }

    /** Dial face for the current needle offset; the flat face when out of range. */
    public static ResourceLocation addonGuiTexture(int offset) {
        return offset >= -3 && offset <= 3 ? AddonRes.meter_v_gui_tex[offset + 3] : AddonRes.solid;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new MeterVertical(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
    }
}

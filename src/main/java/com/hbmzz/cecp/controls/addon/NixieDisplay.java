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
import com.hbmzz.cecp.compat.CtrlBuf;
import com.hbmzz.cecp.compat.CtrlImm;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.controls.configs.addon.SubElementNixie;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.GlStateManager.DestFactor;
import net.minecraft.client.renderer.GlStateManager.SourceFactor;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;

/**
 * Nixie tube display: a row of {@code length} tubes, each showing one character
 * of {@code value} in ghostly orange.
 *
 * <p>Ported from CE Addon's {@code NixieDisplay}.</p>
 */
public class NixieDisplay extends Control {

    private float[] color = new float[]{1F, 1F, 1F};
    private int length = 1;

    public NixieDisplay(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("value", new DataValueString(""));
        configMap.put("length", new DataValueFloat(length));
    }

    @Override
    @SideOnly(Side.CLIENT)
    public SubElementBaseConfig getConfigSubElement(GuiControlEdit gui, Map<String, DataValue> configs) {
        return new SubElementNixie(gui, configs);
    }

    @Override
    public ControlType getControlType() {
        return ControlType.DISPLAY;
    }

    @Override
    public float[] getSize() {
        return new float[]{1.75F, 2.5F, 0.0625F};
    }

    @Override
    public void fillBox(float[] box) {
        float width = getSize()[0];
        float height = getSize()[1];
        box[0] = posX;
        box[1] = posY;
        box[2] = posX + width + length - 1F;
        box[3] = posY + height;
    }

    @Override
    protected void onConfigMapChanged() {
        for (Map.Entry<String, DataValue> e : configMap.entrySet()) {
            if (e.getKey().equals("length")) {
                length = (int) e.getValue().getNumber();
            }
        }
    }

    @SideOnly(Side.CLIENT)
    void draw(FontRenderer font, float div, String s) {
        GlStateManager.pushMatrix();
        GlStateManager.scale(1F / div, 1F / div, 1F / div);
        GlStateManager.translate(-font.getStringWidth(s) / 2F + 0.5F, -3.5F, 0F);
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
        font.drawString(s, 0, 0, 0xFFA746);
        int col = 0x724615;
        float sc = 0.25F;
        // A stack of offset copies: that is what makes the glow, not a blur pass.
        float[][] offsets = {
                {-sc, 0F, -0.01F}, {sc, 0F, -0.02F},
                {0F, -sc, -0.03F}, {0F, sc, -0.04F},
                {-sc * 2F, 0F, -0.05F}, {sc * 2F, 0F, -0.06F},
                {0F, -sc * 2F, -0.07F}, {0F, sc * 2F, -0.08F},
        };
        for (float[] o : offsets) {
            GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
            GlStateManager.pushMatrix();
            GlStateManager.translate(o[0], o[1], o[2]);
            font.drawString(s, 0, 0, col);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
        float boxMinX = renderBox[0];
        float boxMinY = renderBox[1];
        float boxMaxX = renderBox[2];
        float boxMaxY = renderBox[3];
        int len = (int) getConfigs().get("length").getNumber();
        int packedColor = CtrlBuf.packColor(1F, this == selectedControl ? 0.8F : 1F, 1F, 1F);
        CtrlBuf buf = CtrlImm.INSTANCE.beginPositionTexColorQuads(len);
        float padding = 3F / 8F;
        appendGuiQuad(buf, boxMinX, boxMinY, boxMinX + padding, boxMaxY, 0F, 0F, 0.22222222F, 1F, packedColor);
        appendGuiQuad(buf, boxMaxX - padding, boxMinY, boxMaxX, boxMaxY, 0.7777778F, 0F, 1F, 1F, packedColor);

        for (int i = 0; i < len; i++) {
            float start = len == 1
                    ? boxMinX + padding
                    : lerp(boxMinX + padding, boxMaxX - padding - 1F, (float) i / (len - 1));
            appendGuiQuad(buf, start, boxMinY, start + 1F, boxMaxY, 0.22222222F, 0F, 0.7777778F, 1F, packedColor);
        }
        CtrlImm.INSTANCE.draw();
    }

    static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        TextureManager texmg = Minecraft.getMinecraft().getTextureManager();
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        boolean unicode = font.getUnicodeFlag();
        font.setUnicodeFlag(true);
        IModelCustom mdl = getModel();
        String text = getVar("value").toString();
        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;
        GlStateManager.enableBlend();
        texmg.bindTexture(AddonRes.solid);
        GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);

        for (int i = 0; i < length; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.color(0.1F, 0.1F, 0.1F);
            GlStateManager.translate(posX + i, 0F, posY);
            mdl.renderPart("Border");
            if (i == 0) {
                mdl.renderPart("BorderLeft");
            }
            if (i == length - 1) {
                GlStateManager.pushMatrix();
                GlStateManager.rotate(180F, 0F, 1F, 0F);
                mdl.renderPart("BorderLeft");
                GlStateManager.popMatrix();
            }

            GlStateManager.color(0.5F, 0.5F, 0.5F);
            if (i == 0) {
                mdl.renderPart("Screws");
            }
            if (i == length - 1) {
                GlStateManager.pushMatrix();
                GlStateManager.rotate(180F, 0F, 1F, 0F);
                mdl.renderPart("Screws");
                GlStateManager.popMatrix();
            }

            GlStateManager.color(0.02F, 0.02F, 0.02F);
            mdl.renderPart("Inside");
            if (i == 0) {
                mdl.renderPart("InsideLeft");
            }
            if (i == length - 1) {
                GlStateManager.pushMatrix();
                GlStateManager.rotate(180F, 0F, 1F, 0F);
                mdl.renderPart("InsideLeft");
                GlStateManager.popMatrix();
            }
            GlStateManager.popMatrix();
        }

        for (int i = 0; i < length; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.color(0.1F, 0.1F, 0.1F);
            GlStateManager.translate(posX + i, 0F, posY);
            String s = "";
            if (i < text.length()) {
                s = text.substring(i, i + 1);
            }

            GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
            GlStateManager.pushMatrix();
            GlStateManager.rotate(90F, 1F, 0F, 0F);
            GlStateManager.translate(0, 0, 0.562F);
            GlStateManager.translate(0, -0.1F, 0);
            draw(font, 6.4F, s);
            GlStateManager.popMatrix();
            GlStateManager.blendFunc(SourceFactor.SRC_ALPHA, DestFactor.ONE_MINUS_SRC_ALPHA);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);
            texmg.bindTexture(AddonRes.solid);
            GlStateManager.color(1F, 0.462F, 0.076F, 0.2F);
            mdl.renderPart("Tube");
            GlStateManager.popMatrix();
        }

        texmg.bindTexture(AddonRes.invisible);
        GlStateManager.alphaFunc(GL11.GL_ALWAYS, 0F);

        for (int i = 0; i < length; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(posX + i, 0F, posY);
            mdl.renderPart("ClipPlane");
            GlStateManager.popMatrix();
        }

        GlStateManager.alphaFunc(GL11.GL_GREATER, 0F);
        font.setUnicodeFlag(unicode);
        GlStateManager.disableBlend();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return AddonRes.nixie;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return AddonRes.nixie_gui_tex;
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
        return new NixieDisplay(name, registryName, panel);
    }
}

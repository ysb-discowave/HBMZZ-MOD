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

package com.hbmzz.cecp.controls.configs.addon;

import com.hbmzz.cecp.GuiControlEdit;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.GuiFx;
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.types.DataValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;

/** Config page for the text indicator: colour, caption, preview swatch. */
public class SubElementTextIndicator extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {137, 116, 8, 88};

    private float colorR;
    private float colorG;
    private float colorB;
    private String text;

    GuiSlider slideColorR;
    GuiSlider slideColorG;
    GuiSlider slideColorB;
    GuiTextField textField;

    public SubElementTextIndicator(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui, map);
        this.colorR = map.get("red").getNumber();
        this.colorG = map.get("green").getNumber();
        this.colorB = map.get("blue").getNumber();
        this.text = map.get("text").toString();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "red", colorR);
        putFloatConfig(configs, "green", colorG);
        putFloatConfig(configs, "blue", colorB);
        putStringConfig(configs, "text", text);
    }

    @Override
    public void initGui() {
        int cX = gui.width / 2;
        slideColorR = gui.addButton(new GuiSlider(gui.currentButtonId(), cX - 85, gui.getGuiTop() + 70,
                80, 15, TextFormatting.RED + "R", "", 0, 255, colorR, false, true));
        slideColorG = gui.addButton(new GuiSlider(gui.currentButtonId(), cX - 85, gui.getGuiTop() + 90,
                80, 15, TextFormatting.GREEN + "G", "", 0, 255, colorG, false, true));
        slideColorB = gui.addButton(new GuiSlider(gui.currentButtonId(), cX - 85, gui.getGuiTop() + 110,
                80, 15, TextFormatting.BLUE + "B", "", 0, 255, colorB, false, true));
        textField = new GuiTextField(gui.currentButtonId(), Minecraft.getMinecraft().fontRenderer,
                cX + 10, gui.getGuiTop() + 70, 100, 30);
        textField.setText(text);
        super.initGui();
    }

    @Override
    protected void update() {
        super.update();
        textField.updateCursorCounter();
        text = textField.getText();
    }

    @Override
    protected void drawScreen() {
        GlStateManager.disableLighting();
        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.white);
        GuiFx.drawGuiRectColor(gui.getGuiLeft() + 20, gui.getGuiTop() + 70, 0, 0, 15, 15, 1, 1,
                colorR / 255F, colorG / 255F, colorB / 255F, 1F);
        GlStateManager.enableLighting();
        textField.drawTextBox();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        textField.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        super.keyTyped(typedChar, keyCode);
        textField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        colorR = slideColorR.getValueInt();
        colorG = slideColorG.getValueInt();
        colorB = slideColorB.getValueInt();
    }

    @Override
    public void enableButtons(boolean enable) {
        slideColorR.visible = enable;
        slideColorR.enabled = enable;
        slideColorG.visible = enable;
        slideColorG.enabled = enable;
        slideColorB.visible = enable;
        slideColorB.enabled = enable;
        textField.setEnabled(enable);
        textField.setVisible(enable);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

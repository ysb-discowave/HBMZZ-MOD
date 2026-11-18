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

package com.hbmzz.cecp.controls.configs;

import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.GuiControlEdit;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.GuiFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;

public class SubElementLabel extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {137, 116, 8, 88};
    private float colorR;
    private float colorG;
    private float colorB;
    private String text;
    private int scale;

    GuiSlider slideColorR;
    GuiSlider slideColorG;
    GuiSlider slideColorB;
    GuiTextField textField;
    GuiSlider slideScale;

    public SubElementLabel(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui,map);
        this.colorR = map.get("colorR").getNumber();
        this.colorG = map.get("colorG").getNumber();
        this.colorB = map.get("colorB").getNumber();
        this.text = map.get("text").toString();
        this.scale = (int) map.get("scale").getNumber();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "colorR", colorR);
        putFloatConfig(configs, "colorG", colorG);
        putFloatConfig(configs, "colorB", colorB);
        putStringConfig(configs, "text", text);
        putFloatConfig(configs, "scale", scale);
    }

    @Override
    public void initGui() {
        int cX = gui.width/2;
        int cY = gui.height/2;

        slideColorR = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+70, 80, 15, TextFormatting.RED+"R ", "", 0, 100, colorR*100, false, true));
        slideColorG = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+90, 80, 15, TextFormatting.GREEN+"G ", "", 0, 100, colorG*100, false, true));
        slideColorB = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+110, 80, 15, TextFormatting.BLUE+"B ", "", 0, 100, colorB*100, false, true));
        slideScale = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+140, 80, 15, "缩放 ", "", 10, 100, scale, false, true));
        textField = new GuiTextField(gui.currentButtonId(), Minecraft.getMinecraft().fontRenderer, cX+10, gui.getGuiTop()+70, 100, 30);
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
        gui.mc.getTextureManager().bindTexture(CtrlRes.white);
        GuiFx.drawGuiRectColor(gui.getGuiLeft()+20, gui.getGuiTop()+70, 0, 0, 15, 15, 1, 1, colorR, colorG, colorB, 1F);
        GlStateManager.enableLighting();

        textField.drawTextBox();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        this.textField.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        super.keyTyped(typedChar, keyCode);
        this.textField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        colorR = (float) slideColorR.sliderValue;
        colorG = (float) slideColorG.sliderValue;
        colorB = (float) slideColorB.sliderValue;
        scale = slideScale.getValueInt();
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
        slideScale.visible = enable;
        slideScale.enabled = enable;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

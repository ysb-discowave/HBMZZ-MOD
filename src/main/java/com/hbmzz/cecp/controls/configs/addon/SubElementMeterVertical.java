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
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.types.DataValue;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.config.GuiCheckBox;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Config page for the vertical meter: dial face, range, ticks, clamping. */
public class SubElementMeterVertical extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {40, 112, 88, 88};

    private int needleOffset = 0;
    private float max = 1F;
    private float min = -1F;
    private int divisions = 20;
    private int subdivisions = 3;
    private int decimals = 1;
    private boolean clamp = false;

    GuiSlider sliderNeedleOffset;
    GuiTextField fieldMax;
    GuiTextField fieldMin;
    GuiSlider sliderDiv;
    GuiSlider sliderSubd;
    GuiSlider sliderDecimals;
    GuiCheckBox checkClamp;

    final List<GuiButton> buttons = new ArrayList<>();

    public SubElementMeterVertical(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui, map);
        this.needleOffset = (int) map.get("needleOffset").getNumber();
        this.min = map.get("minValue").getNumber();
        this.max = map.get("maxValue").getNumber();
        this.decimals = (int) map.get("decimals").getNumber();
        this.divisions = (int) map.get("divisions").getNumber();
        this.subdivisions = (int) map.get("subdivisions").getNumber();
        this.clamp = map.get("clamp").getBoolean();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "needleOffset", needleOffset);
        putFloatConfig(configs, "minValue", min);
        putFloatConfig(configs, "maxValue", max);
        putFloatConfig(configs, "decimals", decimals);
        putFloatConfig(configs, "divisions", divisions);
        putFloatConfig(configs, "subdivisions", subdivisions);
        putFloatConfig(configs, "clamp", clamp ? 1F : 0F);
    }

    <T extends GuiButton> T add(T button) {
        gui.addButton(button);
        buttons.add(button);
        return button;
    }

    @Override
    public void initGui() {
        int cx = gui.width / 2;
        int top = gui.getGuiTop();
        fieldMax = new GuiTextField(1000, gui.getFontRenderer(), cx - 85, top + 70, 80, 15);
        fieldMin = new GuiTextField(1000, gui.getFontRenderer(), cx - 85, top + 90, 80, 15);
        fieldMax.setText(Float.toString(max));
        fieldMin.setText(Float.toString(min));
        sliderNeedleOffset = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 70, 80, 15,
                "指针偏移 ", "", -3, 3, needleOffset, false, true));
        sliderDecimals = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 90, 80, 15,
                "小数位 ", "", 0, 3, decimals, false, true));
        sliderDiv = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 110, 80, 15,
                "分度 ", "", 5, 30, divisions, false, true));
        sliderSubd = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 130, 80, 15,
                "细分 ", "", 0, 10, subdivisions, false, true));
        checkClamp = add(new GuiCheckBox(gui.currentButtonId(), cx - 85 + 95, top + 152,
                "超量程夹紧", clamp));
        super.initGui();
    }

    @Override
    protected void drawScreen() {
        GlStateManager.disableLighting();
        fieldMin.drawTextBox();
        fieldMax.drawTextBox();
        GlStateManager.enableLighting();
    }

    @Override
    protected void keyTyped(char typedChar, int code) {
        fieldMax.textboxKeyTyped(typedChar, code);
        fieldMin.textboxKeyTyped(typedChar, code);
        super.keyTyped(typedChar, code);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        fieldMax.mouseClicked(mouseX, mouseY, button);
        fieldMin.mouseClicked(mouseX, mouseY, button);
        super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        divisions = sliderDiv.getValueInt();
        subdivisions = sliderSubd.getValueInt();
        decimals = sliderDecimals.getValueInt();
        clamp = checkClamp.isChecked();
    }

    @Override
    protected void update() {
        needleOffset = sliderNeedleOffset.getValueInt();
        fieldMax.setTextColor(0xFFFFFF);
        fieldMin.setTextColor(0xFFFFFF);
        try {
            max = Float.parseFloat(fieldMax.getText());
        } catch (NumberFormatException ignored) {
            fieldMax.setTextColor(0xFF0000);
        }
        try {
            min = Float.parseFloat(fieldMin.getText());
        } catch (NumberFormatException ignored) {
            fieldMin.setTextColor(0xFF0000);
        }
        super.update();
    }

    @Override
    public void enableButtons(boolean enable) {
        for (GuiButton button : buttons) {
            button.visible = enable;
            button.enabled = enable;
        }
        fieldMax.setVisible(enable);
        fieldMax.setEnabled(enable);
        fieldMin.setVisible(enable);
        fieldMin.setEnabled(enable);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

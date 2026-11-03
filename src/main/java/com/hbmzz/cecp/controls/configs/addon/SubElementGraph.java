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
import com.hbmzz.cecp.controls.addon.Graph;
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.types.DataValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Config page for the chart: colour, size, sampling, theme. */
public class SubElementGraph extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {60, 167, 44, 44};

    private double width = 6.0;
    private double height = 4.0;
    private int verticalLineSpan = 1;
    private int horizontalLineDivision = 10;
    private int segments = 40;
    private int decimalLength = 2;
    private double textOffset = 0.5;
    private float red = 0F;
    private float green = 255F;
    private float blue = 0F;
    private String skin = "Console";

    GuiSlider sliderRed;
    GuiSlider sliderGreen;
    GuiSlider sliderBlue;
    GuiSlider sliderWidth;
    GuiSlider sliderHeight;
    GuiSlider sliderSegments;
    GuiSlider sliderVspan;
    GuiSlider sliderHdiv;
    GuiSlider sliderDecimals;
    GuiSlider sliderTextOffset;
    GuiButton buttonSkin;

    final List<GuiButton> buttons = new ArrayList<>();

    public SubElementGraph(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui, map);
        this.red = map.get("red").getNumber();
        this.green = map.get("green").getNumber();
        this.blue = map.get("blue").getNumber();
        this.skin = map.get("skin").toString();
        this.segments = (int) map.get("segments").getNumber();
        this.verticalLineSpan = (int) map.get("verticalLineSpan").getNumber();
        this.horizontalLineDivision = (int) map.get("horizontalLineDivision").getNumber();
        this.decimalLength = (int) map.get("decimalLength").getNumber();
        this.textOffset = Math.round(map.get("textOffset").getNumber() * 10F) / 10D;
        this.width = Math.round(map.get("width").getNumber() * 10F) / 10D;
        this.height = Math.round(map.get("height").getNumber() * 10F) / 10D;
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "red", red);
        putFloatConfig(configs, "green", green);
        putFloatConfig(configs, "blue", blue);
        putStringConfig(configs, "skin", skin);
        putFloatConfig(configs, "segments", segments);
        putFloatConfig(configs, "verticalLineSpan", verticalLineSpan);
        putFloatConfig(configs, "horizontalLineDivision", horizontalLineDivision);
        putFloatConfig(configs, "decimalLength", decimalLength);
        putFloatConfig(configs, "textOffset", (float) textOffset);
        putFloatConfig(configs, "width", (float) width);
        putFloatConfig(configs, "height", (float) height);
    }

    <T extends GuiButton> T add(T button) {
        gui.addButton(button);
        buttons.add(button);
        return button;
    }

    private static String themeLabel(String skin) {
        return "主题: " + skin;
    }

    @Override
    public void initGui() {
        int cx = gui.width / 2;
        int top = gui.getGuiTop();
        sliderRed = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 70, 80, 15,
                TextFormatting.RED + "R", "", 0, 255, red, false, true));
        sliderGreen = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 90, 80, 15,
                TextFormatting.GREEN + "G", "", 0, 255, green, false, true));
        sliderBlue = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 110, 80, 15,
                TextFormatting.BLUE + "B", "", 0, 255, blue, false, true));
        sliderWidth = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 130, 80, 15,
                "宽 ", "", 1, 10, width, true, true));
        sliderHeight = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 150, 80, 15,
                "高 ", "", 1, 10, height, true, true));
        sliderSegments = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 70, 80, 15,
                "采样点 ", "", 1, 200, segments, false, true));
        sliderVspan = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 90, 80, 15,
                "竖线间隔 ", "", 1, 20, verticalLineSpan, false, true));
        sliderHdiv = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 110, 80, 15,
                "横线分度 ", "", 0, 20, horizontalLineDivision, false, true));
        sliderDecimals = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 130, 80, 15,
                "小数位 ", "", 0, 5, decimalLength, false, true));
        sliderTextOffset = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 150, 80, 15,
                "文字边距 ", "", 0, 1, textOffset, true, true));
        buttonSkin = add(new GuiButton(gui.currentButtonId(), cx - 85 + 95, top + 170, 80, 20,
                themeLabel(skin)));
        super.initGui();
    }

    @Override
    protected void drawScreen() {
        GlStateManager.disableLighting();
        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.white);
        GuiFx.drawGuiRectColor(gui.getGuiLeft() + 20, gui.getGuiTop() + 70, 0, 0, 15, 15, 1, 1,
                red / 255F, green / 255F, blue / 255F, 1F);
        GlStateManager.enableLighting();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        red = sliderRed.getValueInt();
        green = sliderGreen.getValueInt();
        blue = sliderBlue.getValueInt();
        width = Math.round((float) sliderWidth.getValue() * 10F) / 10D;
        height = Math.round((float) sliderHeight.getValue() * 10F) / 10D;
        segments = sliderSegments.getValueInt();
        verticalLineSpan = sliderVspan.getValueInt();
        horizontalLineDivision = sliderHdiv.getValueInt();
        decimalLength = sliderDecimals.getValueInt();
        textOffset = Math.round((float) sliderTextOffset.getValue() * 10F) / 10D;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == buttonSkin) {
            int index = 0;
            for (Graph.GraphSkin s : Graph.GraphSkin.skins) {
                if (s.name.equals(skin)) {
                    break;
                }
                index++;
            }
            index++;
            if (index >= Graph.GraphSkin.skins.size()) {
                index = 0;
            }
            skin = Graph.GraphSkin.skins.get(index).name;
            buttonSkin.displayString = themeLabel(skin);
        }
    }

    @Override
    public void enableButtons(boolean enable) {
        for (GuiButton button : buttons) {
            button.visible = enable;
            button.enabled = enable;
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

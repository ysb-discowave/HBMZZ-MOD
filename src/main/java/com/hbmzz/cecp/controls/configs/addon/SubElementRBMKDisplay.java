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
import net.minecraftforge.fml.client.config.GuiCheckBox;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;

/** Config page for the RBMK map: how many cells, and which half-tile it starts on. */
public class SubElementRBMKDisplay extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {132, 112, 88, 88};

    private int width;
    private int height;
    private boolean offsetX;
    private boolean offsetY;

    GuiSlider sliderWidth;
    GuiSlider sliderHeight;
    GuiCheckBox checkOffsetX;
    GuiCheckBox checkOffsetY;

    public SubElementRBMKDisplay(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui, map);
        this.width = (int) map.get("width").getNumber();
        this.height = (int) map.get("height").getNumber();
        this.offsetX = map.get("offsetU").getBoolean();
        this.offsetY = map.get("offsetV").getBoolean();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "width", width);
        putFloatConfig(configs, "height", height);
        putFloatConfig(configs, "offsetU", offsetX ? 1F : 0F);
        putFloatConfig(configs, "offsetV", offsetY ? 1F : 0F);
    }

    @Override
    public void initGui() {
        int cx = gui.width / 2;
        sliderWidth = gui.addButton(new GuiSlider(gui.currentButtonId(), cx - 85, gui.getGuiTop() + 70,
                80, 15, "宽 ", "", 1, 27, width, false, true));
        sliderHeight = gui.addButton(new GuiSlider(gui.currentButtonId(), cx - 85, gui.getGuiTop() + 90,
                80, 15, "高 ", "", 1, 27, height, false, true));
        checkOffsetX = gui.addButton(new GuiCheckBox(gui.currentButtonId(), cx + 10, gui.getGuiTop() + 72,
                "X 半格偏移", offsetX));
        checkOffsetY = gui.addButton(new GuiCheckBox(gui.currentButtonId(), cx + 10, gui.getGuiTop() + 92,
                "Y 半格偏移", offsetY));
        super.initGui();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        width = sliderWidth.getValueInt();
        height = sliderHeight.getValueInt();
        offsetX = checkOffsetX.isChecked();
        offsetY = checkOffsetY.isChecked();
    }

    @Override
    public void enableButtons(boolean enable) {
        sliderWidth.visible = enable;
        sliderWidth.enabled = enable;
        sliderHeight.visible = enable;
        sliderHeight.enabled = enable;
        checkOffsetX.visible = enable;
        checkOffsetX.enabled = enable;
        checkOffsetY.visible = enable;
        checkOffsetY.enabled = enable;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

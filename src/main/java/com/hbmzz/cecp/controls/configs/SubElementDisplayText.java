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
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;

public class SubElementDisplayText extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {149, 64, 88, 88};
    private int scale;
    private int width;

    GuiSlider slideScale;
    GuiSlider slideWidth;

    public SubElementDisplayText(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui,map);
        this.scale = (int) map.get("scale").getNumber();
        this.width = (int) map.get("width").getNumber();
    }

    @Override
    public void initGui() {
        int cX = gui.width/2;
        slideScale = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+70, 80, 15, "缩放 ", "", 10, 100, scale, false, true));
        slideWidth = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+90, 80, 15, "宽度 ", "", 10, 100, width, false, true));
        super.initGui();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "scale", scale);
        putFloatConfig(configs, "width", width);
    }

    @Override
    public void mouseReleased(int mX, int mY, int state) {
        scale = slideScale.getValueInt();
        width = slideWidth.getValueInt();
    }

    @Override
    public void enableButtons(boolean enable) {
        slideScale.visible = enable;
        slideScale.enabled = enable;
        slideWidth.visible = enable;
        slideWidth.enabled = enable;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

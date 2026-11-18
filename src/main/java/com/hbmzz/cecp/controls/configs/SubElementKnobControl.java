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

public class SubElementKnobControl extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {149, 64, 88, 88};
    private int positions;
    GuiSlider slidePositions;

    public SubElementKnobControl(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui,map);
        this.positions = (int) map.get("positions").getNumber();
    }

    @Override
    public void initGui() {
        int cX = gui.width/2;
        slidePositions = gui.addButton(new GuiSlider(gui.currentButtonId(), cX-85, gui.getGuiTop()+70, 80, 15, "档位 ", "", 1, 11, positions, false, true));
        super.initGui();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "positions", positions);
    }

    @Override
    public void mouseReleased(int mX, int mY, int state) {
        positions = slidePositions.getValueInt();
    }

    @Override
    public void enableButtons(boolean enable) {
        slidePositions.visible = enable;
        slidePositions.enabled = enable;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

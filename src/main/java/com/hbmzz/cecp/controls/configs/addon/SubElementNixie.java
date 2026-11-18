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
import com.hbmzz.cecp.controls.configs.SubElementBaseConfig;
import com.hbmzz.cecp.types.DataValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Map;

/** Config page for the nixie display: how many tubes. */
public class SubElementNixie extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {132, 112, 88, 88};
    private int length;
    GuiSlider slideLength;

    public SubElementNixie(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui, map);
        this.length = (int) map.get("length").getNumber();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "length", length);
    }

    @Override
    public void initGui() {
        int cX = gui.width / 2;
        slideLength = gui.addButton(new GuiSlider(gui.currentButtonId(), cX + 10, gui.getGuiTop() + 70, 75, 15,
                "位数 ", "", 0, 8, length, false, true));
        super.initGui();
    }

    @Override
    protected void drawScreen() {
        GlStateManager.disableLighting();
        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.white);
        GlStateManager.enableLighting();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        length = slideLength.getValueInt();
    }

    @Override
    public void enableButtons(boolean enable) {
        slideLength.visible = enable;
        slideLength.enabled = enable;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

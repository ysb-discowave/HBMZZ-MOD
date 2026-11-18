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

package com.hbmzz.cecp;

import com.hbmzz.cecp.controls.configs.*;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.GuiFx;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SubElementItemConfig extends SubElement {
    public static ResourceLocation bg_tex = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/gui_base.png");
    private final float[] previewBox = new float[4];

    public GuiButton btn_done;
    public GuiButton btn_next;
    public GuiButton btn_prev;

    public List<String> variants = Collections.emptyList();
    private int curr_variant = 0;
    private int num_variants = 1;

    SubElementBaseConfig config_gui;
    private Map<String,DataValue> configs;

    public SubElementItemConfig(GuiControlEdit gui) {
        super(gui);
    }

    @Override
    protected void initGui() {
        int cX = gui.width/2;
        int cY = gui.height/2;
        btn_prev = gui.addButton(new GuiButton(gui.currentButtonId(), cX-30, gui.getGuiTop()+24, 15, 20, "<"));
        btn_next = gui.addButton(new GuiButton(gui.currentButtonId(), cX+15, gui.getGuiTop()+24, 15, 20, ">"));
        btn_done = gui.addButton(new GuiButton(gui.currentButtonId(), cX-85, cY+92, 170, 20, "完成"));

        this.config_gui = new SubElementDisplaySevenSeg(gui, ControlRegistry.registry.get("display_7seg").getConfigs());
        this.config_gui.initGui();
        super.initGui();
    }

    Control last_control = null;
    Map<String, DataValue> existing_configs;

    @Override
    protected void drawScreen() {
        int cX = gui.width/2;
        int gLeft = gui.getGuiLeft();
        int gTop = gui.getGuiTop();

        num_variants = variants.size()-1;

        if (gui.isEditMode) {
            existing_configs = gui.currentEditControl.getConfigs();
            curr_variant = variants.indexOf(gui.currentEditControl.registryName);
        }

        if (curr_variant < 0 || curr_variant > variants.size()-1)
            curr_variant = 0;
        Control variant = ControlRegistry.getNew(variants.get(curr_variant), gui.control.panel);

        boolean canChangeVariant = !gui.isEditMode && num_variants > 0;
        btn_prev.visible = canChangeVariant && curr_variant > 0;
        btn_prev.enabled = btn_prev.visible;
        btn_next.visible = canChangeVariant && curr_variant < num_variants;
        btn_next.enabled = btn_next.visible;

        String text = variant.name;
        int text_width = gui.getFontRenderer().getStringWidth(text);
        gui.getFontRenderer().drawString(text, (cX-(text_width/2F))+0, gTop+11, 0xFF777777, false);

        text = (curr_variant+1) + "/" + (num_variants+1);
        text_width = gui.getFontRenderer().getStringWidth(text);
        gui.getFontRenderer().drawString(text, (cX-(text_width/2F))+0, gTop+30, 0xFF777777, false);

        if (last_control == null || !variant.name.equals(last_control.name)) {
            this.config_gui.enableButtons(false);
            Control control = ControlRegistry.registry.get(variants.get(curr_variant));
            if (control != null)
                this.config_gui = control.getConfigSubElement(gui,(gui.isEditMode) ? existing_configs : control.getConfigs());
            if (!gui.isEditMode) {
                gui.currentEditControl = variant;
            }

            this.config_gui.initGui();
            this.config_gui.enableButtons(true);

           variants = ControlRegistry.getAllControlsOfType(gui.currentEditControl.getControlType());
        }
        int[] previewRect = this.config_gui.getPreviewTransform();
        renderPreview(gLeft + previewRect[0], gTop + previewRect[1], previewRect[2], previewRect[3], variant);
        this.config_gui.drawScreen();

        this.last_control = variant;
    }

    @Override
    protected void renderBackground() {
        gui.mc.getTextureManager().bindTexture(bg_tex);
        gui.drawTexturedModalRect(gui.getGuiLeft(), gui.getGuiTop(), 0, 0, gui.getXSize(), gui.getYSize());
    }

    @Override
    protected void update() {
        config_gui.update();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        config_gui.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        config_gui.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        config_gui.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == btn_done) {
            configs = config_gui.getConfigs();
            gui.currentEditControl.applyConfigs(configs);
            gui.linker.reloadLinkedFromCurrentEditControl();

            config_gui.enableButtons(false);
            last_control = null;
            gui.pushElement(gui.linker);
        }
        else if (button == btn_next) {
            curr_variant = Math.min(num_variants, curr_variant+1);
        }
        else if (button == btn_prev) {
            curr_variant = Math.max(0, curr_variant-1);
        }
    }

    @Override
    protected void enableButtons(boolean enable) {
        btn_done.visible = enable;
        btn_done.enabled = enable;
        if (!enable) {
            btn_next.visible = false;
            btn_next.enabled = false;
            btn_prev.visible = false;
            btn_prev.enabled = false;
        }
    }

    private void renderPreview(int x, int y, int width, int height, Control variant) {
        Map<String, DataValue> previewConfigs = variant.getConfigs();
        config_gui.fillConfigs(previewConfigs);
        variant.refreshConfigs();
        variant.posX = 0;
        variant.posY = 0;

        GlStateManager.disableLighting();
        gui.mc.getTextureManager().bindTexture(CtrlRes.white);
        GuiFx.drawGuiRectColor(x, y, 0, 0, width, height, 1, 1, 0.09F, 0.11F, 0.10F, 0.85F);
        GuiFx.drawGuiRectColor(x + 1, y + 1, 0, 0, width - 2, height - 2, 1, 1, 0.16F, 0.18F, 0.17F, 0.95F);
        GlStateManager.enableLighting();

        variant.fillBox(previewBox);
        float boxWidth = Math.max(previewBox[2] - previewBox[0], 0.1F);
        float boxHeight = Math.max(previewBox[3] - previewBox[1], 0.1F);
        float scale = Math.min((width - 12F) / boxWidth, (height - 12F) / boxHeight);
        float renderX = x + (width - boxWidth * scale) / 2F - previewBox[0] * scale;
        float renderY = y + (height - boxHeight * scale) / 2F - previewBox[1] * scale;

        GlStateManager.pushMatrix();
        GlStateManager.translate(renderX, renderY, 0);
        GlStateManager.scale(scale, scale, 1F);
        gui.placement.renderControl(variant);
        GlStateManager.popMatrix();
    }
}

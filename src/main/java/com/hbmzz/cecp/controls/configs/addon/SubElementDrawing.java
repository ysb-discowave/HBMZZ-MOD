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
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.fml.client.config.GuiCheckBox;
import net.minecraftforge.fml.client.config.GuiSlider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Config page for the boxed label: colour, size, caption, text scale. */
public class SubElementDrawing extends SubElementBaseConfig {

    private static final int[] TRANSFORM = {132, 112, 88, 88};

    private double width = 0.2;
    private double height = 0.2;
    private int red = 100;
    private int green = 100;
    private int blue = 100;
    private int scale = 25;
    private boolean whiteText = false;
    private String text = "";

    GuiSlider sliderRed;
    GuiSlider sliderGreen;
    GuiSlider sliderBlue;
    GuiSlider sliderWidth;
    GuiSlider sliderHeight;
    GuiSlider sliderScale;
    GuiCheckBox checkWhite;
    GuiTextField textField;

    final List<GuiButton> buttons = new ArrayList<>();

    public SubElementDrawing(GuiControlEdit gui, Map<String, DataValue> map) {
        super(gui, map);
        this.red = (int) map.get("red").getNumber();
        this.green = (int) map.get("green").getNumber();
        this.blue = (int) map.get("blue").getNumber();
        this.width = Math.round(map.get("width").getNumber() * 10F) / 10D;
        this.height = Math.round(map.get("height").getNumber() * 10F) / 10D;
        this.scale = (int) map.get("scale").getNumber();
        this.whiteText = map.get("white").getBoolean();
        this.text = map.get("text").toString();
    }

    @Override
    public void fillConfigs(Map<String, DataValue> configs) {
        putFloatConfig(configs, "red", red);
        putFloatConfig(configs, "green", green);
        putFloatConfig(configs, "blue", blue);
        putFloatConfig(configs, "width", (float) width);
        putFloatConfig(configs, "height", (float) height);
        putFloatConfig(configs, "white", whiteText ? 1F : 0F);
        putFloatConfig(configs, "scale", scale);
        putStringConfig(configs, "text", text);
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
        sliderRed = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 70, 80, 15,
                TextFormatting.RED + "R", "", 0, 100, red, false, true));
        sliderGreen = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 90, 80, 15,
                TextFormatting.GREEN + "G", "", 0, 100, green, false, true));
        sliderBlue = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 110, 80, 15,
                TextFormatting.BLUE + "B", "", 0, 100, blue, false, true));
        sliderWidth = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 70, 80, 15,
                "宽 ", "", 0.1, 10, width, true, true));
        sliderHeight = add(new GuiSlider(gui.currentButtonId(), cx - 85 + 95, top + 90, 80, 15,
                "高 ", "", 0.1, 10, height, true, true));
        sliderScale = add(new GuiSlider(gui.currentButtonId(), cx - 85, top + 140, 80, 15,
                "文字缩放 ", "", 10, 100, scale, false, true));
        checkWhite = add(new GuiCheckBox(gui.currentButtonId(), cx - 85, top + 197,
                "白色文字", whiteText));
        textField = new GuiTextField(gui.currentButtonId(), Minecraft.getMinecraft().fontRenderer,
                cx - 85 - 20, top + 160, 100, 30);
        textField.setText(text);
        super.initGui();
    }

    @Override
    protected void drawScreen() {
        GlStateManager.disableLighting();
        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.white);
        GuiFx.drawGuiRectColor(gui.getGuiLeft() + 20, gui.getGuiTop() + 70, 0, 0, 15, 15, 1, 1,
                red / 100F, green / 100F, blue / 100F, 1F);
        GlStateManager.enableLighting();
        textField.drawTextBox();
    }

    @Override
    protected void update() {
        super.update();
        textField.updateCursorCounter();
        text = textField.getText();
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
        red = sliderRed.getValueInt();
        green = sliderGreen.getValueInt();
        blue = sliderBlue.getValueInt();
        width = Math.round((float) sliderWidth.getValue() * 10F) / 10D;
        height = Math.round((float) sliderHeight.getValue() * 10F) / 10D;
        scale = sliderScale.getValueInt();
        whiteText = checkWhite.isChecked();
    }

    @Override
    public void enableButtons(boolean enable) {
        for (GuiButton button : buttons) {
            button.visible = enable;
            button.enabled = enable;
        }
        textField.setEnabled(enable);
        textField.setVisible(enable);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int[] getPreviewTransform() {
        return TRANSFORM;
    }
}

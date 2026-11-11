/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
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

package com.rsaudio.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * 一个滑条按钮。
 * <p>
 * 原版 {@code GuiOptionSlider} 绑死在 {@code GameSettings} 上，没法拿来调自定义值，
 * 所以这里照它的长相写一个 —— 纹理坐标（滑块 0,66 与 196,66）就是原版那两张，
 * 这样它看起来和游戏的其它滑条完全一样。
 * <p>
 * 值以归一化的 0..1 存放在 {@link #sliderValue}，显示文本由子类/调用方格式化。
 */
@SideOnly(Side.CLIENT)
public class GuiSlider extends GuiButton {

    private float sliderValue;
    private boolean dragging;

    public GuiSlider(int id, int x, int y, int width, int height, String label, float initial) {
        super(id, x, y, width, height, label);
        this.sliderValue = clamp(initial);
        this.displayString = label + ": " + percent();
    }

    public float getValue() {
        return sliderValue;
    }

    /** 直接设定（0..1），会同步显示文本但不触发拖动状态 */
    public void setValue(float value) {
        this.sliderValue = clamp(value);
        this.displayString = baseLabel() + ": " + percent();
    }

    public void setValueSilently(float value) {
        this.sliderValue = clamp(value);
    }

    /** 拖动中回调：调用方在这里把值写回自己的模型 */
    protected void onValueChanged() {
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (!this.enabled || !this.visible) {
            return false;
        }
        if (super.mousePressed(mc, mouseX, mouseY)) {
            this.dragging = true;
            dragTo(mouseX);
            return true;
        }
        return false;
    }

    @Override
    protected void mouseDragged(Minecraft mc, int mouseX, int mouseY) {
        if (this.visible && this.dragging) {
            dragTo(mouseX);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY) {
        this.dragging = false;
    }

    public boolean isDragging() {
        return dragging;
    }

    private void dragTo(int mouseX) {
        float relative = (float) (mouseX - (this.x + 4)) / (float) (this.width - 8);
        this.sliderValue = clamp(relative);
        this.displayString = baseLabel() + ": " + percent();
        onValueChanged();
    }

    /** 显示用百分比，四舍五入到整数 */
    private String percent() {
        return Math.round(this.sliderValue * 100.0F) + "%";
    }

    private String baseLabel() {
        // displayString 一直是"label: xx%"，取冒号前那一段就是标签本身
        int colon = this.displayString == null ? -1 : this.displayString.lastIndexOf(':');
        return colon <= 0 ? "" : this.displayString.substring(0, colon);
    }

    @Override
    public void drawButton(Minecraft mc, int mouseX, int mouseY, float partialTicks) {
        if (!this.visible) {
            return;
        }
        mc.getTextureManager().bindTexture(BUTTON_TEXTURES);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        this.hovered = mouseX >= this.x && mouseY >= this.y
                && mouseX < this.x + this.width && mouseY < this.y + this.height;
        int state = getHoverState(this.hovered);
        // 底槽：原版滑条就是两段按钮纹理拼起来的
        drawTexturedModalRect(this.x, this.y, 0, 46 + state * 20, this.width / 2, this.height);
        drawTexturedModalRect(this.x + this.width / 2, this.y, 200 - this.width / 2,
                46 + state * 20, this.width / 2, this.height);

        int knobX = this.x + (int) (this.sliderValue * (float) (this.width - 8));
        drawTexturedModalRect(knobX, this.y, 0, 66, 4, this.height);
        drawTexturedModalRect(knobX + 4, this.y, 196, 66, 4, this.height);

        int color = this.enabled ? 14737632 : 10526880;
        drawCenteredString(mc.fontRenderer, this.displayString,
                this.x + this.width / 2, this.y + (this.height - 8) / 2, color);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value)) {
            return 0.0F;
        }
        return value < 0.0F ? 0.0F : (value > 1.0F ? 1.0F : value);
    }
}

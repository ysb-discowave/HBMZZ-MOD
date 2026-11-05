/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * Lives in the package ported from NTM: Cursed Edition's
 * com.hbm.inventory.control_panel, and is built on its Control/ControlEvent
 * API - but this file itself is new work, not a port. See NOTICE.md.
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

package com.hbmzz.cecp.controls.addon;

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.GuiControlEdit;
import com.hbmzz.cecp.compat.CtrlBuf;
import com.hbmzz.cecp.compat.CtrlImm;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.List;

/**
 * A terminal on the ported console: a plate that, when clicked, opens the
 * command window for the machines this control is linked to.
 *
 * <p>It is a normal panel control in every other respect - it is placed from
 * the same menu, moved the same way, and linked with the same linker tab. What
 * it adds is that a link tag stops being just a label for a node and becomes
 * something you can type at: {@code use 反应堆}, {@code read}, {@code set level
 * 40}.</p>
 *
 * <p>The target is kept in this control's own variable map, so it is saved with
 * the panel and restored on load without a second storage path. The value is an
 * index into the sorted link tags; if the tags change, an out-of-range index
 * simply falls back to the first machine rather than pointing at a stranger.</p>
 */
public class TerminalControl extends Control {

    /** Registry name, and therefore part of every saved panel. */
    public static final String REGISTRY = "terminal";

    public static final String VAR_TARGET = "target";

    public TerminalControl(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put(VAR_TARGET, new DataValueFloat(-1));
    }

    @Override
    public ControlType getControlType() {
        return AddonControlType.TERMINAL;
    }

    /**
     * Two blocks wide and a bit over one tall: enough for three lines of text at
     * the scale below, small enough to sit beside a knob.
     */
    @Override
    public float[] getSize() {
        return new float[] {2.0F, 1.2F, 0.12F};
    }

    /** Target index as saved, or -1. */
    public int getTarget() {
        DataValue value = vars.get(VAR_TARGET);
        return value == null ? -1 : (int) value.getNumber();
    }

    public void setTarget(int slot) {
        vars.put(VAR_TARGET, new DataValueFloat(slot));
    }

    /** Link tags in the order the terminal addresses them. */
    public List<String> tags() {
        return com.hbmzz.cecp.TerminalLinks.tagsOf(this);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void render() {
        float[] box = getBox();
        float[] screen = {0.05F, 0.06F, 0.05F};
        int backdrop = CtrlBuf.packColor(screen[0], screen[1], screen[2], 1.0F);

        GlStateManager.pushMatrix();
        GlStateManager.rotate(90, 1, 0, 0);
        GlStateManager.translate(0, 0, -.01F);
        GlStateManager.disableTexture2D();
        CtrlBuf buf = CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
        buf.appendPositionTexColorQuadUnchecked(
                box[0], box[1], -0.01F, 0, 0, backdrop,
                box[0], box[3], -0.01F, 0, 1, backdrop,
                box[2], box[3], -0.01F, 1, 1, backdrop,
                box[2], box[1], -0.01F, 1, 0, backdrop
        );
        CtrlImm.INSTANCE.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.popMatrix();

        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        float scale = 0.02F;
        String target = selectedName();

        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0, posY);
        GlStateManager.translate(0, .03F, 0);
        GlStateManager.scale(scale, -scale, scale);
        GlStateManager.rotate(90, 1, 0, 0);
        GlStateManager.disableLighting();
        font.drawString("终端", 0, 0, 0xFFC24A, false);
        font.drawString("> " + trim(target, 14), 0, 12, 0xE8E2D0, false);
        font.drawString("右键输入", 0, 24, 0x8A7A50, false);
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
        GlStateManager.color(1F, 1F, 1F, 1F);
    }

    /** The current target's tag, or a stand-in when nothing is linked. */
    private String selectedName() {
        List<String> tags = tags();
        if (tags.isEmpty()) {
            return "未连接";
        }
        int index = getTarget();
        if (index < 0 || index >= tags.size()) {
            index = 0;
        }
        return tags.get(index);
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
        // The plate comes from the base: it is the control's gui texture, and it
        // already dims when selected. Only the label is ours.
        super.renderControl(renderBox, selectedControl, gui);
        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        String text = "终端 >_";
        GlStateManager.pushMatrix();
        GlStateManager.translate((renderBox[0] + renderBox[2]) / 2F, (renderBox[1] + renderBox[3]) / 2F, 0F);
        GlStateManager.scale(0.075F, 0.075F, 0.075F);
        font.drawString(text, -font.getStringWidth(text) / 2, -3, 0xFFC24A, false);
        GlStateManager.popMatrix();
    }

    @SideOnly(Side.CLIENT)
    @Override
    public IModelCustom getModel() {
        return CtrlRes.ctrl_display_seven_seg;
    }

    @Override
    public ResourceLocation getGuiTexture() {
        return CtrlRes.white;
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
        // Nothing to wire: the terminal is read and written by an operator, not
        // by the node graph.
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new TerminalControl(name, registryName, panel);
    }

    /** Keeps a tag short enough to sit on the plate. */
    private static String trim(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}

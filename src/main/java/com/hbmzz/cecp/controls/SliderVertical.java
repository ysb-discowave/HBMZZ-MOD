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

package com.hbmzz.cecp.controls;

import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.compat.ObjModel;
import com.hbmzz.cecp.*;
import com.hbmzz.cecp.nodes.*;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SliderVertical extends Control {

    public SliderVertical(String name,String registryName,ControlPanel panel) {
        super(name,registryName, panel);
        vars.put("value", new DataValueFloat(0));
    }

    @Override
    public ControlType getControlType() {
        return ControlType.SLIDER;
    }

    @Override
    public float[] getSize() {
        return new float[] {0.8125F, 2.3125F, .31F};
    }

    @Override
    public void render() {
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.ctrl_slider_vertical_tex);

        ObjModel model = (ObjModel) getModel();
        int position = (int) Math.abs(getVar("value").getNumber()) % 6;

        // --- Base ---
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0.0, posY);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        model.renderPart("base");
        GlStateManager.popMatrix();

        // --- Slider ---
        GlStateManager.disableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0.0, posY - (0.3125F * position));
        GlStateManager.color(51 / 255F, 51 / 255F, 51 / 255F, 1.0F);
        model.renderPart("slider");
        GlStateManager.popMatrix();

        // --- Lights ---
        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);

        GlStateManager.color(0.0F, 1.0F, 0.0F, 1.0F);
        for (int i = 0; i <= position; i++) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(posX, 0.0, posY);
            model.renderPart("light" + i);
            GlStateManager.popMatrix();
        }

        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);

        GlStateManager.enableTexture2D();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return CtrlRes.ctrl_slider_vertical;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return CtrlRes.ctrl_slider_vertical_gui_tex;
    }

    @Override
    public List<String> getOutEvents() {
        return Collections.singletonList("ctrl_press");
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
        NodeSystem ctrl_press = new NodeSystem(this);
        {
            Map<String,DataValue> vars = new HashMap<>(receiveEvents.get(0).vars);
            vars.put("from index", new DataValueFloat(0));
            NodeInput node0 = new NodeInput(170, 100, "Event Data").setVars(vars);
            ctrl_press.addNode(node0);
            NodeGetVar node1 = new NodeGetVar(170, 150, this).setData("value", false);
            ctrl_press.addNode(node1);
            NodeConditional node2 = new NodeConditional(230, 110);
            node2.inputs.get(0).setData(node0, 1, true);
            node2.inputs.get(1).setDefault(new DataValueFloat(-1));
            node2.inputs.get(2).setDefault(new DataValueFloat(1));
            ctrl_press.addNode(node2);
            NodeMath node3 = new NodeMath(290, 130).setData(NodeMath.Operation.ADD);
            node3.inputs.get(0).setData(node2, 0, true);
            node3.inputs.get(1).setData(node1, 0, true);
            ctrl_press.addNode(node3);
            NodeMath node4 = new NodeMath(350, 130).setData(NodeMath.Operation.CLAMP);
            node4.inputs.get(0).setData(node3, 0, true);
            node4.inputs.get(1).setDefault(new DataValueFloat(0));
            node4.inputs.get(2).setDefault(new DataValueFloat(5));
            ctrl_press.addNode(node4);
            NodeSetVar node5 = new NodeSetVar(410, 110, this).setData("value", false);
            node5.inputs.get(0).setData(node4, 0, true);
            ctrl_press.addNode(node5);
        }
        receiveNodeMap.put("ctrl_press", ctrl_press);
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new SliderVertical(name,registryName,panel);
    }

}

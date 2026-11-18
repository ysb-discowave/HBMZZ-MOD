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

package com.hbmzz.cecp.controls.addon;

import com.hbmzz.cecp.Control;
import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.compat.AddonRes;
import com.hbmzz.cecp.compat.IModelCustom;
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.nodes.NodeBuffer;
import com.hbmzz.cecp.nodes.NodeFunction;
import com.hbmzz.cecp.nodes.NodeGetVar;
import com.hbmzz.cecp.nodes.NodeMath;
import com.hbmzz.cecp.nodes.NodeSetVar;
import com.hbmzz.cecp.nodes.addon.NodeSounder;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.List;

/**
 * The big Starbound-style button: lit while pressed, clicks with the wall-button
 * sound.
 *
 * <p>Ported from CE Addon's {@code LargeButton}.</p>
 */
public class LargeButton extends Control {

    public LargeButton(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("isPushed", new DataValueFloat(0));
        vars.put("isLit", new DataValueFloat(0));
    }

    @Override
    public ControlType getControlType() {
        return ControlType.BUTTON;
    }

    @Override
    public float[] getSize() {
        return new float[]{6F, 6F, 0.5F};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return AddonRes.large_button;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1F, 1F, 1F);
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.large_switch_palette);
        IModelCustom mdl = getModel();
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0F, posY);
        GlStateManager.scale(3F, 3F, 3F);
        mdl.renderAllExcept("Circle.001");
        float lX = OpenGlHelper.lastBrightnessX;
        float lY = OpenGlHelper.lastBrightnessY;
        if (!getVar("isLit").getBoolean()) {
            GlStateManager.color(0.5F, 0.5F, 0.5F);
        } else {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240F, 240F);
        }
        if (getVar("isPushed").getBoolean()) {
            GlStateManager.translate(0, -0.125F, 0);
        }
        mdl.renderPart("Circle.001");
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lX, lY);
        GlStateManager.popMatrix();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1F, 1F, 1F);
    }

    @Override
    public List<String> getOutEvents() {
        return Collections.singletonList("ctrl_press");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return AddonRes.large_button_gui_tex;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new LargeButton(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
        NodeSystem ctrl_press = new NodeSystem(this);
        {
            NodeSounder node0 = new NodeSounder(230, 40);
            node0.snd = NodeSounder.NodeSnd.BUTTON;
            ctrl_press.addNode(node0);
            NodeMath nodeBruh = new NodeMath(170, 100).setData(NodeMath.Operation.ADD);
            nodeBruh.inputs.get(0).setDefault(new DataValueFloat(1));
            ctrl_press.addNode(nodeBruh);
            NodeSetVar node2 = new NodeSetVar(230, 140, this).setData("isLit", false);
            node2.inputs.get(0).setData(nodeBruh, 0, true);
            ctrl_press.addNode(node2);
            NodeSetVar node3 = new NodeSetVar(230, 90, this).setData("isPushed", false);
            node3.inputs.get(0).setData(nodeBruh, 0, true);
            ctrl_press.addNode(node3);
        }
        receiveNodeMap.put("ctrl_press", ctrl_press);

        NodeSystem tick = new NodeSystem(this);
        {
            NodeGetVar node0 = new NodeGetVar(170, 100, this).setData("isPushed", false);
            tick.addNode(node0);
            NodeBuffer node1 = new NodeBuffer(230, 120);
            node1.inputs.get(0).setData(node0, 0, true);
            node1.inputs.get(1).setDefault(new DataValueFloat(45));
            tick.addNode(node1);
            NodeFunction node2 = new NodeFunction(290, 130);
            NodeSystem node2_subsystem = new NodeSystem(this);
            {
                node2_subsystem.addNode(new NodeSetVar(290, 90, this).setData("isPushed", false));
                node2_subsystem.addNode(new NodeSetVar(290, 140, this).setData("isLit", false));
            }
            node2.inputs.get(0).setData(node1, 0, true);
            tick.subSystems.put(node2, node2_subsystem);
            tick.addNode(node2);
        }
        receiveNodeMap.put("tick", tick);
    }
}

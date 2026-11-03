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
import com.hbmzz.cecp.compat.ModSounds;
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.nodes.NodeGetVar;
import com.hbmzz.cecp.nodes.NodeMath;
import com.hbmzz.cecp.nodes.NodeSetVar;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.List;

/**
 * Big Starbound-style lever switch. {@code position} 0…100 maps to the throw.
 *
 * <p>Ported from CE Addon's {@code LargeSwitch}. The visual level used to be
 * pushed to nearby clients by a custom packet on every tick; here the client
 * interpolates towards the synced {@code position} value on its own, which is
 * what that packet was for and needs no extra packet type.</p>
 */
public class LargeSwitch extends Control {

    private static final float STEP = 28.571428F;
    private static final long FRAME_MS = 50L;

    private float curLevel = 50F;
    private long lastStep;

    public LargeSwitch(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("position", new DataValueFloat(0));
    }

    @Override
    public ControlType getControlType() {
        return ControlType.SWITCH;
    }

    @Override
    public float[] getSize() {
        return new float[]{6F, 6F, 0.5F};
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IModelCustom getModel() {
        return AddonRes.large_switch;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void render() {
        advanceVisual();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1F, 1F, 1F);
        Minecraft.getMinecraft().getTextureManager().bindTexture(AddonRes.large_switch_palette);
        IModelCustom mdl = getModel();
        GlStateManager.pushMatrix();
        GlStateManager.translate(posX, 0F, posY);
        GlStateManager.scale(3F, 3F, 3F);
        mdl.renderAllExcept("Switch");
        GlStateManager.translate(0, 0.125F, 0);
        GlStateManager.rotate(-65F * (1F - curLevel / 50F), 1F, 0F, 0F);
        GlStateManager.translate(0, -0.125F, 0);
        mdl.renderPart("Switch");
        GlStateManager.popMatrix();
        GlStateManager.shadeModel(GL11.GL_FLAT);
    }

    /** One throw step per game tick, same rate the addon's packet loop produced. */
    @SideOnly(Side.CLIENT)
    private void advanceVisual() {
        long now = System.currentTimeMillis();
        if (now - lastStep < FRAME_MS) {
            return;
        }
        lastStep = now;
        float target = MathHelper.clamp(vars.get("position").getNumber(), 0F, 100F);
        float delta = target - curLevel;
        if (Math.abs(delta) <= STEP) {
            curLevel = target;
        } else {
            curLevel += Math.signum(delta) * STEP;
        }
    }

    @Override
    public void receiveEvent(ControlEvent evt) {
        if (evt.name.equals("ctrl_press")) {
            SoundEvent sound = ModSounds.find("com.leafia.init.LeafiaSoundEvents", "sbWallSwitch");
            if (sound != null) {
                panel.parent.getControlWorld().playSound(null, panel.parent.getControlPos(), sound,
                        SoundCategory.BLOCKS, 0.5F, 1F);
            }
        }
        super.receiveEvent(evt);
    }

    @Override
    public List<String> getOutEvents() {
        return Collections.singletonList("ctrl_press");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public ResourceLocation getGuiTexture() {
        return AddonRes.large_switch_gui_tex;
    }

    @Override
    public Control newControl(ControlPanel panel) {
        return new LargeSwitch(name, registryName, panel);
    }

    @Override
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
        NodeSystem ctrl_press = new NodeSystem(this);
        {
            NodeGetVar node0 = new NodeGetVar(170, 100, this).setData("position", false);
            ctrl_press.addNode(node0);
            NodeMath node1 = new NodeMath(230, 120).setData(NodeMath.Operation.SUB);
            node1.inputs.get(0).setDefault(new DataValueFloat(100));
            node1.inputs.get(1).setData(node0, 0, true);
            ctrl_press.addNode(node1);
            NodeSetVar node2 = new NodeSetVar(290, 130, this).setData("position", false);
            node2.inputs.get(0).setData(node1, 0, true);
            ctrl_press.addNode(node2);
        }
        receiveNodeMap.put("ctrl_press", ctrl_press);
    }
}

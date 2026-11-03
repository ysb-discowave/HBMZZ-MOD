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
import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.nodes.NodeConditional;
import com.hbmzz.cecp.nodes.NodeGetVar;
import com.hbmzz.cecp.nodes.NodeInput;
import com.hbmzz.cecp.nodes.NodeMath;
import com.hbmzz.cecp.nodes.NodeSetVar;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.util.math.MathHelper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base for the two lever instruments: {@code position} 0…100 is the throw, the
 * rendered arm eases towards it one notch per tick.
 *
 * <p>Ported from CE Addon's {@code LeverBase}. Upstream mirrors {@code curLevel}
 * to nearby clients with a custom packet every tick; the client recomputes it
 * here instead, so the addon's packet class is not needed.</p>
 */
public abstract class LeverBase extends Control {

    private static final float STEP = 28.571428F;

    /** Visual arm angle, 0…100. Client-side only. */
    protected float curLevel = 50F;
    private long lastStep;

    public LeverBase(String name, String registryName, ControlPanel panel) {
        super(name, registryName, panel);
        vars.put("position", new DataValueFloat(50));
    }

    @Override
    public ControlType getControlType() {
        return AddonControlType.LEVER;
    }

    @Override
    public List<String> getOutEvents() {
        return Collections.singletonList("ctrl_press");
    }

    @Override
    public void receiveEvent(ControlEvent evt) {
        if (evt.name.equals("tick")) {
            vars.put("position", new DataValueFloat(
                    MathHelper.clamp(vars.get("position").getNumber(), 0F, 100F)));
        }
        super.receiveEvent(evt);
    }

    @SideOnly(Side.CLIENT)
    protected void advanceVisual() {
        long now = System.currentTimeMillis();
        if (now - lastStep < 50L) {
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
    public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
        NodeSystem ctrl_press = new NodeSystem(this);
        {
            Map<String, DataValue> vars = new HashMap<>(receiveEvents.get(0).vars);
            vars.put("from index", new DataValueFloat(0));
            NodeInput node0 = new NodeInput(170, 100, "Event Data").setVars(vars);
            ctrl_press.addNode(node0);
            NodeGetVar node1 = new NodeGetVar(170, 150, this).setData("position", false);
            ctrl_press.addNode(node1);
            NodeConditional node2 = new NodeConditional(230, 110);
            node2.inputs.get(0).setData(node0, 1, true);
            node2.inputs.get(1).setDefault(new DataValueFloat(-10));
            node2.inputs.get(2).setDefault(new DataValueFloat(10));
            ctrl_press.addNode(node2);
            NodeMath node3 = new NodeMath(290, 130).setData(NodeMath.Operation.ADD);
            node3.inputs.get(0).setData(node2, 0, true);
            node3.inputs.get(1).setData(node1, 0, true);
            ctrl_press.addNode(node3);
            NodeSetVar node5 = new NodeSetVar(350, 130, this).setData("position", false);
            node5.inputs.get(0).setData(node3, 0, true);
            ctrl_press.addNode(node5);
        }
        receiveNodeMap.put("ctrl_press", ctrl_press);
    }
}

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

package com.hbmzz.cecp.nodes.addon;

import com.hbmzz.cecp.NodeConnection;
import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A square wave: on for {@code uptime} ticks, off for {@code downtime} ticks.
 *
 * <p>Ported from CE Addon's {@code NodeClock}. Upstream advances the counter
 * from a server tick list so that evaluating the node fifty times in one tick
 * still moves it once. That list is not part of CE, so the same guarantee is
 * made here with a wall clock: one advance per 50 ms, which is a tick. A graph
 * is only ever evaluated on the server thread, and a slow tick makes the clock
 * lag rather than skip.</p>
 */
public class NodeClock extends Node {

    private static final Map<NodeClock, Long> LAST_BEAT = new WeakHashMap<>();

    int internalCounter = 0;

    public NodeClock(float x, float y) {
        super(x, y);
        this.outputs.add(new NodeConnection("Clock Signal", this, this.outputs.size(), false, DataType.NUMBER, new DataValueFloat(0)));
        this.inputs.add(new NodeConnection("Uptime", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(1)));
        this.inputs.add(new NodeConnection("Downtime", this, this.inputs.size(), true, DataType.NUMBER, new DataValueFloat(1)));
        this.evalCache = new DataValue[1];
        this.recalcSize();
    }

    @Override
    public DataValue evaluate(int i) {
        if (this.cacheValid) {
            return this.evalCache[0];
        }
        DataValue d0 = this.inputs.get(0).evaluate();
        DataValue d1 = this.inputs.get(1).evaluate();
        if (d0 == null || d1 == null) {
            return null;
        }
        this.cacheValid = true;
        int uptime = (int) d0.getNumber();
        int downtime = (int) d1.getNumber();
        if (uptime <= 0) {
            return this.evalCache[0] = new DataValueFloat(0);
        }
        if (downtime <= 0) {
            return this.evalCache[0] = new DataValueFloat(1);
        }
        int total = uptime + downtime;
        if (beat(this)) {
            this.internalCounter++;
        }
        this.internalCounter = Math.floorMod(this.internalCounter, total);
        return this.evalCache[0] = new DataValueFloat(this.internalCounter < uptime ? 1 : 0);
    }

    /** True at most once per tick, per node. */
    private static boolean beat(NodeClock node) {
        long now = System.currentTimeMillis() / 50L;
        synchronized (LAST_BEAT) {
            Long last = LAST_BEAT.get(node);
            if (last != null && last.longValue() == now) {
                return false;
            }
            LAST_BEAT.put(node, now);
            return true;
        }
    }

    @Override
    public float[] getColor() {
        return StockNodesRegister.colorUtility;
    }

    @Override
    public String getDisplayName() {
        return "时钟";
    }

    @Override
    public void readFromNBT(NBTTagCompound tag, NodeSystem sys) {
        super.readFromNBT(tag, sys);
        this.internalCounter = tag.getInteger("counter");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys) {
        tag.setString("nodeType", "leafia_clock");
        tag.setInteger("counter", this.internalCounter);
        return super.writeToNBT(tag, sys);
    }
}

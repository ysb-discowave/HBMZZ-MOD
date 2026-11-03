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

package com.hbmzz.cecp.compat;

import com.hbmzz.cecp.ControlEvent;
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import com.hbmzz.ctrl.MachineBridge;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A machine from NTM: Cursed Edition, wearing this mod's {@link IControllable}.
 *
 * <p>NTM machines already speak the exact same three verbs the ported panel
 * speaks - {@code getInEvents}/{@code getQueryData}/{@code receiveEvent} - they
 * just declare them on {@code com.hbm.inventory.control_panel.IControllable}
 * instead of ours. Nothing can be shared between the two: the interfaces live in
 * different jars, one of which is not a compile dependency. So the machine is
 * wrapped instead of reimplemented, and {@link MachineBridge} does the reflective
 * calls.</p>
 *
 * <p>This is what makes the panel useful at all: the node editor's "which machine
 * / which key / which event" dropdowns are built from the machines a control is
 * linked to. With only the console itself linkable, every one of those lists came
 * up empty and the whole node menu looked missing. A wrapper keeps the rest of
 * the ported code untouched - it still sees {@code instanceof IControllable}.</p>
 *
 * <p>Instances are cached per world+position by {@link CPMachines}; equality is
 * by position so a wrapped machine stays a stable identity inside the event
 * system's sets.</p>
 */
public final class BridgedControllable implements IControllable {

    private final World world;
    private final BlockPos pos;

    BridgedControllable(World world, BlockPos pos) {
        this.world = world;
        this.pos = pos.toImmutable();
    }

    private TileEntity tile() {
        if (this.world == null || !this.world.isBlockLoaded(this.pos)) {
            return null;
        }
        return this.world.getTileEntity(this.pos);
    }

    @Override
    public BlockPos getControlPos() {
        return this.pos;
    }

    @Override
    public World getControlWorld() {
        return this.world;
    }

    @Override
    public List<String> getInEvents() {
        TileEntity tile = tile();
        if (tile == null) {
            return Collections.emptyList();
        }
        return MachineBridge.eventsOf(tile);
    }

    @Override
    public List<String> getOutEvents() {
        TileEntity tile = tile();
        if (tile == null) {
            return Collections.emptyList();
        }
        return MachineBridge.outEventsOf(tile);
    }

    @Override
    public Map<String, DataValue> getQueryData() {
        TileEntity tile = tile();
        if (tile == null) {
            return Collections.emptyMap();
        }
        MachineBridge.Sample sample = MachineBridge.sample(tile);
        if (sample == null) {
            return Collections.emptyMap();
        }
        Map<String, DataValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : sample.numbers.entrySet()) {
            out.put(entry.getKey(), new DataValueFloat(entry.getValue().floatValue()));
        }
        for (Map.Entry<String, String> entry : sample.texts.entrySet()) {
            if (!out.containsKey(entry.getKey())) {
                out.put(entry.getKey(), new DataValueString(entry.getValue()));
            }
        }
        return out;
    }

    @Override
    public void receiveEvent(BlockPos from, ControlEvent evt) {
        if (evt == null || this.world == null || this.world.isRemote) {
            return;
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        for (Map.Entry<String, DataValue> entry : evt.vars.entrySet()) {
            DataValue value = entry.getValue();
            if (value == null) {
                continue;
            }
            // Strings go in as strings (a fluid name, a target group); everything
            // else - numbers, booleans, enum ordinals - as a float, which is what
            // NTM's own machines read back out of getNumber()/getEnum().
            if (value.getType() == DataValue.DataType.STRING) {
                vars.put(entry.getKey(), value.toString());
            } else {
                vars.put(entry.getKey(), value.getNumber());
            }
        }
        MachineBridge.fireAll(this.world, this.pos, evt.name, vars);
    }

    /** Readable name for logs and for the linker's row labels. */
    public String describe() {
        TileEntity tile = tile();
        return tile == null ? "（已失效）" : MachineBridge.describe(tile);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BridgedControllable)) {
            return false;
        }
        BridgedControllable that = (BridgedControllable) other;
        return this.pos.equals(that.pos) && this.world == that.world;
    }

    @Override
    public int hashCode() {
        return this.pos.hashCode() * 31 + System.identityHashCode(this.world);
    }

    @Override
    public String toString() {
        return describe() + " " + this.pos;
    }
}

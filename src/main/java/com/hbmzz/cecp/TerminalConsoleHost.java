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

package com.hbmzz.cecp;

import com.hbmzz.cecp.compat.CPMachines;
import com.hbmzz.cecp.controls.addon.TerminalControl;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.ctrl.MachineBridge;
import com.hbmzz.ctrl.TerminalHost;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The terminal's view of a ported console: its slots are the control's linker
 * tags, and every read goes through {@link CPMachines}.
 *
 * <p>This is where the two halves meet. The ported panel already had the right
 * primitives - {@code IControllable.getQueryData()} for reading,
 * {@code receiveEvent} for writing, and a wrapper so NTM's own machines answer
 * the same two calls - but it only ever used them from the node graph. The
 * terminal drives the same primitives directly, which is why it needs no new
 * machine support at all.</p>
 *
 * <p>Vocabulary is snapshotted when the host is built. A command window lives
 * for a minute or two, and re-asking every linked machine for its key list on
 * each keystroke would put a reflective call between the operator and their
 * Tab key. Values are not snapshotted: {@link #read} asks every time.</p>
 */
public class TerminalConsoleHost implements TerminalHost {

    private final ControlPanel panel;
    private final TerminalControl control;
    private final World world;
    private final List<String> tags;
    private final List<BlockPos> positions;

    public TerminalConsoleHost(TerminalControl control, World world) {
        this.control = control;
        this.panel = control == null ? null : control.panel;
        this.world = world;
        this.tags = TerminalLinks.tagsOf(control);
        this.positions = TerminalLinks.positionsOf(control);
    }

    private IControllable machine(int slot) {
        if (slot < 0 || slot >= this.positions.size() || this.world == null) {
            return null;
        }
        return CPMachines.at(this.world, this.positions.get(slot));
    }

    @Override
    public int slots() {
        return this.tags.size();
    }

    @Override
    public String label(int slot) {
        return slot >= 0 && slot < this.tags.size() ? this.tags.get(slot) : "-";
    }

    @Override
    public String name(int slot) {
        if (slot < 0 || slot >= this.positions.size()) {
            return "未连接";
        }
        BlockPos pos = this.positions.get(slot);
        if (machine(slot) == null) {
            return CPMachines.describe(this.world, pos) + "  （离线）";
        }
        return CPMachines.describe(this.world, pos);
    }

    @Override
    public boolean bound(int slot) {
        return slot >= 0 && slot < this.tags.size();
    }

    /**
     * The saved index, or the first machine when it no longer fits.
     *
     * <p>Returning -1 for an out-of-range index would leave the operator with a
     * terminal that has four machines listed and refuses to read any of them;
     * falling back to the first is what they would have clicked anyway.</p>
     */
    @Override
    public int target() {
        if (this.tags.isEmpty()) {
            return -1;
        }
        int index = this.control.getTarget();
        return index < 0 || index >= this.tags.size() ? 0 : index;
    }

    @Override
    public boolean select(int slot) {
        if (!bound(slot) || this.control == null) {
            return false;
        }
        if (this.control.getTarget() == slot) {
            return false;
        }
        this.control.setTarget(slot);
        return true;
    }

    @Override
    public List<String> keys(int slot) {
        IControllable machine = machine(slot);
        if (machine == null) {
            return new ArrayList<String>();
        }
        return new ArrayList<String>(machine.getQueryData().keySet());
    }

    @Override
    public List<String> events(int slot) {
        IControllable machine = machine(slot);
        return machine == null ? new ArrayList<String>() : new ArrayList<String>(machine.getInEvents());
    }

    @Override
    public Map<String, String> vars(int slot) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (slot < 0 || slot >= this.positions.size() || this.world == null) {
            return out;
        }
        TileEntity tile = this.world.getTileEntity(this.positions.get(slot));
        if (tile == null) {
            return out;
        }
        // Only NTM machines publish an event→variable table; a ported console
        // linked to another console has events but no documented variables.
        out.putAll(MachineBridge.varsOf(tile));
        return out;
    }

    @Override
    public Map<String, String> read(int slot) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        IControllable machine = machine(slot);
        if (machine == null) {
            return out;
        }
        for (Map.Entry<String, DataValue> entry : machine.getQueryData().entrySet()) {
            DataValue value = entry.getValue();
            if (value != null) {
                out.put(entry.getKey(), text(value));
            }
        }
        return out;
    }

    @Override
    public int fire(int slot, String event, Map<String, Object> vars) {
        IControllable machine = machine(slot);
        if (machine == null || event == null || event.isEmpty()) {
            return 0;
        }
        ControlEvent evt = new ControlEvent(event);
        for (Map.Entry<String, Object> entry : vars.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Number) {
                evt.setVar(entry.getKey(), ((Number) value).floatValue());
            } else {
                evt.setVar(entry.getKey(), String.valueOf(value));
            }
        }
        BlockPos from = this.panel == null || this.panel.parent == null
                ? this.positions.get(slot) : this.panel.parent.getControlPos();
        machine.receiveEvent(from, evt);
        return 1;
    }

    @Override
    public boolean available() {
        return MachineBridge.available();
    }

    @Override
    public BlockPos pos(int slot) {
        return slot >= 0 && slot < this.positions.size() ? this.positions.get(slot) : null;
    }

    /** Numbers print without a trailing ".0"; everything else as it says. */
    private static String text(DataValue value) {
        if (value.getType() == DataValue.DataType.NUMBER) {
            double number = value.getNumber();
            if (Math.abs(number - Math.rint(number)) < 1.0E-4D) {
                return String.valueOf((long) Math.rint(number));
            }
            return String.format(Locale.ROOT, "%.2f", number);
        }
        return value.toString();
    }
}

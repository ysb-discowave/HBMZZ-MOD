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

package com.hbmzz.ctrl;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A terminal host the client can hold: names and vocabulary, no authority.
 *
 * <p>Completion has to answer a keystroke, so the window cannot ask the server
 * what a machine accepts while someone is typing. It keeps this instead - the
 * slot list and the current target's vocabulary, as last reported by the
 * server. Every reply refreshes it, so the only stale window is between a
 * machine changing its mind and the next command.</p>
 *
 * <p>{@link #read} and {@link #fire} are deliberately inert: the client is not
 * allowed to reach a machine, and the shell only calls those two from the
 * server side. Having them return nothing is more honest than pretending.</p>
 */
public class SnapshotHost implements TerminalHost {

    private final List<String> labels = new ArrayList<String>();
    private final List<String> names = new ArrayList<String>();
    private final List<Boolean> bound = new ArrayList<Boolean>();
    private final List<List<String>> keys = new ArrayList<List<String>>();
    private final List<List<String>> events = new ArrayList<List<String>>();
    private final List<Map<String, String>> vars = new ArrayList<Map<String, String>>();
    private int target = -1;

    public void clear() {
        this.labels.clear();
        this.names.clear();
        this.bound.clear();
        this.keys.clear();
        this.events.clear();
        this.vars.clear();
        this.target = -1;
    }

    public void add(String label, String name, boolean bound) {
        this.labels.add(label == null ? "" : label);
        this.names.add(name == null ? "" : name);
        this.bound.add(Boolean.valueOf(bound));
        this.keys.add(new ArrayList<String>());
        this.events.add(new ArrayList<String>());
        this.vars.add(new LinkedHashMap<String, String>());
    }

    public void vocab(int slot, List<String> keys, List<String> events, Map<String, String> vars) {
        if (slot < 0 || slot >= this.labels.size()) {
            return;
        }
        this.keys.set(slot, keys == null ? new ArrayList<String>() : keys);
        this.events.set(slot, events == null ? new ArrayList<String>() : events);
        this.vars.set(slot, vars == null ? new LinkedHashMap<String, String>() : vars);
    }

    public void target(int slot) {
        this.target = slot;
    }

    @Override
    public int slots() {
        return this.labels.size();
    }

    @Override
    public String label(int slot) {
        return slot >= 0 && slot < this.labels.size() ? this.labels.get(slot) : "-";
    }

    @Override
    public String name(int slot) {
        return slot >= 0 && slot < this.names.size() ? this.names.get(slot) : "";
    }

    @Override
    public boolean bound(int slot) {
        return slot >= 0 && slot < this.bound.size() && this.bound.get(slot).booleanValue();
    }

    @Override
    public int target() {
        return this.target;
    }

    @Override
    public boolean select(int slot) {
        return false;
    }

    @Override
    public List<String> keys(int slot) {
        return slot >= 0 && slot < this.keys.size() ? this.keys.get(slot) : new ArrayList<String>();
    }

    @Override
    public List<String> events(int slot) {
        return slot >= 0 && slot < this.events.size() ? this.events.get(slot) : new ArrayList<String>();
    }

    @Override
    public Map<String, String> vars(int slot) {
        return slot >= 0 && slot < this.vars.size()
                ? this.vars.get(slot) : new LinkedHashMap<String, String>();
    }

    @Override
    public Map<String, String> read(int slot) {
        return new LinkedHashMap<String, String>();
    }

    @Override
    public int fire(int slot, String event, Map<String, Object> vars) {
        return 0;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public BlockPos pos(int slot) {
        return null;
    }
}

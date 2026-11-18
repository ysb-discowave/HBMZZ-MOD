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

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One of the six link slots (A-F): a machine somewhere in the world.
 *
 * <p>The slot indirection is what makes a panel reusable. Controls and rules
 * point at a slot, never at a coordinate, so the same panel - or a copy of it -
 * can be bolted to a different reactor by re-binding four slots instead of
 * editing every control. It also keeps the saved NBT small and legible: a link
 * is eight bytes, and a widget that mentions "slot B" stays eight bytes no
 * matter where B is.</p>
 *
 * <p>{@link #keys} and {@link #events} are a cache of what that machine says it
 * can do, filled by {@link MachineBridge}. They are written to NBT only so the
 * editor's dropdowns work on the client as well - the server overwrites both on
 * the first tick after loading, so what is saved is never trusted, only used to
 * save a round trip.</p>
 */
public class CtrlLink {

    public BlockPos pos;
    /** Human-readable machine name, e.g. "RBMK 控制棒". */
    public String label = "";
    /** Query keys the machine exposes, cached for the editor's dropdown. */
    public final List<String> keys = new ArrayList<String>();
    /** Event names the machine accepts, cached for the editor's dropdown. */
    public final List<String> events = new ArrayList<String>();
    /**
     * Event name to the variable it carries, e.g. {@code rbmk_ctrl_set_level ->
     * level}, for the machines whose vocabulary we know.
     *
     * <p>Rides along with {@link #keys} and {@link #events} for the same reason
     * and under the same rule: it is a cache the server rebuilds on the first
     * round after loading, never a source of truth. The terminal needs it on
     * the client because it has to complete {@code ev rbmk_ctrl_set_level }
     * into {@code level=} without a round trip per keystroke.</p>
     */
    public final Map<String, String> vars = new LinkedHashMap<String, String>();

    public boolean isEmpty() {
        return this.pos == null;
    }

    public void clear() {
        this.pos = null;
        this.label = "";
        this.keys.clear();
        this.events.clear();
        this.vars.clear();
    }

    public void set(BlockPos pos, String label) {
        this.pos = pos;
        this.label = label == null ? "" : label;
    }

    /** Slot letter as shown in the editor. */
    public static String letter(int slot) {
        return slot >= 0 && slot < 6 ? String.valueOf((char) ('A' + slot)) : "-";
    }

    public NBTTagCompound write() {
        NBTTagCompound tag = new NBTTagCompound();
        if (this.pos != null) {
            tag.setLong("p", this.pos.toLong());
            tag.setString("n", this.label);
            // The key/event catalogue rides along so the editor can offer the
            // dropdowns without a second round trip. The server re-derives both
            // within half a second of loading, so a stale copy cannot outlive
            // one tick of play - and an unloaded machine simply produces an
            // empty catalogue, which the editor already handles.
            if (!this.keys.isEmpty()) {
                NBTTagList list = new NBTTagList();
                for (String key : this.keys) {
                    list.appendTag(new NBTTagString(key));
                }
                tag.setTag("kk", list);
            }
            if (!this.events.isEmpty()) {
                NBTTagList list = new NBTTagList();
                for (String event : this.events) {
                    list.appendTag(new NBTTagString(event));
                }
                tag.setTag("ee", list);
            }
            if (!this.vars.isEmpty()) {
                NBTTagCompound vars = new NBTTagCompound();
                for (Map.Entry<String, String> entry : this.vars.entrySet()) {
                    vars.setString(entry.getKey(), entry.getValue());
                }
                tag.setTag("vv", vars);
            }
        }
        return tag;
    }

    public void read(NBTTagCompound tag) {
        this.clear();
        if (tag != null && tag.hasKey("p")) {
            this.pos = BlockPos.fromLong(tag.getLong("p"));
            this.label = tag.getString("n");
            NBTTagList keys = tag.getTagList("kk", 8);
            for (int i = 0; i < keys.tagCount(); i++) {
                this.keys.add(keys.getStringTagAt(i));
            }
            NBTTagList events = tag.getTagList("ee", 8);
            for (int i = 0; i < events.tagCount(); i++) {
                this.events.add(events.getStringTagAt(i));
            }
            NBTTagCompound vars = tag.getCompoundTag("vv");
            for (String event : vars.getKeySet()) {
                this.vars.put(event, vars.getString(event));
            }
        }
    }

    public CtrlLink copy() {
        CtrlLink copy = new CtrlLink();
        copy.pos = this.pos;
        copy.label = this.label;
        copy.keys.addAll(this.keys);
        copy.events.addAll(this.events);
        copy.vars.putAll(this.vars);
        return copy;
    }
}

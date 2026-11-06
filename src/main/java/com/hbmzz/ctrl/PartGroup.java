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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One 部件类型 in a terminal: every linked part of one machine family that this
 * terminal is bound to.
 *
 * <p>This is where "调节整个反应堆" stops being a slogan. A group is read as a
 * whole - each key reported by any member, with the spread of values when the
 * members disagree - and written as a whole: one event reaches every member
 * that declares it, so {@code set level 40} moves all four control rods without
 * the operator naming any of them.</p>
 *
 * <p>Writing is not a blind fan-out. A member that does not list the event is
 * skipped, because an RBMK boiler receiving {@code rbmk_ctrl_set_level} would
 * either ignore it (harmless but noisy in the log) or, for a machine that
 * accepts unknown events, do something nobody asked for. The fallback to
 * "everyone" only applies when no member advertises the event at all, which is
 * the case for a machine whose {@code getInEvents()} is empty but which still
 * reacts to a known name - the same concession {@link TerminalShell} already
 * makes when it sends an event nobody announced.</p>
 *
 * <p>Reading aggregates rather than picking a representative on purpose: the
 * whole point of a control rod group is knowing that one rod is at 40 and
 * another at 80, which a single number would hide.</p>
 */
public final class PartGroup {

    /** Longest aggregated value, so a group of tanks cannot push a readout line off-screen. */
    private static final int MAX_VALUE = 22;
    /** Most keys one group will report; the reply packet carries at most 40 lines. */
    private static final int MAX_KEYS = 20;

    public final String family;
    public final String label;

    private final TerminalHost base;
    private final List<Integer> slots;
    /** Part name per slot, parallel to {@link #slots}. */
    private final List<String> partNames;

    public PartGroup(String family, String label, TerminalHost base,
                     List<Integer> slots, List<String> partNames) {
        this.family = family == null ? "" : family;
        this.label = label == null ? "" : label;
        this.base = base;
        this.slots = new ArrayList<Integer>(slots == null ? new ArrayList<Integer>() : slots);
        this.partNames = new ArrayList<String>();
        for (int i = 0; i < this.slots.size(); i++) {
            String name = partNames != null && i < partNames.size() ? partNames.get(i) : null;
            this.partNames.add(name == null ? "" : name);
        }
    }

    // ------------------------------------------------------------- membership

    public int size() {
        return this.slots.size();
    }

    /** Base slot indices, in the order the panel lists them. */
    public List<Integer> slots() {
        return new ArrayList<Integer>(this.slots);
    }

    /** Position of the first member, for the {@code ls} coordinate line. */
    public BlockPos pos() {
        return this.base == null || this.slots.isEmpty() ? null : this.base.pos(this.slots.get(0).intValue());
    }

    /** Distinct part names, in first-seen order. */
    public List<String> names() {
        return new ArrayList<String>(census().keySet());
    }

    /** Part name to how many members carry it. */
    public Map<String, Integer> census() {
        Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (String name : this.partNames) {
            Integer count = out.get(name);
            out.put(name, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        return out;
    }

    /** "RBMK 反应堆 · 12 部件", or the part name when the group is one part repeated. */
    public String describe() {
        if (this.slots.size() == 1) {
            return this.partNames.get(0);
        }
        return this.label + " · " + this.slots.size() + " 部件";
    }

    // ------------------------------------------------------------- vocabulary

    /** Every data key any member reports, deduplicated, in first-seen order. */
    public List<String> keys() {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Integer slot : this.slots) {
            for (String key : this.base.keys(slot.intValue())) {
                if (key != null && seen.add(key) && out.size() < MAX_KEYS) {
                    out.add(key);
                }
            }
        }
        return out;
    }

    /** Every event any member accepts. */
    public List<String> events() {
        List<String> out = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (Integer slot : this.slots) {
            for (String event : this.base.events(slot.intValue())) {
                if (event != null && seen.add(event)) {
                    out.add(event);
                }
            }
        }
        return out;
    }

    /** Event to variable, merged; the first member that documents an event wins. */
    public Map<String, String> vars() {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Integer slot : this.slots) {
            for (Map.Entry<String, String> entry : this.base.vars(slot.intValue()).entrySet()) {
                if (!out.containsKey(entry.getKey())) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ read

    /**
     * One reading of the whole group.
     *
     * <p>A key every member agrees on prints as that value. A numeric key they
     * disagree on prints as {@code min–max}, which is the one summary that still
     * tells the operator "something in this reactor is not where I put it". A
     * text key they disagree on prints as the distinct values, up to three.</p>
     */
    public Map<String, String> read() {
        Map<String, List<String>> collected = new LinkedHashMap<String, List<String>>();
        for (Integer slot : this.slots) {
            for (Map.Entry<String, String> entry : this.base.read(slot.intValue()).entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                List<String> values = collected.get(entry.getKey());
                if (values == null) {
                    if (collected.size() >= MAX_KEYS) {
                        continue;
                    }
                    values = new ArrayList<String>();
                    collected.put(entry.getKey(), values);
                }
                values.add(entry.getValue());
            }
        }
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, List<String>> entry : collected.entrySet()) {
            out.put(entry.getKey(), summarise(entry.getValue()));
        }
        return out;
    }

    private static String summarise(List<String> values) {
        double min = Double.NaN;
        double max = Double.NaN;
        boolean numeric = true;
        Set<String> distinct = new LinkedHashSet<String>();
        for (String value : values) {
            distinct.add(value);
            try {
                double number = Double.parseDouble(value.trim());
                if (Double.isNaN(min) || number < min) {
                    min = number;
                }
                if (Double.isNaN(max) || number > max) {
                    max = number;
                }
            } catch (NumberFormatException notANumber) {
                numeric = false;
            }
        }
        if (numeric) {
            if (Math.abs(max - min) < 1.0E-4D) {
                return clip(number(max));
            }
            return clip(number(min) + "–" + number(max));
        }
        if (distinct.size() == 1) {
            return clip(values.get(0));
        }
        if (distinct.size() > 3) {
            return distinct.size() + " 种";
        }
        StringBuilder text = new StringBuilder();
        for (String value : distinct) {
            if (text.length() > 0) {
                text.append('/');
            }
            text.append(value);
        }
        return clip(text.toString());
    }

    private static String number(double value) {
        return Math.abs(value - Math.rint(value)) < 1.0E-4D
                ? String.valueOf((long) Math.rint(value))
                : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String clip(String text) {
        String value = text == null ? "" : text;
        return value.length() <= MAX_VALUE ? value : value.substring(0, MAX_VALUE - 1) + "…";
    }

    // ------------------------------------------------------------------ write

    /**
     * Sends one event to every member that accepts it.
     *
     * <p>The scope guard is what makes this one delivery per part instead of
     * N by N: each member's own write path would otherwise fan the event out to
     * its same-name siblings again, and a {@code door_toggle} delivered twice
     * to the same door closes it again.</p>
     *
     * @return how many members took the event
     */
    public int fire(String event, Map<String, Object> vars) {
        if (this.base == null || event == null || event.isEmpty()) {
            return 0;
        }
        List<Integer> targets = new ArrayList<Integer>();
        Set<BlockPos> seen = new LinkedHashSet<BlockPos>();
        for (Integer slot : this.slots) {
            if (!this.base.events(slot.intValue()).contains(event)) {
                continue;
            }
            // Two slots can point at the same machine (a panel lets you bind
            // one machine twice). One delivery is what the operator asked for.
            if (seen.add(this.base.pos(slot.intValue()))) {
                targets.add(slot);
            }
        }
        if (targets.isEmpty()) {
            targets.addAll(this.slots);
        }
        PartBroadcast.beginScope();
        try {
            int sent = 0;
            for (Integer slot : targets) {
                sent += this.base.fire(slot.intValue(), event, vars) > 0 ? 1 : 0;
            }
            return sent;
        } finally {
            PartBroadcast.endScope();
        }
    }
}

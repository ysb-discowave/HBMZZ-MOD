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
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The terminal's view of this mod's own panel: six link slots, one widget.
 *
 * <p>The panel already caches what each slot can do ({@link CtrlLink#keys},
 * {@link CtrlLink#events}, {@link CtrlLink#vars}), so the terminal reads that
 * catalogue rather than asking the machines again - the panel's half-second
 * round keeps it current, and a dropdown that disagrees with the panel would be
 * worse than one that lags.</p>
 *
 * <p>Values, on the other hand, are read live through {@link MachineBridge},
 * because a terminal is exactly the place where a stale number is misleading.</p>
 */
public class CtrlPanelHost implements TerminalHost {

    private final CtrlPanel panel;
    private final int widgetIndex;
    private final World world;

    public CtrlPanelHost(CtrlPanel panel, int widgetIndex, World world) {
        this.panel = panel;
        this.widgetIndex = widgetIndex;
        this.world = world;
    }

    /** The widget this host is attached to, or null when it is gone. */
    private CtrlWidget widget() {
        if (this.panel == null || this.widgetIndex < 0) {
            return null;
        }
        return this.panel.get(this.widgetIndex);
    }

    private CtrlLink link(int slot) {
        if (this.panel == null || slot < 0 || slot >= CtrlPanel.LINKS) {
            return null;
        }
        CtrlLink link = this.panel.links[slot];
        return link == null || link.isEmpty() ? null : link;
    }

    @Override
    public int slots() {
        return CtrlPanel.LINKS;
    }

    @Override
    public String label(int slot) {
        return CtrlLink.letter(slot);
    }

    @Override
    public String name(int slot) {
        CtrlLink link = link(slot);
        if (link == null) {
            return "未绑定";
        }
        boolean live = this.world != null && MachineBridge.isControllable(this.world, link.pos);
        return link.label + (live ? "" : "  （离线）");
    }

    @Override
    public boolean bound(int slot) {
        return link(slot) != null;
    }

    @Override
    public int target() {
        CtrlWidget widget = widget();
        return widget == null ? -1 : widget.link;
    }

    @Override
    public boolean select(int slot) {
        CtrlWidget widget = widget();
        if (widget == null || link(slot) == null || widget.link == slot) {
            return false;
        }
        widget.link = slot;
        return true;
    }

    @Override
    public List<String> keys(int slot) {
        CtrlLink link = link(slot);
        return link == null ? new ArrayList<String>() : link.keys;
    }

    @Override
    public List<String> events(int slot) {
        CtrlLink link = link(slot);
        return link == null ? new ArrayList<String>() : link.events;
    }

    @Override
    public Map<String, String> vars(int slot) {
        CtrlLink link = link(slot);
        return link == null ? new LinkedHashMap<String, String>() : link.vars;
    }

    @Override
    public Map<String, String> read(int slot) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        CtrlLink link = link(slot);
        if (link == null || this.world == null) {
            return out;
        }
        MachineBridge.Sample sample = MachineBridge.sample(this.world, link.pos);
        if (sample == null) {
            return out;
        }
        for (String key : sample.numbers.keySet()) {
            out.put(key, sample.text(key));
        }
        for (String key : sample.texts.keySet()) {
            if (!out.containsKey(key)) {
                out.put(key, sample.text(key));
            }
        }
        return out;
    }

    @Override
    public boolean fire(int slot, String event, Map<String, Object> vars) {
        CtrlLink link = link(slot);
        return link != null && this.world != null
                && MachineBridge.fireAll(this.world, link.pos, event, vars);
    }

    @Override
    public boolean available() {
        return MachineBridge.available();
    }

    @Override
    public BlockPos pos(int slot) {
        CtrlLink link = link(slot);
        return link == null ? null : link.pos;
    }
}

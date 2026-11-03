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

import com.hbmzz.cecp.controls.ControlType;

/**
 * Control types the addon registers on top of CE's own list.
 *
 * <p>{@link ControlType#registerOrGet} is keyed by the same English names the
 * addon uses, so panels saved with it still resolve their control type.</p>
 */
public final class AddonControlType {

    public static final ControlType RBMK_DISPLAY = ControlType.registerOrGet("RBMK Display");
    public static final ControlType GRAPH = ControlType.registerOrGet("Graph");
    public static final ControlType LEVER = ControlType.registerOrGet("Lever");

    private AddonControlType() {
    }
}

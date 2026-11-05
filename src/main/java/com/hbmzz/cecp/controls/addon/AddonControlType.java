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

    /**
     * Forces this class - and therefore the three registrations above - to run.
     *
     * <p>Static fields are initialised on first <em>use</em>, and nothing uses
     * them during startup: {@code ControlRegistry.init()} builds the control
     * table by constructing control objects, and their {@code getControlType()}
     * is not called until something walks it. That "something" used to be the
     * console editor's type menu, which walked the type list at the same time -
     * so the very first walk appended three entries to the list it was
     * iterating, and the editor died of a ConcurrentModificationException
     * before it could draw.</p>
     *
     * <p>Calling this from {@code ControlRegistry.init()} makes the type list
     * complete before any GUI can read it. The menu is also fixed to iterate a
     * snapshot, because one lazy registry is all it takes and there may be more
     * later.</p>
     */
    public static void register() {
        // Touch all three: a reference to one constant initialises the class.
        if (RBMK_DISPLAY == null || GRAPH == null || LEVER == null) {
            throw new IllegalStateException("control type registration failed");
        }
    }

    private AddonControlType() {
    }
}

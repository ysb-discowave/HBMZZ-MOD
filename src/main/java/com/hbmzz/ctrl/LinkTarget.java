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

/**
 * Something the monitor data line can record as a control target.
 *
 * <p>Marker only, and deliberately empty. The cable records two kinds of thing:
 * machines that speak NTM's control-panel protocol, and blocks that carry a
 * control board and are therefore themselves controllable. The second kind is
 * named here instead of by class so the item does not have to know which board
 * it is pointing at - a jar without any board simply never has an instance of
 * this interface, and the click falls through to the generic "recorded a
 * coordinate" branch as before.</p>
 */
public interface LinkTarget {
}

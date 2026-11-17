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

package com.hbmzz.cecp.api;

/**
 * A control that accumulates a numeric series - the chart / graph widget.
 *
 * <p>The graph nodes only ever ask one thing of the control they are dropped
 * into: take this value. Naming that here, rather than checking for the
 * concrete {@code Graph} widget, means the node menu and the node classes do
 * not import the widget's class at all - so the widget can be replaced, or left
 * out of a build, without the nodes failing to link. It is the same reason
 * {@link com.hbmzz.cecp.IControllable} exists one level up.</p>
 */
public interface IValueChart {

    /** Appends one sample to the control's series. */
    void pushValue(double value);

    /**
     * The samples currently held, oldest first. Live, not a copy: the bounds
     * node reads it once per evaluation and immediately reduces it.
     */
    double[] series();
}

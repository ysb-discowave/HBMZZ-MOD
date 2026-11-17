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

package com.hbmzz.cecp;

import com.hbmzz.cecp.types.DataValue;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A block entity that a control board can address: it reports what it can be
 * told, and takes the events the board broadcasts.
 *
 * <p>Also a {@link com.hbmzz.ctrl.LinkTarget}, because a board is itself
 * controllable - one console can drive another's buttons. That marker lives in
 * {@code com.hbmzz.ctrl} so the monitor data line, which is older than the
 * console and outlives it, can recognise a board without naming one.</p>
 */
public interface IControllable extends com.hbmzz.ctrl.LinkTarget {

	default List<String> getInEvents(){return Collections.emptyList();}
	default List<String> getOutEvents(){return Collections.emptyList();}

	default Map<String,DataValue> getQueryData(){return Collections.emptyMap();}

	// here most things respond by sending the out event back
	// -- a lazy fix for other controls that share a panel not receiving the broadcast event too.
	default void receiveEvent(BlockPos from, ControlEvent e) {}
	
	public BlockPos getControlPos();
	public World getControlWorld();
}

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

package com.hbmzz.cecp.nodes;

import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.IControllable;
import com.hbmzz.cecp.NodeSystem;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public abstract class NodeOutput extends Node {

	public NodeOutput(float x, float y){
		super(x, y);
	}
	
	@Override
	public DataValue evaluate(int idx){
		//Output nodes don't need this
		return null;
	}

	/**
	 * Executes this output node.
	 * <p>
	 * Returning {@code true} means processing should continue normally.
	 * Returning {@code false} means the current receiver is canceled when this output is being
	 * evaluated from a send-node map, so {@link NodeEventBroadcast} will skip broadcasting the
	 * event to that receiver. Implementations should therefore return {@code false} only for
	 * explicit cancellation behavior such as {@link NodeCancelEvent}, and return {@code true}
	 * for side effect or no-op outputs like variable assignment and redstone output.
	 */
	public abstract boolean doOutput(IControllable from, Map<String, NodeSystem> sendNodeMap, Map<String,BlockPos> links);
}

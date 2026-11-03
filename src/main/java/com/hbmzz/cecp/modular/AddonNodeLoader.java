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

package com.hbmzz.cecp.modular;

import com.hbmzz.cecp.NodeSystem;
import com.hbmzz.cecp.nodes.Node;
import com.hbmzz.cecp.nodes.addon.*;
import net.minecraft.nbt.NBTTagCompound;

/**
 * Restores the addon nodes from NBT.
 *
 * <p>The {@code nodeType} strings are the ones CE Addon writes
 * ({@code sounder}, {@code leafia_pulse}, {@code format}, ...) so a graph saved
 * on a panel that had the addon installed loads here instead of collapsing into
 * unknown nodes.</p>
 */
public class AddonNodeLoader implements INodeLoader {

	@Override
	public Node nodeFromNBT(NBTTagCompound tag, NodeSystem sys) {
		switch (tag.getString("nodeType")) {
			case "sounder":
				return new NodeSounder(0, 0);
			case "leafia_pulse":
				return new NodePulse(0, 0);
			case "leafia_sr_latch":
				return new NodeSRLatch(0, 0);
			case "addString":
				return new NodeAddString(0, 0);
			case "subString":
				return new NodeSubString(0, 0);
			case "sipfx":
				return new NodeSIPfx(0, 0);
			case "format":
				return new NodeFormat(0, 0);
			case "leafia_cache":
				return new NodeCache(0, 0);
			case "leafia_clock":
				return new NodeClock(0, 0);
			case "leafia_parse_float":
				return new NodeParseFloat(0, 0);
			case "leafia_rng":
				return new NodeRNG(0, 0, sys.parent.panel.parent.getControlWorld());
			case "leafia_rng_d":
				return new NodeRNGDecimal(0, 0, sys.parent.panel.parent.getControlWorld());
			case "leafia_summarizer":
				return new NodeSummarizer(0, 0);
			case "bulkQuery":
				return new NodeBulkQuery(0, 0, sys.parent);
			case "leafia_graph":
				return new NodeGraphAdd(0, 0, sys.parent);
			case "leafia_graph_bounds":
				return new NodeGraphBounds(0, 0, sys.parent);
			default:
				return null;
		}
	}
}

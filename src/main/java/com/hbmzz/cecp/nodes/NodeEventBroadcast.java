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

import com.hbmzz.cecp.*;
import com.hbmzz.cecp.types.DataValue;
import com.hbmzz.cecp.types.DataValue.DataType;
import com.hbmzz.cecp.modular.StockNodesRegister;
import com.hbmzz.cecp.types.DataValueFloat;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class NodeEventBroadcast extends NodeOutput {

	public String eventName;
	
	public NodeEventBroadcast(float x, float y, List<ControlEvent> ableToBroadcast){
		super(x, y);
		NodeDropdown opSelector = new NodeDropdown(this, otherElements.size(), s -> {
			setEvent(s);
			return null;
		}, () -> eventName);
		for(ControlEvent c : ableToBroadcast){
			opSelector.list.addItems(c.name);
		}
		this.otherElements.add(opSelector);
		setEvent(ableToBroadcast.get(0).name);
	}
	
	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		tag.setString("nodeType", "eventBroadcast");
		tag.setString("eventName", eventName);
		NBTTagCompound list = new NBTTagCompound();
		int i = 0;
		for(String s : ((NodeDropdown)this.otherElements.get(0)).list.itemNames){
			list.setString("item"+i, s);
			i++;
		}
		tag.setTag("itemList", list);
		return super.writeToNBT(tag, sys);
	}
	
	@Override
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		eventName = tag.getString("eventName");
		super.readFromNBT(tag, sys);
		repairPorts();
	}

	/**
	 * Rebuilds the port row from the event definition when the saved graph holds
	 * a different number of ports.
	 *
	 * <p>A graph saved while the event table was missing this event has nothing
	 * but {@code Cancel} on disk. The table is fixed now, but the stale ports are
	 * still in the file, so loading alone would keep showing the broken row.
	 * Ports that still exist keep their connection and their typed default; only
	 * the count is brought back in line with the definition.</p>
	 */
	private void repairPorts(){
		if(eventName == null || eventName.isEmpty()){
			return;
		}
		ControlEvent evt = ControlEvent.getOrRegister(eventName);
		int expected = evt.vars.size() + 1;
		if(inputs.size() == expected){
			return;
		}
		Map<String, NodeConnection> saved = new LinkedHashMap<>();
		for(NodeConnection c : inputs){
			saved.put(c.name, c);
		}
		inputs.clear();
		for(Entry<String, DataValue> e : evt.vars.entrySet()){
			NodeConnection port = new NodeConnection(e.getKey(), this, inputs.size(), true, e.getValue().getType(), e.getValue().copy());
			adopt(port, saved.get(e.getKey()));
			inputs.add(port);
		}
		NodeConnection cancel = new NodeConnection("Cancel", this, inputs.size(), true, DataType.GENERIC, new DataValueFloat(0));
		adopt(cancel, saved.get("Cancel"));
		inputs.add(cancel);
		recalcSize();
	}

	/** Carries an old port's connection over to its replacement. */
	private static void adopt(NodeConnection port, NodeConnection old){
		if(old == null || old.connection == null || old.connectionIndex < 0){
			return;
		}
		List<NodeConnection> peers = old.connection.outputs;
		if(old.connectionIndex < peers.size() && peers.get(old.connectionIndex) == old){
			peers.set(old.connectionIndex, port);
		}
		port.setData(old.connection, old.connectionIndex, old.drawsLine);
	}

	@Override
	public boolean doOutput(IControllable from, Map<String, NodeSystem> sendNodeMap, Map<String,BlockPos> positions){
		World world = from.getControlWorld();
		// getOrRegister, not newEvent: newEvent throws outright for a name that is
		// not in the table, and a graph can hold an event an addon registered after
		// the last mirror ran. An empty definition still sends the ports we have.
		ControlEvent e = ControlEvent.getOrRegister(eventName).copy();
		for(NodeConnection c : inputs){
			if(c.name.startsWith("Cancel")){
				if(c.evaluate().getBoolean())
					return true;
				continue;
			}
			e.vars.put(c.name, c.evaluate());
		}
		if(sendNodeMap != null){
			if(sendNodeMap.containsKey(e.name)){
				NodeSystem sys = sendNodeMap.get(e.name);
				int i = 0;
				cont:
				for(BlockPos pos : positions.values()){
					sys.resetCachedValues();
					sys.setVar("receiver_id", new DataValueFloat(i));
					for(NodeOutput o : sys.outputNodes){
						if(!o.doOutput(from, sendNodeMap, positions))
							continue cont;
					}
					ControlEventSystem.get(world).broadcastEvent(from.getControlPos(), e, pos);
					i++;
				}
			} else {
				ControlEventSystem.get(world).broadcastEvent(from.getControlPos(), e, positions.values());
			}
		} else {
			ControlEventSystem.get(world).broadcastEvent(from.getControlPos(), e, positions.values());
		}
		return true;
	}
	
	public void setEvent(String name){
		eventName = name;
		for(NodeConnection c : inputs){
			c.removeConnection();
		}
		this.inputs.clear();
		ControlEvent evt = ControlEvent.getOrRegister(name);
		for(Entry<String,DataValue> e : evt.vars.entrySet()){
			inputs.add(new NodeConnection(e.getKey(), this, inputs.size(), true, e.getValue().getType(), e.getValue().copy()));
		}
		inputs.add(new NodeConnection("Cancel", this, inputs.size(), true, DataType.GENERIC, new DataValueFloat(0)));
		recalcSize();
	}

	@Override
	public float[] getColor() {
		return StockNodesRegister.colorOutput;
	}

	@Override
	public String getDisplayName(){
		return "广播事件";
	}

}

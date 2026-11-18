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
import com.hbmzz.cecp.types.DataValueEnum;
import com.hbmzz.cecp.types.DataValueFloat;
import com.hbmzz.cecp.types.DataValueString;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;

import java.util.Map;
import java.util.Map.Entry;

public class ControlEvent {

	private static final Map<String, ControlEvent> REGISTRY = new Object2ObjectOpenHashMap<>();
	
	public String name;
	public Map<String, DataValue> vars = new Object2ObjectLinkedOpenHashMap<>();
	
	public ControlEvent(String name){
		this.name = name;
	}
	
	public ControlEvent setVar(String key, DataValue val){
		vars.put(key, val);
		return this;
	}
	
	public ControlEvent setVar(String key, float f){
		vars.put(key, new DataValueFloat(f));
		return this;
	}
	
	public ControlEvent setVar(String key, boolean b){
		vars.put(key, new DataValueFloat(b ? 1 : 0));
		return this;
	}
	
	public ControlEvent setVar(String key, String str){
		vars.put(key, new DataValueString(str));
		return this;
	}
	
	public <E extends Enum<E>> ControlEvent setVar(String key, E enm){
		vars.put(key, new DataValueEnum<E>(enm));
		return this;
	}
	
	public ControlEvent copy(){
		ControlEvent evt = new ControlEvent(name);
		//Set default values
		for(Entry<String, DataValue> def : vars.entrySet()){
			evt.vars.put(def.getKey(), def.getValue().copy());
		}
		return evt;
	}
	
	public NBTTagCompound writeToNBT(NBTTagCompound tag){
		tag.setString("name", this.name);
		NBTTagCompound vars = new NBTTagCompound();
		for(Entry<String, DataValue> e : this.vars.entrySet()){
			vars.setTag(e.getKey(), e.getValue().writeToNBT());
		}
		tag.setTag("vars", vars);
		return tag;
	}
	
	public static ControlEvent readFromNBT(NBTTagCompound tag){
		ControlEvent evt = ControlEvent.newEvent(tag.getString("name"));
		NBTTagCompound vars = tag.getCompoundTag("vars");
		for(String k : vars.getKeySet()){
			DataValue val = DataValue.newFromNBT(vars.getTag(k));
			if(val != null)
				evt.vars.put(k, val);
		}
		return evt;
	}
	
	public static ControlEvent newEvent(String name){
		return getRegisteredEvent(name).copy();
	}
	
	public static ControlEvent getRegisteredEvent(String name){
		ControlEvent e = REGISTRY.get(name);
		if(e == null)
			throw new RuntimeException("Unregistered control event: " + name);
		return e;
	}
	
	public static void register(ControlEvent c){
		REGISTRY.put(c.name, c);
	}

	/**
	 * The registered event of this name, registering an empty one if the name is
	 * new.
	 *
	 * <p>Machine-declared events are not all in the stock table: an addon's
	 * machine can announce an event the port has never heard of, and
	 * {@link #getRegisteredEvent} throws for those. Throwing here means opening
	 * the event editor crashes clean out of the GUI, so an unknown name becomes an
	 * empty event instead - it shows up in the list, and broadcasting it sends no
	 * variables rather than killing the window.</p>
	 */
	public static ControlEvent getOrRegister(String name){
		ControlEvent e = REGISTRY.get(name);
		if(e == null){
			// Last chance before inventing an empty event: an addon may have
			// registered this one upstream after the table was mirrored.
			com.hbmzz.cecp.compat.CEEventTable.mirror();
			e = REGISTRY.get(name);
			if(e == null){
				e = new ControlEvent(name);
				register(e);
			}
		}
		return e;
	}

	/** Whether this exact event name has a definition in the table. */
	public static boolean isRegistered(String name){
		return name != null && REGISTRY.containsKey(name);
	}

	/** The definition for this name, or {@code null} when there is none. */
	public static ControlEvent peek(String name){
		return name == null ? null : REGISTRY.get(name);
	}
	
	public static void init(){
		register(new ControlEvent("tick").setVar("time", 0));
		register(new ControlEvent("door_open_state").setVar("state", 0));
		register(new ControlEvent("door_toggle").setVar("passcode", 0));
		register(new ControlEvent("turret_set_target").setVar("players", false).setVar("passive", false).setVar("hostile", true).setVar("machines", true));
		register(new ControlEvent("turret_switch").setVar("isOn", true).setVar("isBlacklistFilter", true));
		register(new ControlEvent("lever_toggle").setVar("isOn", false));
		register(new ControlEvent("spinny_light_power").setVar("isOn", false));
		register(new ControlEvent("siren_set_state").setVar("isOn", false));
		register(new ControlEvent("tank_set_mode").setVar("mode", 0));
		register(new ControlEvent("rbmk_ctrl_set_color").setVar("color", 0));
		register(new ControlEvent("rbmk_ctrl_set_level").setVar("level", 0));
		register(new ControlEvent("rbmk_crane_move").setVar("up", 0).setVar("down", 0).setVar("left", 0).setVar("right", 0));
		register(new ControlEvent("rbmk_crane_load"));
		register(new ControlEvent("ctrl_press").setVar("isSneaking", false));
		register(new ControlEvent("redstone_input")
				.setVar("facing", EnumFacing.NORTH)
				.setVar("power", 0)
				.setVar("weak", 0)
				.setVar("strong", 0)
				.setVar("isPowered", false)
				.setVar("isWeaklyPowered", false)
				.setVar("isStronglyPowered", false));
		register(new ControlEvent("initialize"));
	}
}

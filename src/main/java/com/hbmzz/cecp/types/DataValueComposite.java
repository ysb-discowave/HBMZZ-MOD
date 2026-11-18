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

package com.hbmzz.cecp.types;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class DataValueComposite extends DataValue {
	Map<String,String> dataMap = new Object2ObjectOpenHashMap<>();
	@Override
	public float getNumber() {
		return 0;
	}
	@Override
	public boolean getBoolean() {
		return false;
	}
	@Override
	public String toString() {
		return "< PACKED >";
	}
	@Override
	public String getValueOf(String key) {
		return dataMap.getOrDefault(key,"");
	}
	@Override
	public void setValueOf(String key,String value) {
		if (key.trim().isEmpty()) return;
		if (value.trim().isEmpty())
			dataMap.remove(key);
		else
			dataMap.put(key,value);
	}
	@Override
	public Set<String> values() {
		return dataMap.keySet();
	}
	@Override
	public DataType getType() {
		return DataType.COMPOSITE;
	}
	@Override
	public <E extends Enum<E>> E getEnum(Class<E> clazz) {
		throw new UnsupportedOperationException("DataValueComposite has no enum representation");
	}
	@Override
	public DataValue copy() {
		DataValueComposite data = new DataValueComposite();
		data.dataMap.putAll(dataMap);
		return data;
	}
	@Override
	public NBTBase writeToNBT() {
		NBTTagCompound compound = new NBTTagCompound();
		for (Entry<String,String> entry : dataMap.entrySet())
			compound.setString(entry.getKey(),entry.getValue());
		return compound;
	}
	@Override
	public void readFromNBT(NBTBase nbt) {
		if(nbt instanceof NBTTagCompound) { NBTTagCompound compound = (NBTTagCompound) nbt;
			dataMap.clear();
			for (String s : compound.getKeySet())
				dataMap.put(s,compound.getString(s));
		}
	}

    public Map<String,String> snapshot() {
		return new HashMap<>(dataMap);
	}

    public static DataValueComposite fromLuaTable(Map<?,?> table) {
		DataValueComposite value = new DataValueComposite();
		for (Entry<?,?> e : table.entrySet()) {
			value.setValueOf(e.getKey().toString(), e.getValue().toString());
		}
		return value;
	}
}

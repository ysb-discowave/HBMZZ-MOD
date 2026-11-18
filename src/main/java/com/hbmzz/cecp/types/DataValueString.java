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

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagString;

import java.util.Objects;

public class DataValueString extends DataValue {

	public String str;
	private float floatVal;
	
	public DataValueString(String s){
		set(s);
	}

	public void set(String s) {
		this.str = s;
		float num = 0;
		try {
			num = Float.parseFloat(str);
		} catch(Exception x){
		}
		floatVal = num;
	}

	@Override
	public float getNumber(){
		return floatVal;
	}

	@Override
	public boolean getBoolean(){
		if(str.equals("true"))
			return true;
		return false;
	}

	@Override
	public String toString(){
		return str;
	}

	@Override
	public <E extends Enum<E>> E getEnum(Class<E> clazz){
		E[] enms = clazz.getEnumConstants();
		for(E enm : enms){
			if(enm.name().equalsIgnoreCase(str))
				return enm;
		}
		return enms[0];
	}
	
	@Override
	public DataType getType(){
		return DataType.STRING;
	}

	@Override
	public DataValue copy() {
		return new DataValueString(str);
	}

	@Override
	public NBTBase writeToNBT(){
		return new NBTTagString(str);
	}

	@Override
	public void readFromNBT(NBTBase nbt){
		set(((NBTTagString)nbt).getString());
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(!(obj instanceof DataValueString)) return false; DataValueString other = (DataValueString) obj;
		return Objects.equals(str, other.str);
	}

	@Override
	public int hashCode() {
		return Objects.hash(str);
	}

}

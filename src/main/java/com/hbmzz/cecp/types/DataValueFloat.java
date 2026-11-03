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
import net.minecraft.nbt.NBTTagFloat;

import java.util.Objects;

public class DataValueFloat extends DataValue {

	public float num;
	
	public DataValueFloat(float f) {
		num = f;
	}

	public DataValueFloat(boolean b) {
		num = (b)? 1.0F : 0.0F;
	}

	@Override
	public float getNumber() {
		return num;
	}

	@Override
	public boolean getBoolean() {
		return num != 0;
	}

	@Override
	public String toString() {
		return Float.toString(num);
	}

	@Override
	public DataType getType(){
		return DataType.NUMBER;
	}
	
	
	@Override
	public <E extends Enum<E>> E getEnum(Class<E> clazz){
		int i = (int)num;
		E[] enums = clazz.getEnumConstants();
		if(i >= 0 && i < enums.length){
			return enums[i];
		}
		return enums[0];
	}

	@Override
	public DataValue copy() {
		return new DataValueFloat(num);
	}

	@Override
	public NBTBase writeToNBT(){
		return new NBTTagFloat(num);
	}

	@Override
	public void readFromNBT(NBTBase nbt){
		NBTTagFloat f = (NBTTagFloat)nbt;
		num = f.getFloat();
	}

	@Override
	public boolean equals(Object obj) {
		if(this == obj) return true;
		if(!(obj instanceof DataValueFloat)) return false; DataValueFloat other = (DataValueFloat) obj;
		return Float.compare(other.num, num) == 0;
	}

	@Override
	public int hashCode() {
		return Objects.hash(num);
	}

}

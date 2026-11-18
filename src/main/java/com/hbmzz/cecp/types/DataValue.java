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

import com.hbmzz.cecp.compat.CPEnv;
import net.minecraft.nbt.*;

import java.util.Collections;
import java.util.Set;

public abstract class DataValue {
	public abstract float getNumber();
	public abstract boolean getBoolean();
	public abstract String toString();
	public String getValueOf(String key) {
		return "ERROR";
	}
	public void setValueOf(String key,String value) { }
	public Set<String> values() {
		return Collections.emptySet();
	}
	public abstract DataType getType();
	public abstract <E extends Enum<E>> E getEnum(Class<E> clazz);
	public abstract DataValue copy();
	public abstract NBTBase writeToNBT();
	public abstract void readFromNBT(NBTBase nbt);
	
	public static DataValue newFromNBT(NBTBase base){
		DataValue val = null;
		try {
			if(base instanceof NBTTagCompound) { NBTTagCompound tag = (NBTTagCompound) base;
				if (tag.hasKey("ordinal") && tag.getTag("ordinal") instanceof NBTTagInt) {
					val = new DataValueEnum<>(null);
				} else {
					val = new DataValueComposite();
				}
				val.readFromNBT(base);
			} else if(base instanceof NBTTagFloat) {
				val = new DataValueFloat(0);
				val.readFromNBT(base);
			} else if(base instanceof NBTTagString) {
				val = new DataValueString("");
				val.readFromNBT(base);
			}
		} catch(Exception x) {
			CPEnv.logger.error("Failed to deserialize control-panel data value from NBT type {}", base.getClass().getName(), x);
			return null;
		}
		return val;
	}
	
	public static enum DataType {
		GENERIC(new float[]{0.5F, 0.5F, 0.5F}),
		NUMBER(new float[]{0.4F, 0.6F, 0}),
		STRING(new float[]{0, 1, 1}),
		ENUM(new float[]{0.5F, 0.29F, 0}),
		COMPOSITE(new float[]{1F,0.399F,0.842F});

        public static final DataType[] VALUES = values();
		private float[] color;
		
		private DataType(float[] color){
			this.color = color;
		}
		
		public float[] getColor(){
			return color;
		}
	}
}

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

import com.hbmzz.cecp.nodes.Node;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class NodeElement {

	public String name = "default";
	public Node parent;
	public int index;
	public float offsetX;
	public float offsetY;

	@SideOnly(Side.CLIENT)
	public void onClicked(SubElement subElement) {
	}
	
	public NodeElement(Node parent, int idx){
		this.parent = parent;
		this.index = idx;
		resetOffset();
	}

	public NodeElement(String name, Node parent, int idx){
		this.name = name;
		this.parent = parent;
		this.index = idx;
		resetOffset();
	}

	public void render(float mX, float mY){
	}
	
	public void resetOffset(){
		offsetX = parent.posX;
		offsetY = parent.posY+index*8;
	}
	
	public boolean onClick(float x, float y){
		return false;
	}
	
	public NBTTagCompound writeToNBT(NBTTagCompound tag, NodeSystem sys){
		tag.setFloat("oX", offsetX);
		tag.setFloat("oY", offsetY);
		tag.setInteger("idx", index);
		tag.setInteger("pIdx", sys.nodes.indexOf(parent));
		return tag;
	}
	
	public void readFromNBT(NBTTagCompound tag, NodeSystem sys){
		offsetX = tag.getFloat("oX");
		offsetY = tag.getFloat("oY");
		index = tag.getInteger("idx");
		parent = sys.nodes.get(tag.getInteger("pIdx"));
	}
}

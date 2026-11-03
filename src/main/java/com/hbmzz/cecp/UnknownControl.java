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

import com.hbmzz.cecp.controls.ControlType;
import com.hbmzz.cecp.compat.CtrlBuf;
import com.hbmzz.cecp.compat.CtrlImm;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.IModelCustom;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;

import java.util.Collections;
import java.util.List;

/**
 * for round-tripping in case someone added and removed an addon and wants to add it back later
 *
 * @author mlbv
 */
public class UnknownControl extends Control {

	private static final ControlType UNKNOWN = ControlType.registerOrGet("Unknown");
	private static final float MIN_PLACEHOLDER_SPAN = 0.25F;

	private NBTTagCompound rawTag;
	private float boxMinX;
	private float boxMinY;
	private float boxMaxX;
	private float boxMaxY;
	private float boxHeight;
	private AxisAlignedBB boundingBox;

	public UnknownControl(ControlPanel panel, NBTTagCompound rawTag) {
		super(resolveDisplayName(rawTag), rawTag.getString("name"), panel);
		loadRawTag(rawTag);
	}

	private static String resolveDisplayName(NBTTagCompound rawTag) {
		if(rawTag.hasKey("myName")) {
			return rawTag.getString("myName");
		}
		String registryName = rawTag.getString("name");
		return registryName.isEmpty() ? "Unknown Control" : registryName;
	}

	private void loadRawTag(NBTTagCompound tag) {
		this.rawTag = tag.copy();
		this.name = resolveDisplayName(this.rawTag);
		this.posX = this.rawTag.getFloat("X");
		this.posY = this.rawTag.getFloat("Y");
		loadPlaceholderMeta(this.rawTag.getCompoundTag(PLACEHOLDER_META_TAG));
	}

	@Override
	public ControlType getControlType() {
		return UNKNOWN;
	}

	@Override
	public float[] getSize() {
		return new float[] {Math.max(boxMaxX - boxMinX, 0.1F), Math.max(boxMaxY - boxMinY, 0.1F), boxHeight};
	}

	@Override
	public void fillBox(float[] box) {
		box[0] = boxMinX;
		box[1] = boxMinY;
		box[2] = boxMaxX;
		box[3] = boxMaxY;
	}

	@Override
	public AxisAlignedBB getBoundingBox() {
		return boundingBox;
	}

	@Override
	public Control newControl(ControlPanel panel) {
		return new UnknownControl(panel, rawTag);
	}

	@Override
	public void populateDefaultNodes(List<ControlEvent> receiveEvents) {
	}

	@Override
	public List<String> getInEvents() {
		return Collections.emptyList();
	}

	/**
	 * CE left this empty: a control whose registry name is unknown simply draws
	 * nothing. On a board whose background is a pale grey panel that reads as
	 * "the block vanished", and since the placeholder still holds a bounding box
	 * you can neither see nor (reliably) click the hole it left behind.
	 *
	 * <p>This draws a magenta slab with a black frame into that hole instead, so
	 * a control that this build does not have is visibly missing rather than
	 * silently eaten. The NBT is preserved either way - see {@link #writeToNBT},
	 * which copies {@link #rawTag} back out untouched - so once the matching
	 * control is available again the very same panel rebuilds it in place.</p>
	 */
	@Override
	public void render() {
		float x0 = boxMinX;
		float x1 = boxMaxX;
		float z0 = boxMinY;
		float z1 = boxMaxY;
		float y = 0.02F;
		float edge = Math.min(0.045F, Math.min(x1 - x0, z1 - z0) * 0.2F);

		GlStateManager.pushMatrix();
		GlStateManager.disableTexture2D();
		GlStateManager.disableLighting();
		GlStateManager.disableCull();
		GlStateManager.enableBlend();
		GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
		GlStateManager.translate(posX, 0.0, posY);

		int fill = CtrlBuf.packColor(1.0F, 0.0F, 1.0F, 0.5F);
		int frame = CtrlBuf.packColor(0.0F, 0.0F, 0.0F, 0.85F);

		CtrlBuf buf = CtrlImm.INSTANCE.beginPositionColor(5);
		buf.appendPositionColorQuad(x0, y, z0, x1, y, z0, x1, y, z1, x0, y, z1, fill);
		buf.appendPositionColorQuad(x0, y, z0, x1, y, z0, x1, y, z0 + edge, x0, y, z0 + edge, frame);
		buf.appendPositionColorQuad(x0, y, z1 - edge, x1, y, z1 - edge, x1, y, z1, x0, y, z1, frame);
		buf.appendPositionColorQuad(x0, y, z0, x0 + edge, y, z0, x0 + edge, y, z1, x0, y, z1, frame);
		buf.appendPositionColorQuad(x1 - edge, y, z0, x1, y, z0, x1, y, z1, x1 - edge, y, z1, frame);
		CtrlImm.INSTANCE.draw();

		GlStateManager.disableBlend();
		GlStateManager.enableCull();
		GlStateManager.enableLighting();
		GlStateManager.enableTexture2D();
		GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.popMatrix();
	}

	/** Same idea as {@link #render}, but in the editor's flat 2D view. */
	@Override
	@SideOnly(Side.CLIENT)
	public void renderControl(float[] renderBox, Control selectedControl, GuiControlEdit gui) {
		net.minecraft.client.Minecraft.getMinecraft().getTextureManager().bindTexture(CtrlRes.white);
		int packedColor = CtrlBuf.packColor(1.0F, 0.0F, 1.0F, this == selectedControl ? 0.35F : 0.55F);
		CtrlBuf buf = CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
		appendGuiQuad(buf, renderBox[0], renderBox[1], renderBox[2], renderBox[3], 0.0F, 0.0F, 1.0F, 1.0F, packedColor);
		CtrlImm.INSTANCE.draw();
		gui.getFontRenderer().drawString(registryName, (int) renderBox[0] + 2, (int) renderBox[1] + 2, 0xFFFFFF, true);
	}

	@Override
	@SideOnly(Side.CLIENT)
	public IModelCustom getModel() {
		return null;
	}

	@Override
	@SideOnly(Side.CLIENT)
	public ResourceLocation getGuiTexture() {
		return CtrlRes.white;
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound tag) {
		NBTTagCompound copy = rawTag.copy();
		copy.setString("myName", name);
		copy.setFloat("X", posX);
		copy.setFloat("Y", posY);
		copy.setTag(PLACEHOLDER_META_TAG, writePlaceholderMeta());
		return copy;
	}

	@Override
	public void readFromNBT(NBTTagCompound tag) {
		loadRawTag(tag);
	}

	private void loadPlaceholderMeta(NBTTagCompound placeholderMeta) {
		this.boxMinX = placeholderMeta.hasKey(PLACEHOLDER_BOX_MIN_X) ? placeholderMeta.getFloat(PLACEHOLDER_BOX_MIN_X) : this.posX;
		this.boxMinY = placeholderMeta.hasKey(PLACEHOLDER_BOX_MIN_Y) ? placeholderMeta.getFloat(PLACEHOLDER_BOX_MIN_Y) : this.posY;
		this.boxMaxX = placeholderMeta.hasKey(PLACEHOLDER_BOX_MAX_X) ? placeholderMeta.getFloat(PLACEHOLDER_BOX_MAX_X) : this.posX + 1;
		this.boxMaxY = placeholderMeta.hasKey(PLACEHOLDER_BOX_MAX_Y) ? placeholderMeta.getFloat(PLACEHOLDER_BOX_MAX_Y) : this.posY + 1;
		if(this.boxMaxX <= this.boxMinX) {
			this.boxMaxX = this.boxMinX + MIN_PLACEHOLDER_SPAN;
		}
		if(this.boxMaxY <= this.boxMinY) {
			this.boxMaxY = this.boxMinY + MIN_PLACEHOLDER_SPAN;
		}
		this.boxHeight = placeholderMeta.hasKey(PLACEHOLDER_SIZE_HEIGHT) ? Math.max(placeholderMeta.getFloat(PLACEHOLDER_SIZE_HEIGHT), 0.1F) : 0.1F;

		if(placeholderMeta.hasKey(PLACEHOLDER_HAS_BOUNDING_BOX) && !placeholderMeta.getBoolean(PLACEHOLDER_HAS_BOUNDING_BOX)) {
			this.boundingBox = null;
			return;
		}
		if(placeholderMeta.hasKey(PLACEHOLDER_BOUNDS_MIN_X)
				&& placeholderMeta.hasKey(PLACEHOLDER_BOUNDS_MIN_Y)
				&& placeholderMeta.hasKey(PLACEHOLDER_BOUNDS_MIN_Z)
				&& placeholderMeta.hasKey(PLACEHOLDER_BOUNDS_MAX_X)
				&& placeholderMeta.hasKey(PLACEHOLDER_BOUNDS_MAX_Y)
				&& placeholderMeta.hasKey(PLACEHOLDER_BOUNDS_MAX_Z)) {
			this.boundingBox = new AxisAlignedBB(
					placeholderMeta.getDouble(PLACEHOLDER_BOUNDS_MIN_X),
					placeholderMeta.getDouble(PLACEHOLDER_BOUNDS_MIN_Y),
					placeholderMeta.getDouble(PLACEHOLDER_BOUNDS_MIN_Z),
					placeholderMeta.getDouble(PLACEHOLDER_BOUNDS_MAX_X),
					placeholderMeta.getDouble(PLACEHOLDER_BOUNDS_MAX_Y),
					placeholderMeta.getDouble(PLACEHOLDER_BOUNDS_MAX_Z)
			);
			this.boxHeight = Math.max(this.boxHeight, (float) Math.max(0.1D, this.boundingBox.maxY - this.boundingBox.minY));
			return;
		}
		this.boundingBox = createFallbackBoundingBox();
	}

	private AxisAlignedBB createFallbackBoundingBox() {
		return new AxisAlignedBB(boxMinX, 0, boxMinY, boxMaxX, boxHeight, boxMaxY);
	}

	private NBTTagCompound writePlaceholderMeta() {
		NBTTagCompound placeholderMeta = new NBTTagCompound();
		placeholderMeta.setFloat(PLACEHOLDER_BOX_MIN_X, boxMinX);
		placeholderMeta.setFloat(PLACEHOLDER_BOX_MIN_Y, boxMinY);
		placeholderMeta.setFloat(PLACEHOLDER_BOX_MAX_X, boxMaxX);
		placeholderMeta.setFloat(PLACEHOLDER_BOX_MAX_Y, boxMaxY);
		placeholderMeta.setFloat(PLACEHOLDER_SIZE_HEIGHT, boxHeight);
		placeholderMeta.setBoolean(PLACEHOLDER_HAS_BOUNDING_BOX, boundingBox != null);
		if(boundingBox != null) {
			placeholderMeta.setDouble(PLACEHOLDER_BOUNDS_MIN_X, boundingBox.minX);
			placeholderMeta.setDouble(PLACEHOLDER_BOUNDS_MIN_Y, boundingBox.minY);
			placeholderMeta.setDouble(PLACEHOLDER_BOUNDS_MIN_Z, boundingBox.minZ);
			placeholderMeta.setDouble(PLACEHOLDER_BOUNDS_MAX_X, boundingBox.maxX);
			placeholderMeta.setDouble(PLACEHOLDER_BOUNDS_MAX_Y, boundingBox.maxY);
			placeholderMeta.setDouble(PLACEHOLDER_BOUNDS_MAX_Z, boundingBox.maxZ);
		}
		return placeholderMeta;
	}
}

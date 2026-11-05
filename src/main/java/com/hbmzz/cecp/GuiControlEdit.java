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

import com.hbmzz.cecp.net.CPPackets;
import com.hbmzz.cecp.net.NBTControlPacket;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.ClickType;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public class GuiControlEdit extends GuiContainer {

	public static ResourceLocation texture = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/gui_placement_back.png");

	public float mouseX, mouseY;
	
	public TileEntityCtrlConsole control;
	public ContainerControlEdit container;
	
	public Deque<SubElement> subElementStack = new ArrayDeque<>();
	public SubElementPlacement placement;
	public SubElementItemChoice choice;
	public SubElementLinker linker;
	public SubElementEventEditor eventEditor;
	public SubElementNodeEditor nodeEditor;
	public SubElementPanelResize panelResize;
	public SubElementItemConfig itemConfig;
	public SubElementVariables variables;

	public Control currentEditControl;

	public ScaledResolution res;

	public boolean isEditMode = false; // when editing an existing control
	
	public GuiControlEdit(InventoryPlayer i, TileEntityCtrlConsole te) {
		super(new ContainerControlEdit(i, te));
		container = (ContainerControlEdit)this.inventorySlots;
		control = te;
		this.xSize = 256;
		this.ySize = 256;
		res = new ScaledResolution(Minecraft.getMinecraft());
	}
	
	@Override
	public void onGuiClosed(){
		placement.onClose();
		choice.onClose();
		linker.onClose();
		eventEditor.onClose();
		nodeEditor.onClose();
		panelResize.onClose();
		itemConfig.onClose();
		variables.onClose();
		NBTTagCompound tag = new NBTTagCompound();
		control.panel.writeToNBT(tag);
		tag.setString("full_set", "");
        CPPackets.createSendToServerThreadedPacket(new NBTControlPacket(tag, control.getPos()));
        control.updateTransform();
		isEditMode = false;
	}
	
	@Override
	public void onResize(Minecraft mcIn, int w, int h){
		res = new ScaledResolution(Minecraft.getMinecraft());
		super.onResize(mcIn, w, h);
	}

	@Override
	public void initGui() {
		super.initGui();
		placement = new SubElementPlacement(this);
		choice = new SubElementItemChoice(this);
		linker = new SubElementLinker(this);
		eventEditor = new SubElementEventEditor(this);
		nodeEditor = new SubElementNodeEditor(this);
		panelResize = new SubElementPanelResize(this);
		itemConfig = new SubElementItemConfig(this);
		variables = new SubElementVariables(this);

		// Push first, then initialise. The other order leaves an empty stack
		// behind if any sub-element throws out of initGui - and an empty stack
		// means the next tick's updateScreen() dies on getFirst(), which turns
		// one broken menu into a game crash with the real cause buried a hundred
		// lines up the log. See the ConcurrentModificationException in
		// SubElementItemChoice that motivated this order.
		subElementStack.clear();
		subElementStack.addFirst(placement);

		initQuietly(placement);
		initQuietly(choice);
		initQuietly(linker);
		initQuietly(eventEditor);
		initQuietly(nodeEditor);
		initQuietly(panelResize);
		initQuietly(itemConfig);
		initQuietly(variables);

		placement.enableButtons(true);
	}

	/**
	 * One sub-element's initGui, with the failure contained.
	 *
	 * <p>A menu that cannot build its buttons should be a menu with no buttons
	 * and a stack trace, not a screen the player cannot leave. The trace still
	 * goes to the log at ERROR, so the bug is not hidden - only defused.</p>
	 */
	private void initQuietly(SubElement element) {
		try {
			element.initGui();
		} catch (Throwable error) {
			LogManager.getLogger("hbmzz").error("control panel: " + element.getClass().getSimpleName()
					+ ".initGui() failed; the menu will open without it", error);
		}
	}

	/** The open sub-element, or null when the stack is somehow empty. */
	private SubElement top() {
		return subElementStack.isEmpty() ? null : subElementStack.getFirst();
	}
	
	public FontRenderer getFontRenderer(){
		return fontRenderer;
	}
	
	@Override
	public <T extends GuiButton> T addButton(T buttonIn) {
		return super.addButton(buttonIn);
	}

	public int currentButtonId(){
		return this.buttonList.size();
	}
	
	public List<GuiButton> getButtons(){
		return buttonList;
	}

	public void returnControlInputToPlayerInventory() {
		if(container.input.getHasStack()) {
			handleMouseClick(container.input, container.input.slotNumber, 0, ClickType.QUICK_MOVE);
		}
	}
	
	@Override
	public void drawScreen(int mouseX, int mouseY, float partialTicks) {
		super.drawScreen(mouseX, mouseY, partialTicks);
		SubElement open = top();
		if (open != null) {
			open.drawScreen();
		}
		super.renderHoveredToolTip(mouseX, mouseY);
	}
	
	@Override
	protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
		int sWidth = res.getScaledWidth();
        int sHeight = res.getScaledHeight();
        this.mouseX = Mouse.getX() * sWidth / (float)this.mc.displayWidth;
        this.mouseY = sHeight - Mouse.getY() * sHeight / (float)this.mc.displayHeight - 1;
		super.drawDefaultBackground();
		GlStateManager.color(1, 1, 1, 1);
		this.mc.getTextureManager().bindTexture(texture);

		drawTexturedModalRect(guiLeft, guiTop, 0, 0, xSize, ySize);
		
		SubElement open = top();
		if (open != null) {
			open.renderBackground();
		}
	}
	
	@Override
	protected void actionPerformed(GuiButton button) throws IOException {
		SubElement open = top();
		if(open != null && !open.lock)
			open.actionPerformed(button);
	}
	
	public void pushElement(SubElement e){
		SubElement open = top();
		if (open != null) {
			open.enableButtons(false);
			open.onElementClose();
		}
		e.lock = true;
		e.enableButtons(true);
		e.onElementOpen();
		subElementStack.addFirst(e);
	}
	
	public void popElement(){
		// Back to the placement menu, and no further: popping the last element
		// used to leave an empty stack for the next tick to trip over.
		if (subElementStack.size() <= 1) {
			return;
		}
		SubElement e = subElementStack.removeFirst();
		e.enableButtons(false);
		e.onElementClose();
		SubElement open = top();
		if (open != null) {
			open.enableButtons(true);
			open.onElementOpen();
		}
	}
	
	protected void resetStack(){
		SubElement open = top();
		if (open != null) {
			open.enableButtons(false);
			open.onElementClose();
		}
		subElementStack.clear();
		subElementStack.addFirst(placement);
		placement.enableButtons(true);
	}
	
	@Override
	protected void keyTyped(char typedChar, int keyCode) throws IOException {
		if(!this.mc.gameSettings.keyBindInventory.isActiveAndMatches(keyCode))
			super.keyTyped(typedChar, keyCode);
		SubElement open = top();
		if (open != null) {
			open.keyTyped(typedChar, keyCode);
		}
	}
	
	@Override
	protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException{
		super.mouseClicked(mouseX, mouseY, mouseButton);
		SubElement open = top();
		if (open != null) {
			open.lock = false;
			open.mouseClicked(mouseX, mouseY, mouseButton);
		}
	}
	
	@Override
	protected void mouseReleased(int mouseX, int mouseY, int state){
		super.mouseReleased(mouseX, mouseY, state);
		SubElement open = top();
		if (open != null) {
			open.mouseReleased(mouseX, mouseY, state);
		}
	}
	
	@Override
	public void updateScreen(){
		super.updateScreen();
		SubElement open = top();
		if (open != null) {
			open.update();
		}
	}
	
}

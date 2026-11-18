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

import com.hbmzz.cecp.ContainerControlEdit.SlotDisableable;
import com.hbmzz.cecp.ContainerControlEdit.SlotItemHandlerDisableable;
import net.minecraft.block.Block;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

import java.util.*;
import java.util.Map.Entry;

public class SubElementLinker extends SubElement {

	public static ResourceLocation inv_tex = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/gui_linker_add_element.png");
	
	public GuiButton clear;
	public GuiButton accept;
	public GuiButton pageLeft;
	public GuiButton pageRight;
	public GuiButton cont;
	public GuiButton back;

	public List<GuiLinkerButton> linkedButtons = new ArrayList<>();
	public Map<GuiLinkerButton,BlockPos> linkerToPosMap = new HashMap<>();
	public final Set<BlockPos> linkedPositions = new LinkedHashSet<>();
	public final Map<BlockPos,String> tags = new LinkedHashMap<>();
	public int numPages = 1;
	public int currentPage = 1;
	public boolean invalid = false;
	/** Result line of the last import, shown under the title; null for none. */
	public String status;
	public boolean statusError;
	
	public SubElementLinker(GuiControlEdit gui){
		super(gui);
	}
	
	@Override
	protected void initGui() {
		int cX = gui.width/2;
		int cY = gui.height/2;
		back = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+219, gui.getGuiTop()+13, 30, 20, "返回"));
		clear = gui.addButton(new GuiButton(gui.currentButtonId(), cX-121, cY-93, 40, 20, "清空"));
		accept = gui.addButton(new GuiButton(gui.currentButtonId(), cX-101, cY-116, 20, 20, ">"));
		pageLeft = gui.addButton(new GuiButton(gui.currentButtonId(), cX-60, cY-16, 20, 20, "<"));
		pageRight = gui.addButton(new GuiButton(gui.currentButtonId(), cX+90, cY-16, 20, 20, ">"));
		cont = gui.addButton(new GuiButton(gui.currentButtonId(), cX-60, cY+6, 170, 20, "继续"));

		super.initGui();
		refreshButtons();
	}
	
	@Override
	protected void drawScreen() {
		int cX = gui.width / 2;
		int cY = gui.height / 2;

		ItemStack stack = gui.container.inventorySlots.get(0).getStack();
		accept.enabled = com.hbmzz.cecp.compat.LinkerItems.isLinker(stack);

		String text = currentPage + "/" + numPages;
		gui.getFontRenderer().drawString(text, cX + 16, cY - 10, 0xFF777777, false);
		text = "建立连接";
		gui.getFontRenderer().drawString(text, cX - gui.getFontRenderer().getStringWidth(text) / 2F + 10, cY - 110, 0xFF777777, false);

		String hint;
		int color;
		if (status != null) {
			hint = status;
			color = statusError ? 0xFFAA3333 : 0xFF33AA33;
		} else if (stack.isEmpty()) {
			hint = "放入 多发起爆器 或 监控数据线，再按 >";
			color = 0xFF777777;
		} else if (!com.hbmzz.cecp.compat.LinkerItems.isLinker(stack)) {
			hint = "这个物品不能用：要 多发起爆器 或 监控数据线";
			color = 0xFFAA3333;
		} else {
			int[][] locs = com.hbmzz.cecp.compat.LinkerItems.locations(stack);
			int recorded = locs == null ? 0 : locs[0].length;
			hint = recorded == 0
					? "里面还没有坐标：先用它右键机器记录"
					: "已记录 " + recorded + " 个坐标，按 > 导入";
			color = 0xFF777777;
		}
		gui.getFontRenderer().drawString(hint,
				cX - gui.getFontRenderer().getStringWidth(hint) / 2F + 10, cY - 97, color, false);
	}
	
	@Override
	protected void renderBackground() {
		gui.mc.getTextureManager().bindTexture(inv_tex);
		gui.drawTexturedModalRect(gui.getGuiLeft(), gui.getGuiTop(), 0, 0, gui.getXSize(), gui.getYSize());
	}
	
	private void recalculateVisibleButtons(){
		for(GuiLinkerButton b : linkedButtons){
			b.setVisible(false);
			b.setEnabled(false);
		}
		int idx = (currentPage-1)*3;
		for(int i = idx; i < idx+3; i ++) {
			if(i >= linkedButtons.size()) //TODO: when block gone, remove from linked
				break;
			linkedButtons.get(i).setVisible(true);
			linkedButtons.get(i).setEnabled(true);
		}
		boolean showPaging = numPages > 1;
		pageLeft.visible = showPaging && currentPage > 1;
		pageLeft.enabled = pageLeft.visible;
		pageRight.visible = showPaging && currentPage < numPages;
		pageRight.enabled = pageRight.visible;
	}

	@Override
	public void onClose() {
		super.onClose();
		Keyboard.enableRepeatEvents(false);
	}

	@Override
	protected void actionPerformed(GuiButton button){

		if(button == accept){
			ItemStack stack = gui.container.inventorySlots.get(0).getStack();
			if (!com.hbmzz.cecp.compat.LinkerItems.isLinker(stack)) {
				status = "这个物品不能用：要 多发起爆器 或 监控数据线";
				statusError = true;
			} else {
				int[][] locs = com.hbmzz.cecp.compat.LinkerItems.locations(stack);
				final World world = gui.control.getWorld();
				LinkerImport.Result result = LinkerImport.apply(locs, new LinkerImport.Resolver() {
					@Override
					public IControllable at(BlockPos pos) {
						return com.hbmzz.cecp.compat.CPMachines.at(world, pos);
					}

					@Override
					public String label(BlockPos pos, IControllable machine) {
						// Show the position that is actually stored, i.e. the core of a
						// multi-block machine, not the filler block the tool recorded.
						BlockPos owner = machine.getControlPos();
						return com.hbmzz.cecp.compat.CPMachines.describe(world, pos)
								+ " (" + owner.getX() + ", " + owner.getY() + ", " + owner.getZ() + ")";
					}
				}, linkedPositions, tags);
				refreshButtons();
				status = describeResult(result);
				statusError = result.recognized() == 0;
				if (result.recognized() > 0) {
					// Hand the tool back so the next machine can be recorded and
					// imported without a detour through the inventory.
					gui.returnControlInputToPlayerInventory();
				}
			}
		} else if(button == clear){
			linkedPositions.clear();
			tags.clear();
			refreshButtons();
		} else if(button == back){
			gui.returnControlInputToPlayerInventory();
			gui.popElement();
		} else if(button == cont){
			if (!invalid) {
				syncCurrentEditControlConnections();
				gui.eventEditor.accumulateEventTypes(getLinked());
				gui.eventEditor.populateDefaultNodes();
				gui.returnControlInputToPlayerInventory();
				gui.pushElement(gui.eventEditor);
			}
		} else if(button == pageLeft){
			currentPage = Math.max(1, currentPage - 1);
			recalculateVisibleButtons();
		} else if(button == pageRight){
			currentPage = Math.min(numPages, currentPage + 1);
			recalculateVisibleButtons();
		} else if(linkerToPosMap.containsKey(button)){
			BlockPos p = linkerToPosMap.get(button);
			linkedPositions.remove(p);
			tags.remove(p);
			refreshButtons();
		}
	}

	List<IControllable> getLinked() {
		World world = gui.control.getWorld();
		List<IControllable> list = new ArrayList<>();
		for (BlockPos p : linkedPositions) {
			// NTM machines come back wrapped, so the event editor below sees their
			// events and keys exactly like it sees a console's.
			IControllable machine = com.hbmzz.cecp.compat.CPMachines.at(world, p);
			if (machine != null) {
				list.add(machine);
			}
		}
		return list;
	}

	void reloadLinkedFromCurrentEditControl() {
		linkedPositions.clear();
		tags.clear();
		if(gui.currentEditControl == null) {
			refreshButtons();
			return;
		}

		for (Entry<String,BlockPos> entry : gui.currentEditControl.taggedLinks.entrySet()) {
			linkedPositions.add(entry.getValue());
			tags.put(entry.getValue(),entry.getKey());
		}
		refreshButtons();
	}

	void syncCurrentEditControlConnections() {
		if(gui.currentEditControl == null) {
			return;
		}
		gui.currentEditControl.taggedLinks.clear();
		for (Entry<BlockPos,String> entry : tags.entrySet())
			gui.currentEditControl.taggedLinks.put(entry.getValue(),entry.getKey());
	}
	
	protected void refreshButtons(){
		gui.getButtons().removeAll(linkedButtons);
		linkedButtons.clear();
		linkerToPosMap.clear();
		int i = 0;
		int cX = gui.width/2;
		int cY = gui.height/2;
		for (BlockPos pos : linkedPositions) {
			GuiLinkerButton button = new GuiLinkerButton(gui.mc.fontRenderer,gui.currentButtonId(), cX-73, cY-90 + i*22, 170, 20, tags.getOrDefault(pos,"ERROR"));
			linkedButtons.add(button);
			linkerToPosMap.put(button,pos);
			i = (i+1)%3;
		}
		for(GuiButton b : linkedButtons)
			gui.addButton(b);
		numPages = Math.max(1, (linkedPositions.size()+2)/3);
		currentPage = MathHelper.clamp(currentPage, 1, numPages);
		recalculateVisibleButtons();
	}

	/**
	 * Plain-language outcome of one import.
	 *
	 * <p>The old code was silent on failure, which is why "the cable does nothing"
	 * took three rounds to pin down: a coordinate that resolved to nothing simply
	 * vanished. Every branch now says what happened and why.</p>
	 */
	private static String describeResult(LinkerImport.Result result) {
		if (result.isEmpty()) {
			return "物品里没有坐标：先用它右键机器记录";
		}
		if (result.recognized() == 0) {
			return "读到 " + result.recorded + " 个坐标，但没有一个受面板控制";
		}
		StringBuilder text = new StringBuilder();
		if (result.added > 0) {
			text.append("新增 ").append(result.added).append(" 台机器");
		} else {
			text.append("列表里已经有了");
		}
		if (result.duplicate > 0) {
			text.append("，重复 ").append(result.duplicate).append(" 个坐标");
		}
		if (result.rejected > 0) {
			text.append("，忽略 ").append(result.rejected).append(" 个非可控坐标");
		}
		return text.toString();
	}

	@Override
	protected void keyTyped(char typedChar,int code) {
		super.keyTyped(typedChar,code);
		for(GuiLinkerButton b : linkedButtons)
			b.keyTyped(typedChar,code);
	}

	@Override
	protected void mouseClicked(int mouseX,int mouseY,int button) {
		super.mouseClicked(mouseX,mouseY,button);
		for(GuiLinkerButton b : linkedButtons)
			b.mouseClicked(mouseX,mouseY,button);
	}

	@Override
	protected void update() {
		super.update();
		Keyboard.enableRepeatEvents(true);
		for(GuiLinkerButton b : linkedButtons)
			b.update();
		invalid = false;
		tags.clear();
		Set<String> seenTags = new HashSet<>();
		Set<String> invalidTags = new HashSet<>();
		for(GuiLinkerButton b : linkedButtons) {
			BlockPos p = linkerToPosMap.get(b);
			String tag = b.field.getText();
			b.field.setTextColor(0xFFFFFF);
			if (!seenTags.add(tag)) {
				invalid = true;
				invalidTags.add(tag);
			} else
				tags.put(p,tag);
		}
		for(GuiLinkerButton b : linkedButtons) {
			String tag = b.field.getText();
			if (invalidTags.contains(tag))
				b.field.setTextColor(0xFF0000);
		}
		cont.enabled = lastEnable && !invalid;
	}

	boolean lastEnable = true;

	@Override
	protected void enableButtons(boolean enable) {
		lastEnable = enable;
		if(enable){
			recalculateVisibleButtons();
		} else {
			for(GuiLinkerButton b : linkedButtons){
				b.setVisible(false);
				b.setEnabled(false);
			}
		}
		clear.enabled = enable;
		clear.visible = enable;
		accept.enabled = enable;
		accept.visible = enable;
		if(enable){
			pageLeft.visible = numPages > 1 && currentPage > 1;
			pageLeft.enabled = pageLeft.visible;
			pageRight.visible = numPages > 1 && currentPage < numPages;
			pageRight.enabled = pageRight.visible;
		} else {
			pageLeft.visible = false;
			pageLeft.enabled = false;
			pageRight.visible = false;
			pageRight.enabled = false;
		}
		cont.enabled = enable && !invalid;
		cont.visible = enable;
		back.enabled = enable;
		back.visible = enable;
		SlotItemHandlerDisableable s = (SlotItemHandlerDisableable)gui.container.inventorySlots.get(0);
		s.isEnabled = enable;
		for(SlotDisableable slot : gui.container.invSlots){
			slot.isEnabled = enable;
		}
	}
	
}

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

import net.minecraft.client.gui.GuiButton;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;

public class SubElementEventEditor extends SubElement {

	public List<ControlEvent> receiveEvents = new ArrayList<>();
	public List<ControlEvent> sendEvents = new ArrayList<>();
	public List<GuiButton> receiveButtons = new ArrayList<>();
	public List<GuiButton> sendButtons = new ArrayList<>();
	//public Map<ControlEvent, NodeSystem> sendNodeMap = new HashMap<>();
	//public Map<ControlEvent, NodeSystem> receiveNodeMap = new HashMap<>();
	public int currentReceivePage = 1;
	public int numReceivePages = 1;
	public int currentSendPage = 1;
	public int numSendPages = 1;
	public GuiButton receivePageLeft;
	public GuiButton receivePageRight;
	public GuiButton sendPageLeft;
	public GuiButton sendPageRight;
	public GuiButton done;
	public GuiButton back;

	public SubElementEventEditor(GuiControlEdit gui){
		super(gui);
	}
	
	@Override
	protected void initGui(){
		int cX = gui.width/2;
		int cY = gui.height/2;
		receivePageLeft = gui.addButton(new GuiButton(gui.currentButtonId(), cX-62, cY-29, 20, 20, "<"));
		receivePageRight = gui.addButton(new GuiButton(gui.currentButtonId(), cX+88, cY-29, 20, 20, ">"));
		sendPageLeft = gui.addButton(new GuiButton(gui.currentButtonId(), cX-62, cY+70, 20, 20, "<"));
		sendPageRight = gui.addButton(new GuiButton(gui.currentButtonId(), cX+88, cY+70, 20, 20, ">"));
		done = gui.addButton(new GuiButton(gui.currentButtonId(), cX-62, cY+92, 170, 20, "完成"));
		back = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+7, gui.getGuiTop()+13, 30, 20, "返回"));
		super.initGui();
	}
	
	protected void accumulateEventTypes(List<IControllable> list){
		// Cheap and idempotent: keeps the list in step with CE's registry even if
		// an addon filled it after this mod's preInit ran.
		com.hbmzz.cecp.compat.CEEventTable.mirror();
		gui.getButtons().removeAll(receiveButtons);
		gui.getButtons().removeAll(sendButtons);
		receiveButtons.clear();
		sendButtons.clear();
		receiveEvents.clear();
		sendEvents.clear();
		// these at top cus more consistent when u need to employ fuckery when getting an EventData node by indexing receivable[].
		for(String name : gui.currentEditControl.getOutEvents()){
			ControlEvent evt = ControlEvent.getOrRegister(name);
			if(!receiveEvents.contains(evt))
				receiveEvents.add(ControlEvent.getOrRegister(name));
		}
		for(String name : gui.currentEditControl.getInEvents()){
			ControlEvent evt = ControlEvent.getOrRegister(name);
			if(!receiveEvents.contains(evt))
				receiveEvents.add(ControlEvent.getOrRegister(name));
		}
		for(IControllable c : list){
			for(String name : c.getOutEvents()){
				ControlEvent evt = ControlEvent.getOrRegister(name);
				if(!receiveEvents.contains(evt))
					receiveEvents.add(ControlEvent.getOrRegister(name));
			}
			for(String name : c.getInEvents()){
				ControlEvent evt = ControlEvent.getOrRegister(name);
				if(!sendEvents.contains(evt))
					sendEvents.add(ControlEvent.getOrRegister(name));
			}
		}
		int cX = gui.width/2;
		int cY = gui.height/2;
		
		numReceivePages = (receiveEvents.size()+2)/3;
		for(int i = 0; i < receiveEvents.size(); i ++){
			int offset = (i%3)*25;
			receiveButtons.add(gui.addButton(new ButtonHoverText(i+1000, cX-62, cY-100+offset, 170, 20, receiveEvents.get(i).name, "<Click to edit>")));
		}
		currentReceivePage = MathHelper.clamp(currentReceivePage, 1, numReceivePages);
		
		numSendPages = (sendEvents.size()+2)/3;
		for(int i = 0; i < sendEvents.size(); i ++){
			int offset = (i%3)*25;
			sendButtons.add(gui.addButton(new ButtonHoverText(i+2000, cX-62, cY+5+offset, 170, 20, sendEvents.get(i).name, "<Click to edit>")));
		}
		currentSendPage = MathHelper.clamp(currentSendPage, 1, numSendPages);
	}

	public void populateDefaultNodes() {
		if (!gui.isEditMode
				&& gui.currentEditControl != null
				&& gui.currentEditControl.receiveNodeMap.isEmpty()
				&& gui.currentEditControl.sendNodeMap.isEmpty()) {
			gui.currentEditControl.populateDefaultNodes(receiveEvents);
		}
	}
	
	private void recalculateVisibleButtons(){
		for(GuiButton b : receiveButtons){
			b.visible = false;
			b.enabled = false;
		}
		for(GuiButton b : sendButtons){
			b.visible = false;
			b.enabled = false;
		}
		int idx = (currentReceivePage-1)*3;
		for(int i = idx; i < idx+3; i ++){
			if(i >= receiveButtons.size())
				break;
			receiveButtons.get(i).visible = true;
			receiveButtons.get(i).enabled = true;
		}
		if(sendButtons.size() > 0){
			idx = (currentSendPage-1)*3;
			for(int i = idx; i < idx+3; i ++){
				if(i >= sendButtons.size())
					break;
				sendButtons.get(i).visible = true;
				sendButtons.get(i).enabled = true;
			}
		}
		boolean showReceivePaging = numReceivePages > 1;
		receivePageLeft.visible = showReceivePaging && currentReceivePage > 1;
		receivePageLeft.enabled = receivePageLeft.visible;
		receivePageRight.visible = showReceivePaging && currentReceivePage < numReceivePages;
		receivePageRight.enabled = receivePageRight.visible;

		boolean showSendPaging = numSendPages > 1;
		sendPageLeft.visible = showSendPaging && currentSendPage > 1;
		sendPageLeft.enabled = sendPageLeft.visible;
		sendPageRight.visible = showSendPaging && currentSendPage < numSendPages;
		sendPageRight.enabled = sendPageRight.visible;
	}
	
	@Override
	protected void drawScreen(){
		int cX = gui.width/2;
		int cY = gui.height/2;
		String text = currentReceivePage + "/" + numReceivePages;
		gui.getFontRenderer().drawString(text, cX+12, cY-21, 0xFF777777, false);
		text = currentSendPage + "/" + numSendPages;
		gui.getFontRenderer().drawString(text, cX+12, cY+76, 0xFF777777, false);
		text = "可接收事件";
		gui.getFontRenderer().drawString(text, cX - gui.getFontRenderer().getStringWidth(text) / 2F + 22, cY-112, 0xFF777777, false);
		text = "可发送事件";
		gui.getFontRenderer().drawString(text, cX - gui.getFontRenderer().getStringWidth(text) / 2F + 22, cY-5, 0xFF777777, false);
	}
	
	@Override
	protected void actionPerformed(GuiButton button){
		if (button == back) {
			gui.popElement();
		} else if(button == receivePageLeft){
			if(numReceivePages != 0){
				currentReceivePage = Math.max(1, currentReceivePage - 1);
				recalculateVisibleButtons();
			}
		} else if(button == receivePageRight){
			currentReceivePage = Math.min(numReceivePages, currentReceivePage + 1);
			recalculateVisibleButtons();
		} else if(button == sendPageLeft){
			if(numSendPages != 0){
				currentSendPage = Math.max(1, currentSendPage - 1);
				recalculateVisibleButtons();
			}
		} else if(button == sendPageRight){
			currentSendPage = Math.min(numSendPages, currentSendPage + 1);
			recalculateVisibleButtons();
		} else if(button == done){
			gui.linker.syncCurrentEditControlConnections();
			gui.currentEditControl.receiveEvent(ControlEvent.newEvent("initialize"));
			if (!gui.isEditMode) {
				float[] gridMouse = gui.placement.convertToGridSpace(gui.mouseX, gui.mouseY);
				gui.currentEditControl.posX = gridMouse[0];
				gui.currentEditControl.posY = gridMouse[1];
				gui.placement.resetPrevPos();
			}
			else {
				gui.currentEditControl = null;
				gui.isEditMode = false;
			}
			gui.resetStack();
		} else {
			int i = receiveButtons.indexOf(button);
			if(i != -1){
				gui.nodeEditor.setData(gui.currentEditControl.receiveNodeMap, receiveEvents.get(i), sendEvents);
			} else {
				i = sendButtons.indexOf(button);
				if(i != -1){
					gui.nodeEditor.setData(gui.currentEditControl.sendNodeMap, sendEvents.get(i), null);
				}
			}
			if(i != -1){
				gui.pushElement(gui.nodeEditor);
			}
		}
	}
	
	@Override
	protected void enableButtons(boolean enable){
		if(enable){
			recalculateVisibleButtons();
		} else {
			for(GuiButton b : receiveButtons){
				b.visible = false;
				b.enabled = false;
			}
			for(GuiButton b : sendButtons){
				b.visible = false;
				b.enabled = false;
			}
		}
		if(enable){
			receivePageLeft.visible = numReceivePages > 1 && currentReceivePage > 1;
			receivePageLeft.enabled = receivePageLeft.visible;
			receivePageRight.visible = numReceivePages > 1 && currentReceivePage < numReceivePages;
			receivePageRight.enabled = receivePageRight.visible;
			sendPageLeft.visible = numSendPages > 1 && currentSendPage > 1;
			sendPageLeft.enabled = sendPageLeft.visible;
			sendPageRight.visible = numSendPages > 1 && currentSendPage < numSendPages;
			sendPageRight.enabled = sendPageRight.visible;
		} else {
			receivePageLeft.visible = false;
			receivePageLeft.enabled = false;
			receivePageRight.visible = false;
			receivePageRight.enabled = false;
			sendPageLeft.visible = false;
			sendPageLeft.enabled = false;
			sendPageRight.visible = false;
			sendPageRight.enabled = false;
		}
		done.visible = enable;
		done.enabled = enable;
		back.visible = enable;
		back.enabled = enable;
	}

}

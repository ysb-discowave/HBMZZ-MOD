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

import com.hbmzz.cecp.modular.INodeMenuCreator;
import com.hbmzz.cecp.modular.NTMControlPanelRegistry;
import com.hbmzz.cecp.nodes.*;
import com.hbmzz.cecp.compat.CPEnv;
import com.hbmzz.cecp.compat.GuiFx;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;

import java.util.*;

public class SubElementNodeEditor extends SubElement {

	public static ResourceLocation texture = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/gui_placement_front.png");
	public static ResourceLocation grid = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/grid.png");
	private static NBTTagCompound clipboardNodes;
	
	public GuiButton btn_back;
	public GuiButton btn_variables;

	public ItemList addMenu;

	public NodeSystem currentSystem;
	public Deque<NodeSystem> systemHistoryStack = new ArrayDeque<>();

	public ControlEvent currentEvent;
	public List<ControlEvent> sendEvents;
	public boolean gridGrabbed = false;
	public float gridX = 0;
	public float gridY = 0;
	public float gridScale = 1;
	public float prevMouseX;
	public float prevMouseY;
	
	public SubElementNodeEditor(GuiControlEdit gui){
		super(gui);
	}
	
	protected void setData(Map<String, NodeSystem> map, ControlEvent c, List<ControlEvent> sendEvents){
		currentSystem = map.computeIfAbsent(c.name, e -> new NodeSystem(gui.currentEditControl, this));
		currentSystem.nodeEditor = this;
		currentSystem.gui = gui;
		currentSystem.activeNode = null;
		currentSystem.selectedNodes = new ArrayList<>();
		currentSystem.drag = false;
		currentSystem.dragDist = 0;
		
		currentEvent = c;
		gridX = 0;
		gridY = 0;
		gridScale = 1;
		this.sendEvents = sendEvents;
		alignEventPorts(currentSystem, c, sendEvents == null);
	}

	/**
	 * Brings every event-data node in this graph back in line with the event it
	 * is fed by.
	 *
	 * <p>Same story as the broadcast node's {@code repairPorts}: a graph saved
	 * while an event's variables were unknown on disk carries whatever ports it
	 * had at the time, and loading alone would never grow them back. Ports are
	 * matched by name so an existing wire follows its variable when the row is
	 * rebuilt, and a wire whose variable no longer exists is dropped rather than
	 * left pointing at an index that now means something else.</p>
	 */
	private void alignEventPorts(NodeSystem system, ControlEvent evt, boolean sendGraph){
		if(system == null || evt == null){
			return;
		}
		Map<String, com.hbmzz.cecp.types.DataValue> vars = new LinkedHashMap<>(evt.vars);
		if(sendGraph){
			vars.put("to index", new com.hbmzz.cecp.types.DataValueFloat(0));
		} else {
			vars.put("tag", new com.hbmzz.cecp.types.DataValueString(""));
			vars.put("from index", new com.hbmzz.cecp.types.DataValueFloat(0));
		}
		for(Node node : system.nodes){
			if(node instanceof NodeInput && NodeInput.EVENT_DATA.equals(((NodeInput) node).name)){
				alignEventInput(system, (NodeInput) node, vars);
			}
			if(node instanceof NodeFunction){
				NodeSystem sub = system.subSystems.get(node);
				if(sub != null){
					alignEventPorts(sub, evt, sendGraph);
				}
			}
		}
	}

	private void alignEventInput(NodeSystem system, NodeInput node, Map<String, com.hbmzz.cecp.types.DataValue> vars){
		List<NodeConnection> old = new ArrayList<>(node.outputs);
		if(old.size() == vars.size()){
			boolean identical = true;
			int i = 0;
			for(String key : vars.keySet()){
				if(!key.equals(old.get(i).name)){
					identical = false;
					break;
				}
				i++;
			}
			if(identical){
				return;
			}
		}
		Map<String, Integer> moved = new HashMap<>();
		node.outputs.clear();
		for(Map.Entry<String, com.hbmzz.cecp.types.DataValue> e : vars.entrySet()){
			com.hbmzz.cecp.types.DataValue def = e.getValue() == null
					? new com.hbmzz.cecp.types.DataValueFloat(0) : e.getValue();
			moved.put(e.getKey(), node.outputs.size());
			node.outputs.add(new NodeConnection(e.getKey(), node, node.outputs.size(), false, def.getType(), def.copy()));
		}
		node.recalcSize();
		for(Node other : system.nodes){
			for(NodeConnection port : other.inputs){
				if(port.connection != node || port.connectionIndex < 0 || port.connectionIndex >= old.size()){
					continue;
				}
				Integer now = moved.get(old.get(port.connectionIndex).name);
				if(now == null){
					port.connection = null;
					port.connectionIndex = -1;
					port.drawsLine = false;
				} else {
					port.connectionIndex = now;
				}
			}
		}
	}

	public void descendSubsystem(Node node) {
		systemHistoryStack.push(currentSystem);
		currentSystem = currentSystem.subSystems.get(node);

		currentSystem.nodeEditor = this;
		currentSystem.gui = gui;
		currentSystem.activeNode = null;
		currentSystem.selectedNodes = new ArrayList<>();
		currentSystem.drag = false;
		currentSystem.dragDist = 0;
	}
	
	@Override
	protected void initGui(){
		int cX = gui.width/2;
		int cY = gui.height/2;
		btn_back = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+7, gui.getGuiTop()+13, 30, 20, "返回"));
		btn_variables = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+54, gui.getGuiTop()+13, 58, 20, "变量"));
		super.initGui();
	}
	
	@Override
	protected void keyTyped(char typedChar, int code){
		boolean isTyping = false;
		if(currentSystem != null)
			isTyping = currentSystem.keyTyped(typedChar, code);
		if (isTyping) return;
		boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
		if(currentSystem != null && currentSystem.currentTypingBox == null && ctrl) {
			if(code == Keyboard.KEY_C || code == Keyboard.KEY_X) {
				copySelectionToClipboard();
				return;
			}
			if(code == Keyboard.KEY_V) {
				if(canPasteClipboard()) {
					pasteClipboardAtMouse();
				}
				return;
			}
		}
		if(code == Keyboard.KEY_A && Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)){
			if(addMenu != null){
				addMenu.close();
			}
			addMenu = new ItemList(gui.mouseX, gui.mouseY, 32, s -> {
				final float x = (gui.mouseX-gui.getGuiLeft())*gridScale + gui.getGuiLeft() + gridX;
				final float y = (gui.mouseY-gui.getGuiTop())*gridScale + gui.getGuiTop() - gridY;
				if("粘贴".equals(s)) {
					pasteClipboard(x, y);
					addMenu.close();
					addMenu = null;
					return null;
				}
				List<INodeMenuCreator> controllers = NTMControlPanelRegistry.addMenuControl.get(s.substring(("{expandable}").length()));
				if (controllers != null) {
					ItemList list = new ItemList(0, 0, 32,s2 -> {
						for (INodeMenuCreator controller : controllers) {
							Node node = controller.selectItem(s2,x,y,this);
							if (node != null) {
								addMenu.close();
								addMenu = null;
								currentSystem.addNode(node);
								currentSystem.activeNode = node;
								break;
							}
						}
						return null;
					});
					for (INodeMenuCreator controller : controllers)
						controller.addItems(list,x,y,this);
					return list;
				}
				return null;
			});
			if(canPasteClipboard()) {
				addMenu.addItems("粘贴");
			}
			for (String item : NTMControlPanelRegistry.addMenuCategories)
				addMenu.addItems("{expandable}"+item);
		}
		if(code == Keyboard.KEY_DELETE || code == Keyboard.KEY_X){
			List<Node> selected = new ArrayList<>(currentSystem.selectedNodes);
			for(Node n : selected){
				currentSystem.removeNode(n);
			}
		}
	}

	private static boolean hasClipboardData() {
		return clipboardNodes != null && !clipboardNodes.hasNoTags();
	}

	private boolean canPasteClipboard() {
		return currentSystem != null
				&& currentEvent != null
				&& hasClipboardData()
				&& currentSystem.canPasteFromNBT(clipboardNodes, currentEvent, sendEvents == null);
	}

	private void copySelectionToClipboard() {
		if(currentSystem == null || !currentSystem.hasSelectedNodes()) {
			return;
		}
		NBTTagCompound tag = currentSystem.writeSelectedToNBT();
		clipboardNodes = tag != null ? tag.copy() : null;
	}

	private void pasteClipboardAtMouse() {
		if(currentSystem == null) {
			return;
		}
		float x = (gui.mouseX-gui.getGuiLeft())*gridScale + gui.getGuiLeft() + gridX;
		float y = (gui.mouseY-gui.getGuiTop())*gridScale + gui.getGuiTop() - gridY;
		pasteClipboard(x, y);
	}

	private void pasteClipboard(float x, float y) {
		if(!canPasteClipboard()) {
			return;
		}

		List<Node> pastedNodes = currentSystem.pasteFromNBT(clipboardNodes.copy(), x, y);
		if(pastedNodes.isEmpty()) {
			return;
		}

		currentSystem.selectedNodes.clear();
		currentSystem.selectedNodes.addAll(pastedNodes);
		currentSystem.activeNode = pastedNodes.get(pastedNodes.size() - 1);
		currentSystem.connectionInProgress = null;
		if(currentSystem.currentTypingBox != null) {
			currentSystem.currentTypingBox.stopTyping();
			currentSystem.currentTypingBox = null;
		}
	}
	
	@Override
	protected void renderBackground(){
		gui.mc.getTextureManager().bindTexture(texture);
		gui.drawTexturedModalRect(gui.getGuiLeft(), gui.getGuiTop(), 0, 0, gui.getXSize(), gui.getYSize());
	}
	
	@Override
	protected void drawScreen(){
		int cX = gui.width/2;
		int cY = gui.height/2;
		String hint = "Shift+A 添加节点";
		gui.getFontRenderer().drawString(hint, cX - gui.getFontRenderer().getStringWidth(hint) / 2F + 45, cY-108, 0xFF777777, false);

		/*
		 * CE turns the unicode font off here because every label inside the node
		 * graph is plain ASCII and the ASCII sheet draws crisper at the tiny
		 * scales the graph uses.  The Chinese port puts those labels in Chinese,
		 * and with the flag off FontRenderer hashes any char above 0xFF down to
		 * `ch % 256` and samples ascii.png - every node title, dropdown entry and
		 * parameter label came out as mojibake.  Keep the unicode sheet on for
		 * the duration of the graph instead; it is still one flag flip per frame,
		 * so it costs nothing measurable.
		 */
		boolean unicode = gui.getFontRenderer().getUnicodeFlag();
		gui.getFontRenderer().setUnicodeFlag(true);

		float dWheel = Mouse.getDWheel();
		float dScale = dWheel*gridScale*0.00075F;
		
		//Correction so we scale around mouse position
		float prevX = (gui.mouseX-gui.getGuiLeft())*gridScale;
		float prevY = (gui.mouseY-gui.getGuiTop())*gridScale;
		gridScale = MathHelper.clamp(gridScale-dScale, 0.25F, 2.5F);
		float currentX = (gui.mouseX-gui.getGuiLeft())*gridScale;
		float currentY = (gui.mouseY-gui.getGuiTop())*gridScale;
		gridX += prevX-currentX;
		gridY -= prevY-currentY;
		
		if(gridGrabbed){
			float dX = gui.mouseX-prevMouseX;
			float dY = gui.mouseY-prevMouseY;
			gridX -= dX*gridScale;
			gridY += dY*gridScale;
			prevMouseX = gui.mouseX;
			prevMouseY = gui.mouseY;
		}
		GlStateManager.disableLighting();
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		int minX = (cX-72)*gui.res.getScaleFactor();
		int minY = (cY-114)*gui.res.getScaleFactor();
		int maxX = (cX+120)*gui.res.getScaleFactor();
		int maxY = (cY+78)*gui.res.getScaleFactor();
		/*
		 * glScissor works in window pixels with the origin at the bottom-left,
		 * while everything above is in GUI units with the origin at the
		 * top-left. Upstream feeds minY straight in, which mirrors the box
		 * vertically: the visible band ends up 36 units lower than the panel, so
		 * the top of the canvas - and any node parked near it - is clipped
		 * away. Flip it against the real framebuffer height instead.
		 */
		int fbHeight = gui.mc.displayHeight;
		GL11.glScissor(minX, fbHeight - maxY, maxX-minX, maxY-minY);
		
		////GlStateManager.matrixMode(GL11.GL_TEXTURE);
		//GlStateManager.pushMatrix();
		gui.mc.getTextureManager().bindTexture(grid);
		float x = gridX/gui.getXSize();
		float y = -gridY/gui.getYSize();
		//float scalePointX = x + gui.mouseX/gui.getXSize();
		//float scalePointY = y + gui.mouseY/gui.getYSize();
		//GlStateManager.translate(scalePointX, scalePointY, 0);
		//GL11.glScaled(gridScale, gridScale, 0);
		//GlStateManager.translate(-scalePointX, -scalePointY, 0);
		GuiFx.drawGuiRectColor(gui.getGuiLeft(), gui.getGuiTop(), x, y, gui.getXSize(), gui.getYSize(), gridScale+x, gridScale+y, 0.2F, 0.2F, 0.2F, 1);
		//GlStateManager.popMatrix();
		//GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		
		GlStateManager.pushMatrix();
		
		float spX = gui.getGuiLeft();
		float spY = gui.getGuiTop();
		GlStateManager.translate(spX, spY, 0);
		GlStateManager.scale(1/gridScale, 1/gridScale, 1/gridScale);
		GlStateManager.translate(-spX, -spY, 0);
		GlStateManager.translate(-gridX, gridY, 0);
		GlStateManager.getFloat(GL11.GL_MODELVIEW_MATRIX, CPEnv.AUX_GL_BUFFER);
		Matrix4f mat = new Matrix4f();
		mat.load(CPEnv.AUX_GL_BUFFER);
		//System.out.println(gui.getGuiLeft() + " " + gui.getGuiTop() + " " + mat.m30 + " " + mat.m31);
		CPEnv.AUX_GL_BUFFER.rewind();
		float gridMX = (gui.mouseX-gui.getGuiLeft())*gridScale + gui.getGuiLeft() + gridX;
		float gridMY = (gui.mouseY-gui.getGuiTop())*gridScale + gui.getGuiTop() - gridY;
		currentSystem.render(gridMX, gridMY);
		GlStateManager.popMatrix();
		if(addMenu != null){
			addMenu.render(gui.mouseX, gui.mouseY);
		}
		GL11.glDisable(GL11.GL_SCISSOR_TEST);
		gui.getFontRenderer().setUnicodeFlag(unicode);
	}
	
	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button){
		if(addMenu != null && button == 0){
			if(GuiFx.intersects2DBox(mouseX, mouseY, addMenu.getBoundingBox())){
				addMenu.mouseClicked(mouseX, mouseY);
			} else {
				addMenu.close();
				addMenu = null;
			}
		} else if(button == 0){
			// doing this here for now cus i want buttons to be able to make gui changes
			NodeElement pressed = currentSystem.getNodeElementPressed(mouseX, mouseY);
			if (pressed != null)
				pressed.onClicked(this);
			currentSystem.onClick(mouseX, mouseY);
		}
		if(button == 2){
			gridGrabbed = true;
			prevMouseX = gui.mouseX;
			prevMouseY = gui.mouseY;
		}
	}
	
	@Override
	protected void mouseReleased(int mouseX, int mouseY, int state){
		if(state == 2){
			gridGrabbed = false;
		}
		if(addMenu == null && state == 0){
			currentSystem.clickReleased(mouseX, mouseY);
		}
	}
	
	@Override
	protected void actionPerformed(GuiButton button){
		if(button == btn_back){
			if(currentSystem != null){
				currentSystem.removeClientData();
				currentSystem = null;

				if (!systemHistoryStack.isEmpty()) {
					currentSystem = systemHistoryStack.getFirst();
					systemHistoryStack.pop();
					return;
				}
			}
			gui.popElement();
		}
		if (button == btn_variables) {
			gui.currentEditControl = currentSystem.parent;
			gui.pushElement(gui.variables);
		}
	}
	
	@Override
	public void onClose(){
		if(currentSystem != null){
			currentSystem.removeClientData();
			currentSystem = null;
		}
	}
	
	@Override
	protected void enableButtons(boolean enable){
		btn_back.enabled = enable;
		btn_back.visible = enable;
		btn_variables.enabled = enable;
		btn_variables.visible = enable;
	}
	
}

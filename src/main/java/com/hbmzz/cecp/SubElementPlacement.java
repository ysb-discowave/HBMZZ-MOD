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

import com.hbmzz.cecp.controls.DialLarge;
import com.hbmzz.cecp.controls.DisplaySevenSeg;
import com.hbmzz.cecp.controls.DisplayText;
import com.hbmzz.cecp.controls.Label;
import com.hbmzz.cecp.compat.CPEnv;
import com.hbmzz.cecp.compat.CtrlRes;
import com.hbmzz.cecp.compat.GuiFx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Matrix4f;

public class SubElementPlacement extends SubElement {

	public static ResourceLocation texture = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/gui_placement_front.png");
	public static ResourceLocation grid = new ResourceLocation("hbmzz" + ":textures/gui/control_panel/placement_grid.png");
	
	public float ogPosX;
	public float ogPosY;
	public Control selectedControl;
	public boolean controlGrabbed = false;

	public GuiButton btn_panelResize;
	public GuiButton btn_security;
	public GuiButton btn_connections;
	public GuiButton btn_variables;
	public GuiButton btn_newControl;
	public GuiButton btn_editControl;
	public GuiButton btn_deleteControl;

	private boolean gridGrabbed = false;
	protected float gridX = 0;
	protected float gridY = 0;
	protected float gridScale = 0.1F;
	private float prevMouseX;
	private float prevMouseY;
	private final float[] renderBox = new float[4];

	private int ctrl_index = 0;
	
	public SubElementPlacement(GuiControlEdit gui){
		super(gui);
	}
	
	@Override
	protected void initGui() {
		int cX = gui.width/2;
		int cY = gui.height/2;
		btn_panelResize = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+7, gui.getGuiTop()+13, 43, 20, "尺寸"));
		btn_variables = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+54, gui.getGuiTop()+13, 58, 20, "变量"));
		btn_security = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+117, gui.getGuiTop()+13, 58, 20, "安全"));
		btn_connections = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+180, gui.getGuiTop()+13, 70, 20, "连接"));
		btn_newControl = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+7, gui.getGuiTop()+47, 43, 20, "新建"));
		btn_editControl = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+7, gui.getGuiTop()+69, 43, 20, "编辑"));
		btn_deleteControl = gui.addButton(new GuiButton(gui.currentButtonId(), gui.getGuiLeft()+7, gui.getGuiTop()+91, 43, 20, "删除"));
		float[] cGrid = convertToGridSpace(cX+10, cY+20);
		gridX = -cGrid[0];
		gridY = cGrid[1];
		super.initGui();
	}
	
	protected void clear(){
		ogPosX = 0;
		ogPosY = 0;
		selectedControl = null;
		gridGrabbed = false;
		controlGrabbed = false;
		gridX = 0;
		gridY = 0;
		gridScale = 1;
	}
	
	public void resetPrevPos(){
		prevMouseX = gui.mouseX;
		prevMouseY = gui.mouseY;
	}

	@Override
	protected void drawScreen() {
		float dWheel = Mouse.getDWheel();
		float dScale = dWheel*gridScale*0.00075F;

		btn_editControl.enabled = isEditableControl(selectedControl);
		btn_deleteControl.enabled = selectedControl != null;

		//Correction so we scale around mouse position
		float prevX = (gui.mouseX-gui.getGuiLeft())*gridScale;
		float prevY = (gui.mouseY-gui.getGuiTop())*gridScale;
		gridScale = MathHelper.clamp(gridScale-dScale, 0.025F, 0.15F);
		float currentX = (gui.mouseX-gui.getGuiLeft())*gridScale;
		float currentY = (gui.mouseY-gui.getGuiTop())*gridScale;
		gridX += prevX-currentX;
		gridY -= prevY-currentY;

		if(gridGrabbed || gui.currentEditControl != null){
			float dX = (gui.mouseX-prevMouseX)*gridScale;
			float dY = (gui.mouseY-prevMouseY)*gridScale;
			if(gridGrabbed){
				gridX -= dX;
				gridY += dY;
			} else if(!gui.isEditMode) {
				gui.currentEditControl.posX += dX;
				gui.currentEditControl.posY += dY;
			}
		}
		if((prevMouseX != gui.mouseX || prevMouseY != gui.mouseY) && controlGrabbed){
			ctrl_index = gui.control.panel.controls.indexOf(selectedControl);
			gui.control.panel.controls.remove(selectedControl);
			gui.currentEditControl = selectedControl;
			selectedControl = null;
			controlGrabbed = false;
			gui.control.markDirty();
		}
		prevMouseX = gui.mouseX;
		prevMouseY = gui.mouseY;
		GlStateManager.disableLighting();
		GL11.glEnable(GL11.GL_SCISSOR_TEST);
		int cX = gui.width/2;
		int cY = gui.height/2;
		int minX = (cX-72)*gui.res.getScaleFactor();
		int minY = (cY-114)*gui.res.getScaleFactor();
		int maxX = (cX+120)*gui.res.getScaleFactor();
		int maxY = (cY+78)*gui.res.getScaleFactor();
		GL11.glScissor(minX, minY, Math.max(maxX-minX, 0), Math.max(maxY-minY, 0));
		
		gui.mc.getTextureManager().bindTexture(grid);

		float baseSizeX = (1-(gui.control.panel.b_off+gui.control.panel.d_off));
		float baseSizeY = (1-(gui.control.panel.a_off+gui.control.panel.c_off));
		float panel_hyp = (float) (baseSizeY/Math.cos(Math.abs(gui.control.panel.angle)));

		float[] box = gui.control.getBox();
		float[] pos1 = convertFromGridSpace(box[0]-0.03125F, box[1]-0.03125F);
		float[] pos2 = convertFromGridSpace(box[2]+0.03125F, box[3]+0.03125F);

		float uScale = (baseSizeX*10)/4;
		float vScale = (panel_hyp*10)/4;
		GuiFx.drawGuiRect(pos1[0], pos1[1], 0, 0, pos2[0]-pos1[0], pos2[1]-pos1[1], uScale+0.015625F, vScale+0.015625F);

		// bottom,right lines to close the grid, cus somehow scaling the grid looks worse
		GlStateManager.disableTexture2D();
		GlStateManager.color(.4F, .4F, .4F, 1);
		GuiFx.drawGuiRect(pos1[0], pos2[1]-(1/(gridScale*15)), 0, 0, pos2[0]-pos1[0], 1/(gridScale*15), uScale+0.015625F, 0.015625F);
		GuiFx.drawGuiRect(pos2[0]-(1/(gridScale*15)), pos1[1], 0, 0, 1/(gridScale*15), pos2[1]-pos1[1], 0.015625F, vScale+0.015625F);
		GlStateManager.enableTexture2D();

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
		CPEnv.AUX_GL_BUFFER.rewind();
		float gridMX = (gui.mouseX-gui.getGuiLeft())*gridScale + gui.getGuiLeft() + gridX;
		float gridMY = (gui.mouseY-gui.getGuiTop())*gridScale + gui.getGuiTop() - gridY;
		renderItems(gridMX, gridMY);

		GlStateManager.popMatrix();
		GL11.glDisable(GL11.GL_SCISSOR_TEST);

		if (selectedControl != null) {
			gui.getFontRenderer().drawString("编号: " + selectedControl.panel.controls.indexOf(selectedControl), gui.getGuiLeft()+58, gui.getGuiTop()+230, 0x333333);
		}
	}
	
	public void renderItems(float mx, float my){
		for(Control c : gui.control.panel.controls){
			renderControl(c);
		}
		if(gui.currentEditControl != null){
			boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL);
			float prevX = gui.currentEditControl.posX;
			float prevY = gui.currentEditControl.posY;

			if (!ctrl) {
				gui.currentEditControl.posX = (float) Math.round(prevX*10)*.1F;
				gui.currentEditControl.posY = (float) Math.round(prevY*10)*.1F;
			}

			boolean canPlace = canPlace();
			if(!canPlace)
				GlStateManager.colorMask(true, false, false, true);
			renderControl(gui.currentEditControl);
			if (!ctrl) {
				gui.currentEditControl.posX = prevX;
				gui.currentEditControl.posY = prevY;
			}
			if(!canPlace)
				GlStateManager.colorMask(true, true, true, true);
		}
	}

	public void renderControl(Control c){
		Minecraft.getMinecraft().getTextureManager().bindTexture(c.getGuiTexture());
		c.fillBox(renderBox);
		c.renderControl(renderBox,selectedControl,gui);
	}
	
	@Override
	protected void renderBackground() {
		gui.mc.getTextureManager().bindTexture(texture);

		gui.drawTexturedModalRect(gui.getGuiLeft(), gui.getGuiTop(), 0, 0, gui.getXSize(), gui.getYSize());
	}

	@Override
	protected void actionPerformed(GuiButton button) {
		if (button == btn_panelResize) {
			gui.pushElement(gui.panelResize);
		}
		else if (button == btn_variables) {
			gui.currentEditControl = isEditableControl(selectedControl) ? selectedControl : null; // allows access to a selected control's local vars
			gui.variables.isGlobalScope = true;
			gui.pushElement(gui.variables);
		}
		else if (button == btn_newControl) {
			gui.isEditMode = false;
			gui.pushElement(gui.choice);
		}
		else if (button == btn_editControl) {
			if (isEditableControl(selectedControl)) {
				gui.currentEditControl = selectedControl;
				gui.itemConfig.last_control = null;
				gui.itemConfig.variants = ControlRegistry.getAllControlsOfType(gui.currentEditControl.getControlType());
				gui.pushElement(gui.itemConfig);
				gui.isEditMode = true;
				selectedControl = null;
			}
		}
		else if (button == btn_deleteControl) {
			gui.isEditMode = false;
			gui.control.panel.controls.remove(selectedControl);
			selectedControl = null;
		}
	}

	public static Control clipboard = null;

	@Override
	protected void keyTyped(char typedChar,int code) {
		if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)) {
			if (code == Keyboard.KEY_C || code == Keyboard.KEY_X) { // Copy
				if (isEditableControl(selectedControl)) {
					clipboard = duplicateControl(selectedControl);
					if(clipboard == null)
						return;
					if (code == Keyboard.KEY_X) {
						gui.isEditMode = false;
						gui.control.panel.controls.remove(selectedControl);
						selectedControl = null;
					}
				}
			} else if (code == Keyboard.KEY_V) {
				if (clipboard != null) {
					Control duplicate = duplicateControl(clipboard);
					if(duplicate == null)
						return;
					gui.currentEditControl = duplicate;
					float[] gridMouse = gui.placement.convertToGridSpace(gui.mouseX, gui.mouseY);
					gui.currentEditControl.posX = gridMouse[0];
					gui.currentEditControl.posY = gridMouse[1];
					gui.placement.resetPrevPos();
					controlGrabbed = false;
					selectedControl = null;
				}
			}
		}
		if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)) {
			if (gui.currentEditControl != null) return;
			if (code == Keyboard.KEY_A) { // Add new
				gui.isEditMode = false;
				gui.pushElement(gui.choice);
			} else if (code == Keyboard.KEY_D && isEditableControl(selectedControl)) { // Duplicate
				Control duplicate = duplicateControl(selectedControl);
				if(duplicate == null)
					return;
				gui.currentEditControl = duplicate;
				float[] gridMouse = gui.placement.convertToGridSpace(gui.mouseX, gui.mouseY);
				gui.currentEditControl.posX = gridMouse[0];
				gui.currentEditControl.posY = gridMouse[1];
				gui.placement.resetPrevPos();
				controlGrabbed = false;
				selectedControl = null;
			}
		}
	}

	private Control duplicateControl(Control source) {
		Control duplicate = gui.control.panel.cloneControl(source);
		return isEditableControl(duplicate) ? duplicate : null;
	}

	protected boolean canPlace(){
		if(gui.currentEditControl == null)
			return false;
		for(Control c : gui.control.panel.controls){
			if(GuiFx.boxesOverlap(c.getBox(), gui.currentEditControl.getBox())){
				return false;
			}
		}
		if(!GuiFx.boxContainsOther(gui.control.getBox(), gui.currentEditControl.getBox()))
			return false;
		return true;
	}
	
	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button){
		float gridMX = (gui.mouseX-gui.getGuiLeft())*gridScale + gui.getGuiLeft() + gridX;
		float gridMY = (gui.mouseY-gui.getGuiTop())*gridScale + gui.getGuiTop() - gridY;
		if(button == 0){
			if(gui.currentEditControl != null) {
				float prevX = gui.currentEditControl.posX;
				float prevY = gui.currentEditControl.posY;
				if(!Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)){
					gui.currentEditControl.posX = (float) Math.round(prevX*10)*.1F;
					gui.currentEditControl.posY = (float) Math.round(prevY*10)*.1F;
				}
				if(canPlace()){
					gui.control.panel.controls.add(gui.currentEditControl);
					gui.currentEditControl = null;
				} else {
					gui.currentEditControl.posX = prevX;
					gui.currentEditControl.posY = prevY;
				}
			} else {
				for(Control c : gui.control.panel.controls){
					if(GuiFx.intersects2DBox(gridMX, gridMY, c.getBox())){
						selectedControl = c;
						controlGrabbed = isEditableControl(c);
						return;
					}
				}
				selectedControl = null;
				controlGrabbed = false;
			}
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
		if(state == 0){
			if(canPlace()){
				if(!Keyboard.isKeyDown(Keyboard.KEY_LCONTROL)){
					gui.currentEditControl.posX = (float)Math.round(gui.currentEditControl.posX*10)*.1F;
					gui.currentEditControl.posY = (float)Math.round(gui.currentEditControl.posY*10)*.1F;
				}
				gui.control.panel.controls.add(ctrl_index, gui.currentEditControl);
				gui.currentEditControl = null;
			}
			controlGrabbed = false;
		}
	}

	@Override
	protected void enableButtons(boolean enable) {
		btn_panelResize.enabled = enable;
		btn_panelResize.visible = enable;
		btn_variables.enabled = enable;
		btn_variables.visible = enable;
		btn_security.enabled = false;
		btn_security.visible = enable;
		btn_connections.enabled = false;
		btn_connections.visible = enable;
		btn_newControl.enabled = enable;
		btn_newControl.visible = enable;
		btn_editControl.enabled = enable;
		btn_editControl.visible = enable;
		btn_deleteControl.enabled = enable;
		btn_deleteControl.visible = enable;
	}

	protected float[] convertToGridSpace(float x, float y){
		float gridMX = (x-gui.getGuiLeft())*gridScale + gui.getGuiLeft() + gridX;
		float gridMY = (y-gui.getGuiTop())*gridScale + gui.getGuiTop() - gridY;
		return new float[]{gridMX, gridMY};
	}
	
	protected float[] convertFromGridSpace(float gridMX, float gridMY){
		float x = ((gridMX-gridX)-gui.getGuiLeft())/gridScale+gui.getGuiLeft();
		float y = ((gridMY+gridY)-gui.getGuiTop())/gridScale+gui.getGuiTop();
		return new float[]{x, y};
	}

	private static boolean isEditableControl(Control control) {
		return control != null && ControlRegistry.isRegistered(control.registryName) && !(control instanceof UnknownControl);
	}
}

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
import com.hbmzz.cecp.compat.GuiFx;
import com.hbmzz.cecp.compat.CtrlImm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

public class NodeButton extends NodeElement {

    public NodeButton(String name, Node parent, int idx) {
        super(parent, idx);
        this.name = name;
        resetOffset();
    }

    @Override
    public void render(float mX, float mY){
        Minecraft.getMinecraft().getTextureManager().bindTexture(NodeSystem.node_tex);
        CtrlImm.INSTANCE.beginPositionTexColorQuads(1);
        float x = offsetX+4;
        float y = offsetY+8;
        GuiFx.drawGuiRectBatchedColor(x, y, 0F, 0.890625F, 32, 6, 0.609375F, 0.984375F, 1, 1, 1, 1);
        CtrlImm.INSTANCE.draw();

        FontRenderer font = Minecraft.getMinecraft().fontRenderer;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(0.35, 0.35, 0.35);
        GlStateManager.translate(-x, -y, 0);
        font.drawString(name, x+43-font.getStringWidth(name)/2F, y+5, 0xFF5F5F5F, false);
        GlStateManager.popMatrix();
    }

    @Override
    public boolean onClick(float x, float y) {
        if (GuiFx.intersects2DBox(x, y, getBox())) {
            return true;
        }
        return false;
    }

    public float[] getBox() {
        return new float[]{3 + offsetX, -3 + offsetY + 10, 37 + offsetX, 3 + offsetY + 10};
    }

}

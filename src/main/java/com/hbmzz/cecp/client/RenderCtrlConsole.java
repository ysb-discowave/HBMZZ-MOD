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

package com.hbmzz.cecp.client;

import com.hbmzz.cecp.ControlPanel;
import com.hbmzz.cecp.compat.CPEnv;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Draws the console body and every control mounted on it.
 *
 * <p>Ported from NTM: Cursed Edition ({@code com.hbm.render.tileentity.RenderControlPanel}),
 * custom-panel path only. The sloped backing plate is drawn as six quads at the
 * panel's own height/offset values; the controls themselves are drawn by the
 * ported {@code Control.render()} implementations, which is why the models and
 * textures had to come along too.</p>
 */
public class RenderCtrlConsole extends TileEntitySpecialRenderer<TileEntityCtrlConsole> {

    private static final ResourceLocation BODY =
            new ResourceLocation("hbmzz", "textures/models/misc/control_panel.png");

    @Override
    public void render(TileEntityCtrlConsole te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        if (te == null || te.panel == null) {
            return;
        }
        GlStateManager.pushMatrix();
        renderCustomPanel(te, x, y, z);

        GlStateManager.enableRescaleNormal();
        GlStateManager.pushMatrix();
        GlStateManager.translate(-0.5D, 0.0D, -0.5D);
        te.panel.transform.store(CPEnv.AUX_GL_BUFFER);
        CPEnv.AUX_GL_BUFFER.rewind();
        GlStateManager.multMatrix(CPEnv.AUX_GL_BUFFER);
        te.updateTransform();
        te.panel.render();
        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
    }

    private void renderCustomPanel(TileEntityCtrlConsole te, double x, double y, double z) {
        GlStateManager.translate(x + 0.5D, y, z + 0.5D);
        GlStateManager.pushMatrix();
        GlStateManager.rotate(-90.0F, 0.0F, 0.0F, 1.0F);

        int metadata = te.getBlockMetadata();
        switch ((metadata & 3) + 2) {
            case 4:
                GlStateManager.rotate(-180.0F, 1.0F, 0.0F, 0.0F);
                break;
            case 2:
                GlStateManager.rotate(-90.0F, 1.0F, 0.0F, 0.0F);
                break;
            case 3:
                GlStateManager.rotate(-270.0F, 1.0F, 0.0F, 0.0F);
                break;
            default:
                break;
        }
        boolean isUp = ((metadata >> 3) & 1) == 1;
        boolean isDown = ((metadata >> 2) & 1) == 1;
        if (isDown) {
            GlStateManager.rotate(-180.0F, 1.0F, 0.0F, 0.0F);
            GlStateManager.rotate(-90.0F, 0.0F, 0.0F, 1.0F);
            GlStateManager.translate(0.0D, -1.0D, 0.0D);
        } else if (isUp) {
            GlStateManager.rotate(90.0F, 0.0F, 0.0F, 1.0F);
        } else {
            GlStateManager.translate(-0.5D, -0.5D, 0.0D);
        }

        GlStateManager.enableRescaleNormal();

        GlStateManager.pushMatrix();
        GlStateManager.translate(-0.5D, 0.0D, -0.5D);

        ControlPanel panel = te.panel;
        float a_off = panel.a_off;
        float b_off = panel.b_off;
        float c_off = panel.c_off;
        float d_off = panel.d_off;
        float height = panel.height;
        float angle = panel.angle;

        float height1 = ControlPanel.getSlopeHeightFromZ(1 - c_off, height, -angle);
        float height0 = ControlPanel.getSlopeHeightFromZ(a_off, height, -angle);

        if (height != 0) {
            GlStateManager.disableLighting();
            bindTexture(BODY);
            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            com.hbmzz.cecp.compat.CtrlImm.INSTANCE.ensureIdle();
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            // back
            buf.pos(a_off, 0, 1 - d_off).tex(1, 1).endVertex();
            buf.pos(a_off, height0, 1 - d_off).tex(1, 0).endVertex();
            buf.pos(a_off, height0, b_off).tex(0, 0).endVertex();
            buf.pos(a_off, 0, b_off).tex(0, 1).endVertex();
            // left
            buf.pos(1 - c_off, 0, 1 - d_off).tex(1, 1).endVertex();
            buf.pos(1 - c_off, height1, 1 - d_off).tex(1, 0).endVertex();
            buf.pos(a_off, height0, 1 - d_off).tex(0, 0).endVertex();
            buf.pos(a_off, 0, 1 - d_off).tex(0, 1).endVertex();
            // right
            buf.pos(a_off, 0, b_off).tex(1, 1).endVertex();
            buf.pos(a_off, height0, b_off).tex(1, 0).endVertex();
            buf.pos(1 - c_off, height1, b_off).tex(0, 0).endVertex();
            buf.pos(1 - c_off, 0, b_off).tex(0, 1).endVertex();
            // front
            buf.pos(1 - c_off, 0, b_off).tex(1, 1).endVertex();
            buf.pos(1 - c_off, height1, b_off).tex(1, 0).endVertex();
            buf.pos(1 - c_off, height1, 1 - d_off).tex(0, 0).endVertex();
            buf.pos(1 - c_off, 0, 1 - d_off).tex(0, 1).endVertex();
            // top
            buf.pos(1 - c_off, height1, b_off).tex(1, 1).endVertex();
            buf.pos(a_off, height0, b_off).tex(1, 0).endVertex();
            buf.pos(a_off, height0, 1 - d_off).tex(0, 0).endVertex();
            buf.pos(1 - c_off, height1, 1 - d_off).tex(0, 1).endVertex();
            // bottom
            buf.pos(1 - c_off, 0, 1 - d_off).tex(0, 1).endVertex();
            buf.pos(a_off, 0, 1 - d_off).tex(0, 0).endVertex();
            buf.pos(a_off, 0, b_off).tex(1, 0).endVertex();
            buf.pos(1 - c_off, 0, b_off).tex(1, 1).endVertex();
            tess.draw();
        }

        GlStateManager.enableLighting();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
        GlStateManager.popMatrix();
    }
}

/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
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
 * Portions of the render structure and the offscreen-canvas pipeline are derived
 * from JiezhiMod by Shushang de Jiezhi, used under written permission to license
 * them under GPL-3.0. See AUTHORIZATION.md.
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client;

import com.hbmzz.client.ConsoleModel.Face;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.List;

/**
 * The two draw calls every wall panel shares: the painted housing and the glass.
 *
 * <p>Extracted from the DFC monitor's renderer rather than duplicated in both
 * new ones, because the details are the kind that must not be re-derived: culling
 * off for the whole assembly (a single wrong-facing triangle reads as an
 * invisible panel, not a broken one), and the terrain atlas rebound after the
 * glass, or the next thing drawn samples this panel's texture stretched across
 * the world.</p>
 */
public final class PanelRender {

    private PanelRender() {
    }

    /** Untextured housing, in the panel's local frame. */
    public static void faces(List<Face> faces) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Face face : faces) {
            float a = ((face.argb >>> 24) & 255) / 255.0F;
            float r = ((face.argb >> 16) & 255) / 255.0F;
            float g = ((face.argb >> 8) & 255) / 255.0F;
            float b = (face.argb & 255) / 255.0F;
            for (int i = 0; i < 4; i++) {
                buffer.pos(face.xs[i], face.ys[i], face.zs[i]).color(r, g, b, a).endVertex();
            }
        }
        tessellator.draw();

        GlStateManager.enableLighting();
    }

    /**
     * The glass: one textured quad, just inside the frame.
     *
     * @param corners {@code (x1,y0) (x0,y0) (x0,y1) (x1,y1)}, bottom-right first
     */
    public static void glass(int textureId, float[][] corners) {
        if (textureId == 0 || corners == null || corners.length < 4) {
            return;
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(textureId);

        float[] uv = com.hbmzz.dfc.ConsoleRig.panelUv();
        float ux0 = uv[0];
        float ux1 = uv[1];
        float vy0 = uv[2];
        float vy1 = uv[3];
        float[] u = {ux1, ux0, ux0, ux1};
        float[] v = {vy0, vy0, vy1, vy1};

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int i = 0; i < 4; i++) {
            buffer.pos(corners[i][0], corners[i][1], corners[i][2])
                    .tex(u[i], v[i])
                    .color(255, 255, 255, 255)
                    .endVertex();
        }
        tessellator.draw();

        // Hand the terrain atlas back: leaving the panel texture bound here makes
        // whatever draws next - the next chunk, the clouds, another TESR - sample
        // this screen stretched over the world.
        TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
        if (atlas != null) {
            GlStateManager.bindTexture(atlas.getGlTextureId());
        }

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }
}

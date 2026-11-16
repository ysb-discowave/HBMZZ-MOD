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
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client;

import com.hbmzz.client.ConsoleModel.Face;
import com.hbmzz.dfc.ConsoleRig;
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
 * Every OpenGL call the monitors need, in one place.
 *
 * <p>The two things this draws — the machine's solid faces and the glass quad —
 * are the only parts of the console that touch the graphics state at all. They
 * used to be spelled out in each renderer, which meant two copies of the same
 * state dance, and a state bug fixed in one of them stayed broken in the other
 * for a release.</p>
 *
 * <h3>Two rules, both learned the hard way</h3>
 * <ul>
 *   <li><b>Culling is the caller's job to disable</b>, and it must be: the
 *       terrain pass leaves {@code GL_CULL_FACE} on, and a single wrong-facing
 *       triangle then reads to a player as "the screen is frozen on its block
 *       texture" instead of as an obviously broken model.</li>
 *   <li><b>The texture unit is forced to 0 before binding</b>, and the block
 *       atlas is bound back afterwards. Another mod may have left a different
 *       unit active, and leaving our canvas bound makes the next chunk, cloud or
 *       TESR sample a 400-pixel control panel stretched over the world.</li>
 * </ul>
 */
public final class PanelPainter {

    private PanelPainter() {
    }

    /**
     * Draws the machine's faces: untextured, one quad each, alpha applied.
     *
     * @param alpha 1 for an intact console; the falling screen fades out with it
     */
    public static void solid(List<Face> faces, float alpha) {
        if (faces == null || faces.isEmpty()) {
            return;
        }
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        if (alpha < 1.0F) {
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);
        } else {
            GlStateManager.disableBlend();
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Face face : faces) {
            float a = ((face.argb >>> 24) & 255) / 255.0F * alpha;
            float r = ((face.argb >> 16) & 255) / 255.0F;
            float g = ((face.argb >> 8) & 255) / 255.0F;
            float b = (face.argb & 255) / 255.0F;
            for (int i = 0; i < 4; i++) {
                buffer.pos(face.xs[i], face.ys[i], face.zs[i]).color(r, g, b, a).endVertex();
            }
        }
        tessellator.draw();

        if (alpha < 1.0F) {
            GlStateManager.disableBlend();
        }
        GlStateManager.enableLighting();
    }

    /**
     * Draws the glass: the canvas texture on the four corners
     * {@link ConsoleModel#panelCorners} handed out.
     *
     * <p>The u axis runs backwards on purpose. The observer stands outside the
     * face looking along +z, so their right hand is local -x: image column 0 has
     * to land on their left, which means u=0 pairs with the x1 corners.</p>
     */
    public static void glass(float[][] corners, int textureId, float alpha) {
        if (corners == null || corners.length < 4 || textureId == 0 || alpha <= 0.001F) {
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

        float[] uv = ConsoleRig.panelUv();
        float[] u = {uv[1], uv[0], uv[0], uv[1]};
        float[] v = {uv[2], uv[2], uv[3], uv[3]};

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int i = 0; i < 4; i++) {
            buffer.pos(corners[i][0], corners[i][1], corners[i][2])
                    .tex(u[i], v[i])
                    .color(1.0F, 1.0F, 1.0F, alpha)
                    .endVertex();
        }
        tessellator.draw();

        restoreAtlas();
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    /**
     * Puts the block atlas back on unit 0.
     *
     * <p>Not optional, and not "just tidy": {@code GlStateManager} caches the
     * binding, so leaving the canvas bound here makes the next thing drawn —
     * the next chunk, the clouds, another tile entity renderer — sample it.</p>
     */
    public static void restoreAtlas() {
        TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
        if (atlas != null) {
            GlStateManager.bindTexture(atlas.getGlTextureId());
        }
    }
}

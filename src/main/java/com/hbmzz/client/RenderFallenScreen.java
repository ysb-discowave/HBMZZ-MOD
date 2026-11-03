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
import com.hbmzz.client.screen.SignalLossCanvas;
import com.hbmzz.entity.EntityFallenScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws a screen that has been knocked off its mount.
 *
 * <p>Three parts, top to bottom: the harness still bolted to the ceiling, one
 * cable, and the panel swinging on the end of it. The swing is applied to the
 * cable <em>and</em> the panel together - a pendulum whose cable stays vertical
 * and only moves the payload looks like a sliding picture, not a hanging
 * one.</p>
 *
 * <p>Geometry comes from the same {@link ConsoleModel} the intact console uses,
 * so a fallen screen is the same machine: if the frame gains a lip or the
 * bezel changes width, this cannot silently drift out of sync.</p>
 */
public class RenderFallenScreen extends Render<EntityFallenScreen> {

    private static final int C_FRAME = 0xFF464E55;
    private static final int C_HULL = 0xFF23272B;
    private static final int C_RIG = 0xFF1B1F22;
    private static final int C_RIG_HI = 0xFF525B63;
    private static final int C_BOLT = 0xFF88929B;
    private static final int C_CABLE = 0xFF2E3238;

    /** One dead-channel canvas per fallen screen; weak so a dead entity is collectable. */
    private static final Map<EntityFallenScreen, SignalLossCanvas> CANVASES =
            new WeakHashMap<EntityFallenScreen, SignalLossCanvas>();

    public RenderFallenScreen(RenderManager manager) {
        super(manager);
        // It hangs in mid-air; a ground shadow under a floating panel reads as
        // a bug rather than as depth.
        this.shadowSize = 0.0F;
    }

    @Override
    public void doRender(EntityFallenScreen entity, double x, double y, double z,
                         float entityYaw, float partialTicks) {
        SignalLossCanvas canvas = canvasFor(entity);
        long ageMs = (long) ((entity.ticksExisted + partialTicks) * 50L);
        canvas.render(ageMs);
        canvas.upload();

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        try {
            GlStateManager.translate(x, y, z);
            // Local +x is the viewer's right, -z faces out; same convention as
            // the mounted console, so one yaw value serves both.
            GlStateManager.rotate(entity.getHangYaw(), 0.0F, 1.0F, 0.0F);

            GlStateManager.disableCull();

            // Harness: bolted to the ceiling and staying there.
            drawHarness();

            double wire = entity.wireLength(partialTicks);
            double swing = entity.swingDegrees(partialTicks);

            // The whole hanging assembly swings about the hook. Rotating here
            // - after the harness, before the cable - is what keeps the hook
            // still while everything below it moves.
            GlStateManager.pushMatrix();
            GlStateManager.rotate((float) swing, 0.0F, 0.0F, 1.0F);
            drawCable(wire);

            // Down the cable to the panel's top edge, then to its centre.
            GlStateManager.translate(0.0D, -wire, 0.0D);
            int cols = entity.getCols();
            int rows = entity.getRows();
            GlStateManager.translate(0.0D, -rows * 0.5D, 0.0D);

            float fade = entity.visibility(partialTicks);
            drawShell(cols, rows, fade);
            drawPanel(canvas, cols, rows, fade);

            GlStateManager.popMatrix();

            GlStateManager.enableCull();
        } finally {
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    // ---------------------------------------------------------------- pieces

    /** Ceiling flange, drop stub and the hinge the panel tore out of. */
    private void drawHarness() {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        noTexture(buffer);
        // Flange, half sunk into the block above.
        box(buffer, -0.20F, 0.00F, -0.12F, 0.20F, 0.10F, 0.12F, C_RIG_HI);
        box(buffer, -0.15F, -0.03F, -0.09F, 0.15F, 0.00F, 0.09F, C_RIG);
        // Drop stub and the hinge saddle.
        box(buffer, -0.055F, -0.15F, -0.055F, 0.055F, 0.02F, 0.055F, C_RIG);
        box(buffer, -0.17F, -0.20F, -0.075F, 0.17F, -0.13F, 0.075F, C_RIG_HI);
        // Cheek plates and a pin: the part the panel used to hang from.
        box(buffer, -0.20F, -0.22F, -0.055F, -0.145F, -0.11F, 0.055F, C_RIG);
        box(buffer, 0.145F, -0.22F, -0.055F, 0.20F, -0.11F, 0.055F, C_RIG);
        box(buffer, -0.175F, -0.175F, -0.075F, 0.175F, -0.155F, 0.075F, C_BOLT);

        tessellator.draw();
    }

    /** One cable, hanging straight in the swinging frame. */
    private void drawCable(double wire) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        noTexture(buffer);
        float top = -0.19F;
        float bottom = (float) -wire;
        box(buffer, -0.016F, bottom, -0.016F, 0.016F, top, 0.016F, C_CABLE);
        // A ferrule where it meets the panel, so it does not look spliced on.
        box(buffer, -0.05F, bottom - 0.03F, -0.05F, 0.05F, bottom + 0.02F, 0.05F, C_BOLT);

        tessellator.draw();
    }

    private void drawShell(int cols, int rows, float fade) {
        List<Face> faces = ConsoleModel.build(cols, rows, 0, false, false);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (Face face : faces) {
            float a = ((face.argb >>> 24) & 255) / 255.0F * fade;
            float r = ((face.argb >> 16) & 255) / 255.0F;
            float g = ((face.argb >> 8) & 255) / 255.0F;
            float b = (face.argb & 255) / 255.0F;
            for (int i = 0; i < 4; i++) {
                buffer.pos(face.xs[i], face.ys[i], face.zs[i]).color(r, g, b, a).endVertex();
            }
        }
        tessellator.draw();

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    private void drawPanel(SignalLossCanvas canvas, int cols, int rows, float fade) {
        if (!canvas.hasTexture()) {
            return;
        }
        float[][] corners = ConsoleModel.panelCorners(cols, rows, 0);
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, fade);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(canvas.textureId());

        float[] uv = com.hbmzz.dfc.ConsoleRig.panelUv();
        float[] u = {uv[1], uv[0], uv[0], uv[1]};
        float[] v = {uv[2], uv[2], uv[3], uv[3]};

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int i = 0; i < 4; i++) {
            buffer.pos(corners[i][0], corners[i][1], corners[i][2])
                    .tex(u[i], v[i])
                    .color(1.0F, 1.0F, 1.0F, fade)
                    .endVertex();
        }
        tessellator.draw();

        // Hand the atlas back; see RenderDFCMonitor for why this is not optional.
        TextureMap atlas = Minecraft.getMinecraft().getTextureMapBlocks();
        if (atlas != null) {
            GlStateManager.bindTexture(atlas.getGlTextureId());
        }
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    // --------------------------------------------------------------- helpers

    private static void noTexture(BufferBuilder buffer) {
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
    }

    /** Six faces, wound counter-clockwise from the outside. */
    private static void box(BufferBuilder buffer, float x0, float y0, float z0,
                            float x1, float y1, float z1, int argb) {
        float a = ((argb >>> 24) & 255) / 255.0F;
        float r = ((argb >> 16) & 255) / 255.0F;
        float g = ((argb >> 8) & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;

        vertex(buffer, x1, y0, z0, r, g, b, a);
        vertex(buffer, x0, y0, z0, r, g, b, a);
        vertex(buffer, x0, y1, z0, r, g, b, a);
        vertex(buffer, x1, y1, z0, r, g, b, a);

        vertex(buffer, x0, y0, z1, r, g, b, a);
        vertex(buffer, x1, y0, z1, r, g, b, a);
        vertex(buffer, x1, y1, z1, r, g, b, a);
        vertex(buffer, x0, y1, z1, r, g, b, a);

        vertex(buffer, x0, y0, z0, r, g, b, a);
        vertex(buffer, x0, y0, z1, r, g, b, a);
        vertex(buffer, x0, y1, z1, r, g, b, a);
        vertex(buffer, x0, y1, z0, r, g, b, a);

        vertex(buffer, x1, y0, z1, r, g, b, a);
        vertex(buffer, x1, y0, z0, r, g, b, a);
        vertex(buffer, x1, y1, z0, r, g, b, a);
        vertex(buffer, x1, y1, z1, r, g, b, a);

        vertex(buffer, x0, y0, z1, r, g, b, a);
        vertex(buffer, x1, y0, z1, r, g, b, a);
        vertex(buffer, x1, y0, z0, r, g, b, a);
        vertex(buffer, x0, y0, z0, r, g, b, a);

        vertex(buffer, x0, y1, z0, r, g, b, a);
        vertex(buffer, x1, y1, z0, r, g, b, a);
        vertex(buffer, x1, y1, z1, r, g, b, a);
        vertex(buffer, x0, y1, z1, r, g, b, a);
    }

    private static void vertex(BufferBuilder buffer, float x, float y, float z,
                               float r, float g, float b, float a) {
        buffer.pos(x, y, z).color(r, g, b, a).endVertex();
    }

    private static SignalLossCanvas canvasFor(EntityFallenScreen entity) {
        SignalLossCanvas canvas = CANVASES.get(entity);
        if (canvas == null) {
            canvas = new SignalLossCanvas(entity.getCols(), entity.getRows());
            CANVASES.put(entity, canvas);
        }
        return canvas;
    }

    /** Releases every dead-channel texture, called when a world is unloaded. */
    public static void clearCaches() {
        for (SignalLossCanvas canvas : CANVASES.values()) {
            try {
                canvas.dispose();
            } catch (Throwable ignored) {
                // Tearing a world down; nothing useful to do.
            }
        }
        CANVASES.clear();
    }

    @Override
    protected ResourceLocation getEntityTexture(EntityFallenScreen entity) {
        return null;
    }
}

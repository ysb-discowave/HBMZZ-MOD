/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis.client;

import com.hbmzz.HBMZZ;
import com.hbmzz.aegis.AegisTelemetry;
import com.hbmzz.aegis.BlockAegisTerminal;
import com.hbmzz.aegis.TileAegisTerminal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.WeakHashMap;

/**
 * Draws one Aegis terminal: an offscreen canvas uploaded to a GL texture and
 * mapped onto a quad that sits just in front of the host block, rotated to the
 * block's facing. A slim bezel is drawn around the picture so it reads as a
 * physical screen rather than a floating image.
 *
 * <p>Canvas and texture are kept per tile entity in weak maps; a broken or
 * unloaded terminal is collected automatically and its GL texture is released
 * through {@link #clearCaches()} on a world unload.</p>
 */
public final class AegisRenderer extends TileEntitySpecialRenderer<TileAegisTerminal> {

    private static final WeakHashMap<TileAegisTerminal, AegisCanvas> CANVASES =
            new WeakHashMap<TileAegisTerminal, AegisCanvas>();
    private static final WeakHashMap<TileAegisTerminal, AegisTexture> TEXTURES =
            new WeakHashMap<TileAegisTerminal, AegisTexture>();

    /** The wall face the panel mounts to, in local block coordinates.
     *  FACING points out of the wall toward the viewer, so the wall itself is
     *  on the local -Z side. Backing must sit here to actually touch the wall. */
    private static final float PLANE = -0.5F;
    /** Depth of the wall-mounted enclosure, from the wall face to the picture.
     *  The wall face is local -0.5 (the block's north face in local space); the
     *  viewer is on local +Z. Filling the whole block depth (1.0) plus a small
     *  0.06 lip makes the panel sit flush on the wall instead of floating half
     *  a block out in front of it. */
    private static final float BOX_DEPTH = 1.06F;

    @Override
    public void render(TileAegisTerminal te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        if (te == null || !te.hasWorld()) {
            return;
        }
        EnumFacing facing = facingOf(te);
        if (facing == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        try {
            // The enclosure is a thin slab; with back-face culling on, an
            // oblique view can cull the side walls and leave the block looking
            // like air. Draw both faces of every quad so the panel reads as a
            // solid wall-mounted box from any angle.
            GlStateManager.disableCull();
            AegisCanvas canvas = canvasFor(te);
            AegisTexture texture = textureFor(te);

            AegisTelemetry frame = te.getLatest();
            boolean powered = te.isPowered();
            long now = System.currentTimeMillis();

            // No-signal detection lives in AegisCanvas: it timestamps changes
            // in frame.worldTick and paints the "无信号" screen once the feed
            // goes quiet for five seconds. Hand it the last frame (or a blank
            // one) and let it decide — an earlier no-op branch here was dead
            // code that claimed to blank stale numbers but never did.
            if (frame == null) {
                frame = new AegisTelemetry();
            }
            canvas.render(frame, te.getPage(), now, te.getBootAt(), powered);
            texture.upload(canvas.image());

            float halfW = Math.max(0.05F, te.getPanelWidth() * 0.5F);
            float halfH = Math.max(0.05F, te.getPanelHeight() * 0.5F);

            GlStateManager.translate(x + 0.5D, y + 0.5D, z + 0.5D);
            GlStateManager.rotate(yaw(facing), 0.0F, 1.0F, 0.0F);
            // Pivot the tilt on the WALL SURFACE (local +Z = 0.5), not on a
            // point floating in front of it. The enclosure's back face then
            // stays flush against the wall at every tilt angle; only the front
            // leans out. This is what makes it read as a wall-mounted panel
            // instead of a screen hovering in mid-air.
            GlStateManager.translate(0.0D, 0.0D, PLANE);
            GlStateManager.rotate(te.getTilt(), 1.0F, 0.0F, 0.0F);

            // A flat enclosure whose back sits on the wall (z = 0) and whose
            // picture face is BOX_DEPTH in front. No protruding bracket: side
            // views now show a clean, flat-sided box with real thickness.
            backing(halfW, halfH);
            GlStateManager.translate(0.0D, 0.0D, BOX_DEPTH);
            bezel(halfW, halfH);
            // Additive rim light: cyan normally, amber when hot, red on a
            // collapse/tamper alarm. Makes the panel readable as "alive" from
            // across the room without touching the screen texture itself.
            boolean danger = frame.collapsing || frame.forecastKind == 1 || frame.tamperState >= 2;
            int glow = danger ? 0xFFFF5A56
                    : (frame.heatFraction() >= com.hbmzz.aegis.AegisConfig.dangerHeat ? 0xFFFFB23C
                    : (frame.heatFraction() >= com.hbmzz.aegis.AegisConfig.warnHeat ? 0xFFFFB23C
                    : 0xFF39D6E8));
            glowRim(glow, halfW, halfH);
            if (texture.ready()) {
                screen(texture.id(), halfW, halfH);
            }
        } catch (Throwable failure) {
            HBMZZ.logger.error("Aegis terminal at " + (te == null ? "?" : te.getPos()) + " failed to render", failure);
        } finally {
            GlStateManager.enableCull();
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    /**
     * Flat metal enclosure whose back face lies exactly on the wall (local
     * z = 0) and whose picture face is {@link #BOX_DEPTH} in front. It is a
     * shallow, solid box — top/bottom/left/right walls plus a back plate — with
     * no arm jutting out of the wall. From the side you see a thin, straight
     * slab with readable thickness; the old version had a deep 0.18-block shell
     * and a crossed bracket sticking out, which at oblique angles read as a
     * rolled log rather than a wall monitor.
     */
    private static void backing(float halfW, float halfH) {
        float pad = 0.035F;
        float l = -halfW - pad, r = halfW + pad;
        float b = -halfH - pad, t = halfH + pad;
        float front = BOX_DEPTH;
        float back = 0.0F;    // flush with the wall surface
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();

        // Back plate (also the visible rim when the panel is tilted).
        GlStateManager.color(0.13F, 0.16F, 0.19F, 1.0F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        quad(buf, l, b, r, t, back);
        tess.draw();

        // Four side walls, one shade per face so the depth reads edge-on.
        // top (brightest)
        GlStateManager.color(0.30F, 0.36F, 0.42F, 1.0F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buf.pos(l, t, front).endVertex(); buf.pos(r, t, front).endVertex();
        buf.pos(r, t, back).endVertex();  buf.pos(l, t, back).endVertex();
        tess.draw();
        // bottom (darkest)
        GlStateManager.color(0.08F, 0.10F, 0.13F, 1.0F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buf.pos(l, b, back).endVertex();  buf.pos(r, b, back).endVertex();
        buf.pos(r, b, front).endVertex(); buf.pos(l, b, front).endVertex();
        tess.draw();
        // left / right cheeks (mid tone)
        GlStateManager.color(0.18F, 0.22F, 0.27F, 1.0F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        buf.pos(l, b, front).endVertex(); buf.pos(l, b, back).endVertex();
        buf.pos(l, t, back).endVertex();  buf.pos(l, t, front).endVertex();
        buf.pos(r, b, back).endVertex();  buf.pos(r, b, front).endVertex();
        buf.pos(r, t, front).endVertex(); buf.pos(r, t, back).endVertex();
        tess.draw();

        // A slim cyan strip along the front edge gives the flat box a crisp,
        // manufactured outline without turning it into a rounded object.
        GlStateManager.color(0.16F, 0.55F, 0.60F, 1.0F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        float e = 0.010F;
        // top front edge
        buf.pos(l, t - e, front).endVertex(); buf.pos(r, t - e, front).endVertex();
        buf.pos(r, t, front).endVertex();     buf.pos(l, t, front).endVertex();
        // bottom front edge
        buf.pos(l, b, front).endVertex();     buf.pos(r, b, front).endVertex();
        buf.pos(r, b + e, front).endVertex(); buf.pos(l, b + e, front).endVertex();
        // left front edge
        buf.pos(l, b, front).endVertex();     buf.pos(l + e, b, front).endVertex();
        buf.pos(l + e, t, front).endVertex(); buf.pos(l, t, front).endVertex();
        // right front edge
        buf.pos(r - e, b, front).endVertex(); buf.pos(r, b, front).endVertex();
        buf.pos(r, t, front).endVertex();     buf.pos(r - e, t, front).endVertex();
        tess.draw();
        GlStateManager.enableTexture2D();
    }

    /** Local-space bezel around the picture; drawn as four dark bars. */
    private static void bezel(float halfW, float halfH) {
        float l = -halfW - 0.02F;
        float r = halfW + 0.02F;
        float b = -halfH - 0.02F;
        float t = halfH + 0.02F;
        float edge = 0.035F;
        float z = -0.002F;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.color(0.06F, 0.10F, 0.12F, 1.0F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        quad(buf, l, b, l + edge, t, z);
        quad(buf, r - edge, b, r, t, z);
        quad(buf, l + edge, b, r - edge, b + edge, z);
        quad(buf, l + edge, t - edge, r - edge, t, z);
        tess.draw();
        GlStateManager.enableTexture2D();
    }

    /** Additive rim light just outside the bezel, tinted by alarm state. */
    private static void glowRim(int argb, float halfW, float halfH) {
        float a = ((argb >>> 24) & 0xFF) / 255.0F;
        float r = ((argb >>> 16) & 0xFF) / 255.0F;
        float g = ((argb >>> 8) & 0xFF) / 255.0F;
        float b = (argb & 0xFF) / 255.0F;
        float l = -halfW - 0.055F;
        float rr = halfW + 0.055F;
        float bot = -halfH - 0.055F;
        float top = halfH + 0.055F;
        float edge = 0.012F;
        float z = -0.004F;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(r, g, b, a * 0.55F);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        quad(buf, l, bot, rr, bot + edge, z);
        quad(buf, l, top - edge, rr, top, z);
        quad(buf, l, bot, l + edge, top, z);
        quad(buf, rr - edge, bot, rr, top, z);
        tess.draw();
        // Restore the conventional blend func used by the screen quad.
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.enableTexture2D();
    }

    /** The emissive screen quad itself. */
    private static void screen(int textureId, float halfW, float halfH) {
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        float lightX = OpenGlHelper.lastBrightnessX;
        float lightY = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(textureId);

        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        buf.pos(-halfW, -halfH, 0.0D).tex(0.0F, 1.0F).endVertex();
        buf.pos(halfW, -halfH, 0.0D).tex(1.0F, 1.0F).endVertex();
        buf.pos(halfW, halfH, 0.0D).tex(1.0F, 0.0F).endVertex();
        buf.pos(-halfW, halfH, 0.0D).tex(0.0F, 0.0F).endVertex();
        tess.draw();

        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, lightX, lightY);
        GlStateManager.bindTexture(0);
    }

    /** Emits one axis-aligned quad in the current (rotated) local space. */
    private static void quad(BufferBuilder buf, float x0, float y0, float x1, float y1, float z) {
        buf.pos(x0, y0, z).endVertex();
        buf.pos(x1, y0, z).endVertex();
        buf.pos(x1, y1, z).endVertex();
        buf.pos(x0, y1, z).endVertex();
    }

    /**
     * Yaw that turns this renderer's local +Z (the side the screen quad is
     * drawn on) toward {@code facing}. The screen is emitted at local +Z, so
     * the mapping is the +Z variant — the inverse of the old -Z convention.
     * Getting this wrong buries the picture in the wall behind the block and
     * leaves the player staring at an INVISIBLE (air-looking) block.
     */
    private static float yaw(EnumFacing facing) {
        if (facing == null) {
            return 0.0F;
        }
        switch (facing) {
            case SOUTH:
                return 0.0F;   // +Z already faces south
            case EAST:
                return 90.0F;  // +Z rotated +90 -> +X
            case NORTH:
                return 180.0F; // +Z rotated 180 -> -Z
            case WEST:
                return 270.0F; // +Z rotated 270 -> -X
            default:
                return 0.0F;
        }
    }

    private static EnumFacing facingOf(TileAegisTerminal te) {
        BlockPos pos = te.getPos();
        IBlockState state = te.getWorld().getBlockState(pos);
        if (!(state.getBlock() instanceof BlockAegisTerminal)) {
            return null;
        }
        return state.getValue(BlockAegisTerminal.FACING);
    }

    private static AegisCanvas canvasFor(TileAegisTerminal te) {
        AegisCanvas canvas = CANVASES.get(te);
        if (canvas == null) {
            canvas = new AegisCanvas();
            CANVASES.put(te, canvas);
        }
        return canvas;
    }

    private static AegisTexture textureFor(TileAegisTerminal te) {
        AegisTexture texture = TEXTURES.get(te);
        if (texture == null) {
            texture = new AegisTexture();
            TEXTURES.put(te, texture);
        }
        return texture;
    }

    public static void clearCaches() {
        for (AegisTexture texture : TEXTURES.values()) {
            try {
                texture.dispose();
            } catch (Throwable ignored) {
                // Nothing useful to do while tearing a world down.
            }
        }
        TEXTURES.clear();
        CANVASES.clear();
    }
}

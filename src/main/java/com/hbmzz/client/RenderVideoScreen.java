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
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client;

import com.hbmzz.block.BlockVideoScreen;
import com.hbmzz.client.ConsoleModel.Face;
import com.hbmzz.client.video.VideoScreenPlayback;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.tile.TileEntityVideoScreen;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a video screen standing in the world: housing, bezel, and the picture.
 *
 * <h3>Why a TESR and not a block model</h3>
 * <p>The picture changes every frame, and a block model is baked once at
 * resource load. The only alternatives are swapping models (a reload per frame)
 * or a texture that the model samples - which is what this does, only drawn as
 * a quad in front of a real housing so the screen has thickness, a bezel and a
 * recessed glass instead of reading as a photo taped to a cube.</p>
 *
 * <h3>What is cached, and why</h3>
 * <p>The housing's geometry depends on exactly two numbers, {@code (cols, rows)}.
 * Rebuilding it per frame per screen was the single largest cost of the DFC
 * monitor rewrite, so it is cached here from the start: a wall of ten screens
 * costs ten draw calls, not ten thousand temporary float arrays. The picture
 * costs one quad and one texture bind; the texture itself is only re-uploaded
 * when the clip actually advances (see {@link VideoScreenPlayback}).</p>
 *
 * <h3>Two state rules, borrowed from the monitors</h3>
 * <ul>
 *   <li>Culling is disabled around the housing: the terrain pass leaves
 *       {@code GL_CULL_FACE} on, and one wrong-facing quad then reads as a
 *       missing screen rather than as a bug.</li>
 *   <li>The block atlas is restored after the picture, because
 *       {@code GlStateManager} caches the binding and the next chunk drawn
 *       would otherwise sample a video frame.</li>
 * </ul>
 */
public class RenderVideoScreen extends TileEntitySpecialRenderer<TileEntityVideoScreen> {

    /** Gap between the panel and the cell edges, so neighbours never merge. */
    private static final float MARGIN = 0.03F;

    /** Width of the bezel around the glass. */
    private static final float BEZEL = 0.075F;

    /** Front plane of the housing, in blocks, forward of the cell centre. */
    private static final float FRONT_Z = -0.0625F;

    /** Glass sits a hair behind the bezel so the frame reads as a frame. */
    private static final float GLASS_Z = FRONT_Z + 0.0015F;

    private static final int HOUSING_BACK = 0xFF1A1E23;
    private static final int HOUSING_SIDE = 0xFF2A3038;
    private static final int BEZEL_FACE = 0xFF12161A;
    private static final int BEZEL_EDGE = 0xFF3A424C;
    private static final int STANDBY_GLASS = 0xFF05070A;
    private static final int STANDBY_LED = 0xFFF5A524;

    /** Housing geometry per (cols, rows). Tiny key space, defensive cap. */
    private static final Map<Long, List<Face>> MODELS = new HashMap<Long, List<Face>>();
    private static final int MODEL_CACHE_LIMIT = 32;

    @Override
    public void render(TileEntityVideoScreen te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        if (te == null || te.getWorld() == null) {
            return;
        }
        EnumFacing facing = facingOf(te);
        if (facing == null) {
            return;
        }
        int cols = Math.max(1, te.getCols());
        int rows = Math.max(1, te.getRows());
        String clip = te.getVideo();

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        try {
            // The renderer is handed the origin cell's minimum corner; the panel
            // is authored about its own centre (same frame the monitors use).
            double[] anchor = ConsoleRig.anchor(cols, rows, facing);
            GlStateManager.translate(x + anchor[0], y + anchor[1], z + anchor[2]);
            GlStateManager.rotate(ConsoleRig.yawOf(facing), 0.0F, 1.0F, 0.0F);

            GlStateManager.disableCull();
            GlStateManager.disableLighting();

            PanelPainter.solid(modelFor(cols, rows), 1.0F);

            double distSq = x * x + y * y + z * z;
            int texture = VideoScreenPlayback.textureFor(te, clip, distSq);
            drawGlass(cols, rows, clip, texture);

            GlStateManager.enableLighting();
            GlStateManager.enableCull();
        } catch (Throwable throwable) {
            // Same rule as the monitors: a bad frame is a missing screen, not a
            // crashed client.
            com.hbmzz.HBMZZ.logger.error("video screen at " + te.getPos() + " failed to render", throwable);
        } finally {
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    /**
     * The picture, letterboxed inside the bezel.
     *
     * <p>Never stretched: a 4:3 clip on a 3x2 panel keeps its shape and gets
     * black bars, because a distorted face is more obviously wrong than a
     * smaller picture. Until the first frame arrives (or when nothing is linked)
     * the glass is simply dark with the standby lamp lit.</p>
     */
    private void drawGlass(int cols, int rows, String clip, int texture) {
        float halfW = cols * 0.5F - MARGIN;
        float halfH = rows * 0.5F - MARGIN;
        float sx = Math.max(0.01F, halfW - BEZEL);
        float sy = Math.max(0.01F, halfH - BEZEL);

        if (texture <= 0) {
            fillQuad(-sx, -sy, sx, sy, GLASS_Z, STANDBY_GLASS);
            // A small amber lamp in the corner: the difference between "no feed"
            // and "dead block" that a player can see from across the room.
            float ledY = -sy + 0.035F;
            float ledX = sx - 0.14F;
            fillQuad(ledX, ledY, ledX + 0.10F, ledY + 0.012F, GLASS_Z - 0.001F, STANDBY_LED);
            return;
        }

        // Black backing: the bars of a letterboxed picture and the seam behind
        // the glass in one quad.
        fillQuad(-sx, -sy, sx, sy, GLASS_Z, 0xFF000000);

        float vx = sx;
        float vy = sy;
        int[] size = VideoScreenPlayback.frameSize(clip);
        if (size != null && size[0] > 0 && size[1] > 0) {
            float panelAspect = sx / sy;
            float videoAspect = (float) size[0] / (float) size[1];
            if (videoAspect > panelAspect) {
                vy = sx / videoAspect;
            } else {
                vx = sy * videoAspect;
            }
        }
        drawTexturedQuad(-vx, -vy, vx, vy, GLASS_Z - 0.0008F, texture);
    }

    // --------------------------------------------------------------- drawing

    private static void fillQuad(float x0, float y0, float x1, float y1, float z, int argb) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        float a = ((argb >>> 24) & 255) / 255.0F;
        float r = ((argb >> 16) & 255) / 255.0F;
        float g = ((argb >> 8) & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;

        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        buffer.pos(x0, y1, z).color(r, g, b, a).endVertex();
        buffer.pos(x1, y1, z).color(r, g, b, a).endVertex();
        buffer.pos(x1, y0, z).color(r, g, b, a).endVertex();
        buffer.pos(x0, y0, z).color(r, g, b, a).endVertex();
        tessellator.draw();
    }

    private static void drawTexturedQuad(float x0, float y0, float x1, float y1, float z, int texture) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(texture);

        // The viewer stands outside this face, so their right hand is local +x:
        // image column 0 lands on their left, which is x0.
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        buffer.pos(x0, y1, z).tex(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x1, y1, z).tex(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x1, y0, z).tex(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x0, y0, z).tex(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        tessellator.draw();

        PanelPainter.restoreAtlas();
    }

    // ----------------------------------------------------------------- model

    /** Housing for this size, built once and shared by every screen of that size. */
    private static List<Face> modelFor(int cols, int rows) {
        long key = ((long) cols << 8) | (rows & 0xFFL);
        List<Face> faces = MODELS.get(key);
        if (faces == null) {
            if (MODELS.size() >= MODEL_CACHE_LIMIT) {
                MODELS.clear();
            }
            faces = buildHousing(cols, rows);
            MODELS.put(key, faces);
        }
        return faces;
    }

    private static List<Face> buildHousing(int cols, int rows) {
        float hx = cols * 0.5F - MARGIN;
        float hy = rows * 0.5F - MARGIN;
        float sx = Math.max(0.01F, hx - BEZEL);
        float sy = Math.max(0.01F, hy - BEZEL);

        List<Face> faces = new ArrayList<Face>();

        // Back plate against the wall.
        faces.add(flat(-hx, -hy, hx, hy, 0.0F, HOUSING_BACK));

        // The four rims that give the housing its thickness.
        faces.add(wall(-hx, hy, hx, HOUSING_SIDE));        // top
        faces.add(wall(-hx, -hy, hx, HOUSING_SIDE));       // bottom
        faces.add(wallV(-hx, -hy, hy, HOUSING_SIDE));      // left
        faces.add(wallV(hx, -hy, hy, HOUSING_SIDE));       // right

        // Bezel: four strips around the glass, plus a lit top edge.
        faces.add(flat(-hx, sy, hx, hy, FRONT_Z, BEZEL_FACE));
        faces.add(flat(-hx, -hy, hx, -sy, FRONT_Z, BEZEL_FACE));
        faces.add(flat(-hx, -sy, -sx, sy, FRONT_Z, BEZEL_FACE));
        faces.add(flat(sx, -sy, hx, sy, FRONT_Z, BEZEL_FACE));
        faces.add(flat(-hx, hy - 0.012F, hx, hy, FRONT_Z - 0.0006F, BEZEL_EDGE));

        return faces;
    }

    /** A quad on the local z plane. */
    private static Face flat(float x0, float y0, float x1, float y1, float z, int argb) {
        return new Face(new float[]{x0, x1, x1, x0},
                new float[]{y0, y0, y1, y1},
                new float[]{z, z, z, z}, argb);
    }

    /** A vertical rim along local x, spanning z from the wall to the bezel. */
    private static Face wall(float x0, float y, float x1, int argb) {
        return new Face(new float[]{x0, x1, x1, x0},
                new float[]{y, y, y, y},
                new float[]{0.0F, 0.0F, FRONT_Z, FRONT_Z}, argb);
    }

    /** A vertical rim along local y (left/right edges of the housing). */
    private static Face wallV(float x, float y0, float y1, int argb) {
        return new Face(new float[]{x, x, x, x},
                new float[]{y0, y1, y1, y0},
                new float[]{0.0F, 0.0F, FRONT_Z, FRONT_Z}, argb);
    }

    // -------------------------------------------------------------- plumbing

    private static EnumFacing facingOf(TileEntityVideoScreen te) {
        if (!te.hasWorld()) {
            return null;
        }
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockVideoScreen)) {
            return null;
        }
        return state.getValue(BlockVideoScreen.FACING);
    }

    /** Releases every texture and cached housing; called on world unload. */
    public static void clearCaches() {
        VideoScreenPlayback.clearAll();
        MODELS.clear();
    }
}

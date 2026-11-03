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

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.block.BlockDFCMonitor;
import com.hbmzz.client.ConsoleModel.Face;
import com.hbmzz.client.screen.DfcScreen;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.tile.TileEntityDFCMonitor;
import com.hbmzz.tile.TileEntityMonitorCamera;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Draws the console in the world.
 *
 * <p>This class owns no geometry any more. {@link ConsoleModel} builds the
 * machine - ceiling bracket, housing, frame, bolts, fins - as a list of quads
 * already placed in the panel's local frame, and all this has to do is put that
 * frame at the right block, hand the quads to the tessellator and texture the
 * glass on top. The reason is testability: {@code tools/RigPreview} projects
 * the very same list to a PNG, so "does this look like a machine" can be
 * answered without launching the game.</p>
 *
 * <p>Two things here are load-bearing. <b>Culling is off for the whole
 * assembly</b>: the terrain pass leaves {@code GL_CULL_FACE} enabled, and a
 * single wrong-facing triangle becomes an invisible panel rather than an
 * obviously broken one - which reads to a player as "the screen is frozen on its
 * block texture". And <b>the texture unit is forced back to 0</b> before
 * binding, because another mod may have left a different unit active, in which
 * case the panel samples whatever happened to be there.</p>
 */
public class RenderDFCMonitor extends TileEntitySpecialRenderer<TileEntityDFCMonitor> {

    /**
     * One canvas per monitor. Weak keys so an unloaded world does not pin tile
     * entities; the GL texture behind each canvas is released explicitly in
     * {@link #clearCaches()} on world unload.
     */
    private static final Map<TileEntityDFCMonitor, DfcScreen> CANVASES =
            new WeakHashMap<TileEntityDFCMonitor, DfcScreen>();

    @Override
    public void render(TileEntityDFCMonitor te, double x, double y, double z,
                       float partialTicks, int destroyStage, float alpha) {
        if (te == null || te.getWorld() == null || !HBMZZConfig.liveScreen) {
            return;
        }
        // NO distance test here. The x/y/z handed to a TESR are already
        // camera-relative, and an earlier version compared them against the
        // player's absolute position - effectively computing 2*viewX - teX.
        // At any normal world coordinate that is far outside the budget, so
        // every monitor was discarded on every frame: the cells read as air and
        // only the selection box was left. Frustum culling already happens
        // outside, via TileEntity.getRenderBoundingBox.
        EnumFacing facing = facingOf(te);
        if (facing == null) {
            return;
        }
        int cols = te.getScreenCols();
        int rows = te.getScreenRows();
        // Panels placed by this version have filler cells, and those cells have
        // no tile entity - so this one is the only draw call and the neighbour
        // test must not run: it cannot tell a cell of this panel from a
        // separate console standing next to it, and would skip the neighbour.
        // Only old saves, where all twelve cells are controllers, still need it.
        if (!BlockDFCMonitor.hasFillerCells(te.getWorld(), te.getPos(), facing, cols, rows)
                && coveredByNeighbour(te, facing)) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.pushAttrib();
        try {
            DfcScreen canvas = canvasFor(te);
            update(canvas, te);

            int tilt = te.getTilt();

            // The renderer is handed the controller cell's minimum corner, but
            // the geometry is authored about the panel's geometric centre.
            // anchor() is that offset, and it is the only place the two frames
            // are reconciled: the controller is the panel's top-left cell, so
            // the centre is half a panel right and half a panel down from it.
            // Skipping it - or using the block centre plus a vertical offset,
            // which is what this did before - leaves x un-corrected and the
            // whole machine lands half a panel to the right of its own cells.
            double[] anchor = ConsoleRig.anchor(cols, rows, facing);
            GlStateManager.translate(x + anchor[0], y + anchor[1], z + anchor[2]);
            GlStateManager.rotate(ConsoleRig.yawOf(facing), 0.0F, 1.0F, 0.0F);

            GlStateManager.disableCull();

            // The faces come back already tipped about the hinge and pushed
            // forward, so there is no matrix work left to do here.
            List<Face> faces = ConsoleModel.build(cols, rows, tilt, te.isPowered(), hasCeiling(te));
            drawFaces(faces);
            drawPanel(canvas, ConsoleModel.panelCorners(cols, rows, tilt));

            GlStateManager.enableCull();
        } catch (Throwable throwable) {
            // A renderer that throws takes the client down with it; a monitor
            // that skips one frame does not.
            HBMZZ.logger.error("DFC monitor at " + te.getPos() + " failed to render", throwable);
        } finally {
            GlStateManager.popAttrib();
            GlStateManager.popMatrix();
        }
    }

    /**
     * Feeds the canvas and keeps the client-side clocks.
     *
     * <p>The boot clock has to be started somewhere, and the first frame this
     * client draws is the only moment that is guaranteed to be "just after the
     * chunk appeared" - doing it here means a screen you walk up to plays its
     * start-up once, then stays on the live page.</p>
     */
    private void update(DfcScreen canvas, TileEntityDFCMonitor te) {
        long now = System.currentTimeMillis();
        boolean powered = te.isPowered();
        if (powered && te.getClientBootAt() == 0L) {
            te.setClientBootAt(now);
        }
        long sinceOn = 0L;
        if (powered) {
            long bootAt = te.getClientBootAt();
            // A clock skew or a world reload can leave the stamp in the future;
            // treating it as "just booted" beats showing a negative uptime.
            sinceOn = bootAt == 0L ? 0L : Math.max(0L, now - bootAt);
        }
        long faultAt = te.getClientFaultAt();
        long faultElapsed = faultAt == 0L ? -1L : Math.max(0L, now - faultAt);

        DFCSnapshot data = te.getClientData();
        TileEntityMonitorCamera.CameraView cameraView = null;
        if (te.isCameraEnabled() && te.getCameraPos() != null && te.getWorld() != null) {
            TileEntity cameraTe = te.getWorld().getTileEntity(te.getCameraPos());
            if (cameraTe instanceof TileEntityMonitorCamera) {
                cameraView = ((TileEntityMonitorCamera) cameraTe).capture(te.getWorld());
            }
        }
        canvas.setLinkAge(te.getClientDataAge());
        canvas.setGeometry(te.getScreenCols(), te.getScreenRows());
        // Alarm state is server state, but whether it is still running is a
        // question about the clock this client shares with the server, so the
        // frame decides it here instead of waiting for a packet that may be
        // several ticks late.
        boolean alarm = te.isAlarmActive();
        canvas.setCustom(te.getPanelLayout(), alarm ? Math.max(0, te.getAlarmLevel()) : -1,
                alarm ? te.getAlarmText() : "");
        canvas.render(sinceOn, powered, data, te.getPage(), te.getClientTempRate(), faultElapsed,
                cameraView);
        canvas.upload();
    }

    // ------------------------------------------------------------------ drawing

    private static void drawFaces(List<Face> faces) {
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
     * The glass. One quad, textured, sitting just inside the frame so it cannot
     * z-fight with it.
     */
    private void drawPanel(DfcScreen canvas, float[][] corners) {
        if (canvas == null || !canvas.hasTexture()) {
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
        GlStateManager.bindTexture(canvas.textureId());

        float[] uv = ConsoleRig.panelUv();
        float ux0 = uv[0];
        float ux1 = uv[1];
        float vy0 = uv[2];
        float vy1 = uv[3];
        // corners[] is (x1,y0) (x0,y0) (x0,y1) (x1,y1): bottom-right first.
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

        // Hand the terrain atlas back. GlStateManager caches the binding, so
        // leaving the screen texture bound here makes whatever draws next -
        // the next chunk, the clouds, another TESR - sample this monitor's
        // 256-pixel canvas stretched over the world, which reads as scrambled
        // blocks scattered around the machine.
        net.minecraft.client.renderer.texture.TextureMap atlas =
                Minecraft.getMinecraft().getTextureMapBlocks();
        if (atlas != null) {
            GlStateManager.bindTexture(atlas.getGlTextureId());
        }

        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    // ------------------------------------------------------------------ plumbing

    private static DfcScreen canvasFor(TileEntityDFCMonitor te) {
        DfcScreen canvas = CANVASES.get(te);
        if (canvas == null) {
            canvas = new DfcScreen();
            CANVASES.put(te, canvas);
        }
        return canvas;
    }

    /** Releases every canvas texture; called when the client leaves a world. */
    public static void clearCaches() {
        for (DfcScreen canvas : CANVASES.values()) {
            try {
                canvas.dispose();
            } catch (Throwable ignored) {
                // Nothing useful to do while tearing a world down.
            }
        }
        CANVASES.clear();
    }

    private EnumFacing facingOf(TileEntityDFCMonitor te) {
        if (!te.hasWorld()) {
            return null;
        }
        IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (!(state.getBlock() instanceof BlockDFCMonitor)) {
            return null;
        }
        return state.getValue(BlockDFCMonitor.FACING);
    }

    /** A rig only exists when there is something above the top row to bolt it to. */
    private boolean hasCeiling(TileEntityDFCMonitor te) {
        if (!te.hasWorld()) {
            return false;
        }
        return !te.getWorld().isAirBlock(te.getPos().up());
    }

    /**
     * True when a monitor whose origin is up and to the left of this cell
     * already paints over it, so a wall of monitor blocks draws the panel once.
     *
     * <p>The origin of a painted panel is its top-left cell, so a cell can only
     * be covered by a monitor at {@code here - right*dc - down*dr}. Looking for
     * it on the other side - which is what this used to do - finds nothing for
     * the bottom-right cell of every structure, and that cell happily painted a
     * second, smaller copy of the screen one block off.</p>
     *
     * <p>The scan is bounded by the configured maximum size rather than by this
     * cell's own size: a fresh cell is 2x2 by default while the panel around it
     * may have been grown to 4x3, and a bound of 2 would never reach the
     * origin three columns away.</p>
     */
    private boolean coveredByNeighbour(TileEntityDFCMonitor te, EnumFacing facing) {
        if (!te.hasWorld()) {
            return false;
        }
        BlockPos here = te.getPos();
        int maxCols = Math.max(1, HBMZZConfig.maxScreenCols);
        int maxRows = Math.max(1, HBMZZConfig.maxScreenRows);
        for (int back = 0; back < maxCols; back++) {
            for (int up = 0; up < maxRows; up++) {
                if (back == 0 && up == 0) {
                    continue;
                }
                BlockPos other = ConsoleRig.partPos(here, -back, -up, facing);
                if (other == null || !te.getWorld().isBlockLoaded(other)) {
                    continue;
                }
                TileEntityDFCMonitor monitor = asMonitor(te, other, facing);
                if (monitor != null && monitor.getScreenCols() > back && monitor.getScreenRows() > up) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Neighbour at {@code other}, or null when it is not a monitor facing the same way. */
    private static TileEntityDFCMonitor asMonitor(TileEntityDFCMonitor te, BlockPos other, EnumFacing facing) {
        Object candidate = te.getWorld().getTileEntity(other);
        if (!(candidate instanceof TileEntityDFCMonitor) || candidate == te) {
            return null;
        }
        IBlockState state = te.getWorld().getBlockState(other);
        if (!(state.getBlock() instanceof BlockDFCMonitor)
                || state.getValue(BlockDFCMonitor.FACING) != facing) {
            return null;
        }
        return (TileEntityDFCMonitor) candidate;
    }
}

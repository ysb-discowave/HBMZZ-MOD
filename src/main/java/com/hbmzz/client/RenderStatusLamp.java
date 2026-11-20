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

import com.hbmzz.HBMZZ;
import com.hbmzz.alert.AlertCode;
import com.hbmzz.block.BlockStatusLamp;
import com.hbmzz.tile.TileEntityStatusLamp;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

/**
 * Draws the status lamp: a coloured lens on the block's facing face.
 *
 * <p>The colour is the facility's - the custom colour when a custom code is up,
 * otherwise the standard code's colour. There is nothing else to draw: a lamp
 * that showed anything but the current colour would be lying, and a lamp the
 * player cannot read at a glance is not a lamp. Geometry is manual and
 * self-illuminated, the same way the DFC parts draw it, so the lens stays lit in
 * a dark room and cannot disagree with the block's facing.</p>
 */
public class RenderStatusLamp extends TileEntitySpecialRenderer<TileEntityStatusLamp> {

    private static final double VIEW_RANGE = 48.0D;
    private static final double R_LENS = 5.4D / 16.0D;
    private static final double R_RIM = 6.4D / 16.0D;
    private static final double D_LENS = 0.02D;
    private static final int SEGMENTS = 20;

    @Override
    public void render(TileEntityStatusLamp te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        if (destroyStage >= 0 || te == null || te.getWorld() == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        BlockPos pos = te.getPos();
        if (mc.player.getDistanceSqToCenter(pos) > VIEW_RANGE * VIEW_RANGE) {
            return;
        }
        IBlockState state = te.getWorld().getBlockState(pos);
        if (!(state.getBlock() instanceof BlockStatusLamp)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockStatusLamp.FACING);

        int argb = facilityArgb();
        float cr = ((argb >> 16) & 0xFF) / 255.0F;
        float cg = ((argb >> 8) & 0xFF) / 255.0F;
        float cb = (argb & 0xFF) / 255.0F;

        try {
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            GlStateManager.disableLighting();
            GlStateManager.disableBlend();
            GlStateManager.disableTexture2D();
            GlStateManager.disableCull();
            GlStateManager.disableRescaleNormal();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

            Frame f = new Frame(facing);
            drawRim(f);
            drawLens(f, cr, cg, cb);

            GlStateManager.enableRescaleNormal();
            GlStateManager.enableCull();
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        } catch (Throwable throwable) {
            // A renderer that throws takes the client down with it; a lamp that
            // skips one frame does not.
            HBMZZ.logger.error("Status lamp at " + pos + " failed to render", throwable);
        }
    }

    /** The lamp's colour: the facility's, custom-aware. */
    private static int facilityArgb() {
        if (ClientAlertState.isCustom()) {
            return 0xFF000000 | (ClientAlertState.customRgb() & 0xFFFFFF);
        }
        return AlertCode.byId(ClientAlertState.code()).argb();
    }

    /** A dark bezel ring so the lens reads as something set into the block. */
    private void drawRim(Frame f) {
        BufferBuilder buf = begin();
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.toRadians(360.0D * i / SEGMENTS);
            double a1 = Math.toRadians(360.0D * (i + 1) / SEGMENTS);
            quad(buf, f, R_RIM, Math.cos(a0), Math.sin(a0), R_RIM, Math.cos(a1), Math.sin(a1),
                    R_LENS, Math.cos(a1), Math.sin(a1), R_LENS, Math.cos(a0), Math.sin(a0),
                    0.14F, 0.16F, 0.18F, D_LENS - 0.004D);
        }
        Tessellator.getInstance().draw();
    }

    /** The coloured disc. */
    private void drawLens(Frame f, float cr, float cg, float cb) {
        BufferBuilder buf = begin(GL11.GL_TRIANGLE_FAN);
        buf.pos(f.x(0.0D, 1.0D, 0.0D, D_LENS), f.y(0.0D, 1.0D, 0.0D, D_LENS),
                f.z(0.0D, 1.0D, 0.0D, D_LENS)).color(cr, cg, cb, 1.0F).endVertex();
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.toRadians(360.0D * i / SEGMENTS);
            double c = Math.cos(a);
            double s = Math.sin(a);
            buf.pos(f.x(R_LENS, c, s, D_LENS), f.y(R_LENS, c, s, D_LENS),
                    f.z(R_LENS, c, s, D_LENS)).color(cr, cg, cb, 1.0F).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    private static final class Frame {
        final double nx;
        final double ny;
        final double nz;
        final double ux;
        final double uy;
        final double uz;
        final double vx;
        final double vy;
        final double vz;

        Frame(EnumFacing facing) {
            this.nx = facing.getFrontOffsetX();
            this.ny = facing.getFrontOffsetY();
            this.nz = facing.getFrontOffsetZ();
            if (this.ny != 0) {
                this.ux = 1.0D;
                this.uy = 0.0D;
                this.uz = 0.0D;
                this.vx = 0.0D;
                this.vy = 0.0D;
                this.vz = 1.0D;
            } else {
                this.ux = this.nz;
                this.uy = 0.0D;
                this.uz = -this.nx;
                this.vx = 0.0D;
                this.vy = 1.0D;
                this.vz = 0.0D;
            }
        }

        double x(double r, double cos, double sin, double depth) {
            return 0.5D + this.ux * (r * cos) + this.vx * (r * sin) + this.nx * (0.5D + depth);
        }

        double y(double r, double cos, double sin, double depth) {
            return 0.5D + this.uy * (r * cos) + this.vy * (r * sin) + this.ny * (0.5D + depth);
        }

        double z(double r, double cos, double sin, double depth) {
            return 0.5D + this.uz * (r * cos) + this.vz * (r * sin) + this.nz * (0.5D + depth);
        }
    }

    private static BufferBuilder begin() {
        return begin(GL11.GL_QUADS);
    }

    private static BufferBuilder begin(int mode) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.getBuffer();
        buf.begin(mode, DefaultVertexFormats.POSITION_COLOR);
        return buf;
    }

    private static void quad(BufferBuilder buf, Frame f,
                             double r1, double c1, double s1,
                             double r2, double c2, double s2,
                             double r3, double c3, double s3,
                             double r4, double c4, double s4,
                             float cr, float cg, float cb, double depth) {
        buf.pos(f.x(r1, c1, s1, depth), f.y(r1, c1, s1, depth), f.z(r1, c1, s1, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(r2, c2, s2, depth), f.y(r2, c2, s2, depth), f.z(r2, c2, s2, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(r3, c3, s3, depth), f.y(r3, c3, s3, depth), f.z(r3, c3, s3, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(r4, c4, s4, depth), f.y(r4, c4, s4, depth), f.z(r4, c4, s4, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
    }

    /** Releases any renderer state; a no-op here but kept for symmetry. */
    public static void clearCaches() {
        // Nothing to free: geometry is rebuilt every frame.
    }
}

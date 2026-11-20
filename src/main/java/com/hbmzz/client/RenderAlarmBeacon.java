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
import com.hbmzz.HBMZZConfig;
import com.hbmzz.alert.AlertCode;
import com.hbmzz.block.BlockAlarmBeacon;
import com.hbmzz.tile.TileEntityAlarmBeacon;
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
 * Draws the rotating alarm beacon.
 *
 * <p>Active when the facility's active code climbs above the configured safe
 * level; when active it shows the facility colour - the custom colour when a
 * custom code is up, otherwise the standard one - spinning across the block's
 * facing face. When the code drops back to or below the safe level the beacon
 * shows a dim, unlit disc instead, so a player can tell at a glance that the
 * lamp is present but quiet.
 *
 * <p>The spin is the exhaust-fan's: geometry is computed from the facing's own
 * basis vectors with no {@code GlStateManager.rotate}, so it cannot disagree
 * with the block, and the phase is taken modulo a period that is an exact
 * multiple of 360 degrees so a long-lived world never jitters.</p>
 */
public class RenderAlarmBeacon extends TileEntitySpecialRenderer<TileEntityAlarmBeacon> {

    private static final double VIEW_RANGE = 48.0D;

    /** The period must make {@code SPIN_PERIOD_TICKS * SPIN_DEG_PER_TICK} a multiple of 360. */
    private static final float SPIN_DEG_PER_TICK = 12.0F;
    private static final long SPIN_PERIOD_TICKS = 3000L; // 3000 * 12 = 36000 = 100 * 360

    private static final int SECTOR_COUNT = 4;
    private static final double SECTOR_ARC = 64.0D;

    private static final double R_LENS = 6.0D / 16.0D;
    private static final double R_HUB = 1.6D / 16.0D;
    private static final double D_LENS = 0.02D;
    private static final int SEGMENTS = 24;

    @Override
    public void render(TileEntityAlarmBeacon te, double x, double y, double z, float partialTicks,
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
        if (!(state.getBlock() instanceof BlockAlarmBeacon)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockAlarmBeacon.FACING);

        int codeLevel = ClientAlertState.code() & 0x0F;
        boolean active = codeLevel > HBMZZConfig.alertSafeLevel;
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
            if (active) {
                double spin = spinAngle(te, partialTicks);
                drawRim(f);
                drawSectors(f, spin, cr, cg, cb);
                drawHub(f, cr, cg, cb);
            } else {
                // Quiet: a dim disc, so the beacon is visibly present but not alarming.
                drawRim(f);
                drawLens(f, 0.18F, 0.20F, 0.22F);
            }

            GlStateManager.enableRescaleNormal();
            GlStateManager.enableCull();
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        } catch (Throwable throwable) {
            HBMZZ.logger.error("Alarm beacon at " + pos + " failed to render", throwable);
        }
    }

    /** The beacon's colour: the facility's, custom-aware. */
    private static int facilityArgb() {
        if (ClientAlertState.isCustom()) {
            return 0xFF000000 | (ClientAlertState.customRgb() & 0xFFFFFF);
        }
        return AlertCode.byId(ClientAlertState.code()).argb();
    }

    /** Current spin angle in degrees, 0..360; 0 when the beacon is quiet. */
    private static double spinAngle(TileEntityAlarmBeacon te, float partialTicks) {
        long ticks = te.getWorld().getTotalWorldTime() % SPIN_PERIOD_TICKS;
        return ((ticks + partialTicks) * SPIN_DEG_PER_TICK) % 360.0D;
    }

    /** A dark bezel ring, so the spinning sectors read as a light, not a sticker. */
    private void drawRim(Frame f) {
        BufferBuilder buf = begin();
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.toRadians(360.0D * i / SEGMENTS);
            double a1 = Math.toRadians(360.0D * (i + 1) / SEGMENTS);
            ringSegment(buf, f, R_LENS - 0.045D, R_LENS + 0.045D,
                    a0, a1, 0.14F, 0.16F, 0.18F, D_LENS);
        }
        Tessellator.getInstance().draw();
    }

    /**
     * The spinning part: alternate bright facility-colour sectors with dark gaps,
     * so rotation reads as movement rather than a blur.
     */
    private void drawSectors(Frame f, double spin, float cr, float cg, float cb) {
        BufferBuilder buf = begin();
        double step = 360.0D / SECTOR_COUNT;
        for (int i = 0; i < SECTOR_COUNT; i++) {
            double a0 = Math.toRadians(spin + step * i);
            double a1 = Math.toRadians(spin + step * i + SECTOR_ARC);
            // Bright sector: a wedge from the hub to the lens edge.
            wedge(buf, f, R_HUB, R_LENS, a0, a1, cr, cg, cb, D_LENS);
            // A darker trailing edge, so each sector has a visible front.
            wedge(buf, f, R_HUB, R_LENS, a1, a1 + 0.12,
                    cr * 0.4F, cg * 0.4F, cb * 0.4F, D_LENS + 0.006D);
        }
        Tessellator.getInstance().draw();
    }

    /** A bright hub cap at the centre. */
    private void drawHub(Frame f, float cr, float cg, float cb) {
        BufferBuilder buf = begin(GL11.GL_TRIANGLE_FAN);
        buf.pos(f.x(0.0D, 1.0D, 0.0D, D_LENS + 0.004D),
                f.y(0.0D, 1.0D, 0.0D, D_LENS + 0.004D),
                f.z(0.0D, 1.0D, 0.0D, D_LENS + 0.004D)).color(1.0F, 1.0F, 1.0F, 1.0F).endVertex();
        for (int i = 0; i <= 12; i++) {
            double a = Math.toRadians(360.0D * i / 12);
            double c = Math.cos(a);
            double s = Math.sin(a);
            buf.pos(f.x(R_HUB, c, s, D_LENS + 0.004D), f.y(R_HUB, c, s, D_LENS + 0.004D),
                    f.z(R_HUB, c, s, D_LENS + 0.004D)).color(0.9F, 0.9F, 0.9F, 1.0F).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    /** The quiet-state disc. */
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

    /** A thin annular ring segment between two radii and two angles. */
    private static void ringSegment(BufferBuilder buf, Frame f, double rIn, double rOut,
                                   double a0, double a1, float cr, float cg, float cb, double depth) {
        buf.pos(f.x(rOut, Math.cos(a0), Math.sin(a0), depth),
                f.y(rOut, Math.cos(a0), Math.sin(a0), depth),
                f.z(rOut, Math.cos(a0), Math.sin(a0), depth)).color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(rOut, Math.cos(a1), Math.sin(a1), depth),
                f.y(rOut, Math.cos(a1), Math.sin(a1), depth),
                f.z(rOut, Math.cos(a1), Math.sin(a1), depth)).color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(rIn, Math.cos(a1), Math.sin(a1), depth),
                f.y(rIn, Math.cos(a1), Math.sin(a1), depth),
                f.z(rIn, Math.cos(a1), Math.sin(a1), depth)).color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(rIn, Math.cos(a0), Math.sin(a0), depth),
                f.y(rIn, Math.cos(a0), Math.sin(a0), depth),
                f.z(rIn, Math.cos(a0), Math.sin(a0), depth)).color(cr, cg, cb, 1.0F).endVertex();
    }

    /** A solid wedge from an inner to an outer radius over an angle range. */
    private static void wedge(BufferBuilder buf, Frame f, double rIn, double rOut,
                              double a0, double a1, float cr, float cg, float cb, double depth) {
        double c0 = Math.cos(a0);
        double s0 = Math.sin(a0);
        double c1 = Math.cos(a1);
        double s1 = Math.sin(a1);
        buf.pos(f.x(rOut, c0, s0, depth), f.y(rOut, c0, s0, depth), f.z(rOut, c0, s0, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(rOut, c1, s1, depth), f.y(rOut, c1, s1, depth), f.z(rOut, c1, s1, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(rIn, c1, s1, depth), f.y(rIn, c1, s1, depth), f.z(rIn, c1, s1, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(rIn, c0, s0, depth), f.y(rIn, c0, s0, depth), f.z(rIn, c0, s0, depth))
                .color(cr, cg, cb, 1.0F).endVertex();
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

    /** Releases any renderer state; a no-op here, kept for symmetry. */
    public static void clearCaches() {
        // Nothing to free: geometry is rebuilt every frame.
    }
}

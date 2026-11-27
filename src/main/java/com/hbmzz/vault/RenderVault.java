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

package com.hbmzz.vault;

import com.hbmzz.HBMZZ;
import com.hbmzz.alert.AlertCode;
import com.hbmzz.client.ClientAlertState;
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

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Draws the bank vault door as one object: a steel frame, a big leaf that swings
 * on its left hinge, a handle wheel, and a live status plate.
 *
 * <p>Only the corner cell carries the tile entity, so this renderer - bound to
 * that cell - draws the whole five-by-six structure; every other cell renders
 * nothing. The block itself is invisible on purpose (its javadoc says so): a
 * baked block model cannot interpolate, and the whole point of the vault is that
 * the leaf swings. So the geometry is built by hand here, every frame, from the
 * block's facing and the door's open state.</p>
 *
 * <h3>Smooth without a new sync field</h3>
 * <p>The open state in the block is a plain boolean, which snaps. To make the
 * leaf glide, this renderer keeps, per door position, a float that eases toward
 * the target each frame and swings the leaf by that much. It is deliberately a
 * client-side cache, not a tile-entity field: there is no network traffic, it
 * is rebuilt the moment a door appears, and a cache that is merely a few
 * doors' worth of memory is the right tool for a purely cosmetic animation.</p>
 */
public class RenderVault extends TileEntitySpecialRenderer<TileEntityVault> {

    private static final double VIEW_RANGE = 40.0D;
    /** How quickly the swing closes on the target; per frame, framerate-ish. */
    private static final double EASE = 0.22D;
    /** How far the leaf swings open from shut. */
    private static final double OPEN_ANGLE = Math.toRadians(108.0D);
    private static final int SEGMENTS = 28;

    private final Map<Long, Float> swing = new HashMap<Long, Float>();

    @Override
    public void render(TileEntityVault te, double x, double y, double z, float partialTicks,
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
        if (!(state.getBlock() instanceof BlockVault)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockVault.FACING);

        double amount = swingAmount(pos, state.getValue(BlockVault.OPEN));

        int argb = facilityArgb();
        float cr = ((argb >> 16) & 0xFF) / 255.0F;
        float cg = ((argb >> 8) & 0xFF) / 255.0F;
        float cb = (argb & 0xFF) / 255.0F;

        try {
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.disableTexture2D();
            GlStateManager.disableRescaleNormal();
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

            Frame f = new Frame(facing);
            // The frame (jambs + lintel) never moves.
            drawFrame(f);
            // The leaf swings on its left hinge by `amount` of the full angle.
            // Positive theta folds it into the wall rather than out toward the
            // viewer, the way a real bank vault leaf retracts.
            double theta = OPEN_ANGLE * amount;
            drawLeaf(f, theta, cr, cg, cb);
            // The status plate sits on the lintel so it is always readable, even
            // when the leaf is open and swung out of the way.
            drawStatusPlate(f, cr, cg, cb);

            GlStateManager.enableRescaleNormal();
            GlStateManager.enableCull();
            GlStateManager.enableTexture2D();
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        } catch (Throwable throwable) {
            // A renderer that throws takes the client down with it; a door that
            // skips one frame does not.
            HBMZZ.logger.error("Vault door at " + pos + " failed to render", throwable);
        }
    }

    // -------------------------------------------------------------- easing

    /** Eases the door's open-amount toward its target; 0 shut, 1 open. */
    private float swingAmount(BlockPos pos, boolean open) {
        Long key = pos.toLong();
        Float last = this.swing.get(key);
        float current = last == null ? (open ? 1.0F : 0.0F) : last.floatValue();
        float target = open ? 1.0F : 0.0F;
        current += (target - current) * (float) EASE;
        if (Math.abs(current - target) < 0.002F) {
            current = target;
        }
        this.swing.put(key, current);
        if (this.swing.size() > 512) {
            prune();
        }
        return current;
    }

    private void prune() {
        if (this.swing.size() <= 256) {
            return;
        }
        Iterator<Long> it = this.swing.keySet().iterator();
        while (it.hasNext() && this.swing.size() > 256) {
            it.next();
            it.remove();
        }
    }

    // ------------------------------------------------------------------ scene

    private void drawFrame(Frame f) {
        float[] steel = {0.55F, 0.57F, 0.60F};
        float[] dark = {0.32F, 0.33F, 0.35F};
        BufferBuilder buf = begin();
        // Left and right jambs, full height.
        box(buf, f, 0.0D, 0.0D, 0.5D, 0.5D, 0.5D, 1.0D, steel);
        box(buf, f, 4.5D, 0.0D, 0.5D, 5.0D, 0.5D, 1.0D, steel);
        // Lintel across the top.
        box(buf, f, 0.0D, 5.5D, 0.5D, 5.0D, 6.0D, 1.0D, steel);
        // A recessed back wall so the open gap is not a hole to the sky.
        box(buf, f, 0.0D, 0.0D, 0.0D, 5.0D, 6.0D, 0.06D, dark);
        Tessellator.getInstance().draw();
    }

    private void drawLeaf(Frame f, double theta, float cr, float cg, float cb) {
        float[] plate = {0.60F, 0.62F, 0.66F};
        float[] edge = {0.38F, 0.40F, 0.44F};
        float[] handle = {0.78F, 0.80F, 0.83F};

        BufferBuilder buf = begin();
        // The plate: from the left hinge (a=0.5) to the right jamb (a=4.5),
        // floor to lintel, centred on the cell's normal axis.
        leafBox(buf, f, theta, 0.5D, 0.0D, 4.5D, 5.5D, plate);
        // A darker rim around the plate so it reads as a door, not a slab.
        leafBox(buf, f, theta, 0.5D, 0.0D, 4.5D, 0.40D, edge);      // bottom rim
        leafBox(buf, f, theta, 4.10D, 0.0D, 4.5D, 5.5D, edge);      // right rim
        // The handle wheel, on the plate a little right of centre and up.
        drawWheel(buf, f, theta, 3.0D, 3.0D, 1.3D, handle);
        // A small status light on the plate itself.
        leafBox(buf, f, theta, 1.10D, 4.60D, 1.70D, 5.20D, new float[]{cr, cg, cb});
        Tessellator.getInstance().draw();
    }

    private void drawStatusPlate(Frame f, float cr, float cg, float cb) {
        // A fixed plate on the lintel: dark bezel, then the live facility colour.
        BufferBuilder buf = begin();
        box(buf, f, 2.10D, 5.56D, 0.90D, 2.90D, 5.94D, 1.02D, new float[]{0.12F, 0.13F, 0.14F});
        quad(buf, f,
                2.18D, 5.62D, 0.90D,
                2.82D, 5.62D, 0.90D,
                2.82D, 5.88D, 0.90D,
                2.18D, 5.88D, 0.90D, new float[]{cr, cg, cb});
        Tessellator.getInstance().draw();
    }

    // ---------------------------------------------------------------- helpers

    /** The facility's colour, custom-aware - the same source the lamp reads. */
    private static int facilityArgb() {
        if (ClientAlertState.isCustom()) {
            return 0xFF000000 | (ClientAlertState.customRgb() & 0xFFFFFF);
        }
        return AlertCode.byId(ClientAlertState.code()).argb();
    }

    /** A thin box, axis-aligned in door space; depth in the cell's normal axis. */
    private static void box(BufferBuilder buf, Frame f,
                            double a0, double b0, double c0,
                            double a1, double b1, double c1, float[] col) {
        // Front face (toward the viewer, on the +c side).
        quad(buf, f, a0, b0, c1, a1, b0, c1, a1, b1, c1, a0, b1, c1, col);
        // The five other faces, darker, so the steel has some depth.
        float[] shade = new float[]{col[0] * 0.7F, col[1] * 0.7F, col[2] * 0.7F};
        quad(buf, f, a0, b0, c0, a1, b0, c0, a1, b1, c0, a0, b1, c0, shade);
        quad(buf, f, a0, b0, c0, a0, b1, c0, a0, b1, c1, a0, b0, c1, shade);
        quad(buf, f, a1, b0, c0, a1, b1, c0, a1, b1, c1, a1, b0, c1, shade);
        quad(buf, f, a0, b0, c0, a1, b0, c0, a1, b0, c1, a0, b0, c1, shade);
        quad(buf, f, a0, b1, c0, a1, b1, c0, a1, b1, c1, a0, b1, c1, shade);
    }

    /** A box whose left edge (a=0.5) is the hinge; swings with the leaf. */
    private static void leafBox(BufferBuilder buf, Frame f, double theta,
                               double a0, double b0, double a1, double b1, float[] col) {
        double c0 = 0.46D, c1 = 0.54D;
        // Hinge runs down the plate's left edge (a=0.5). Express every point's
        // distance from that hinge as (leafA, dC) and rotate the pair by theta.
        P p000 = rot(f, theta, a0, b0, c0);
        P p100 = rot(f, theta, a1, b0, c0);
        P p110 = rot(f, theta, a1, b1, c0);
        P p010 = rot(f, theta, a0, b1, c0);
        P p001 = rot(f, theta, a0, b0, c1);
        P p101 = rot(f, theta, a1, b0, c1);
        P p111 = rot(f, theta, a1, b1, c1);
        P p011 = rot(f, theta, a0, b1, c1);
        // Front (+c).
        quadP(buf, p001, p101, p111, p011, col);
        float[] shade = new float[]{col[0] * 0.7F, col[1] * 0.7F, col[2] * 0.7F};
        quadP(buf, p000, p100, p110, p010, shade);                 // back
        quadP(buf, p000, p010, p011, p001, shade);                // left (hinge)
        quadP(buf, p100, p110, p111, p101, shade);                // right (free)
        quadP(buf, p000, p100, p101, p001, shade);                // bottom
        quadP(buf, p010, p110, p111, p011, shade);                // top
    }

    /** A handle wheel: an annulus in the plate's plane, centred on (cx, cy). */
    private static void drawWheel(BufferBuilder buf, Frame f, double theta,
                                  double cx, double cy, double radius, float[] col) {
        double inner = radius * 0.72D;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.toRadians(360.0D * i / SEGMENTS);
            double a1 = Math.toRadians(360.0D * (i + 1) / SEGMENTS);
            P p0o = rot(f, theta, cx + radius * Math.cos(a0), cy + radius * Math.sin(a0), 0.60D);
            P p1o = rot(f, theta, cx + radius * Math.cos(a1), cy + radius * Math.sin(a1), 0.60D);
            P p1i = rot(f, theta, cx + inner * Math.cos(a1), cy + inner * Math.sin(a1), 0.60D);
            P p0i = rot(f, theta, cx + inner * Math.cos(a0), cy + inner * Math.sin(a0), 0.60D);
            quadP(buf, p0o, p1o, p1i, p0i, col);
        }
        // A hub so the wheel reads as a wheel and not a ring of nothing.
        P h = rot(f, theta, cx, cy, 0.60D);
        P h0 = rot(f, theta, cx + 0.30D, cy, 0.60D);
        P h1 = rot(f, theta, cx, cy + 0.30D, 0.60D);
        P h2 = rot(f, theta, cx - 0.30D, cy, 0.60D);
        P h3 = rot(f, theta, cx, cy - 0.30D, 0.60D);
        quadP(buf, h0, h1, h2, h3, new float[]{col[0] * 0.8F, col[1] * 0.8F, col[2] * 0.8F});
    }

    // -------------------------------------------------------- door-space math

    /** A rotated point, already in block-local world coords. */
    private static final class P {
        final double x, y, z;
        P(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * Maps a door-space point to block-local world. The leaf's hinge runs down
     * the vertical line a=0.5, c=0.5; the leaf is measured from there, so a
     * point swings around that line by {@code theta} before being placed.
     */
    private static P rot(Frame f, double theta, double a, double b, double c) {
        double leafA = a - 0.5D;
        double dC = c - 0.5D;
        double cos = Math.cos(theta);
        double sin = Math.sin(theta);
        double ar = leafA * cos + dC * sin;
        double cr = -leafA * sin + dC * cos;
        double blockA = 0.5D + ar;
        double blockC = 0.5D + cr;
        return new P(
                f.ux * blockA + f.nx * blockC,
                b,
                f.uz * blockA + f.nz * blockC);
    }

    /** A quad in door space, given as its four corners, mapped to world. */
    private static void quad(BufferBuilder buf, Frame f,
                             double a0, double b0, double c0,
                             double a1, double b1, double c1,
                             double a2, double b2, double c2,
                             double a3, double b3, double c3, float[] col) {
        quadP(buf,
                new P(f.ux * a0 + f.nx * c0, b0, f.uz * a0 + f.nz * c0),
                new P(f.ux * a1 + f.nx * c1, b1, f.uz * a1 + f.nz * c1),
                new P(f.ux * a2 + f.nx * c2, b2, f.uz * a2 + f.nz * c2),
                new P(f.ux * a3 + f.nx * c3, b3, f.uz * a3 + f.nz * c3), col);
    }

    private static void quadP(BufferBuilder buf, P p0, P p1, P p2, P p3, float[] col) {
        buf.pos(p0.x, p0.y, p0.z).color(col[0], col[1], col[2], 1.0F).endVertex();
        buf.pos(p1.x, p1.y, p1.z).color(col[0], col[1], col[2], 1.0F).endVertex();
        buf.pos(p2.x, p2.y, p2.z).color(col[0], col[1], col[2], 1.0F).endVertex();
        buf.pos(p3.x, p3.y, p3.z).color(col[0], col[1], col[2], 1.0F).endVertex();
    }

    /** The door's basis: u along the leaf's width, n out its face, y up. */
    private static final class Frame {
        final double nx, nz;
        final double ux, uz;

        Frame(EnumFacing facing) {
            this.nx = facing.getFrontOffsetX();
            this.nz = facing.getFrontOffsetZ();
            EnumFacing right = facing.rotateY();
            this.ux = right.getFrontOffsetX();
            this.uz = right.getFrontOffsetZ();
        }
    }

    private static BufferBuilder begin() {
        BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        return buf;
    }
}

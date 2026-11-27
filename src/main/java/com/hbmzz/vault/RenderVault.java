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
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Draws the bank vault door as a real 3D structure, modelled the way the nuke
 * is: several independent solid parts, each textured from the block atlas.
 *
 * <ul>
 *   <li><b>Frame</b> - two jambs, a lintel and a sill, static boxes.</li>
 *   <li><b>Leaf</b> - a 0.20-block-thick plate (front, back, two ends, a raised
 *       rim), swinging on the vertical hinge line at its left edge.</li>
 *   <li><b>Handle wheel</b> - a ring band, four spokes and a hub, all rotating
 *       around the wheel's own axis; the spokes and four grip knobs break the
 *       ring's symmetry so the turn is unmistakable.</li>
 *   <li><b>Lock bolts</b> - three flanges with shafts that slide out of the
 *       leaf into the right jamb; the shaft's solid length changes every tick
 *       of the bolt phase.</li>
 * </ul>
 *
 * <h3>Per-tick animation</h3>
 * <p>The block only stores a boolean, so this renderer anchors that boolean to
 * the world clock ({@code getTotalWorldTime() + partialTicks}) the moment it
 * sees it flip, and plays a fixed-length sequence against that anchor:
 * bolts retract (0-14%), the leaf swings 108 degrees and the wheel turns one
 * full 360 degrees together (14-90%), the leaf finishes (90-100%). Closing runs
 * the same windows backwards. Between 14% and 90% <em>both</em> the wheel angle
 * and the leaf angle change on every rendered tick, so no frame is a copy of
 * its neighbour. Purely client-side; no network traffic.</p>
 */
public class RenderVault extends TileEntitySpecialRenderer<TileEntityVault> {

    private static final double VIEW_RANGE = 40.0D;
    /** One full open/close run, in ticks. */
    private static final double CYCLE_TICKS = 80.0D;
    /** How far the leaf swings open from shut. */
    private static final double OPEN_ANGLE = Math.toRadians(108.0D);

    // ---- leaf plate (door space: a = width, b = height, c = depth, +c = front)
    private static final double P_A0 = 0.52D, P_A1 = 4.48D;
    private static final double P_B0 = 0.12D, P_B1 = 5.48D;
    private static final double P_C0 = 0.40D, P_C1 = 0.60D;

    // ---- handle wheel
    private static final double W_A = 2.90D, W_B = 3.00D;
    private static final double W_R_OUT = 0.62D, W_R_IN = 0.50D;
    private static final double W_C0 = 0.62D, W_C1 = 0.70D;
    private static final double HUB_R = 0.17D;
    private static final double HUB_C0 = 0.60D, HUB_C1 = 0.76D;
    private static final double KNOB_R = 0.085D, KNOB_LEN = 0.13D;
    private static final int RING_SEGS = 20, HUB_SEGS = 12, KNOB_SEGS = 8;

    // ---- lock bolts
    private static final double[] BOLT_B = {0.90D, 2.75D, 4.60D};
    private static final double BOLT_A = 4.04D;
    private static final double BOLT_C = 0.62D;
    private static final double BOLT_R = 0.055D;
    private static final double BOLT_IN = 0.14D, BOLT_OUT = 0.58D;
    private static final int BOLT_SEGS = 8;

    /** The door's basis: u along the leaf's width, n out of its face, y up. */
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

    private static final class V3 {
        final double x, y, z;
        V3(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** A door: the cached open state and the tick its last flip was seen. */
    private static final class DoorAnims {
        boolean wasOpen;
        boolean fresh = true;   // first sighting: snap, never replay
        double flipTime;
    }

    private final Map<Long, DoorAnims> anims = new HashMap<Long, DoorAnims>();

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
        boolean open = state.getValue(BlockVault.OPEN);

        double now = te.getWorld().getTotalWorldTime() + partialTicks;
        double openP = openProgress(pos, open, now);
        // Three staggered windows. The leaf and the wheel deliberately overlap
        // so that, for most of the run, both quantities move every single tick.
        double boltOut = 1.0D - phaseP(openP, 0.00D, 0.14D);
        double spinT = phaseP(openP, 0.10D, 0.90D);
        double leafT = phaseP(openP, 0.10D, 1.00D);
        double theta = OPEN_ANGLE * easeInOut(leafT);
        double spin = Math.PI * 2.0D * easeInOut(spinT);

        int argb = facilityArgb();
        float cr = ((argb >> 16) & 0xFF) / 255.0F;
        float cg = ((argb >> 8) & 0xFF) / 255.0F;
        float cb = (argb & 0xFF) / 255.0F;

        try {
            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            GlStateManager.enableTexture2D();
            mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.disableBlend();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

            Frame f = new Frame(facing);
            drawFrame(f);
            drawLeaf(f, theta);
            drawHingePosts(f, theta);
            drawBolts(f, theta, boltOut);
            drawWheel(f, theta, spin);
            drawStatusLight(f, cr, cg, cb, openP);

            GlStateManager.enableCull();
            GlStateManager.enableLighting();
            GlStateManager.popMatrix();
        } catch (Throwable throwable) {
            // A renderer that throws takes the client down with it; a door that
            // skips one frame does not.
            HBMZZ.logger.error("Vault door at " + pos + " failed to render", throwable);
        }
    }

    // ------------------------------------------------------------ animation

    /**
     * Anchors the boolean open state to the world clock and returns the
     * overall open progress (0 shut, 1 open). A door seen for the first time
     * snaps to its current state so nothing replays on chunk load.
     */
    private double openProgress(BlockPos pos, boolean open, double now) {
        Long key = pos.toLong();
        DoorAnims a = this.anims.get(key);
        if (a == null) {
            a = new DoorAnims();
            a.wasOpen = open;
            a.fresh = true;
            a.flipTime = now;
            this.anims.put(key, a);
            if (this.anims.size() > 512) {
                prune();
            }
            return open ? 1.0D : 0.0D;
        }
        if (a.fresh) {
            if (open != a.wasOpen) {
                a.wasOpen = open;
                a.flipTime = now;
                a.fresh = false;
            } else {
                return open ? 1.0D : 0.0D;
            }
        } else if (open != a.wasOpen) {
            a.wasOpen = open;
            a.flipTime = now;
        }
        double dt = now - a.flipTime;
        if (dt < 0.0D) {                 // world clock rewound (relog)
            a.flipTime = now;
            dt = 0.0D;
        }
        double p = a.wasOpen ? dt / CYCLE_TICKS : 1.0D - dt / CYCLE_TICKS;
        return Math.max(0.0D, Math.min(1.0D, p));
    }

    private void prune() {
        Iterator<Long> it = this.anims.keySet().iterator();
        while (it.hasNext() && this.anims.size() > 256) {
            it.next();
            it.remove();
        }
    }

    private static double phaseP(double p, double lo, double hi) {
        return Math.max(0.0D, Math.min(1.0D, (p - lo) / (hi - lo)));
    }

    private static double easeInOut(double t) {
        return 0.5D - 0.5D * Math.cos(Math.PI * t);
    }

    // ---------------------------------------------------------------- frame

    /** The static frame: jambs, lintel, sill. */
    private void drawFrame(Frame f) {
        TextureAtlasSprite frame = sprite("hbmzz:blocks/vault_frame");
        BufferBuilder buf = texBuf();
        boxTex(buf, f, 0.0D, 0.0D, 0.0D, 0.5D, 0.5D, 6.0D, 1.0D, frame, frame);     // left jamb
        boxTex(buf, f, 0.0D, 4.5D, 0.0D, 0.5D, 5.0D, 6.0D, 1.0D, frame, frame);     // right jamb
        boxTex(buf, f, 0.0D, 0.0D, 5.5D, 0.5D, 5.0D, 6.0D, 1.0D, frame, frame);     // lintel
        boxTex(buf, f, 0.0D, 0.5D, 0.0D, 0.5D, 4.5D, 0.10D, 1.0D, frame, frame);    // sill
        Tessellator.getInstance().draw();
    }

    /** Two hinge barrels on the leaf's left edge; they swing with the leaf. */
    private void drawHingePosts(Frame f, double theta) {
        TextureAtlasSprite frame = sprite("hbmzz:blocks/vault_frame");
        BufferBuilder buf = texBuf();
        boxTex(buf, f, theta, 0.52D, 0.35D, 0.603D, 0.72D, 0.90D, 0.68D, frame, frame);
        boxTex(buf, f, theta, 0.52D, 4.60D, 0.603D, 0.72D, 5.15D, 0.68D, frame, frame);
        Tessellator.getInstance().draw();
    }

    // -------------------------------------------------------------- the leaf

    /**
     * The swinging leaf: a 0.20-thick plate with a raised rim. The plate's
     * front face is tiled from the armour texture, the rest from the darker
     * side texture, and a frame-textured strip rims the front so it reads as a
     * forged door rather than a slab.
     */
    private void drawLeaf(Frame f, double theta) {
        TextureAtlasSprite body = sprite("hbmzz:blocks/vault_body");
        TextureAtlasSprite side = sprite("hbmzz:blocks/vault_body_side");
        TextureAtlasSprite rim = sprite("hbmzz:blocks/vault_frame");
        BufferBuilder buf = texBuf();
        boxTex(buf, f, theta, P_A0, P_B0, P_C0, P_A1, P_B1, P_C1, body, side);
        // Raised rim strips, a hair proud of the front face.
        double r = P_C1 + 0.014D;
        boxTex(buf, f, theta, P_A0, P_B0, r, P_A1, P_B0 + 0.09D, r + 0.02D, rim, rim);
        boxTex(buf, f, theta, P_A0, P_B1 - 0.09D, r, P_A1, P_B1, r + 0.02D, rim, rim);
        boxTex(buf, f, theta, P_A1 - 0.09D, P_B0, r, P_A1, P_B1, r + 0.02D, rim, rim);
        Tessellator.getInstance().draw();
    }

    // --------------------------------------------------------------- the bolts

    /**
     * Three lock bolts on the free edge. Each has a flange disc on the leaf
     * face and a shaft that slides along the leaf's width axis: retracted
     * ({@code out}=0) it is a short stub, extended ({@code out}=1) it reaches
     * into the jamb. The length is recomputed from the animation every frame.
     */
    private void drawBolts(Frame f, double theta, double out) {
        TextureAtlasSprite metal = sprite("hbmzz:blocks/vault_frame");
        BufferBuilder buf = texBuf();
        double len = BOLT_IN + (BOLT_OUT - BOLT_IN) * out;
        for (double b : BOLT_B) {
            // Flange disc on the front face.
            discC(buf, f, theta, BOLT_A - 0.03D, b, P_C1 + 0.02D, 0.14D, 10, metal);
            // Shaft: a solid cylinder running out along +a.
            cylA(buf, f, theta, BOLT_A, BOLT_A + len, b, BOLT_C, BOLT_R, BOLT_SEGS, metal);
        }
        Tessellator.getInstance().draw();
    }

    // -------------------------------------------------------------- the wheel

    /**
     * The handle wheel, drawn as real 3D: a ring band (outer + inner walls and
     * two annular faces), a hub cylinder, four spokes and four grip knobs on
     * the rim. {@code spin} rotates every one of those parts around the wheel's
     * axis, which is the leaf's normal.
     */
    private void drawWheel(Frame f, double theta, double spin) {
        TextureAtlasSprite metal = sprite("hbmzz:blocks/vault_frame");
        BufferBuilder buf = texBuf();
        // Ring: outer wall, inner wall, front annulus, back annulus.
        bandAB(buf, f, theta, W_A, W_B, W_R_OUT, W_C0, W_C1, spin, RING_SEGS, metal);
        bandAB(buf, f, theta, W_A, W_B, W_R_IN, W_C0, W_C1, spin, RING_SEGS, metal);
        annulusAB(buf, f, theta, W_A, W_B, W_C1, W_R_IN, W_R_OUT, spin, RING_SEGS, metal);
        annulusAB(buf, f, theta, W_A, W_B, W_C0, W_R_OUT, W_R_IN, spin, RING_SEGS, metal);
        // Hub.
        bandAB(buf, f, theta, W_A, W_B, HUB_R, HUB_C0, HUB_C1, spin, HUB_SEGS, metal);
        discC(buf, f, theta, W_A, W_B, HUB_C1, HUB_R, HUB_SEGS, metal);
        // Spokes + grip knobs.
        for (int i = 0; i < 4; i++) {
            double ang = spin + Math.PI * 0.5D * i;
            double dx = Math.cos(ang), dy = Math.sin(ang);
            spoke(buf, f, theta, W_A, W_B, ang, HUB_R - 0.02D, W_R_IN, 0.055D, W_C0, W_C1, metal);
            double ka = W_A + dx * (W_R_IN - KNOB_R * 0.6D);
            double kb = W_B + dy * (W_R_IN - KNOB_R * 0.6D);
            bandAB(buf, f, theta, ka, kb, KNOB_R, W_C1, W_C1 + KNOB_LEN, spin, KNOB_SEGS, metal);
            discC(buf, f, theta, ka, kb, W_C1 + KNOB_LEN, KNOB_R, KNOB_SEGS, metal);
        }
        Tessellator.getInstance().draw();
    }

    // --------------------------------------------------------- status light

    /** Bezel on the lintel plus an untextured emissive quad in the facility colour. */
    private void drawStatusLight(Frame f, float cr, float cg, float cb, double openP) {
        TextureAtlasSprite frame = sprite("hbmzz:blocks/vault_frame");
        BufferBuilder buf = texBuf();
        boxTex(buf, f, 0.0D, 2.10D, 5.56D, 0.98D, 2.90D, 5.94D, 1.04D, frame, frame);
        Tessellator.getInstance().draw();

        float lit = (float) openP;
        float r = 0.80F * (1.0F - lit) + cr * lit;
        float g = 0.80F * (1.0F - lit) + cg * lit;
        float b = 0.84F * (1.0F - lit) + cb * lit;
        GlStateManager.disableTexture2D();
        BufferBuilder cbuf = Tessellator.getInstance().getBuffer();
        cbuf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        cbuf.pos(2.18D, 5.62D, 1.046D).color(r, g, b, 1.0F).endVertex();
        cbuf.pos(2.82D, 5.62D, 1.046D).color(r, g, b, 1.0F).endVertex();
        cbuf.pos(2.82D, 5.88D, 1.046D).color(r, g, b, 1.0F).endVertex();
        cbuf.pos(2.18D, 5.88D, 1.046D).color(r, g, b, 1.0F).endVertex();
        Tessellator.getInstance().draw();
        GlStateManager.enableTexture2D();
    }

    // -------------------------------------------------------- quad primitives

    private static BufferBuilder texBuf() {
        BufferBuilder buf = Tessellator.getInstance().getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        return buf;
    }

    private static TextureAtlasSprite sprite(String name) {
        return Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(name);
    }

    /**
     * A leaf-space point mapped to block-local world coordinates. The hinge is
     * the vertical line at a=0.5, c=0.5; every point is swung around it by
     * {@code theta} (0 = shut, OPEN_ANGLE = fully open, folding into the wall).
     */
    private static V3 pt(Frame f, double theta, double a, double b, double c) {
        double la = a - 0.5D;
        double dc = c - 0.5D;
        double cos = Math.cos(theta), sin = Math.sin(theta);
        double ar = la * cos + dc * sin;
        double cr = -la * sin + dc * cos;
        return new V3(f.ux * (0.5D + ar) + f.nx * (0.5D + cr),
                b,
                f.uz * (0.5D + ar) + f.nz * (0.5D + cr));
    }

    /** One quad, mapped to a whole sprite tile. */
    private static void quad4(BufferBuilder buf, V3 p0, V3 p1, V3 p2, V3 p3,
                              TextureAtlasSprite sp) {
        float u0 = sp.getMinU(), u1 = sp.getMaxU();
        float v0 = sp.getMinV(), v1 = sp.getMaxV();
        buf.pos(p0.x, p0.y, p0.z).tex(u0, v1).endVertex();
        buf.pos(p1.x, p1.y, p1.z).tex(u1, v1).endVertex();
        buf.pos(p2.x, p2.y, p2.z).tex(u1, v0).endVertex();
        buf.pos(p3.x, p3.y, p3.z).tex(u0, v0).endVertex();
    }

    /** One quad with an explicit slice of the sprite (for ring textures). */
    private static void quad4uv(BufferBuilder buf, V3 p0, V3 p1, V3 p2, V3 p3,
                                float u0, float u1, float v0, float v1) {
        buf.pos(p0.x, p0.y, p0.z).tex(u0, v1).endVertex();
        buf.pos(p1.x, p1.y, p1.z).tex(u1, v1).endVertex();
        buf.pos(p2.x, p2.y, p2.z).tex(u1, v0).endVertex();
        buf.pos(p3.x, p3.y, p3.z).tex(u0, v0).endVertex();
    }

    /** A grid of nU x nV sprite tiles across the (a,b) rectangle at depth c. */
    private static void gridAB(BufferBuilder buf, Frame f, double theta,
                               double a0, double b0, double a1, double b1, double c,
                               int nU, int nV, TextureAtlasSprite sp) {
        for (int i = 0; i < nU; i++) {
            double ua = a0 + (a1 - a0) * i / nU;
            double ub = a0 + (a1 - a0) * (i + 1) / nU;
            for (int j = 0; j < nV; j++) {
                double vb = b0 + (b1 - b0) * j / nV;
                double vt = b0 + (b1 - b0) * (j + 1) / nV;
                quad4(buf,
                        pt(f, theta, ua, vb, c), pt(f, theta, ub, vb, c),
                        pt(f, theta, ub, vt, c), pt(f, theta, ua, vt, c), sp);
            }
        }
    }

    /** A grid across the (b,c) rectangle at fixed a. */
    private static void gridBC(BufferBuilder buf, Frame f, double theta, double a,
                               double b0, double c0, double b1, double c1,
                               int nU, int nV, TextureAtlasSprite sp) {
        for (int i = 0; i < nU; i++) {
            double ub = b0 + (b1 - b0) * i / nU;
            double ut = b0 + (b1 - b0) * (i + 1) / nU;
            for (int j = 0; j < nV; j++) {
                double va = c0 + (c1 - c0) * j / nV;
                double vb = c0 + (c1 - c0) * (j + 1) / nV;
                quad4(buf,
                        pt(f, theta, a, ub, va), pt(f, theta, a, ut, va),
                        pt(f, theta, a, ut, vb), pt(f, theta, a, ub, vb), sp);
            }
        }
    }

    /** A grid across the (a,c) rectangle at fixed b. */
    private static void gridAC(BufferBuilder buf, Frame f, double theta, double b,
                               double a0, double c0, double a1, double c1,
                               int nU, int nV, TextureAtlasSprite sp) {
        for (int i = 0; i < nU; i++) {
            double ua = a0 + (a1 - a0) * i / nU;
            double ub = a0 + (a1 - a0) * (i + 1) / nU;
            for (int j = 0; j < nV; j++) {
                double va = c0 + (c1 - c0) * j / nV;
                double vb = c0 + (c1 - c0) * (j + 1) / nV;
                quad4(buf,
                        pt(f, theta, ua, b, va), pt(f, theta, ub, b, va),
                        pt(f, theta, ub, b, vb), pt(f, theta, ua, b, vb), sp);
            }
        }
    }

    /**
     * A solid box in leaf space. The face pointing at +c uses {@code front},
     * every other face uses {@code other}; both are tiled about one sprite per
     * block so nothing stretches.
     */
    private static void boxTex(BufferBuilder buf, Frame f, double theta,
                               double a0, double b0, double c0,
                               double a1, double b1, double c1,
                               TextureAtlasSprite front, TextureAtlasSprite other) {
        int na = Math.max(1, (int) Math.round(a1 - a0));
        int nb = Math.max(1, (int) Math.round(b1 - b0));
        int nc = Math.max(1, (int) Math.round(c1 - c0));
        gridAB(buf, f, theta, a0, b0, a1, b1, c1, na, nb, front);   // front (+c)
        gridAB(buf, f, theta, a0, b0, a1, b1, c0, na, nb, other);   // back (-c)
        gridBC(buf, f, theta, a0, b0, c0, b1, c1, nb, nc, other);   // left (-a)
        gridBC(buf, f, theta, a1, b0, c0, b1, c1, nb, nc, other);   // right (+a)
        gridAC(buf, f, theta, b0, a0, c0, a1, c1, na, nc, other);   // bottom (-b)
        gridAC(buf, f, theta, b1, a0, c0, a1, c1, na, nc, other);   // top (+b)
    }

    /**
     * A cylinder wall around the wheel axis: a circle of radius {@code r} in
     * the (a,b) plane, swept from depth c0 to c1, starting at angle
     * {@code spin}. Winding is irrelevant (culling is off).
     */
    private static void bandAB(BufferBuilder buf, Frame f, double theta,
                               double ca, double cb, double r,
                               double c0, double c1, double spin, int segs,
                               TextureAtlasSprite sp) {
        float su = sp.getMaxU() - sp.getMinU();
        float sv = sp.getMaxV() - sp.getMinV();
        for (int i = 0; i < segs; i++) {
            double a0 = spin + Math.PI * 2.0D * i / segs;
            double a1 = spin + Math.PI * 2.0D * (i + 1) / segs;
            double xa = ca + r * Math.cos(a0), ya = cb + r * Math.sin(a0);
            double xb = ca + r * Math.cos(a1), yb = cb + r * Math.sin(a1);
            quad4uv(buf,
                    pt(f, theta, xa, ya, c0), pt(f, theta, xb, yb, c0),
                    pt(f, theta, xb, yb, c1), pt(f, theta, xa, ya, c1),
                    sp.getMinU() + su * i / segs, sp.getMinU() + su * (i + 1) / segs,
                    sp.getMinV(), sp.getMinV() + sv);
        }
    }

    /** An annulus in the (a,b) plane at depth c, between radii r0 and r1. */
    private static void annulusAB(BufferBuilder buf, Frame f, double theta,
                                  double ca, double cb, double c, double r0, double r1,
                                  double spin, int segs, TextureAtlasSprite sp) {
        float su = sp.getMaxU() - sp.getMinU();
        float sv = sp.getMaxV() - sp.getMinV();
        for (int i = 0; i < segs; i++) {
            double a0 = spin + Math.PI * 2.0D * i / segs;
            double a1 = spin + Math.PI * 2.0D * (i + 1) / segs;
            double cos0 = Math.cos(a0), sin0 = Math.sin(a0);
            double cos1 = Math.cos(a1), sin1 = Math.sin(a1);
            quad4uv(buf,
                    pt(f, theta, ca + r0 * cos0, cb + r0 * sin0, c),
                    pt(f, theta, ca + r1 * cos0, cb + r1 * sin0, c),
                    pt(f, theta, ca + r1 * cos1, cb + r1 * sin1, c),
                    pt(f, theta, ca + r0 * cos1, cb + r0 * sin1, c),
                    sp.getMinU() + su * i / segs, sp.getMinU() + su * (i + 1) / segs,
                    sp.getMinV(), sp.getMinV() + sv);
        }
    }

    /** A flat disc (fan of degenerate quads) at depth c around (ca, cb). */
    private static void discC(BufferBuilder buf, Frame f, double theta,
                              double ca, double cb, double c, double r, int segs,
                              TextureAtlasSprite sp) {
        V3 centre = pt(f, theta, ca, cb, c);
        for (int i = 0; i < segs; i++) {
            double a0 = Math.PI * 2.0D * i / segs;
            double a1 = Math.PI * 2.0D * (i + 1) / segs;
            V3 p0 = pt(f, theta, ca + r * Math.cos(a0), cb + r * Math.sin(a0), c);
            V3 p1 = pt(f, theta, ca + r * Math.cos(a1), cb + r * Math.sin(a1), c);
            float u0 = sp.getMinU(), u1 = sp.getMaxU();
            float v0 = sp.getMinV(), v1 = sp.getMaxV();
            float mu = (u0 + u1) * 0.5F, mv = (v0 + v1) * 0.5F;
            buf.pos(centre.x, centre.y, centre.z).tex(mu, mv).endVertex();
            buf.pos(p0.x, p0.y, p0.z).tex(u0, v0).endVertex();
            buf.pos(p1.x, p1.y, p1.z).tex(u1, v1).endVertex();
            buf.pos(p1.x, p1.y, p1.z).tex(u1, v1).endVertex();
        }
    }

    /**
     * A cylinder wall around the +a axis: a circle in the (b,c) plane at
     * (cb, cc), swept from a0 to a1, closed with an end disc at a1.
     */
    private static void cylA(BufferBuilder buf, Frame f, double theta,
                             double a0, double a1, double cb, double cc,
                             double r, int segs, TextureAtlasSprite sp) {
        for (int i = 0; i < segs; i++) {
            double t0 = Math.PI * 2.0D * i / segs;
            double t1 = Math.PI * 2.0D * (i + 1) / segs;
            double b0 = cb + r * Math.cos(t0), c0 = cc + r * Math.sin(t0);
            double b1 = cb + r * Math.cos(t1), c1 = cc + r * Math.sin(t1);
            quad4(buf,
                    pt(f, theta, a0, b0, c0), pt(f, theta, a0, b1, c1),
                    pt(f, theta, a1, b1, c1), pt(f, theta, a1, b0, c0), sp);
        }
        // End cap.
        V3 centre = pt(f, theta, a1, cb, cc);
        for (int i = 0; i < segs; i++) {
            double t0 = Math.PI * 2.0D * i / segs;
            double t1 = Math.PI * 2.0D * (i + 1) / segs;
            V3 p0 = pt(f, theta, a1, cb + r * Math.cos(t0), cc + r * Math.sin(t0));
            V3 p1 = pt(f, theta, a1, cb + r * Math.cos(t1), cc + r * Math.sin(t1));
            float u0 = sp.getMinU(), u1 = sp.getMaxU();
            float v0 = sp.getMinV(), v1 = sp.getMaxV();
            buf.pos(centre.x, centre.y, centre.z).tex((u0 + u1) * 0.5F, (v0 + v1) * 0.5F).endVertex();
            buf.pos(p0.x, p0.y, p0.z).tex(u0, v0).endVertex();
            buf.pos(p1.x, p1.y, p1.z).tex(u1, v1).endVertex();
            buf.pos(p1.x, p1.y, p1.z).tex(u1, v1).endVertex();
        }
    }

    /**
     * One wheel spoke: a rectangular bar in the (a,b) plane, running from
     * radius r0 to r1 at angle {@code ang}, half-width {@code hw} across, and
     * {@code c0}..{@code c1} thick.
     */
    private static void spoke(BufferBuilder buf, Frame f, double theta,
                              double ca, double cb, double ang,
                              double r0, double r1, double hw,
                              double c0, double c1, TextureAtlasSprite sp) {
        double dx = Math.cos(ang), dy = Math.sin(ang);
        double px = -dy, py = dx;
        // Four corners of the bar in the plane.
        double[] ra = {r0, r0, r1, r1};
        double[] wa = {-hw, hw, hw, -hw};
        V3[] lo = new V3[4];
        V3[] hi = new V3[4];
        for (int i = 0; i < 4; i++) {
            double a = ca + dx * ra[i] + px * wa[i];
            double b = cb + dy * ra[i] + py * wa[i];
            lo[i] = pt(f, theta, a, b, c0);
            hi[i] = pt(f, theta, a, b, c1);
        }
        // Two flat faces plus the four sides.
        quad4(buf, lo[0], lo[1], lo[2], lo[3], sp);
        quad4(buf, hi[3], hi[2], hi[1], hi[0], sp);
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            quad4(buf, lo[i], hi[i], hi[j], lo[j], sp);
        }
    }

    /** The facility's colour, custom-aware - the same source the lamp reads. */
    private static int facilityArgb() {
        if (ClientAlertState.isCustom()) {
            return 0xFF000000 | (ClientAlertState.customRgb() & 0xFFFFFF);
        }
        return AlertCode.byId(ClientAlertState.code()).argb();
    }
}

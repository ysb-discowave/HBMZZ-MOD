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

import com.hbmzz.dfc.ConsoleRig;

import java.util.ArrayList;
import java.util.List;

/**
 * The console as geometry, with no OpenGL in sight.
 *
 * <p>This exists because the renderer used to be the only description of the
 * machine, which meant the only way to find out whether a bracket was in the
 * wrong place was to launch the game and walk up to it. Building faces first
 * and drawing them second lets an offline tool project the exact same quads to
 * a PNG - see {@code tools/RigPreview.java}. If the picture looks wrong, the
 * machine is wrong, and wrong in the same way.</p>
 *
 * <h3>Local frame</h3>
 * <p>Origin at the <b>geometric centre of the panel</b>, {@code -z} facing out.
 * A panel {@code cols} wide by {@code rows} tall occupies
 * {@code x in [-cols/2, +cols/2]} and {@code y in [-rows/2, +rows/2]}.</p>
 *
 * <p>Centre symmetry is load-bearing, not tidiness. Authoring from
 * {@code x = 0} upwards (which is what this class did until now) puts the whole
 * machine half a panel to the viewer's right once the renderer's origin sits at
 * the block centre: a 4-wide screen drew itself two blocks outside its own 4x3
 * footprint, and the cells it claimed rendered as air. Every rotation by 90
 * degrees also moved it somewhere new, so three of the four facings were wrong
 * in different ways.</p>
 *
 * <h3>Two frames that move differently</h3>
 * <ul>
 *   <li><b>The housing</b> pivots about its top edge ({@code y = +rows/2}) and
 *       is then pushed forward by {@link ConsoleRig#push}.</li>
 *   <li><b>The hanger</b> lives entirely above that pivot, reaches into the
 *       ceiling block, and takes the same forward push without rotating. Since
 *       all of it is above the hinge line it can never intersect the panel at
 *       any tilt.</li>
 * </ul>
 */
public final class ConsoleModel {

    // ---------------------------------------------------------------- palette
    //
    // Cast aluminium and amber. The amber is the same phosphor the on-screen
    // interface uses, so the hardware and the software read as one product.

    private static final int C_HULL = 0xFF23272B;
    private static final int C_HULL_HI = 0xFF353C42;
    private static final int C_FRAME = 0xFF464E55;
    private static final int C_FRAME_LIP = 0xFF14171A;
    private static final int C_BOLT = 0xFF88929B;
    private static final int C_BOLT_HI = 0xFFB9C2CA;
    private static final int C_RIG = 0xFF1B1F22;
    private static final int C_RIG_HI = 0xFF525B63;
    private static final int C_PIP_ON = 0xFFFFB03A;
    private static final int C_PIP_OFF = 0xFF38312A;

    private static final float T = ConsoleRig.THICKNESS;
    private static final float BEZEL = ConsoleRig.BEZEL;
    private static final float GAP = ConsoleRig.GAP;

    /** One flat quad, wound the way the vanilla model boxes are. */
    public static final class Face {
        public final float[] xs = new float[4];
        public final float[] ys = new float[4];
        public final float[] zs = new float[4];
        public final int argb;

        Face(float[] x, float[] y, float[] z, int argb) {
            System.arraycopy(x, 0, this.xs, 0, 4);
            System.arraycopy(y, 0, this.ys, 0, 4);
            System.arraycopy(z, 0, this.zs, 0, 4);
            this.argb = argb;
        }
    }

    private ConsoleModel() {
    }

    /**
     * Builds every face of the machine in the panel's local frame, already
     * tipped and pushed, so the caller only has to place the frame at the
     * panel's centre and rotate it to face the right way.
     */
    public static List<Face> build(int cols, int rows, int tilt, boolean powered, boolean ceiling) {
        List<Face> faces = new ArrayList<>();
        float tiltDegrees = ConsoleRig.tiltDegrees(tilt, rows);
        float push = ConsoleRig.push(tilt, rows);

        buildHousing(faces, cols, rows, powered, tiltDegrees, push);
        if (ceiling) {
            buildHanger(faces, cols, rows, powered, push);
        }
        return faces;
    }

    // ----------------------------------------------------------------- hanger

    /**
     * Ceiling mount: clamp bar, two hinge bosses, two drop rods, one flange.
     *
     * <p>Everything sits at {@code y >= rows/2 - 0.08}, i.e. at or above the
     * hinge line. The part above {@code rows/2} is inside the ceiling block,
     * which is exactly where a real flange goes - the visible part is the clamp
     * biting the top frame and the rods disappearing upwards.</p>
     *
     * <p>It is not rotated with the panel. It is bolted to the building; only
     * the forward push applies, because the whole assembly moved.</p>
     */
    private static void buildHanger(List<Face> out, int cols, int rows, boolean powered, float push) {
        float hw = cols * 0.5F;
        float hh = rows * 0.5F;
        // Half-width of the clamp bar. A 4-wide panel gets the full 1.35; a
        // 1-wide one still needs something to grip, hence the floor.
        float cw = Math.max(0.18F, hw - 0.65F);
        float fw = Math.max(cw + 0.16F, hw - 0.50F);
        float rw = Math.min(Math.min(1.0F, hw * 0.5F), fw - 0.08F);
        if (rw < 0.06F) {
            rw = cw * 0.5F;
        }

        // Clamp bar across the top edge: the part the player actually sees.
        hangBox(out, -cw, hh - 0.08F, -0.22F, cw, hh + 0.10F, 0.30F, C_RIG_HI, push);
        // Hinge bosses just outside it, so the thing reads as adjustable.
        hangBox(out, -cw - 0.13F, hh - 0.06F, -0.16F, -cw - 0.03F, hh + 0.20F, 0.22F, C_RIG, push);
        hangBox(out, cw + 0.03F, hh - 0.06F, -0.16F, cw + 0.13F, hh + 0.20F, 0.22F, C_RIG, push);

        // Two drop rods running up into the ceiling.
        hangBox(out, -rw - 0.06F, hh + 0.08F, -0.09F, -rw + 0.06F, hh + 0.46F, 0.09F, C_RIG_HI, push);
        hangBox(out, rw - 0.06F, hh + 0.08F, -0.09F, rw + 0.06F, hh + 0.46F, 0.09F, C_RIG_HI, push);

        // Flange, sunk into the ceiling block above.
        hangBox(out, -fw, hh + 0.36F, -0.28F, fw, hh + 0.46F, 0.28F, C_RIG, push);
        hangBox(out, -fw, hh + 0.44F, -0.28F, fw, hh + 0.46F, 0.28F, C_RIG_HI, push);
        // Exposed bolt heads under the flange.
        hangBox(out, -cw - 0.02F, hh + 0.28F, -0.09F, -cw + 0.10F, hh + 0.36F, 0.09F, C_BOLT, push);
        hangBox(out, cw - 0.10F, hh + 0.28F, -0.09F, cw + 0.02F, hh + 0.36F, 0.09F, C_BOLT, push);

        // Two pips on the clamp: the mount is live even when the panel is dark.
        int pip = powered ? C_PIP_ON : C_PIP_OFF;
        hangBox(out, -0.30F, hh - 0.02F, -0.24F, -0.16F, hh + 0.02F, -0.20F, pip, push);
        hangBox(out, 0.16F, hh - 0.02F, -0.24F, 0.30F, hh + 0.02F, -0.20F, pip, push);
    }

    // ---------------------------------------------------------------- housing

    /**
     * Housing and front frame, in the panel's own frame before the tip.
     *
     * <p>Proportions are those of the hardware this is modelled on: a
     * {@code 0.075} half-thickness, a {@code 0.10} bezel on a 4x3 panel, and a
     * {@code 0.012} margin so neighbouring consoles do not z-fight. The glass
     * sits just behind the front frame rather than deeply recessed - a recess
     * this shallow reads as a shadow line, a deep one reads as a picture
     * frame.</p>
     */
    private static void buildHousing(List<Face> out, int cols, int rows, boolean powered,
                                     float tiltDegrees, float push) {
        float hw = cols * 0.5F;
        float hh = rows * 0.5F;
        float x0 = -hw + GAP;
        float x1 = hw - GAP;
        float y0 = -hh + GAP;
        float y1 = hh - GAP;

        // Rear plate, slightly smaller than the frame so the panel reads as
        // inset rather than glued on.
        shellBox(out, x0 + 0.02F, y0 + 0.02F, T, x1 - 0.02F, y1 - 0.02F, T + 0.05F,
                C_HULL, rows, tiltDegrees, push);

        float zf = -T - 0.010F;   // frontmost plane of the bezel
        float zb = T;             // back plane of the bezel

        // Four frame bands.
        shellBox(out, x0, y0, zf, x1, y0 + BEZEL, zb, C_FRAME, rows, tiltDegrees, push);
        shellBox(out, x0, y1 - BEZEL, zf, x1, y1, zb, C_FRAME, rows, tiltDegrees, push);
        shellBox(out, x0, y0 + BEZEL, zf, x0 + BEZEL, y1 - BEZEL, zb, C_FRAME, rows, tiltDegrees, push);
        shellBox(out, x1 - BEZEL, y0 + BEZEL, zf, x1, y1 - BEZEL, zb, C_FRAME, rows, tiltDegrees, push);

        // Shadow line: a thin dark band just inside the opening, so the glass
        // looks seated in the housing instead of floating in front of it.
        shellBox(out, x0 + BEZEL - 0.014F, y0 + BEZEL - 0.014F, -T + 0.004F,
                x1 - BEZEL + 0.014F, y0 + BEZEL + 0.010F, -T + 0.011F,
                C_FRAME_LIP, rows, tiltDegrees, push);
        shellBox(out, x0 + BEZEL - 0.014F, y1 - BEZEL - 0.010F, -T + 0.004F,
                x1 - BEZEL + 0.014F, y1 - BEZEL + 0.014F, -T + 0.011F,
                C_FRAME_LIP, rows, tiltDegrees, push);
        shellBox(out, x0 + BEZEL - 0.014F, y0 + BEZEL + 0.010F, -T + 0.004F,
                x0 + BEZEL + 0.010F, y1 - BEZEL - 0.010F, -T + 0.011F,
                C_FRAME_LIP, rows, tiltDegrees, push);
        shellBox(out, x1 - BEZEL - 0.010F, y0 + BEZEL + 0.010F, -T + 0.004F,
                x1 - BEZEL + 0.014F, y1 - BEZEL - 0.010F, -T + 0.011F,
                C_FRAME_LIP, rows, tiltDegrees, push);

        // Corner bolts, inside the bezel so they never reach the picture.
        float b = Math.min(0.030F, BEZEL * 0.30F);
        bolt(out, x0 + BEZEL * 0.5F, y0 + BEZEL * 0.5F, zf, b, rows, tiltDegrees, push);
        bolt(out, x1 - BEZEL * 0.5F, y0 + BEZEL * 0.5F, zf, b, rows, tiltDegrees, push);
        bolt(out, x0 + BEZEL * 0.5F, y1 - BEZEL * 0.5F, zf, b, rows, tiltDegrees, push);
        bolt(out, x1 - BEZEL * 0.5F, y1 - BEZEL * 0.5F, zf, b, rows, tiltDegrees, push);

        // Status strip on the bottom rail: the machine says whether it is on
        // even before you can read the glass.
        float sw = Math.min(0.22F, hw * 0.22F);
        shellBox(out, -sw, y0 + BEZEL * 0.34F, zf - 0.006F, sw, y0 + BEZEL * 0.66F, zf + 0.002F,
                powered ? C_PIP_ON : C_PIP_OFF, rows, tiltDegrees, push);
    }

    // ------------------------------------------------------------------ panel

    /**
     * The four corners of the glass, in the same local frame as the faces, so
     * the renderer can texture it and a preview can fill it without either one
     * re-deriving the geometry.
     */
    public static float[][] panelCorners(int cols, int rows, int tilt) {
        float tiltDegrees = ConsoleRig.tiltDegrees(tilt, rows);
        float push = ConsoleRig.push(tilt, rows);
        float hw = cols * 0.5F;
        float hh = rows * 0.5F;
        float z = -T - 0.003F;
        float x0 = -hw + GAP + BEZEL;
        float x1 = hw - GAP - BEZEL;
        float y0 = -hh + GAP + BEZEL;
        float y1 = hh - GAP - BEZEL;

        // Counter-clockwise seen from the front: (x1,y0) (x0,y0) (x0,y1) (x1,y1).
        float[][] corners = new float[4][];
        corners[0] = place(x1, y0, z, rows, tiltDegrees, push);
        corners[1] = place(x0, y0, z, rows, tiltDegrees, push);
        corners[2] = place(x0, y1, z, rows, tiltDegrees, push);
        corners[3] = place(x1, y1, z, rows, tiltDegrees, push);
        return corners;
    }

    /** Plane the glass sits on, in the tipped frame. Used by the preview tool. */
    public static float glassZ() {
        return -T - 0.003F;
    }

    // -------------------------------------------------------------- transform

    /**
     * Rotates a point about the hinge line {@code y = rows/2} by {@code -tilt}
     * and then slides the assembly forward by {@code push}.
     *
     * <p>The negative angle is the difference between a screen that hangs and a
     * screen that stares at the ceiling: local -z is the front, so a positive
     * rotation lifts the normal upwards and the player standing underneath sees
     * the back shell.</p>
     */
    private static float[] place(float x, float y, float z, int rows,
                                 float tiltDegrees, float push) {
        float hh = rows * 0.5F;
        float dy = y - hh;
        double rad = Math.toRadians(-tiltDegrees);
        float c = (float) Math.cos(rad);
        float s = (float) Math.sin(rad);
        return new float[]{x, hh + dy * c - z * s, dy * s + z * c - push};
    }

    /** A box of the housing: rotated about the hinge, then pushed forward. */
    private static void shellBox(List<Face> out, float x0, float y0, float z0,
                                 float x1, float y1, float z1, int argb,
                                 int rows, float tiltDegrees, float push) {
        box(out, x0, y0, z0, x1, y1, z1, argb,
                p -> place(p[0], p[1], p[2], rows, tiltDegrees, push));
    }

    /** A box of the hanger: above the hinge, so it only takes the push. */
    private static void hangBox(List<Face> out, float x0, float y0, float z0,
                                float x1, float y1, float z1, int argb, float push) {
        box(out, x0, y0, z0, x1, y1, z1, argb,
                p -> new float[]{p[0], p[1], p[2] - push});
    }

    private static void bolt(List<Face> out, float cx, float cy, float z, float r,
                             int rows, float tiltDegrees, float push) {
        shellBox(out, cx - r, cy - r, z - 0.010F, cx + r, cy + r, z, C_BOLT,
                rows, tiltDegrees, push);
        shellBox(out, cx - r * 0.45F, cy - r * 0.45F, z - 0.014F, cx + r * 0.45F, cy + r * 0.45F,
                z - 0.010F, C_BOLT_HI, rows, tiltDegrees, push);
    }

    // ------------------------------------------------------------------ boxes

    private interface Transform {
        float[] apply(float[] p);
    }

    /** Emits the six faces of an axis-aligned box, transforming every corner. */
    private static void box(List<Face> out, float x0, float y0, float z0,
                            float x1, float y1, float z1, int argb, Transform t) {
        float[] a = t.apply(new float[]{x0, y0, z0});
        float[] b = t.apply(new float[]{x1, y0, z0});
        float[] c = t.apply(new float[]{x1, y1, z0});
        float[] d = t.apply(new float[]{x0, y1, z0});
        float[] e = t.apply(new float[]{x0, y0, z1});
        float[] f = t.apply(new float[]{x1, y0, z1});
        float[] g = t.apply(new float[]{x1, y1, z1});
        float[] h = t.apply(new float[]{x0, y1, z1});

        // -Z
        quad(out, d, c, b, a, argb);
        // +Z
        quad(out, e, f, g, h, argb);
        // -X
        quad(out, a, e, h, d, argb);
        // +X
        quad(out, f, b, c, g, argb);
        // -Y
        quad(out, a, b, f, e, argb);
        // +Y
        quad(out, h, g, c, d, argb);
    }

    private static void quad(List<Face> out, float[] p0, float[] p1, float[] p2, float[] p3, int argb) {
        out.add(new Face(
                new float[]{p0[0], p1[0], p2[0], p3[0]},
                new float[]{p0[1], p1[1], p2[1], p3[1]},
                new float[]{p0[2], p1[2], p2[2], p3[2]},
                argb));
    }
}

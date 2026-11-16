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
 * The monitor's rendering was originally modelled on JiezhiMod by Shushang de
 * Jiezhi; that part has since been rewritten here (2026-11-16) and this file no
 * longer carries their code. Historical attribution stays in NOTICE.md.
 */

package com.hbmzz.client;

import com.hbmzz.dfc.ConsoleRig;

import java.util.ArrayList;
import java.util.List;

/**
 * The console as a list of boxes, with no OpenGL in sight.
 *
 * <h3>How this is put together</h3>
 * <p>The machine is described in two steps. First a list of {@link Box} parts is
 * collected — the rear plate, four bezel bands, four shadow lines, four corner
 * bolts, the status strip, and (if it hangs from a ceiling) the clamp, bosses,
 * rods, flange and pips. Then one pass transforms every part and emits its six
 * quads.</p>
 *
 * <p>That split is the point: parts are described in the machine's own upright
 * frame, and the tilt is applied exactly once, to everything, by
 * {@link #emit}. A box therefore never carries its own trigonometry, and adding
 * a part cannot accidentally tilt it twice or forget to push it.</p>
 *
 * <h3>Two mounts, one transform</h3>
 * <ul>
 *   <li>{@link Mount#HINGED} parts rotate about the top edge ({@code y = +rows/2})
 *       and then travel forward with the rest of the assembly.</li>
 *   <li>{@link Mount#RIGID} parts (the ceiling hanger) only travel forward. They
 *       live entirely at or above the hinge line, so no rotation can push them
 *       through the panel.</li>
 * </ul>
 *
 * <h3>Local frame</h3>
 * <p>Origin at the geometric centre of the panel, {@code -z} facing out; the
 * panel spans {@code x in [-cols/2, +cols/2]}, {@code y in [-rows/2, +rows/2]}.
 * Centre symmetry is what makes all four facings agree with the cells the block
 * claims — see {@link ConsoleRig}.</p>
 */
public final class ConsoleModel {

    // ---------------------------------------------------------------- palette
    //
    // Cast aluminium and amber. The amber is the same phosphor the on-screen
    // interface uses, so hardware and software read as one product.

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

    /** Which way a part follows the assembly when the panel tips. */
    private enum Mount {
        /** Bolted to the panel: rotates about the hinge, then travels forward. */
        HINGED,
        /** Bolted to the building: travels forward only. */
        RIGID
    }

    /** An axis-aligned box in the machine's upright frame, plus its colour. */
    private static final class Box {
        final float x0, y0, z0, x1, y1, z1;
        final int argb;
        final Mount mount;

        Box(float x0, float y0, float z0, float x1, float y1, float z1, int argb, Mount mount) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
            this.argb = argb;
            this.mount = mount;
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
        List<Box> parts = new ArrayList<>();
        collectHousing(parts, cols, rows, powered);
        if (ceiling) {
            collectHanger(parts, cols, rows, powered);
        }
        return emit(parts, rows, tilt);
    }

    // ------------------------------------------------------------------ parts

    /**
     * Housing and front frame: a shallow rear plate, four bezel bands, four
     * shadow lines inside the opening, four corner bolts sitting in the bezel,
     * and a status strip on the bottom rail.
     *
     * <p>Proportions are the hardware's: a 0.075 half-thickness, a 0.10 bezel on
     * a 4x3 panel, and a 0.012 margin so neighbouring consoles do not z-fight.
     * The glass sits just behind the front frame rather than deeply recessed — a
     * shallow recess reads as a shadow line, a deep one reads as a picture
     * frame.</p>
     */
    private static void collectHousing(List<Box> parts, int cols, int rows, boolean powered) {
        float hw = cols * 0.5F;
        float hh = rows * 0.5F;
        float x0 = -hw + GAP;
        float x1 = hw - GAP;
        float y0 = -hh + GAP;
        float y1 = hh - GAP;

        float zFront = -T - 0.010F;   // frontmost plane of the bezel
        float zBack = T;              // back plane of the bezel

        // Rear plate, inset a little so the panel reads as mounted rather than glued on.
        hinged(parts, x0 + 0.02F, y0 + 0.02F, T, x1 - 0.02F, y1 - 0.02F, T + 0.05F, C_HULL);

        // Bezel: bottom, top, left, right.
        hinged(parts, x0, y0, zFront, x1, y0 + BEZEL, zBack, C_FRAME);
        hinged(parts, x0, y1 - BEZEL, zFront, x1, y1, zBack, C_FRAME);
        hinged(parts, x0, y0 + BEZEL, zFront, x0 + BEZEL, y1 - BEZEL, zBack, C_FRAME);
        hinged(parts, x1 - BEZEL, y0 + BEZEL, zFront, x1, y1 - BEZEL, zBack, C_FRAME);

        // Shadow line just inside the opening: the glass looks seated, not floating.
        float lipIn = BEZEL - 0.014F;
        float lipOut = BEZEL + 0.014F;
        float lipZ0 = -T + 0.004F;
        float lipZ1 = -T + 0.011F;
        hinged(parts, x0 + lipIn, y0 + BEZEL - 0.014F, lipZ0, x1 - lipIn, y0 + BEZEL + 0.010F, lipZ1, C_FRAME_LIP);
        hinged(parts, x0 + lipIn, y1 - BEZEL - 0.010F, lipZ0, x1 - lipIn, y1 - BEZEL + 0.014F, lipZ1, C_FRAME_LIP);
        hinged(parts, x0 + lipIn, y0 + BEZEL + 0.010F, lipZ0, x0 + BEZEL + 0.010F, y1 - BEZEL - 0.010F, lipZ1, C_FRAME_LIP);
        hinged(parts, x1 - BEZEL - 0.010F, y0 + BEZEL + 0.010F, lipZ0, x1 - lipIn, y1 - BEZEL - 0.010F, lipZ1, C_FRAME_LIP);

        // Corner bolts, held inside the bezel so they never reach the picture.
        float r = Math.min(0.030F, BEZEL * 0.30F);
        float bx0 = x0 + BEZEL * 0.5F;
        float bx1 = x1 - BEZEL * 0.5F;
        float by0 = y0 + BEZEL * 0.5F;
        float by1 = y1 - BEZEL * 0.5F;
        bolt(parts, bx0, by0, zFront, r);
        bolt(parts, bx1, by0, zFront, r);
        bolt(parts, bx0, by1, zFront, r);
        bolt(parts, bx1, by1, zFront, r);

        // Status strip on the bottom rail: the machine says whether it is on
        // before anybody can read the glass.
        float sw = Math.min(0.22F, hw * 0.22F);
        hinged(parts, -sw, y0 + BEZEL * 0.34F, zFront - 0.006F,
                sw, y0 + BEZEL * 0.66F, zFront + 0.002F,
                powered ? C_PIP_ON : C_PIP_OFF);
    }

    /**
     * Ceiling mount: clamp bar, two hinge bosses, two drop rods, flange, bolt
     * heads, and two pips. Everything is at or above the hinge line, so the
     * panel can never tilt into it; nothing rotates, because it is bolted to the
     * building and only travels forward with the assembly.
     */
    private static void collectHanger(List<Box> parts, int cols, int rows, boolean powered) {
        float hw = cols * 0.5F;
        float hh = rows * 0.5F;
        // Half-width of the clamp. A wide panel gets the full span; a 1-wide one
        // still needs something to grip, hence the floor.
        float clampHalf = Math.max(0.18F, hw - 0.65F);
        float flangeHalf = Math.max(clampHalf + 0.16F, hw - 0.50F);
        float rodHalf = Math.min(Math.min(1.0F, hw * 0.5F), flangeHalf - 0.08F);
        if (rodHalf < 0.06F) {
            rodHalf = clampHalf * 0.5F;
        }

        // Clamp bar across the top edge: the part the player actually sees.
        rigid(parts, -clampHalf, hh - 0.08F, -0.22F, clampHalf, hh + 0.10F, 0.30F, C_RIG_HI);
        // Hinge bosses just outside it, so the thing reads as adjustable.
        rigid(parts, -clampHalf - 0.13F, hh - 0.06F, -0.16F, -clampHalf - 0.03F, hh + 0.20F, 0.22F, C_RIG);
        rigid(parts, clampHalf + 0.03F, hh - 0.06F, -0.16F, clampHalf + 0.13F, hh + 0.20F, 0.22F, C_RIG);
        // Two drop rods running up into the ceiling.
        rigid(parts, -rodHalf - 0.06F, hh + 0.08F, -0.09F, -rodHalf + 0.06F, hh + 0.46F, 0.09F, C_RIG_HI);
        rigid(parts, rodHalf - 0.06F, hh + 0.08F, -0.09F, rodHalf + 0.06F, hh + 0.46F, 0.09F, C_RIG_HI);
        // Flange sunk into the ceiling block, with a lighter cap on top.
        rigid(parts, -flangeHalf, hh + 0.36F, -0.28F, flangeHalf, hh + 0.46F, 0.28F, C_RIG);
        rigid(parts, -flangeHalf, hh + 0.44F, -0.28F, flangeHalf, hh + 0.46F, 0.28F, C_RIG_HI);
        // Exposed bolt heads under the flange.
        rigid(parts, -clampHalf - 0.02F, hh + 0.28F, -0.09F, -clampHalf + 0.10F, hh + 0.36F, 0.09F, C_BOLT);
        rigid(parts, clampHalf - 0.10F, hh + 0.28F, -0.09F, clampHalf + 0.02F, hh + 0.36F, 0.09F, C_BOLT);
        // Two pips on the clamp: the mount is live even while the panel is dark.
        int pip = powered ? C_PIP_ON : C_PIP_OFF;
        rigid(parts, -0.30F, hh - 0.02F, -0.24F, -0.16F, hh + 0.02F, -0.20F, pip);
        rigid(parts, 0.16F, hh - 0.02F, -0.24F, 0.30F, hh + 0.02F, -0.20F, pip);
    }

    private static void hinged(List<Box> parts, float x0, float y0, float z0,
                               float x1, float y1, float z1, int argb) {
        parts.add(new Box(x0, y0, z0, x1, y1, z1, argb, Mount.HINGED));
    }

    private static void rigid(List<Box> parts, float x0, float y0, float z0,
                              float x1, float y1, float z1, int argb) {
        parts.add(new Box(x0, y0, z0, x1, y1, z1, argb, Mount.RIGID));
    }

    /** A bolt head: a square boss with a brighter cap standing proud of it. */
    private static void bolt(List<Box> parts, float cx, float cy, float z, float r) {
        hinged(parts, cx - r, cy - r, z - 0.010F, cx + r, cy + r, z, C_BOLT);
        hinged(parts, cx - r * 0.45F, cy - r * 0.45F, z - 0.014F,
                cx + r * 0.45F, cy + r * 0.45F, z - 0.010F, C_BOLT_HI);
    }

    // ------------------------------------------------------------------ panel

    /**
     * The four corners of the glass, in the same local frame as the faces, so
     * the renderer can texture it and a preview can fill it without either one
     * re-deriving the geometry.
     */
    public static float[][] panelCorners(int cols, int rows, int tilt) {
        float hw = cols * 0.5F;
        float hh = rows * 0.5F;
        float z = glassZ();
        float x0 = -hw + GAP + BEZEL;
        float x1 = hw - GAP - BEZEL;
        float y0 = -hh + GAP + BEZEL;
        float y1 = hh - GAP - BEZEL;

        Transform hinged = Transform.hinged(rows, tilt);
        // Counter-clockwise seen from the front: (x1,y0) (x0,y0) (x0,y1) (x1,y1).
        return new float[][]{
                hinged.at(x1, y0, z),
                hinged.at(x0, y0, z),
                hinged.at(x0, y1, z),
                hinged.at(x1, y1, z)};
    }

    /** Plane the glass sits on, in the tipped frame. Used by the preview tool. */
    public static float glassZ() {
        return -T - 0.003F;
    }

    // ------------------------------------------------------------- transform

    /**
     * The assembly's transform, computed once per build.
     *
     * <p>Local -z is the front, so a positive tilt has to rotate <em>against</em>
     * the angle: otherwise the normal climbs and a player standing underneath
     * sees the back shell. The forward travel equals the centre's backward
     * travel ({@code hh·sin t}), which is what keeps the centre on the mounting
     * plane and the bottom edge inside the cells the panel already owns.</p>
     */
    private static final class Transform {
        final float hingeY;
        final float cos;
        final float sin;
        final float push;

        private Transform(float hingeY, float degrees, float push) {
            this.hingeY = hingeY;
            double rad = Math.toRadians(-degrees);
            this.cos = (float) Math.cos(rad);
            this.sin = (float) Math.sin(rad);
            this.push = push;
        }

        static Transform hinged(int rows, int tilt) {
            float hh = rows * 0.5F;
            return new Transform(hh, ConsoleRig.tiltDegrees(tilt, rows), ConsoleRig.push(tilt, rows));
        }

        static Transform rigid(float hingeY, float push) {
            return new Transform(hingeY, 0.0F, push);
        }

        float[] at(float x, float y, float z) {
            float dy = y - hingeY;
            return new float[]{x, hingeY + dy * cos - z * sin, dy * sin + z * cos - push};
        }
    }

    /**
     * Emits six quads per box.
     *
     * <p>The corner table is indexed, not spelled out: the eight box corners are
     * numbered once, and each of the six faces names the four corners to walk in
     * the order that leaves the normal pointing outwards. Spelling out six quads
     * by hand is where winding mistakes live — a face wound the wrong way is
     * invisible from the front and looks like a hole.</p>
     */
    private static List<Face> emit(List<Box> parts, int rows, int tilt) {
        List<Face> out = new ArrayList<>(parts.size() * 6);
        Transform hinged = Transform.hinged(rows, tilt);
        Transform rigid = Transform.rigid(rows * 0.5F, ConsoleRig.push(tilt, rows));
        for (Box b : parts) {
            Transform t = b.mount == Mount.HINGED ? hinged : rigid;
            float[][] c = {
                    t.at(b.x0, b.y0, b.z0), t.at(b.x1, b.y0, b.z0),
                    t.at(b.x1, b.y1, b.z0), t.at(b.x0, b.y1, b.z0),
                    t.at(b.x0, b.y0, b.z1), t.at(b.x1, b.y0, b.z1),
                    t.at(b.x1, b.y1, b.z1), t.at(b.x0, b.y1, b.z1)};
            for (int[] order : FACES) {
                out.add(quad(c, order, b.argb));
            }
        }
        return out;
    }

    /**
     * The six faces as corner indices, each walked so its normal points out of
     * the box: -Z, +Z, -X, +X, -Y, +Y.
     */
    private static final int[][] FACES = {
            {3, 2, 1, 0},   // -Z
            {4, 5, 6, 7},   // +Z
            {0, 4, 7, 3},   // -X
            {5, 1, 2, 6},   // +X
            {0, 1, 5, 4},   // -Y
            {7, 6, 2, 3}};  // +Y

    private static Face quad(float[][] c, int[] order, int argb) {
        return new Face(
                new float[]{c[order[0]][0], c[order[1]][0], c[order[2]][0], c[order[3]][0]},
                new float[]{c[order[0]][1], c[order[1]][1], c[order[2]][1], c[order[3]][1]},
                new float[]{c[order[0]][2], c[order[1]][2], c[order[2]][2], c[order[3]][2]},
                argb);
    }
}

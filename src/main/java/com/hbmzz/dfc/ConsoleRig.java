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

package com.hbmzz.dfc;

import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/**
 * Geometry of the console: where the panel sits, how far it tips, and which
 * cells of the wall it occupies.
 *
 * <h3>The one rule this class exists to enforce</h3>
 * <p>The renderer's local origin is the <b>geometric centre of the panel</b>,
 * not a corner of any block. That is not a stylistic choice: a panel whose
 * origin sits at a corner covers {@code 0..cols} in one direction only, and
 * the moment you rotate it by 90 or 180 degrees to face another direction the
 * whole interval swings into negative space and the picture lands a block away
 * from the blocks it belongs to. Centre-symmetric coordinates
 * ({@code x in [-cols/2, +cols/2]}) rotate onto themselves, so all four
 * facings agree with the cells by construction.</p>
 *
 * <p>This is the bug that made a 4-wide screen draw two blocks to the right of
 * its own footprint: the model was authored from {@code x = 0} upwards while
 * the renderer had already moved the origin to the block centre. The cells the
 * machine occupied were air, and the console floated beside them.</p>
 *
 * <h3>Why the panel tips about its top edge</h3>
 * <p>A hung console swings from a hinge on its top edge, the way the real
 * hardware it is modelled on does. Tipping by {@code t} walks the panel's
 * centre backwards by {@code HH*sin(t)}, so the whole assembly is pushed
 * forward by exactly that much - which leaves the centre on the mounting
 * plane, the top edge {@code HH*sin} in front of it, and the bottom edge the
 * same distance behind it. Push by any more than that and the console leaves
 * its own cells entirely.</p>
 */
public final class ConsoleRig {

    /** Tilt notches: 0, 15, 30, 45, 60 degrees. */
    public static final int TILT_STEPS = 5;
    public static final int TILT_STEP_DEG = 15;

    /** Hung from a ceiling: the panel looks down at whoever stands below. */
    public static final int TILT_CEILING = 2;
    /** Bolted flat to a wall: no tip at all. */
    public static final int TILT_WALL = 0;

    /**
     * Half thickness of the housing, in blocks.
     *
     * <p>Deliberately thin. This is a display, not a cabinet: the front frame
     * reaches {@code -T-0.010} and the rear plate {@code T+0.05}, so the whole
     * machine is about a fifth of a block deep. Making it thicker does not read
     * as "sturdier", it reads as a box - which is what the block model already
     * looked like.</p>
     */
    public static final float THICKNESS = 0.075F;
    /** Width of the front frame, in blocks. */
    public static final float BEZEL = 0.10F;
    /**
     * Margin kept between the housing and the cell edges.
     *
     * <p>Without it the frame is coplanar with the neighbouring block faces and
     * the two z-fight: a wall of consoles shimmers along every seam.</p>
     */
    public static final float GAP = 0.012F;

    private ConsoleRig() {
    }

    // ------------------------------------------------------------------ tilt

    public static int clampTilt(int tilt) {
        if (tilt < 0) {
            return 0;
        }
        return tilt > TILT_STEPS - 1 ? TILT_STEPS - 1 : tilt;
    }

    /**
     * Deepest notch this panel height can take.
     *
     * <p>Every notch is available: because the assembly is pushed forward by
     * exactly the centre's travel, tipping never moves the panel into the wall
     * behind it - it only walks the bottom edge further back through the cells
     * the panel already owns. A hung console has open air behind it, so the
     * full 60 degrees is usable and the player gets the whole range.</p>
     */
    public static int maxNotch(int rows) {
        return TILT_STEPS - 1;
    }

    public static int clampNotch(int notch, int rows) {
        return clampTilt(notch);
    }

    public static float tiltDegrees(int notch, int rows) {
        return clampTilt(notch) * (float) TILT_STEP_DEG;
    }

    /** Advances one notch, wrapping back to flat at the top of the range. */
    public static int nextNotch(int notch, int rows) {
        int next = clampTilt(notch) + 1;
        return next > TILT_STEPS - 1 ? 0 : next;
    }

    /**
     * Placement pitch to notch.
     *
     * <p>Minecraft's {@code rotationPitch} is negative when you look up, so
     * somebody aiming at a ceiling to hang a screen lands on a steep notch and
     * somebody placing it at eye level lands on 30 degrees.</p>
     */
    public static int notchFromPitch(float pitch, int rows) {
        return clampTilt(Math.round((pitch + 30.0F) / (float) TILT_STEP_DEG));
    }

    /**
     * Unit normal of the panel face after tipping. A negative y component means
     * the panel looks at the floor. Flip the sign of the rotation and it stares
     * at the ceiling instead, where nobody below can read a thing.
     */
    public static float[] tiltNormal(int notch, int rows) {
        double rad = Math.toRadians(tiltDegrees(notch, rows));
        return new float[]{0.0F, (float) -Math.sin(rad), (float) -Math.cos(rad)};
    }

    // ------------------------------------------------------------------ size

    public static float halfWidth(int cols) {
        return cols * 0.5F;
    }

    public static float halfHeight(int rows) {
        return rows * 0.5F;
    }

    /**
     * How far the assembly is pushed forward along -z.
     *
     * <p>Equality with the centre's backward travel is the whole trick: after
     * the tip, the panel's centre returns to {@code z = 0}, the hinge stays at
     * the top edge, and the bottom edge ends up {@code HH*sin} behind the
     * mounting plane - still inside the cells the panel occupies.</p>
     */
    public static float push(int notch, int rows) {
        double rad = Math.toRadians(tiltDegrees(notch, rows));
        return (float) (halfHeight(rows) * Math.sin(rad));
    }

    // ------------------------------------------------------------------ cells

    /**
     * The viewer's right hand side when standing in front of the panel. This is
     * also the renderer's local +x, which is why column numbers grow along it.
     */
    public static EnumFacing rightOf(EnumFacing facing) {
        if (facing == null || facing.getAxis() == EnumFacing.Axis.Y) {
            return EnumFacing.EAST;
        }
        return facing.rotateY();
    }

    /** Yaw that turns the renderer's local -z into {@code facing}. */
    public static float yawOf(EnumFacing facing) {
        if (facing == null) {
            return 0.0F;
        }
        switch (facing) {
            case SOUTH:
                return 180.0F;
            case EAST:
                return 270.0F;
            case WEST:
                return 90.0F;
            case NORTH:
            default:
                return 0.0F;
        }
    }

    /** World position of cell (col, row) for a monitor whose controller is at origin. */
    public static BlockPos partPos(BlockPos origin, int col, int row, EnumFacing facing) {
        if (origin == null) {
            return null;
        }
        return origin.offset(rightOf(facing), col).down(row);
    }

    /** True when {@code target} is one of the cells this panel spans. */
    public static boolean covers(BlockPos origin, int cols, int rows, EnumFacing facing, BlockPos target) {
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                BlockPos p = partPos(origin, col, row, facing);
                if (p != null && p.equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ render

    /**
     * Offset from the controller block's <b>minimum corner</b> (which is what
     * the tile entity renderer is handed) to the geometric centre of the whole
     * panel.
     *
     * <p>The controller is the panel's top-left cell, so the centre is half a
     * panel to the viewer's right and half a panel down from that cell's
     * centre. Getting this wrong in either axis shifts the machine off its own
     * blocks, and the blocks then render as air.</p>
     */
    public static double[] anchor(int cols, int rows, EnumFacing facing) {
        EnumFacing right = rightOf(facing);
        return new double[]{
                0.5D + right.getFrontOffsetX() * (cols - 1) / 2.0D,
                0.5D - (rows - 1) / 2.0D,
                0.5D + right.getFrontOffsetZ() * (cols - 1) / 2.0D};
    }

    /**
     * UV of the panel quad. Returns {u@x0, u@x1, v@y0, v@y1}.
     *
     * <p>u runs backwards. The observer stands outside the face looking along
     * +z, so their right vector is -x: local +x is their <em>left</em>. Image
     * column 0 has to land on the viewer's left, so u=0 pairs with x1.</p>
     */
    public static float[] panelUv() {
        return new float[]{1.0F, 0.0F, 1.0F, 0.0F};
    }
}

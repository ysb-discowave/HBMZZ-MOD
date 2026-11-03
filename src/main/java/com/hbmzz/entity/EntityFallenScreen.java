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

package com.hbmzz.entity;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The screen after somebody breaks it off its mount.
 *
 * <p>It is not a dropped item and not a falling block: the harness stays bolted
 * to the ceiling, a single cable keeps feeding it, and the panel swings under
 * that cable showing a dead channel before the feed is finally cut. Modelling
 * it as an entity is the only way to get that - the blocks are gone, so there
 * is no tile entity left to draw with, and a block cannot swing.</p>
 *
 * <p>The entity's origin is the <b>hook</b>, not the panel. Everything else -
 * cable, panel, swing - hangs below it, which is exactly the geometry the
 * renderer needs and keeps the pendulum maths in one place.</p>
 */
public class EntityFallenScreen extends Entity {

    /** 30 seconds at 20 ticks a second, then the feed is cut for good. */
    public static final int LIFETIME_TICKS = 600;

    /** One second of twitching after the feed dies, before it winks out. */
    public static final int FADE_TICKS = 20;

    private int cols = 2;
    private int rows = 2;
    private int tilt;
    private float hangYaw;

    public EntityFallenScreen(World world) {
        super(world);
        // No physics, no collisions: this is scenery hanging in the air.
        this.noClip = true;
        this.preventEntitySpawning = false;
        setSize(1.0F, 1.0F);
    }

    public EntityFallenScreen(World world, BlockPos origin, EnumFacing facing,
                              int cols, int rows, int tilt, float hangYaw) {
        this(world);
        this.cols = Math.max(1, cols);
        this.rows = Math.max(1, rows);
        this.tilt = tilt;
        this.hangYaw = hangYaw;
        // The hook sits at the panel's top edge, dead centre. anchor() is the
        // controller corner -> panel centre offset, so undoing half the panel
        // height lands on the top edge.
        double[] anchor = com.hbmzz.dfc.ConsoleRig.anchor(this.cols, this.rows, facing);
        setPosition(origin.getX() + anchor[0],
                origin.getY() + anchor[1] + this.rows * 0.5D,
                origin.getZ() + anchor[2]);
    }

    @Override
    protected void entityInit() {
    }

    @Override
    public void onUpdate() {
        // Deliberately no gravity: the panel is on a cable, not in free fall.
        super.onUpdate();
        if (!this.world.isRemote && this.ticksExisted > LIFETIME_TICKS + FADE_TICKS) {
            setDead();
        }
    }

    // ------------------------------------------------------------- animation

    /**
     * Pendulum angle in degrees, positive swinging to the panel's own right.
     *
     * <p>Damped harmonic motion rather than a sine: the panel is knocked, not
     * wound up, so the swing has to die away. The time constant is long enough
     * that it is still visibly drifting at the end of its life - a screen that
     * stops moving after four seconds reads as frozen, not as hanging.</p>
     */
    public double swingDegrees(float partialTicks) {
        double t = this.ticksExisted + partialTicks;
        return 26.0D * Math.exp(-t / 260.0D) * Math.cos(t * 0.205D);
    }

    /**
     * Length of the cable, in blocks. Snaps out over the first dozen ticks so
     * the fall reads as a jerk on the cable rather than a smooth lowering.
     */
    public double wireLength(float partialTicks) {
        double t = this.ticksExisted + partialTicks;
        double p = Math.min(1.0D, t / 14.0D);
        p = 1.0D - (1.0D - p) * (1.0D - p);
        return 0.10D + 1.05D * p;
    }

    /** 1 while the feed is live, sliding to 0 in the last second. */
    public float visibility(float partialTicks) {
        double left = LIFETIME_TICKS + FADE_TICKS - (this.ticksExisted + partialTicks);
        if (left >= FADE_TICKS) {
            return 1.0F;
        }
        return (float) Math.max(0.0D, left / FADE_TICKS);
    }

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public int getTilt() {
        return tilt;
    }

    public float getHangYaw() {
        return hangYaw;
    }

    // ------------------------------------------------------------------ save

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        cols = Math.max(1, compound.getInteger("cols"));
        rows = Math.max(1, compound.getInteger("rows"));
        tilt = compound.getInteger("tilt");
        hangYaw = compound.getFloat("yaw");
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setInteger("cols", cols);
        compound.setInteger("rows", rows);
        compound.setInteger("tilt", tilt);
        compound.setFloat("yaw", hangYaw);
    }

    // ------------------------------------------------------------- behaviour

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    /** Scenery: never let the game think it is buried in a block. */
    @Override
    public boolean isEntityInsideOpaqueBlock() {
        return false;
    }

    /** Self-lit: the panel is supposed to read in the dark. */
    @Override
    public int getBrightnessForRender() {
        return 0xF000F0;
    }

    /**
     * The volume the renderer actually paints.
     *
     * <p>Default is a one-block box at the entity's position - and the entity
     * sits at the <b>hook</b>, while the panel hangs a cable-length and half a
     * panel further down. Left alone, the whole thing is culled the moment the
     * hook leaves the frustum, which for a hanging panel is most of the time
     * you are looking at it. Same class of bug as drawing nothing at all, just
     * intermittent.</p>
     */
    @Override
    public net.minecraft.util.math.AxisAlignedBB getRenderBoundingBox() {
        double down = wireLength(0.0F) + rows * 0.5D + 1.0D;
        double wide = Math.max(2.0D, cols * 0.5D + 1.0D);
        return new net.minecraft.util.math.AxisAlignedBB(
                this.posX - wide, this.posY - down, this.posZ - wide,
                this.posX + wide, this.posY + 0.5D, this.posZ + wide);
    }

    @Override
    public boolean shouldRenderInPass(int pass) {
        return pass == 0;
    }
}

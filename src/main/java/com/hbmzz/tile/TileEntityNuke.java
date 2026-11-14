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

package com.hbmzz.tile;

import com.hbmzz.alert.NukeService;
import com.hbmzz.block.BlockNuke;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The bomb.
 *
 * <p>Holds three things: how big it is, how long its fuse is, and where it is in
 * the sequence. Nothing else - the "only one per world" rule lives in
 * {@link com.hbmzz.alert.NukeRegistry}, because it is a property of the save
 * rather than of the block, and the blast itself lives in
 * {@link NukeService}, because the controller has to be able to start the same
 * sequence from a different dimension.</p>
 *
 * <h3>Why the fuse is counted here and not scheduled</h3>
 * <p>{@code World.scheduleBlockUpdate} is dropped when the chunk unloads, so a
 * bomb primed at the edge of a player's view radius would simply never go off -
 * which reads as a broken block, not a lucky escape. A counter in the tile
 * entity survives as long as the chunk is loaded and stops (rather than resets)
 * while it is not, and the tile entity is exactly what the monitor reads the
 * countdown from.</p>
 */
public class TileEntityNuke extends TileEntity implements ITickable {

    /** Placed, waiting. */
    public static final int IDLE = 0;
    /** Armed, counting down. */
    public static final int COUNTDOWN = 1;
    /** Went off; the block is already air by the time this is set. */
    public static final int SPENT = 2;

    /** Yield in kilotons. Copied from the config when the block is placed. */
    private int yieldKt = 100;
    /** Fuse length in ticks. Also copied at placement. */
    private int fuseTicks = 100;
    private int state = IDLE;
    private int countdown;

    /** The controller that primed it, so the monitor can draw the data path. */
    private BlockPos controllerPos;
    private int controllerDim;

    private int ambientTimer;

    public int getYield() {
        return this.yieldKt;
    }

    public void setYield(int yieldKt) {
        this.yieldKt = Math.max(1, Math.min(10000, yieldKt));
        markDirty();
    }

    public int getFuseTicks() {
        return this.fuseTicks;
    }

    public void setFuseTicks(int fuseTicks) {
        this.fuseTicks = Math.max(0, Math.min(12000, fuseTicks));
        markDirty();
    }

    public int getState() {
        return this.state;
    }

    public int getCountdown() {
        return this.countdown;
    }

    public BlockPos getControllerPos() {
        return this.controllerPos;
    }

    public int getControllerDim() {
        return this.controllerDim;
    }

    public void setController(BlockPos pos, int dim) {
        this.controllerPos = pos;
        this.controllerDim = dim;
        markDirty();
        sync();
    }

    public boolean isCountingDown() {
        return this.state == COUNTDOWN;
    }

    /**
     * Starts the fuse.
     *
     * @return false when the device is spent or already counting - the caller
     *         uses that to say "it is already running" instead of starting a
     *         second, overlapping countdown that would reset the first one
     */
    public boolean prime() {
        if (this.world == null || this.world.isRemote) {
            return false;
        }
        if (this.state != IDLE) {
            return false;
        }
        this.state = COUNTDOWN;
        this.countdown = Math.max(0, this.fuseTicks);
        setPrimed(true);
        markDirty();
        sync();
        if (this.countdown <= 0) {
            NukeService.detonate(this);
            return true;
        }
        return true;
    }

    /** Stops a running countdown. The device goes back to idle, not to spent. */
    public boolean abort() {
        if (this.state != COUNTDOWN) {
            return false;
        }
        this.state = IDLE;
        this.countdown = 0;
        setPrimed(false);
        markDirty();
        sync();
        return true;
    }

    /**
     * Records the detonation without touching the world.
     *
     * <p>Called by {@link NukeService} immediately before the block is removed, so
     * that anything still holding a reference - a monitor reading this tile
     * entity on the same tick - sees "spent" rather than "idle".</p>
     */
    public void markSpent() {
        this.state = SPENT;
        this.countdown = 0;
        markDirty();
    }

    private void setPrimed(boolean primed) {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        IBlockState current = this.world.getBlockState(this.pos);
        if (current.getBlock() instanceof BlockNuke
                && current.getValue(BlockNuke.PRIMED) != primed) {
            this.world.setBlockState(this.pos, current.withProperty(BlockNuke.PRIMED, primed), 3);
        }
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (this.state == COUNTDOWN) {
            if (--this.countdown <= 0) {
                NukeService.detonate(this);
                // detonate() removed the block, so this tile entity is invalid
                // from here on; touching any field would be writing to a corpse.
                return;
            }
            tickCountdown();
            return;
        }
        if (this.state == IDLE && (this.world.getTotalWorldTime() & 63L) == 0L) {
            // A single idle lamp pulse: enough to find the thing in the dark, not
            // enough to be a light source. Server side only, so it costs nothing
            // on a client that is not looking.
            this.world.spawnParticle(EnumParticleTypes.REDSTONE,
                    this.pos.getX() + 0.5D, this.pos.getY() + 0.9D, this.pos.getZ() + 0.5D,
                    0.0D, 0.0D, 0.0D, 1, 0, 0);
        }
    }

    private void tickCountdown() {
        if (this.countdown % 10 != 0) {
            return;
        }
        this.ambientTimer++;
        double x = this.pos.getX() + 0.5D;
        double y = this.pos.getY() + 0.9D;
        double z = this.pos.getZ() + 0.5D;
        // Urgency is the only thing the fuse has to communicate from a distance.
        double spread = 0.25D + 0.5D * (1.0D - (double) this.countdown / Math.max(1, this.fuseTicks));
        for (int i = 0; i < 3; i++) {
            this.world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL,
                    x + (this.world.rand.nextDouble() - 0.5D) * spread,
                    y + this.world.rand.nextDouble() * 0.4D,
                    z + (this.world.rand.nextDouble() - 0.5D) * spread,
                    0.0D, 0.02D, 0.0D);
        }
        this.world.playSound(null, this.pos, SoundEvents.BLOCK_NOTE_HAT, SoundCategory.BLOCKS,
                0.6F, 0.9F + (this.countdown % 20 == 0 ? 0.3F : 0.0F));
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("yield", this.yieldKt);
        compound.setInteger("fuse", this.fuseTicks);
        compound.setByte("state", (byte) this.state);
        compound.setInteger("countdown", this.countdown);
        if (this.controllerPos != null) {
            compound.setLong("ctrlPos", this.controllerPos.toLong());
            compound.setInteger("ctrlDim", this.controllerDim);
        }
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        // A save written before this block existed, or a block placed by a
        // command, still has to produce a working bomb: the defaults are the
        // configured ones rather than zero.
        this.yieldKt = compound.hasKey("yield") ? compound.getInteger("yield")
                : com.hbmzz.HBMZZConfig.nukeYieldKt;
        this.fuseTicks = compound.hasKey("fuse") ? compound.getInteger("fuse")
                : com.hbmzz.HBMZZConfig.nukeFuseTicks;
        this.state = compound.getByte("state");
        this.countdown = compound.getInteger("countdown");
        this.controllerPos = compound.hasKey("ctrlPos") ? BlockPos.fromLong(compound.getLong("ctrlPos")) : null;
        this.controllerDim = compound.getInteger("ctrlDim");
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 3, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

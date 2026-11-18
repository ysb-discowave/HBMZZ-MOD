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

import com.hbmzz.HBMZZ;
import com.hbmzz.alert.NukeService;
import com.hbmzz.block.BlockNukeController;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The thing with the button on the wall.
 *
 * <p>It owns exactly one decision: a rising edge on its redstone input means
 * "prime the device I am bound to". It does not contain the blast, does not know
 * the yield, and does not count the fuse - all of that belongs to the bomb, and
 * duplicating any of it here would give the monitor two conflicting numbers to
 * choose from.</p>
 *
 * <h3>The edge, and why it is polled</h3>
 * <p>{@code Block.neighborChanged} is the obvious hook and the wrong one: it
 * fires for every neighbouring block update including the ones a comparator
 * clock produces on the same tick as a signal removal, and it is not called at
 * all when the block is loaded into an already-powered position. Polling every
 * other tick gives one edge, on every tick the state actually changes, which is
 * also what a latching detonator is supposed to do - a signal held high does not
 * fire twice.</p>
 */
public class TileEntityNukeController extends TileEntity implements ITickable {

    private BlockPos nukePos;
    private int nukeDim;
    private boolean lastPowered;

    /** Last thing that happened, for the player who right-clicks the box. */
    public static final int RESULT_NONE = 0;
    public static final int RESULT_FIRED = 1;
    public static final int RESULT_NO_DEVICE = 2;
    public static final int RESULT_NO_CHUNK = 3;
    private int lastResult = RESULT_NONE;
    private long lastFireTick;

    public BlockPos getNukePos() {
        return this.nukePos;
    }

    public int getNukeDim() {
        return this.nukeDim;
    }

    public boolean hasNuke() {
        return this.nukePos != null;
    }

    public int getLastResult() {
        return this.lastResult;
    }

    public long getLastFireTick() {
        return this.lastFireTick;
    }

    /** Binds this controller to a device and tells the device who owns it. */
    public void bind(BlockPos pos, int dim) {
        this.nukePos = pos;
        this.nukeDim = dim;
        TileEntityNuke device = NukeService.find(this.world, pos, dim);
        if (device != null) {
            device.setController(this.pos, this.world == null ? 0 : this.world.provider.getDimension());
        }
        markDirty();
        sync();
    }

    public void unbind() {
        this.nukePos = null;
        this.nukeDim = 0;
        markDirty();
        sync();
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if ((this.world.getTotalWorldTime() & 1L) != 0L) {
            return;
        }
        boolean powered = this.world.isBlockPowered(this.pos);
        if (powered && !this.lastPowered) {
            fire();
        }
        this.lastPowered = powered;
        setPoweredState(powered);
    }

    private void fire() {
        this.lastFireTick = this.world.getTotalWorldTime();
        if (this.nukePos == null) {
            this.lastResult = RESULT_NO_DEVICE;
            return;
        }
        TileEntityNuke device = NukeService.find(this.world, this.nukePos, this.nukeDim);
        if (device == null) {
            // Distinguish "the bomb was destroyed" from "the bomb is in a chunk
            // nobody has loaded": the first is final, the second is a problem the
            // operator can fix by walking over there.
            boolean loaded = isTargetLoaded();
            this.lastResult = loaded ? RESULT_NO_DEVICE : RESULT_NO_CHUNK;
            if (!loaded) {
                HBMZZ.logger.warn("Nuke controller at {} could not reach its device at {} (chunk not loaded)",
                        this.pos, this.nukePos);
            }
            return;
        }
        boolean primed = NukeService.prime(this.world, device, "核弹信号控制器", "");
        this.lastResult = primed ? RESULT_FIRED : RESULT_NONE;
    }

    private boolean isTargetLoaded() {
        if (this.nukePos == null || this.world == null) {
            return false;
        }
        World target = this.world.getMinecraftServer() == null ? this.world
                : this.world.getMinecraftServer().getWorld(this.nukeDim);
        return target != null && target.isBlockLoaded(this.nukePos);
    }

    private void setPoweredState(boolean powered) {
        IBlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockNukeController
                && state.getValue(BlockNukeController.POWERED) != powered) {
            this.world.setBlockState(this.pos, state.withProperty(BlockNukeController.POWERED, powered), 3);
        }
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
        if (this.nukePos != null) {
            compound.setLong("nukePos", this.nukePos.toLong());
            compound.setInteger("nukeDim", this.nukeDim);
        }
        compound.setByte("lastResult", (byte) this.lastResult);
        compound.setLong("lastFire", this.lastFireTick);
        compound.setBoolean("lastPowered", this.lastPowered);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.nukePos = compound.hasKey("nukePos") ? BlockPos.fromLong(compound.getLong("nukePos")) : null;
        this.nukeDim = compound.getInteger("nukeDim");
        this.lastResult = compound.getByte("lastResult");
        this.lastFireTick = compound.getLong("lastFire");
        // A controller that was powered when the world was saved must not fire on
        // load: the bomb is going to be primed by the next rising edge, and an
        // unasked-for detonation caused by a save/load cycle is a bug report that
        // no player will ever describe correctly.
        this.lastPowered = compound.getBoolean("lastPowered");
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

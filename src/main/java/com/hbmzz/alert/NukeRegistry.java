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

package com.hbmzz.alert;

import com.hbmzz.block.BlockNuke;
import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

/**
 * The one bomb.
 *
 * <p>"One per world" is a rule about the save, not about the player's
 * inventory, so it cannot live in an item and it cannot be a scan of loaded
 * chunks - a bomb in an unloaded chunk would be invisible to the scan and the
 * player could quietly place a second one. It is a claim recorded here, on the
 * overworld's map storage, and it is what both the block and the item consult
 * before allowing a placement.</p>
 *
 * <h3>The claim has to be able to die</h3>
 * <p>{@code Block.breakBlock} is not called when a block is destroyed by an
 * explosion - vanilla goes through {@code onBlockExploded} straight to
 * {@code setBlockToAir}. A nuke destroyed by its own blast, or by someone else's,
 * would therefore leave a claim pointing at air, and from then on no nuke could
 * ever be placed again. So the claim is validated against the world whenever it
 * is consulted: if the recorded position no longer holds a nuke, the claim is
 * dropped on the spot. Chunks that are not loaded are respected rather than
 * guessed at - an unloaded bomb is still a bomb, and answering "free" there is
 * how a save ends up with two.</p>
 */
public final class NukeRegistry extends WorldSavedData {

    public static final String NAME = "hbmzz_nuke";

    private boolean claimed;
    private BlockPos pos = BlockPos.ORIGIN;
    private int dim;

    public NukeRegistry(String name) {
        super(name);
    }

    public NukeRegistry() {
        this(NAME);
    }

    public static NukeRegistry get(World world) {
        if (world == null || world.isRemote) {
            return null;
        }
        MinecraftServer server = world.getMinecraftServer();
        World storageWorld = server != null && server.getWorld(0) != null ? server.getWorld(0) : world;
        MapStorage storage = storageWorld.getMapStorage();
        if (storage == null) {
            return null;
        }
        WorldSavedData data = storage.getOrLoadData(NukeRegistry.class, NAME);
        if (!(data instanceof NukeRegistry)) {
            NukeRegistry fresh = new NukeRegistry(NAME);
            storage.setData(NAME, fresh);
            return fresh;
        }
        return (NukeRegistry) data;
    }

    public boolean claimed() {
        return this.claimed;
    }

    public BlockPos pos() {
        return this.pos;
    }

    public int dim() {
        return this.dim;
    }

    /**
     * True when a nuke already exists somewhere that is not {@code candidate}.
     *
     * <p>Side effect with a purpose: a claim that has gone stale is dropped here,
     * because this is the only moment anybody looks. It is the cheap version of a
     * "validate every tick" sweep, and it cannot run at a worse time than "the
     * player is trying to place the bomb".</p>
     */
    public boolean blocks(World world, BlockPos candidate) {
        if (!this.claimed) {
            return false;
        }
        if (this.pos.equals(candidate)) {
            return false;
        }
        if (stillThere(world)) {
            return true;
        }
        release();
        return false;
    }

    /** Drops the claim if the recorded block is gone; returns whether it survived. */
    private boolean stillThere(World world) {
        World target = worldOf(world);
        if (target == null || !target.isBlockLoaded(this.pos)) {
            // Cannot tell, so assume it is there. Refusing a second bomb is
            // recoverable; a duplicate is not.
            return true;
        }
        Block block = target.getBlockState(this.pos).getBlock();
        return block instanceof BlockNuke;
    }

    private World worldOf(World fallback) {
        if (fallback == null) {
            return null;
        }
        MinecraftServer server = fallback.getMinecraftServer();
        if (server == null) {
            return null;
        }
        return server.getWorld(this.dim);
    }

    /** Claims the slot, or returns false when another bomb already holds it. */
    public boolean claim(BlockPos at, int dim) {
        if (this.claimed && !this.pos.equals(at)) {
            return false;
        }
        this.claimed = true;
        this.pos = at;
        this.dim = dim;
        markDirty();
        return true;
    }

    /** Called when the bomb block is broken or detonated. */
    public void release() {
        this.claimed = false;
        markDirty();
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        this.claimed = nbt.getBoolean("claimed");
        this.pos = nbt.hasKey("pos") ? BlockPos.fromLong(nbt.getLong("pos")) : BlockPos.ORIGIN;
        this.dim = nbt.getInteger("dim");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        nbt.setBoolean("claimed", this.claimed);
        nbt.setLong("pos", this.pos.toLong());
        nbt.setInteger("dim", this.dim);
        return nbt;
    }
}

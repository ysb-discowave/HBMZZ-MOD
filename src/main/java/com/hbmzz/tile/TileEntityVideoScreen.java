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

import com.hbmzz.HBMZZConfig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The memory of one video screen: how big it is, which broadcaster feeds it,
 * and (as a cache) the clip that broadcaster is currently set to.
 *
 * <h3>Why the clip name is copied instead of asked for</h3>
 * <p>The screen's renderer runs on the client, and the broadcaster may be in
 * another chunk that this client has not loaded. Asking it every frame would
 * mean the picture blinks out whenever the operator walks behind a wall. So the
 * name is copied here at bind time and refreshed once a second while the
 * broadcaster is loaded - the screen keeps the last name it was told, and the
 * renderer only needs the client's own video directory to draw something.</p>
 *
 * <p>What is <em>not</em> copied is the picture itself. The server has no
 * pixels, exactly as with {@link TileEntityVideoBroadcaster}: both ends only
 * ever exchange a file name.</p>
 */
public class TileEntityVideoScreen extends TileEntity implements ITickable {

    /** Sizes the sneak-right-click cycles through, as {cols, rows}. */
    public static final int[][] SIZE_PRESETS = {{1, 1}, {2, 1}, {2, 2}, {3, 2}, {3, 3}};

    /** How often a linked screen re-reads its broadcaster, in ticks. */
    private static final int REFRESH_TICKS = 20;

    private int cols = 1;
    private int rows = 1;

    /** The broadcaster that feeds this screen, or null when unlinked. */
    private BlockPos broadcaster;

    /** Clip name, copied from the broadcaster; empty when nothing will play. */
    private String video = "";

    private int refreshCooldown;

    // ------------------------------------------------------------------ size

    public int getCols() {
        return this.cols;
    }

    public int getRows() {
        return this.rows;
    }

    /** Clamped here as well as in the block: NBT can carry anything. */
    public void setSize(int cols, int rows) {
        this.cols = HBMZZConfig.clampCols(cols);
        this.rows = HBMZZConfig.clampRows(rows);
        markDirty();
        sync();
    }

    /** Next preset after the current size, skipping ones the config forbids. */
    public static int[] nextPreset(int currentCols, int currentRows) {
        int start = 0;
        for (int i = 0; i < SIZE_PRESETS.length; i++) {
            if (SIZE_PRESETS[i][0] == currentCols && SIZE_PRESETS[i][1] == currentRows) {
                start = i + 1;
                break;
            }
        }
        for (int step = 0; step < SIZE_PRESETS.length; step++) {
            int[] candidate = SIZE_PRESETS[(start + step) % SIZE_PRESETS.length];
            if (candidate[0] <= HBMZZConfig.maxScreenCols && candidate[1] <= HBMZZConfig.maxScreenRows) {
                return candidate;
            }
        }
        return new int[]{1, 1};
    }

    // ------------------------------------------------------------------ link

    public BlockPos getBroadcasterPos() {
        return this.broadcaster;
    }

    public boolean isLinked() {
        return this.broadcaster != null;
    }

    public String getVideo() {
        return this.video;
    }

    /** Binds (or rebinds) this screen to a broadcaster and adopts its clip. */
    public void link(BlockPos pos, String clip) {
        this.broadcaster = pos;
        this.video = clip == null ? "" : clip.trim();
        this.refreshCooldown = REFRESH_TICKS;
        markDirty();
        sync();
    }

    public void unlink() {
        this.broadcaster = null;
        this.video = "";
        markDirty();
        sync();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (this.broadcaster == null) {
            return;
        }
        if (this.refreshCooldown > 0) {
            this.refreshCooldown--;
            return;
        }
        this.refreshCooldown = REFRESH_TICKS;
        refreshFromBroadcaster();
    }

    /**
     * Re-reads the clip name from the feeding broadcaster.
     *
     * <p>An unloaded chunk is not "gone": the link is kept and nothing changes,
     * which is what stops a screen from dropping its feed because the operator
     * walked a hundred blocks away. A loaded chunk with no broadcaster in it is
     * a player who broke the block, and that really is a dead link.</p>
     */
    private void refreshFromBroadcaster() {
        if (!this.world.isBlockLoaded(this.broadcaster)) {
            return;
        }
        TileEntity te = this.world.getTileEntity(this.broadcaster);
        if (!(te instanceof TileEntityVideoBroadcaster)) {
            unlink();
            return;
        }
        String name = ((TileEntityVideoBroadcaster) te).getVideo();
        if (!name.equals(this.video)) {
            this.video = name;
            markDirty();
            sync();
        }
    }

    /**
     * Widened box: the panel is drawn over the whole footprint, and the default
     * one-block box would let the culler throw the screen away as soon as its
     * origin cell left the frustum - the far half of a 3x3 screen would blink
     * in and out while walking past it.
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        int w = Math.max(1, this.cols);
        int h = Math.max(1, this.rows);
        return new AxisAlignedBB(getPos()).grow(w + 1.0D, h + 1.0D, w + 1.0D);
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("cols", this.cols);
        compound.setInteger("rows", this.rows);
        compound.setString("video", this.video);
        if (this.broadcaster != null) {
            compound.setLong("vid", this.broadcaster.toLong());
        }
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.cols = HBMZZConfig.clampCols(compound.getInteger("cols") == 0 ? 1 : compound.getInteger("cols"));
        this.rows = HBMZZConfig.clampRows(compound.getInteger("rows") == 0 ? 1 : compound.getInteger("rows"));
        this.video = compound.getString("video");
        this.broadcaster = compound.hasKey("vid") ? BlockPos.fromLong(compound.getLong("vid")) : null;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        NBTTagCompound tag = packet.getNbtCompound();
        this.cols = HBMZZConfig.clampCols(tag.getInteger("cols") == 0 ? 1 : tag.getInteger("cols"));
        this.rows = HBMZZConfig.clampRows(tag.getInteger("rows") == 0 ? 1 : tag.getInteger("rows"));
        this.video = tag.getString("video");
        this.broadcaster = tag.hasKey("vid") ? BlockPos.fromLong(tag.getLong("vid")) : null;
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

}

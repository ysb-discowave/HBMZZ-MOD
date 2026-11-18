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

import com.hbmzz.block.BlockVideoScreen;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * One wall's worth of state: how big it is, how fast it plays, which clip, and
 * whether the lever upstream is currently on.
 *
 * <p>Server-authoritative like every other machine here. The client is told the
 * shape of the picture ({@code cols}, {@code rows}, {@code speed}, {@code video})
 * and the play state, and does its own decoding - the frames never travel. A
 * client that has no copy of the clip simply shows nothing; the broadcaster's
 * window is where the "this client does not have that file" sentence lives.</p>
 *
 * <p><b>The screen does not decide when to play.</b> A redstone lever drives the
 * broadcaster, the broadcaster drives this flag, and every client follows it. If
 * the screen ran its own clock the wall could be showing the film while the
 * lever said stop, which is exactly the confusion this feature exists to avoid.</p>
 */
public class TileEntityVideoScreen extends TileEntity {

    /** Both axes are capped at this many blocks. */
    public static final int MAX_SIZE = 32;

    /** Playback speed range. */
    public static final float MIN_SPEED = 0.25F;
    public static final float MAX_SPEED = 3.0F;

    private int cols = 1;
    private int rows = 1;
    private float speed = 1.0F;
    private String video = "";
    private boolean playing;
    /** Volume the wall plays at, 0..200 percent; set by the broadcaster. */
    private int volume = 100;
    /** Controller position of the broadcaster feeding this wall, or null. */
    private BlockPos playerPos;

    // ------------------------------------------------------------------ state

    public int getCols() {
        return this.cols;
    }

    public int getRows() {
        return this.rows;
    }

    public float getSpeed() {
        return this.speed;
    }

    public String getVideo() {
        return this.video;
    }

    public boolean isPlaying() {
        return this.playing;
    }

    public int getVolume() {
        return this.volume;
    }

    public void setVolume(int volume) {
        int next = Math.max(0, Math.min(200, volume));
        if (next == this.volume) {
            return;
        }
        this.volume = next;
        markDirty();
        sync();
    }

    public BlockPos getPlayerPos() {
        return this.playerPos;
    }

    public boolean hasPlayer() {
        return this.playerPos != null;
    }

    /**
     * Stores the wall's size and re-lays the cells.
     *
     * <p>Growth places the missing cells, shrink removes only cells this block
     * owns; both are done here so every caller - window, packet, NBT - goes
     * through one path.</p>
     */
    public void applySize(int cols, int rows) {
        int nextCols = clamp(cols);
        int nextRows = clamp(rows);
        if (nextCols == this.cols && nextRows == this.rows) {
            return;
        }
        this.cols = nextCols;
        this.rows = nextRows;
        markDirty();
        if (this.world != null && !this.world.isRemote) {
            IBlockState state = this.world.getBlockState(this.pos);
            if (state.getBlock() instanceof BlockVideoScreen) {
                BlockVideoScreen.ensureParts(this.world, this.pos, state.getValue(BlockVideoScreen.FACING),
                        nextCols, nextRows);
                BlockVideoScreen.trimParts(this.world, this.pos, state.getValue(BlockVideoScreen.FACING),
                        nextCols, nextRows);
            }
        }
        sync();
    }

    public void applySpeed(float speed) {
        float next = Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
        // Quarter steps only, so two clients showing the same window cannot
        // disagree about the value by a float's worth of rounding.
        next = Math.round(next * 4.0F) / 4.0F;
        if (Math.abs(next - this.speed) < 1.0e-4F) {
            return;
        }
        this.speed = next;
        markDirty();
        sync();
    }

    public void setVideo(String video) {
        String next = video == null ? "" : video.trim();
        if (next.equals(this.video)) {
            return;
        }
        this.video = next;
        markDirty();
        sync();
    }

    public void setPlayerPos(BlockPos pos) {
        this.playerPos = pos == null ? null : pos.toImmutable();
        markDirty();
        sync();
    }

    public void setPlaying(boolean playing) {
        if (this.playing == playing) {
            return;
        }
        this.playing = playing;
        markDirty();
        sync();
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(MAX_SIZE, value));
    }

    // ------------------------------------------------------------------ tick

    /**
     * Keeps the renderer's culling box around the whole wall.
     *
     * <p>A TESR is only called when its tile entity survives frustum culling, and
     * the tile entity sits in the single controller cell. Standing two metres from
     * the far corner of a 32x32 wall would put the controller behind the camera and
     * take the picture with it. The box is grown by the wall's extent, which is the
     * documented way to say "this cell draws over there too".</p>
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(this.pos).grow(MAX_SIZE + 1.0D, MAX_SIZE + 1.0D, MAX_SIZE + 1.0D);
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("cols", this.cols);
        compound.setInteger("rows", this.rows);
        compound.setFloat("speed", this.speed);
        compound.setString("video", this.video);
        compound.setBoolean("playing", this.playing);
        compound.setInteger("volume", this.volume);
        if (this.playerPos != null) {
            compound.setLong("player", this.playerPos.toLong());
        }
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.cols = clamp(compound.hasKey("cols") ? compound.getInteger("cols") : 1);
        this.rows = clamp(compound.hasKey("rows") ? compound.getInteger("rows") : 1);
        this.speed = compound.hasKey("speed")
                ? Math.max(MIN_SPEED, Math.min(MAX_SPEED, compound.getFloat("speed"))) : 1.0F;
        this.video = compound.getString("video");
        // A wall does not resume by itself after a reload: the lever is the only
        // thing that starts it, and the broadcaster re-asserts the state on its
        // first tick if it is still powered. Restoring "playing" from disk is how
        // a film ends up running with nothing driving it.
        this.playing = false;
        this.volume = Math.max(0, Math.min(200, compound.getInteger("volume")));
        this.playerPos = compound.hasKey("player") ? BlockPos.fromLong(compound.getLong("player")) : null;
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
        this.cols = clamp(tag.getInteger("cols"));
        this.rows = clamp(tag.getInteger("rows"));
        this.speed = Math.max(MIN_SPEED, Math.min(MAX_SPEED, tag.getFloat("speed")));
        this.video = tag.getString("video");
        this.playing = tag.getBoolean("playing");
        this.volume = Math.max(0, Math.min(200, tag.getInteger("volume")));
        this.playerPos = tag.hasKey("player") ? BlockPos.fromLong(tag.getLong("player")) : null;
    }

    private void sync() {
        if (this.world != null && !this.world.isRemote) {
            IBlockState state = this.world.getBlockState(this.pos);
            this.world.notifyBlockUpdate(this.pos, state, state, 3);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

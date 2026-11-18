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

    /**
     * Longest playhead the server will accept, in milliseconds.
     *
     * <p>The server cannot know a clip's real length - the file lives in each
     * client's own {@code config/hbmzz/videos} - so it bounds the number it does
     * accept and every client clamps again against the clip it actually has. Six
     * hours is far past any clip this mod can read, and small enough that a
     * hostile packet cannot park a wall at some astronomically large time.</p>
     */
    public static final int MAX_PROGRESS_MS = 6 * 60 * 60 * 1000;

    private int cols = 1;
    private int rows = 1;
    private float speed = 1.0F;
    private String video = "";
    private boolean playing;
    /**
     * Playhead, in film milliseconds, anchored to a world tick.
     *
     * <p>The wall keeps a position and a stopwatch instead of counting frames, so
     * everybody who looks at it agrees where the film is: the server publishes
     * {@code progressMs} plus the tick it was set on, and a client works out
     * "23.4 s" from its own world clock. Sending a position every tick would be
     * both wasteful and jittery; sending the anchor once is exact.</p>
     */
    private int progressMs;
    /** World time when {@link #progressMs} stopped being the whole truth. */
    private long anchorTick = -1L;
    /** A freeze on top of the lever: the picture holds, the clock does not run. */
    private boolean paused;
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

    /** Where the playhead sits right now, in film milliseconds. */
    public int positionMs() {
        if (!this.playing || this.paused || this.anchorTick < 0L) {
            return this.progressMs;
        }
        long ticks = worldTicks() - this.anchorTick;
        if (ticks <= 0L) {
            return this.progressMs;
        }
        long advanced = Math.round(ticks * 50.0D * this.speed);
        return clampMs(this.progressMs + advanced);
    }

    /** Anchor the timeline was set at; clients use it to stay in step. */
    public long getAnchorTick() {
        return this.anchorTick;
    }

    public int getProgressMs() {
        return this.progressMs;
    }

    public boolean isPaused() {
        return this.paused;
    }

    /**
     * Moves the playhead and keeps playing from there.
     *
     * <p>This is the only entry point for a jump - window, packet and command all
     * land here - and it deliberately does not change the play state: a seek
     * while paused leaves the wall paused at the new position, and a seek while
     * playing carries on. Starting or stopping is the lever's business.</p>
     */
    public void seekTo(int millis) {
        this.progressMs = clampMs(millis);
        this.anchorTick = worldTicks();
        markDirty();
        sync();
    }

    /**
     * Freezes or resumes the clock without touching the lever.
     *
     * <p>Pause is a third state on purpose. Turning the lever off means "stop",
     * and stopping resets the playhead; pausing means "hold this frame", which
     * has to survive an unpause exactly where it was. Folding the two together
     * would make pause either lose the film or leave the wall running with the
     * lever off.</p>
     */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        // Read the playhead first: after the flag flips it is a stale anchor.
        int now = positionMs();
        this.paused = paused;
        this.progressMs = now;
        this.anchorTick = worldTicks();
        markDirty();
        sync();
    }

    /**
     * Back to the first frame, still playing.
     *
     * <p>Two calls in one, because "从头播放" is one action for whoever presses it:
     * jump to zero and make sure the clock is running. The lever keeps its own
     * meaning - this does not turn a stopped wall on, since a wall with no signal
     * has no business playing.</p>
     */
    public void restart() {
        this.paused = false;
        this.progressMs = 0;
        this.anchorTick = worldTicks();
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
        // Fold the film time played so far into the anchor first: the position is
        // base + elapsed x speed, so changing speed without re-anchoring jumps the
        // playhead by the difference.
        this.progressMs = positionMs();
        this.anchorTick = worldTicks();
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
        // A different clip starts at its first frame: keeping the old playhead
        // would open the new film at whatever second the last one had reached.
        this.progressMs = 0;
        this.paused = false;
        this.anchorTick = worldTicks();
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
        if (playing) {
            // Continue from wherever the playhead was left. Normally that is zero,
            // because stopping resets it; it is not zero when somebody seeked
            // while the wall was stopped to pick a start point.
            this.anchorTick = worldTicks();
        } else {
            // Stop means stop and rewind: a lever-driven broadcast starts at the
            // top, which is also what it did before the playhead existed.
            this.paused = false;
            this.progressMs = 0;
            this.anchorTick = -1L;
        }
        markDirty();
        sync();
    }

    private static int clamp(int value) {
        return Math.max(1, Math.min(MAX_SIZE, value));
    }

    private static int clampMs(long value) {
        return (int) Math.max(0L, Math.min(MAX_PROGRESS_MS, value));
    }

    private long worldTicks() {
        return this.world == null ? 0L : this.world.getTotalWorldTime();
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
        compound.setInteger("progress", this.progressMs);
        compound.setBoolean("paused", this.paused);
        compound.setLong("anchor", this.anchorTick);
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
        // Same reason: a reloaded wall starts at the top of its clip, not where
        // the previous session happened to be.
        this.progressMs = 0;
        this.paused = false;
        this.anchorTick = -1L;
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
        // The anchor travels with the position: a client that joined late can
        // still work out where the film is, without waiting for the next tick.
        this.progressMs = clampMs(tag.hasKey("progress") ? tag.getInteger("progress") : 0);
        this.paused = tag.getBoolean("paused");
        this.anchorTick = tag.hasKey("anchor") ? tag.getLong("anchor") : -1L;
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

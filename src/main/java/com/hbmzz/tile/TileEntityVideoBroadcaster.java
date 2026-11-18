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
import com.hbmzz.block.BlockVideoBroadcaster;
import com.hbmzz.video.VideoBroadcast;
import com.hbmzz.video.VideoLibrary;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The broadcaster's memory: which clip, which players, and the edge detector.
 *
 * <h3>Why the redstone is polled instead of listened to</h3>
 * <p>Minecraft's redstone is a tick-based system; no signal can arrive between
 * two ticks. Polling {@link World#isBlockPowered} once per tick is therefore
 * exactly as fast as any {@code neighborChanged} hook - the worst case is the
 * same one tick - and it cannot miss anything. Neighbour notifications, by
 * contrast, <em>can</em> be missed: a chunk that is reloaded next to a powered
 * wire comes back without having seen the transition at all. Since the failure
 * mode here is "the screen never takes over", missing an edge is the one bug
 * this block cannot afford.</p>
 *
 * <p><b>Rising edge only.</b> A lever left down is one broadcast, not twenty a
 * second. The first tick after the tile entity is created is explicitly
 * <em>not</em> an edge: log in next to a block sitting on a redstone block and
 * nothing should happen. Cooldown then covers the remaining case - a fast
 * clock (hopper, repeater loop) pulsing faster than the configured minimum
 * interval - so a 5 Hz signal cannot turn a video into a strobe.</p>
 *
 * <h3>What is saved</h3>
 * <p>Only the configuration: {@code video} and {@code selector}. The status
 * line is runtime state - reloading a world should not resurrect last Tuesday's
 * "选择器没有匹配到玩家" - but it is part of the update tag so an open window can
 * show what the server last said.</p>
 */
public class TileEntityVideoBroadcaster extends TileEntity implements ITickable {

    public static final String DEFAULT_SELECTOR = "@a[r=32]";

    private String video = "";
    private String selector = DEFAULT_SELECTOR;
    private String status = "等待红石信号";

    /** False until the first tick has adopted the current signal without firing. */
    private boolean primed;
    private boolean lastPowered;
    private int cooldown;

    // ---------------------------------------------------------------- config

    public String getVideo() {
        return this.video;
    }

    public String getSelector() {
        return this.selector;
    }

    public String getStatus() {
        return this.status;
    }

    /**
     * Stores both fields after checking them.
     *
     * @return false when the name is not a legal clip name
     */
    public boolean applyConfig(String video, String selector) {
        String nextVideo = video == null ? "" : video.trim();
        String nextSelector = selector == null ? "" : selector.trim();
        if (!nextVideo.isEmpty() && !VideoLibrary.isValidName(nextVideo)) {
            return false;
        }
        if (nextSelector.length() > 128) {
            return false;
        }
        this.video = nextVideo;
        this.selector = nextSelector;
        this.status = "已保存，等待红石信号";
        markDirty();
        sync();
        return true;
    }

    public void setStatus(String status) {
        this.status = status == null ? "" : status;
        markDirty();
        sync();
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
        }
        boolean powered = this.world.isBlockPowered(this.pos);
        if (!this.primed) {
            this.primed = true;
            this.lastPowered = powered;
            applyPoweredState(powered);
            return;
        }
        if (powered == this.lastPowered) {
            return;
        }
        this.lastPowered = powered;
        applyPoweredState(powered);
        if (powered) {
            fire();
        }
    }

    private void fire() {
        if (this.cooldown > 0) {
            setStatus("红石信号过快，冷却中（" + HBMZZConfig.videoCooldownTicks + " tick）");
            return;
        }
        if (this.video.isEmpty()) {
            setStatus("未设置视频文件");
            return;
        }
        this.cooldown = Math.max(1, HBMZZConfig.videoCooldownTicks);
        setStatus(VideoBroadcast.fire(this.world, this.pos, this.video, this.selector));
    }

    /** Lights the lamp on the block face; purely cosmetic, never gameplay. */
    private void applyPoweredState(boolean powered) {
        if (this.world == null) {
            return;
        }
        net.minecraft.block.state.IBlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockVideoBroadcaster
                && state.getValue(BlockVideoBroadcaster.POWERED) != powered) {
            this.world.setBlockState(this.pos, state.withProperty(BlockVideoBroadcaster.POWERED, powered), 3);
        }
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("video", this.video);
        compound.setString("selector", this.selector);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.video = compound.getString("video");
        this.selector = compound.hasKey("selector") ? compound.getString("selector") : DEFAULT_SELECTOR;
        // Coming back from disk is not a redstone edge.
        this.primed = false;
        this.lastPowered = false;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = writeToNBT(new NBTTagCompound());
        tag.setString("status", this.status);
        return tag;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        NBTTagCompound tag = packet.getNbtCompound();
        this.video = tag.getString("video");
        this.selector = tag.hasKey("selector") ? tag.getString("selector") : DEFAULT_SELECTOR;
        if (tag.hasKey("status")) {
            this.status = tag.getString("status");
        }
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

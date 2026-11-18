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

    /** Playback level, 0..200 percent. Owned by this block, not by the wall. */
    private int volume = 100;
    /**
     * 强制限制返回: 没接大屏时，全屏播放结束前 ESC 不生效。
     *
     * <p>默认开启。它只在<b>没有</b>检测到大屏时成立——接上大屏以后，谁能
     * 退出由大屏和拉杆决定，全屏那条路不该再拦人。所以这里存的是"玩家的
     * 意愿"，是否生效要再问一句 {@link #restrictBackApplies()}。</p>
     */
    private boolean restrictBack = !HBMZZConfig.videoAllowEsc;
    /**
     * The wall this player drives, if any.
     *
     * <p>Kept even while the wall's chunk is unloaded: "the screen is over there"
     * and "the screen is loaded right now" are different questions, and only the
     * first one may decide whether the lever talks to a wall or to everybody's
     * full screen.</p>
     */
    private BlockPos screenPos;

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

    public int getVolume() {
        return this.volume;
    }

    public boolean isRestrictBack() {
        return this.restrictBack;
    }

    /**
     * 这条限制此刻是否成立：玩家开了它，而且没有检测到大屏。
     *
     * <p>连接状态是唯一的事实来源，客户端每次要判断时都回来查一次，所以遥控器
     * 中途接上大屏、或者大屏被拆掉，下一次按键就已经是新结论，不需要谁去通知
     * 谁。拆掉大屏时 {@code loadedScreen()} 会顺手清空连接，于是限制自动恢复。</p>
     */
    public boolean restrictBackApplies() {
        return this.restrictBack && this.screenPos == null;
    }

    public BlockPos getScreenPos() {
        return this.screenPos;
    }

    /** True when a wall has been connected, loaded or not. */
    public boolean hasScreen() {
        return this.screenPos != null;
    }

    /** Volume and 强制限制返回, from the player's own settings window. */
    public void applyPlayback(int volume, boolean restrictBack) {
        this.volume = Math.max(0, Math.min(200, volume));
        this.restrictBack = restrictBack;
        markDirty();
        TileEntityVideoScreen screen = loadedScreen();
        if (screen != null) {
            screen.setVolume(this.volume);
        }
        sync();
    }

    public void setScreenPos(BlockPos pos) {
        this.screenPos = pos == null ? null : pos.toImmutable();
        markDirty();
        sync();
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
        // A wall shows what its player holds, so the wall is told about a new
        // clip here rather than polling for it.
        TileEntityVideoScreen screen = loadedScreen();
        if (screen != null) {
            screen.setVideo(this.video);
            screen.setVolume(this.volume);
        }
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
            // Adopting the current signal must not fire anything (a lever left
            // down is not an edge), but a connected wall still has to agree with
            // the lever it is wired to.
            driveScreen(powered);
            return;
        }
        if (powered == this.lastPowered) {
            return;
        }
        this.lastPowered = powered;
        applyPoweredState(powered);
        if (driveScreen(powered)) {
            // A wall is connected: the lever is a play / stop switch for it, and
            // that is all it is. In particular a mis-click must never turn into a
            // full-screen takeover for everyone in range.
            return;
        }
        if (powered) {
            fire();
        }
    }

    /**
     * Tells the connected wall to play or stop.
     *
     * @return true when this block is wired to a wall, i.e. the full-screen path
     *         is not the lever's business any more
     */
    private boolean driveScreen(boolean powered) {
        if (this.screenPos == null) {
            return false;
        }
        TileEntityVideoScreen screen = loadedScreen();
        if (screen == null) {
            setStatus(powered ? "大屏不在已加载区域，无法开始播放" : "大屏不在已加载区域");
            return true;
        }
        screen.setVideo(this.video);
        screen.setVolume(this.volume);
        screen.setPlaying(powered);
        if (!powered) {
            setStatus("大屏已停止");
        } else if (this.video.isEmpty()) {
            setStatus("大屏已接通，但还没有设置视频");
        } else {
            setStatus("大屏播放中：" + this.video);
        }
        return true;
    }

    /** The connected wall, or null when it is gone or its chunk is not loaded. */
    private TileEntityVideoScreen loadedScreen() {
        if (this.screenPos == null || this.world == null) {
            return null;
        }
        if (!this.world.isBlockLoaded(this.screenPos)) {
            return null;
        }
        TileEntity te = this.world.getTileEntity(this.screenPos);
        if (!(te instanceof TileEntityVideoScreen)) {
            // The wall was broken; forget the link so the lever goes back to its
            // original full-screen behaviour instead of never doing anything.
            this.screenPos = null;
            markDirty();
            sync();
            return null;
        }
        TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
        if (screen.getPlayerPos() != null && !this.pos.equals(screen.getPlayerPos())) {
            // Re-bound to another player since; this block is not its owner.
            this.screenPos = null;
            markDirty();
            sync();
            return null;
        }
        return screen;
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
        setStatus(VideoBroadcast.fire(this.world, this.pos, this.video, this.selector,
                this.volume, this.restrictBack));
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
        compound.setInteger("volume", this.volume);
        compound.setBoolean("restrictBack", this.restrictBack);
        if (this.screenPos != null) {
            compound.setLong("screen", this.screenPos.toLong());
        }
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.video = compound.getString("video");
        this.selector = compound.hasKey("selector") ? compound.getString("selector") : DEFAULT_SELECTOR;
        this.volume = compound.hasKey("volume")
                ? Math.max(0, Math.min(200, compound.getInteger("volume"))) : 100;
        // 旧存档里这一位叫 forceExit，读得到就用它，读不到才回默认值。
        this.restrictBack = compound.hasKey("restrictBack") ? compound.getBoolean("restrictBack")
                : compound.hasKey("forceExit") ? compound.getBoolean("forceExit")
                : !HBMZZConfig.videoAllowEsc;
        this.screenPos = compound.hasKey("screen") ? BlockPos.fromLong(compound.getLong("screen")) : null;
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
        this.volume = tag.hasKey("volume") ? tag.getInteger("volume") : 100;
        this.restrictBack = tag.hasKey("restrictBack") ? tag.getBoolean("restrictBack") : true;
        this.screenPos = tag.hasKey("screen") ? BlockPos.fromLong(tag.getLong("screen")) : null;
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

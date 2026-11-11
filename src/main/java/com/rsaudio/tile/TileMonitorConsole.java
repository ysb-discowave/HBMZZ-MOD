package com.rsaudio.tile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 监控控制台的方块实体。
 *
 * <h3>它自己不渲染</h3>
 * 和摄像头一样，画面是客户端渲染的。控制台这边只存"我在看哪个频道"，
 * 以及"屏幕上要不要叠一层字幕（频道号 / 时间 / 画面里有谁）"。
 * <p>
 * 控制台屏幕上的实时画面由 {@code com.rsaudio.client.MonitorConsoleRenderer}
 * （方块实体专用渲染器）画出来；打开界面后，同一张画面会被界面复用 ——
 * <b>一份渲染、两处显示</b>，不会为了"屏幕上"和"界面里"各渲染一遍。
 */
public class TileMonitorConsole extends TileEntity {

    public static final int CHANNEL_MIN = TileMonitorCamera.CHANNEL_MIN;
    public static final int CHANNEL_MAX = TileMonitorCamera.CHANNEL_MAX;
    public static final int DEFAULT_CHANNEL = TileMonitorCamera.DEFAULT_CHANNEL;

    private int channel = DEFAULT_CHANNEL;
    /** 屏幕上要不要叠加频道号/时间/画面内实体的字幕条 */
    private boolean overlay = true;

    public int getChannel() {
        return channel;
    }

    public boolean isOverlay() {
        return overlay;
    }

    public void setChannel(int value) {
        int clamped = TileMonitorCamera.clampChannel(value);
        if (channel == clamped) {
            return;
        }
        channel = clamped;
        markDirty();
        syncToClient();
    }

    public void setOverlay(boolean value) {
        if (overlay == value) {
            return;
        }
        overlay = value;
        markDirty();
        syncToClient();
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.channel = compound.hasKey("Channel")
                ? TileMonitorCamera.clampChannel(compound.getInteger("Channel")) : DEFAULT_CHANNEL;
        this.overlay = !compound.hasKey("Overlay") || compound.getBoolean("Overlay");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Channel", channel);
        compound.setBoolean("Overlay", overlay);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Channel", channel);
        tag.setBoolean("Overlay", overlay);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.channel = tag.hasKey("Channel")
                ? TileMonitorCamera.clampChannel(tag.getInteger("Channel")) : DEFAULT_CHANNEL;
        this.overlay = !tag.hasKey("Overlay") || tag.getBoolean("Overlay");
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    /**
     * 控制台<b>故意不</b>重写 {@code invalidate()} 去释放画面缓冲。
     * <p>
     * 多台控制台可能看同一个频道，谁先卸载谁就释放的话，另一台还开着界面就会突然黑屏。
     * 画布只属于摄像头（键是摄像头的坐标），所以回收只由
     * {@code TileMonitorCamera#invalidate()} / {@code onChunkUnload()} 负责 ——
     * 控制台在这里调 {@code releaseCameraFeed(pos)} 是个<b>无效动作</b>
     * （那个键上根本没有画布），写上去只会让后来的人以为"控制台也管回收"。
     */
}

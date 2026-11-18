package com.rsaudio.tile;

import com.rsaudio.block.BlockRedstoneDelayer;
import com.rsaudio.util.Broadcaster;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 红石延迟器方块实体：把延迟时间存在 NBT 里。
 * <p>
 * 为什么不放在方块状态里：方块状态的 meta 只有 4 bit，塞不下"几秒~几分钟"这种范围；
 * 而且每多一档延迟就要多一套模型/贴图。放在方块实体里之后延迟范围可以随便给，
 * 顶面的刻度条则由 {@link BlockRedstoneDelayer#getActualState} 现算，仍然能看出大概快慢。
 * <p>
 * 还带一份"聊天栏播报"设置（{@link IBroadcastHolder}）：延迟到点输出红石信号时，
 * 把设定好的文字发到附近玩家的聊天栏，颜色可选。
 */
public class TileRedstoneDelayer extends TileEntity implements IBroadcastHolder {

    private int delayTicks = BlockRedstoneDelayer.DEFAULT_DELAY_TICKS;

    /** 是否已经为这一次上升沿排定了延迟更新（避免同一次上升沿被重复排程） */
    private boolean pending = false;

    /** 输入变高的那一刻的世界时间，用来算"还差多久"（中途改时间也能正确重排） */
    private long riseTime = 0L;

    /** 输出红石信号时播报到聊天栏的文字（空 = 不播报） */
    private String broadcast = "";
    /** 播报文字的颜色（原版 TextFormatting 的 ordinal） */
    private int broadcastColor = Broadcaster.DEFAULT_COLOR;

    public int getDelayTicks() {
        return delayTicks;
    }

    /** 服务端设置延迟（存档 + 通知客户端刷新渲染） */
    public void setDelayTicks(int ticks) {
        int value = BlockRedstoneDelayer.clampDelay(ticks);
        boolean changed = value != this.delayTicks;
        this.delayTicks = value;
        markDirty();
        if (changed && world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            // 顶面刻度条是 getActualState 现算的，让客户端重建这一块的渲染
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    /** 客户端收到同步包时使用：只改值，不回发任何东西 */
    public void setDelayTicksClient(int ticks) {
        this.delayTicks = BlockRedstoneDelayer.clampDelay(ticks);
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean value) {
        this.pending = value;
    }

    public long getRiseTime() {
        return riseTime;
    }

    public void setRiseTime(long value) {
        this.riseTime = value;
    }

    // ------------------------------------------------------------------ 聊天栏播报

    @Override
    public String getBroadcastText() {
        return broadcast;
    }

    @Override
    public int getBroadcastColor() {
        return broadcastColor;
    }

    /** 服务端设置播报文字与颜色（存档 + 通知附近客户端） */
    @Override
    public void setBroadcast(String text, int color) {
        String value = Broadcaster.sanitize(text);
        if (value.equals(this.broadcast) && color == this.broadcastColor) {
            return;
        }
        this.broadcast = value;
        this.broadcastColor = color;
        markDirty();
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    /** 客户端收到同步包时使用：只改值，不回发任何东西 */
    @Override
    public void applySyncedBroadcast(String text, int color) {
        this.broadcast = Broadcaster.sanitize(text);
        this.broadcastColor = color;
    }

    // ------------------------------------------------------------------ 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("Delay")) {
            this.delayTicks = BlockRedstoneDelayer.clampDelay(compound.getInteger("Delay"));
        }
        this.pending = compound.getBoolean("Pending");
        this.riseTime = compound.getLong("Rise");
        this.broadcast = compound.getString("Bcast");
        this.broadcastColor = compound.hasKey("BcastColor")
                ? compound.getByte("BcastColor") : Broadcaster.DEFAULT_COLOR;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Delay", delayTicks);
        compound.setBoolean("Pending", pending);
        compound.setLong("Rise", riseTime);
        compound.setString("Bcast", broadcast);
        compound.setByte("BcastColor", (byte) broadcastColor);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Delay", delayTicks);
        tag.setString("Bcast", broadcast);
        tag.setByte("BcastColor", (byte) broadcastColor);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        if (tag.hasKey("Delay")) {
            this.delayTicks = BlockRedstoneDelayer.clampDelay(tag.getInteger("Delay"));
        }
        this.broadcast = tag.getString("Bcast");
        if (tag.hasKey("BcastColor")) {
            this.broadcastColor = tag.getByte("BcastColor");
        }
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
}

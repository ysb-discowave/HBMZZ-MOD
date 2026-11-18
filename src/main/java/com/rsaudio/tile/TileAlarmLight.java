package com.rsaudio.tile;

import com.rsaudio.block.BlockAlarmLight;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 警报灯的方块实体：爆闪节奏 + 蜂鸣。
 *
 * <h3>时间模型</h3>
 * 有红石信号时按 {@code 周期} 闪烁：{@code 周期} 刻里亮一半、灭一半；
 * {@code 周期 = 0} 表示常亮。点亮/熄灭都由服务端写方块状态（{@link BlockAlarmLight#LIT}），
 * 客户端只负责渲染 —— 这样多人游戏里所有人看到的闪烁节奏完全一致。
 *
 * <h3>蜂鸣</h3>
 * 只在"灭 → 亮"的那一下响一次，所以周期越短叫得越急。
 * 音效直接用原版音符（{@code BLOCK_NOTE_*}），按颜色变调 ——
 * 好处是<b>不用往模组里塞音频文件</b>：塞 .ogg 要处理资源包、体积、授权一堆事。
 */
public class TileAlarmLight extends TileEntity implements ITickable {

    /** 常亮 */
    public static final int PERIOD_ALWAYS = 0;
    /** 最快的爆闪周期（2 刻 = 1 亮 1 灭） */
    public static final int PERIOD_MIN = 2;
    /** 最慢的爆闪周期（100 刻 = 5 秒一轮） */
    public static final int PERIOD_MAX = 100;
    public static final int PERIOD_DEFAULT = 10;

    /** 每种颜色的蜂鸣音色（和 {@link BlockAlarmLight#COLOR_NAMES} 一一对应） */
    private static final SoundEvent[] SOUNDS = {
            SoundEvents.BLOCK_NOTE_BELL, SoundEvents.BLOCK_NOTE_PLING, SoundEvents.BLOCK_NOTE_CHIME,
            SoundEvents.BLOCK_NOTE_HARP, SoundEvents.BLOCK_NOTE_HAT, SoundEvents.BLOCK_NOTE_BASS,
    };
    /** 每种颜色的音高 */
    private static final float[] PITCHES = {0.7F, 0.85F, 1.0F, 1.25F, 1.5F, 1.75F};

    private int period = PERIOD_DEFAULT;
    private boolean beep = true;
    /** 上一次的亮灭（用来找"灭→亮"的瞬间） */
    private boolean lastLit = false;

    // ---------------------------------------------------------------- 参数

    public int getPeriod() {
        return period;
    }

    /** 服务端设置周期（0 = 常亮；1 会被吸成 0，免得出现"周期 1"这种含糊状态） */
    public void setPeriod(int value) {
        int clamped = clampPeriod(value);
        if (clamped == period) {
            return;
        }
        period = clamped;
        markDirty();
        syncToClient();
    }

    public boolean isBeep() {
        return beep;
    }

    public void setBeep(boolean value) {
        if (beep == value) {
            return;
        }
        beep = value;
        markDirty();
        syncToClient();
    }

    /** 潜行右键用的快速开关，返回开关之后的状态 */
    public boolean toggleBeep() {
        setBeep(!beep);
        return beep;
    }

    /**
     * 周期裁剪：0 和 1 都算"常亮"，2~100 是爆闪周期。
     * 界面上一直按减号就会滑到 0（常亮），符合直觉。
     */
    public static int clampPeriod(int value) {
        if (value < PERIOD_MIN) {
            return PERIOD_ALWAYS;
        }
        return value > PERIOD_MAX ? PERIOD_MAX : value;
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        boolean powered = world.isBlockPowered(pos);
        boolean lit;
        if (!powered) {
            lit = false;
        } else if (period <= PERIOD_ALWAYS) {
            lit = true;
        } else {
            long time = world.getTotalWorldTime();
            long phase = time % period;
            lit = phase < Math.max(1L, period / 2L);
        }

        if (lit && !lastLit && beep) {
            playBeep();
        }
        lastLit = lit;

        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockAlarmLight)) {
            return;
        }
        if (state.getValue(BlockAlarmLight.LIT) != lit) {
            // flag 3 = 通知邻居 + 同步客户端；光照会跟着 LIT 一起重算
            world.setBlockState(pos, state.withProperty(BlockAlarmLight.LIT, lit), 3);
        }
    }

    private void playBeep() {
        int index = 0;
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockAlarmLight) {
            index = ((BlockAlarmLight) state.getBlock()).getColorIndex();
        }
        int safe = index < 0 || index >= SOUNDS.length ? 0 : index;
        // 服务端播放 = 自动发给附近所有客户端（原版就是这么做的）
        world.playSound(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                SOUNDS[safe], SoundCategory.BLOCKS, 0.8F, PITCHES[safe]);
    }

    // ---------------------------------------------------------------- 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.period = clampPeriod(compound.hasKey("Period") ? compound.getInteger("Period") : PERIOD_DEFAULT);
        this.beep = !compound.hasKey("Beep") || compound.getBoolean("Beep");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Period", period);
        compound.setBoolean("Beep", beep);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Period", period);
        tag.setBoolean("Beep", beep);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.period = clampPeriod(tag.hasKey("Period") ? tag.getInteger("Period") : PERIOD_DEFAULT);
        this.beep = !tag.hasKey("Beep") || tag.getBoolean("Beep");
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

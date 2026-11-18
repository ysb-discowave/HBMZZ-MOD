package com.rsaudio.tile;

import com.rsaudio.block.BlockDmrCore;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

/**
 * DMR 堆芯的方块实体。
 *
 * <h3>为什么堆芯需要一个方块实体</h3>
 * 它本身几乎不存任何权威状态 —— 反应堆的一切读数都在控制柜（{@link TileDmrController}）
 * 那边的 {@code DmrCore} 里。这里存在的唯一理由是<b>让 TESR 有东西可挂</b>：
 * 方块模型是烘死在区块网格里的静态几何，做不了逐帧动画；
 * 只有方块实体能在 {@code render()} 里逐帧改矩阵。
 *
 * <h3>★ 客户端动画必须自己插值</h3>
 * 服务端每 5 tick 才推一次状态（见 {@code PacketDmrSync}），而屏幕上要跑 60+ fps。
 * 如果渲染器直接拿"服务端上一次给的状态"当动画相位，等离子体就会以 4 fps 一跳一跳地转。
 * 所以速度/相位这类<b>连续量</b>在客户端本地累积：每帧按 {@code dt} 推进，
 * 只把"亮/灭/熔毁"这类<b>离散量</b>交给服务端权威。
 * <p>
 * 这条分工和塔塔鲁斯的 {@code clientAnim} 是同一个思路，只是这里量更多。
 */
public class TileDmrCore extends TileEntity implements ITickable {

    /** 客户端累积的相位（弧度）。只增不减，取模后当角度用 */
    public float clientPhase;
    /** 客户端累积的脉动相位（球体呼吸感的来源） */
    public float clientPulse;
    /** 上一次渲染的相位快照，用于按真实帧间隔推进（而不是按 tick） */
    private float lastPhase;

    /** 等离子体的当前"强度"（0..1），客户端向目标值平滑追赶 */
    public float clientIntensity;
    /** 目标强度：由方块状态决定（未点火 0.25 / 运行 1.0 / 熔毁 1.4） */
    private float targetIntensity = 0.25F;

    /** 熔毁剧烈抖动用的相位 */
    public float clientShake;

    /** 是否正在熔毁（客户端从方块状态读） */
    private boolean melting;

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void update() {
        if (world == null) {
            return;
        }
        if (world.isRemote) {
            // 客户端：只推进相位。真正的"目标强度"在渲染时从方块状态现取，
            // 这样即使服务端还没推包，观感也已经是新的。
            tickClient();
        }
        // ★ 服务端不做任何事：堆芯不是权威。所有推进都在控制柜那边。
    }

    /**
     * 客户端逐 tick 推进动画。
     * <p>
     * 注意这里<b>只推进相位</b>，不改 {@code targetIntensity} 之外的东西 ——
     * 而且强度用"每 tick 追一点"而不是瞬变，这样"点火瞬间亮起来"是一个
     * 约 0.5 秒的渐亮，而不是一帧跳变。塔塔鲁斯那边也是这个手感。
     */
    private void tickClient() {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockDmrCore) {
            melting = state.getValue(BlockDmrCore.MELTDOWN);
            boolean lit = state.getValue(BlockDmrCore.LIT);
            targetIntensity = melting ? 1.4F : (lit ? 1.0F : 0.25F);
        }
        // 相位推进：熔毁时转得更快更狂
        float spin = melting ? 0.22F : 0.055F;
        clientPhase += spin;
        if (clientPhase > (float) (Math.PI * 2.0D)) {
            clientPhase -= (float) (Math.PI * 2.0D);
        }
        clientPulse += melting ? 0.35F : 0.075F;
        if (clientPulse > (float) (Math.PI * 2.0D)) {
            clientPulse -= (float) (Math.PI * 2.0D);
        }
        clientShake += melting ? 0.9F : 0.0F;
        if (clientShake > (float) (Math.PI * 2.0D)) {
            clientShake -= (float) (Math.PI * 2.0D);
        }
        // 强度平滑追赶（每 tick 走 6%，约 0.5 秒到位）
        clientIntensity += (targetIntensity - clientIntensity) * 0.06F;
    }

    /** 渲染器每帧调一次，拿到"本帧相对上一帧推进了多少"，用于独立于 tick 的平滑 */
    public float advancePhase(float amount) {
        clientPhase += amount;
        if (clientPhase > (float) (Math.PI * 2.0D)) {
            clientPhase -= (float) (Math.PI * 2.0D);
        }
        return clientPhase;
    }

    // ------------------------------------------------------------------ 包围盒

    /**
     * ★ 渲染包围盒必须放大到整台机器的尺寸。
     * <p>
     * 原版默认给的是"这一格"的盒子。而等离子体和腔体环是画在堆芯<b>外面</b>的，
     * 会伸到 3×3×3 外壳的范围里 —— 用默认盒子的话，玩家一转视角、
     * 堆芯刚离开视锥，整团等离子体就会连同外壳里的环一起消失。
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        // 以堆芯为中心，覆盖 3×3×3 外壳再往外的余量（竖向多留一点给顶部辉光）
        return new AxisAlignedBB(
                pos.getX() - 2.0D, pos.getY() - 2.0D, pos.getZ() - 2.0D,
                pos.getX() + 3.0D, pos.getY() + 4.0D, pos.getZ() + 3.0D);
    }

    @Override
    public boolean shouldRefresh(net.minecraft.world.World world, BlockPos oldPos, IBlockState oldState,
                                 IBlockState newState) {
        // 默认实现会在"方块还没被换掉但状态变了"时也重建实例，
        // 那会把客户端累积的相位清零 → 每次状态切换动画都跳一下。
        return oldState.getBlock() != newState.getBlock();
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 堆芯自己不需要同步任何数据 —— 视觉状态全在方块状态里
     * （{@code lit} / {@code meltdown} 两个 PropertyBool），
     * 方块状态是原版自动同步的，不用手写包。
     */
    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        readFromNBT(tag);
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 0, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        readFromNBT(pkt.getNbtCompound());
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        // 相位刻意不存盘：它只是观感，重启后从 0 开始没有任何问题，
        // 而存了反而要处理"读档瞬间相位乱跳"。
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        return super.writeToNBT(compound);
    }
}

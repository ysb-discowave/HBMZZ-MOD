package com.rsaudio.tile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 起重机的方块实体。
 *
 * <h3>它不存任何东西 —— 为什么还要有</h3>
 * 和 {@link TileExhaustFan} 一模一样的理由：朝向和开关全在<b>方块状态</b>里
 * （自动同步、自动存档），方块实体的唯一职责是"让 MC 觉得这一格有个东西
 * 要特判渲染" —— {@code TileEntitySpecialRenderer} 只对带方块实体
 * 的方块调用。
 *
 * <h3>★ 渲染包围盒要罩住整台龙门架</h3>
 * 排风扇凸出方块表面半格，{@code grow(0.5D)} 就够了；起重机不一样 ——
 * 龙门架和回转臂都<b>高出方块顶面将近一格</b>（见 {@code CraneRenderer}：
 * 横梁顶到 y≈1.5，回转臂最远甩到中心外 1.1 格）。默认的
 * {@code [pos, pos+1]} 包围盒罩不住，区块边缘 / 视锥边缘处的这台车
 * 会"整块被剔除" —— 和列车车厢附件那次是同一个坑（纯渲染的附加物
 * 必须自己把包围盒撑大）。
 * <p>
 * 横向取 ±0.75（回转臂甩到 0.5+1.1=1.6 会超出方块，但那是<b>瞬时</b>的
 * 尖端；包围盒常驻 2.5×1.7×2.5 已经比"罩满 3.2 的旋转扫掠圆"省得多），
 * 纵向取 [pos-0.1, pos+1.6]。
 */
public class TileCrane extends TileEntity {

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        // 右键切换 RUNNING 会 setBlockState，状态变了但方块没变 —— 方块实体必须留着，
        // 否则每按一次都重建一个（并丢掉这一格已注册的渲染器状态）。
        return oldState.getBlock() != newState.getBlock();
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        if (this.pos == null) {
            return super.getRenderBoundingBox();
        }
        int x = this.pos.getX();
        int y = this.pos.getY();
        int z = this.pos.getZ();
        return new AxisAlignedBB(x - 0.75D, y - 0.1D, z - 0.75D,
                x + 1.75D, y + 1.6D, z + 1.75D);
    }
}

package com.rsaudio.tile;

import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 排风扇的方块实体。
 *
 * <h3>它不存任何东西 —— 为什么还要有</h3>
 * 排风扇的状态（{@code FACING} / {@code RUNNING}）全在<b>方块状态</b>里：
 * 它本来就是"看得到的状态"，写进方块状态就自动同步、自动存档，
 * 不需要方块实体再抄一份（抄一份出来就有"两份谁说了算"的问题）。
 * <p>
 * 之所以仍然要一个方块实体，是因为 {@code TileEntitySpecialRenderer}
 * <b>只</b>对带方块实体的方块调用。也就是说这个类的唯一职责是
 * "让 MC 觉得这一格有个东西要特判渲染"。
 *
 * <h3>★ 渲染包围盒要外扩</h3>
 * 叶片和防护罩是<b>凸出方块表面</b>的（见 {@code ExhaustFanRenderer}：为了不和
 * 方块自己的正面贴图打架，几何整体挪到了面外）。默认的
 * {@code TileEntity.getRenderBoundingBox()} 恰好是 {@code [pos, pos+1]}，
 * 方块贴到视锥边缘时，凸出来的那部分可能跟着整块被剔掉 ——
 * 本模组的列车车厢附件栽过同一次（见项目笔记"纯渲染的附加物"）。
 * 外扩半格的成本可以忽略，换的是"转头看风扇不会突然少一半"。
 */
public class TileExhaustFan extends TileEntity {

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        // 右键切换 RUNNING 会 setBlockState，状态变了但方块没变 —— 方块实体必须留着，
        // 否则每按一次都重建一个（并丢掉这一格已注册的渲染器状态）。
        return oldState.getBlock() != newState.getBlock();
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return super.getRenderBoundingBox().grow(0.5D);
    }
}

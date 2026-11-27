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

import com.hbmzz.blast.BlockDfcCollapseSensor;
import com.hbmzz.blast.DfcCollapseWatch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 传感器的方块实体：每一 tick 问一次「现在该输出几级」。
 *
 * <h3>为什么只有它在算，而且只在服务端算</h3>
 * <p>红石是服务端的语言：活塞、红石粉、比较器判定强弱，全都在服务端发生。客户端
 * 拿到的只是「这个方块现在通不通电」这一个布尔值，靠方块状态换模型。所以这里没有
 * 任何客户端同步包——一次坍缩只产生<strong>两次</strong>方块更新（点亮、熄灭），
 * 而不是每 tick 一个包。</p>
 *
 * <h3>为什么它什么都不存</h3>
 * <p>唯一需要记的是「哪些核心正在坍缩」，那在服务端的 {@link DfcCollapseWatch} 里。
 * 这里存一份副本只会带来一个问题：存档里一条过期记录，下次进游戏先输出一段
 * 莫名其妙的满格信号。坍缩是持续状态，没有衰减包络，所以强度只在 0 和满格之间
 * 跳，方块更新也只在两个拐点上发生。</p>
 */
public class TileEntityDfcCollapseSensor extends TileEntity implements ITickable {

    /**
     * 0 或满格，服务端权威；客户端不读它，只读方块状态的 active。
     *
     * <p>初值是 -1 而不是 0：方块状态是存在存档里的，而强度不是。存档里可能留着一个
     * {@code active=true}（坍缩那一刻被存下来），重新进游戏时表已经被清空——如果这里
     * 也从 0 开始，第一 tick 就会因为「目标 0 = 当前 0」直接 return，那盏灯会一直亮着。
     * 从 -1 开始，第一次 update 必然对账一次。</p>
     */
    private int strength = -1;

    public int getStrength() {
        return this.strength < 0 ? 0 : this.strength;
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        int target = DfcCollapseWatch.collapseStrengthAt(this.world, this.pos);
        if (target == this.strength) {
            return;
        }
        this.strength = target;
        setActiveState(target > 0);
        // 强度本身不在方块状态里（要留出朝向位），所以红石粉必须靠这一次通知
        // 重新取样，否则它记着的还是上一级。
        this.world.notifyNeighborsOfStateChange(this.pos, this.getBlockType(), false);
    }

    private void setActiveState(boolean active) {
        IBlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockDfcCollapseSensor
                && state.getValue(BlockDfcCollapseSensor.ACTIVE) != active) {
            this.world.setBlockState(this.pos, state.withProperty(BlockDfcCollapseSensor.ACTIVE, active), 3);
        }
    }

    /** 换方块时重建实体，别把上一条记录带到一个新方块上。 */
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

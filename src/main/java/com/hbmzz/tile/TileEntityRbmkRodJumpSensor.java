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

import com.hbmzz.rbmk.BlockRbmkRodJumpSensor;
import com.hbmzz.rbmk.RbmkEventWatch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 跳棒传感器的方块实体：每一 tick 问一次「这台反应堆现在还在跳棒吗」。
 *
 * <p>跳棒是持续一分钟的状态，所以这里的强度只在「开始跳」和「封顶停跳」两个
 * 拐点上变：开始时跳到满格，停跳时归零，中间整段维持满格——强度没变就什么都不
 * 通知，一次事故只产生两次邻居更新，不是一分钟里每分钟一百次。</p>
 */
public class TileEntityRbmkRodJumpSensor extends TileEntity implements ITickable {

    /** 0..15，服务端权威。初值 -1：理由同爆炸传感器，见其注释。 */
    private int strength = -1;

    public int getStrength() {
        return this.strength < 0 ? 0 : this.strength;
    }

    @Override
    public void update() {
        if (this.world == null || this.world.isRemote) {
            return;
        }
        int target = RbmkEventWatch.jumpStrengthAt(this.world, this.pos);
        if (target == this.strength) {
            return;
        }
        this.strength = target;
        setActiveState(target > 0);
        this.world.notifyNeighborsOfStateChange(this.pos, this.getBlockType(), false);
    }

    private void setActiveState(boolean active) {
        IBlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockRbmkRodJumpSensor
                && state.getValue(BlockRbmkRodJumpSensor.ACTIVE) != active) {
            this.world.setBlockState(this.pos, state.withProperty(BlockRbmkRodJumpSensor.ACTIVE, active), 3);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

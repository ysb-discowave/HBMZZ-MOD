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

import com.hbmzz.rbmk.BlockRbmkBlastSensor;
import com.hbmzz.rbmk.RbmkEventWatch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 爆炸传感器的方块实体：每一 tick 问一次「现在该输出几级」。
 *
 * <p>与 {@link TileEntityDfcBlastSensor} 完全同一套：强度变化才通知邻居，
 * 自己不存任何东西——唯一要持久化的「什么时候炸过」在服务端的
 * {@link RbmkEventWatch} 里。</p>
 */
public class TileEntityRbmkBlastSensor extends TileEntity implements ITickable {

    /**
     * 0..15，服务端权威。初值 -1：存档里可能留着 active=true，强度却已归零，
     * 从 -1 起第一次 update 必然对账一次，那盏灯才会熄灭。
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
        int target = RbmkEventWatch.blastStrengthAt(this.world, this.pos);
        if (target == this.strength) {
            return;
        }
        this.strength = target;
        setActiveState(target > 0);
        this.world.notifyNeighborsOfStateChange(this.pos, this.getBlockType(), false);
    }

    private void setActiveState(boolean active) {
        IBlockState state = this.world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockRbmkBlastSensor
                && state.getValue(BlockRbmkBlastSensor.ACTIVE) != active) {
            this.world.setBlockState(this.pos, state.withProperty(BlockRbmkBlastSensor.ACTIVE, active), 3);
        }
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

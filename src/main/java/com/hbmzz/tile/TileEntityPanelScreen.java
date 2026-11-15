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
 * Portions of the render structure and the offscreen-canvas pipeline are derived
 * from JiezhiMod by Shushang de Jiezhi, used under written permission to license
 * them under GPL-3.0. See AUTHORIZATION.md.
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.tile;

import com.hbmzz.block.BlockConsolePanel;
import com.hbmzz.dfc.ConsoleRig;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;

/**
 * 吊装式工业显示器的公共方块实体：只回答「这台机器现在倾到第几档」。
 *
 * <p>倾角是机器自己的属性，不是格子的属性：一块 2x2 的面板挂在原点格上，四格共用同一个
 * 铰链角度。所以它必须存在方块实体里、跟着存档走、跟着更新包走——存在客户端渲染器里就会
 * 出现"走进区块时面板先立起来再慢慢倒下去"，而存在方块状态里则要为每个朝向乘上五档倾角，
 * 方块状态爆炸成二十个变体，只为了记一个纯外观的数字。</p>
 *
 * <p>倾角只影响渲染，所以它走 {@code SPacketUpdateTileEntity} 这条慢通道就够：放下去那一刻
 * 同步一次，之后不再变。</p>
 */
public abstract class TileEntityPanelScreen extends TileEntity implements ITickable {

    /** 吊装默认 30 度；贴墙放时会被改成 {@link ConsoleRig#TILT_WALL}。 */
    protected int tilt = ConsoleRig.TILT_CEILING;

    public int getTilt() {
        return this.tilt;
    }

    public void setTilt(int tilt) {
        int clamped = ConsoleRig.clampTilt(tilt);
        if (clamped == this.tilt) {
            return;
        }
        this.tilt = clamped;
        markDirty();
        sync();
    }

    /**
     * 包围盒按整台面板放大。
     *
     * <p>不放大就会被剔除掉：方块实体渲染器的可见性跟着原点格那一个方块走，而面板向上、
     * 向右各多占一格，玩家站在侧面看时原点格在视锥外、屏幕却还在视野里，于是整台机器
     * 凭空消失又凭空出现。DFC 监视器早就是这么做的。</p>
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return new AxisAlignedBB(getPos()).grow(
                BlockConsolePanel.PANEL_COLS + 1.0D,
                BlockConsolePanel.PANEL_ROWS + 1.0D,
                BlockConsolePanel.PANEL_COLS + 1.0D);
    }

    protected void sync() {
        if (this.world != null && !this.world.isRemote) {
            this.world.notifyBlockUpdate(this.pos, this.world.getBlockState(this.pos),
                    this.world.getBlockState(this.pos), 3);
        }
    }

    // ------------------------------------------------------------------- nbt

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("tilt", this.tilt);
        return compound;
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.tilt = ConsoleRig.clampTilt(compound.getInteger("tilt"));
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        return writeToNBT(new NBTTagCompound());
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(this.pos, 3, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity packet) {
        readFromNBT(packet.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
                                 net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

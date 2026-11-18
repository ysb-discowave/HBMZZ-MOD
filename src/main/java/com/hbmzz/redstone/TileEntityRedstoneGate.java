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

package com.hbmzz.redstone;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 红石门的方块实体——只干一件事：区块加载后第一 tick，替方块对一次账。
 *
 * <p>门是纯组合逻辑，状态全在方块状态里，正常工作时这个 TE 每 tick 只做一次
 * 布尔判断就返回。它存在的唯一理由是：Forge 不会在区块加载时替方块重算邻居，
 * 存档里存成「亮」的门如果输入的粉已经灭了，光靠 {@code neighborChanged} 是
 * 修不回来的——不会有人来通知它。</p>
 *
 * <p>它还有第二个职责：把「算输出」这件事从 {@code neighborChanged} 里挪到下一个
 * tick。当场算会让 {@code setBlockState} 立刻触发邻居、邻居再触发回来，一条首尾
 * 相接的反馈回路就会在一个 tick 内无限递归——1.12.2 没有链式更新深度上限，结局
 * 是 StackOverflowError。挪到 tick 里之后，每块门一拍延迟，和原版中继器同量级。</p>
 *
 * <p>为什么不用 {@code world.scheduleBlockUpdate}：排定的 tick 会随区块一起存盘，
 * 每次读档都多一条待办；而「加载后对一次账」只该发生一次，放在 TE 里最省心，
 * 也不依赖方块在放置时有没有被正确排程。</p>
 */
public class TileEntityRedstoneGate extends TileEntity implements ITickable {

    /**
     * true = 下个 tick 需要重新对账。
     *
     * <p>构造时就是 true，一次覆盖两个时机：「刚放下」与「刚从存档读出来」。
     * 之后只有邻居变化会把它置回 true。</p>
     */
    private boolean dirty = true;

    /** 方块那边在 {@code neighborChanged} 里调这个，而不是当场算（理由见那边的注释）。 */
    public void requestRefresh() {
        this.dirty = true;
    }

    @Override
    public void onLoad() {
        this.dirty = true;
    }

    @Override
    public void update() {
        if (!this.dirty) {
            return;
        }
        this.dirty = false;
        if (this.world == null || this.world.isRemote) {
            return;
        }
        Block block = this.getBlockType();
        if (block instanceof BlockRedstoneGate) {
            ((BlockRedstoneGate) block).refresh(this.world, this.pos);
        }
    }

    /** 换了方块就重建实体，别把「已经对过账」这件事带到一个新方块上。 */
    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

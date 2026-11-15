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

package com.hbmzz.block;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 一块跨多格挂着的显示屏的「原点格」。
 *
 * <h3>为什么需要这个接口</h3>
 * <p>面板的其余格子都是 {@link BlockDFCMonitorPart}——一块没有方块实体的填充板。它必须能把
 * 自己交还给造它的那台机器：拆一格要拆掉整台，右键一格要和右键原点格一个效果，掉落物要是
 * 那台机器自己的物品，而不是填充板。（填充板没有物品形态，玩家拿到它就等于什么都没有。）</p>
 *
 * <p>这些事以前是写死成 {@code instanceof BlockDFCMonitor} 的，于是「多格面板」这件事只能
 * 由 DFC 监视器独占。核弹监视屏和事故显示器改用同一套吊装外壳之后，填充板必须同时认三种
 * 原点格，而它不该知道具体是哪三种——它只认这个接口。</p>
 *
 * <h3>边界</h3>
 * <p>实现方只回答四个问题：占几格、拆的时候做什么、这一格被右键时该谁处理、拆掉掉什么。
 * 填充板自己不保存任何状态，也不推断自己属于谁——它每一格都现场去问，所以面板被拆掉一半、
 * 存档被外部工具改过、两台面板紧挨着放，都不会留下"我是谁"的悬案。</p>
 */
public interface IPanelOrigin {

    /** 这台面板占几列（沿 {@link com.hbmzz.dfc.ConsoleRig#rightOf} 方向）。 */
    int panelCols(World world, BlockPos origin);

    /** 这台面板占几行（向下）。 */
    int panelRows(World world, BlockPos origin);

    /** 拆掉一整台面板时应掉落的物品。 */
    ItemStack panelDrop();

    /**
     * 把整台面板拆掉：其余格子连同原点格一起清空。
     *
     * <p>由 {@code breakBlock} 调用，因此实现里必须自己防重入——清格子会再次触发
     * {@code breakBlock}。</p>
     */
    void takeDown(World world, BlockPos origin, IBlockState state);

    /**
     * 原点格被右键。
     *
     * <p>签名与 {@code Block#onBlockActivated} 一致，所以实现类不需要额外写胶水方法：
     * 它自己的那个重写就是这份实现。</p>
     */
    boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                             EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ);
}

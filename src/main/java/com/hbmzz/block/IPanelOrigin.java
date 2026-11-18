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
     * 原点格被右键 —— 这件事<b>不在本接口里声明</b>。
     *
     * <p>填充格要转发右键，但只能按 {@code Block#onBlockActivated} 调：reobfJar 会把实现类里
     * 这个重写改名成 {@code func_180639_a}，而接口声明（owner 不是 Block，映射表里没有它）
     * 保留原名，两者对不上，{@code invokeinterface} 会在运行时抛 {@code AbstractMethodError}。
     * 3.1.0–3.5.x 一直是这么写的，症状是"点面板边缘没反应/崩"，而点中间那格正常。</p>
     *
     * <p>所以转发点直接拿 {@code Block} 类型调（见 {@code BlockDFCMonitorPart#onBlockActivated}）——
     * 那正是 {@code BlockGatePart} 从第一天起就在用的写法。</p>
     */
    // 故意不声明 onBlockActivated：声明了就会有人按接口去调，然后踩上面那个坑。
}

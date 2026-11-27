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

package com.hbmzz.rbmk;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;

/**
 * 反应堆残骸：蒸汽爆炸之后留在原地的熔毁本体。
 *
 * <p>基础模组自己的熔毁会把柱子换成它的 {@code pribris} 碎块——那是对的，
 * 但它只在自己那条「高温瞬熔」路径里做。本模组的蒸汽爆炸是另一条路径：
 * 掀盖、顶盖飞起、两次落地。之前这条路径在最后一步才调用基础模组的
 * {@code meltdown}，可那时威力 30 的落地核爆早已把本体炸成空气，能转换的
 * 方块一个不剩——于是玩家看到的就是「本体被顶盖一砸就消失了」。</p>
 *
 * <p>这个方块补的就是那个缺口：掀盖的瞬间就把本体换成它，于是当顶盖在两次
 * 落地后真正砸下来时，底下是一堆实实在在的残骸，而不是一个空坑。落地核爆
 * 仍然会把周围削出一个坑，但残骸的爆炸抗性是黑曜石级别（{@code setResistance}
 * 换算后约 1200），所以砸不碎——顶盖最终是压在残骸上的。</p>
 *
 * <p>它不提供物品、不进创造栏：它是事故留下的东西，不是一种建筑材料。玩家
 * 可以用镐子把它挖掉清场（挖了不掉落，因为它本来就不是可再生的资源）。</p>
 */
public class BlockRbmkWreckage extends Block {

    public BlockRbmkWreckage() {
        super(Material.ROCK);
        setRegistryName("hbmzz", "rbmk_wreckage");
        setUnlocalizedName("hbmzz.rbmk_wreckage");
        // 挖得动（铁镐），但砸不碎：硬度管挖掘，抗爆性管爆炸，两者互不影响。
        setHardness(5.0F);
        // 1.12.2 里 setResistance 实际存的是 x3，getExplosionResistance 再 /5，
        // 所以 2000 落到约 1200，跟黑曜石一个量级。威力 30 的落地核爆远低于它。
        setResistance(2000.0F);
        setSoundType(net.minecraft.block.SoundType.STONE);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return true;
    }

    /**
     * 落地核爆砸不碎它。
     *
     * <p>只靠 {@code setResistance} 已经够（换算是黑曜石级），但不同版本对
     * 爆炸抗性的换算公式并不完全一致，而这里一旦被削掉，顶盖就会砸进空气里、
     * 又变回「本体消失」的老问题。所以直接把这条也钉死：任何爆炸都带不走它。
     * 玩家仍然可以用镐子挖掉（那是挖掘硬度的事，不是爆炸抗性）。</p>
     */
    @Override
    public float getExplosionResistance(net.minecraft.world.World world, net.minecraft.util.math.BlockPos pos,
                                       net.minecraft.entity.Entity exploder, net.minecraft.world.Explosion explosion) {
        return 6000.0F;
    }
}

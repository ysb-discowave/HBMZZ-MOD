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

import com.hbmzz.HBMZZ;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 红石门：一块只能做一种运算的逻辑门。
 *
 * <h3>接线怎么接</h3>
 * <ul>
 *   <li><strong>正面（朝向玩家看的那一面）是输出</strong>，强充能与弱充能都给，
 *       贴着的红石粉、中继器、活板门、活塞都能直接吃。</li>
 *   <li><strong>与 / 或 / 异或：左右两侧是输入</strong>，谁在左边谁在右边不影响
 *       结果（这三种都是对称的）。</li>
 *   <li><strong>非门只有一个输入，在背面</strong>——一条线直接穿过去，符合直觉；
 *       如果做成「侧面进」，接一条线还得先拐个弯。</li>
 * </ul>
 *
 * <h3>为什么输出只朝正面</h3>
 * <p>侧面如果也输出，接在侧面上的那根粉就会反过来给这块门自己供电，形成
 * 「输出喂输入」的自锁。所以 {@link #getWeakPower} / {@link #getStrongPower} 只在
 * {@code side == FACING} 时答 15，其余一律 0。这条约束让门的输入永远不会被自己
 * 的输出污染，接线时也不必担心「线贴着门绕过去」。</p>
 *
 * <h3>为什么还要一个方块实体</h3>
 * <p>门本身没有状态，理论上不需要 TE。但区块加载时 Forge 不会替方块重新算一遍
 * 邻居，一块在存档里存成「亮」的门，读档后如果输入的粉已经灭了，它会一直亮着。
 * 所以留一个只在<strong>加载后第一 tick 对一次账</strong>的 TE，之后每 tick 只做
 * 一次布尔判断，代价可以忽略。</p>
 */
public class BlockRedstoneGate extends BlockHorizontal implements ITileEntityProvider {

    /** 有没有输出。只用来换贴图（亮/灭），红石强度是 15 或 0，由这句话推出。 */
    public static final PropertyBool POWERED = PropertyBool.create("powered");

    private final GateType type;

    public BlockRedstoneGate(GateType type) {
        super(Material.IRON);
        this.type = type;
        setRegistryName(HBMZZ.MODID, type.id());
        setUnlocalizedName(HBMZZ.MODID + "." + type.id());
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(2.0F);
        // 比普通机器结实一点：它多半装在被红石电路围着的地方，旁边经常有 TNT。
        setResistance(14.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(POWERED, Boolean.FALSE));
    }

    public GateType type() {
        return this.type;
    }

    // ------------------------------------------------------------------ state

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, POWERED);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX,
                                            float hitY, float hitZ, int meta, EntityLivingBase placer,
                                            EnumHand hand) {
        // 正面朝向玩家：放下时「看得到符号的那一面」对着自己，符号就是这块门是什么。
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(POWERED) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(POWERED, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityRedstoneGate();
    }

    // ------------------------------------------------------------------ power

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return output(state, side);
    }

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return output(state, side);
    }

    private int output(IBlockState state, EnumFacing side) {
        // 只朝正面。侧面也输出的话，贴在侧面上的粉会反过来给门自己供电。
        return side == state.getValue(FACING) && state.getValue(POWERED) ? 15 : 0;
    }

    /**
     * 读一侧的输入。
     *
     * <p>弱充能与强充能都算：一堵墙背后放着红石块，强充能会穿过来，只读弱充能
     * 就会漏掉。两边取大的那个。</p>
     */
    public int input(World world, BlockPos pos, EnumFacing dir) {
        BlockPos from = pos.offset(dir);
        EnumFacing toward = dir.getOpposite();
        return Math.max(world.getRedstonePower(from, toward), world.getStrongPower(from, toward));
    }

    /** 按当前朝向和输入算一次真值，不写世界。 */
    public boolean compute(World world, BlockPos pos, IBlockState state) {
        EnumFacing front = state.getValue(FACING);
        if (this.type.twoInputs()) {
            int a = input(world, pos, front.rotateY());
            int b = input(world, pos, front.rotateYCCW());
            return this.type.eval(a > 0, b > 0);
        }
        int a = input(world, pos, front.getOpposite());
        return this.type.eval(a > 0, false);
    }

    /**
     * 对一次账：算出来的结果和方块状态不一致才写世界。
     *
     * <p>「不一致才写」不只是省事——每次写都会再触发一圈邻居更新，无条件写就是
     * 让整条线每 tick 抖一次，最后烧掉的是别人的红石电路。</p>
     */
    public void refresh(World world, BlockPos pos) {
        if (world == null || world.isRemote) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockRedstoneGate)) {
            return;
        }
        boolean on = compute(world, pos, state);
        if (state.getValue(POWERED) == on) {
            return;
        }
        world.setBlockState(pos, state.withProperty(POWERED, on), 3);
        // 方块变了邻居会重新取样，但比较器/红石粉读的是「状态变化」，补一次更稳。
        world.notifyNeighborsOfStateChange(pos, state.getBlock(), false);
    }

    /**
     * 邻居变了：只<strong>登记</strong>一次「下个 tick 该对账」，不在这里当场算。
     *
     * <p>这不是为了省事，是为了不被自己的输出绕死。当场算意味着 {@code setBlockState}
     * 会立刻触发邻居的 {@code neighborChanged}，邻居再改、再触发……一条首尾相接的
     * 反馈回路会在一个 tick 内无限递归下去，1.12.2 没有「链式更新深度上限」这种东西
     * （那是 1.19 才加的），结果就是 StackOverflowError 直接崩游戏。</p>
     *
     * <p>代价是每个门一拍延迟，和原版中继器、比较器一样。门是组合逻辑，但「组合」
     * 指的是没有记忆，不是没有延迟——红石本来就没有零延迟的元件。</p>
     */
    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, net.minecraft.block.Block blockIn,
                                BlockPos fromPos) {
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityRedstoneGate) {
            ((TileEntityRedstoneGate) te).requestRefresh();
        } else {
            // 没有 TE（理论上不该发生）：退回当场算，总比永远不更新好。
            refresh(world, pos);
        }
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state, EntityLivingBase placer,
                                ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote) {
            // 放到一根已经通电的线上，放下就该亮。
            refresh(world, pos);
        }
    }

    // ------------------------------------------------------------ interaction

    /**
     * 右键只给读数，不改任何东西。
     *
     * <p>门没有可调的东西——它是哪种门由方块本身决定，改运算要换方块。给一个
     * 「右键切换运算」的菜单反而危险：同一张存档里的门会因为被谁点过而行为不同。</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        EnumFacing front = state.getValue(FACING);
        int a;
        int b = -1;
        if (this.type.twoInputs()) {
            a = input(world, pos, front.rotateY());
            b = input(world, pos, front.rotateYCCW());
        } else {
            a = input(world, pos, front.getOpposite());
        }
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.gate",
                new TextComponentTranslation("tile.hbmzz." + this.type.id() + ".name"),
                String.valueOf(a),
                b < 0 ? "-" : String.valueOf(b),
                String.valueOf(state.getValue(POWERED) ? 15 : 0),
                new TextComponentTranslation(this.type.twoInputs()
                        ? "hbmzz.msg.gate.sides" : "hbmzz.msg.gate.back")));
        return true;
    }
}

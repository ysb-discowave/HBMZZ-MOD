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

package com.hbmzz.blast;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.tile.TileEntityDfcCollapseSensor;
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
 * DFC 坍缩传感器：DFC 一进入熔毁，它就开始输出满格红石。
 *
 * <h3>它和 DFC 爆炸传感器差在哪</h3>
 * <p>爆炸传感器认的是「炸了那一记」，按满格→衰减→归零的包络输出；这块认的是
 * 「正在坍缩」这段<strong>持续状态</strong>——核心一进入倒数就点亮、满格一直挂到
 * 爆炸结束。中间没有衰减：核还在倒数的时候，就该一直满格。判定全部在服务端的
 * {@link DfcCollapseWatch} 里做一次，方块只读那张表，所以装十块和装一块说的是
 * 同一句话：同一个维度、同一批正在坍缩的核心。</p>
 *
 * <h3>为什么是满方块而不是吊装屏</h3>
 * <p>它不是给人看的，是给导线看的。做成 2×2 的屏没有意义：信号只需要两根线，
 * 而设备越小越容易塞进墙里、塞进防爆门旁边的检修槽里。正面那块镜头只是为了让
 * 人一眼看出「这台是传感器」以及「它现在有没有在响」。</p>
 */
public class BlockDfcCollapseSensor extends BlockHorizontal implements ITileEntityProvider {

    /** 有没有输出信号。只用于换模型，红石强度是 0 或满格，见方块实体。 */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockDfcCollapseSensor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "dfc_collapse_sensor");
        setUnlocalizedName(HBMZZ.MODID + ".dfc_collapse_sensor");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        // 比普通机器结实的多：这东西的用处就是在事故里活下来把信号送出去，
        // 一块被自己那次爆炸顺手掀掉的传感器等于没装。当然，正对着爆心的
        // 位置仍然会被抹掉——所以它该装在爆炸半径之外。
        setResistance(90.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(ACTIVE, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, ACTIVE);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(ACTIVE) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(ACTIVE, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityDfcCollapseSensor();
    }

    // ----------------------------------------------------------------- power

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return strengthAt(world, pos);
    }

    /**
     * 同时给强充能。
     *
     * <p>只给弱充能的话，红石粉能点亮，但直接贴在方块另一侧的中继器、活板门、
     * 活塞都读不到——接线的人得先铺一格粉。传感器本来就该「贴哪面哪面通」，
     * 所以两种都答。</p>
     */
    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return strengthAt(world, pos);
    }

    private static int strengthAt(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityDfcCollapseSensor) {
            return ((TileEntityDfcCollapseSensor) te).getStrength();
        }
        return 0;
    }

    // ------------------------------------------------------------ interaction

    /**
     * 右键只报读数，不改任何东西。
     *
     * <p>它没有可调的东西——半径、强度、扫描间隔都在配置里，因为它们是<strong>一次
     * 事故里全厂共用的常数</strong>，不是每块设备各自的偏好。给一块传感器加个菜单，
     * 结果只会是同一个基地里的传感器行为不一致。</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityDfcCollapseSensor)) {
            return true;
        }
        TileEntityDfcCollapseSensor sensor = (TileEntityDfcCollapseSensor) te;
        if (!DfcCollapseWatch.collapsing(world)) {
            player.sendMessage(HBMZZConfig.dfcCollapseSensorRange > 0
                    ? new TextComponentTranslation("hbmzz.msg.dfc_collapse_idle",
                            String.valueOf(HBMZZConfig.dfcCollapseSensorRange))
                    : new TextComponentTranslation("hbmzz.msg.dfc_collapse_idle_any"));
            return true;
        }
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.dfc_collapse",
                String.valueOf(sensor.getStrength())));
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote) {
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }
}

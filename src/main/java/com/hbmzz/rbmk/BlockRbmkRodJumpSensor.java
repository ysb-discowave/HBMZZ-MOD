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

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.tile.TileEntityRbmkRodJumpSensor;
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
 * RBMK 跳棒传感器：反应堆开始跳棒时，它输出红石；封顶停跳时，归零。
 *
 * <p>和爆炸传感器不同，跳棒是<strong>持续一分钟的状态</strong>而不是瞬间：反应堆
 * 从过热进到封顶之间，控制棒反复撞击导槽，整个过程持续。所以在跳棒的整段时间里
 * 这块方块一直输出满格红石，封顶的瞬间（反应堆起爆）才停。判定在服务端的
 * {@link RbmkEventWatch}，由 {@code RbmkSurge} 在状态转换的两个拐点上显式通知。</p>
 */
public class BlockRbmkRodJumpSensor extends BlockHorizontal implements ITileEntityProvider {

    /** 有没有输出信号。只用于换模型，见方块实体。 */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockRbmkRodJumpSensor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "rbmk_rod_jump_sensor");
        setUnlocalizedName(HBMZZ.MODID + ".rbmk_rod_jump_sensor");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
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
        return new TileEntityRbmkRodJumpSensor();
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

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return strengthAt(world, pos);
    }

    private static int strengthAt(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityRbmkRodJumpSensor) {
            return ((TileEntityRbmkRodJumpSensor) te).getStrength();
        }
        return 0;
    }

    // ------------------------------------------------------------ interaction

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityRbmkRodJumpSensor)) {
            return true;
        }
        TileEntityRbmkRodJumpSensor sensor = (TileEntityRbmkRodJumpSensor) te;
        RbmkEventWatch.RodJump rod = RbmkEventWatch.rodJump(world);
        if (rod == null) {
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.rbmk_jump_idle",
                    String.valueOf(HBMZZConfig.rbmkSensorRange)));
            return true;
        }
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.rbmk_jump",
                String.valueOf(sensor.getStrength()),
                String.valueOf(Math.round(rod.distanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)))));
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

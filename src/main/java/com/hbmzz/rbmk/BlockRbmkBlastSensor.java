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
import com.hbmzz.tile.TileEntityRbmkBlastSensor;
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
 * RBMK 爆炸传感器：反应堆核爆时，它开始输出红石。
 *
 * <p>和 DFC 爆炸传感器同一套骨架——判定全在服务端的 {@link RbmkEventWatch}，
 * 方块只读那条记录。差别只在认哪种爆炸：这里认的是反应堆柱子上方的那次核爆，
 * 而不是 DFC 核心。</p>
 */
public class BlockRbmkBlastSensor extends BlockHorizontal implements ITileEntityProvider {

    /** 有没有输出信号。只用于换模型，红石强度是连续的 0..15，见方块实体。 */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockRbmkBlastSensor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "rbmk_blast_sensor");
        setUnlocalizedName(HBMZZ.MODID + ".rbmk_blast_sensor");
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
        return new TileEntityRbmkBlastSensor();
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
        if (te instanceof TileEntityRbmkBlastSensor) {
            return ((TileEntityRbmkBlastSensor) te).getStrength();
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
        if (!(te instanceof TileEntityRbmkBlastSensor)) {
            return true;
        }
        TileEntityRbmkBlastSensor sensor = (TileEntityRbmkBlastSensor) te;
        RbmkEventWatch.Blast blast = RbmkEventWatch.lastBlast(world);
        if (blast == null) {
            player.sendMessage(HBMZZConfig.rbmkSensorRange > 0
                    ? new TextComponentTranslation("hbmzz.msg.rbmk_blast_idle",
                            String.valueOf(HBMZZConfig.rbmkSensorRange))
                    : new TextComponentTranslation("hbmzz.msg.rbmk_blast_idle_any"));
            return true;
        }
        long age = world.getTotalWorldTime() - blast.tick;
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.rbmk_blast",
                String.valueOf(sensor.getStrength()),
                String.valueOf(Math.round(blast.distanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D))),
                String.valueOf(age / 20.0D),
                blast.source));
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

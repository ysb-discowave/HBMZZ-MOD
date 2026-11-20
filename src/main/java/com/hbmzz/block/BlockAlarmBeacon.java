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

package com.hbmzz.block;

import com.hbmzz.HBMZZ;
import com.hbmzz.tile.TileEntityAlarmBeacon;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 代码旋转警报灯: the alarm that turns.
 *
 * <p>Like the status lamp it is a view, not a source - no preset, no input. What
 * decides whether it spins is a single number: the facility's own safe level
 * ({@code alertSafeLevel}). When the active code climbs above that level the
 * beacon lights up in the facility colour and spins; when the code drops back to
 * or below it the beacon goes dark. The lamp says <em>what colour</em>; this one
 * says <em>how bad</em>, in the one thing a facility can actually see from far
 * away - something moving.</p>
 */
public class BlockAlarmBeacon extends BlockHorizontal implements ITileEntityProvider {

    public BlockAlarmBeacon() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "alarm_beacon");
        setUnlocalizedName(HBMZZ.MODID + ".alarm_beacon");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(2.0F);
        setResistance(5.0F);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityAlarmBeacon();
    }
}

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
import com.hbmzz.tile.TileEntityStatusLamp;
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
 * 状态提示灯: a wall lamp that mirrors the facility colour.
 *
 * <p>The lamp is a view, not a source: it has no preset and no input. Whatever
 * code the facility is on - a standard one or a custom one - is the colour it
 * glows. A hundred lamps read the same colour at once because they all read the
 * same record through the renderer, exactly the way the displays do.</p>
 */
public class BlockStatusLamp extends BlockHorizontal implements ITileEntityProvider {

    public BlockStatusLamp() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "status_lamp");
        setUnlocalizedName(HBMZZ.MODID + ".status_lamp");
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
        return new TileEntityStatusLamp();
    }
}

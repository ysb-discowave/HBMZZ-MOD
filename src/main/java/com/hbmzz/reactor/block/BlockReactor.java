/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free
 * Software Foundation, version 3.
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

package com.hbmzz.reactor.block;

import com.hbmzz.HBMZZ;
import com.hbmzz.reactor.tile.TileEntityReactor;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The reactor control block: a wall plate backed by a small HBMZZ reactor core.
 *
 * <p>The visual is intentionally borrowed from the existing control panel -
 * the same model and textures, no new art - so the block reads as part of the
 * same machine family and cannot arrive purple-and-black in a pack that has not
 * been rebuilt. The physics behind it is entirely new and lives in
 * {@code com.hbmzz.reactor}.</p>
 *
 * <p>Four redstone faces make it a self-contained control loop: rods on the
 * back, coolant on the left, start/scram on the right, power out the front. See
 * {@link TileEntityReactor} for the exact contract.</p>
 */
public class BlockReactor extends BlockHorizontal implements ITileEntityProvider {

    public static final PropertyDirection FACING = BlockHorizontal.FACING;

    public BlockReactor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "reactor");
        setUnlocalizedName(HBMZZ.MODID + ".reactor");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(5.0F);
        setResistance(20.0F);
        setLightOpacity(0);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                            float hitX, float hitY, float hitZ, int meta,
                                            EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta));
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityReactor();
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    /** Power is emitted from the front face only. */
    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState state, net.minecraft.world.IBlockAccess world,
                            BlockPos pos, EnumFacing side) {
        if (side != state.getValue(FACING)) {
            return 0;
        }
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileEntityReactor
                ? ((TileEntityReactor) te).output().redstoneLevel() : 0;
    }

    @Override
    public int getStrongPower(IBlockState state, net.minecraft.world.IBlockAccess world,
                              BlockPos pos, EnumFacing side) {
        return getWeakPower(state, world, pos, side);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state,
                                    EntityPlayer player, EnumHand hand, EnumFacing facing,
                                    float hitX, float hitY, float hitZ) {
        // Right-click reports status in chat. A full GUI would add a packet and
        // a container; the four redstone faces are the real control surface.
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityReactor) {
            TileEntityReactor reactor = (TileEntityReactor) te;
            player.sendMessage(new net.minecraft.util.text.TextComponentString(
                    "HBMZZ Reactor: " + reactor.statusKey()
                            + " | flux=" + String.format("%.0f", reactor.output().flux)
                            + " heat=" + String.format("%.0f", reactor.output().heat)
                            + " out=" + String.format("%.0f", reactor.output().power)));
        }
        return true;
    }
}

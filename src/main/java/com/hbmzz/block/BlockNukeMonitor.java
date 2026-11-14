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

import com.hbmzz.HBMZZ;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.NukeDataMessage;
import com.hbmzz.tile.TileEntityNukeMonitor;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 核弹监视屏: a one-cell wall screen that reads one device.
 *
 * <p>Single cell rather than the DFC monitor's multi-cell wall. The numbers a
 * bomb produces fit in one panel - state, yield, fuse, countdown, the controller
 * it is wired to, and the facility code - and a wall-sized screen for six lines
 * is a screen that spends most of its area on nothing. Placement, mounting and
 * the housing are the same hardware as the DFC monitor, so the two look like
 * they were bought from the same supplier.</p>
 *
 * <p>Bound with the wooden axe, the same click that binds the controller. A
 * monitor with nothing bound tells the player how to bind it instead of showing
 * a blank face.</p>
 */
public class BlockNukeMonitor extends BlockHorizontal implements ITileEntityProvider {

    private static final double HALF_THICK = 2.0D / 16.0D;

    public BlockNukeMonitor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "nuke_monitor");
        setUnlocalizedName(HBMZZ.MODID + ".nuke_monitor");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        setResistance(20.0F);
        setLightLevel(0.25F);
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
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta));
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNukeMonitor();
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    private static AxisAlignedBB plateBox(EnumFacing facing) {
        switch (facing) {
            case EAST:
            case WEST:
                return new AxisAlignedBB(0.5D - HALF_THICK, 0.0D, 0.0D,
                        0.5D + HALF_THICK, 1.0D, 1.0D);
            case SOUTH:
            case NORTH:
            default:
                return new AxisAlignedBB(0.0D, 0.0D, 0.5D - HALF_THICK,
                        1.0D, 1.0D, 0.5D + HALF_THICK);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return plateBox(state.getValue(FACING));
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return plateBox(state.getValue(FACING));
    }

    /**
     * Right-click: push a fresh sample to this player.
     *
     * <p>The screen updates by itself every display interval; the click exists
     * so that a player who has just bound a device does not have to wait for the
     * next poll to see whether it worked.</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityNukeMonitor && player instanceof EntityPlayerMP) {
            NetworkHandler.sendTo((EntityPlayerMP) player,
                    new NukeDataMessage(pos, ((TileEntityNukeMonitor) te).snapshot()));
        }
        return true;
    }
}

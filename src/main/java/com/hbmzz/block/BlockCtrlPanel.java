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
import com.hbmzz.tile.TileEntityCtrlPanel;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The machine control panel: a plate you bolt to a wall, right-click to open,
 * and wire to the machines around it.
 *
 * <p>Unlike the DFC monitor there is no tile entity renderer here. The monitor
 * needs one because it is a big angled console whose content changes every
 * frame; this is a 16x16 plate whose text is too small to read from two blocks
 * away anyway, so the honest thing is a plain textured block and a real window
 * when you open it. That keeps the in-world cost at one block model and puts
 * every pixel of the actual panel where the player can read it.</p>
 */
public class BlockCtrlPanel extends BlockHorizontal implements ITileEntityProvider {

    /** Plate thickness, in blocks. Matches the model's 3/16 element exactly. */
    private static final double THICK = 3.0D / 16.0D;

    public BlockCtrlPanel() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "control_panel");
        setUnlocalizedName(HBMZZ.MODID + ".control_panel");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        setResistance(12.0F);
        // A 3/16 plate must not cast a full block of shadow, or every panel
        // leaves a black square on the wall behind it.
        setLightOpacity(0);
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
        return new TileEntityCtrlPanel();
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
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
     * The plate sits against the side the panel's face points away from - a
     * control panel bolted to a wall, not a slab floating in the middle of a
     * cube. The direction mapping is the same one the blockstate rotations use,
     * so the hitbox and the model cannot disagree.
     */
    private static AxisAlignedBB plateBox(EnumFacing facing) {
        switch (facing) {
            case NORTH:
                return new AxisAlignedBB(0.0D, 0.0D, 1.0D - THICK, 1.0D, 1.0D, 1.0D);
            case WEST:
                return new AxisAlignedBB(1.0D - THICK, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
            case EAST:
                return new AxisAlignedBB(0.0D, 0.0D, 0.0D, THICK, 1.0D, 1.0D);
            case SOUTH:
            default:
                return new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, THICK);
        }
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            HBMZZ.proxy.openControlPanel(pos);
            return true;
        }
        // Push a fresh copy down on open: the client may have been out of range
        // while a rule was moving rods, and the window should not open on
        // yesterday's numbers.
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityCtrlPanel) {
            world.notifyBlockUpdate(pos, state, state, 3);
        }
        return true;
    }
}

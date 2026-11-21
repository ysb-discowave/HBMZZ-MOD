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

package com.hbmzz.reactor.block;

import com.hbmzz.HBMZZ;
import com.hbmzz.reactor.tile.TileEntityReactorCore;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyDirection;
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
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The multi-block reactor core: the one block of the assembled reactor that
 * actually does anything.
 *
 * <p>It is the authoritative block of a rig the player lays down by hand.
 * Around it sit the functional components - emitters, absorbers, stabilisers
 * and control rods - which the core scans every tick and folds into its
 * physics. The visual is borrowed from the control panel: the same model and
 * textures, no new art, so the core cannot arrive purple-and-black in a pack
 * that has not been rebuilt.</p>
 *
 * <p>Everything it computes is a {@code com.hbmzz.reactor} value: the
 * capability spec comes from {@code ReactorCoreMath.specFor}, the control-rod
 * depth from the rods the player placed, and the tick from the shared
 * {@code ReactorMachine}. The block itself only knows how to stand in the
 * world and report its power out the front face.</p>
 */
public class BlockReactorCore extends BlockHorizontal implements ITileEntityProvider {

    public static final PropertyDirection FACING = BlockHorizontal.FACING;

    public BlockReactorCore() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "reactor_core");
        setUnlocalizedName(HBMZZ.MODID + ".reactor_core");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(6.0F);
        setResistance(24.0F);
        setLightLevel(0.15F);
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
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityReactorCore();
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
        return te instanceof TileEntityReactorCore
                ? ((TileEntityReactorCore) te).redstoneOutput() : 0;
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
        // Right-click reports status in chat. The machine control panel - and
        // the redstone feeding the components - is the real control surface.
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityReactorCore) {
            TileEntityReactorCore core = (TileEntityReactorCore) te;
            player.sendMessage(new net.minecraft.util.text.TextComponentString(
                    "HBMZZ Core: " + core.statusKey()
                            + " | flux=" + String.format("%.0f", core.flux())
                            + " heat=" + String.format("%.0f", core.heat())
                            + " out=" + String.format("%.0f", core.power())));
        }
        return true;
    }

    @Override
    public java.util.List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState state,
                                               int fortune) {
        java.util.List<ItemStack> drops = new java.util.ArrayList<ItemStack>();
        // Breaking the core never drops the components around it: each of those
        // is its own block with its own item, and the rig must not multiply.
        return drops.isEmpty() ? super.getDrops(world, pos, state, fortune) : drops;
    }
}

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
import com.hbmzz.HBMZZConfig;
import com.hbmzz.alert.FacilityState;
import com.hbmzz.alert.NukeRegistry;
import com.hbmzz.tile.TileEntityNuke;
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
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 核弹: a rectangular casing that sits on the floor and ends the map.
 *
 * <p>One per world, enforced in {@link NukeRegistry}. The block asks the registry
 * twice - once in {@link #canPlaceBlockAt} so a placement that cannot succeed
 * does not create and immediately destroy a tile entity, and once in the item so
 * the player gets told why instead of watching the block snap back into their
 * hand.</p>
 *
 * <h3>Two model states, one meaning</h3>
 * <p>{@link #PRIMED} is not "armed". It is the only thing a player with no
 * monitor can read from across the yard: the lamp on the detonator box goes red
 * the moment the fuse starts, so a bomb that is counting down looks different
 * from a bomb that is furniture. The state is set by the tile entity, not by the
 * block, because the fuse is the tile entity's business.</p>
 *
 * <p>Nothing here explodes. A block that both owns a sequence and performs it is
 * a block that cannot be primed by a controller in another dimension, which is
 * exactly what the signal controller is for.</p>
 */
public class BlockNuke extends BlockHorizontal implements ITileEntityProvider {

    public static final PropertyBool PRIMED = PropertyBool.create("primed");

    /** Half-width of the casing, in blocks: the model is 10/16 across. */
    private static final double SIDE = 3.0D / 16.0D;

    public BlockNuke() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "nuke");
        setUnlocalizedName(HBMZZ.MODID + ".nuke");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(8.0F);
        // Blast resistant on purpose: a bomb that a stray creeper can remove is
        // not a facility asset, and the resistance is also what keeps the casing
        // from being a weak point in the crater.
        setResistance(600.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(PRIMED, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, PRIMED);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(PRIMED) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(PRIMED, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNuke();
    }

    /** The casing, not a cube: 10 wide, 9 tall, the full cell long. */
    private static AxisAlignedBB casingBox(EnumFacing facing) {
        switch (facing) {
            case EAST:
            case WEST:
                return new AxisAlignedBB(0.0D, 0.0D, SIDE, 1.0D, 9.0D / 16.0D, 1.0D - SIDE);
            case SOUTH:
            case NORTH:
            default:
                return new AxisAlignedBB(SIDE, 0.0D, 0.0D, 1.0D - SIDE, 9.0D / 16.0D, 1.0D);
        }
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return casingBox(state.getValue(FACING));
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return casingBox(state.getValue(FACING));
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    /**
     * Refuses a second bomb.
     *
     * <p>Called before the block exists, so a refusal costs nothing. The message
     * is the item's job - by this point there is no block to talk through.</p>
     */
    @Override
    public boolean canPlaceBlockAt(World world, BlockPos pos) {
        if (world == null || world.isRemote) {
            return true;
        }
        NukeRegistry registry = NukeRegistry.get(world);
        return registry == null || !registry.blocks(world, pos);
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote) {
            return;
        }
        NukeRegistry registry = NukeRegistry.get(world);
        if (registry != null) {
            registry.claim(pos, world.provider.getDimension());
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityNuke) {
            TileEntityNuke nuke = (TileEntityNuke) te;
            // Copied at placement rather than read live: an operator lowering the
            // yield in the config should not silently disarm the bomb that is
            // already standing in the silo.
            nuke.setYield(HBMZZConfig.nukeYieldKt);
            nuke.setFuseTicks(HBMZZConfig.nukeFuseTicks);
        }
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote) {
            NukeRegistry registry = NukeRegistry.get(world);
            if (registry != null && pos.equals(registry.pos())) {
                registry.release();
            }
        }
        super.breakBlock(world, pos, state);
    }

    /**
     * Right-click with a hand: read the device out.
     *
     * <p>The same numbers a monitor shows, in chat, because the person who just
     * placed the block is usually the person who has not built the monitor yet.</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityNuke)) {
            return true;
        }
        TileEntityNuke nuke = (TileEntityNuke) te;
        FacilityState facility = FacilityState.get(world);
        String code = facility == null ? "GREEN" : facility.alert().label();
        String controller = nuke.getControllerPos() == null ? "-"
                : nuke.getControllerPos().getX() + " " + nuke.getControllerPos().getY() + " "
                + nuke.getControllerPos().getZ();
        String stateText = nuke.isCountingDown()
                ? "COUNTDOWN " + String.format(java.util.Locale.ROOT, "%.1f", nuke.getCountdown() / 20.0D) + "s"
                : "IDLE";
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_status",
                stateText, nuke.getYield(), String.format(java.util.Locale.ROOT, "%.1f", nuke.getFuseTicks() / 20.0D),
                controller, code));
        return true;
    }
}

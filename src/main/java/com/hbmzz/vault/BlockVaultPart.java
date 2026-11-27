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

package com.hbmzz.vault;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

/**
 * One of the leaf cells that is not the corner.
 *
 * <p>Like {@code BlockGatePart}, it carries the same {@code open} state as the
 * corner cell but no tile entity: the door is one object and the state lives in
 * the blocks, so thirty copies of "am I open" would be thirty chances to
 * disagree. These cells render nothing (the corner's tile entity renderer draws
 * the whole leaf as one piece) and only forward clicks to the corner, and hand
 * the teardown over when broken.</p>
 *
 * <p>The collision box follows {@code open} exactly as the corner's does: shut
 * the cell is solid and keeps the door a wall, open it is gone and the leaf's
 * gap is a gap you can walk through.</p>
 */
public class BlockVaultPart extends Block {

    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create("facing",
            EnumFacing.class, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST);
    public static final PropertyBool OPEN = PropertyBool.create("open");

    public BlockVaultPart() {
        super(Material.IRON);
        setRegistryName("hbmzz", "vault_door_part");
        setUnlocalizedName("hbmzz.vault_door_part");
        setHardness(50.0F);
        setResistance(6000.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(OPEN, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, OPEN);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(OPEN) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(OPEN, (meta & 4) != 0);
    }

    // ------------------------------------------------------------- appearance

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return false;
    }

    @Override
    @Nullable
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.getValue(OPEN) ? null : FULL_BLOCK_AABB;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return FULL_BLOCK_AABB;
    }

    /** Selectable whether open or shut: the crosshair aims at the whole cell. */
    @Override
    public net.minecraft.util.math.RayTraceResult collisionRayTrace(
            IBlockState state, World world, BlockPos pos,
            net.minecraft.util.math.Vec3d start, net.minecraft.util.math.Vec3d end) {
        return rayTrace(pos, start, end, FULL_BLOCK_AABB);
    }

    // ------------------------------------------------------------------ using

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        BlockPos origin = BlockVault.findOrigin(world, pos);
        if (origin == null) {
            return false;
        }
        IBlockState originState = world.getBlockState(origin);
        if (!(originState.getBlock() instanceof BlockVault)) {
            return false;
        }
        return originState.getBlock().onBlockActivated(world, origin, originState, player, hand,
                facing, hitX, hitY, hitZ);
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !BlockVault.DISMANTLING) {
            BlockPos origin = BlockVault.findOrigin(world, pos);
            if (origin != null) {
                EnumFacing facing = BlockVault.facingOf(world, origin);
                // No item of ours exists; the corner hands the player the door,
                // dropped here rather than six blocks away at the corner.
                BlockVault.takeDown(world, origin, facing, pos, true);
            }
        }
        super.breakBlock(world, pos, state);
    }

    @Override
    public Item getItemDropped(IBlockState state, java.util.Random rand, int fortune) {
        return net.minecraft.init.Items.AIR;
    }

    @Override
    public List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        // The corner's teardown spawns exactly one item. Dropping here as well
        // would turn a thirty-cell door into thirty doors.
        return Collections.emptyList();
    }
}

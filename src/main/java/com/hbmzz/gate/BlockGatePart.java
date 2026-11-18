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

package com.hbmzz.gate;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
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
 * One of the eight door cells that is not the corner.
 *
 * <p>It carries the same {@code open} state and the same door-panel model as the
 * corner cell, just without a tile entity: the door is one object and the state
 * lives in the blocks, so nine copies of "am I open" is nine chances to
 * disagree. These cells only forward clicks and redstone to the corner, and
 * hand the teardown over when broken.</p>
 *
 * <p>Textures are the wall door's own panel: {@code rsaudio:blocks/door_panel}
 * on the face that points up, {@code door_side} around it, {@code door_bottom}
 * underneath. The panel is the same panel; only which way it faces is different.</p>
 */
public class BlockGatePart extends Block {

    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create("facing",
            EnumFacing.class, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST);
    public static final PropertyBool OPEN = PropertyBool.create("open");

    public BlockGatePart() {
        super(Material.IRON);
        setRegistryName("hbmzz", "tartarus_gate_part");
        setUnlocalizedName("hbmzz.tartarus_gate_part");
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

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return !state.getValue(OPEN);
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

    /**
     * Selectable even when open.
     *
     * <p>An open door has no collision box, and the default ray trace only ever
     * hits one. That makes the door impossible to close by hand: every click
     * goes through the hole to whatever is at the bottom of the shaft. Keeping
     * a full-cube <em>selection</em> box while the collision box stays empty
     * gives the player something to aim at without giving them a floor to stand
     * on - the two boxes answer different questions, and this is the one the
     * crosshair asks.</p>
     */
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
        BlockPos origin = BlockTartarusGate.findOrigin(world, pos);
        if (origin == null) {
            return false;
        }
        IBlockState originState = world.getBlockState(origin);
        if (!(originState.getBlock() instanceof BlockTartarusGate)) {
            return false;
        }
        return originState.getBlock().onBlockActivated(world, origin, originState, player, hand,
                facing, hitX, hitY, hitZ);
    }

    @Override
    public void neighborChanged(IBlockState state, World world, BlockPos pos, Block blockIn, BlockPos fromPos) {
        if (world.isRemote) {
            return;
        }
        BlockPos origin = BlockTartarusGate.findOrigin(world, pos);
        if (origin != null) {
            BlockTartarusGate.updateSignal(world, origin);
        }
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !BlockTartarusGate.DISMANTLING) {
            BlockPos origin = BlockTartarusGate.findOrigin(world, pos);
            if (origin != null) {
                // No item of ours exists; the corner hands the player the door,
                // dropped here rather than 25 blocks away at the corner.
                BlockTartarusGate.takeDown(world, origin, pos, true);
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
        // would turn a nine-cell door into nine doors.
        return Collections.emptyList();
    }
}

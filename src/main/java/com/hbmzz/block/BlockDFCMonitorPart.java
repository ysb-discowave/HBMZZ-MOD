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

import com.hbmzz.ModContent;
import com.hbmzz.dfc.ConsoleRig;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Filler cell of a wall screen: every cell of the panel except the controller.
 *
 * <h3>Why this exists at all</h3>
 * <p>The obvious design is "twelve identical monitor blocks, and the renderer
 * skips the ones a neighbour already covers". That is what this mod did, and it
 * is a trap: every cell then owns a tile entity that wants to paint a screen,
 * so correctness depends entirely on a de-duplication test. Get that test
 * slightly wrong - look for the neighbour on the wrong side, or compare against
 * a stale size - and either the panel is drawn twelve times at twelve offsets,
 * or every cell decides somebody else owns it and the whole thing renders as
 * air.</p>
 *
 * <p>A cell with no tile entity cannot paint anything. Only the controller has
 * one, so the console is drawn exactly once by construction, and the
 * de-duplication test stops being load-bearing.</p>
 *
 * <p>This block still carries three jobs: it gives the panel a collidable
 * surface, it records the facing so a broken cell can walk back to the
 * controller, and it hands the whole structure to the controller's teardown
 * when broken.</p>
 */
public class BlockDFCMonitorPart extends Block {

    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create("facing",
            EnumFacing.class, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST);

    /** Same plate the controller uses; see {@link BlockDFCMonitor}. */
    private static final double HALF_THICK = 2.0D / 16.0D;

    public BlockDFCMonitorPart() {
        super(Material.IRON);
        setRegistryName("hbmzz", "dfc_monitor_part");
        setUnlocalizedName("hbmzz.dfc_monitor_part");
        setHardness(4.0F);
        setResistance(12.0F);
        setLightLevel(0.25F);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // ------------------------------------------------------------------ state

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    // ------------------------------------------------------------- appearance

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

    @Override
    public boolean isNormalCube(IBlockState state, IBlockAccess world, BlockPos pos) {
        return false;
    }

    static AxisAlignedBB plateBox(EnumFacing facing) {
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

    // ------------------------------------------------------------------ using

    /**
     * A cell is part of the screen, so right-clicking it has to behave exactly
     * like right-clicking the controller. The middle of a 4x3 panel is almost
     * never the controller cell, so routing only the controller would make
     * "open the terminal" fail at the most natural place to click.
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        BlockPos origin = findOrigin(world, pos);
        if (origin == null) {
            return false;
        }
        IBlockState originState = world.getBlockState(origin);
        Block block = originState.getBlock();
        if (!(block instanceof IPanelOrigin)) {
            return false;
        }
        return ((IPanelOrigin) block).onBlockActivated(world, origin, originState, player, hand,
                facing, hitX, hitY, hitZ);
    }

    // --------------------------------------------------------------- teardown

    /** Reentrancy guard: clearing the cells must not start another teardown. */
    static boolean DISMANTLING = false;

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !DISMANTLING) {
            BlockPos origin = findOrigin(world, pos);
            if (origin != null) {
                IBlockState originState = world.getBlockState(origin);
                Block block = originState.getBlock();
                if (block instanceof IPanelOrigin) {
                    ((IPanelOrigin) block).takeDown(world, origin, originState);
                }
            }
        }
        super.breakBlock(world, pos, state);
    }

    /**
     * Breaks start at whichever cell the player hit, so walk back to the
     * controller. The scan is bounded by the configured maximum, not by any one
     * cell's idea of its size: a stale cell in an old save may think it is 2x2
     * while the panel around it has grown to 4x3.
     */
    public static BlockPos findOrigin(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockDFCMonitorPart)) {
            return null;
        }
        EnumFacing facing = state.getValue(FACING);
        int maxCols = Math.max(1, com.hbmzz.HBMZZConfig.maxScreenCols);
        int maxRows = Math.max(1, com.hbmzz.HBMZZConfig.maxScreenRows);
        for (int back = 0; back < maxCols; back++) {
            for (int up = 0; up < maxRows; up++) {
                BlockPos candidate = ConsoleRig.partPos(pos, -back, -up, facing);
                // isBlockLoaded is not an optimisation here: getBlockState on an
                // unloaded chunk loads it, and this scan runs on every break of a
                // panel cell - 12 cells at a time, at the edge of the loaded area.
                if (candidate == null || !world.isBlockLoaded(candidate)) {
                    continue;
                }
                Block block = world.getBlockState(candidate).getBlock();
                if (!(block instanceof IPanelOrigin)) {
                    continue;
                }
                IPanelOrigin origin = (IPanelOrigin) block;
                // Confirm the candidate actually claims this cell; otherwise a
                // neighbouring console would adopt us - and two 2x2 panels hung
                // side by side is a legal layout.
                int cols = Math.max(1, origin.panelCols(world, candidate));
                int rows = Math.max(1, origin.panelRows(world, candidate));
                if (ConsoleRig.covers(candidate, cols, rows, facing, pos)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * Breaking any cell yields the panel's own item. The origin and the other
     * cells are removed by {@code setBlockToAir}, which does not drop, so one
     * panel can never turn into four items.
     *
     * <p>Which item that is comes from the origin block itself, not from a
     * constant: the same filler cell serves the DFC monitor, the nuke monitor
     * and the alert display, and a cell that guessed would eventually hand a
     * player the wrong machine.</p>
     */
    @Override
    public int quantityDropped(Random random) {
        return 1;
    }

    /**
     * No item form: this block only ever exists as part of a placed panel. It
     * is deliberately not registered as an {@code ItemBlock}, so it cannot be
     * obtained, held or placed on its own.
     *
     * <p>{@code getItemDropped} keeps pointing at the DFC monitor for the
     * legacy path vanilla takes when it ignores {@code getDrops}; the path that
     * actually runs is this one, and it asks the origin what it was.</p>
     */
    @Override
    public Item getItemDropped(IBlockState state, Random rand, int fortune) {
        return Item.getItemFromBlock(ModContent.BLOCK_MONITOR);
    }

    @Override
    public java.util.List<ItemStack> getDrops(IBlockAccess world, BlockPos pos, IBlockState state,
                                              int fortune) {
        java.util.List<ItemStack> drops = new java.util.ArrayList<ItemStack>();
        if (!(world instanceof World)) {
            return drops;
        }
        BlockPos origin = findOrigin((World) world, pos);
        if (origin == null) {
            return drops;
        }
        Block block = world.getBlockState(origin).getBlock();
        if (block instanceof IPanelOrigin) {
            ItemStack drop = ((IPanelOrigin) block).panelDrop();
            if (drop != null && !drop.isEmpty()) {
                drops.add(drop);
            }
        }
        return drops;
    }
}

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
import com.hbmzz.ModContent;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.tile.TileEntityVideoScreen;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Collections;
import java.util.List;

/**
 * 视频大屏: the wall a broadcaster's clip is drawn on.
 *
 * <h3>One block, two roles</h3>
 * <p>The controller cell (the one the player placed) owns the tile entity and is
 * the only cell the renderer draws from. Every other cell of the wall is the same
 * block with {@link #PART} set, and has no tile entity at all - a cell that
 * cannot paint cannot paint twice. That removes the whole class of bugs where a
 * wall of identical controllers each decide a neighbour already drew the picture
 * and the screen renders as air.</p>
 *
 * <h3>Geometry comes from the console rig</h3>
 * <p>Cell layout and the renderer's local frame both go through
 * {@link ConsoleRig}: {@code rightOf(facing)} for column order,
 * {@code anchor(cols, rows, facing)} for the panel centre, {@code yawOf(facing)}
 * for the rotation and {@code panelUv()} for the image orientation. Those four
 * already encode "which way is the viewer's right", and the picture's left edge
 * lands on the viewer's left because of it. Doing this arithmetic locally is how
 * a screen ends up mirrored on west/east facings and upside down on the others.</p>
 *
 * <h3>Size</h3>
 * <p>Set from the configuration window (sneak + right-click), separately in width
 * and height, up to {@link TileEntityVideoScreen#MAX_SIZE} square. Growing places
 * the new cells, shrinking removes only cells this block owns.</p>
 */
public class BlockVideoScreen extends BlockHorizontal implements ITileEntityProvider {

    /** True on every cell of the wall except the controller. */
    public static final PropertyBool PART = PropertyBool.create("part");

    /** Reentrancy guard for {@link #takeDown(World, BlockPos, IBlockState)}. */
    private static boolean DISMANTLING;

    public BlockVideoScreen() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "video_screen");
        setUnlocalizedName(HBMZZ.MODID + ".video_screen");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(2.5F);
        setResistance(8.0F);
        setLightLevel(0.15F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(PART, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, PART);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        // The screen looks at whoever placed it: same convention as every other
        // panel in this mod, so a wall built from the front faces the builder.
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite())
                .withProperty(PART, Boolean.FALSE);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(PART) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(PART, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return (meta & 4) != 0 ? null : new TileEntityVideoScreen();
    }

    /**
     * Only the controller owns a tile entity.
     *
     * <p>Stated explicitly rather than left to the meta test in
     * {@link #createNewTileEntity}: the chunk decides whether to create one by
     * asking this method, and a part cell that quietly got a tile entity would be
     * a second renderer for the same picture.</p>
     */
    @Override
    public boolean hasTileEntity(IBlockState state) {
        return !state.getValue(PART);
    }

    // ------------------------------------------------------------------ using

    /**
     * Sneak + right-click opens the size / speed window; a plain click does
     * nothing at all.
     *
     * <p>The remote is declined first: {@code Item.onItemUse} only runs after the
     * block says no, so a block that claims every click would make the remote
     * dead the moment it is aimed at a screen - which is the one thing it exists
     * for.</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (player.getHeldItem(hand).getItem() instanceof com.hbmzz.item.ItemVideoRemote) {
            return false;
        }
        BlockPos origin = state.getValue(PART) ? originOf(world, pos) : pos;
        if (origin == null) {
            return false;
        }
        if (world.isRemote) {
            if (player.isSneaking()) {
                HBMZZ.proxy.openVideoScreenConfig(origin);
            }
            return true;
        }
        world.notifyBlockUpdate(origin, world.getBlockState(origin), world.getBlockState(origin), 3);
        return true;
    }

    // ------------------------------------------------------------- placement

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityVideoScreen) {
            TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
            ensureParts(world, pos, state.getValue(FACING), screen.getCols(), screen.getRows());
        }
    }

    /**
     * Turns every cell the wall claims into a part cell.
     *
     * <p>Cells that are already occupied by something else are skipped, not
     * refused: a wall that grows into a wall of stone has a hole in it, which is
     * visible and fixable, while refusing the resize would silently keep the old
     * size and leave the player with a window that does nothing.</p>
     */
    public static void ensureParts(World world, BlockPos origin, EnumFacing facing, int cols, int rows) {
        if (world.isRemote) {
            return;
        }
        IBlockState part = ModContent.BLOCK_VIDEO_SCREEN.getDefaultState()
                .withProperty(FACING, facing)
                .withProperty(PART, Boolean.TRUE);
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                if (col == 0 && row == 0) {
                    continue;
                }
                BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                if (cell == null || !world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState existing = world.getBlockState(cell);
                if (existing.getBlock() == ModContent.BLOCK_VIDEO_SCREEN) {
                    if (existing.getValue(FACING) != facing || !existing.getValue(PART)) {
                        world.setBlockState(cell, part, 3);
                    }
                    continue;
                }
                if (existing.getBlock().isReplaceable(world, cell)) {
                    world.setBlockState(cell, part, 2);
                }
            }
        }
    }

    /**
     * Removes the part cells that fall outside a new, smaller size.
     *
     * <p>Only cells of this wall are touched: a shrinking screen that ate the
     * player's chest because it happened to stand where the wall used to be would
     * be the worst kind of bug to explain.</p>
     */
    public static void trimParts(World world, BlockPos origin, EnumFacing facing, int cols, int rows) {
        if (world.isRemote) {
            return;
        }
        for (int col = 0; col < TileEntityVideoScreen.MAX_SIZE; col++) {
            for (int row = 0; row < TileEntityVideoScreen.MAX_SIZE; row++) {
                if (col < cols && row < rows) {
                    continue;
                }
                BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                if (cell == null || !world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState existing = world.getBlockState(cell);
                if (existing.getBlock() == ModContent.BLOCK_VIDEO_SCREEN && existing.getValue(PART)) {
                    world.setBlockToAir(cell);
                }
            }
        }
    }

    // -------------------------------------------------------------- teardown

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        if (!world.isRemote && !DISMANTLING) {
            BlockPos origin = state.getValue(PART) ? originOf(world, pos) : pos;
            if (origin != null && origin.equals(pos)) {
                takeDown(world, pos, state, pos);
            } else if (origin != null) {
                takeDown(world, origin, world.getBlockState(origin), pos);
            } else if (state.getValue(PART)) {
                // An orphan cell: its controller is gone, so nothing else will
                // ever clean it up.
                world.setBlockToAir(pos);
            }
        }
        super.breakBlock(world, pos, state);
    }

    /**
     * Removes every cell of the wall.
     *
     * <p>The part cells and the controller both disappear, but exactly one item
     * is spawned, at the cell the player actually broke. {@code setBlockToAir}
     * drops nothing, which is why {@link #getDrops} is empty: the drop is placed
     * here by hand so a 4x3 wall cannot become twelve screens.</p>
     */
    private static void takeDown(World world, BlockPos origin, IBlockState state, BlockPos dropAt) {
        if (world.isRemote || DISMANTLING) {
            return;
        }
        DISMANTLING = true;
        try {
            EnumFacing facing = state.getBlock() instanceof BlockVideoScreen
                    ? state.getValue(FACING) : EnumFacing.NORTH;
            int cols = 1;
            int rows = 1;
            TileEntity te = world.getTileEntity(origin);
            boolean haveSize = te instanceof TileEntityVideoScreen;
            if (haveSize) {
                cols = Math.max(1, ((TileEntityVideoScreen) te).getCols());
                rows = Math.max(1, ((TileEntityVideoScreen) te).getRows());
            } else {
                // The tile entity is already gone (the chunk dropped it, or the
                // block was replaced rather than broken). Walk the wall instead of
                // leaving its cells behind: stop at the first column without a
                // cell, and at the first gap inside a column.
                for (int col = 0; col < TileEntityVideoScreen.MAX_SIZE; col++) {
                    BlockPos probe = ConsoleRig.partPos(origin, col, 0, facing);
                    if (!isCell(world, probe, facing)) {
                        break;
                    }
                    cols = col + 1;
                }
                for (int row = 0; row < TileEntityVideoScreen.MAX_SIZE; row++) {
                    BlockPos probe = ConsoleRig.partPos(origin, 0, row, facing);
                    if (!isCell(world, probe, facing)) {
                        break;
                    }
                    rows = row + 1;
                }
            }
            for (int col = 0; col < cols; col++) {
                for (int row = 0; row < rows; row++) {
                    if (col == 0 && row == 0) {
                        continue;
                    }
                    BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                    if (cell == null || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    if (world.getBlockState(cell).getBlock() == ModContent.BLOCK_VIDEO_SCREEN) {
                        world.setBlockToAir(cell);
                    }
                }
            }
            if (world.getBlockState(origin).getBlock() == ModContent.BLOCK_VIDEO_SCREEN) {
                world.setBlockToAir(origin);
            }
            spawnAsEntity(world, dropAt, new ItemStack(ModContent.BLOCK_VIDEO_SCREEN));
        } finally {
            DISMANTLING = false;
        }
    }

    /** True when that position holds a part cell of a wall facing this way. */
    private static boolean isCell(World world, BlockPos pos, EnumFacing facing) {
        if (pos == null || !world.isBlockLoaded(pos)) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        return state.getBlock() == ModContent.BLOCK_VIDEO_SCREEN
                && state.getValue(PART) && state.getValue(FACING) == facing;
    }

    /** The wall hands out its own item on break, so vanilla must not drop one too. */
    @Override
    public List<ItemStack> getDrops(net.minecraft.world.IBlockAccess world, BlockPos pos,
                                    IBlockState state, int fortune) {
        return Collections.emptyList();
    }

    /**
     * Walks a cell back to its controller.
     *
     * <p>Bounded by the maximum size, and every candidate is confirmed with
     * {@link ConsoleRig#covers}: two walls standing side by side is a legal
     * layout, and the nearest controller is not necessarily ours.</p>
     */
    public static BlockPos originOf(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() != ModContent.BLOCK_VIDEO_SCREEN) {
            return null;
        }
        if (!state.getValue(PART)) {
            return pos;
        }
        EnumFacing facing = state.getValue(FACING);
        for (int back = 0; back < TileEntityVideoScreen.MAX_SIZE; back++) {
            for (int up = 0; up < TileEntityVideoScreen.MAX_SIZE; up++) {
                BlockPos candidate = ConsoleRig.partPos(pos, -back, -up, facing);
                if (candidate == null || !world.isBlockLoaded(candidate)) {
                    continue;
                }
                IBlockState candidateState = world.getBlockState(candidate);
                if (candidateState.getBlock() != ModContent.BLOCK_VIDEO_SCREEN
                        || candidateState.getValue(PART)) {
                    continue;
                }
                TileEntity te = world.getTileEntity(candidate);
                if (!(te instanceof TileEntityVideoScreen)) {
                    continue;
                }
                TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
                if (ConsoleRig.covers(candidate, screen.getCols(), screen.getRows(), facing, pos)) {
                    return candidate;
                }
            }
        }
        return null;
    }
}

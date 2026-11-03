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
import com.hbmzz.ModContent;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.entity.EntityFallenScreen;
import com.hbmzz.network.DFCDataMessage;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.tile.TileEntityDFCMonitor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;

public class BlockDFCMonitor extends BlockHorizontal implements ITileEntityProvider {

    public BlockDFCMonitor() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "dfc_monitor");
        setUnlocalizedName(HBMZZ.MODID + ".dfc_monitor");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        setResistance(12.0F);
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
        return new TileEntityDFCMonitor();
    }

    /**
     * The machine is drawn entirely by the renderer, so the block itself paints
     * nothing.
     *
     * <p>This is not laziness - it is the only way the thing can look like a
     * device. A block model is a 1x1x1 cube, so with one the console is a wall
     * of textured cubes with a picture in front of it, which is exactly what it
     * looked like before: a photo taped to a block. Invisible blocks leave the
     * cell empty for the tile entity renderer to put a real housing in, with
     * thickness, a bezel that stands off the wall, fins and a mount - and the
     * panel stays free to tip without clipping into a cube that is no longer
     * there.</p>
     */
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

    /**
     * The monitor is also a switch: a 红石输出 action block drives this line.
     *
     * <p>Reported through the tile entity so the pulse is world state rather
     * than a special case in the renderer - pistons, lamps and doors react to
     * it exactly as they would to a lever, and the strength is the one the
     * operator configured instead of a hard 15.</p>
     */
    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return powerAt(world, pos);
    }

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return powerAt(world, pos);
    }

    private static int powerAt(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileEntityDFCMonitor ? ((TileEntityDFCMonitor) te).getRedstoneOutput() : 0;
    }

    /**
     * Half thickness of the collidable plate, in blocks.
     *
     * <p>The machine is a flat panel hanging on the cell's centre plane, so the
     * thing you can bump into is a 4/16 sheet through the middle of the cell -
     * not the whole cell. A full cube here reads as an invisible wall: you walk
     * into empty air a block before you reach the screen. Nothing at all is
     * worse, because then you walk through the panel.</p>
     */
    private static final double HALF_THICK = 2.0D / 16.0D;

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
     * Decides how the console hangs.
     *
     * <p>There are two ways to mount it and the block can tell them apart
     * without asking: if there is something above the top row to bolt a rig to,
     * it is a ceiling mount and the panel has to tip down towards whoever is
     * standing underneath - how far is read from where the placer was looking,
     * so hanging it while staring at the ceiling gives a steep panel and
     * placing it at eye level gives the usual 30 degrees. Bolted to a wall
     * there is nothing above it, and a wall mount sits flat.</p>
     */
    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityDFCMonitor)) {
            return;
        }
        TileEntityDFCMonitor monitor = (TileEntityDFCMonitor) te;
        // Part 0 is the top-left cell of the structure and every other cell is
        // laid out downwards from it, so the ceiling is the block directly
        // above this one - not one per row. Asking for pos.up(rows) while
        // standing under a one-block-thick ceiling finds air and quietly
        // classifies every hung console as a wall mount.
        boolean hung = !world.isAirBlock(pos.up());
        int rows = monitor.getScreenRows();
        monitor.setTilt(hung ? ConsoleRig.notchFromPitch(placer.rotationPitch, rows)
                : ConsoleRig.TILT_WALL);

        // Fill the rest of the footprint with inert cells. Whatever does not
        // fit is simply left alone: the panel still renders at its configured
        // size, it just has nothing to collide with in the cells that were
        // already occupied. Refusing the placement outright would be worse -
        // the player would lose the block and get no screen at all.
        ensureParts(world, pos, state.getValue(FACING),
                monitor.getScreenCols(), monitor.getScreenRows());
    }

    /**
     * Makes sure every cell the panel claims is a filler cell.
     *
     * <p>Called on placement and again whenever the size changes: growing a
     * 2x2 panel to 4x3 has to turn two more columns of ordinary cells into
     * filler, otherwise the panel renders four blocks wide on top of a
     * two-block-wide footprint.</p>
     */
    public static void ensureParts(World world, BlockPos origin, EnumFacing facing, int cols, int rows) {
        if (world.isRemote) {
            return;
        }
        IBlockState part = ModContent.BLOCK_MONITOR_PART.getDefaultState()
                .withProperty(BlockDFCMonitorPart.FACING, facing);
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
                Block block = existing.getBlock();
                if (block instanceof BlockDFCMonitorPart) {
                    // Already a cell; make sure it agrees about the facing.
                    if (existing.getValue(BlockDFCMonitorPart.FACING) != facing) {
                        world.setBlockState(cell, part, 3);
                    }
                    continue;
                }
                if (block == ModContent.BLOCK_MONITOR) {
                    // Left over from a save made before filler cells existed.
                    // Replacing it is what stops the old eleven-controllers
                    // layout from painting eleven extra screens.
                    world.setBlockToAir(cell);
                }
                if (block.isReplaceable(world, cell)) {
                    world.setBlockState(cell, part, 3);
                }
            }
        }
    }

    /**
     * Removes every cell of this panel. Called when any cell is broken.
     *
     * <p>Guarded by a static flag: clearing a cell runs its own
     * {@code breakBlock}, which would otherwise try to take down the structure
     * it is already being taken down by.</p>
     */
    public void takeDown(World world, BlockPos origin, IBlockState state) {
        if (world.isRemote || BlockDFCMonitorPart.DISMANTLING) {
            return;
        }
        BlockDFCMonitorPart.DISMANTLING = true;
        try {
            EnumFacing facing = state.getValue(FACING);
            int cols = 1;
            int rows = 1;
            TileEntity te = world.getTileEntity(origin);
            if (te instanceof TileEntityDFCMonitor) {
                cols = Math.max(1, ((TileEntityDFCMonitor) te).getScreenCols());
                rows = Math.max(1, ((TileEntityDFCMonitor) te).getScreenRows());
            }
            // The panel does not simply vanish: the harness stays bolted to the
            // ceiling, the panel drops onto its cable and swings there showing
            // a dead channel until the feed is cut.
            spawnFallen(world, origin, facing, cols, rows);
            for (int col = 0; col < cols; col++) {
                for (int row = 0; row < rows; row++) {
                    if (col == 0 && row == 0) {
                        continue;
                    }
                    BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                    if (cell == null || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    Block block = world.getBlockState(cell).getBlock();
                    if (block == this || block instanceof BlockDFCMonitorPart) {
                        world.setBlockToAir(cell);
                    }
                }
            }
            // Breaking a filler cell leaves the controller standing; break it
            // too so the whole machine disappears as one piece.
            if (!world.isAirBlock(origin) && world.getBlockState(origin).getBlock() == this) {
                world.setBlockToAir(origin);
            }
        } finally {
            BlockDFCMonitorPart.DISMANTLING = false;
        }
    }

    /**
     * Spawns the falling panel. Server side only; the entity is tracked so
     * every nearby client sees the same swing.
     *
     * <p>Not a dropped item and not a falling block: neither can swing on a
     * cable, and the panel has to keep showing something while it dies.</p>
     */
    private static void spawnFallen(World world, BlockPos origin, EnumFacing facing, int cols, int rows) {
        if (world.isRemote) {
            return;
        }
        EntityFallenScreen entity = new EntityFallenScreen(world, origin, facing, cols, rows,
                0, ConsoleRig.yawOf(facing));
        world.spawnEntity(entity);
    }

    /**
     * True when this panel has any filler cell, i.e. it was placed by a version
     * that uses them.
     *
     * <p>Only old saves need the renderer's neighbour de-duplication any more;
     * new panels draw exactly once because their other cells have no tile
     * entity to draw with. Keeping the distinction explicit matters because the
     * de-duplication test cannot tell a neighbouring console from a cell of the
     * same one, and would happily swallow a panel placed right next to
     * another.</p>
     */
    public static boolean hasFillerCells(World world, BlockPos origin, EnumFacing facing,
                                         int cols, int rows) {
        for (int col = 0; col < cols; col++) {
            for (int row = 0; row < rows; row++) {
                if (col == 0 && row == 0) {
                    continue;
                }
                BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                if (cell != null && world.isBlockLoaded(cell)
                        && world.getBlockState(cell).getBlock() instanceof BlockDFCMonitorPart) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Dismantles the rest of the panel when its origin is broken.
     *
     * <p>Cells of a panel are ordinary monitor blocks with their own tiles, so
     * without this the eleven cells left behind would each start painting a
     * screen of their own the moment the controller went - invisible blocks
     * spawning visible panels. Only the origin does the dismantling; a cell
     * that is merely part of somebody else's panel is left alone, so breaking
     * one square of a neighbour's wall does not delete the neighbour.</p>
     */
    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        takeDown(world, pos, state);
        super.breakBlock(world, pos, state);
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                             EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            HBMZZ.proxy.openMonitor(pos);
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityDFCMonitor && player instanceof EntityPlayerMP) {
            NetworkHandler.sendTo((EntityPlayerMP) player, new DFCDataMessage(pos, ((TileEntityDFCMonitor) te).snapshot()));
        }
        return true;
    }
}
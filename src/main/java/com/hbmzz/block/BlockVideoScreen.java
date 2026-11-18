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
import com.hbmzz.ModContent;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.item.ItemMonitorLinker;
import com.hbmzz.tile.TileEntityVideoScreen;
import net.minecraft.block.Block;
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
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 视频大屏: the wall the broadcast actually lands on.
 *
 * <h3>What it is not</h3>
 * <p>Not a second video pipeline. The broadcaster already owns the whole story -
 * a clip name arrives from the server, the client reads the file from its own
 * {@code config/hbmzz/videos/} - and this block only adds a surface to show it
 * on. It stores the name and draws; it never talks to the server about pixels.</p>
 *
 * <h3>How it is linked</h3>
 * <p>With the same cable that binds cameras and cores: point it at the
 * broadcaster (records it), then point it at the screen (binds it). Both blocks
 * decline the click while the cable is held, because 1.12.2 only calls
 * {@code Item.onItemUse} after the block says no - a block that claims every
 * right-click makes its own linking tool unreachable, which is a bug this mod
 * has already shipped once.</p>
 *
 * <h3>Why the panel is multi-cell</h3>
 * <p>Because "大屏" is the point. Cells after the origin are ordinary
 * {@link BlockDFCMonitorPart} filler: no tile entity, so the screen can only be
 * painted once no matter what the renderer does, and breaking any cell takes the
 * whole panel down and drops one screen. Sizes are cycled with sneak-right-click
 * rather than a window: a wall panel is a placement decision, and a window to
 * pick 2x2 would be a window for one click.</p>
 */
public class BlockVideoScreen extends BlockHorizontal implements ITileEntityProvider, IPanelOrigin {

    /** Half thickness of the collidable plate, same as the monitor housing. */
    private static final double HALF_THICK = 2.0D / 16.0D;

    public BlockVideoScreen() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "video_screen");
        setUnlocalizedName(HBMZZ.MODID + ".video_screen");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(3.5F);
        setResistance(10.0F);
        // Same as the filler cells it grows into (they are the shared monitor
        // part block, which lights at 0.25). A mismatch here would make the
        // origin cell the one dark square of an otherwise glowing panel.
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
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityVideoScreen();
    }

    // ------------------------------------------------------------- appearance

    /** The block itself paints nothing; the renderer owns the whole housing. */
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

    // -------------------------------------------------------------- placement

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
     * Makes every cell the panel claims a filler cell.
     *
     * <p>Only air and replaceables are taken: growing a screen into a wall must
     * not delete the wall. The panel still renders at its configured size over
     * whatever it could not claim, which is the same compromise the monitor
     * makes when its footprint is obstructed.</p>
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
                    // A cell of somebody else's panel (a DFC monitor next door
                    // shares this filler block) is not ours to re-face. Without
                    // this the grow step would silently turn a neighbour's cell
                    // around and paint that panel backwards.
                    BlockPos owner = BlockDFCMonitorPart.findOrigin(world, cell);
                    if (owner != null && !owner.equals(origin)) {
                        continue;
                    }
                    if (existing.getValue(BlockDFCMonitorPart.FACING) != facing) {
                        world.setBlockState(cell, part, 3);
                    }
                    continue;
                }
                if (block.isReplaceable(world, cell)) {
                    world.setBlockState(cell, part, 3);
                }
            }
        }
    }

    /**
     * Applies a new size: grows into free cells, and gives back the cells the
     * panel no longer covers.
     *
     * <p>Shrinking matters as much as growing. Without the release step a 3x3
     * screen reduced to 1x1 would leave eight invisible filler cells standing in
     * the wall - collidable, unbreakable-looking, and impossible to explain.</p>
     */
    public static void applySize(World world, BlockPos origin, IBlockState state, int cols, int rows) {
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(origin);
        if (!(te instanceof TileEntityVideoScreen)) {
            return;
        }
        EnumFacing facing = state.getValue(FACING);
        TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
        int oldCols = screen.getCols();
        int oldRows = screen.getRows();

        BlockDFCMonitorPart.DISMANTLING = true;
        try {
            for (int col = 0; col < oldCols; col++) {
                for (int row = 0; row < oldRows; row++) {
                    if (col < cols && row < rows) {
                        continue;
                    }
                    BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                    if (cell == null || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    if (world.getBlockState(cell).getBlock() instanceof BlockDFCMonitorPart) {
                        // Only give back cells that are actually ours; a filler
                        // that resolves to a different origin belongs to the
                        // panel next door and must survive our resize.
                        BlockPos owner = BlockDFCMonitorPart.findOrigin(world, cell);
                        if (owner == null || owner.equals(origin)) {
                            world.setBlockToAir(cell);
                        }
                    }
                }
            }
        } finally {
            BlockDFCMonitorPart.DISMANTLING = false;
        }

        screen.setSize(cols, rows);
        ensureParts(world, origin, facing, cols, rows);
    }

    // ------------------------------------------------------------------ using

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // The cable has to get the click - see the class comment.
        if (player.getHeldItem(hand).getItem() instanceof ItemMonitorLinker) {
            return false;
        }
        if (player.isSneaking()) {
            if (!world.isRemote) {
                cycleSize(world, pos, state, player);
            }
            return true;
        }
        if (world.isRemote) {
            HBMZZ.proxy.openVideoViewer(pos);
            return true;
        }
        // Push the current clip/link down before the window opens, so a client
        // that walked up while it was bound elsewhere does not play a stale name.
        world.notifyBlockUpdate(pos, state, state, 3);
        return true;
    }

    private static void cycleSize(World world, BlockPos pos, IBlockState state, EntityPlayer player) {
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityVideoScreen)) {
            return;
        }
        TileEntityVideoScreen screen = (TileEntityVideoScreen) te;
        int[] next = TileEntityVideoScreen.nextPreset(screen.getCols(), screen.getRows());
        applySize(world, pos, state, next[0], next[1]);
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.video_screen_size",
                next[0], next[1]));
    }

    // ------------------------------------------------------------- panel origin

    @Override
    public int panelCols(World world, BlockPos origin) {
        TileEntity te = world.getTileEntity(origin);
        if (te instanceof TileEntityVideoScreen) {
            return Math.max(1, ((TileEntityVideoScreen) te).getCols());
        }
        return Math.max(1, HBMZZConfig.defaultScreenCols);
    }

    @Override
    public int panelRows(World world, BlockPos origin) {
        TileEntity te = world.getTileEntity(origin);
        if (te instanceof TileEntityVideoScreen) {
            return Math.max(1, ((TileEntityVideoScreen) te).getRows());
        }
        return Math.max(1, HBMZZConfig.defaultScreenRows);
    }

    @Override
    public ItemStack panelDrop() {
        net.minecraft.item.Item item = net.minecraft.item.Item.getItemFromBlock(this);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1);
    }

    @Override
    public void takeDown(World world, BlockPos origin, IBlockState state) {
        if (world.isRemote || BlockDFCMonitorPart.DISMANTLING) {
            return;
        }
        BlockDFCMonitorPart.DISMANTLING = true;
        try {
            EnumFacing facing = state.getValue(FACING);
            int cols = panelCols(world, origin);
            int rows = panelRows(world, origin);
            for (int col = 0; col < cols; col++) {
                for (int row = 0; row < rows; row++) {
                    if (col == 0 && row == 0) {
                        continue;
                    }
                    BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                    if (cell == null || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    if (world.getBlockState(cell).getBlock() instanceof BlockDFCMonitorPart) {
                        BlockPos owner = BlockDFCMonitorPart.findOrigin(world, cell);
                        if (owner == null || owner.equals(origin)) {
                            world.setBlockToAir(cell);
                        }
                    }
                }
            }
            if (!world.isAirBlock(origin) && world.getBlockState(origin).getBlock() == this) {
                world.setBlockToAir(origin);
            }
        } finally {
            BlockDFCMonitorPart.DISMANTLING = false;
        }
    }

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        takeDown(world, pos, state);
        super.breakBlock(world, pos, state);
    }
}

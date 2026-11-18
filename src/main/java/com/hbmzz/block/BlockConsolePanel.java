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
import com.hbmzz.tile.TileEntityPanelScreen;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 吊装式工业显示器的公共部分：2x2 格、可吊装、可倾斜、外壳由渲染器画。
 *
 * <h3>为什么两个屏共用这一个基类</h3>
 * <p>核弹监视屏和事故显示器是同一台硬件的两块面板，只有玻璃上的内容不同。它们与 DFC 监视器
 * 用的是同一套骨架（{@link com.hbmzz.client.ConsoleModel} 画的外壳、吊架、压条、走线管），
 * 所以占地、碰撞板、吊装判定、拆装、掉落实物这一整套行为必须一模一样——写两遍就是等着哪天
 * 只改了其中一份，然后玩家发现两种屏的高度差半格。</p>
 *
 * <h3>为什么是 2x2 而不是 1x1</h3>
 * <p>1x1 的版本已经做过，看起来不像设备，像一块贴在墙上的板：没有吊架、没有倾角、外壳只有
 * 一格厚，挂在 DFC 监视器旁边一眼就能看出不是同一种东西。2x2 是 DFC 监视器的默认尺寸，也是
 * 这块画的画布（256 逻辑像素见方）能保持正方形、字不变形的最小尺寸。</p>
 *
 * <h3>方块自己什么也不画</h3>
 * <p>与 DFC 监视器同一个理由：方块模型只能是一个 1x1x1 的立方体，用它就永远得不到有厚度、
 * 有边框、能倾斜的外壳。所以整台机器由 {@code TileEntityPanelScreen} 的方块实体渲染器画，
 * 方块只负责占位、碰撞和格子归属。</p>
 */
public abstract class BlockConsolePanel extends BlockHorizontal
        implements ITileEntityProvider, IPanelOrigin {

    /** 面板宽度（格），沿 {@link ConsoleRig#rightOf} 方向。 */
    public static final int PANEL_COLS = 2;
    /** 面板高度（格），向下。 */
    public static final int PANEL_ROWS = 2;

    /**
     * 可碰撞板的半厚度。
     *
     * <p>面板挂在格子中线上，所以能撞到的是一张 4/16 厚的板，而不是整格。整格会变成一堵
     * 看不见的墙——在离屏幕还有一格的地方就撞上空气。</p>
     */
    private static final double HALF_THICK = 2.0D / 16.0D;

    protected BlockConsolePanel() {
        super(Material.IRON);
        setHardness(4.0F);
        setResistance(20.0F);
        setLightLevel(0.25F);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // ------------------------------------------------------------------ state

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX,
                                            float hitY, float hitZ, int meta, EntityLivingBase placer,
                                            EnumHand hand) {
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

    // ------------------------------------------------------------- placement

    /**
     * 挂上去的时候决定怎么挂，并把另外三格变成填充板。
     *
     * <p>吊装判定与 DFC 监视器同一条规则：原点格正上方不是空气就说明有东西可以栓吊架，
     * 此时倾角取玩家俯仰角对应的那一档（抬头挂＝陡、平视挂＝常规 30 度）。贴墙放时头顶
     * 空着，没有东西可吊，于是平贴墙面。</p>
     */
    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (world.isRemote) {
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityPanelScreen) {
            TileEntityPanelScreen panel = (TileEntityPanelScreen) te;
            panel.setTilt(isHung(world, pos)
                    ? ConsoleRig.notchFromPitch(placer.rotationPitch, PANEL_ROWS)
                    : ConsoleRig.TILT_WALL);
        }
        fillCells(world, pos, state.getValue(FACING), PANEL_COLS, PANEL_ROWS);
    }

    /** 原点格正上方不是空气 = 有东西可以栓吊架。 */
    public static boolean isHung(World world, BlockPos origin) {
        return !world.isAirBlock(origin.up());
    }

    /**
     * 把面板声明的那几格变成填充板。
     *
     * <p>只填空格与可替换方块。占着不动的格子原样留着——面板照样按自己的尺寸画出来，
     * 只是那几格没有东西可撞。直接拒绝放置更糟：玩家会连方块一起丢掉，屏幕上什么也没有。</p>
     *
     * <p>刻意没有顺手拆掉格子里别人的显示器（DFC 监视器那份有这条，因为它要清理填充板
     * 出现之前的老存档）。这里不清理：两颗核弹屏紧挨着放是合法摆放，不该被后放的那颗
     * 吃掉先放的那颗。</p>
     */
    public static void fillCells(World world, BlockPos origin, EnumFacing facing, int cols, int rows) {
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
                if (existing.getBlock() instanceof BlockDFCMonitorPart) {
                    if (existing.getValue(BlockDFCMonitorPart.FACING) != facing) {
                        world.setBlockState(cell, part, 3);
                    }
                    continue;
                }
                if (existing.getBlock().isReplaceable(world, cell)) {
                    world.setBlockState(cell, part, 3);
                }
            }
        }
    }

    // --------------------------------------------------------------- teardown

    @Override
    public void breakBlock(World world, BlockPos pos, IBlockState state) {
        takeDown(world, pos, state);
        super.breakBlock(world, pos, state);
    }

    @Override
    public void takeDown(World world, BlockPos origin, IBlockState state) {
        if (world.isRemote || BlockDFCMonitorPart.DISMANTLING) {
            return;
        }
        BlockDFCMonitorPart.DISMANTLING = true;
        try {
            EnumFacing facing = state.getValue(FACING);
            for (int col = 0; col < PANEL_COLS; col++) {
                for (int row = 0; row < PANEL_ROWS; row++) {
                    if (col == 0 && row == 0) {
                        continue;
                    }
                    BlockPos cell = ConsoleRig.partPos(origin, col, row, facing);
                    if (cell == null || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    if (world.getBlockState(cell).getBlock() instanceof BlockDFCMonitorPart) {
                        world.setBlockToAir(cell);
                    }
                }
            }
            // 从填充板拆下来时原点还站着，一并拆掉，整台机器作为一件东西消失。
            if (world.getBlockState(origin).getBlock() == this) {
                world.setBlockToAir(origin);
            }
        } finally {
            BlockDFCMonitorPart.DISMANTLING = false;
        }
    }

    @Override
    public ItemStack panelDrop() {
        Item item = Item.getItemFromBlock(this);
        return item == null ? ItemStack.EMPTY : new ItemStack(item, 1);
    }

    // ------------------------------------------------------------ panel origin

    @Override
    public int panelCols(World world, BlockPos origin) {
        return PANEL_COLS;
    }

    @Override
    public int panelRows(World world, BlockPos origin) {
        return PANEL_ROWS;
    }

    // ------------------------------------------------------------------- item

    // 掉落物不需要重写：默认就是「自己这个方块的物品，一个」。填充板那一侧走的是
    // BlockDFCMonitorPart#getDrops，它把掉落物交给原点格决定，所以一台面板永远只变成
    // 一件物品，而不是四件。
}

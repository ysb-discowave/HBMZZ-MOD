/*
 * HBMZZ - Aegis DFC telemetry terminal.
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
 */

package com.hbmzz.aegis;

import com.hbmzz.HBMZZ;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;

/**
 * The controller cell of an Aegis telemetry wall. It is a thin, wall-mounted
 * panel whose face is drawn entirely by {@code AegisRenderer}; the block itself
 * renders nothing so the picture is not a texture taped to a cube.
 *
 * <p>Interaction: right-click cycles telemetry pages; sneak right-click resets
 * the core binding so a moved core can be re-acquired. The core is otherwise
 * discovered automatically within the configured scan radius.</p>
 */
public final class BlockAegisTerminal extends BlockHorizontal implements ITileEntityProvider {

    private static final double HALF_THICK = 2.0D / 16.0D;

    public BlockAegisTerminal() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "aegis_terminal");
        setUnlocalizedName(HBMZZ.MODID + ".aegis_terminal");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(4.0F);
        setResistance(12.0F);
        setLightLevel(AegisConfig.glow ? 0.5F : 0.0F);
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
        return new TileAegisTerminal();
    }

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

    private static AxisAlignedBB plate(EnumFacing facing) {
        if (facing == EnumFacing.EAST || facing == EnumFacing.WEST) {
            return new AxisAlignedBB(0.5D - HALF_THICK, 0.0D, 0.0D,
                    0.5D + HALF_THICK, 1.0D, 1.0D);
        }
        return new AxisAlignedBB(0.0D, 0.0D, 0.5D - HALF_THICK,
                1.0D, 1.0D, 0.5D + HALF_THICK);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, net.minecraft.world.IBlockAccess world, BlockPos pos) {
        return plate(state.getValue(FACING));
    }

    @Nullable
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, net.minecraft.world.IBlockAccess world, BlockPos pos) {
        return plate(state.getValue(FACING));
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing side, float hitX, float hitY, float hitZ) {
        // 普通右键打开配置窗口（绑定核心 / 翻页 / 电源都在这里）。
        // 潜行右键保留为快速翻页，方便站在屏前连续切换而不必开关窗口。
        if (player.isSneaking()) {
            if (!world.isRemote) {
                TileEntity te = world.getTileEntity(pos);
                if (te instanceof TileAegisTerminal) {
                    ((TileAegisTerminal) te).cyclePage();
                }
            }
            return true;
        }
        if (world.isRemote) {
            HBMZZ.proxy.openAegis(pos);
            return true;
        }
        // Push the authoritative page/power/binding down before the window is
        // read: the client may have been out of range when those changed, and
        // the menu must not open on yesterday's state.
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileAegisTerminal) {
            world.notifyBlockUpdate(pos, state, state, 3);
        }
        return true;
    }
}

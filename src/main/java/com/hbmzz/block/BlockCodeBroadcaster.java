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
import com.hbmzz.tile.TileEntityCodeBroadcaster;
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
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

/**
 * 代码广播器: redstone in, facility code out.
 *
 * <p>Right-click steps the preset up one code, sneak-right-click steps it down.
 * There is no window because there is nothing to put in one: eight choices and a
 * label that fits in a chat line. The block's own lamp is the redstone state,
 * not the preset - a box that sat on the wall showing DELTA while the facility
 * was fine would be the exact confusion this system exists to avoid.</p>
 */
public class BlockCodeBroadcaster extends BlockHorizontal implements ITileEntityProvider {

    public static final PropertyBool POWERED = PropertyBool.create("powered");

    public BlockCodeBroadcaster() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "code_broadcaster");
        setUnlocalizedName(HBMZZ.MODID + ".code_broadcaster");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(3.5F);
        setResistance(15.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(POWERED, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, POWERED);
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer, EnumHand hand) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex() | (state.getValue(POWERED) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(POWERED, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityCodeBroadcaster();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityCodeBroadcaster)) {
            return true;
        }
        TileEntityCodeBroadcaster broadcaster = (TileEntityCodeBroadcaster) te;
        // Sneaking walks back down the ladder. Without it, an operator who
        // overshoots by one has to click seven more times.
        com.hbmzz.alert.AlertCode code = broadcaster.step(!player.isSneaking());
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.code_set",
                code.label(), code.zhTitle()));
        return true;
    }

    @Override
    public void onBlockPlacedBy(World world, BlockPos pos, IBlockState state,
                                EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(world, pos, state, placer, stack);
        if (!world.isRemote) {
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }
}

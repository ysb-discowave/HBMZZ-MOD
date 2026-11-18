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
import com.hbmzz.tile.TileEntityVideoBroadcaster;
import net.minecraft.block.BlockHorizontal;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 视频广播器: a box on the wall that hijacks screens.
 *
 * <p>A plain full cube, deliberately. The interesting part of this block is what
 * happens on somebody else's monitor, not on its own face; a tile entity
 * renderer would add a per-frame renderer to the world for one lamp. The lamp
 * the {@link #POWERED} state drives says the only thing worth reading at a
 * glance: is this thing being driven right now.</p>
 *
 * <p>Right-click is two gestures: standing starts the display (the wall, if one
 * is connected; otherwise the client's own full-screen view), sneaking opens the
 * configuration window. The block's own state is server-authoritative like every
 * other machine here - the window is a remote control for a tile entity, not a
 * place where the settings live.</p>
 */
public class BlockVideoBroadcaster extends BlockHorizontal implements ITileEntityProvider {

    /** Lit while the block is receiving redstone. Cosmetic only. */
    public static final PropertyBool POWERED = PropertyBool.create("powered");

    public BlockVideoBroadcaster() {
        super(Material.IRON);
        setRegistryName(HBMZZ.MODID, "video_broadcaster");
        setUnlocalizedName(HBMZZ.MODID + ".video_broadcaster");
        setCreativeTab(CreativeTabs.REDSTONE);
        setHardness(3.5F);
        setResistance(10.0F);
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

    /** Facing keeps the low two bits, so the lamp can share the metadata byte. */
    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex()
                | (state.getValue(POWERED) ? 4 : 0);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(POWERED, (meta & 4) != 0);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityVideoBroadcaster();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // The remote is declined first: Item.onItemUse only runs after the block
        // says no, so a block that claims every click makes the remote dead - and
        // pointing the remote at a player is the first half of connecting a wall.
        // 蹲下不算"对准"：那个手势是"我要改设置"，拿着遥控器也一样。
        if (!player.isSneaking()
                && player.getHeldItem(hand).getItem() instanceof com.hbmzz.item.ItemVideoRemote) {
            return false;
        }
        if (world.isRemote) {
            // 蹲下 = 编辑这台机器（片源、目标、文件夹、播放器设置）；站着 = 大屏展示。
            // 站着的这一下**不再**开一个全屏播放窗口：接了屏就把画面放到屏上，
            // 客户端进"展示模式"看的是那块屏自己的解码器，全程只有一份播放实例。
            if (player.isSneaking()) {
                HBMZZ.proxy.openVideoBroadcaster(pos);
            } else {
                HBMZZ.proxy.openVideoPlayback(pos);
            }
            return true;
        }
        // Push the current settings down before the window opens, so a client
        // that walked up while the block was edited elsewhere does not edit a
        // stale copy.
        world.notifyBlockUpdate(pos, state, state, 3);
        if (!player.isSneaking()) {
            // 服务端这一下是"开始展示"：接了屏就让那块屏播起来。客户端已经在
            // 等它了（展示窗口每帧问屏要帧），拉杆一变化仍以拉杆为准。
            TileEntity te = world.getTileEntity(pos);
            if (te instanceof TileEntityVideoBroadcaster) {
                ((TileEntityVideoBroadcaster) te).startDisplay();
            }
        }
        return true;
    }
}

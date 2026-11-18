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
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.NukeDataMessage;
import com.hbmzz.tile.TileEntityNukeMonitor;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 核弹监视屏: 一台吊装式工业显示器，读一台机器。
 *
 * <p>形体与 DFC 监视器完全一致——2x2 格、吊架、压条、三十度倾角、同一套外壳色，
 * 因为它们本来就该像同一家厂出的设备（{@link BlockConsolePanel} 是那块公共部分）。
 * 早先它是 1x1 的，挂上去像一块贴在墙上的板，站在 DFC 监视器旁边一眼就能看出不是一种东西。</p>
 *
 * <p>木斧蹲右键绑定，与控制器同一个手势。没有绑定的监视屏不会黑屏，它画出绑定说明——
 * 一块什么都不显示的屏不告诉玩家下一步该做什么。</p>
 */
public class BlockNukeMonitor extends BlockConsolePanel {

    public BlockNukeMonitor() {
        setRegistryName(HBMZZ.MODID, "nuke_monitor");
        setUnlocalizedName(HBMZZ.MODID + ".nuke_monitor");
        setCreativeTab(CreativeTabs.REDSTONE);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityNukeMonitor();
    }

    /**
     * 右键：立刻给自己推一帧新采样。
     *
     * <p>屏幕本来每个显示间隔自己会更新；这个点击是给刚绑定完的玩家用的——不然他得等下一轮
     * 轮询才知道绑对了没有。</p>
     */
    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityNukeMonitor && player instanceof EntityPlayerMP) {
            NetworkHandler.sendTo((EntityPlayerMP) player,
                    new NukeDataMessage(pos, ((TileEntityNukeMonitor) te).snapshot()));
        }
        return true;
    }
}

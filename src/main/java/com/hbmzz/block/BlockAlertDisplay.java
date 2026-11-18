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
import com.hbmzz.alert.AlertSync;
import com.hbmzz.alert.FacilityState;
import com.hbmzz.tile.TileEntityAlertDisplay;
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
 * 事故显示器: 挂着的那块屏，说设施现在是什么状态。
 *
 * <p>形体与 DFC 监视器、核弹监视屏一致（{@link BlockConsolePanel}），因为它们挂在同一间
 * 控制室里。可以是任意多块，挂在哪里都一样，因为它们看的是同一条记录：没有绑定、没有配置——
 * 一块需要接线的显示器就是一块会接错的显示器，而这块屏唯一的要求是它不可能错。</p>
 */
public class BlockAlertDisplay extends BlockConsolePanel {

    public BlockAlertDisplay() {
        setRegistryName(HBMZZ.MODID, "alert_display");
        setUnlocalizedName(HBMZZ.MODID + ".alert_display");
        setCreativeTab(CreativeTabs.REDSTONE);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int meta) {
        return new TileEntityAlertDisplay();
    }

    @Override
    public boolean onBlockActivated(World world, BlockPos pos, IBlockState state, EntityPlayer player,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (world.isRemote) {
            return true;
        }
        FacilityState facility = FacilityState.get(world);
        if (facility != null && player instanceof EntityPlayerMP) {
            AlertSync.to((EntityPlayerMP) player, world, facility);
        }
        return true;
    }
}

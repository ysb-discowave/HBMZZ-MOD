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

package com.hbmzz.item;

import com.hbmzz.HBMZZ;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumHand;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import java.util.List;

/**
 * The hacker tablet: a one-slot item whose only job is to open a terminal.
 *
 * <p>No inventory, no container, no tile entity. Everything a keystroke means
 * lives on the server (see {@code HackService}); this item opens a window and
 * that window sends text. Keeping the item this thin is what lets the whole
 * feature work in single player and on a dedicated server with the same code,
 * and it is also why losing the item loses nothing but access - the intrusion
 * marks it leaves behind belong to the world, not to the tablet.</p>
 */
public class ItemHackerTablet extends Item {

    public ItemHackerTablet() {
        setRegistryName(HBMZZ.MODID, "hacker_tablet");
        setUnlocalizedName(HBMZZ.MODID + ".hacker_tablet");
        // Redstone, next to the linker and the panel: somebody looking for
        // "the thing that talks to machines" looks there.
        setCreativeTab(CreativeTabs.REDSTONE);
        setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        // The window is client-only. The server learns nothing about it until a
        // line is actually sent, which is the same arm's length the panel
        // terminal keeps.
        if (world.isRemote) {
            HBMZZ.proxy.openHackerTablet();
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.DARK_GREEN + "右键打开终端");
        tooltip.add(TextFormatting.DARK_GRAY + "可绕过控制棒，直接写入机器数值");
        tooltip.add(TextFormatting.DARK_GRAY + "示例: target 128 64 -32 然后 set heat 5000");
    }
}

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

import com.hbmzz.alert.NukeRegistry;
import net.minecraft.block.Block;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The bomb in item form, mostly so that a refused placement can explain itself.
 *
 * <p>{@code Block.canPlaceBlockAt} returning false is silent: the block simply
 * never appears. For the one rule that is genuinely surprising - "there is
 * already a bomb in this world, and it is at these coordinates" - silence is the
 * worst possible answer, because the player's next move is to assume the item is
 * broken. The check happens here, before the block layer, and it says where the
 * other one is.</p>
 */
public class ItemBlockNuke extends ItemBlock {

    public ItemBlockNuke(Block block) {
        super(block);
        setMaxStackSize(1);
        setMaxDamage(0);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!world.isRemote) {
            BlockPos target = world.getBlockState(pos).getBlock().isReplaceable(world, pos)
                    ? pos : pos.offset(facing);
            NukeRegistry registry = NukeRegistry.get(world);
            if (registry != null && registry.blocks(world, target)) {
                BlockPos other = registry.pos();
                player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_only_one",
                        other.getX(), other.getY(), other.getZ(), registry.dim()));
                return EnumActionResult.FAIL;
            }
        }
        return super.onItemUse(player, world, pos, hand, facing, hitX, hitY, hitZ);
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.DARK_RED + net.minecraft.util.text.translation.I18n
                .translateToLocal("hbmzz.nuke.desc"));
        tooltip.add(TextFormatting.GRAY + net.minecraft.util.text.translation.I18n
                .translateToLocal("hbmzz.nuke.desc2"));
        tooltip.add(TextFormatting.GRAY + net.minecraft.util.text.translation.I18n
                .translateToLocal("hbmzz.nuke.desc3"));
    }
}

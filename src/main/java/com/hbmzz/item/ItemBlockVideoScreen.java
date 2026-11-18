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

import net.minecraft.block.Block;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * The screen's item form, with the two things a player cannot guess written on
 * it: how it is fed, and what right-click does.
 *
 * <p>A plain {@link ItemBlock} would ship a block whose entire use is "hold a
 * cable and click two things", discoverable only by reading the source. The
 * tooltip is three lines; that is cheaper than a manual.</p>
 */
public class ItemBlockVideoScreen extends ItemBlock {

    public ItemBlockVideoScreen(Block block) {
        super(block);
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.GRAY + I18n.translateToLocal("hbmzz.video_screen.tip1"));
        tooltip.add(TextFormatting.GRAY + I18n.translateToLocal("hbmzz.video_screen.tip2"));
        tooltip.add(TextFormatting.DARK_GRAY + I18n.translateToLocal("hbmzz.video_screen.tip3"));
    }
}

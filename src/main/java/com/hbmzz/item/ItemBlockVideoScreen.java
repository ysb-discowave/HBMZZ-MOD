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
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The wall's item form, with the two lines nobody would guess.
 *
 * <p>A plain {@code ItemBlock} would work - it is an ordinary block. What it
 * cannot do is explain that the size window is behind sneak + right-click and that
 * the wall is connected with the remote, and those are the only two things about
 * this block that are not visible from looking at it. Tooltips are cheaper than a
 * wiki page that nobody reads.</p>
 */
public class ItemBlockVideoScreen extends ItemBlock {

    public ItemBlockVideoScreen(Block block) {
        super(block);
    }

    @Override
    public void addInformation(ItemStack stack, @Nullable World world, List<String> tooltip,
                               ITooltipFlag flag) {
        tooltip.add(TextFormatting.GREEN
                + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.video_screen.tip1"));
        tooltip.add(TextFormatting.GRAY
                + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.video_screen.tip2"));
        tooltip.add(TextFormatting.DARK_GRAY
                + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.video_screen.tip3"));
    }
}

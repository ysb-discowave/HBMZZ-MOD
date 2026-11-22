/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
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
 * The AI terminal: a hand-held window onto the AI control service.
 *
 * <p>As thin as the hacker tablet on purpose — no tile entity, no container.
 * The window sends text, and {@code AiTerminalService} decides on the server
 * what that text means against the world it actually has. Losing the item loses
 * only access; every binding and safety setting lives on the node and in the
 * AI configuration, not in this stack.</p>
 */
public class ItemAITerminal extends Item {

    public ItemAITerminal() {
        setRegistryName(HBMZZ.MODID, "ai_terminal");
        setUnlocalizedName(HBMZZ.MODID + ".ai_terminal");
        setCreativeTab(CreativeTabs.REDSTONE);
        setMaxStackSize(1);
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, EntityPlayer player, EnumHand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) {
            HBMZZ.proxy.openAITerminal();
        }
        return new ActionResult<ItemStack>(EnumActionResult.SUCCESS, stack);
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.AQUA + "右键打开 AI 终端");
        tooltip.add(TextFormatting.DARK_GRAY + "本地命令: status / start / scram / stabiliser on");
        tooltip.add(TextFormatting.DARK_GRAY + "其它文字发给 AI，由其控制 DFC 组件");
    }
}

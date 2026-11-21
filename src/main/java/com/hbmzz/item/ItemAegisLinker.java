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

package com.hbmzz.item;

import com.hbmzz.HBMZZ;
import com.hbmzz.aegis.BlockAegisTerminal;
import com.hbmzz.aegis.TileAegisTerminal;
import com.hbmzz.dfc.DFCReader;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import java.util.List;

/**
 * 神盾连接工具。两下点击完成绑定：先右键 DFC 核心记录坐标，再右键神盾终端
 * 把坐标写进去。潜行右键终端则清除绑定、恢复自动扫描。
 *
 * <p>绑定动作全部在服务端执行并记录在终端的 NBT 里，玩家手里这根工具只是
 * 携带一个坐标；即便工具丢失或核心被搬走，终端的绑定状态仍然有效。空白工具
 * 右键终端会退化为打开设置窗口，所以它同时也是终端的便捷遥控器。</p>
 */
public class ItemAegisLinker extends Item {

    public ItemAegisLinker() {
        setRegistryName(HBMZZ.MODID, "aegis_linker");
        setUnlocalizedName(HBMZZ.MODID + ".aegis_linker");
        setCreativeTab(CreativeTabs.REDSTONE);
        setMaxStackSize(1);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);
        TileEntity te = world.getTileEntity(pos);
        boolean onTerminal = world.getBlockState(pos).getBlock() instanceof BlockAegisTerminal;

        // Clicking a terminal: bind what we recorded, clear on sneak, or open
        // the settings window when the tool is still blank.
        if (onTerminal) {
            if (world.isRemote) {
                if (!player.isSneaking()) {
                    HBMZZ.proxy.openAegis(pos);
                }
                return EnumActionResult.SUCCESS;
            }
            TileAegisTerminal terminal = te instanceof TileAegisTerminal ? (TileAegisTerminal) te : null;
            if (terminal == null) {
                return EnumActionResult.SUCCESS;
            }
            if (player.isSneaking()) {
                terminal.clearBinding();
                say(player, TextFormatting.RED + "已清除绑定 · 神盾终端恢复自动扫描");
                return EnumActionResult.SUCCESS;
            }
            NBTTagCompound tag = stack.getTagCompound();
            BlockPos core = tag != null && tag.hasKey("core")
                    ? BlockPos.fromLong(tag.getLong("core")) : null;
            if (core == null) {
                say(player, TextFormatting.YELLOW + "尚未记录核心 · 请先右键 DFC 反应堆的核心");
                return EnumActionResult.SUCCESS;
            }
            terminal.setBinding(core, terminal.getBoundMeter());
            say(player, TextFormatting.GREEN + "已绑定核心 " + TextFormatting.WHITE + coord(core)
                    + TextFormatting.GREEN + " 到神盾终端");
            // Go straight into the settings window after binding. The bind
            // message alone used to leave the operator staring at chat with no
            // menu; asking the client to open it here is authoritative and also
            // works when the local right-click GUI open was missed.
            if (player instanceof net.minecraft.entity.player.EntityPlayerMP) {
                com.hbmzz.network.NetworkHandler.sendTo(
                        (net.minecraft.entity.player.EntityPlayerMP) player,
                        new com.hbmzz.network.AegisOpenGuiPacket(pos));
            }
            return EnumActionResult.SUCCESS;
        }

        // Clicking a DFC core records its coordinates in the tool.
        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        if (DFCReader.isCore(te)) {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag == null) {
                tag = new NBTTagCompound();
                stack.setTagCompound(tag);
            }
            tag.setLong("core", pos.toLong());
            say(player, TextFormatting.GREEN + "已记录核心 " + TextFormatting.WHITE + coord(pos)
                    + TextFormatting.GREEN + " · 现在右键神盾终端完成绑定");
            return EnumActionResult.SUCCESS;
        }
        return EnumActionResult.PASS;
    }

    private static void say(EntityPlayer player, String text) {
        player.sendMessage(new TextComponentString(text));
    }

    private static String coord(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && tag.hasKey("core")) {
            BlockPos c = BlockPos.fromLong(tag.getLong("core"));
            tooltip.add(TextFormatting.AQUA + "已记录核心: " + coord(c));
            tooltip.add(TextFormatting.GRAY + "右键神盾终端完成绑定");
        } else {
            tooltip.add(TextFormatting.GRAY + "右键 DFC 核心记录，再右键神盾终端绑定");
        }
        tooltip.add(TextFormatting.DARK_GRAY + "潜行右键终端: 清除绑定 / 恢复自动扫描");
    }
}

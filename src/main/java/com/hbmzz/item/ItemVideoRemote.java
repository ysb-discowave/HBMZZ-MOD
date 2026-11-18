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
import com.hbmzz.block.BlockVideoScreen;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.VideoLinkMessage;
import com.hbmzz.tile.TileEntityVideoBroadcaster;
import com.hbmzz.tile.TileEntityVideoScreen;
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
import net.minecraft.world.World;

/**
 * 大屏遥控器: the only way a wall learns which broadcaster feeds it.
 *
 * <p><b>Why an item and not a cable.</b> The monitor cable is a data line: it
 * records meters, cores and cameras, and it works by being pointed at two
 * machines in a row. Reusing it for the video wall meant a player carrying the
 * cable could no longer open anything with a plain click, and the wall silently
 * took over a tool that was not about video at all. A dedicated remote does one
 * thing, says so in its own messages, and cannot be confused with wiring.</p>
 *
 * <p><b>Why it is not an {@code ItemBlock}.</b> A remote is not a placeable
 * object. Extending {@code ItemBlock} (or overriding {@code onItemUse} to return
 * {@code FAIL}) would make a right-click on grass print "cannot place here" -
 * which is a bug report waiting to happen, and worse, it would swallow the click
 * that was meant for the screen. As a plain {@link Item} it simply never tries to
 * place anything.</p>
 *
 * <p><b>Two clicks.</b> Sneak-free right click on a broadcaster records it; right
 * click on a wall binds the wall to the recorded broadcaster. Both messages name
 * what happened, because "nothing happened" is the one answer that leaves a player
 * with no next step.</p>
 */
public class ItemVideoRemote extends Item {

    /** Where this client last aimed, so the bind click works before NBT syncs. */
    private static BlockPos clientTarget;

    public ItemVideoRemote() {
        setRegistryName(HBMZZ.MODID, "video_remote");
        setUnlocalizedName(HBMZZ.MODID + ".video_remote");
        setCreativeTab(CreativeTabs.REDSTONE);
        setMaxStackSize(1);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);
        TileEntity te = world.getTileEntity(pos);

        // Step 1: aim at the player (the broadcaster).
        if (te instanceof TileEntityVideoBroadcaster) {
            if (world.isRemote) {
                clientTarget = pos.toImmutable();
            } else {
                NBTTagCompound tag = stack.getTagCompound();
                if (tag == null) {
                    tag = new NBTTagCompound();
                    stack.setTagCompound(tag);
                }
                tag.setLong("player", pos.toLong());
                player.sendStatusMessage(new TextComponentString("遥控器已对准播放器"), true);
            }
            return EnumActionResult.SUCCESS;
        }

        // Step 2: bind the wall to it. Clicking any cell of the wall counts.
        BlockPos origin = te instanceof TileEntityVideoScreen
                ? pos : (world.getBlockState(pos).getBlock() instanceof BlockVideoScreen
                ? BlockVideoScreen.originOf(world, pos) : null);
        if (origin != null) {
            if (world.isRemote) {
                BlockPos target = clientTarget != null ? clientTarget : readTarget(stack);
                if (target == null) {
                    player.sendStatusMessage(new TextComponentString(
                            "遥控器还没有对准播放器：先右键播放器，再右键大屏"), true);
                    return EnumActionResult.SUCCESS;
                }
                NetworkHandler.sendToServer(new VideoLinkMessage(origin, target));
            }
            return EnumActionResult.SUCCESS;
        }
        // Anything else: no placement, no complaint, no click swallowed.
        return EnumActionResult.PASS;
    }

    @Override
    public void addInformation(ItemStack stack, World world, java.util.List<String> tooltip,
                               net.minecraft.client.util.ITooltipFlag flag) {
        tooltip.add(net.minecraft.util.text.TextFormatting.GREEN
                + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.video_remote.tip1"));
        tooltip.add(net.minecraft.util.text.TextFormatting.GRAY
                + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.video_remote.tip2"));
        tooltip.add(net.minecraft.util.text.TextFormatting.DARK_GRAY
                + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.video_remote.tip3"));
    }

    private static BlockPos readTarget(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey("player") ? BlockPos.fromLong(tag.getLong("player")) : null;
    }

    /** Cleared on world change so a remote from the previous save cannot bind. */
    public static void forget() {
        clientTarget = null;
    }
}

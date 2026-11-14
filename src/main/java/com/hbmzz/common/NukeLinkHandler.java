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

package com.hbmzz.common;

import com.hbmzz.block.BlockNuke;
import com.hbmzz.block.BlockNukeController;
import com.hbmzz.block.BlockNukeMonitor;
import com.hbmzz.tile.TileEntityNuke;
import com.hbmzz.tile.TileEntityNukeController;
import com.hbmzz.tile.TileEntityNukeMonitor;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * The wooden axe: the wiring tool for the nuclear side of the facility.
 *
 * <p>HBM's own bombs are wired by clicking them with the tool that belongs to
 * them, and that is the shape copied here - point at the device, point at the
 * thing that should talk to it, done. The reason it is a plain axe rather than a
 * new item is that an axe is already in every builder's inventory, it is
 * obviously not a machine part, and sneak-right-click on a block does nothing
 * else in vanilla, so the gesture is free.</p>
 *
 * <p>Which click does what, and why:</p>
 * <ul>
 *   <li><b>Sneak-right-click the bomb</b> records it in the axe. Recording the
 *       same bomb again clears the record - the second thing a player does after
 *       recording is usually "wait, which one did I record?".</li>
 *   <li><b>Sneak-right-click a bomb that is counting down</b> aborts the
 *       countdown instead. The panic gesture has to be the one already in the
 *       player's hand, and it has to work without a monitor.</li>
 *   <li><b>Sneak-right-click a controller or a monitor</b> binds it to the
 *       recorded device. On a monitor with nothing recorded it unbinds, which is
 *       the only way to stop watching without breaking the screen.</li>
 * </ul>
 *
 * <p>Nothing is bound by right-clicking a device with no axe: that click is
 * reserved for the device's own readout, so a player who is not wiring anything
 * gets the numbers instead of a silent tool.</p>
 */
public class NukeLinkHandler {

    /** Position of the recorded device, as a {@code long}. */
    public static final String TAG_NUKE = "nukePos";
    public static final String TAG_DIM = "nukeDim";

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player == null || !player.isSneaking()) {
            return;
        }
        ItemStack held = event.getItemStack();
        if (held.isEmpty() || !(held.getItem() instanceof ItemAxe)) {
            return;
        }
        World world = event.getWorld();
        BlockPos pos = event.getPos();
        Block block = world.getBlockState(pos).getBlock();
        if (!(block instanceof BlockNuke) && !(block instanceof BlockNukeController)
                && !(block instanceof BlockNukeMonitor)) {
            return;
        }
        // Cancelled on both sides. On the client this stops the axe's swing
        // animation; on the server it stops the block's own onBlockActivated from
        // also running, which would print the readout on top of the binding
        // message.
        event.setCanceled(true);
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        if (world.isRemote) {
            return;
        }

        if (block instanceof BlockNuke) {
            onNuke(world, pos, player, held);
        } else if (block instanceof BlockNukeController) {
            onController(world, pos, player, held);
        } else {
            onMonitor(world, pos, player, held);
        }
    }

    private void onNuke(World world, BlockPos pos, EntityPlayer player, ItemStack axe) {
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileEntityNuke && ((TileEntityNuke) te).abort()) {
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_aborted"));
            return;
        }
        NBTTagCompound tag = tagOf(axe);
        if (tag.hasKey(TAG_NUKE) && BlockPos.fromLong(tag.getLong(TAG_NUKE)).equals(pos)
                && tag.getInteger(TAG_DIM) == world.provider.getDimension()) {
            tag.removeTag(TAG_NUKE);
            tag.removeTag(TAG_DIM);
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_forgotten"));
            return;
        }
        tag.setLong(TAG_NUKE, pos.toLong());
        tag.setInteger(TAG_DIM, world.provider.getDimension());
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_recorded",
                pos.getX(), pos.getY(), pos.getZ()));
    }

    private void onController(World world, BlockPos pos, EntityPlayer player, ItemStack axe) {
        NBTTagCompound tag = tagOf(axe);
        if (!tag.hasKey(TAG_NUKE)) {
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_need_record"));
            return;
        }
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityNukeController)) {
            return;
        }
        BlockPos device = BlockPos.fromLong(tag.getLong(TAG_NUKE));
        int dim = tag.getInteger(TAG_DIM);
        ((TileEntityNukeController) te).bind(device, dim);
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_linked",
                device.getX(), device.getY(), device.getZ(), dim));
    }

    private void onMonitor(World world, BlockPos pos, EntityPlayer player, ItemStack axe) {
        NBTTagCompound tag = tagOf(axe);
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof TileEntityNukeMonitor)) {
            return;
        }
        TileEntityNukeMonitor monitor = (TileEntityNukeMonitor) te;
        if (!tag.hasKey(TAG_NUKE)) {
            if (monitor.hasNuke()) {
                monitor.unbind();
                player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_unlinked"));
            } else {
                player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_need_record"));
            }
            return;
        }
        BlockPos device = BlockPos.fromLong(tag.getLong(TAG_NUKE));
        int dim = tag.getInteger(TAG_DIM);
        monitor.bind(device, dim);
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.nuke_linked",
                device.getX(), device.getY(), device.getZ(), dim));
    }

    private static NBTTagCompound tagOf(ItemStack axe) {
        NBTTagCompound tag = axe.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            axe.setTagCompound(tag);
        }
        return tag;
    }

    /** The device an axe has recorded, or {@code null}. Used by the tooltip. */
    public static BlockPos recorded(ItemStack axe) {
        NBTTagCompound tag = axe == null ? null : axe.getTagCompound();
        return tag != null && tag.hasKey(TAG_NUKE) ? BlockPos.fromLong(tag.getLong(TAG_NUKE)) : null;
    }

    /** The dimension of the recorded device. */
    public static int recordedDim(ItemStack axe) {
        NBTTagCompound tag = axe == null ? null : axe.getTagCompound();
        return tag == null ? 0 : tag.getInteger(TAG_DIM);
    }
}

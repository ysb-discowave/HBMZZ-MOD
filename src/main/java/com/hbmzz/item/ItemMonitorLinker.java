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
import com.hbmzz.block.BlockDFCMonitor;
import com.hbmzz.dfc.DFCReader;
import com.hbmzz.tile.TileEntityDFCMonitor;
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
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import java.util.List;

public class ItemMonitorLinker extends Item {

    /**
     * How far a camera may sit from the screen that reads it, in blocks.
     *
     * <p>Not a technical limit - the feed is pulled from the chunk either way -
     * but a cable that works across the map makes "which camera is this?" a
     * question the player has to answer by breaking things. 64 also keeps the
     * camera inside the same loaded-chunk neighbourhood as most screens.</p>
     */
    public static final int MAX_CAMERA_RANGE = 64;

    public ItemMonitorLinker() {
        setRegistryName(HBMZZ.MODID, "monitor_linker");
        setUnlocalizedName(HBMZZ.MODID + ".monitor_linker");
        setCreativeTab(CreativeTabs.REDSTONE);
        setMaxStackSize(1);
    }

    @Override
    public EnumActionResult onItemUse(EntityPlayer player, World world, BlockPos pos, EnumHand hand,
                                      EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack stack = player.getHeldItem(hand);

        // The console opens its own editor through the block, so the item must
        // not swallow the click: it is itself IControllable, and recording it
        // here would mean the editor could never be opened while carrying the
        // tool. Sneaking still records it, which is how one console addresses
        // another in the node editor.
        if (world.getBlockState(pos).getBlock() instanceof com.hbmzz.cecp.block.BlockCtrlConsole
                && !player.isSneaking()) {
            return EnumActionResult.PASS;
        }

        // The panel is opened through the data line, not around it: the line is
        // how a slot learns which machine it owns, so a player carrying one is
        // exactly the player who wants the window. Held normally it opens the
        // panel; held sneaking it binds the recorded machine into the first free
        // slot, which is the whole binding flow in two clicks.
        if (world.getBlockState(pos).getBlock() instanceof com.hbmzz.block.BlockCtrlPanel) {
            if (world.isRemote) {
                if (!player.isSneaking()) {
                    HBMZZ.proxy.openControlPanel(pos);
                }
                return EnumActionResult.SUCCESS;
            }
            if (player.isSneaking()) {
                return bindIntoPanel(player, world, pos, stack);
            }
            return EnumActionResult.SUCCESS;
        }

        // A screen's filler cells forward their clicks to the controller cell, so
        // the cable has to look through them as well - otherwise binding by
        // clicking the right half of a 4x3 screen records the filler instead.
        BlockPos origin = monitorOrigin(world, pos);
        boolean onMonitor = world.getBlockState(origin).getBlock() instanceof BlockDFCMonitor;

        // Carrying the cable and clicking a screen means "bind what I recorded".
        // With nothing recorded there is nothing to bind, and silence would read
        // as a broken click - so the click falls back to opening the terminal,
        // which is exactly what it did before binding existed.
        if (onMonitor && world.isRemote && !hasRecording(stack.getTagCompound())) {
            HBMZZ.proxy.openMonitor(origin);
            return EnumActionResult.SUCCESS;
        }

        if (world.isRemote) {
            return EnumActionResult.SUCCESS;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        TileEntity te = world.getTileEntity(onMonitor ? origin : pos);

        if (DFCReader.isPowerMeter(te)) {
            tag.setLong("meter", pos.toLong());
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.meter_set"));
            return EnumActionResult.SUCCESS;
        }
        if (DFCReader.isCore(te)) {
            tag.setLong("core", pos.toLong());
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.core_set"));
            return EnumActionResult.SUCCESS;
        }
        // The camera is recorded exactly like the core: point at it, then point
        // at the screen. Before this existed the cable fell through to the
        // generic "recorded a coordinate" path, which records the position but
        // leaves the monitor with no way to ever read it - the camera could be
        // placed, aimed and right-clicked, and still never appear on a screen.
        if (te instanceof com.hbmzz.tile.TileEntityMonitorCamera) {
            com.hbmzz.tile.TileEntityMonitorCamera camera =
                    (com.hbmzz.tile.TileEntityMonitorCamera) te;
            tag.setLong("cam", pos.toLong());
            tag.setString("camName", camera.getName());
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.camera_set", camera.getName()));
            return EnumActionResult.SUCCESS;
        }
        // The console itself is controllable too, which is how one console can
        // drive another's buttons through the node editor.
        if (te instanceof com.hbmzz.cecp.IControllable) {
            tag.setLong("ctrl", pos.toLong());
            tag.setString("ctrlName", "控制台");
            recordPosition(tag, pos);
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_set", "控制台"));
            return EnumActionResult.SUCCESS;
        }
        // Any machine that speaks NTM's control-panel protocol can be recorded
        // here. It is not bound yet - the panel's sidebar picks which slot it
        // goes into - but the position and name ride along in the item so the
        // binding click does not have to aim at the machine again.
        if (com.hbmzz.ctrl.MachineBridge.isControllable(te)) {
            tag.setLong("ctrl", pos.toLong());
            tag.setString("ctrlName", com.hbmzz.ctrl.MachineBridge.describe(te));
            recordPosition(tag, pos);
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_set",
                    com.hbmzz.ctrl.MachineBridge.describe(te)));
            return EnumActionResult.SUCCESS;
        }        if (onMonitor) {
            if (te instanceof TileEntityDFCMonitor) {
                TileEntityDFCMonitor monitor = (TileEntityDFCMonitor) te;
                boolean changed = false;
                if (tag.hasKey("meter")) {
                    monitor.setMeterPos(BlockPos.fromLong(tag.getLong("meter")));
                    changed = true;
                    player.sendMessage(new TextComponentTranslation("hbmzz.msg.meter_bound"));
                }
                if (tag.hasKey("core")) {
                    monitor.setCorePos(BlockPos.fromLong(tag.getLong("core")));
                    changed = true;
                    player.sendMessage(new TextComponentTranslation("hbmzz.msg.core_bound"));
                }
                if (tag.hasKey("cam")) {
                    changed = true;
                    if (player.isSneaking()) {
                        // Sneak to cut the feed. Without this the only way to
                        // stop watching a camera would be to break the screen.
                        monitor.setCameraEnabled(false);
                        player.sendMessage(new TextComponentTranslation("hbmzz.msg.camera_clear"));
                    } else {
                        bindCamera(world, player, monitor, BlockPos.fromLong(tag.getLong("cam")));
                    }
                }
                if (!changed && player.isSneaking()) {
                    // Sneaking skipped the block, so no window opened either;
                    // say why nothing happened instead of saying nothing.
                    player.sendMessage(new TextComponentTranslation("hbmzz.msg.nothing"));
                }
                return EnumActionResult.SUCCESS;
            }
        }
        // Any other block with a tile entity still gets recorded. Cursed Edition's
        // multi detonator works this way - it stores whatever you point at - and
        // the point is that the linker tab then shows the row with a reason
        // instead of an empty list, which is what a silent drop looked like from
        // the player's side.
        if (te != null) {
            recordPosition(tag, pos);
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_recorded_only",
                    pos.getX(), pos.getY(), pos.getZ()));
            return EnumActionResult.SUCCESS;
        }
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.invalid"));
        return EnumActionResult.PASS;
    }

    /**
     * The controller cell behind a click, or {@code pos} itself when the click
     * landed somewhere else.
     */
    private static BlockPos monitorOrigin(World world, BlockPos pos) {
        if (world.getBlockState(pos).getBlock() instanceof com.hbmzz.block.BlockDFCMonitorPart) {
            BlockPos controller = com.hbmzz.block.BlockDFCMonitorPart.findController(world, pos);
            if (controller != null) {
                return controller;
            }
        }
        return pos;
    }

    /** True when the cable remembers something that a screen can be given. */
    private static boolean hasRecording(NBTTagCompound tag) {
        return tag != null && (tag.hasKey("meter") || tag.hasKey("core") || tag.hasKey("cam"));
    }

    /**
     * Connects a recorded camera to a screen.
     *
     * <p>Checked rather than assumed: the position was recorded minutes ago and
     * the camera may have been broken since, and a screen reading a dead camera
     * would look exactly like a screen with no camera at all. Both refusals say
     * why, because "nothing happened" is the one answer that leaves the player
     * with no next step.</p>
     */
    private static boolean bindCamera(World world, EntityPlayer player,
                                      TileEntityDFCMonitor monitor, BlockPos camPos) {
        TileEntity camTe = world.getTileEntity(camPos);
        if (!(camTe instanceof com.hbmzz.tile.TileEntityMonitorCamera)) {
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.camera_lost"));
            return false;
        }
        if (camPos.distanceSq(monitor.getPos()) > MAX_CAMERA_RANGE * MAX_CAMERA_RANGE) {
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.camera_far", MAX_CAMERA_RANGE));
            return false;
        }
        com.hbmzz.tile.TileEntityMonitorCamera camera =
                (com.hbmzz.tile.TileEntityMonitorCamera) camTe;
        monitor.setCameraPos(camPos);
        monitor.setCameraEnabled(true);
        // Switching the page is the difference between "bound" and "obviously
        // bound": the operator is standing in front of a screen whose first
        // three pages say nothing about cameras, so a silent bind reads as a
        // failure and the next thing they do is break the block and retry.
        monitor.setPage(TileEntityDFCMonitor.PAGE_CAMERA);
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.camera_bound", camera.getName()));
        return true;
    }

    /**
     * Sneak-right-click on a panel with a recorded machine: put it in the first
     * free slot.
     *
     * <p>The alternative would be a chip per slot in the window, which exists
     * too - but the fast path for "wire this reactor to this wall" should not
     * require opening a window at all.</p>
     */
    private EnumActionResult bindIntoPanel(EntityPlayer player, World world, BlockPos pos, ItemStack stack) {
        TileEntity te = world.getTileEntity(pos);
        if (!(te instanceof com.hbmzz.tile.TileEntityCtrlPanel)) {
            return EnumActionResult.SUCCESS;
        }
        com.hbmzz.tile.TileEntityCtrlPanel panel = (com.hbmzz.tile.TileEntityCtrlPanel) te;
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || !tag.hasKey("ctrl")) {
            player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_none"));
            return EnumActionResult.SUCCESS;
        }
        BlockPos machine = BlockPos.fromLong(tag.getLong("ctrl"));
        for (int i = 0; i < com.hbmzz.ctrl.CtrlPanel.LINKS; i++) {
            if (panel.getPanel().links[i].isEmpty()) {
                if (panel.bind(i, machine)) {
                    player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_bound",
                            com.hbmzz.ctrl.CtrlLink.letter(i), panel.describeSlot(i)));
                } else {
                    player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_far"));
                }
                return EnumActionResult.SUCCESS;
            }
        }
        player.sendMessage(new TextComponentTranslation("hbmzz.msg.ctrl_full"));
        return EnumActionResult.SUCCESS;
    }

    /**
     * Appends a position to the cable's "recorded machines" list, skipping
     * duplicates. The control console's linker tab reads this list, which is
     * how CE's multi-detonator doubles as a link tool - we have no detonator,
     * so the cable carries the list instead.
     */
    private static void recordPosition(NBTTagCompound tag, BlockPos pos) {
        net.minecraft.nbt.NBTTagList list = tag.getTagList("pos", 4); // 4 = long
        for (int i = 0; i < list.tagCount(); i++) {
            if (((net.minecraft.nbt.NBTTagLong) list.get(i)).getLong() == pos.toLong()) {
                return;
            }
        }
        net.minecraft.nbt.NBTTagLong entry = new net.minecraft.nbt.NBTTagLong(pos.toLong());
        list.appendTag(entry);
        tag.setTag("pos", list);
    }

    /**
     * {@code {{x...}, {y...}, {z...}}} of every recorded machine, or {@code null}
     * when the cable has not recorded anything yet. Mirrors
     * {@code ItemMultiDetonator.getLocations} from NTM: Cursed Edition, which is
     * what the console's linker tab expects.
     */
    public static int[][] getLocations(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return positionsFromTag(stack.getTagCompound());
    }

    /**
     * The coordinate list in a tag, without going through an item stack.
     *
     * <p>Split out so the format can be verified on its own - the linker tab
     * reads this list, and an empty list is indistinguishable from "the player
     * never recorded anything".</p>
     */
    public static int[][] positionsFromTag(NBTTagCompound tag) {
        if (tag == null || !tag.hasKey("pos", 9)) {
            return null;
        }
        net.minecraft.nbt.NBTTagList list = tag.getTagList("pos", 4);
        int n = list.tagCount();
        if (n == 0) {
            return null;
        }
        int[][] out = new int[3][n];
        for (int i = 0; i < n; i++) {
            BlockPos p = BlockPos.fromLong(((net.minecraft.nbt.NBTTagLong) list.get(i)).getLong());
            out[0][i] = p.getX();
            out[1][i] = p.getY();
            out[2][i] = p.getZ();
        }
        return out;
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.DARK_GREEN + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.linker.desc"));
        tooltip.add(TextFormatting.DARK_GRAY + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.linker.desc2"));
        tooltip.add(TextFormatting.DARK_GRAY + net.minecraft.util.text.translation.I18n.translateToLocal("hbmzz.linker.desc3"));
        NBTTagCompound tag = stack.getTagCompound();
        if (tag != null && tag.hasKey("meter")) {
            tooltip.add(TextFormatting.AQUA + "meter: " + BlockPos.fromLong(tag.getLong("meter")));
        }
        if (tag != null && tag.hasKey("core")) {
            tooltip.add(TextFormatting.AQUA + "core: " + BlockPos.fromLong(tag.getLong("core")));
        }
        if (tag != null && tag.hasKey("ctrl")) {
            tooltip.add(TextFormatting.AQUA + "machine: " + tag.getString("ctrlName"));
        }
        if (tag != null && tag.hasKey("cam")) {
            tooltip.add(TextFormatting.AQUA + "camera: " + tag.getString("camName")
                    + " @ " + BlockPos.fromLong(tag.getLong("cam")));
        }
        int[][] recorded = getLocations(stack);
        if (recorded != null) {
            tooltip.add(TextFormatting.GRAY + net.minecraft.util.text.translation.I18n
                    .translateToLocalFormatted("hbmzz.linker.count", recorded[0].length));
        }
    }
}
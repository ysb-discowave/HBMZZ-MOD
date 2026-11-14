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

package com.hbmzz;

import com.hbmzz.block.BlockCtrlPanel;
import com.hbmzz.block.BlockDFCMonitor;
import com.hbmzz.block.BlockDFCMonitorPart;
import com.hbmzz.block.BlockMonitorCamera;
import com.hbmzz.item.ItemAmmo;
import com.hbmzz.item.ItemBlockMonitor;
import com.hbmzz.item.ItemCoil;
import com.hbmzz.item.ItemGun;
import com.hbmzz.item.ItemHackerTablet;
import com.hbmzz.item.ItemJetpack;
import com.hbmzz.item.ItemMonitorLinker;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

public final class ModContent {

    public static final Block BLOCK_MONITOR = new BlockDFCMonitor();
    /**
     * Filler cell of a wall screen. Registered as a block but deliberately not
     * as an item: it only ever exists as part of a placed panel. Registered
     * after {@link #BLOCK_MONITOR} because the controller's placement hook
     * reaches for it.
     */
    public static final Block BLOCK_MONITOR_PART = new BlockDFCMonitorPart();
    /** A fixed observation camera that can be linked into the DFC monitor. */
    public static final Block BLOCK_MONITOR_CAMERA = new BlockMonitorCamera();
    /** Machine control panel: the block that actually drives other machines. */
    public static final Block BLOCK_CTRL_PANEL = new BlockCtrlPanel();
    /** Cursed-Edition style console: free-form control layout with a node graph. */
    public static final Block BLOCK_CTRL_CONSOLE = new com.hbmzz.cecp.block.BlockCtrlConsole();
    /** Takes over the screens of the players a selector names. */
    public static final Block BLOCK_VIDEO_BROADCASTER = new com.hbmzz.block.BlockVideoBroadcaster();
    public static final Item ITEM_LINKER = new ItemMonitorLinker();
    /** The hacker tablet: opens a terminal that writes machine fields directly. */
    public static final Item ITEM_HACKER_TABLET = new ItemHackerTablet();
    public static final Item ITEM_BLOCK_MONITOR = new ItemBlockMonitor(BLOCK_MONITOR);
    public static final Item ITEM_BLOCK_MONITOR_CAMERA = new ItemBlock(BLOCK_MONITOR_CAMERA);
    public static final Item ITEM_BLOCK_CTRL_PANEL = new ItemBlock(BLOCK_CTRL_PANEL);
    public static final Item ITEM_BLOCK_CTRL_CONSOLE = new ItemBlock(BLOCK_CTRL_CONSOLE);
    public static final Item ITEM_BLOCK_VIDEO_BROADCASTER = new ItemBlock(BLOCK_VIDEO_BROADCASTER);

    public static final Item ITEM_SPEED_COIL = new ItemCoil(ItemCoil.CoilType.SPEED);
    public static final Item ITEM_JUMP_COIL = new ItemCoil(ItemCoil.CoilType.JUMP);
    public static final Item ITEM_REGEN_COIL = new ItemCoil(ItemCoil.CoilType.REGEN);
    public static final Item ITEM_JETPACK = new ItemJetpack();

    public static final Item ITEM_AMMO = new ItemAmmo();
    public static final Item ITEM_GUN_PISTOL = new ItemGun(ItemGun.GunType.SIDEARM);
    public static final Item ITEM_GUN_RIFLE = new ItemGun(ItemGun.GunType.CARBINE);
    public static final Item ITEM_GUN_SHOTGUN = new ItemGun(ItemGun.GunType.BREAKER);

    private ModContent() {
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        event.getRegistry().register(BLOCK_MONITOR);
        event.getRegistry().register(BLOCK_MONITOR_PART);
        event.getRegistry().register(BLOCK_MONITOR_CAMERA);
        event.getRegistry().register(BLOCK_CTRL_PANEL);
        event.getRegistry().register(BLOCK_CTRL_CONSOLE);
        event.getRegistry().register(BLOCK_VIDEO_BROADCASTER);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        ITEM_BLOCK_MONITOR.setRegistryName(BLOCK_MONITOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_MONITOR);
        ITEM_BLOCK_MONITOR_CAMERA.setRegistryName(BLOCK_MONITOR_CAMERA.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_MONITOR_CAMERA);
        ITEM_BLOCK_CTRL_PANEL.setRegistryName(BLOCK_CTRL_PANEL.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_CTRL_PANEL);
        ITEM_BLOCK_CTRL_CONSOLE.setRegistryName(BLOCK_CTRL_CONSOLE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_CTRL_CONSOLE);
        ITEM_BLOCK_VIDEO_BROADCASTER.setRegistryName(BLOCK_VIDEO_BROADCASTER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_VIDEO_BROADCASTER);
        event.getRegistry().register(ITEM_LINKER);
        event.getRegistry().register(ITEM_HACKER_TABLET);
        event.getRegistry().register(ITEM_SPEED_COIL);
        event.getRegistry().register(ITEM_JUMP_COIL);
        event.getRegistry().register(ITEM_REGEN_COIL);
        event.getRegistry().register(ITEM_JETPACK);
        event.getRegistry().register(ITEM_AMMO);
        event.getRegistry().register(ITEM_GUN_PISTOL);
        event.getRegistry().register(ITEM_GUN_RIFLE);
        event.getRegistry().register(ITEM_GUN_SHOTGUN);
    }

    @SubscribeEvent
    public static void registerRecipes(RegistryEvent.Register<IRecipe> event) {
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "dfc_monitor"),
                new ItemStack(BLOCK_MONITOR),
                "IGI",
                "GRG",
                "ICI",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "dfc_monitor"));

        // Control panel: steel plate, a dial's worth of comparators and a bed
        // of redstone. Nothing rare - a control panel is something you build a
        // few of, not one per world.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "control_panel"),
                new ItemStack(BLOCK_CTRL_PANEL),
                "IRI",
                "GCG",
                "IRI",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "control_panel"));

        // Cursed-Edition style console: the expensive one. It carries a node
        // graph and drives redstone, so it asks for a comparator, a repeater and
        // an observer rather than plain redstone dust.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "ctrl_console"),
                new ItemStack(BLOCK_CTRL_CONSOLE),
                "ICI",
                "ROR",
                "III",
                'I', Items.IRON_INGOT,
                'C', Items.COMPARATOR,
                'R', Items.REPEATER,
                'O', Item.getItemFromBlock(Blocks.OBSERVER)
        ).setRegistryName(HBMZZ.MODID, "ctrl_console"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "monitor_linker"),
                new ItemStack(ITEM_LINKER),
                " RG",
                "RER",
                "GR ",
                'R', Items.REDSTONE,
                'G', Items.GOLD_INGOT,
                'E', Items.ENDER_PEARL
        ).setRegistryName(HBMZZ.MODID, "monitor_linker"));

        // Hacker tablet: a glass face for the screen, a pearl for the uplink, a
        // comparator for the logic that reads a machine's internals. Kept
        // deliberately expensive - it is the one item in the mod that can skip
        // the control rods entirely, and it should not be something a player
        // crafts before they have built their first reactor.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "hacker_tablet"),
                new ItemStack(ITEM_HACKER_TABLET),
                "GRG",
                "IPI",
                "ICI",
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'I', Items.IRON_INGOT,
                'P', Items.ENDER_PEARL,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "hacker_tablet"));

        // Video broadcaster: a glass face to be the screen, an observer because
        // it is the block that watches for a signal, and redstone to carry it.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "video_broadcaster"),
                new ItemStack(BLOCK_VIDEO_BROADCASTER),
                "IGI",
                "ROR",
                "III",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'O', Item.getItemFromBlock(Blocks.OBSERVER)
        ).setRegistryName(HBMZZ.MODID, "video_broadcaster"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "monitor_camera"),
                new ItemStack(BLOCK_MONITOR_CAMERA),
                "IGI",
                "GRG",
                "IRI",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "monitor_camera"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "speed_coil"),
                new ItemStack(ITEM_SPEED_COIL),
                "G G",
                " R ",
                "G G",
                'G', Items.GOLD_INGOT,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "speed_coil"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "jump_coil"),
                new ItemStack(ITEM_JUMP_COIL),
                "S S",
                " F ",
                "S S",
                'S', Items.SLIME_BALL,
                'F', Items.FEATHER
        ).setRegistryName(HBMZZ.MODID, "jump_coil"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "regen_coil"),
                new ItemStack(ITEM_REGEN_COIL),
                "M M",
                " S ",
                "M M",
                'M', Items.SPECKLED_MELON,
                'S', Items.SLIME_BALL
        ).setRegistryName(HBMZZ.MODID, "regen_coil"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "jetpack"),
                new ItemStack(ITEM_JETPACK),
                "III",
                "IBI",
                "R R",
                'I', Items.IRON_INGOT,
                'B', Items.BLAZE_ROD,
                'R', Item.getItemFromBlock(Blocks.REDSTONE_BLOCK)
        ).setRegistryName(HBMZZ.MODID, "jetpack"));

        // ---- ammunition ----
        event.getRegistry().register(new ShapelessOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "ammo_9mm"),
                new ItemStack(ITEM_AMMO, 16, 0),
                Items.IRON_NUGGET, Items.GUNPOWDER, Items.GUNPOWDER
        ).setRegistryName(HBMZZ.MODID, "ammo_9mm"));

        event.getRegistry().register(new ShapelessOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "ammo_556"),
                new ItemStack(ITEM_AMMO, 12, 1),
                Items.IRON_NUGGET, Items.IRON_NUGGET, Items.GUNPOWDER, Items.GUNPOWDER, Items.GUNPOWDER
        ).setRegistryName(HBMZZ.MODID, "ammo_556"));

        event.getRegistry().register(new ShapelessOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "ammo_12ga"),
                new ItemStack(ITEM_AMMO, 8, 2),
                Items.IRON_NUGGET, Items.GUNPOWDER, Items.GUNPOWDER, Items.PAPER
        ).setRegistryName(HBMZZ.MODID, "ammo_12ga"));

        // ---- firearms ----
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "gun_pistol"),
                new ItemStack(ITEM_GUN_PISTOL),
                "I I",
                "III",
                " RG",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'G', Items.GUNPOWDER
        ).setRegistryName(HBMZZ.MODID, "gun_pistol"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "gun_rifle"),
                new ItemStack(ITEM_GUN_RIFLE),
                "III",
                "IGI",
                "IR ",
                'I', Items.IRON_INGOT,
                'G', Items.GUNPOWDER,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "gun_rifle"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "gun_shotgun"),
                new ItemStack(ITEM_GUN_SHOTGUN),
                "III",
                "GG ",
                "RR ",
                'I', Items.IRON_INGOT,
                'G', Items.GUNPOWDER,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "gun_shotgun"));
    }
}
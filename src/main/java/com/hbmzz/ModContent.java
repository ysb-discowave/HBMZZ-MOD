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
import com.hbmzz.item.ItemAITerminal;
import com.hbmzz.item.ItemAmmo;
import com.hbmzz.item.ItemBlockMonitor;
import com.hbmzz.item.ItemCoil;
import com.hbmzz.item.ItemGun;
import com.hbmzz.item.ItemHackerTablet;
import com.hbmzz.item.ItemJetpack;
import com.hbmzz.item.ItemMonitorLinker;
import com.hbmzz.item.ItemVideoRemote;
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

    // Aegis telemetry terminal (independent re-implementation).
    public static final Block BLOCK_AEGIS_TERMINAL = new com.hbmzz.aegis.BlockAegisTerminal();
    public static final Block BLOCK_AEGIS_CELL = new com.hbmzz.aegis.BlockAegisCell();
    public static final Item ITEM_BLOCK_AEGIS_TERMINAL = new ItemBlock(BLOCK_AEGIS_TERMINAL);
    public static final Item ITEM_BLOCK_AEGIS_CELL = new ItemBlock(BLOCK_AEGIS_CELL);
    public static final Item ITEM_AEGIS_LINKER = new com.hbmzz.item.ItemAegisLinker();
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
    /**
     * The wall the broadcast lands on: a multi-cell panel that plays the linked
     * player's clip in the world. Its own filler cells are the same block with
     * {@code part=true}, so a wall stays one block type in the creative tab.
     */
    public static final Block BLOCK_VIDEO_SCREEN = new com.hbmzz.block.BlockVideoScreen();
    public static final Item ITEM_LINKER = new ItemMonitorLinker();
    /** 大屏遥控器: links a wall to its player. Never placed, never a block item. */
    public static final Item ITEM_VIDEO_REMOTE = new ItemVideoRemote();
    /** The hacker tablet: opens a terminal that writes machine fields directly. */
    public static final Item ITEM_HACKER_TABLET = new ItemHackerTablet();
    /** The AI terminal: natural-language commands to the AI, with local fallbacks. */
    public static final Item ITEM_AI_TERMINAL = new ItemAITerminal();
    public static final Item ITEM_BLOCK_MONITOR = new ItemBlockMonitor(BLOCK_MONITOR);
    public static final Item ITEM_BLOCK_MONITOR_CAMERA = new ItemBlock(BLOCK_MONITOR_CAMERA);
    public static final Item ITEM_BLOCK_CTRL_PANEL = new ItemBlock(BLOCK_CTRL_PANEL);
    public static final Item ITEM_BLOCK_CTRL_CONSOLE = new ItemBlock(BLOCK_CTRL_CONSOLE);
    public static final Item ITEM_BLOCK_VIDEO_BROADCASTER = new ItemBlock(BLOCK_VIDEO_BROADCASTER);
    public static final Item ITEM_BLOCK_VIDEO_SCREEN =
            new com.hbmzz.item.ItemBlockVideoScreen(BLOCK_VIDEO_SCREEN);

    // ---- nuclear accident system -------------------------------------------
    /** The one bomb. A plain ItemBlock would do, except it cannot explain a refusal. */
    public static final Block BLOCK_NUKE = new com.hbmzz.block.BlockNuke();
    public static final Block BLOCK_NUKE_CONTROLLER = new com.hbmzz.block.BlockNukeController();
    public static final Block BLOCK_NUKE_MONITOR = new com.hbmzz.block.BlockNukeMonitor();
    public static final Block BLOCK_CODE_BROADCASTER = new com.hbmzz.block.BlockCodeBroadcaster();
    public static final Block BLOCK_ALERT_DISPLAY = new com.hbmzz.block.BlockAlertDisplay();
    /** Redstone out while a DFC blast is running out of time. Detection lives in DfcBlastWatch. */
    public static final Block BLOCK_DFC_BLAST_SENSOR = new com.hbmzz.blast.BlockDfcBlastSensor();
    /** Full power for as long as any DFC core is collapsing; drops out at the detonation. */
    public static final Block BLOCK_DFC_COLLAPSE_SENSOR = new com.hbmzz.blast.BlockDfcCollapseSensor();
    /** Redstone out on an RBMK nuclear blast. Detection lives in RbmkEventWatch. */
    public static final Block BLOCK_RBMK_BLAST_SENSOR = new com.hbmzz.rbmk.BlockRbmkBlastSensor();
    /** Full power for as long as an RBMK is jumping its rods; drops out at the meltdown. */
    public static final Block BLOCK_RBMK_ROD_JUMP_SENSOR = new com.hbmzz.rbmk.BlockRbmkRodJumpSensor();
    /**
     * Self-contained HBMZZ reactor core. Uses the control-panel model and
     * textures - no new art, so it can never arrive purple-and-black.
     */
    public static final Block BLOCK_REACTOR = new com.hbmzz.reactor.block.BlockReactor();
    /**
     * The status indicator lamp: a fixed disc on the block face that shows the
     * colour of the active facility code. Purely cosmetic - the colour is a
     * client-side rendering decision driven by the synced alert state, so the
     * tile entity only needs to remember it exists.
     */
    public static final Block BLOCK_STATUS_LAMP = new com.hbmzz.block.BlockStatusLamp();
    /**
     * The rotating code alarm beacon: a spinning coloured light that wakes the
     * moment the active code climbs above the configured safe level. Same
     * client-side rendering story as the status lamp - the block itself holds no
     * state beyond its facing.
     */
    public static final Block BLOCK_ALARM_BEACON = new com.hbmzz.block.BlockAlarmBeacon();
    public static final Item ITEM_BLOCK_NUKE = new com.hbmzz.item.ItemBlockNuke(BLOCK_NUKE);
    public static final Item ITEM_BLOCK_NUKE_CONTROLLER = new ItemBlock(BLOCK_NUKE_CONTROLLER);
    public static final Item ITEM_BLOCK_NUKE_MONITOR = new ItemBlock(BLOCK_NUKE_MONITOR);
    public static final Item ITEM_BLOCK_CODE_BROADCASTER = new ItemBlock(BLOCK_CODE_BROADCASTER);
    public static final Item ITEM_BLOCK_ALERT_DISPLAY = new ItemBlock(BLOCK_ALERT_DISPLAY);
    public static final Item ITEM_BLOCK_DFC_BLAST_SENSOR = new ItemBlock(BLOCK_DFC_BLAST_SENSOR);
    public static final Item ITEM_BLOCK_DFC_COLLAPSE_SENSOR = new ItemBlock(BLOCK_DFC_COLLAPSE_SENSOR);
    public static final Item ITEM_BLOCK_RBMK_BLAST_SENSOR = new ItemBlock(BLOCK_RBMK_BLAST_SENSOR);
    public static final Item ITEM_BLOCK_RBMK_ROD_JUMP_SENSOR = new ItemBlock(BLOCK_RBMK_ROD_JUMP_SENSOR);
    public static final Item ITEM_BLOCK_REACTOR = new ItemBlock(BLOCK_REACTOR);
    public static final Item ITEM_BLOCK_STATUS_LAMP = new ItemBlock(BLOCK_STATUS_LAMP);
    public static final Item ITEM_BLOCK_ALARM_BEACON = new ItemBlock(BLOCK_ALARM_BEACON);

    // ---- multi-block reactor rig ------------------------------------------
    /** The authoritative core of the multi-block reactor. */
    public static final Block BLOCK_REACTOR_CORE = new com.hbmzz.reactor.block.BlockReactorCore();
    /** Emitter: more fuel -> higher rated output and more waste heat. */
    public static final Block BLOCK_REACTOR_EMITTER =
            new com.hbmzz.reactor.block.BlockReactorComponent(com.hbmzz.reactor.block.BlockReactorComponent.ComponentType.EMITTER);
    /** Absorber: more cooling surface -> a fuller coolant loop carries more. */
    public static final Block BLOCK_REACTOR_ABSORBER =
            new com.hbmzz.reactor.block.BlockReactorComponent(com.hbmzz.reactor.block.BlockReactorComponent.ComponentType.ABSORBER);
    /** Stabiliser: more temperature feedback -> the coefficient that stops a runaway. */
    public static final Block BLOCK_REACTOR_STABILISER =
            new com.hbmzz.reactor.block.BlockReactorComponent(com.hbmzz.reactor.block.BlockReactorComponent.ComponentType.STABILISER);
    /** Control rod: its redstone depth is the throttle. */
    public static final Block BLOCK_REACTOR_CONTROL_ROD =
            new com.hbmzz.reactor.block.BlockReactorComponent(com.hbmzz.reactor.block.BlockReactorComponent.ComponentType.CONTROL_ROD);
    public static final Item ITEM_BLOCK_REACTOR_CORE = new ItemBlock(BLOCK_REACTOR_CORE);
    public static final Item ITEM_BLOCK_REACTOR_EMITTER = new ItemBlock(BLOCK_REACTOR_EMITTER);
    public static final Item ITEM_BLOCK_REACTOR_ABSORBER = new ItemBlock(BLOCK_REACTOR_ABSORBER);
    public static final Item ITEM_BLOCK_REACTOR_STABILISER = new ItemBlock(BLOCK_REACTOR_STABILISER);
    public static final Item ITEM_BLOCK_REACTOR_CONTROL_ROD = new ItemBlock(BLOCK_REACTOR_CONTROL_ROD);

    /* 四种红石门。拆成四个方块而不是一块可切换的门，理由在 GateType 的类注释里：
       1.12.2 的 meta 只有 4 位，朝向 4 × 亮灭 2 已经用掉 8 个组合，再塞 8 种运算
       就得牺牲朝向或亮灭。 */
    public static final Block BLOCK_AND_GATE =
            new com.hbmzz.redstone.BlockRedstoneGate(com.hbmzz.redstone.GateType.AND);
    public static final Block BLOCK_OR_GATE =
            new com.hbmzz.redstone.BlockRedstoneGate(com.hbmzz.redstone.GateType.OR);
    public static final Block BLOCK_XOR_GATE =
            new com.hbmzz.redstone.BlockRedstoneGate(com.hbmzz.redstone.GateType.XOR);
    public static final Block BLOCK_NOT_GATE =
            new com.hbmzz.redstone.BlockRedstoneGate(com.hbmzz.redstone.GateType.NOT);
    public static final Item ITEM_BLOCK_AND_GATE = new ItemBlock(BLOCK_AND_GATE);
    public static final Item ITEM_BLOCK_OR_GATE = new ItemBlock(BLOCK_OR_GATE);
    public static final Item ITEM_BLOCK_XOR_GATE = new ItemBlock(BLOCK_XOR_GATE);
    public static final Item ITEM_BLOCK_NOT_GATE = new ItemBlock(BLOCK_NOT_GATE);

    /**
     * The Tartarus gate: the community edition's giant blast door, laid flat.
     * Two leaves in nine cells; only the corner cell carries the tile entity.
     */
    /** AI control node: watches a bound machine and raises a TTS voice alarm. */
    public static final Block BLOCK_AI_CONTROL_NODE = new com.hbmzz.ai.BlockAIControlNode();
    public static final Item ITEM_BLOCK_AI_CONTROL_NODE = new ItemBlock(BLOCK_AI_CONTROL_NODE);
    public static final Block BLOCK_TARTARUS_GATE = new com.hbmzz.gate.BlockTartarusGate();
    public static final Block BLOCK_GATE_PART = new com.hbmzz.gate.BlockGatePart();
    public static final Item ITEM_BLOCK_TARTARUS_GATE =
            new com.hbmzz.item.ItemBlockTartarusGate(BLOCK_TARTARUS_GATE);
    public static final Block BLOCK_VAULT_DOOR = new com.hbmzz.vault.BlockVault();
    public static final Block BLOCK_VAULT_PART = new com.hbmzz.vault.BlockVaultPart();
    public static final Item ITEM_BLOCK_VAULT_DOOR =
            new com.hbmzz.vault.ItemBlockVault(BLOCK_VAULT_DOOR);

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
        event.getRegistry().register(BLOCK_AEGIS_TERMINAL);
        event.getRegistry().register(BLOCK_AEGIS_CELL);
        event.getRegistry().register(BLOCK_MONITOR_PART);
        event.getRegistry().register(BLOCK_MONITOR_CAMERA);
        event.getRegistry().register(BLOCK_CTRL_PANEL);
        event.getRegistry().register(BLOCK_CTRL_CONSOLE);
        event.getRegistry().register(BLOCK_VIDEO_BROADCASTER);
        event.getRegistry().register(BLOCK_VIDEO_SCREEN);
        event.getRegistry().register(BLOCK_NUKE);
        event.getRegistry().register(BLOCK_NUKE_CONTROLLER);
        event.getRegistry().register(BLOCK_NUKE_MONITOR);
        event.getRegistry().register(BLOCK_CODE_BROADCASTER);
        event.getRegistry().register(BLOCK_ALERT_DISPLAY);
        event.getRegistry().register(BLOCK_DFC_BLAST_SENSOR);
        event.getRegistry().register(BLOCK_DFC_COLLAPSE_SENSOR);
        event.getRegistry().register(BLOCK_RBMK_BLAST_SENSOR);
        event.getRegistry().register(BLOCK_RBMK_ROD_JUMP_SENSOR);
        event.getRegistry().register(BLOCK_REACTOR);
        event.getRegistry().register(BLOCK_STATUS_LAMP);
        event.getRegistry().register(BLOCK_ALARM_BEACON);
        event.getRegistry().register(BLOCK_REACTOR_CORE);
        event.getRegistry().register(BLOCK_REACTOR_EMITTER);
        event.getRegistry().register(BLOCK_REACTOR_ABSORBER);
        event.getRegistry().register(BLOCK_REACTOR_STABILISER);
        event.getRegistry().register(BLOCK_REACTOR_CONTROL_ROD);
        event.getRegistry().register(BLOCK_AND_GATE);
        event.getRegistry().register(BLOCK_OR_GATE);
        event.getRegistry().register(BLOCK_XOR_GATE);
        event.getRegistry().register(BLOCK_NOT_GATE);
        event.getRegistry().register(BLOCK_AI_CONTROL_NODE);
        event.getRegistry().register(BLOCK_TARTARUS_GATE);
        event.getRegistry().register(BLOCK_GATE_PART);
        event.getRegistry().register(BLOCK_VAULT_DOOR);
        event.getRegistry().register(BLOCK_VAULT_PART);
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        ITEM_BLOCK_MONITOR.setRegistryName(BLOCK_MONITOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_MONITOR);
        ITEM_BLOCK_AEGIS_TERMINAL.setRegistryName(BLOCK_AEGIS_TERMINAL.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_AEGIS_TERMINAL);
        ITEM_BLOCK_AEGIS_CELL.setRegistryName(BLOCK_AEGIS_CELL.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_AEGIS_CELL);
        event.getRegistry().register(ITEM_AEGIS_LINKER);
        ITEM_BLOCK_MONITOR_CAMERA.setRegistryName(BLOCK_MONITOR_CAMERA.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_MONITOR_CAMERA);
        ITEM_BLOCK_CTRL_PANEL.setRegistryName(BLOCK_CTRL_PANEL.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_CTRL_PANEL);
        ITEM_BLOCK_CTRL_CONSOLE.setRegistryName(BLOCK_CTRL_CONSOLE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_CTRL_CONSOLE);
        ITEM_BLOCK_VIDEO_BROADCASTER.setRegistryName(BLOCK_VIDEO_BROADCASTER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_VIDEO_BROADCASTER);
        ITEM_BLOCK_VIDEO_SCREEN.setRegistryName(BLOCK_VIDEO_SCREEN.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_VIDEO_SCREEN);
        ITEM_BLOCK_NUKE.setRegistryName(BLOCK_NUKE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_NUKE);
        ITEM_BLOCK_NUKE_CONTROLLER.setRegistryName(BLOCK_NUKE_CONTROLLER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_NUKE_CONTROLLER);
        ITEM_BLOCK_NUKE_MONITOR.setRegistryName(BLOCK_NUKE_MONITOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_NUKE_MONITOR);
        ITEM_BLOCK_CODE_BROADCASTER.setRegistryName(BLOCK_CODE_BROADCASTER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_CODE_BROADCASTER);
        ITEM_BLOCK_ALERT_DISPLAY.setRegistryName(BLOCK_ALERT_DISPLAY.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_ALERT_DISPLAY);
        ITEM_BLOCK_DFC_BLAST_SENSOR.setRegistryName(BLOCK_DFC_BLAST_SENSOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_DFC_BLAST_SENSOR);
        ITEM_BLOCK_DFC_COLLAPSE_SENSOR.setRegistryName(BLOCK_DFC_COLLAPSE_SENSOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_DFC_COLLAPSE_SENSOR);
        ITEM_BLOCK_RBMK_BLAST_SENSOR.setRegistryName(BLOCK_RBMK_BLAST_SENSOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_RBMK_BLAST_SENSOR);
        ITEM_BLOCK_RBMK_ROD_JUMP_SENSOR.setRegistryName(BLOCK_RBMK_ROD_JUMP_SENSOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_RBMK_ROD_JUMP_SENSOR);
        ITEM_BLOCK_REACTOR.setRegistryName(BLOCK_REACTOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_REACTOR);
        ITEM_BLOCK_STATUS_LAMP.setRegistryName(BLOCK_STATUS_LAMP.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_STATUS_LAMP);
        ITEM_BLOCK_ALARM_BEACON.setRegistryName(BLOCK_ALARM_BEACON.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_ALARM_BEACON);
        ITEM_BLOCK_REACTOR_CORE.setRegistryName(BLOCK_REACTOR_CORE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_REACTOR_CORE);
        ITEM_BLOCK_REACTOR_EMITTER.setRegistryName(BLOCK_REACTOR_EMITTER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_REACTOR_EMITTER);
        ITEM_BLOCK_REACTOR_ABSORBER.setRegistryName(BLOCK_REACTOR_ABSORBER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_REACTOR_ABSORBER);
        ITEM_BLOCK_REACTOR_STABILISER.setRegistryName(BLOCK_REACTOR_STABILISER.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_REACTOR_STABILISER);
        ITEM_BLOCK_REACTOR_CONTROL_ROD.setRegistryName(BLOCK_REACTOR_CONTROL_ROD.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_REACTOR_CONTROL_ROD);
        ITEM_BLOCK_AND_GATE.setRegistryName(BLOCK_AND_GATE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_AND_GATE);
        ITEM_BLOCK_OR_GATE.setRegistryName(BLOCK_OR_GATE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_OR_GATE);
        ITEM_BLOCK_XOR_GATE.setRegistryName(BLOCK_XOR_GATE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_XOR_GATE);
        ITEM_BLOCK_NOT_GATE.setRegistryName(BLOCK_NOT_GATE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_NOT_GATE);
        ITEM_BLOCK_AI_CONTROL_NODE.setRegistryName(BLOCK_AI_CONTROL_NODE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_AI_CONTROL_NODE);
        ITEM_BLOCK_TARTARUS_GATE.setRegistryName(BLOCK_TARTARUS_GATE.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_TARTARUS_GATE);
        ITEM_BLOCK_VAULT_DOOR.setRegistryName(BLOCK_VAULT_DOOR.getRegistryName());
        event.getRegistry().register(ITEM_BLOCK_VAULT_DOOR);
        event.getRegistry().register(ITEM_LINKER);
        event.getRegistry().register(ITEM_VIDEO_REMOTE);
        event.getRegistry().register(ITEM_HACKER_TABLET);
        event.getRegistry().register(ITEM_AI_TERMINAL);
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

        // Aegis terminal: glass pane and redstone for the screen, a comparator
        // for the reader, surrounded by iron. The frame cell is cheaper.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "aegis_terminal"),
                new ItemStack(BLOCK_AEGIS_TERMINAL),
                "III",
                "GRG",
                "ICI",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "aegis_terminal"));
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "aegis_cell"),
                new ItemStack(BLOCK_AEGIS_CELL),
                "III",
                "IGI",
                "III",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE)
        ).setRegistryName(HBMZZ.MODID, "aegis_cell"));
        // Aegis linker: a comparator on a redstone/iron handle. Cheap and
        // reusable - it carries only a coordinate, not any scarce material.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "aegis_linker"),
                new ItemStack(ITEM_AEGIS_LINKER),
                " I ",
                "IRI",
                " C ",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "aegis_linker"));

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

        // AI terminal: the hacker tablet's glass + pearl uplink, but with a
        // redstone block for the authority to write to machines and an emerald
        // for the "thinking" logic. Distinct from every tablet/recipe above.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "ai_terminal"),
                new ItemStack(ITEM_AI_TERMINAL),
                "GEG",
                "IPI",
                "IRI",
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'E', Items.EMERALD,
                'I', Items.IRON_INGOT,
                'P', Items.ENDER_PEARL,
                'R', Item.getItemFromBlock(Blocks.REDSTONE_BLOCK)
        ).setRegistryName(HBMZZ.MODID, "ai_terminal"));

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

        // Video screen: mostly glass (it is a panel, not a machine) on an iron
        // frame, with one redstone to say it is wired to something.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "video_screen"),
                new ItemStack(BLOCK_VIDEO_SCREEN),
                "GGG",
                "IRI",
                "III",
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "video_screen"));

        // Remote: a handset, so the parts are a button (redstone), a case (iron)
        // and a display window (glass pane). One per wall is enough, and it is
        // reusable, which is why it is not consumed on binding.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "video_remote"),
                new ItemStack(ITEM_VIDEO_REMOTE),
                " R ",
                "IGI",
                " I ",
                'R', Items.REDSTONE,
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE)
        ).setRegistryName(HBMZZ.MODID, "video_remote"));

        // ---- nuclear accident system ----
        // The bomb is the most expensive thing in the mod by design: it ends a
        // world, and "one per save" is a rule about the save, not a cost.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "nuke"),
                new ItemStack(BLOCK_NUKE),
                "IOI",
                "PRP",
                "IOI",
                'I', Items.IRON_INGOT,
                'O', Item.getItemFromBlock(Blocks.OBSIDIAN),
                'P', Items.ENDER_PEARL,
                'R', Item.getItemFromBlock(Blocks.REDSTONE_BLOCK)
        ).setRegistryName(HBMZZ.MODID, "nuke"));

        // The controller is cheap on purpose: it is the part you want several of,
        // one per lever or per alarm circuit.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "nuke_controller"),
                new ItemStack(BLOCK_NUKE_CONTROLLER),
                "IEI",
                "RCR",
                "III",
                'I', Items.IRON_INGOT,
                'E', Items.ENDER_PEARL,
                'R', Items.REDSTONE,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "nuke_controller"));

        // Deliberately NOT the DFC monitor's pattern. 1.12.2 resolves crafting by
        // returning the first recipe that matches, so an identical grid makes the
        // later registration silently unreachable - the player would craft a DFC
        // monitor and never learn why the bomb screen is missing. The top row's
        // glass pane becomes obsidian instead: same monitor, with the blast
        // shielding the job calls for.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "nuke_monitor"),
                new ItemStack(BLOCK_NUKE_MONITOR),
                "IOI",
                "GRG",
                "ICI",
                'I', Items.IRON_INGOT,
                'O', Item.getItemFromBlock(Blocks.OBSIDIAN),
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'C', Items.COMPARATOR
        ).setRegistryName(HBMZZ.MODID, "nuke_monitor"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "code_broadcaster"),
                new ItemStack(BLOCK_CODE_BROADCASTER),
                "IRI",
                "GLG",
                "IRI",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'L', Item.getItemFromBlock(Blocks.REDSTONE_LAMP)
        ).setRegistryName(HBMZZ.MODID, "code_broadcaster"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "alert_display"),
                new ItemStack(BLOCK_ALERT_DISPLAY),
                "IGI",
                "GRG",
                "IEI",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE,
                'E', Items.ENDER_PEARL
        ).setRegistryName(HBMZZ.MODID, "alert_display"));

        // Status lamp: an iron bezel with a glass lens, and one redstone to say it
        // is wired into the alert bus. The lens is the whole point - a colour you
        // read across the hall.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "status_lamp"),
                new ItemStack(BLOCK_STATUS_LAMP),
                " R ",
                "IGI",
                "III",
                'R', Items.REDSTONE,
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE)
        ).setRegistryName(HBMZZ.MODID, "status_lamp"));

        // Alarm beacon: the same bezel and lens, but the heart is a quartz lens
        // (optics, not power) and the bottom row is a redstone channel. Deliberately
        // a different grid from the status lamp, so the two recipes never shadow
        // each other under regcheck #61.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "alarm_beacon"),
                new ItemStack(BLOCK_ALARM_BEACON),
                " Q ",
                "IGI",
                "RIR",
                'Q', Items.QUARTZ,
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "alarm_beacon"));

        // 石英是这里唯一"准"的东西：传感器要的是稳定，不是威力。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "dfc_blast_sensor"),
                new ItemStack(BLOCK_DFC_BLAST_SENSOR),
                "IQI",
                "GRG",
                "IQI",
                'I', Items.IRON_INGOT,
                'Q', Items.QUARTZ,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "dfc_blast_sensor"));

        // DFC 坍缩传感器：认的是「正在坍缩」这段持续状态，所以配方把玻璃镜头夹在
        // 两列石英中间（「持续监视」的意象），红石只出现在正中央一格。网格与 DFC
        // 爆炸传感器不同（顶/底用 Q 而非 I），避免撞上 regcheck 第 61 条。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "dfc_collapse_sensor"),
                new ItemStack(BLOCK_DFC_COLLAPSE_SENSOR),
                "QIQ",
                "RIR",
                "QIQ",
                'I', Items.IRON_INGOT,
                'Q', Items.QUARTZ,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "dfc_collapse_sensor"));

        // RBMK 爆炸传感器：认的是反应堆柱子上的核爆，所以配方里用石英（稳定、瞬时
        // 读数）+ 玻璃镜头 + 红石心。网格与 DFC 不同，避免撞上 regcheck 第 61 条。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "rbmk_blast_sensor"),
                new ItemStack(BLOCK_RBMK_BLAST_SENSOR),
                " R ",
                "IQI",
                "III",
                'I', Items.IRON_INGOT,
                'Q', Items.QUARTZ,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "rbmk_blast_sensor"));

        // RBMK 跳棒传感器：认的是控制棒反复跳的持续状态，配方用两根红石夹玻璃
        // （「在跳」是持续的量），网格再错开一格。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "rbmk_rod_jump_sensor"),
                new ItemStack(BLOCK_RBMK_ROD_JUMP_SENSOR),
                "III",
                "RGR",
                " Q ",
                'I', Items.IRON_INGOT,
                'Q', Items.QUARTZ,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "rbmk_rod_jump_sensor"));

        // 四个门：外壳一样（铁），区别只在运算符那一层用什么材料。
        // 四张字符画第三行各不相同，免得撞上 regcheck 第 61 条「有序配方不许重样」。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "and_gate"),
                new ItemStack(BLOCK_AND_GATE),
                "IRI",
                "RQR",
                "III",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'Q', Items.QUARTZ
        ).setRegistryName(HBMZZ.MODID, "and_gate"));

        // ---- multi-block reactor rig ----
        // The core: the single block that does the physics. Expensive on purpose -
        // it borrows the control panel's look and the reactor's job, so it asks for
        // both a comparator (the brain) and a bed of redstone (the wiring).
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "reactor_core"),
                new ItemStack(BLOCK_REACTOR_CORE),
                "ICI",
                "RIR",
                "ICI",
                'I', Items.IRON_INGOT,
                'C', Items.COMPARATOR,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "reactor_core"));

        // Emitter: a fuel source. Iron frame with a redstone heart - the thing that
        // makes the chain reaction happen. Third row distinct from the core.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "reactor_emitter"),
                new ItemStack(BLOCK_REACTOR_EMITTER),
                "III",
                "IRI",
                "RRR",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "reactor_emitter"));

        // Absorber: a cooling loop. Iron frame, glass heart, one redstone to say it
        // is wired to the loop. Same outer frame as the emitter, different middle.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "reactor_absorber"),
                new ItemStack(BLOCK_REACTOR_ABSORBER),
                "III",
                "IGI",
                "IRI",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'R', Items.REDSTONE
        ).setRegistryName(HBMZZ.MODID, "reactor_absorber"));

        // Stabiliser: the temperature-feedback hardware. Iron frame, a quartz sensor
        // at the heart (stability, not power), glass on the outside.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "reactor_stabiliser"),
                new ItemStack(BLOCK_REACTOR_STABILISER),
                "GGG",
                "GQG",
                "III",
                'I', Items.IRON_INGOT,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'Q', Items.QUARTZ
        ).setRegistryName(HBMZZ.MODID, "reactor_stabiliser"));

        // Control rod: the throttle. A tungsten-like iron rod (redstone block) inside
        // an iron channel. The depth the player drives it to is the redstone beside it.
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "reactor_control_rod"),
                new ItemStack(BLOCK_REACTOR_CONTROL_ROD),
                "IRI",
                "IRI",
                "IRI",
                'I', Items.IRON_INGOT,
                'R', Item.getItemFromBlock(Blocks.REDSTONE_BLOCK)
        ).setRegistryName(HBMZZ.MODID, "reactor_control_rod"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "or_gate"),
                new ItemStack(BLOCK_OR_GATE),
                "IRI",
                "RQR",
                "GGG",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'Q', Items.QUARTZ,
                'G', Items.GOLD_INGOT
        ).setRegistryName(HBMZZ.MODID, "or_gate"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "xor_gate"),
                new ItemStack(BLOCK_XOR_GATE),
                "IRI",
                "RQR",
                "QQQ",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'Q', Items.QUARTZ
        ).setRegistryName(HBMZZ.MODID, "xor_gate"));

        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "not_gate"),
                new ItemStack(BLOCK_NOT_GATE),
                "IRI",
                "RQR",
                "RRR",
                'I', Items.IRON_INGOT,
                'R', Items.REDSTONE,
                'Q', Items.QUARTZ
        ).setRegistryName(HBMZZ.MODID, "not_gate"));

        // AI 控制节点：比较器（读机器）+ 末影珍珠（联网）+ 红石块（广播），
        // 玻璃面板当"屏幕"。第三行 IRIR 与所有已有配方都不同。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "ai_control_node"),
                new ItemStack(BLOCK_AI_CONTROL_NODE),
                "CGC",
                "IEI",
                "IRI",
                'C', Items.COMPARATOR,
                'G', Item.getItemFromBlock(Blocks.GLASS_PANE),
                'I', Items.IRON_INGOT,
                'E', Items.ENDER_PEARL,
                'R', Item.getItemFromBlock(Blocks.REDSTONE_BLOCK)
        ).setRegistryName(HBMZZ.MODID, "ai_control_node"));

        // 塔塔鲁斯大门：九个铁块围一块黑曜石，再压一块红石块。
        // 第三行与任何已有配方都不同（regcheck 第 61 条）。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "tartarus_gate"),
                new ItemStack(BLOCK_TARTARUS_GATE),
                "III",
                "IOI",
                "IRI",
                'I', Blocks.IRON_BLOCK,
                'O', Blocks.OBSIDIAN,
                'R', Blocks.REDSTONE_BLOCK
        ).setRegistryName(HBMZZ.MODID, "tartarus_gate"));

        // 银行金库门：黑曜石门框、铁门板。它认设施代码而不是红石（见
        // TileEntityVault），配方只是凑材料。第三行 ORO 与任何已有配方都不同
        // （regcheck 第 61 条）。
        event.getRegistry().register(new ShapedOreRecipe(
                new ResourceLocation(HBMZZ.MODID, "vault_door"),
                new ItemStack(BLOCK_VAULT_DOOR),
                "OIO",
                "IRI",
                "ORO",
                'I', Blocks.IRON_BLOCK,
                'O', Blocks.OBSIDIAN,
                'R', Blocks.REDSTONE_BLOCK
        ).setRegistryName(HBMZZ.MODID, "vault_door"));

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
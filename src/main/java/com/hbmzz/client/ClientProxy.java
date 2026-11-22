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

package com.hbmzz.client;

import com.hbmzz.ModContent;
import com.hbmzz.common.CommonProxy;
import com.hbmzz.network.DFCRequestMessage;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.tile.TileEntityDFCMonitor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import java.util.ArrayList;
import java.util.List;

public class ClientProxy extends CommonProxy {

    /**
     * 刷新视频目录的键。
     *
     * <p>F7: nothing in vanilla uses it, and nothing in this jar does either. The
     * obvious alternative, F3+T, is taken twice over - it reloads the resource
     * packs, so pressing it to pick up a dropped zip also re-bakes every model in
     * the game, and this mod's own model check runs on that path.</p>
     */
    public static final net.minecraft.client.settings.KeyBinding KEY_REFRESH_VIDEOS =
            new net.minecraft.client.settings.KeyBinding("key.hbmzz.video_refresh",
                    org.lwjgl.input.Keyboard.KEY_F7, "key.categories.hbmzz");

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        // 多格式音频（ogg 之外的 mp3 / flac / wav / m4a …），从 wzh 的 rsaudiofm 插件并进来。
        // 它只在这一处挂钩：内部幂等、每一步都自己包了 try，装不起来就退回"只认 ogg"。
        // 直出路线的钩子写在 rsaudio 的 ClientSoundPlayer 里，不再需要 MixinBooter。
        com.rsaudio.formats.FormatsBootstrap.init(event);
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        MinecraftForge.EVENT_BUS.register(new MonitorHud());
        // Blast feedback: camera roll, hotbar displacement, ground ring. Driven
        // by ShakeMessage, ticked here so the animation costs one client tick
        // per frame instead of a particle packet per point.
        ClientShake.init();
        // The zd_tx orbit camera. Purely client-side presentation: the server
        // only says "watch this block", and this decides where the eye goes.
        ClientCinematic.init();
        // DFC explosion "totem" show: logo billboard, golden spiral, screen
        // flash and sound. Driven by the TOTEM_EXPLODE mode of DfcFxMessage;
        // the handler is event-bus based, so it only costs a tick when live.
        ClientTotemFx.init();
        ClientRegistry.registerKeyBinding(KEY_REFRESH_VIDEOS);
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityDFCMonitor.class, new RenderDFCMonitor());
        // Aegis telemetry terminal — its renderer owns its own canvas/texture.
        ClientRegistry.bindTileEntitySpecialRenderer(com.hbmzz.aegis.TileAegisTerminal.class,
                new com.hbmzz.aegis.client.AegisRenderer());
        // 视频大屏：世界里那块正在放片子的屏幕。走 TESR 而不是方块模型，因为
        // 画面每帧都在变，而方块模型是资源加载时烘焙一次的（理由见 RenderVideoScreen）。
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.hbmzz.tile.TileEntityVideoScreen.class, new RenderVideoScreen());
        // The two nuclear-system panels: one cell each, same housing hardware as
        // the DFC monitor, different canvas.
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.hbmzz.tile.TileEntityNukeMonitor.class, new RenderNukeMonitor());
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.hbmzz.tile.TileEntityAlertDisplay.class, new RenderAlertDisplay());
        // The two alert lamps draw their own geometry: a static coloured lens for
        // the status lamp, and a spinning beacon that only turns above the safe
        // code level. Both are pure presentation, driven by the synced alert
        // state, so they belong on the client and read nothing from the block.
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.hbmzz.tile.TileEntityStatusLamp.class, new RenderStatusLamp());
        ClientRegistry.bindTileEntitySpecialRenderer(
                com.hbmzz.tile.TileEntityAlarmBeacon.class, new RenderAlarmBeacon());
        ClientRegistry.bindTileEntitySpecialRenderer(com.hbmzz.cecp.tile.TileEntityCtrlConsole.class,
                new com.hbmzz.cecp.client.RenderCtrlConsole());
        // The panel that was knocked off its mount: an entity, because blocks
        // are gone and a block cannot swing on a cable.
        net.minecraftforge.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(
                com.hbmzz.entity.EntityFallenScreen.class, RenderFallenScreen::new);
        // The lid that came off an RBMK. Also an entity: a lid that lands as a
        // block would plant a live reactor column wherever it stopped.
        net.minecraftforge.fml.client.registry.RenderingRegistry.registerEntityRenderingHandler(
                com.hbmzz.entity.EntityRbmkLid.class, RenderRbmkLid::new);
    }

    /**
     * 把三张表交给 Forge。
     *
     * <p>在 {@code ModelRegistryEvent} 上调，不再放在 {@code init} 里：模型是在
     * 资源包重载时才烘焙的，而这个事件的契约就是"注册模型"这一段窗口。同一枚 jar 里的
     * {@code rsaudio} 一直这么写，也一直是对的；{@code com.hbmzz} 这边写在 init 里，
     * 恰好也能跑，但那依赖的是"init 早于第一次资源重载"这个巧合，而不是约定。</p>
     */
    public static void registerItemModels() {
        List<ModelResourceLocation> models = itemModels();
        for (int i = 0; i < models.size(); i++) {
            Item item = items().get(i);
            int meta = metas().get(i);
            ModelLoader.setCustomModelResourceLocation(item, meta, models.get(i));
        }
    }

    /**
     * Every item model the mod ships, in the same order as {@link #items()} and
     * {@link #metas()}. Kept in one place so the startup model check and the
     * registration can never drift apart.
     */
    public static List<ModelResourceLocation> itemModels() {
        List<ModelResourceLocation> list = new ArrayList<>();
        list.add(loc("dfc_monitor"));
        list.add(loc("monitor_camera"));
        list.add(loc("monitor_linker"));
        list.add(loc("speed_coil"));
        list.add(loc("jump_coil"));
        list.add(loc("regen_coil"));
        list.add(loc("jetpack"));
        list.add(loc("gun_pistol"));
        list.add(loc("gun_rifle"));
        list.add(loc("gun_shotgun"));
        list.add(loc("ammo_9mm"));
        list.add(loc("ammo_556"));
        list.add(loc("ammo_12ga"));
        list.add(loc("control_panel"));
        list.add(loc("ctrl_console"));
        list.add(loc("video_broadcaster"));
        list.add(loc("video_screen"));
        list.add(loc("video_remote"));
        list.add(loc("nuke"));
        list.add(loc("nuke_controller"));
        list.add(loc("nuke_monitor"));
        list.add(loc("code_broadcaster"));
        list.add(loc("alert_display"));
        // 黑客平板曾经不在这张表里。它之所以还能显示，只是因为原版兜底会去
        // models/item/<注册名>.json 找一份同名模型 —— 表里少一行不会当场炸，
        // 只会在有人给这个物品改名的那天安静地变成紫黑块。
        list.add(loc("hacker_tablet"));
        list.add(loc("ai_terminal"));
        // 事故之后的第一块砖：DFC 一炸就输出红石。它自己也必须有模型位置，
        // 否则玩家放下之前根本认不出这是哪块砖。
        list.add(loc("dfc_blast_sensor"));
        // 四种红石门。它们同样必须在表里，否则物品栏里先是一排紫黑块。
        list.add(loc("and_gate"));
        list.add(loc("or_gate"));
        list.add(loc("xor_gate"));
        list.add(loc("not_gate"));
        list.add(loc("tartarus_gate"));
        // HBMZZ 反应堆：复用控制面板模型，但必须有自己的显式位置，
        // 否则物品栏图标只能靠注册名撞文件名（改名那天就变紫黑）。
        list.add(loc("reactor"));
        // Multi-block reactor rig: the core borrows the control panel's model,
        // and the four components each have their own cube model. All five must
        // be here, in the same order as the items and metas tables, or regcheck
        // will report them as purple-and-black cubes in hand.
        list.add(loc("reactor_core"));
        list.add(loc("reactor_emitter"));
        list.add(loc("reactor_absorber"));
        list.add(loc("reactor_stabiliser"));
        list.add(loc("reactor_control_rod"));
        // The two alert lamps: a status lamp and a rotating beacon. Both need an
        // explicit model slot, or the inventory icon falls back to the registry
        // name and goes purple-and-black the day someone renames the block.
        list.add(loc("status_lamp"));
        list.add(loc("alarm_beacon"));
        // Aegis telemetry terminal and its frame cell.
        list.add(loc("aegis_terminal"));
        list.add(loc("aegis_cell"));
        list.add(loc("aegis_linker"));
        // AI control node
        list.add(loc("ai_control_node"));
        return list;
    }

    public static List<Item> items() {
        List<Item> list = new ArrayList<>();
        list.add(Item.getItemFromBlock(ModContent.BLOCK_MONITOR));
        list.add(Item.getItemFromBlock(ModContent.BLOCK_MONITOR_CAMERA));
        list.add(ModContent.ITEM_LINKER);
        list.add(ModContent.ITEM_SPEED_COIL);
        list.add(ModContent.ITEM_JUMP_COIL);
        list.add(ModContent.ITEM_REGEN_COIL);
        list.add(ModContent.ITEM_JETPACK);
        list.add(ModContent.ITEM_GUN_PISTOL);
        list.add(ModContent.ITEM_GUN_RIFLE);
        list.add(ModContent.ITEM_GUN_SHOTGUN);
        list.add(ModContent.ITEM_AMMO);
        list.add(ModContent.ITEM_AMMO);
        list.add(ModContent.ITEM_AMMO);
        list.add(Item.getItemFromBlock(ModContent.BLOCK_CTRL_PANEL));
        list.add(ModContent.ITEM_BLOCK_CTRL_CONSOLE);
        list.add(ModContent.ITEM_BLOCK_VIDEO_BROADCASTER);
        list.add(ModContent.ITEM_BLOCK_VIDEO_SCREEN);
        list.add(ModContent.ITEM_VIDEO_REMOTE);
        list.add(ModContent.ITEM_BLOCK_NUKE);
        list.add(ModContent.ITEM_BLOCK_NUKE_CONTROLLER);
        list.add(ModContent.ITEM_BLOCK_NUKE_MONITOR);
        list.add(ModContent.ITEM_BLOCK_CODE_BROADCASTER);
        list.add(ModContent.ITEM_BLOCK_ALERT_DISPLAY);
        list.add(ModContent.ITEM_HACKER_TABLET);
        list.add(ModContent.ITEM_AI_TERMINAL);
        list.add(ModContent.ITEM_BLOCK_DFC_BLAST_SENSOR);
        list.add(ModContent.ITEM_BLOCK_AND_GATE);
        list.add(ModContent.ITEM_BLOCK_OR_GATE);
        list.add(ModContent.ITEM_BLOCK_XOR_GATE);
        list.add(ModContent.ITEM_BLOCK_NOT_GATE);
        list.add(ModContent.ITEM_BLOCK_TARTARUS_GATE);
        list.add(ModContent.ITEM_BLOCK_REACTOR);
        list.add(ModContent.ITEM_BLOCK_REACTOR_CORE);
        list.add(ModContent.ITEM_BLOCK_REACTOR_EMITTER);
        list.add(ModContent.ITEM_BLOCK_REACTOR_ABSORBER);
        list.add(ModContent.ITEM_BLOCK_REACTOR_STABILISER);
        list.add(ModContent.ITEM_BLOCK_REACTOR_CONTROL_ROD);
        list.add(ModContent.ITEM_BLOCK_STATUS_LAMP);
        list.add(ModContent.ITEM_BLOCK_ALARM_BEACON);
        list.add(ModContent.ITEM_BLOCK_AEGIS_TERMINAL);
        list.add(ModContent.ITEM_BLOCK_AEGIS_CELL);
        list.add(ModContent.ITEM_AEGIS_LINKER);
        list.add(ModContent.ITEM_BLOCK_AI_CONTROL_NODE);
        return list;
    }

    /**
     * 与 {@link #itemModels()} / {@link #items()} 逐位对应的 metadata。
     *
     * <p>写成一张定长表而不是"先循环十个零再补几个"：三张表必须一样长，而
     * "循环生成一部分"正好让长度变得不可数——上一次往表尾加物品的人就是这么漏掉平板的。
     * {@code tools/regcheck} 会核对这三张表等长，且每个注册过的物品都在 {@link #items()}
     * 里出现一次。</p>
     */
    private static final int[] METAS = {
            //  dfc_monitor  camera  linker  speed  jump  regen  jetpack  pistol  rifle  shotgun
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            //  ammo_9mm  556  12ga
            0, 1, 2,
            //  control_panel  console  video  screen  remote  nuke  controller  nuke_monitor  code  alert  tablet  ai_terminal
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            //  dfc_blast_sensor
            0,
            //  and_gate  or_gate  xor_gate  not_gate
            0, 0, 0, 0,
            //  tartarus_gate
            0,
            //  reactor
            0,
            //  reactor_core  emitter  absorber  stabiliser  control_rod
            0, 0, 0, 0, 0,
            //  status_lamp  alarm_beacon
            0, 0,
            //  aegis_terminal  aegis_cell  aegis_linker
            0, 0, 0,
            //  ai_control_node
            0
    };

    public static List<Integer> metas() {
        List<Integer> list = new ArrayList<>(METAS.length);
        for (int meta : METAS) {
            list.add(meta);
        }
        return list;
    }

    private static ModelResourceLocation loc(String path) {
        return new ModelResourceLocation("hbmzz:" + path, "inventory");
    }

    @Override
    public void openMonitor(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiDFCMonitor(pos));
        NetworkHandler.sendToServer(new DFCRequestMessage(pos));
    }

    @Override
    public void openAegis(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new com.hbmzz.aegis.client.GuiAegisTerminal(pos));
    }

    @Override
    public void openControlPanel(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiCtrlPanel(pos));
    }

    @Override
    public void openVideoBroadcaster(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiVideoBroadcaster(pos));
    }

    @Override
    public void openCodeBroadcaster(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiCodeBroadcaster(pos));
    }

    /**
     * 站立右键：大屏展示。
     *
     * <p>接了屏，就进"展示模式"看那块屏——画面是屏自己的解码器给的，声音也是那
     * 一路，所以这里不会再多出一个播放实例来。没接屏（或者屏不在已加载区）才退回
     * 本机全屏播放，那条路上只有它自己一份声音。</p>
     *
     * <p>拿不到方块实体时开菜单而不是什么都不做：方块实体是随方块更新同步下来
     * 的，刚放下 / 刚重连的那一瞬间可能还没有，而"右键没反应"是玩家无法自查的
     * 故障。</p>
     */
    @Override
    public void openVideoPlayback(BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        net.minecraft.tileentity.TileEntity te = mc.world.getTileEntity(pos);
        if (!(te instanceof com.hbmzz.tile.TileEntityVideoBroadcaster)) {
            mc.displayGuiScreen(new GuiVideoBroadcaster(pos));
            return;
        }
        com.hbmzz.tile.TileEntityVideoBroadcaster playerBlock =
                (com.hbmzz.tile.TileEntityVideoBroadcaster) te;
        if (playerBlock.getVideo().isEmpty()) {
            mc.displayGuiScreen(new GuiVideoBroadcaster(pos));
            return;
        }
        BlockPos wall = playerBlock.getScreenPos();
        if (wall != null && mc.world.isBlockLoaded(wall)) {
            // 接了大屏：画面归那块屏。播放器这一下不再开窗口 —— 以前它开的是
            // "展示"窗口（一块只读的放大镜），右键落在里面还会切全屏，于是同一
            // 个手势既能看片又能改桌面模式。现在看片只有一条路：右键那块屏。
            // 这里保持"什么都不发生"，是这条规则的实现，不是漏了返回值。
            return;
        }
        // 接了屏但屏不在本机已加载区（人跑远了 / 屏在另一个维度）：这边没有那块
        // 屏在发声，本机全屏播放不会和谁重叠，是唯一能让玩家看到画面的路。
        mc.displayGuiScreen(new GuiVideoScreen(pos, playerBlock.getVideo(),
                playerBlock.getVolume(), false));
    }

    @Override
    public void openVideoScreenConfig(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiVideoScreenConfig(pos));
    }

    @Override
    public void openVideoSeek(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiVideoSeek(pos));
    }

    /**
     * 普通右键大屏：屏中间开一台电视机。
     *
     * <p>窗口本身是 {@link GuiVideoScreen#watch} 那个"从大屏进来的"展示窗口，
     * 只是不再一进来就切全屏：小窗状态就是一台摆在屏幕正中的电视（外壳、边框、
     * 底座都在 {@code GuiVideoScreen} 里画），F 再切全屏、再按 F 回到这台电视，
     * 关窗回到看片之前的桌面状态。</p>
     */
    @Override
    public void openVideoWatch(BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        mc.displayGuiScreen(GuiVideoScreen.watch(broadcasterOf(mc, pos), pos));
    }

    /** 菜单里的「全屏观看」：同一个电视窗口，进来就已经满屏。 */
    @Override
    public void openVideoFullscreen(BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            return;
        }
        GuiVideoScreen window = GuiVideoScreen.watch(broadcasterOf(mc, pos), pos);
        mc.displayGuiScreen(window);
        window.enterFullscreen();
    }

    /** The player block a wall answers to, or null when the wall is on its own. */
    private static BlockPos broadcasterOf(Minecraft mc, BlockPos wall) {
        net.minecraft.tileentity.TileEntity te = mc.world.getTileEntity(wall);
        return te instanceof com.hbmzz.tile.TileEntityVideoScreen
                ? ((com.hbmzz.tile.TileEntityVideoScreen) te).getPlayerPos() : null;
    }

    @Override
    public void openVideoPlayerConfig(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiVideoPlayerConfig(pos));
    }

    @Override
    public void openTerminal(int kind, BlockPos pos, int index) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiCtrlTerminal(kind, pos, index));
    }

    @Override
    public void openHackerTablet() {
        // No position argument: the tablet is not bound to a block. What it
        // writes to is decided by the "target" command, server side.
        Minecraft.getMinecraft().displayGuiScreen(new GuiHackerTablet());
    }

    @Override
    public void openAITerminal() {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAITerminal());
    }
}
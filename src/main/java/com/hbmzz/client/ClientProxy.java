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
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityDFCMonitor.class, new RenderDFCMonitor());
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
        list.add(loc("nuke"));
        list.add(loc("nuke_controller"));
        list.add(loc("nuke_monitor"));
        list.add(loc("code_broadcaster"));
        list.add(loc("alert_display"));
        // 黑客平板曾经不在这张表里。它之所以还能显示，只是因为原版兜底会去
        // models/item/<注册名>.json 找一份同名模型 —— 表里少一行不会当场炸，
        // 只会在有人给这个物品改名的那天安静地变成紫黑块。
        list.add(loc("hacker_tablet"));
        // 事故之后的第一块砖：DFC 一炸就输出红石。它自己也必须有模型位置，
        // 否则玩家放下之前根本认不出这是哪块砖。
        list.add(loc("dfc_blast_sensor"));
        // 四种红石门。它们同样必须在表里，否则物品栏里先是一排紫黑块。
        list.add(loc("and_gate"));
        list.add(loc("or_gate"));
        list.add(loc("xor_gate"));
        list.add(loc("not_gate"));
        list.add(loc("tartarus_gate"));
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
        list.add(ModContent.ITEM_BLOCK_NUKE);
        list.add(ModContent.ITEM_BLOCK_NUKE_CONTROLLER);
        list.add(ModContent.ITEM_BLOCK_NUKE_MONITOR);
        list.add(ModContent.ITEM_BLOCK_CODE_BROADCASTER);
        list.add(ModContent.ITEM_BLOCK_ALERT_DISPLAY);
        list.add(ModContent.ITEM_HACKER_TABLET);
        list.add(ModContent.ITEM_BLOCK_DFC_BLAST_SENSOR);
        list.add(ModContent.ITEM_BLOCK_AND_GATE);
        list.add(ModContent.ITEM_BLOCK_OR_GATE);
        list.add(ModContent.ITEM_BLOCK_XOR_GATE);
        list.add(ModContent.ITEM_BLOCK_NOT_GATE);
        list.add(ModContent.ITEM_BLOCK_TARTARUS_GATE);
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
            //  control_panel  console  video  screen  nuke  controller  nuke_monitor  code  alert  tablet
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            //  dfc_blast_sensor
            0,
            //  and_gate  or_gate  xor_gate  not_gate
            0, 0, 0, 0,
            //  tartarus_gate
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
    public void openControlPanel(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiCtrlPanel(pos));
    }

    @Override
    public void openVideoBroadcaster(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiVideoBroadcaster(pos));
    }

    /**
     * The watching window for a video block.
     *
     * <p>Works for both ends: a screen in the world (it copies the clip from its
     * broadcaster and syncs it) and a broadcaster itself (right-clicking one
     * while not sneaking watches rather than configures). An unlinked screen
     * opens the same window with an explanation, because "nothing happens" is the
     * one answer that leaves the player with no next step.</p>
     */
    @Override
    public void openVideoViewer(BlockPos pos) {
        String clip = "";
        net.minecraft.tileentity.TileEntity te = Minecraft.getMinecraft().world == null
                ? null : Minecraft.getMinecraft().world.getTileEntity(pos);
        if (te instanceof com.hbmzz.tile.TileEntityVideoScreen) {
            clip = ((com.hbmzz.tile.TileEntityVideoScreen) te).getVideo();
        } else if (te instanceof com.hbmzz.tile.TileEntityVideoBroadcaster) {
            clip = ((com.hbmzz.tile.TileEntityVideoBroadcaster) te).getVideo();
        }
        Minecraft.getMinecraft().displayGuiScreen(new GuiVideoScreen(clip, true));
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
}

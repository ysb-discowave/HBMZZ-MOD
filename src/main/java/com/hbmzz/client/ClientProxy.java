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
        list.add(loc("nuke"));
        list.add(loc("nuke_controller"));
        list.add(loc("nuke_monitor"));
        list.add(loc("code_broadcaster"));
        list.add(loc("alert_display"));
        // 黑客平板曾经不在这张表里。它之所以还能显示，只是因为原版兜底会去
        // models/item/<注册名>.json 找一份同名模型 —— 表里少一行不会当场炸，
        // 只会在有人给这个物品改名的那天安静地变成紫黑块。
        list.add(loc("hacker_tablet"));
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
        list.add(ModContent.ITEM_BLOCK_NUKE);
        list.add(ModContent.ITEM_BLOCK_NUKE_CONTROLLER);
        list.add(ModContent.ITEM_BLOCK_NUKE_MONITOR);
        list.add(ModContent.ITEM_BLOCK_CODE_BROADCASTER);
        list.add(ModContent.ITEM_BLOCK_ALERT_DISPLAY);
        list.add(ModContent.ITEM_HACKER_TABLET);
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
            //  control_panel  console  video  nuke  controller  nuke_monitor  code  alert  tablet
            0, 0, 0, 0, 0, 0, 0, 0, 0
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

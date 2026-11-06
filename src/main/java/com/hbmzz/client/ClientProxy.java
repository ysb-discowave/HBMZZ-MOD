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
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

import java.util.ArrayList;
import java.util.List;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
        MinecraftForge.EVENT_BUS.register(new MonitorHud());
        ClientRegistry.bindTileEntitySpecialRenderer(TileEntityDFCMonitor.class, new RenderDFCMonitor());
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

    @Override
    public void init(FMLInitializationEvent event) {
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
        return list;
    }

    public static List<Integer> metas() {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            list.add(0);
        }
        list.add(0);
        list.add(1);
        list.add(2);
        list.add(0);
        list.add(0);
        list.add(0);
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
}

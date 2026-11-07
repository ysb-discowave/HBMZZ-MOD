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

import com.hbmzz.common.CommonEvents;
import com.hbmzz.common.CommonProxy;
import com.hbmzz.gun.GunHandler;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.tile.TileEntityDFCMonitor;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.Logger;

@Mod(modid = HBMZZ.MODID, name = HBMZZ.NAME, version = HBMZZ.VERSION,
        dependencies = "after:hbm")
public class HBMZZ {

    public static final String MODID = "hbmzz";
    public static final String NAME = "HBMZZ";
    public static final String VERSION = "2.7.1";

    @Mod.Instance(MODID)
    public static HBMZZ instance;

    @SidedProxy(clientSide = "com.hbmzz.client.ClientProxy", serverSide = "com.hbmzz.common.CommonProxy")
    public static CommonProxy proxy;

    public static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        HBMZZConfig.load(event.getSuggestedConfigurationFile());
        NetworkHandler.init();
        MinecraftForge.EVENT_BUS.register(ModContent.class);
        MinecraftForge.EVENT_BUS.register(new CommonEvents());
        MinecraftForge.EVENT_BUS.register(new GunHandler());
        GameRegistry.registerTileEntity(TileEntityDFCMonitor.class, new ResourceLocation(MODID, "dfc_monitor"));
        GameRegistry.registerTileEntity(com.hbmzz.tile.TileEntityCtrlPanel.class,
                new ResourceLocation(MODID, "control_panel"));
        GameRegistry.registerTileEntity(com.hbmzz.cecp.tile.TileEntityCtrlConsole.class,
                new ResourceLocation(MODID, "ctrl_console"));
        GameRegistry.registerTileEntity(com.hbmzz.tile.TileEntityVideoBroadcaster.class,
                new ResourceLocation(MODID, "video_broadcaster"));
        // The camera syncs its name/channel/aim through SPacketUpdateTileEntity, so
        // the very first setName() in onBlockPlacedBy calls writeToNBT(). Without a
        // mapping that throws "TileEntityMonitorCamera is missing a mapping!" and the
        // game dies the instant the block is placed - not on save, on place.
        GameRegistry.registerTileEntity(com.hbmzz.tile.TileEntityMonitorCamera.class,
                new ResourceLocation(MODID, "monitor_camera"));
        // The console opens its layout editor through FML's gui packet, so this
        // mod needs a gui handler even though the id is always 0.
        net.minecraftforge.fml.common.network.NetworkRegistry.INSTANCE.registerGuiHandler(
                instance, new com.hbmzz.cecp.CtrlConsoleGuiHandler());
        // Cursed Edition filled these three registries from its own MainRegistry.
        // Nothing else does it here, and an empty ControlRegistry means the
        // "new control" list would be blank, so this has to happen before any
        // panel can be opened.
        com.hbmzz.cecp.ControlEvent.init();
        // NTM addons (CE Addon / Leafia among them) mix extra events into CE's
        // own ControlEvent.init(). Those machines declare event names the copied
        // stock table has never heard of, and an unknown name degrades to an
        // event with no variables - which is why a broadcast node for such a
        // machine showed nothing but "Cancel". Mirror the live table instead of
        // guessing at its contents.
        int mirroredEvents = com.hbmzz.cecp.compat.CEEventTable.mirror();
        if (mirroredEvents > 0) {
            logger.info("Mirrored {} extra control events from NTM.", mirroredEvents);
        }
        com.hbmzz.cecp.ControlRegistry.init();
        com.hbmzz.cecp.modular.StockNodesRegister.register();
        // The fallen panel. Registered with a tracking range so the swing is
        // seen by everyone nearby, not just the player who broke it.
        net.minecraftforge.fml.common.registry.EntityRegistry.registerModEntity(
                new ResourceLocation(MODID, "fallen_screen"),
                com.hbmzz.entity.EntityFallenScreen.class, "fallen_screen", 0, instance, 64, 2, true);
        // The RBMK lid that gets thrown off. Registered with a wide tracking
        // range: the plate is the point, and half the reactor's neighbourhood
        // should be able to watch it come back down.
        net.minecraftforge.fml.common.registry.EntityRegistry.registerModEntity(
                new ResourceLocation(MODID, "rbmk_lid"),
                com.hbmzz.entity.EntityRbmkLid.class, "rbmk_lid", 1, instance, 256, 2, true);
        // The staged RBMK blow-up. Server side only, and inert until a reactor
        // trips, so it costs one list scan every twenty ticks.
        MinecraftForge.EVENT_BUS.register(new com.hbmzz.rbmk.RbmkSurge());
        proxy.preInit(event);
        logger.info("HBMZZ loaded (DFC Monitor + coils + jetpack).");
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }
}
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

import com.hbmzz.HBMZZ;
import com.hbmzz.item.ItemGun;
import com.hbmzz.item.ItemJetpack;
import com.hbmzz.network.GunTriggerMessage;
import com.hbmzz.network.JetpackThrustMessage;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.registry.IRegistry;
import net.minecraftforge.client.event.ModelBakeEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

/**
 * Client side input: forwards the jetpack thrust state and the gun trigger to
 * the server. Both are sent as edge transitions only, so a held key costs one
 * packet instead of twenty per second.
 */
public class ClientEvents {

    private boolean lastThrust;
    private boolean lastTrigger;

    /**
     * Verifies every item model actually resolved to something other than the
     * missing model. Without this a bad model silently shows a magenta square
     * and the log says nothing, which is exactly the kind of bug that takes
     * three rounds to find.
     */
    @SubscribeEvent
    public void onModelBake(ModelBakeEvent event) {
        IRegistry<ModelResourceLocation, IBakedModel> registry = event.getModelRegistry();
        IBakedModel missing = event.getModelManager().getMissingModel();
        List<ModelResourceLocation> models = ClientProxy.itemModels();
        int broken = 0;
        for (ModelResourceLocation location : models) {
            IBakedModel baked = registry.getObject(location);
            if (baked == null || baked == missing) {
                broken++;
                HBMZZ.logger.error("HBMZZ item model did not resolve: " + location);
            }
        }
        if (broken == 0) {
            HBMZZ.logger.info("HBMZZ item models: " + models.size() + "/" + models.size() + " resolved");
        } else {
            HBMZZ.logger.error("HBMZZ item models: " + broken + " of " + models.size() + " are missing");
        }
    }

    /**
     * Releases the per-monitor panel textures when a world goes away.
     *
     * <p>Each panel keeps a 768x768 texture; without this a long session of
     * hopping between worlds would leak a few megabytes per hop.</p>
     */
    @SubscribeEvent
    public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
        if (event.getWorld() != null && event.getWorld().isRemote) {
            RenderDFCMonitor.clearCaches();
            RenderFallenScreen.clearCaches();
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        if (player == null) {
            // Leaving a world drops the server side state; forget ours as well so
            // the next press is sent again instead of staying latched.
            this.lastThrust = false;
            this.lastTrigger = false;
            return;
        }

        boolean wearing = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST).getItem() instanceof ItemJetpack;
        boolean thrust = wearing && mc.gameSettings.keyBindJump.isKeyDown();
        if (thrust != this.lastThrust) {
            this.lastThrust = thrust;
            NetworkHandler.sendToServer(new JetpackThrustMessage(thrust));
        }

        ItemStack held = player.getHeldItemMainhand();
        boolean armed = held.getItem() instanceof ItemGun && mc.currentScreen == null && !player.isSpectator();
        boolean trigger = armed && mc.gameSettings.keyBindUseItem.isKeyDown();
        if (trigger != this.lastTrigger) {
            this.lastTrigger = trigger;
            NetworkHandler.sendToServer(new GunTriggerMessage(trigger));
        }
    }
}

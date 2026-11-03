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

import com.hbmzz.item.ItemCoil;
import com.hbmzz.item.ItemJetpack;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.EnumParticleTypes;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server side: applies the held-coil effects and drives the jetpack thrust.
 */
public class CommonEvents {

    /** Players whose jump key is currently held (set by the client thrust packet). */
    public static final Set<UUID> THRUSTING = new HashSet<>();

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.world.isRemote) {
            return;
        }
        EntityPlayer player = event.player;
        applyCoil(player.getHeldItemMainhand(), player);
        applyCoil(player.getHeldItemOffhand(), player);
        tickJetpack(player);
    }

    private void applyCoil(ItemStack stack, EntityPlayer player) {
        if (stack.getItem() instanceof ItemCoil) {
            ItemCoil coil = (ItemCoil) stack.getItem();
            PotionEffect existing = player.getActivePotionEffect(coil.getPotion());
            if (existing == null || existing.getDuration() <= 30) {
                player.addPotionEffect(new PotionEffect(coil.getPotion(), 60, coil.getAmplifier(), false, true));
            }
        }
    }

    private void tickJetpack(EntityPlayer player) {
        ItemStack chest = player.getItemStackFromSlot(EntityEquipmentSlot.CHEST);
        boolean wearing = chest.getItem() instanceof ItemJetpack;
        if (wearing && THRUSTING.contains(player.getUniqueID())
                && chest.getItemDamage() < chest.getMaxDamage()) {
            player.motionY = Math.min(player.motionY + 0.12D, 0.6D);
            player.fallDistance = 0.0F;
            player.velocityChanged = true;
            if (player.world.getTotalWorldTime() % 3L == 0L) {
                chest.damageItem(1, player);
            }
            if (player.world.getTotalWorldTime() % 2L == 0L) {
                for (int i = 0; i < 2; i++) {
                    player.world.spawnParticle(EnumParticleTypes.FLAME,
                            player.posX + (player.world.rand.nextDouble() - 0.5D) * 0.4D,
                            player.posY - 0.1D,
                            player.posZ + (player.world.rand.nextDouble() - 0.5D) * 0.4D,
                            0.0D, -0.6D, 0.0D);
                }
            }
        }
        if (!wearing) {
            THRUSTING.remove(player.getUniqueID());
        }
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        THRUSTING.remove(event.player.getUniqueID());
    }
}
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

package com.hbmzz.gun;

import com.hbmzz.item.ItemAmmo;
import com.hbmzz.item.ItemGun;
import com.hbmzz.item.ItemGun.GunType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EntityDamageSourceIndirect;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server side gun logic: trigger state, rate of fire, magazines, reloads and
 * the hitscan trace.
 *
 * <p>The client only reports whether the use key is held; every authoritative
 * decision (ammo, cooldown, what got hit) happens here.</p>
 */
public class GunHandler {

    /**
     * Players currently holding the trigger. Written from the network thread,
     * read from the server thread, hence the concurrent backing maps.
     */
    private static final Set<UUID> TRIGGER = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    /** Semi-auto weapons that already fired for the current trigger pull. */
    private static final Set<UUID> LATCH = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());

    private final Map<UUID, Long> lastShot = new ConcurrentHashMap<>();

    public static void setTrigger(UUID player, boolean active) {
        if (active) {
            TRIGGER.add(player);
        } else {
            TRIGGER.remove(player);
            LATCH.remove(player);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        EntityPlayer player = event.player;
        if (player.world == null || player.world.isRemote) {
            return;
        }
        tick(player);
    }

    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.player.getUniqueID();
        TRIGGER.remove(id);
        LATCH.remove(id);
        this.lastShot.remove(id);
    }

    private void tick(EntityPlayer player) {
        UUID id = player.getUniqueID();
        ItemStack held = player.getHeldItemMainhand();

        if (!(held.getItem() instanceof ItemGun) || player.isSpectator()) {
            TRIGGER.remove(id);
            LATCH.remove(id);
            return;
        }

        ItemGun gun = (ItemGun) held.getItem();
        GunType type = gun.getType();
        long now = player.world.getTotalWorldTime();
        boolean creative = player.capabilities.isCreativeMode;

        if (ItemGun.isReloading(held)) {
            finishReload(player, held, type, now, creative);
            return;
        }

        if (!TRIGGER.contains(id)) {
            LATCH.remove(id);
            return;
        }

        // Sneak + trigger reloads instead of firing.
        if (player.isSneaking()) {
            startReload(player, held, type, now, creative);
            LATCH.add(id);
            return;
        }

        if (!type.automatic && LATCH.contains(id)) {
            return;
        }
        Long previous = this.lastShot.get(id);
        if (previous != null && now - previous < type.interval) {
            return;
        }

        int mag = creative ? type.magazine : ItemGun.getMag(held);
        if (!creative && mag <= 0) {
            startReload(player, held, type, now, false);
            LATCH.add(id);
            return;
        }

        shoot(player, type);
        this.lastShot.put(id, now);
        if (creative) {
            if (ItemGun.getMag(held) != type.magazine) {
                ItemGun.setMag(held, type.magazine);
            }
        } else {
            ItemGun.setMag(held, mag - 1);
        }
        if (!type.automatic) {
            LATCH.add(id);
        }
    }

    private void startReload(EntityPlayer player, ItemStack stack, GunType type, long now, boolean creative) {
        if (ItemGun.getMag(stack) >= type.magazine) {
            return;
        }
        if (!creative && countAmmo(player, type.ammoMeta) <= 0) {
            play(player.world, player, SoundEvents.BLOCK_LEVER_CLICK, 0.4F, 0.6F);
            return;
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setLong(ItemGun.TAG_RELOAD, now + type.reloadTicks);
        play(player.world, player, SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE, 0.5F, 1.3F);
    }

    private void finishReload(EntityPlayer player, ItemStack stack, GunType type, long now, boolean creative) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null || now < tag.getLong(ItemGun.TAG_RELOAD)) {
            return;
        }
        tag.removeTag(ItemGun.TAG_RELOAD);
        int missing = type.magazine - ItemGun.getMag(stack);
        int loaded = creative ? missing : takeAmmo(player, type.ammoMeta, missing);
        ItemGun.setMag(stack, ItemGun.getMag(stack) + loaded);
        play(player.world, player, loaded > 0 ? SoundEvents.ITEM_ARMOR_EQUIP_IRON : SoundEvents.BLOCK_LEVER_CLICK,
                0.5F, loaded > 0 ? 1.4F : 0.7F);
    }

    private void shoot(EntityPlayer player, GunType type) {
        World world = player.world;
        Vec3d eye = new Vec3d(player.posX, player.posY + (double) player.getEyeHeight() - 0.1D, player.posZ);
        Vec3d look = player.getLookVec();

        for (int pellet = 0; pellet < type.pellets; pellet++) {
            Vec3d direction = look;
            if (type.spread > 0.0D) {
                direction = new Vec3d(
                        look.x + world.rand.nextGaussian() * type.spread,
                        look.y + world.rand.nextGaussian() * type.spread,
                        look.z + world.rand.nextGaussian() * type.spread).normalize();
            }
            trace(player, eye, direction, type);
        }

        Vec3d muzzle = eye.addVector(look.x * 0.6D, look.y * 0.6D, look.z * 0.6D);
        world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL, muzzle.x, muzzle.y, muzzle.z,
                look.x * 0.06D, look.y * 0.06D, look.z * 0.06D);
        world.spawnParticle(EnumParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 0.0D, 0.0D, 0.0D);
        play(world, player,
                type == GunType.BREAKER ? SoundEvents.ENTITY_FIREWORK_LARGE_BLAST : SoundEvents.ENTITY_FIREWORK_BLAST,
                0.9F, 1.0F + (world.rand.nextFloat() - 0.5F) * 0.12F);
    }

    private void trace(EntityPlayer player, Vec3d start, Vec3d direction, GunType type) {
        World world = player.world;
        Vec3d end = start.addVector(direction.x * type.range, direction.y * type.range, direction.z * type.range);
        RayTraceResult blockHit = world.rayTraceBlocks(start, end, false, true, false);

        double closest = type.range * type.range;
        Entity target = null;
        Vec3d impact = null;

        AxisAlignedBB search = new AxisAlignedBB(
                Math.min(start.x, end.x), Math.min(start.y, end.y), Math.min(start.z, end.z),
                Math.max(start.x, end.x), Math.max(start.y, end.y), Math.max(start.z, end.z)).grow(1.0D);
        for (Entity entity : world.getEntitiesWithinAABBExcludingEntity(player, search)) {
            if (entity == null || !entity.canBeCollidedWith()) {
                continue;
            }
            RayTraceResult hit = entity.getEntityBoundingBox().grow(0.3D).calculateIntercept(start, end);
            if (hit == null) {
                continue;
            }
            double distance = start.squareDistanceTo(hit.hitVec);
            if (distance < closest) {
                closest = distance;
                target = entity;
                impact = hit.hitVec;
            }
        }

        if (blockHit != null) {
            double distance = start.squareDistanceTo(blockHit.hitVec);
            if (distance < closest) {
                closest = distance;
                target = null;
                impact = blockHit.hitVec;
            }
        }

        if (target != null) {
            // Automatic fire outruns the vanilla 10 tick invulnerability window.
            target.hurtResistantTime = 0;
            target.attackEntityFrom(new EntityDamageSourceIndirect("hbmzz.gun", player, player), (float) type.damage);
        }
        if (impact != null) {
            world.spawnParticle(EnumParticleTypes.SMOKE_NORMAL, impact.x, impact.y, impact.z, 0.0D, 0.0D, 0.0D);
            world.spawnParticle(target != null ? EnumParticleTypes.DAMAGE_INDICATOR : EnumParticleTypes.CRIT,
                    impact.x, impact.y, impact.z, 0.0D, 0.0D, 0.0D);
        }
    }

    private static void play(World world, EntityPlayer player, SoundEvent sound, float volume, float pitch) {
        world.playSound(null, player.posX, player.posY, player.posZ, sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private static int countAmmo(EntityPlayer player, int meta) {
        int total = 0;
        for (ItemStack stack : player.inventory.mainInventory) {
            total += ammoCount(stack, meta);
        }
        for (ItemStack stack : player.inventory.offHandInventory) {
            total += ammoCount(stack, meta);
        }
        return total;
    }

    private static int ammoCount(ItemStack stack, int meta) {
        return !stack.isEmpty() && stack.getItem() instanceof ItemAmmo && stack.getMetadata() == meta ? stack.getCount() : 0;
    }

    private static int takeAmmo(EntityPlayer player, int meta, int wanted) {
        int left = wanted;
        for (ItemStack stack : player.inventory.mainInventory) {
            left = drain(stack, meta, left);
        }
        for (ItemStack stack : player.inventory.offHandInventory) {
            left = drain(stack, meta, left);
        }
        return wanted - left;
    }

    private static int drain(ItemStack stack, int meta, int left) {
        if (left <= 0 || stack.isEmpty() || !(stack.getItem() instanceof ItemAmmo) || stack.getMetadata() != meta) {
            return left;
        }
        int taken = Math.min(left, stack.getCount());
        stack.shrink(taken);
        return left - taken;
    }
}

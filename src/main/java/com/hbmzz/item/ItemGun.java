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

package com.hbmzz.item;

import com.hbmzz.HBMZZ;
import com.hbmzz.ModContent;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;
import java.util.Locale;

/**
 * A hitscan firearm. The shooting itself happens on the server in
 * {@link com.hbmzz.gun.GunHandler}; this class only carries the stats, the
 * magazine stored in NBT and the tooltip.
 */
public class ItemGun extends Item {

    /** Rounds currently in the magazine. */
    public static final String TAG_MAG = "Mag";
    /** World time at which the running reload finishes. */
    public static final String TAG_RELOAD = "ReloadDone";

    public enum GunType {
        /** Slow, accurate semi-auto sidearm. */
        SIDEARM("gun_pistol", 7.0D, 6, 12, 40.0D, 1, 0.006D, false, 0, 20),
        /** Fast full-auto carbine with a big magazine. */
        CARBINE("gun_rifle", 5.5D, 3, 30, 60.0D, 1, 0.012D, true, 1, 30),
        /** Eight-pellet close range breaker. */
        BREAKER("gun_shotgun", 4.0D, 20, 6, 20.0D, 8, 0.090D, false, 2, 40);

        public final String registry;
        public final double damage;
        /** Ticks between two shots. */
        public final int interval;
        public final int magazine;
        public final double range;
        public final int pellets;
        /** Direction jitter per pellet, 0 = perfectly accurate. */
        public final double spread;
        public final boolean automatic;
        /** Metadata of the matching {@link ItemAmmo} entry. */
        public final int ammoMeta;
        public final int reloadTicks;

        GunType(String registry, double damage, int interval, int magazine, double range,
                int pellets, double spread, boolean automatic, int ammoMeta, int reloadTicks) {
            this.registry = registry;
            this.damage = damage;
            this.interval = interval;
            this.magazine = magazine;
            this.range = range;
            this.pellets = pellets;
            this.spread = spread;
            this.automatic = automatic;
            this.ammoMeta = ammoMeta;
            this.reloadTicks = reloadTicks;
        }
    }

    private final GunType type;

    public ItemGun(GunType type) {
        this.type = type;
        setRegistryName(HBMZZ.MODID, type.registry);
        setUnlocalizedName(HBMZZ.MODID + "." + type.registry);
        setCreativeTab(CreativeTabs.COMBAT);
        setMaxStackSize(1);
    }

    public GunType getType() {
        return this.type;
    }

    public static int getMag(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag == null ? 0 : tag.getInteger(TAG_MAG);
    }

    public static void setMag(ItemStack stack, int value) {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) {
            tag = new NBTTagCompound();
            stack.setTagCompound(tag);
        }
        tag.setInteger(TAG_MAG, Math.max(0, value));
    }

    public static boolean isReloading(ItemStack stack) {
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey(TAG_RELOAD);
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        GunType t = this.type;
        if (ItemGun.isReloading(stack)) {
            tooltip.add(TextFormatting.GOLD + I18n.translateToLocal("hbmzz.gun.reloading"));
        }
        tooltip.add(TextFormatting.GRAY + I18n.translateToLocal("hbmzz.gun.caliber") + ": "
                + TextFormatting.WHITE + ammoName(t.ammoMeta));
        tooltip.add(TextFormatting.GRAY + I18n.translateToLocal("hbmzz.gun.magazine") + ": "
                + TextFormatting.WHITE + ItemGun.getMag(stack) + " / " + t.magazine);
        tooltip.add(TextFormatting.GRAY + I18n.translateToLocal("hbmzz.gun.damage") + ": "
                + TextFormatting.WHITE + String.format(Locale.ROOT, "%.1f", t.damage * t.pellets));
        tooltip.add(TextFormatting.GRAY + I18n.translateToLocal("hbmzz.gun.mode") + ": "
                + TextFormatting.WHITE + I18n.translateToLocal(t.automatic ? "hbmzz.gun.mode.auto" : "hbmzz.gun.mode.semi"));
        tooltip.add(TextFormatting.DARK_GREEN + I18n.translateToLocal("hbmzz.gun.hint"));
    }

    /** Display name of the matching ammo stack, so both stay in sync. */
    public static String ammoName(int meta) {
        return new ItemStack(ModContent.ITEM_AMMO, 1, meta).getDisplayName();
    }
}

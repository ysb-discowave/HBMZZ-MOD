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
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.NonNullList;

/**
 * Ammunition. Metadata selects the caliber so a single item covers every gun.
 */
public class ItemAmmo extends Item {

    /** Metadata order, index == stack metadata. */
    public static final String[] CALIBERS = {"9mm", "556", "12ga"};

    public ItemAmmo() {
        setRegistryName(HBMZZ.MODID, "ammo");
        setUnlocalizedName(HBMZZ.MODID + ".ammo");
        setHasSubtypes(true);
        setMaxDamage(0);
        setCreativeTab(CreativeTabs.COMBAT);
    }

    @Override
    public String getUnlocalizedName(ItemStack stack) {
        int meta = stack.getMetadata();
        String caliber = meta >= 0 && meta < CALIBERS.length ? CALIBERS[meta] : CALIBERS[0];
        return "item." + HBMZZ.MODID + ".ammo." + caliber;
    }

    @Override
    public void getSubItems(CreativeTabs tab, NonNullList<ItemStack> items) {
        if (!isInCreativeTab(tab)) {
            return;
        }
        for (int i = 0; i < CALIBERS.length; i++) {
            items.add(new ItemStack(this, 1, i));
        }
    }
}

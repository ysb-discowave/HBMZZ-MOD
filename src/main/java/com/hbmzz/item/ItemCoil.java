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
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.MobEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * A Roblox-style "coil" that grants a passive potion effect while held.
 */
public class ItemCoil extends Item {

    public enum CoilType {
        SPEED(MobEffects.SPEED, 2, "speed"),
        JUMP(MobEffects.JUMP_BOOST, 2, "jump"),
        REGEN(MobEffects.REGENERATION, 1, "regen");

        public final Potion potion;
        public final int amplifier;
        public final String suffix;

        CoilType(Potion potion, int amplifier, String suffix) {
            this.potion = potion;
            this.amplifier = amplifier;
            this.suffix = suffix;
        }
    }

    private final CoilType type;

    public ItemCoil(CoilType type) {
        this.type = type;
        setRegistryName(HBMZZ.MODID, type.suffix + "_coil");
        setUnlocalizedName(HBMZZ.MODID + "." + type.suffix + "_coil");
        setCreativeTab(CreativeTabs.TOOLS);
        setMaxStackSize(1);
    }

    public Potion getPotion() {
        return this.type.potion;
    }

    public int getAmplifier() {
        return this.type.amplifier;
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.DARK_GREEN + I18n.translateToLocal("hbmzz.coil.desc"));
    }
}
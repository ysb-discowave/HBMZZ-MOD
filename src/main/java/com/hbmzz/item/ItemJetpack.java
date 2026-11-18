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
import net.minecraft.init.Items;
import net.minecraft.init.SoundEvents;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.common.util.EnumHelper;

import java.util.List;

/**
 * A wearable jetpack: hold jump while wearing it to thrust upward. Durability
 * is the fuel gauge; repair with iron ingots in an anvil.
 */
public class ItemJetpack extends ItemArmor {

    public static final int MAX_FUEL = 2400;

    /**
     * The texture argument must carry the {@code hbmzz:} domain. LayerArmorBase
     * splits the material name on ':' and falls back to {@code minecraft:} when
     * there is none, which is why an unqualified name used to render as the
     * purple/black missing texture on the player model.
     */
    private static final String TEXTURE = "hbmzz:hbmzz_jetpack";

    private static final ArmorMaterial MATERIAL = EnumHelper.addArmorMaterial(
            "hbmzz_jetpack", TEXTURE, 15, new int[]{0, 0, 0, 0}, 0,
            SoundEvents.ITEM_ARMOR_EQUIP_IRON, 0.0F);

    public ItemJetpack() {
        super(MATERIAL, 1, EntityEquipmentSlot.CHEST);
        setRegistryName(HBMZZ.MODID, "jetpack");
        setUnlocalizedName(HBMZZ.MODID + ".jetpack");
        setCreativeTab(CreativeTabs.TRANSPORTATION);
        setMaxStackSize(1);
        setMaxDamage(MAX_FUEL);
    }

    @Override
    public int getMaxDamage() {
        return MAX_FUEL;
    }

    @Override
    public boolean getIsRepairable(ItemStack toRepair, ItemStack repair) {
        return repair.getItem() == Items.IRON_INGOT;
    }

    @Override
    public String getArmorTexture(ItemStack stack, Entity entity, EntityEquipmentSlot slot, String type) {
        // Forge uses this string verbatim as a TextureManager path, so it needs
        // the full textures/models/armor prefix. A bare name 404s and shows up
        // as the purple/black checkerboard on the worn chestplate.
        return "hbmzz:textures/models/armor/hbmzz_jetpack_layer_1.png";
    }

    @Override
    public void addInformation(ItemStack stack, World world, List<String> tooltip, ITooltipFlag flag) {
        tooltip.add(TextFormatting.AQUA + I18n.translateToLocal("hbmzz.jetpack.desc"));
        int fuel = Math.max(0, stack.getMaxDamage() - stack.getItemDamage());
        tooltip.add(TextFormatting.DARK_GRAY + I18n.translateToLocal("hbmzz.jetpack.fuel") + ": " + fuel);
    }
}
package com.rsaudio;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public class RsAudioTab extends CreativeTabs {

    public static final RsAudioTab INSTANCE = new RsAudioTab();

    public RsAudioTab() {
        super(RsAudio.MODID);
    }

    /** 1.12.2 里创造模式物品栏的图标方法叫 getTabIconItem（1.13+ 才改名 createIcon） */
    @Override
    @SideOnly(Side.CLIENT)
    public ItemStack getTabIconItem() {
        return new ItemStack(ModBlocks.AUDIO_PLAYER);
    }
}

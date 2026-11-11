package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * DMR 反应堆外壳：多方块结构的"砖"。
 * <p>
 * 它本身不做任何事 —— 没有方块实体、不 tick、不读红石。存在的唯一理由是让玩家
 * <b>亲手砌出</b>那台机器：3×3×3 的壳围住一块 {@link BlockDmrCore}，控制柜贴在外面。
 * 结构由控制柜每 20 tick 扫一次（见 {@code TileDmrController}），这里只负责长得像外壳。
 * <p>
 * 抗爆调到很高：内爆时最先没的应该是堆芯和控制柜，而不是把整座厂房一起带走 ——
 * 这样"炸完还得重建外壳"才是个有意义的代价。
 */
public class BlockDmrCasing extends Block {

    public BlockDmrCasing() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "dmr_casing");
        setUnlocalizedName(RsAudio.MODID + ".dmr_casing");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(6.0F);
        setResistance(120.0F);
        setSoundType(SoundType.METAL);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.dmr_casing.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.dmr_casing.2"));
    }
}

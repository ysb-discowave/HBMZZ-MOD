package com.rsaudio;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 模组自己的音效登记（目前只有纪念挂画那一首）。
 *
 * <h3>为什么要自己登记</h3>
 * 1.12.2 的 {@code SoundEvent.REGISTRY} 只收本模组"声明过"的音效；
 * 服务端播声音时发的 {@code SPacketCustomSound} 带的是
 * {@code sound.getRegistryName()} —— 没登记的 {@link SoundEvent} 这个值是 null，
 * 客户端收到就直接丢掉。所以哪怕 sounds.json 里写好了，也还是得在这里注册。
 *
 * <h3>★ 这里刻意没有 {@code @Mod.EventBusSubscriber}</h3>
 * {@code rsaudio} 是同一枚 jar 里的<b>第二个</b> {@code @Mod} 容器，注解注入那条链
 * 一旦漏掉本类，失败是<b>完全静默</b>的（见 {@link ModBlocks} 的类注释）。
 * 所以和 {@code ModBlocks} 同款：由 {@link RsAudio#preInit} 显式
 * {@code MinecraftForge.EVENT_BUS.register(ModSounds.class)}。
 *
 * <h3>音源出处</h3>
 * 纪念挂画潜行右键放的那首歌，音源是 B 站 UP 主「梨茗子」的 AI 生成作品《念黄师》
 * （https://www.bilibili.com/video/BV1ALWa6dEfN），压成
 * {@code assets/rsaudio/sounds/huang_chant.ogg}（Vorbis，stream）。
 * ★ 这是彩蛋，跟模组功能无关；要拿掉只需删这一个字段 + sounds.json 里那一条。
 */
public final class ModSounds {

    /** 纪念挂画潜行右键放的那首歌 */
    public static final SoundEvent HUANG_CHANT = make("block.huang_poster.chant");

    private ModSounds() {
    }

    private static SoundEvent make(String name) {
        ResourceLocation loc = new ResourceLocation(RsAudio.MODID, name);
        SoundEvent event = new SoundEvent(loc);
        event.setRegistryName(loc);
        return event;
    }

    @SubscribeEvent
    public static void register(RegistryEvent.Register<SoundEvent> event) {
        event.getRegistry().register(HUANG_CHANT);
    }
}

package com.rsaudio.client;

import com.rsaudio.ModSounds;
import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockHuangPoster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;

/**
 * 纪念挂画那首歌的播放控制（客户端本地， purely decorative）。
 *
 * <h3>为什么要单独做一个控制器</h3>
 * 之前是 {@code playerIn.playSound(...)} 一把梭：MC 内部 new 一个 {@link ISound} 就丢掉引用，
 * 于是两件事都做不到 ——
 * <ol>
 *   <li>连点两下会叠两遍（原来每次点击都是一次全新的 playSound）；</li>
 *   <li>方块打掉后停不下来（没有 ISound 引用就没法 {@code stopSound}）。</li>
 * </ol>
 * 现在把引用存下来：播放前先判断"是不是还在响"，是就忽略这次点击；
 * 不是就先停掉旧的那份（保险），再播新的。每 tick 回头看一眼播放的那个坐标，
 * 只要那儿不再是纪念挂画，立刻停。
 *
 * <h3>音量/衰减</h3>
 * {@link AttenuationType#NONE}：这是彩蛋，玩家走远也该听得见整首，
 * 不要像方块音效那样随距离淡出；至于"同服其他人听不到"是刻意的，见 BlockHuangPoster。
 */
public final class HuangChantController {

    /** 当前正在播的那一份；null = 没在播。只有客户端线程会碰。 */
    private static ISound current;
    /** 当前播放对应的挂画坐标（用来判断方块还在不在） */
    private static BlockPos at;

    /** 音量/音调沿用原来的 playerIn.playSound(HUANG_CHANT, 2.0F, 1.0F)。 */
    private static final float VOLUME = 2.0F;
    private static final float PITCH = 1.0F;

    private HuangChantController() {
    }

    /**
     * 潜行右键挂画时调用。
     *
     * @return true 表示这次真的开始播了；false 表示上一遍还没放完，这次被忽略
     */
    public static boolean play(BlockPos pos) {
        Minecraft mc = Minecraft.getMinecraft();
        SoundHandler handler = mc.getSoundHandler();
        if (handler == null) {
            return false;
        }
        // 同一时间只允许一份：上一遍还在响就直接忽略这次点击（"只按一次"）
        if (current != null && handler.isSoundPlaying(current)) {
            return false;
        }
        // 上一遍已经自然结束、但引用还留着 —— 先清干净再播，杜绝叠加
        stop();

        ResourceLocation loc = ModSounds.HUANG_CHANT.getSoundName();
        if (handler.getAccessor(loc) == null) {
            RsAudio.warn("[纪念挂画] 找不到 %s 的音效资源，放不出歌", loc);
            return false;
        }
        float x = mc.player == null ? 0.0F : (float) mc.player.posX;
        float y = mc.player == null ? 0.0F : (float) mc.player.posY;
        float z = mc.player == null ? 0.0F : (float) mc.player.posZ;
        current = new PositionedSoundRecord(loc, SoundCategory.RECORDS, VOLUME, PITCH,
                false, 0, ISound.AttenuationType.NONE, x, y, z);
        at = pos == null ? null : pos.toImmutable();
        handler.playSound(current);
        return true;
    }

    /** 立刻停掉当前那一份（方块被打掉 / 换存档时调用）。 */
    public static void stop() {
        if (current != null) {
            Minecraft mc = Minecraft.getMinecraft();
            SoundHandler handler = mc.getSoundHandler();
            if (handler != null) {
                handler.stopSound(current);
            }
            current = null;
        }
        at = null;
    }

    /**
     * 每客户端 tick 调用一次：
     * ① 声音自己放完了 → 清掉引用，下次点击可以重新播；
     * ② 播放的那个坐标不再是挂画（被挖掉/被炸掉/换方块）→ 立刻停。
     */
    public static void tick() {
        if (current == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        SoundHandler handler = mc.getSoundHandler();
        if (handler == null || mc.world == null) {
            stop();
            return;
        }
        if (!handler.isSoundPlaying(current)) {
            // 自然结束，仅清引用，不再调 stopSound（声音已经没了）
            current = null;
            at = null;
            return;
        }
        if (at != null && !(mc.world.getBlockState(at).getBlock() instanceof BlockHuangPoster)) {
            RsAudio.log("[纪念挂画] 挂画已不在 %s，停止音乐", at);
            stop();
        }
    }
}

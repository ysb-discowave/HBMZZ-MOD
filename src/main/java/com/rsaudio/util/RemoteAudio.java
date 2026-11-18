package com.rsaudio.util;

import com.rsaudio.RsAudio;
import com.rsaudio.client.AudioLibrary;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketPlaySound;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 服务端发起、按<b>方块位置</b>在客户端播放 config/rsaudio/audio 里的音频。
 *
 * <h3>为什么需要这么一个类</h3>
 * 模组里已经有两条放音频的路子：音频播放器方块（玩家手动选曲）和 TTS 语音。
 * 但 QSERF 移植进来之后多了一种需求：<b>事件驱动的、写死在代码里的音频映射</b> ——
 * "熔毁响起 P1 融毁音乐"、"越线播报超过安全阈值"、"停机成功放停机成功"。
 * 这类映射有两个容易做错的点，都收在这个类里：
 * <ol>
 *   <li><b>按原始文件名解析</b>。见 {@link AudioLibrary#soundNameOf}：
 *       全中文名会被规范化成不可预测的 {@code track_xxx}，代码里绝不该写死那种名字。</li>
 *   <li><b>同一个位置只能有一条声音</b>。客户端 {@code ClientSoundPlayer} 是按
 *       {@code BlockPos} 索引的，同一格上新播一条会把上一条顶掉。
 *       所以这里用 {@link #slotPos} 给"音乐 / 语音 / 音效"三个用途各分配一个
 *       相差 1 格的虚拟位置 —— 于是熔毁音乐和 Code Black 语音能同时响。</li>
 * </ol>
 *
 * <h3>为什么放在 util 而不是 dmr</h3>
 * {@code com.rsaudio.dmr} 是<b>零 Minecraft 依赖</b>的纯内核，那个包一旦 import 了
 * {@code World}，{@code tools/check_dmr_core.py} 那条纯 javac 自检链路就断了。
 * 所以"事件键 → 音频"这一层必须住在外面。
 */
public final class RemoteAudio {

    /** 音乐（熔毁 / 启动 / 停机成功）—— 占用的虚拟位置槽 */
    public static final int SLOT_MUSIC = 0;
    /** 语音播报（FAAS 广播、警报词）—— 与音乐可以同时响 */
    public static final int SLOT_VOICE = 1;
    /** 音效（爆炸、气闸、机械声） */
    public static final int SLOT_SFX = 2;

    private RemoteAudio() {
    }

    /**
     * 给某个用途分配一个"虚拟位置"。
     * <p>
     * 客户端按 BlockPos 索引正在播放的声音，所以想让两条音频共存就必须让它们的
     * 位置不同。这里用"向上偏 n 格"来实现：差 1~2 格对线性衰减的音量影响可以忽略，
     * 但足以让客户端把它们当成两条独立的声音。
     */
    public static BlockPos slotPos(BlockPos origin, int slot) {
        if (origin == null) {
            return BlockPos.ORIGIN;
        }
        return origin.up(slot <= 0 ? 0 : slot);
    }

    /**
     * 把一条"候选链"拆成候选数组。
     * <p>
     * ★ 为什么要候选链：音频目录是<b>玩家的东西</b>，缺文件是常态。表里写死一个名字时，
     * 一旦那个文件不在，这件事就<b>整条哑掉</b>（只留一行日志，玩家在游戏里毫无察觉）。
     * 写成 {@code "首选名|备选名|兜底名"} 之后，首选缺了就退到备选 —— 至少还有声音，
     * 而且"现在正在用备选"这件事能被 {@code tools/check_audio_refs.py} 列出来，
     * 于是"该补哪个文件"变成了可见的待办，而不是一个静默的空洞。
     * <p>
     * 名字里本身可以带 {@code |} 的几率极低，且音频文件名里 {@code |} 在 Windows 上非法，
     * 所以拿它当分隔符是安全的。
     */
    public static String[] splitChain(String spec) {
        if (spec == null || spec.isEmpty()) {
            return new String[0];
        }
        String[] parts = spec.split("\\|");
        java.util.List<String> out = new java.util.ArrayList<String>(parts.length);
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.toArray(new String[out.size()]);
    }

    /** {@link #splitChain} + {@link #resolve}：把一条候选链解析成资源名 */
    public static String resolveChain(String spec) {
        return resolve(splitChain(spec));
    }

    /**
     * 按<b>原始文件名</b>解析出资源名。名字记不准时退化成模糊查找。
     *
     * @param candidates 依次尝试的原始文件名（不含后缀也行）
     * @return 解析到的 soundName；一个都找不到返回 null
     */
    public static String resolve(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String name : candidates) {
            String hit = AudioLibrary.soundNameOf(name);
            if (hit != null) {
                return hit;
            }
        }
        // 精确匹配全落空，再试一遍"包含"——但这只对唯一性有保证的片段安全，
        // 所以放在后面，且只用作兜底
        for (String name : candidates) {
            String hit = AudioLibrary.soundNameContaining(name);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /**
     * 在某个位置放一条音频（带距离衰减）。适合"机器在响"这种感觉的声音。
     *
     * @param range       发给多远以内的玩家（格）
     * @param displayName 原始文件名；可用 {@code "首选|备选"} 写候选链
     */
    public static void playAt(World world, BlockPos origin, double range, int slot,
                              String displayName, boolean loop) {
        if (world == null || world.isRemote || origin == null) {
            return;
        }
        String sound = resolveChain(displayName);
        if (sound == null) {
            RsAudio.warn("音频目录里找不到 %s 中的任何一个，这条声音不会响"
                    + "（把对应的 ogg 放进 config/rsaudio/audio 即可）", displayName);
            return;
        }
        BlockPos pos = slotPos(origin, slot);
        send(world, origin, range, pos, sound, false, loop);
    }

    /**
     * 全设施广播：<b>把声音放在每个玩家自己的位置上</b>，于是没有距离衰减、
     * 人人都是满音量。这就是"PA 广播"和"机器在响"的区别 ——
     * Code Black 那种撤离命令必须让跑在走廊尽头的人也听得见。
     */
    public static void announce(World world, BlockPos origin, double range, int slot,
                                String displayName) {
        if (world == null || world.isRemote || origin == null) {
            return;
        }
        String sound = resolveChain(displayName);
        if (sound == null) {
            RsAudio.warn("音频目录里找不到 %s 中的任何一个，这条广播不会响", displayName);
            return;
        }
        double limit = range * range;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.isDead) {
                continue;
            }
            if (player.getDistanceSq(origin.getX() + 0.5D, origin.getY() + 0.5D,
                    origin.getZ() + 0.5D) > limit) {
                continue;
            }
            // 用玩家脚下那一格当位置：他和声源零距离 → 满音量、无方向感，正是广播要的
            BlockPos self = new BlockPos(player.posX, player.posY, player.posZ);
            self = self.up(slot <= 0 ? 0 : slot);
            try {
                PacketHandler.INSTANCE.sendTo(
                        new PacketPlaySound(self, sound, false, false), (EntityPlayerMP) player);
            } catch (Throwable t) {
                // 单个玩家掉线不该影响其他人
            }
        }
    }

    /** 停掉某个用途的音频（例如熔毁窗口结束时把音乐掐掉） */
    public static void stop(World world, BlockPos origin, double range, int slot) {
        if (world == null || world.isRemote || origin == null) {
            return;
        }
        BlockPos pos = slotPos(origin, slot);
        send(world, origin, range, pos, "", true, false);
    }

    private static void send(World world, BlockPos origin, double range, BlockPos pos,
                             String sound, boolean stop, boolean loop) {
        double limit = range * range;
        double x = origin.getX() + 0.5D;
        double y = origin.getY() + 0.5D;
        double z = origin.getZ() + 0.5D;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.isDead) {
                continue;
            }
            if (player.getDistanceSq(x, y, z) > limit) {
                continue;
            }
            try {
                PacketHandler.INSTANCE.sendTo(
                        new PacketPlaySound(pos, sound, stop, loop), (EntityPlayerMP) player);
            } catch (Throwable t) {
                // 同上
            }
        }
    }
}

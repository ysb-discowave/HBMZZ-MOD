package com.rsaudio.util;

import com.rsaudio.Config;
import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketTtsBegin;
import com.rsaudio.network.PacketTtsChunk;
import com.rsaudio.network.PacketTtsEnd;
import com.rsaudio.tts.TtsCache;
import com.rsaudio.tts.TtsEngine;
import com.rsaudio.tts.TtsSpec;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 服务端语音播报（TTS）管理器。
 * <p>
 * 职责链：<b>合成 → 缓存 → 分片下发</b>。整条链路都在服务端做，
 * 好处是<b>玩家什么都不用装</b> —— 只有开服的人需要装一次语音引擎。
 * 代价是服务器要有 CPU 余量，所以合成放在单线程后台队列里，绝不占服务端线程。
 *
 * <h3>为什么是"服务端合成 + 把音频发下去"</h3>
 * 另一种做法是只广播文本、让每个客户端自己合成。那样服务器很轻松，
 * 但每个玩家都得自己装引擎、装模型，而且同一句话在不同机器上音色还不一样。
 * 播报是"所有人听到同一个声音"才有意义的东西，所以选服务端合成。
 *
 * <h3>线程</h3>
 * <ul>
 *   <li>{@link #speak} 可以在任意线程调用，立刻返回；</li>
 *   <li>合成在单线程 {@code worker} 上跑（{@code TtsEngine.synthesize} 会阻塞几秒）；</li>
 *   <li>发包必须回到服务端线程，用 {@code MinecraftServer.addScheduledTask} 跳回去。</li>
 * </ul>
 *
 * <h3>为什么目标是 UUID 列表而不是玩家对象</h3>
 * 合成要花几百毫秒到几秒，这期间玩家可能已经掉线、换维度，甚至整个世界都卸载了。
 * 所以进队列时只记 UUID，发包时再回服务端线程按 UUID 重新查一次玩家 ——
 * 查不到就跳过，不会出现"对着一个已经失效的连接发数据"的崩溃。
 */
public final class TtsServer {

    /** 每个流一个自增 id，客户端靠它把分片认到同一次播报上 */
    private static final AtomicInteger NEXT_STREAM = new AtomicInteger(1);

    private static File ttsDir;
    private static TtsCache cache;
    private static TtsEngine.Status status;
    private static ExecutorService worker;

    /** 日志噪声控制：引擎没装/合成失败只吵一次，不然每次广播都刷屏 */
    private static boolean warnedNotReady;
    private static boolean warnedSynthFailed;

    private TtsServer() {
    }

    // ------------------------------------------------------------------ 生命周期

    public static synchronized void init() {
        ttsDir = Config.getTtsDir();
        cache = TtsCache.standard(ttsDir);
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "rsaudio-tts");
                    // 守护线程 + 低优先级：合成慢一点没关系，不能拖累服务端 tick
                    t.setDaemon(true);
                    t.setPriority(Thread.MIN_PRIORITY + 1);
                    return t;
                }
            });
        }
        refresh();
    }

    /** 重新探测引擎（装完引擎不用重启游戏就能生效） */
    public static synchronized void refresh() {
        if (ttsDir == null) {
            ttsDir = Config.getTtsDir();
        }
        status = TtsEngine.discover(ttsDir);
        if (status.available) {
            RsAudio.log("[TTS] 语音已就绪 —— %s", status.message);
        } else {
            RsAudio.log("[TTS] 语音未启用：%s", status.message);
        }
    }

    public static synchronized void shutdown() {
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    // ------------------------------------------------------------------ 状态

    public static synchronized boolean isReady() {
        return status != null && status.available;
    }

    public static synchronized String statusMessage() {
        return status == null ? "(TTS 尚未初始化)" : status.message;
    }

    public static synchronized String cacheSummary() {
        if (cache == null) {
            return "";
        }
        return cache.count() + " 条 / " + (cache.totalBytes() / 1024L) + " KB";
    }

    // ------------------------------------------------------------------ 播报

    /**
     * 把一段文字合成成语音并发给指定玩家。
     * <p>
     * 文本为空、没有目标、引擎没装都直接返回 false，<b>不抛异常也不排队</b> ——
     * 调用方（广播器）靠这个返回值决定要不要提示玩家"只弹窗没声音"。
     *
     * @return 是否已经排进合成队列
     */
    public static boolean speak(String text, double lengthScale, int speakerId,
                               List<EntityPlayerMP> targets) {
        final String clean = TtsSpec.sanitizeTts(text);
        if (clean.isEmpty() || targets == null || targets.isEmpty()) {
            return false;
        }
        synchronized (TtsServer.class) {
            if (worker == null) {
                ttsDir = Config.getTtsDir();
                cache = TtsCache.standard(ttsDir);
                worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "rsaudio-tts");
                        t.setDaemon(true);
                        t.setPriority(Thread.MIN_PRIORITY + 1);
                        return t;
                    }
                });
                refresh();
            }
            if (!status.available) {
                if (!warnedNotReady) {
                    warnedNotReady = true;
                    RsAudio.warn("[TTS] 收到播报请求但引擎没装好，只弹窗不发声。%s", status.message);
                }
                return false;
            }
        }

        final double scale = TtsSpec.clampLengthScale(lengthScale);
        final String voiceTag;
        synchronized (TtsServer.class) {
            if (status == null || status.model == null) {
                return false;
            }
            // ★ 指纹在锁里算：refresh() 随时可能把 status 换成"不可用"（model 为 null），
            //   在外面读 status.model.getName() 会撞出一个偶发的 NPE
            voiceTag = TtsCache.voiceTag(status.model.getName(), scale, speakerId);
        }
        final String key = TtsCache.keyFor(clean, voiceTag);

        final List<UUID> ids = new ArrayList<UUID>(targets.size());
        for (EntityPlayerMP p : targets) {
            if (p != null && p.getUniqueID() != null) {
                ids.add(p.getUniqueID());
            }
        }
        if (ids.isEmpty()) {
            return false;
        }

        try {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    byte[] wav = loadOrSynth(key, clean, scale, speakerId);
                    if (wav == null) {
                        return;
                    }
                    deliver(wav, ids);
                }
            });
        } catch (RejectedExecutionException ree) {
            // 服务器正在关服、线程池已经被 shutdown 了：这次播报丢了就算了
            RsAudio.warn("[TTS] 语音队列已关闭，本次播报被丢弃");
            return false;
        }
        return true;
    }

    /**
     * 只是把这段文字<b>提前合成好塞进缓存</b>，不发任何东西。
     * <p>
     * 为什么需要它：合成有十几秒的固定开销（起进程 + 载入 60MB 模型），
     * 如果等到"红石触发那一刻"才开始合成，玩家会先看到弹窗、十几秒之后才听到声音。
     * 所以在<b>设置被保存的时候</b>就先合成一遍 —— 之后无论是界面点「广播」
     * 还是红石触发，都能直接命中缓存、几乎是即时的。
     * <p>
     * 调用方是服务端线程，所以这里只做"入队"，缓存检查也放在 worker 里做，
     * 避免在主线程上摸硬盘。
     */
    public static void warm(String text, double lengthScale, int speakerId) {
        final String clean = TtsSpec.sanitizeTts(text);
        if (clean.isEmpty()) {
            return;
        }
        final double scale = TtsSpec.clampLengthScale(lengthScale);
        final String key;
        final ExecutorService pool;
        synchronized (TtsServer.class) {
            if (worker == null || status == null || !status.available || status.model == null) {
                return;
            }
            key = TtsCache.keyFor(clean,
                    TtsCache.voiceTag(status.model.getName(), scale, speakerId));
            pool = worker;
        }
        try {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (cache.get(key) != null) {
                        return;      // 已经热了
                    }
                    loadOrSynth(key, clean, scale, speakerId);
                }
            });
        } catch (RejectedExecutionException ignored) {
            // 正在关服，预热丢掉不影响正确性
        }
    }

    /**
     * 先查缓存，未命中才真的跑一遍引擎，跑完把结果写进缓存。
     *
     * @return WAV 字节；超限/失败返回 null
     */
    private static byte[] loadOrSynth(String key, String text, double scale, int speakerId) {
        File hit = cache.get(key);
        if (hit != null) {
            try {
                byte[] data = TtsEngine.readCapped(hit, TtsSpec.MAX_WAV_BYTES);
                if (data != null) {
                    return data;
                }
            } catch (IOException ignored) {
                // 读坏了（磁盘满、被别的进程占着）就当没命中，重新合成一遍
            }
        }

        // worker 是单线程的，所以一个固定的临时文件名就够，不会互相踩
        File tmp = new File(ttsDir, "synth.tmp.wav");
        try {
            if (!TtsEngine.synthesize(status, text, tmp, scale, speakerId)) {
                if (!warnedSynthFailed) {
                    warnedSynthFailed = true;
                    RsAudio.warn("[TTS] 合成失败（可能是模型不匹配或引擎启动异常）。"
                            + "界面上点「本机试听」可以看到更直接的反馈。");
                }
                return null;
            }
            byte[] data = TtsEngine.readCapped(tmp, TtsSpec.MAX_WAV_BYTES);
            if (data == null) {
                RsAudio.warn("[TTS] 合成结果超过 %d 字节上限，已丢弃（把文本缩短一点）",
                        TtsSpec.MAX_WAV_BYTES);
                return null;
            }
            cache.put(key, data);
            return data;
        } catch (IOException ioe) {
            RsAudio.warn("[TTS] 写缓存失败: %s", ioe.toString());
            return null;
        } finally {
            if (tmp.exists() && !tmp.delete()) {
                RsAudio.warn("[TTS] 临时文件删不掉: %s", tmp.getAbsolutePath());
            }
        }
    }

    /** 回到服务端线程，按 16KB 一片发下去 */
    private static void deliver(final byte[] wav, final List<UUID> ids) {
        final MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                List<EntityPlayerMP> players = new ArrayList<EntityPlayerMP>(ids.size());
                for (UUID id : ids) {
                    EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(id);
                    if (p != null) {
                        players.add(p);
                    }
                }
                if (players.isEmpty()) {
                    return;
                }

                final int totalBytes = wav.length;
                final int chunks = TtsSpec.chunkCount(totalBytes);
                final int streamId = NEXT_STREAM.getAndIncrement();

                // 先切好再发：切片只做一次，每个玩家各自 new 一个包实例。
                // ★ 不给多个玩家复用同一个 IMessage 实例：SimpleNetworkWrapper 会把它
                //   编码进一个共享的 Packet 对象，多个连接同时持有同一个 Packet
                //   在 Netty 异步写出时是没必要的风险。
                byte[][] slices = new byte[chunks][];
                for (int i = 0; i < chunks; i++) {
                    int off = TtsSpec.chunkOffset(i);
                    int len = TtsSpec.chunkLength(totalBytes, i);
                    byte[] slice = new byte[len];
                    System.arraycopy(wav, off, slice, 0, len);
                    slices[i] = slice;
                }

                for (EntityPlayerMP p : players) {
                    try {
                        PacketHandler.INSTANCE.sendTo(
                                new PacketTtsBegin(streamId, totalBytes, chunks), p);
                        for (int i = 0; i < chunks; i++) {
                            PacketHandler.INSTANCE.sendTo(
                                    new PacketTtsChunk(streamId, i, slices[i]), p);
                        }
                        PacketHandler.INSTANCE.sendTo(new PacketTtsEnd(streamId), p);
                    } catch (Throwable t) {
                        // 一个玩家在网络抖动中掉线，不该连累其他玩家听不到
                        RsAudio.warn("[TTS] 音频分片发给 %s 失败: %s", p.getName(), t.toString());
                    }
                }
                RsAudio.log("[TTS] 已下发 %d 字节语音（%d 片）给 %d 人", totalBytes, chunks, players.size());
            }
        });
    }
}

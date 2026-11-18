package com.rsaudio.client;

import com.rsaudio.Config;
import com.rsaudio.RsAudio;
import com.rsaudio.tts.AudioReassembler;
import com.rsaudio.tts.TtsEngine;
import com.rsaudio.tts.TtsSpec;

import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.text.TextComponentTranslation;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 客户端语音播放：接收分片 → 拼回 WAV → 出声。
 *
 * <h3>为什么不用原版声音系统</h3>
 * 原版 {@code SoundManager} 只吃资源包里的 {@code .ogg}，要让一段"临时生成"的音频
 * 走原版流程，得先往动态资源包里写文件再 {@code refreshResources()} ——
 * 而 {@code refreshResources()} 会重载全部贴图和模型，每次播报卡一次，
 * 完全不能接受。所以直接用 {@code javax.sound.sampled} 开一条独立音轨，
 * 和游戏音频互不干扰（音量上单独乘一次"主音量"滑块）。
 *
 * <h3>线程</h3>
 * 网络包到达在客户端主线程，但播放要阻塞到一句念完（可能十几秒），
 * 所以：主线程只负责"收包 → 拼装 → 塞进队列"，一个后台播放线程负责出声。
 * 队列只留最近两条 —— 播报的意义在于"当前这条"，积压一堆一起放没有价值。
 */
public final class TtsClientPlayer {

    private static final int MAX_QUEUED = 2;

    private static final AudioReassembler REASSEMBLER = new AudioReassembler();
    private static final ArrayDeque<byte[]> QUEUE = new ArrayDeque<byte[]>();
    private static final Object LOCK = new Object();

    private static Thread playThread;
    private static ExecutorService previewWorker;

    /** 当前正在写的音轨（停止时要用它打断 write 阻塞） */
    private static volatile SourceDataLine current;
    private static volatile boolean abort;

    private TtsClientPlayer() {
    }

    // ------------------------------------------------------------------ 收包

    public static void onBegin(int streamId, int totalBytes, int chunkCount) {
        REASSEMBLER.begin(streamId, totalBytes, chunkCount, System.currentTimeMillis());
    }

    public static void onChunk(int streamId, int index, byte[] data) {
        REASSEMBLER.offer(streamId, index, data);
    }

    public static void onEnd(int streamId) {
        byte[] wav = REASSEMBLER.take(streamId);
        if (wav == null) {
            // 没凑齐（丢包、被并发上限挤掉）就丢掉，不留着等到超时
            REASSEMBLER.abort(streamId);
            return;
        }
        enqueue(wav);
    }

    /** 每 tick 清扫超时的半截音频 */
    public static void tick() {
        REASSEMBLER.expire(System.currentTimeMillis(), 0L);
    }

    // ------------------------------------------------------------------ 本机试听

    /**
     * 用<b>本机</b>装的引擎合成并播放，用来验证"我这台机器的引擎装好了没有"。
     * <p>
     * 和广播走的不是同一条链路：广播用服务端的引擎、发给所有人；
     * 试听用本机的引擎、只给自己听。单人游戏里两者是同一台机器，效果一样。
     */
    public static void previewLocal(final String text, final double lengthScale) {
        final String clean = TtsSpec.sanitizeTts(text);
        if (clean.isEmpty()) {
            report("chat.rsaudio.announcer.preview_empty");
            return;
        }
        if (previewWorker == null) {
            previewWorker = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "rsaudio-tts-preview");
                    t.setDaemon(true);
                    return t;
                }
            });
        }
        report("chat.rsaudio.announcer.preview_start");
        previewWorker.execute(new Runnable() {
            @Override
            public void run() {
                TtsEngine.Status status = TtsEngine.discover(Config.getTtsDir());
                if (!status.available) {
                    report("chat.rsaudio.announcer.preview_no_engine", status.message);
                    return;
                }
                File out = new File(Config.getTtsDir(), "preview.tmp.wav");
                try {
                    if (!TtsEngine.synthesize(status, clean, out,
                            TtsSpec.clampLengthScale(lengthScale), -1)) {
                        report("chat.rsaudio.announcer.preview_failed");
                        return;
                    }
                    byte[] data = TtsEngine.readCapped(out, TtsSpec.MAX_WAV_BYTES);
                    if (data == null) {
                        report("chat.rsaudio.announcer.preview_failed");
                        return;
                    }
                    enqueue(data);
                } catch (IOException ioe) {
                    RsAudio.warn("[TTS] 试听读文件失败: %s", ioe.toString());
                    report("chat.rsaudio.announcer.preview_failed");
                } finally {
                    if (out.exists() && !out.delete()) {
                        RsAudio.warn("[TTS] 试听临时文件删不掉: %s", out.getAbsolutePath());
                    }
                }
            }
        });
    }

    public static void shutdown() {
        if (previewWorker != null) {
            previewWorker.shutdownNow();
            previewWorker = null;
        }
        stopAll();
    }

    // ------------------------------------------------------------------ 播放

    /** 世界卸载 / 掉线时清干净，免得回到主界面还在念 */
    public static void stopAll() {
        abort = true;
        SourceDataLine line = current;
        if (line != null) {
            try {
                line.stop();
                line.flush();
            } catch (Throwable ignored) {
                // 已经关掉或者音频设备被拔了，不值得报错
            }
        }
        synchronized (LOCK) {
            QUEUE.clear();
            LOCK.notifyAll();
        }
        REASSEMBLER.clear();
    }

    private static void enqueue(byte[] wav) {
        if (wav == null || wav.length <= 44) {
            return;
        }
        ensurePlayThread();
        synchronized (LOCK) {
            while (QUEUE.size() >= MAX_QUEUED) {
                QUEUE.pollFirst();
            }
            QUEUE.addLast(wav);
            LOCK.notifyAll();
        }
    }

    private static void ensurePlayThread() {
        if (playThread != null && playThread.isAlive()) {
            return;
        }
        playThread = new Thread(new Runnable() {
            @Override
            public void run() {
                playLoop();
            }
        }, "rsaudio-tts-play");
        playThread.setDaemon(true);
        playThread.start();
    }

    private static void playLoop() {
        while (true) {
            byte[] wav;
            synchronized (LOCK) {
                while (QUEUE.isEmpty()) {
                    try {
                        LOCK.wait();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                wav = QUEUE.pollFirst();
            }
            playOnce(wav);
        }
    }

    private static void playOnce(byte[] wav) {
        abort = false;
        AudioInputStream in = null;
        SourceDataLine line = null;
        try {
            in = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wav));
            AudioFormat format = in.getFormat();
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                RsAudio.warn("[TTS] 本机声卡不支持这个格式，无法播放: %s", format);
                return;
            }
            line = (SourceDataLine) AudioSystem.getLine(info);
            // 缓冲区取 1/4 秒：太小会爆音，太大按停止之后还会多念一段
            int bufferBytes = Math.max(4096, (int) (format.getSampleRate() / 4.0D)
                    * Math.max(1, format.getFrameSize()));
            line.open(format, bufferBytes);
            current = line;
            applyMasterVolume(line);
            line.start();

            byte[] buffer = new byte[4096];
            int read;
            while (!abort && (read = in.read(buffer)) > 0) {
                line.write(buffer, 0, read);
            }
            if (!abort) {
                // drain 会把缓冲区里剩下的放完再返回；被打断时跳过，做到"立刻静音"
                line.drain();
            }
            line.stop();
        } catch (Throwable t) {
            // 音频设备被占用、被拔掉、格式诡异……都不该让游戏崩
            RsAudio.warn("[TTS] 播放失败: %s", t.toString());
        } finally {
            current = null;
            closeQuietly(line);
            closeQuietly(in);
        }
    }

    /**
     * 把"主音量"滑块乘进来。
     * <p>
     * 用 {@code MASTER_GAIN}（对数增益，单位 dB）做一次乘法：
     * 音量百分比 v → {@code 20*log10(v)} dB。
     * 主音量为 0 时对数会跑到负无穷，所以夹到控件允许的最小值。
     * 控件可能不存在（某些系统/虚拟声卡没有主增益），整段都包在 try 里。
     */
    private static void applyMasterVolume(SourceDataLine line) {
        try {
            float volume = Minecraft.getMinecraft().gameSettings.getSoundLevel(SoundCategory.MASTER);
            if (volume <= 0.0001F) {
                return;
            }
            FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
            float db = (float) (20.0D * Math.log10(volume));
            if (db < gain.getMinimum()) {
                db = gain.getMinimum();
            }
            if (db > gain.getMaximum()) {
                db = gain.getMaximum();
            }
            gain.setValue(db);
        } catch (Throwable ignored) {
            // 没有主增益控件就按设备默认音量放
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // 关不掉就随它去，进程结束时会回收
        }
    }

    /** 把一条提示发到玩家聊天栏。可能在后台线程被调用，所以排队到主线程再发 */
    private static void report(final String key, final Object... args) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (Minecraft.getMinecraft().player != null) {
                    Minecraft.getMinecraft().player.sendMessage(
                            new TextComponentTranslation(key, args));
                }
            }
        });
    }
}

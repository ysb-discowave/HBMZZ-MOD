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

package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.audio.ReasimDsp;
import com.rsaudio.audio.ReasimParams;
import net.minecraft.util.math.BlockPos;

import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 一路 reasim 播放会话：解码 ogg → 跑 DSP → 把 PCM 喂进 Minecraft 的声音引擎。
 * <p>
 * <b>为什么要自己推流，而不是像原来那样丢一个 {@code ISound} 进去：</b>
 * 原版那条路只给了"音量"和"音高"两个旋钮，音量是线性缩放、音高是重采样。
 * 失真需要波形整形、沙沙声需要加噪声、回声需要一个带反馈的延迟线 ——
 * 这三件事都必须拿到采样才行，所以只能绕开 {@code ISound}，
 * 用 paulscode 的原始数据流接口自己喂。
 * <p>
 * <b>线程：</b>解码 + DSP + 喂数据全在一条后台线程上（{@link #pump}），
 * 因为它们是阻塞的、而且要按实时节奏走。所有来自游戏线程的东西
 * （参数、自动回声、停止标志）都是 {@code volatile} 字段，只管读，不管写。
 * 这条线程<b>绝不</b>碰 Minecraft 的世界或渲染状态。
 * <p>
 * <b>节奏：</b>按"从开始播到现在应该喂了多少采样"来推，而不是"睡固定时间喂一块"。
 * 后者在卡顿时会累积误差，一两分钟后就变成断断续续；前者自带纠偏：
 * 掉帧了就一次多喂几块追回来。
 */
public final class ReasimSession {

    /** 目标缓冲：200ms。太小容易被 GC 打断成杂音，太大操作延迟明显 */
    private static final int TARGET_BUFFER_MS = 200;
    /** 一次喂多少：20ms 一块，约 882 帧 */
    private static final int CHUNK_MS = 20;
    /** 连续多少次拿不到声音引擎就放弃（约 2 秒），通常是玩家关了声音 */
    private static final int MAX_MISSES = 400;

    private static final List<ReasimSession> SESSIONS = new ArrayList<ReasimSession>();
    private static Thread pumpThread;
    private static boolean loggedPumpFailure;

    private final BlockPos pos;
    private final String soundName;
    private final boolean loop;
    private final String sourceName;
    /** 界面里的试听：没有对应的方块，只挂在玩家自己身上，{@link #find} 不会命中它 */
    private final boolean preview;

    private volatile ReasimParams params = new ReasimParams();
    private volatile float autoEcho = 0.30F;
    private volatile boolean stopped;
    private volatile boolean finished;

    // ---- 以下只在推流线程上读写 ----
    private Object system;
    private Object codec;
    private ReasimDsp dsp;
    private AudioFormat format;
    private boolean bigEndian;
    private long startNanos;
    private long fedFrames;
    private boolean eof;
    private long eofNanos;
    private int misses;
    private ReasimParams applied;
    private float appliedEcho = -1.0F;
    private byte[] silence;

    private ReasimSession(BlockPos pos, String soundName, boolean loop, boolean preview) {
        this.pos = pos.toImmutable();
        this.soundName = soundName;
        this.loop = loop;
        this.preview = preview;
        this.sourceName = "rsaudio_reasim_" + (preview ? "preview_" : "")
                + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    /**
     * 开一路会话。返回 null 表示开不起来（引擎不可用 / 文件不存在），
     * 调用方应当退回普通播放路径，而不是静默不放。
     */
    public static ReasimSession start(BlockPos pos, String soundName, boolean loop, ReasimParams params) {
        return start(pos, soundName, loop, params, false);
    }

    /**
     * 界面里的试听：位置取玩家脚下，不循环，不带方块。
     * 没有它的话，玩家拖动滑条时听到的是<b>上一首歌</b>的效果，调参全靠猜。
     */
    public static ReasimSession startPreview(BlockPos pos, String soundName, ReasimParams params) {
        return start(pos, soundName, false, params, true);
    }

    private static ReasimSession start(BlockPos pos, String soundName, boolean loop,
                                       ReasimParams params, boolean preview) {
        if (!ReasimBridge.available() || soundName == null || soundName.isEmpty() || pos == null) {
            return null;
        }
        File file = AudioLibrary.fileOf(soundName);
        if (file == null || !file.isFile()) {
            return null;
        }
        ReasimSession session = new ReasimSession(pos, soundName, loop, preview);
        session.params = params == null ? new ReasimParams() : params.copy();
        synchronized (SESSIONS) {
            SESSIONS.add(session);
        }
        ensurePump();
        return session;
    }

    /** 按坐标找正在跑的会话（试听会话不参与，它们没有方块） */
    public static ReasimSession find(BlockPos pos) {
        synchronized (SESSIONS) {
            for (ReasimSession session : SESSIONS) {
                if (!session.preview && session.pos.equals(pos)) {
                    return session;
                }
            }
        }
        return null;
    }

    public static boolean hasAny() {
        synchronized (SESSIONS) {
            return !SESSIONS.isEmpty();
        }
    }

    /** 客户端 tick：参数变了不必重开，直接在下一块数据上生效 */
    public void update(ReasimParams params, float autoEcho) {
        if (params != null) {
            this.params = params.copy();
        }
        this.autoEcho = autoEcho;
    }

    public BlockPos getPos() {
        return pos;
    }

    public boolean isFinished() {
        return finished;
    }

    public boolean isStopped() {
        return stopped;
    }

    public void stop() {
        this.stopped = true;
    }

    /** 取走"已结束"这个事实，避免同一次结束被上报两遍 */
    public boolean consumeFinished() {
        if (finished) {
            finished = false;
            stopped = true;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 推流

    private static void ensurePump() {
        synchronized (ReasimSession.class) {
            if (pumpThread != null) {
                return;
            }
            pumpThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    pumpLoop();
                }
            }, "rsaudio-reasim");
            pumpThread.setDaemon(true);
            pumpThread.start();
        }
    }

    private static void pumpLoop() {
        while (true) {
            try {
                ReasimSession[] snapshot;
                synchronized (SESSIONS) {
                    snapshot = SESSIONS.toArray(new ReasimSession[0]);
                }
                if (snapshot.length == 0) {
                    Thread.sleep(200L);
                    continue;
                }
                long now = System.nanoTime();
                for (ReasimSession session : snapshot) {
                    try {
                        session.pump(now);
                    } catch (Throwable t) {
                        session.abort("推流异常: " + t);
                    }
                }
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                return;
            } catch (Throwable t) {
                if (!loggedPumpFailure) {
                    loggedPumpFailure = true;
                    RsAudio.warn("reasim 推流线程出错，本次会话停用 reasim: %s", t);
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }
    }

    private void pump(long now) {
        if (stopped) {
            release();
            return;
        }
        if (finished) {
            return;
        }
        ReasimParams snapshot = this.params;
        if (snapshot == null) {
            snapshot = new ReasimParams();
        }

        Object current = ReasimBridge.currentSystem();
        if (current == null) {
            if (++misses > MAX_MISSES) {
                abort("声音引擎一直拿不到");
            }
            return;
        }
        misses = 0;
        if (current != system) {
            // 玩家改资源包 / F3+T 会让 SoundManager 重建 SoundSystem，
            // 旧音源随之作废。这里重建，并把计时器拉回"刚开播"。
            if (system != null) {
                ReasimBridge.closeSource(system, sourceName);
            }
            system = current;
            if (!openSource()) {
                abort("建不出喂数据的音源");
                return;
            }
        }

        applyParams(snapshot);
        feed(now);
    }

    private boolean openSource() {
        if (codec == null && !openCodec()) {
            return false;
        }
        if (format == null) {
            format = ReasimBridge.codecFormat(codec);
        }
        if (format == null) {
            return false;
        }
        int rate = Math.round(format.getSampleRate());
        if (dsp == null) {
            dsp = new ReasimDsp(rate, format.getChannels());
        }
        if (silence == null) {
            int chunkFrames = Math.max(64, rate * CHUNK_MS / 1000);
            silence = new byte[chunkFrames * format.getFrameSize()];
        }
        float x = pos.getX() + 0.5F;
        float y = pos.getY() + 0.5F;
        float z = pos.getZ() + 0.5F;
        if (!ReasimBridge.openRawSource(system, sourceName, format, x, y, z, 64.0F)) {
            return false;
        }
        startNanos = System.nanoTime();
        fedFrames = 0L;
        eof = false;
        eofNanos = 0L;
        return true;
    }

    private void applyParams(ReasimParams snapshot) {
        if (snapshot == null) {
            return;
        }
        float echo = snapshot.echoAuto ? autoEcho : snapshot.echo;
        boolean sameParams = applied != null && applied.sameAs(snapshot);
        if (sameParams && Math.abs(echo - appliedEcho) < 0.005F) {
            return;
        }
        applied = snapshot.copy();
        appliedEcho = echo;
        if (dsp != null) {
            dsp.configure(snapshot, echo);
        }
    }

    private void feed(long now) {
        if (format == null || dsp == null) {
            return;
        }
        int rate = Math.round(format.getSampleRate());
        int chunkFrames = Math.max(64, rate * CHUNK_MS / 1000);
        long target = (now - startNanos) / 1000000L * rate / 1000L
                + rate * TARGET_BUFFER_MS / 1000L;

        if (eof) {
            // 循环模式：立刻重开解码器，不能等尾巴（否则每圈之间会空一拍）
            if (loop) {
                restart();
                return;
            }
            // 单次播放：继续喂静音，让回声尾巴自然衰减完再收工。
            // 直接停掉的话会很突兀 —— 像有人在你刚说完话时拔了插头。
            long tailMs = 250L + Math.round(600.0F * appliedEcho);
            if ((now - eofNanos) / 1000000L < tailMs) {
                while (fedFrames < target) {
                    if (!feedChunk(silence, silence.length, chunkFrames)) {
                        return;
                    }
                }
            } else {
                finished = true;
            }
            return;
        }

        while (fedFrames < target) {
            byte[] chunk = ReasimBridge.readChunk(codec);
            if (chunk == null || chunk.length == 0) {
                if (ReasimBridge.codecEnded(codec)) {
                    eof = true;
                    eofNanos = now;
                    return;
                }
                // codec 偶尔会在非结尾处返回空块，直接重试下一次推流
                return;
            }
            if (!feedChunk(chunk, chunk.length, chunkFrames)) {
                return;
            }
        }
    }

    private boolean feedChunk(byte[] data, int length, int chunkFrames) {
        // DSP 内部一律按小端处理。大端的音频要先换成小端，跑完再换回去 ——
        // 喂给引擎的字节必须是<b>我们声明的那个格式</b>，只换一次会放出尖锐的噪声。
        if (bigEndian) {
            swapBytes(data, length);
        }
        dsp.process(data, 0, length);
        if (bigEndian) {
            swapBytes(data, length);
        }
        if (!ReasimBridge.feedRaw(system, sourceName, data)) {
            abort("喂数据失败");
            return false;
        }
        fedFrames += length / Math.max(1, format.getFrameSize());
        return true;
    }

    private void restart() {
        ReasimBridge.closeCodec(codec);
        codec = null;
        if (!openCodec()) {
            abort("循环重开解码器失败");
            return;
        }
        // 解码器换了，格式理论上一样；不一样就重建 DSP，避免采样率错配
        AudioFormat reopened = ReasimBridge.codecFormat(codec);
        if (reopened != null && (format.getSampleRate() != reopened.getSampleRate()
                || format.getChannels() != reopened.getChannels())) {
            format = reopened;
            dsp = new ReasimDsp(Math.round(reopened.getSampleRate()), reopened.getChannels());
            silence = null;
        }
        startNanos = System.nanoTime();
        fedFrames = 0L;
        eof = false;
        eofNanos = 0L;
    }

    private boolean openCodec() {
        File file = AudioLibrary.fileOf(soundName);
        codec = ReasimBridge.openCodec(file);
        if (codec == null) {
            return false;
        }
        AudioFormat codecAudioFormat = ReasimBridge.codecFormat(codec);
        if (codecAudioFormat == null) {
            ReasimBridge.closeCodec(codec);
            codec = null;
            return false;
        }
        format = codecAudioFormat;
        bigEndian = codecAudioFormat.isBigEndian();
        return true;
    }

    private void abort(String why) {
        this.stopped = true;
        // 中止也算"结束了"：让客户端照常回报 PacketSoundFinished，
        // 否则脉冲模式的播放器会一直以为自己还在放，再也接不了下一首。
        this.finished = true;
        RsAudio.warn("reasim 会话中止（%s）：%s", pos, why);
    }

    private void release() {
        if (system != null) {
            ReasimBridge.closeSource(system, sourceName);
            system = null;
        }
        if (codec != null) {
            ReasimBridge.closeCodec(codec);
            codec = null;
        }
        dsp = null;
        synchronized (SESSIONS) {
            SESSIONS.remove(this);
        }
    }

    public static void stopAll() {
        synchronized (SESSIONS) {
            for (ReasimSession session : SESSIONS) {
                session.stopped = true;
            }
        }
    }

    private static void swapBytes(byte[] data, int length) {
        for (int i = 0; i + 1 < length; i += 2) {
            byte tmp = data[i];
            data[i] = data[i + 1];
            data[i + 1] = tmp;
        }
    }
}

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;

import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通往 Minecraft 内部声音引擎的唯一一道门。
 * <p>
 * <b>为什么这里全是反射，而不是直接 import：</b>
 * <ul>
 *   <li>{@code paulscode.*} 是 Minecraft 自带的第三方库，ForgeGradle 2.3 的
 *       {@code compileClasspath} 里<b>没有</b>它们（只有 deobf 过的 MC 本体），
 *       直接 import 编不过。它们在运行时一定在（MC 自己要用），所以用反射。</li>
 *   <li>{@code SoundHandler.sndManager} 和 {@code SoundManager.sndSystem} 是私有字段。
 *       官方映射名（{@code sndManager}）只在开发环境有效，正式包里的字段是 SRG 名
 *       （{@code field_147694_f} / {@code field_148620_e}），所以两个名字都要试。</li>
 * </ul>
 * <b>为什么值得冒这个险：</b>普通的 {@code ISound} 只能给出音量和音高，
 * 音量是线性缩放、音高是重采样，做不出失真和噪声 —— 那些必须拿到 PCM 才谈得上。
 * paulscode 恰好有一个"喂原始 PCM"的接口（{@code rawDataStream} +
 * {@code feedRawAudioData}），有了它，整条 DSP 链就都成立。
 * <p>
 * <b>失败必须是温和的：</b>{@link #available()} 一旦为 false，上层退回普通播放路径
 * （只是没有那些效果），而不是让音乐彻底不响。任何一步解析失败只记一次日志。
 */
public final class ReasimBridge {

    /** 线性衰减：到 {@code rolloff} 格处音量归零 */
    public static final int ATTENUATION_LINEAR = 1;

    private static boolean probed;
    private static boolean usable;
    private static String failure = "";

    private static Field fieldSndManager;
    private static Field fieldSndSystem;

    private static Method methodRawDataStream;
    private static Method methodFeedRaw;
    private static Method methodPlay;
    private static Method methodStop;
    private static Method methodRemoveSource;
    private static Method methodSetPosition;
    private static Method methodSetVolume;
    private static Method methodSetAttenuation;

    private static Class<?> codecClass;
    private static Method codecInitialize;
    private static Method codecRead;
    private static Method codecEndOfStream;
    private static Method codecCleanup;
    private static Method codecGetFormat;
    private static Field soundBufferAudioData;

    private ReasimBridge() {
    }

    /** 引擎是否可用。第一次调用时才解析，之后走缓存 */
    public static synchronized boolean available() {
        if (!probed) {
            probe();
        }
        return usable;
    }

    public static String failureReason() {
        return failure;
    }

    private static void probe() {
        probed = true;
        try {
            Class<?> soundHandlerClass = SoundHandler.class;
            fieldSndManager = findField(soundHandlerClass, "SoundManager", "field_147694_f", "sndManager");
            Class<?> soundManagerClass = fieldSndManager.getType();
            fieldSndSystem = findField(soundManagerClass, "SoundSystem", "field_148620_e", "sndSystem");

            Class<?> systemClass = Class.forName("paulscode.sound.SoundSystem");
            methodRawDataStream = systemClass.getMethod("rawDataStream",
                    AudioFormat.class, boolean.class, String.class,
                    float.class, float.class, float.class, int.class, float.class);
            methodFeedRaw = systemClass.getMethod("feedRawAudioData", String.class, byte[].class);
            methodPlay = systemClass.getMethod("play", String.class);
            methodStop = systemClass.getMethod("stop", String.class);
            methodRemoveSource = systemClass.getMethod("removeSource", String.class);
            methodSetPosition = systemClass.getMethod("setPosition", String.class,
                    float.class, float.class, float.class);
            methodSetVolume = systemClass.getMethod("setVolume", String.class, float.class);
            methodSetAttenuation = systemClass.getMethod("setAttenuation", String.class, int.class);

            codecClass = Class.forName("paulscode.sound.codecs.CodecJOrbis");
            codecInitialize = codecClass.getMethod("initialize", java.net.URL.class);
            codecRead = codecClass.getMethod("read");
            codecEndOfStream = codecClass.getMethod("endOfStream");
            codecCleanup = codecClass.getMethod("cleanup");
            codecGetFormat = codecClass.getMethod("getAudioFormat");

            Class<?> bufferClass = Class.forName("paulscode.sound.SoundBuffer");
            soundBufferAudioData = bufferClass.getField("audioData");

            usable = true;
        } catch (Throwable t) {
            usable = false;
            failure = t.getClass().getSimpleName() + ": " + t.getMessage();
            RsAudio.warn("reasim 拿不到声音引擎，将退回普通播放（只有音量，没有失真与噪声）：%s", failure);
        }
    }

    /**
     * 取当前的 {@code SoundSystem} 实例。
     * <p>
     * <b>绝不能缓存这个对象</b>：玩家改资源包 / 按 F3+T 都会走
     * {@code SoundManager.reloadSoundSystem()}，那会造一个全新的实例，
     * 旧实例上建的音源全部作废。所以每次现取，由调用方比对身份，变了就重建音源。
     */
    public static Object currentSystem() {
        if (!available()) {
            return null;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            SoundHandler handler = mc == null ? null : mc.getSoundHandler();
            if (handler == null) {
                return null;
            }
            Object manager = fieldSndManager.get(handler);
            if (manager == null) {
                return null;
            }
            return fieldSndSystem.get(manager);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 音源

    public static boolean openRawSource(Object system, String name, AudioFormat format,
                                        float x, float y, float z, float rolloff) {
        try {
            methodRawDataStream.invoke(system, format, Boolean.FALSE, name, x, y, z,
                    ATTENUATION_LINEAR, rolloff);
            methodSetVolume.invoke(system, name, 1.0F);
            methodPlay.invoke(system, name);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean feedRaw(Object system, String name, byte[] data) {
        try {
            methodFeedRaw.invoke(system, name, data);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void moveTo(Object system, String name, float x, float y, float z) {
        try {
            methodSetPosition.invoke(system, name, x, y, z);
        } catch (Throwable ignored) {
            // 位置更新失败不影响出声，只是会留在旧位置
        }
    }

    /** 停止并释放音源。两步都要：stop 不释放通道，removeSource 不停止播放 */
    public static void closeSource(Object system, String name) {
        if (system == null) {
            return;
        }
        try {
            methodStop.invoke(system, name);
        } catch (Throwable ignored) {
            // 已经没了就算了
        }
        try {
            methodRemoveSource.invoke(system, name);
        } catch (Throwable ignored) {
            // 同上
        }
    }

    // ------------------------------------------------------------------ 解码

    /** 打开一个 ogg 解码器；失败返回 null（文件坏了 / 不是 ogg） */
    public static Object openCodec(File file) {
        if (!available() || file == null || !file.isFile()) {
            return null;
        }
        try {
            Object codec = codecClass.newInstance();
            Boolean ok = (Boolean) codecInitialize.invoke(codec, file.toURI().toURL());
            if (ok == null || !ok.booleanValue()) {
                closeCodec(codec);
                return null;
            }
            return codec;
        } catch (Throwable t) {
            return null;
        }
    }

    public static AudioFormat codecFormat(Object codec) {
        try {
            return (AudioFormat) codecGetFormat.invoke(codec);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 读下一块 PCM；返回 null 表示读到结尾或者坏了 */
    public static byte[] readChunk(Object codec) {
        try {
            Object buffer = codecRead.invoke(codec);
            if (buffer == null) {
                return null;
            }
            return (byte[]) soundBufferAudioData.get(buffer);
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean codecEnded(Object codec) {
        try {
            Boolean eof = (Boolean) codecEndOfStream.invoke(codec);
            return eof != null && eof.booleanValue();
        } catch (Throwable t) {
            return true;
        }
    }

    public static void closeCodec(Object codec) {
        if (codec == null) {
            return;
        }
        try {
            codecCleanup.invoke(codec);
        } catch (Throwable ignored) {
            // 解码器自己会随 GC 回收，这里只是尽量早放掉文件句柄
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 按若干候选名找字段；全都找不到时退化为"按类型名模糊匹配"。
     * 最后这一层不是为了好看：Forge 每次大版本都会重排 SRG 编号，
     * 而字段类型（{@code SoundManager} / {@code SoundSystemStarterThread}）是不会变的。
     *
     * @param typeHint 类型名里必须出现的片段，用于兜底匹配
     */
    private static Field findField(Class<?> owner, String typeHint, String... names)
            throws NoSuchFieldException {
        for (String name : names) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 换下一个候选名
            }
        }
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType().getName().contains(typeHint)) {
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(owner.getName() + " 里找不到 "
                + java.util.Arrays.toString(names) + "（兜底也没找到类型含 " + typeHint + " 的字段）");
    }
}

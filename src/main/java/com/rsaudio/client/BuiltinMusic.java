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

import com.rsaudio.Config;
import com.rsaudio.RsAudio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 内置曲目：把 jar 里带的三首 OST 铺进 {@code config/rsaudio/audio}。
 *
 * <p><b>为什么不直接写在 jar 的 sounds.json 里。</b> 红石音频的音乐库不是一张静态清单，
 * 而是一个目录：{@link AudioLibrary} 扫 {@code config/rsaudio/audio/*.ogg}，
 * {@link DynamicSoundPack} 据此生成资源包和 sounds.json，播放器界面再把这批条目列出来。
 * 想"装完就能选、能放"，唯一不另开一条播放路径的办法就是让这三首出现在那个目录里——
 * 于是这里做的事只是"首次启动时把 jar 里的 ogg 复制过去"，之后完全不参与播放，
 * 玩家换文件、删曲目、加自己的曲子，行为与手放进去的音轨一模一样。
 *
 * <p><b>为什么不是每次启动都补齐。</b> 玩家很可能不想在列表里看到这三首（那个界面没有
 * "隐藏"功能，只有删）。所以补装只做一次，靠 {@code config/rsaudio/builtin-music.txt}
 * 里的版本号记住；删掉的文件不会被下一次启动又塞回来。真要重装，改 {@link #SEED_VERSION}
 * 或删掉那个标记文件即可。
 *
 * <p><b>为什么不打进 sounds/ 让引擎直接播。</b> 那要另外维护一套"内置条目"——时长读取、
 * 资源包优先级、删不掉的列表项——而现有链路已经把这些都解决了。这里只做搬运。
 */
public final class BuiltinMusic {

    /**
     * 内置曲目的版本。**改动曲目、改名、重新编码之后必须 +1**，否则已经装过一次的玩家
     * 不会拿到新的文件（标记里的版本不比这个数小就直接返回）。
     */
    private static final int SEED_VERSION = 1;

    /** 标记文件，放在 config/rsaudio 根下，不放进 audio：那里只应有音频 */
    private static final String MARKER = "builtin-music.txt";

    /**
     * 曲目表：{@code {jar 内路径, 落地文件名}}。
     *
     * <p>落地名是玩家在播放器界面里看到的名字，也是 {@link AudioLibrary#sanitize} 的输入：
     * 统一用 {@code HBMZZ OST NN 曲名} 开头，字母序自然把三首排在一起，不会被别人的
     * 曲子插在中间。jar 内路径保持纯 ASCII，出问题时不至于在 zip 里找错大小写。
     */
    private static final String[][] TRACKS = {
            {"assets/hbmzz/sounds/ost/how-to-survive.ogg", "HBMZZ OST 01 How to survive.ogg"},
            {"assets/hbmzz/sounds/ost/when-will-it-end.ogg", "HBMZZ OST 02 When will it end.ogg"},
            {"assets/hbmzz/sounds/ost/the-ending-of-melting.ogg", "HBMZZ OST 03 The ending of melting.ogg"},
    };

    private BuiltinMusic() {
    }

    /**
     * 首次启动时安装内置曲目；已经装过就只读一个几行的文本文件。
     *
     * <p>必须在 {@link AudioLibrary#rescan()} <b>之前</b>调用，否则第一帧的扫描看不到它们，
     * 要等玩家按"重新扫描"才出现。
     */
    public static void ensureInstalled() {
        File marker = new File(Config.getConfigDir(), MARKER);
        if (readSeedVersion(marker) >= SEED_VERSION) {
            return;
        }

        File audioDir = Config.getAudioDir();
        if (!audioDir.isDirectory()) {
            RsAudio.warn("内置曲目没有安装：音频目录不可用 (%s)", audioDir.getAbsolutePath());
            return;
        }

        int installed = 0;
        int kept = 0;
        int broken = 0;
        for (String[] track : TRACKS) {
            String resource = track[0];
            String fileName = track[1];
            File target = new File(audioDir, fileName);
            if (target.isFile() && target.length() > 0) {
                kept++;                      // 已经在了（可能是玩家自己放的同名文件）
                continue;
            }
            InputStream in = BuiltinMusic.class.getResourceAsStream("/" + resource);
            if (in == null) {
                // 打包漏了资源。这条日志就是唯一线索——播放器界面只会少一项，不会报错。
                RsAudio.warn("内置曲目缺失：jar 里没有 %s", resource);
                broken++;
                continue;
            }
            try {
                writeAtomic(in, target);
                installed++;
            } catch (Throwable t) {
                RsAudio.warn("内置曲目安装失败 %s: %s", fileName, t);
                broken++;
            } finally {
                closeQuietly(in);
            }
        }

        writeMarker(marker);
        if (installed > 0) {
            RsAudio.log("已安装 %d 首内置 OST 到 %s（可在播放器里直接选）",
                    installed, audioDir.getAbsolutePath());
        }
        if (broken > 0) {
            RsAudio.warn("有 %d 首内置 OST 没装成，播放器列表里会缺这几项", broken);
        }
    }

    /** 标记里记的版本；没有标记、读不出来、格式不对一律当 0（= 没装过） */
    private static int readSeedVersion(File marker) {
        if (!marker.isFile()) {
            return 0;
        }
        try {
            String text = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                line = line.trim();
                if (line.startsWith("seed=")) {
                    return Integer.parseInt(line.substring(5).trim());
                }
            }
        } catch (Throwable ignored) {
            // 文件坏了就当没装过，重装一次即可——比"永远不装"好
        }
        return 0;
    }

    private static void writeMarker(File marker) {
        try {
            String text = "# 红石音频内置曲目的安装记录。\r\n"
                    + "# 删掉本文件或把 seed 改成比模组内建版本小的数，下次启动会重新安装缺失的曲目。\r\n"
                    + "seed=" + SEED_VERSION + "\r\n"
                    + "tracks=" + TRACKS.length + "\r\n";
            Files.write(marker.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            RsAudio.warn("写入内置曲目记录失败（下次启动会重装一次）: %s", t);
        }
    }

    /**
     * 先写到 {@code .part} 再改名。
     *
     * <p>直接往目标文件写，一旦中途失败（磁盘满、被占用），目录里会留下一个<b>半截 ogg</b>：
     * 扫描照收、资源包照生成，然后在播放到一半时突然静音，没有任何报错。改名是原子的，
     * 最坏情况只是留下一个 {@code .part}——它的后缀不在播放列表里，也不会被当成损坏音频。
     */
    private static void writeAtomic(InputStream in, File target) throws IOException {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        OutputStream out = new FileOutputStream(part);
        try {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        } finally {
            closeQuietly(out);
        }
        if (!part.renameTo(target)) {
            // 某些文件系统上目标已存在时 renameTo 会失败：先删再试一次
            Files.deleteIfExists(target.toPath());
            if (!part.renameTo(target)) {
                Files.deleteIfExists(part.toPath());
                throw new IOException("无法改名到 " + target.getAbsolutePath());
            }
        }
    }

    private static void closeQuietly(java.io.Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
            // 关不上就算了，文件已经写完
        }
    }
}

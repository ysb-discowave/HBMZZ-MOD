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

package com.hbmzz.video;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The video shelf: {@code <config>/hbmzz/videos/}.
 *
 * <p>Everything a broadcaster can play is a {@code .zip} file or a directory in
 * that one folder. Two things matter here beyond listing files.</p>
 *
 * <p><b>Names are a whitelist, not a path.</b> The name travels in a packet:
 * server &rarr; client, telling the client which file to open. If it were pasted
 * into a {@code File} unchecked, a hostile server could hand a client
 * {@code ../../options.txt} and read it out of the world. {@link #isValidName}
 * is therefore checked on <em>both</em> ends - once when the operator saves, and
 * again here, where the file is actually opened. No separators, no dots beyond
 * the single separator before the extension, no traversal.</p>
 *
 * <p><b>The folder is self-documenting.</b> The first run drops a README.txt
 * next to the videos, because "put a zip here" is not something a player should
 * have to learn from a wiki.</p>
 */
public final class VideoLibrary {

    /** Legal clip name: starts alphanumeric, then letters, digits, {@code _ . -}. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}");

    private static final String README = String.join("\n",
            "HBMZZ 视频广播器 — 视频存放目录",
            "",
            "把视频做成下面两种形状之一放进这个文件夹，游戏里就能在「视频广播器」中选择它：",
            "",
            "  1) <名字>.zip",
            "       meta.json     {\"fps\":12,\"frames\":240,\"title\":\"显示名\",\"audio\":\"audio.wav\"}",
            "       f000001.jpg   帧，按文件名排序播放，编号要用固定位数补零",
            "       f000002.jpg",
            "       audio.wav     可选，44.1k / 16bit 的 WAV",
            "",
            "  2) <名字>/        同样的内容，只是不打包（适合手工做几帧的短片）",
            "",
            "meta.json 可以省略：缺省 12 fps，帧数按目录里实际有多少张图算。",
            "支持 png / jpg。分辨率超过 config 里的 videoMaxWidth×videoMaxHeight 会被等比缩小。",
            "",
            "用 ffmpeg 一条命令就能打包：",
            "  python tools/video/pack.py 输入.mp4 报警音效",
            "",
            "注意：视频文件要在每一台需要观看的客户端上各放一份，服务端只负责触发。",
            "");

    private static File root;
    private static List<String> cache = Collections.emptyList();
    private static long scannedAt;

    private VideoLibrary() {
    }

    /** Called from preInit on both sides; {@code configDir} is the game's config folder. */
    public static void init(File configDir) {
        if (configDir == null) {
            return;
        }
        root = new File(new File(configDir, "hbmzz"), "videos");
        if (!root.isDirectory() && !root.mkdirs()) {
            org.apache.logging.log4j.LogManager.getLogger("hbmzz-video")
                    .warn("could not create {}", root);
            return;
        }
        File readme = new File(root, "README.txt");
        if (!readme.isFile()) {
            try (OutputStream out = Files.newOutputStream(readme.toPath())) {
                out.write(README.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
    }

    public static File root() {
        return root;
    }

    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) {
            return false;
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\")) {
            return false;
        }
        return NAME.matcher(name).matches();
    }

    /** Clip names, sorted. Rescanned at most once a second. */
    public static List<String> names() {
        if (root == null) {
            return Collections.emptyList();
        }
        long now = System.currentTimeMillis();
        if (now - scannedAt < 1000L) {
            return cache;
        }
        scannedAt = now;
        List<String> found = new ArrayList<>();
        File[] files = root.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = null;
                if (f.isDirectory()) {
                    name = f.getName();
                } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    name = f.getName().substring(0, f.getName().length() - 4);
                }
                if (name != null && isValidName(name) && !found.contains(name)) {
                    found.add(name);
                }
            }
        }
        Collections.sort(found);
        cache = found;
        return found;
    }

    public static boolean exists(String name) {
        if (root == null || !isValidName(name)) {
            return false;
        }
        return new File(root, name + ".zip").isFile() || new File(root, name).isDirectory();
    }

    /** Opens a clip for playback, or null when it is missing or unreadable. */
    public static VideoClip open(String name) {
        if (root == null || !isValidName(name)) {
            return null;
        }
        File zip = new File(root, name + ".zip");
        if (zip.isFile()) {
            return VideoClip.open(name, zip);
        }
        File dir = new File(root, name);
        if (dir.isDirectory()) {
            return VideoClip.open(name, dir);
        }
        return null;
    }

    /** Where a clip would live, for error messages. */
    public static String describe(String name) {
        if (root == null) {
            return name;
        }
        if (!isValidName(name)) {
            return name;
        }
        File zip = new File(root, name + ".zip");
        if (zip.isFile()) {
            return zip.getPath();
        }
        return new File(root, name).getPath();
    }
}

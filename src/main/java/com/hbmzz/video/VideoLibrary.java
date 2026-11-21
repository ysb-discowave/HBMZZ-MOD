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

    /** 名字的长度上限。报文里是一个 UTF-8 字符串，太长没必要。 */
    private static final int MAX_NAME = 64;

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
            "直接丢进来的 .mp4 / .mkv / .mov / .avi / .webm / .flv / .ts / .wmv / .rmvb 等",
            "不会直接出现在列表里 —— 播放端读的是图片帧序列（png / jpg），不是视频容器。",
            "把文件丢进这个目录，然后在游戏里蹲下右键播放器 →「导入视频」→ 转换，",
            "会就地拆帧并打包成一个 <名字>.zip，之后按 F7 就能选到。转换用 ffmpeg，",
            "首次启动会自动下载一份；也可以继续用命令行：",
            "  python tools/video/pack.py 输入.mp4 报警",
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
    /**
     * Files that are in the folder and are not playable.
     *
     * <p>Kept because ignoring them silently is what makes "刷新" look broken:
     * a dropped {@code .mp4} never shows up, so the player presses refresh
     * again, sees the same two clips and concludes the button does nothing. The
     * list exists to be said out loud, not to be played.</p>
     */
    private static List<String> skipped = Collections.emptyList();
    /**
     * 目录里是视频容器、等着导入的文件。
     *
     * <p>和 {@link #skipped} 分开：后者是"这个文件永远播不了"，前者是"这个文件
     * 现在播不了，但转一下就能播"。两者混在一起会让玩家以为 mp4 是坏文件，
     * 而实际上它只是还没被拆成帧。</p>
     */
    private static List<File> importable = Collections.emptyList();
    private static long scannedAt;

    private VideoLibrary() {
    }

    /** Called from preInit on both sides; {@code configDir} is the game's config folder. */
    public static void init(File configDir) {
        if (configDir == null) {
            return;
        }
        root = new File(new File(configDir, "hbmzz"), "videos");
        if (!ensureRoot()) {
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

    /**
     * Makes sure {@link #root} is a usable directory, creating it on demand.
     *
     * <p>preInit creates the folder, but a player can delete it while the game
     * is running, and a mod loaded before us may ask for the shelf earlier than
     * expected. Recreating here means every caller of {@link #root()} gets a
     * real directory instead of a path that silently lists no clips.</p>
     *
     * @return true once the directory exists
     */
    private static boolean ensureRoot() {
        if (root == null) {
            return false;
        }
        if (root.isDirectory()) {
            return true;
        }
        if (root.mkdirs() || root.isDirectory()) {
            return true;
        }
        org.apache.logging.log4j.LogManager.getLogger("hbmzz-video")
                .warn("could not create {}", root);
        return false;
    }

    /** The shelf directory, created on demand so callers never see a missing folder. */
    public static File root() {
        if (root != null) {
            ensureRoot();
        }
        return root;
    }

    /**
     * Forgets the one-second listing cache.
     *
     * <p>Called by the refresh key ({@code F7}) and by the window's 刷新 button:
     * dropping a new zip into the folder while the game is running should show
     * up on the next click, not on the next restart. One second is a long time
     * when the player is staring at the list.</p>
     */
    public static void rescan() {
        synchronized (VideoLibrary.class) {
            cache = Collections.emptyList();
            skipped = Collections.emptyList();
            importable = Collections.emptyList();
            scannedAt = 0L;
        }
    }

    /**
     * Opens {@code config/hbmzz/videos/} in the operating system's file browser.
     *
     * <p>Deliberately best-effort: {@link java.awt.Desktop} is unavailable on a
     * headless JVM and silently unsupported on some Linux desktops, and a video
     * folder that refuses to open must not throw inside a GUI click. The caller
     * prints the path either way, so the player always knows where to go.</p>
     *
     * @return true when a file browser was asked to open the folder
     */
    public static boolean openFolder() {
        if (root == null) {
            return false;
        }
        if (!root.isDirectory() && !root.mkdirs()) {
            return false;
        }
        try {
            if (!java.awt.Desktop.isDesktopSupported()) {
                return false;
            }
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop == null || !desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                return false;
            }
            desktop.open(root);
            return true;
        } catch (Throwable t) {
            org.apache.logging.log4j.LogManager.getLogger("hbmzz-video")
                    .warn("could not open {}: {}", root, t.toString());
            return false;
        }
    }

    /** Absolute path of the folder, for the "here is where it lives" message. */
    public static String folderPath() {
        return root == null ? "" : root.getAbsolutePath();
    }

    /**
     * 这个名字能不能当包名。
     *
     * <p><b>白名单的意思是"只能是这些字符"，不是"只能是 ASCII"。</b>名字会跟着
     * 报文走到客户端，在那里被拼成 {@code <名字>.zip}，所以分隔符、冒号、控制
     * 字符一律不许出现 —— 没有 {@code /} 就没有目录穿越。除此之外放行任何语言
     * 的字母与数字：一个中文片名转出来的包叫 {@code 测试片段} 比叫
     * {@code video} 有用得多，而文件名本来就该是它自己的名字。</p>
     */
    public static boolean isValidName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) {
            return false;
        }
        char first = name.charAt(0);
        if (!Character.isLetterOrDigit(first)) {
            return false;
        }
        if (name.charAt(name.length() - 1) == '.') {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                continue;
            }
            return false;
        }
        return true;
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
        List<String> ignored = new ArrayList<>();
        List<File> videos = new ArrayList<>();
        File[] files = root.listFiles();
        if (files != null) {
            for (File f : files) {
                String name = null;
                if (f.isDirectory()) {
                    name = f.getName();
                } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    name = f.getName().substring(0, f.getName().length() - 4);
                } else if (worthReporting(f)) {
                    if (VideoFormats.isImportable(f)) {
                        videos.add(f);
                    } else {
                        ignored.add(f.getName());
                    }
                }
                if (name != null && isValidName(name) && !found.contains(name)) {
                    found.add(name);
                }
            }
        }
        Collections.sort(found);
        java.util.Collections.sort(ignored);
        videos.sort(java.util.Comparator.comparing(File::getName));
        cache = found;
        skipped = ignored;
        importable = videos;
        return found;
    }

    /**
     * Regular, visible, non-README files that are not a playable clip.
     *
     * <p>README.txt is ours and a dotfile is the operating system's; neither is
     * worth a line of complaint. Everything else is a file the player put there
     * expecting it to play, so it gets named.</p>
     */
    private static boolean worthReporting(File f) {
        if (!f.isFile()) {
            return false;
        }
        String name = f.getName();
        if (name.startsWith(".") || name.equalsIgnoreCase("README.txt")) {
            return false;
        }
        return true;
    }

    /** Names of files in the folder that are not playable clips, sorted. */
    public static List<String> skipped() {
        // names() is what fills this list; calling it keeps the two in step
        // without a second scan on every frame.
        names();
        return skipped;
    }

    /**
     * 视频目录里等着导入的容器文件（mp4 / mkv / mov / …），按文件名排序。
     *
     * <p>只列文件，不递归、也不列 .zip —— 已经能播的东西不该出现在"待导入"
     * 里。真正的转换在 {@link VideoImportQueue}。</p>
     */
    public static List<File> importable() {
        names();
        return importable;
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

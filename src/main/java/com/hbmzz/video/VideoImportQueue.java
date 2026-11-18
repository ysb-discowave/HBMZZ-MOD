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

import com.rsaudio.formats.convert.FfmpegFinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 把任意视频容器转成广播器能播的帧包 —— 一条后台队列，一次一集。
 *
 * <p>播放端只有一个解码器：{@code ImageIO}。所以"支持市面上所有格式"落在
 * 这里 —— 用 ffmpeg 把 mp4 / mkv / mov / avi / webm / flv / ts / wmv / rmvb
 * 之类全部拆成图片序列，再按 {@link VideoClip} 认识的形状打包。格式多少取决于
 * 手上那份 ffmpeg 编了什么进去，而不是这个模组编了什么进去；模组自己从来不解
 * 一个像素的 H.264。</p>
 *
 * <p><b>为什么不边播边转。</b>实时解码要在播放线程上做，一帧慢就掉帧，而录像
 * 是要放给一屋子人看的。转一次、放很多次更划算，而且转出来的包和手工做的包
 * 一模一样，F7 之后就在列表里，谁放的、放在哪台机器上都一样。</p>
 *
 * <p><b>线程模型。</b>{@link #submit} 只往队列里塞文件，真正的活在一个守护线程
 * 里按顺序做；界面每帧读 {@link #progress()} 拿快照，从不 join、从不等锁。
 * 转换期间关掉窗口不影响转换，重新打开窗口还能接着看进度。</p>
 */
public final class VideoImportQueue {

    /** 默认帧率：广播器常见的电视感，也是 {@link VideoClip} 的默认值。 */
    public static final int DEFAULT_FPS = 12;
    /** 默认宽度：854 是 480p 级的宽度，一块 3×3 的大屏也够看。 */
    public static final int DEFAULT_WIDTH = 854;

    /**
     * 帧率与宽度的可选档位，界面按一下换一档。
     *
     * <p>上限给到 120：高帧率的片子（60/120fps 的录像、游戏内录屏）以前会被压到 30，
     * 快速横移的镜头一顿一顿。代价是帧数按帧率线性涨——120fps 的一部番是 30fps 的四倍
     * 体积，所以列表里那行"帧率与宽度决定产物体积"不是客套话。</p>
     */
    public static final int[] FPS_STEPS = {6, 8, 10, 12, 15, 24, 30, 48, 60, 90, 120};
    public static final int[] WIDTH_STEPS = {320, 480, 640, 854, 1280};

    private static final Pattern DURATION =
            Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final int IMAGE_QUALITY = 4;

    /** 一次转换任务的快照。字段不可变，界面拿到的永远是一致的一份。 */
    public static final class Progress {
        public final String phase;
        public final int percent;
        public final String detail;
        public final boolean running;

        Progress(String phase, int percent, String detail, boolean running) {
            this.phase = phase;
            this.percent = percent;
            this.detail = detail == null ? "" : detail;
            this.running = running;
        }

        public boolean failed() {
            return "失败".equals(this.phase) || "部分失败".equals(this.phase);
        }
    }

    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static volatile Progress progress = new Progress("空闲", 0, "", false);
    private static volatile boolean cancelRequested;
    private static volatile Process child;
    private static Thread worker;

    private VideoImportQueue() {
    }

    /** 一个待转的文件：源、目标名字、两个画质参数。 */
    public static final class Job {
        public final File source;
        public final String name;
        public final int fps;
        public final int width;

        Job(File source, String name, int fps, int width) {
            this.source = source;
            this.name = name;
            this.fps = fps;
            this.width = width;
        }
    }

    public static Progress progress() {
        return progress;
    }

    public static boolean busy() {
        return progress.running;
    }

    /** 队列长度（不含正在做的那一个）。界面用来说"还有几个"。 */
    public static synchronized int queued() {
        return QUEUE.size();
    }

    /**
     * 排队转换。重复提交同一个文件会被忽略 —— 双击一次按钮不该把它转两遍。
     *
     * @return 真正进了队列的数量
     */
    public static synchronized int submit(List<File> files, int fps, int width) {
        if (files == null || files.isEmpty()) {
            return 0;
        }
        int clampedFps = Math.max(1, Math.min(120, fps));
        int clampedWidth = Math.max(64, Math.min(4096, width));
        int added = 0;
        for (File file : files) {
            if (!VideoFormats.isImportable(file)) {
                continue;
            }
            boolean duplicate = false;
            for (Job queued : QUEUE) {
                if (queued.source.equals(file)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            QUEUE.add(new Job(file, suggestName(file), clampedFps, clampedWidth));
            added++;
        }
        if (added > 0) {
            cancelRequested = false;
            if (!progress.running) {
                progress = new Progress("排队", 0, added + " 个文件", true);
            }
            startWorker();
        }
        return added;
    }

    /** 取消：清空队列，并杀掉正在跑的那个 ffmpeg。已经转好的不会回滚。 */
    public static synchronized void cancel() {
        QUEUE.clear();
        cancelRequested = true;
        Process running = child;
        if (running != null) {
            running.destroy();
        }
        if (progress.running) {
            progress = new Progress("取消中", progress.percent, "正在停下 ffmpeg…", true);
        }
    }

    /** 就地重扫视频目录，让刚转好的包立刻出现在列表里。 */
    public static void refreshLibrary() {
        VideoLibrary.rescan();
    }

    // ------------------------------------------------------------------ worker

    private static synchronized void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                drain();
            }
        }, "hbmzz-video-import");
        worker.setDaemon(true);
        worker.start();
    }

    private static void drain() {
        while (true) {
            Job job;
            synchronized (VideoImportQueue.class) {
                job = QUEUE.poll();
                if (job == null) {
                    // 队列空了只把"还在跑"的状态收掉；刚做完的那条结果要留给
                    // 界面看一眼（完成 / 失败 / 已取消），不能立刻被"空闲"盖掉。
                    if (progress.running) {
                        progress = new Progress("空闲", 0, "", false);
                    }
                    return;
                }
            }
            if (cancelRequested) {
                synchronized (VideoImportQueue.class) {
                    QUEUE.clear();
                    cancelRequested = false;
                }
                progress = new Progress("已取消", 0, "", false);
                return;
            }
            try {
                convert(job);
            } catch (Throwable t) {
                progress = new Progress("失败", 0, job.source.getName() + "：" + shortReason(t), false);
            }
        }
    }

    private static void convert(Job job) throws Exception {
        String ffmpeg = FfmpegFinder.path();
        if (ffmpeg == null) {
            progress = new Progress("失败", 0,
                    "没有可用的 ffmpeg（首次启动会自动下载，也可放到 mods/ 或系统 PATH）", false);
            return;
        }
        File shelf = VideoLibrary.root();
        if (shelf == null) {
            progress = new Progress("失败", 0, "视频目录不可用", false);
            return;
        }

        progress = new Progress("准备", 1, "读取 " + job.source.getName(), true);
        double seconds = durationOf(ffmpeg, job.source);
        int expected = seconds > 0.05 ? Math.max(1, (int) Math.round(seconds * job.fps)) : 0;

        File work = new File(new File(shelf, ".import"), job.name);
        deleteTree(work.toPath());
        if (!work.mkdirs()) {
            progress = new Progress("失败", 0, "无法创建临时目录 " + work.getPath(), false);
            return;
        }

        long started = System.currentTimeMillis();
        boolean ok = runFrames(ffmpeg, job, work, expected);
        if (!ok || cancelRequested) {
            deleteTree(work.toPath());
            progress = new Progress(cancelRequested ? "已取消" : "失败", 0,
                    job.source.getName() + "：ffmpeg 没能解出画面", false);
            return;
        }

        List<File> frames = listFrames(work);
        if (frames.isEmpty()) {
            deleteTree(work.toPath());
            progress = new Progress("失败", 0, job.source.getName() + "：没有解出任何帧", false);
            return;
        }

        progress = new Progress("音轨", 96, frames.size() + " 帧，提取音轨", true);
        File audio = new File(work, "audio.wav");
        if (!runAudio(ffmpeg, job.source, audio)) {
            audio.delete();
        }

        progress = new Progress("打包", 97, "写入 " + job.name + ".zip", true);
        writeMeta(new File(work, "meta.json"), job, frames.size(), audio.isFile());
        File target = uniqueTarget(shelf, job.name);
        zip(work, frames, audio, target);

        deleteTree(work.toPath());
        File importDir = new File(shelf, ".import");
        String[] leftovers = importDir.list();
        if (leftovers != null && leftovers.length == 0) {
            importDir.delete();
        }
        VideoLibrary.rescan();

        long secondsTaken = Math.max(1L, (System.currentTimeMillis() - started) / 1000L);
        progress = new Progress("完成", 100,
                target.getName() + " · " + frames.size() + " 帧 · " + secondsTaken + " 秒", false);
    }

    /**
     * 逐帧解码并写 jpg。
     *
     * <p>ffmpeg 不会告诉我们"到第几帧了"，所以进度靠数临时目录里的文件：
     * 每 400 ms 数一次，除以按时长估出来的总帧数。估不准也没关系——帧数只影响
     * 进度条走得多顺，真正写进 meta.json 的帧数是解完之后数出来的。</p>
     */
    private static boolean runFrames(String ffmpeg, Job job, File work, int expected) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-nostdin");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(job.source.getAbsolutePath());
        cmd.add("-vf");
        cmd.add("fps=" + job.fps + ",scale=" + job.width + ":-2:flags=lanczos");
        cmd.add("-q:v");
        cmd.add(String.valueOf(IMAGE_QUALITY));
        cmd.add(new File(work, "f%06d.jpg").getAbsolutePath());

        Process process = start(cmd);
        child = process;
        try {
            while (!process.waitFor(400L, TimeUnit.MILLISECONDS)) {
                if (cancelRequested) {
                    process.destroy();
                    return false;
                }
                int frames = countFrames(work);
                int percent = expected > 0
                        ? Math.min(95, frames * 95 / expected)
                        : Math.min(95, frames / 10);
                progress = new Progress("解码中", Math.max(2, percent),
                        frames + (expected > 0 ? " / ~" + expected : "") + " 帧", true);
            }
            return process.exitValue() == 0;
        } finally {
            child = null;
            process.destroy();
        }
    }

    /** 音轨单独跑一遍：要的是 PCM WAV，和播放器那边的 {@code VideoAudio} 对得上。 */
    private static boolean runAudio(String ffmpeg, File source, File out) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");
        cmd.add("-nostdin");
        cmd.add("-y");
        cmd.add("-i");
        cmd.add(source.getAbsolutePath());
        cmd.add("-vn");
        cmd.add("-ac");
        cmd.add("2");
        cmd.add("-ar");
        cmd.add("44100");
        cmd.add("-c:a");
        cmd.add("pcm_s16le");
        cmd.add(out.getAbsolutePath());
        try {
            Process process = start(cmd);
            child = process;
            try {
                if (!process.waitFor(120L, TimeUnit.SECONDS)) {
                    process.destroy();
                    return false;
                }
                return process.exitValue() == 0 && out.isFile() && out.length() > 1024L;
            } finally {
                child = null;
                process.destroy();
            }
        } catch (Exception e) {
            return false;
        }
    }

    private static Process start(List<String> cmd) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(cmd);
        builder.redirectErrorStream(true);
        return builder.start();
    }

    /** 时长（秒）。读的是 {@code ffmpeg -i} 打在 stderr 上的那一行，没有就返回 0。 */
    private static double durationOf(String ffmpeg, File source) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-hide_banner");
        cmd.add("-nostdin");
        cmd.add("-i");
        cmd.add(source.getAbsolutePath());
        Process process = null;
        try {
            process = start(cmd);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = DURATION.matcher(line);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1)) * 3600
                                + Integer.parseInt(m.group(2)) * 60
                                + Double.parseDouble(m.group(3));
                    }
                }
            } finally {
                reader.close();
            }
        } catch (Throwable ignored) {
            // 探测失败只是没有进度条，下面的解帧照样跑
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return 0.0;
    }

    // -------------------------------------------------------------- pack & files

    private static int countFrames(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (isFrame(f)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static List<File> listFrames(File dir) {
        List<File> frames = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (isFrame(f)) {
                    frames.add(f);
                }
            }
        }
        frames.sort(Comparator.comparing(File::getName));
        return frames;
    }

    private static boolean isFrame(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        return file.isFile() && (name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".png"));
    }

    private static void writeMeta(File meta, Job job, int frames, boolean hasAudio) throws IOException {
        String json = "{\"fps\":" + job.fps
                + ",\"frames\":" + frames
                + ",\"title\":\"" + escape(job.name) + "\""
                + (hasAudio ? ",\"audio\":\"audio.wav\"" : "")
                + "}\n";
        Files.write(meta.toPath(), json.getBytes(StandardCharsets.UTF_8));
    }

    /** meta.json 里的名字会进客户端，转义一下免得一个引号把 JSON 弄坏。 */
    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * 打包：帧是 jpg，本来就压过了，所以用 STORED；只有 meta.json 和 wav 会
     * 因此略大一点，换的是几乎瞬时的打包。
     */
    private static void zip(File work, List<File> frames, File audio, File target) throws IOException {
        File part = new File(target.getPath() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(part))) {
            zip.setMethod(ZipOutputStream.STORED);
            addStored(zip, new File(work, "meta.json"), "meta.json");
            for (File frame : frames) {
                addStored(zip, frame, frame.getName());
            }
            if (audio.isFile()) {
                addStored(zip, audio, "audio.wav");
            }
        }
        if (target.exists() && !target.delete()) {
            part.delete();
            throw new IOException("无法覆盖 " + target.getName());
        }
        if (!part.renameTo(target)) {
            part.delete();
            throw new IOException("无法写入 " + target.getName());
        }
    }

    private static void addStored(ZipOutputStream zip, File file, String entryName) throws IOException {
        ZipEntry entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(file.length());
        entry.setCompressedSize(file.length());
        entry.setCrc(crcOf(file));
        zip.putNextEntry(entry);
        Files.copy(file.toPath(), zip);
        zip.closeEntry();
    }

    private static long crcOf(File file) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        byte[] buffer = new byte[1 << 16];
        try (java.io.InputStream in = Files.newInputStream(file.toPath())) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                crc.update(buffer, 0, n);
            }
        }
        return crc.getValue();
    }

    /**
     * 目标名字：源文件名里不能进包名的字符换成下划线，撞名就加后缀。
     *
     * <p>包名会跟着报文跑到客户端，所以和 {@link VideoLibrary#isValidName}
     * 同一套规则 —— 这里不"尽量保留原样"，而是先保证合法。</p>
     */
    static String suggestName(File source) {
        String base = source.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < base.length() && safe.length() < 40; i++) {
            char c = base.charAt(i);
            // 字母与数字按语言算：中文片名原样留下，只有分隔符和控制字符会被换掉。
            boolean ok = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
            safe.append(ok ? c : '_');
        }
        String name = safe.toString();
        while (!name.isEmpty() && !Character.isLetterOrDigit(name.charAt(0))) {
            name = name.substring(1);
        }
        while (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.isEmpty()) {
            name = "video";
        }
        return name;
    }

    /** 界面用：这个文件转出来会叫什么（还没考虑撞名，撞名在真正写盘时决定）。 */
    public static String targetName(File source) {
        return suggestName(source);
    }

    private static File uniqueTarget(File shelf, String name) {
        File target = new File(shelf, name + ".zip");
        int suffix = 2;
        while (target.exists()) {
            target = new File(shelf, name + "_" + suffix + ".zip");
            suffix++;
        }
        return target;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 删不掉就留着，下次转换前还会再清一遍
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private static String shortReason(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        return message.length() > 80 ? message.substring(0, 79) + "…" : message;
    }
}

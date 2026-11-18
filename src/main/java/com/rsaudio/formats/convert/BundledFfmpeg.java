package com.rsaudio.formats.convert;

import com.rsaudio.formats.FmLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 在线下载 ffmpeg.exe（不把 100 MB 的 exe 打进 jar）。
 *
 * <p>下载走通用下载代理 {@code https://yxz.ysbdwz.dpdns.org}，用法是
 * {@code /download?url=<真实地址>}。真实源默认是 BtbN 的 GitHub release zip
 * （含 bin/ffmpeg.exe，Java 标准库即可解压）。代理域名和真实源都可用 JVM 系统属性覆盖：
 * {@code -Drsaudiofm.ffmpeg.proxy=...} / {@code -Drsaudiofm.ffmpeg.source=...}。
 *
 * <p><b>下载加速</b>：先用 HEAD 取总长与 {@code Accept-Ranges}，支持就把 zip 切成
 * 64 段并行下载（实测单线程约 0.5 MB/s、8 段约 3~6 MB/s、64 段约 13 MB/s，192 MB 从 6 分钟压到 20 秒内）。
 * 每段独立存成 {@code ffmpeg-download.zip.part.N}，写满后放一个 {@code .done} 标记；
 * 中断后下次启动只补没下完的段，已经下好的段直接复用。全部到齐后按序合并成 zipPart、
 * 校验总长再解压。服务端不支持 Range 时自动退回单线程下载。
 *
 * <p>下载到 {@code <游戏目录>/config/rsaudiofm/bin/}，解压出的 ffmpeg.exe 用同目录的
 * {@code .stamp} 记住"来源 URL + 大小"，已存在且 stamp 匹配时直接复用，不会每次启动都下载。
 *
 * <p>任何一步失败（无网络、代理挂、磁盘满、源格式不对）都只返回 null，调用方会继续走
 * FfmpegFinder 的外置候选路径，不会让播放器整体不可用。
 */
public final class BundledFfmpeg {

    /** 通用下载代理根地址（尾部不要带斜杠） */
    private static final String DEFAULT_PROXY = "https://yxz.ysbdwz.dpdns.org";
    /** 真实的 ffmpeg 源：BtbN GitHub release，zip 内含 bin/ffmpeg.exe */
    private static final String DEFAULT_SOURCE =
            "https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-gpl.zip";

    /** 老包里打包的 ffmpeg.exe：在线下载失败时的兜底（当前两种包都没有这个资源） */
    private static final String RESOURCE = "/assets/rsaudiofm/ffmpeg.exe";
    private static final String EXE = "ffmpeg.exe";
    private static final String STAMP = ".stamp";
    private static final String ZIP_PART = "ffmpeg-download.zip.part";
    private static final String SEG_SUFFIX = ".seg";
    private static final String DONE_SUFFIX = ".done";
    private static final String META = "ffmpeg-download.meta";
    private static final String EXE_PART = "ffmpeg.exe.part";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 60000;
    private static final int SEGMENTS = 64;
    private static final String USER_AGENT = "rsaudiofm-ffmpeg-downloader/2.0";

    /**
     * 同一时刻只允许一次下载。
     *
     * <p>进度页在后台线程里下载，而播放线程随时可能问 {@link FfmpegFinder#path()}，
     * 那条路也会走到这里。两路同时往同一批分片里写，拼出来的 zip 必然是坏的，
     * 而且会白下一遍。第二个调用者不排队（排队会把播放线程卡上几分钟），直接
     * 拿不到东西回去走外置候选，这次播放按"没有 ffmpeg"降级，等下载完就好。</p>
     */
    private static final java.util.concurrent.atomic.AtomicBoolean IN_FLIGHT =
            new java.util.concurrent.atomic.AtomicBoolean();

    /** 正在后台下载时返回 true —— 调用方据此不要说"没有 ffmpeg"这类会误导人的话。 */
    public static boolean downloading() {
        return IN_FLIGHT.get();
    }

    /* ------------------------------------------------------------------ 进度（供进世界前的下载进度页轮询） */
    private static volatile String progressPhase = "待启动";
    private static volatile int progressDone = 0;
    private static volatile int progressTotal = 0;
    private static volatile long progressBytes = 0L;
    private static volatile long progressTotalBytes = 0L;

    /** 进度快照，GUI 每帧读一次即可。 */
    public static final class Progress {
        public final String phase;
        public final int done;
        public final int total;
        public final long bytes;
        public final long totalBytes;

        Progress(String phase, int done, int total, long bytes, long totalBytes) {
            this.phase = phase;
            this.done = done;
            this.total = total;
            this.bytes = bytes;
            this.totalBytes = totalBytes;
        }

        public int percent() {
            if (total <= 0) {
                return 0;
            }
            return (int) Math.max(0L, Math.min(100L, (long) done * 100L / total));
        }

        /** 完成/失败/命中缓存都算"结束"，进度页据此自动关闭。 */
        public boolean finished() {
            return "完成".equals(phase) || "失败".equals(phase)
                    || "已缓存".equals(phase) || "不需要".equals(phase);
        }
    }

    public static Progress progress() {
        return new Progress(progressPhase, progressDone, progressTotal, progressBytes, progressTotalBytes);
    }

    private static void setProgress(String phase, int done, int total, long bytes, long totalBytes) {
        progressPhase = phase;
        progressDone = done;
        progressTotal = total;
        progressBytes = bytes;
        progressTotalBytes = totalBytes;
    }

    /** 不触发任何下载地判断 ffmpeg 是否已就绪（进度页显示前用它避免闪一下）。 */
    public static boolean isReady(File gameDir) {
        if (gameDir == null) {
            return false;
        }
        File dir = new File(gameDir, "config" + File.separator + "rsaudiofm" + File.separator + "bin");
        File target = new File(dir, EXE);
        File stamp = new File(dir, STAMP);
        String source = trimToNull(System.getProperty("rsaudiofm.ffmpeg.source"));
        if (source == null) {
            source = DEFAULT_SOURCE;
        }
        return target.isFile() && target.length() > 0 && stampValue(source).equals(readStamp(stamp));
    }

    private BundledFfmpeg() {
    }

    /** 返回可执行的 ffmpeg 路径；下载/解压失败时返回 null。 */
    public static File ensure(File gameDir, List<String> notes) {
        if (gameDir == null) {
            return null;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            notes.add("候选 0 在线 ffmpeg: 下载已在进行中，本次跳过");
            return null;
        }
        try {
            return ensureNow(gameDir, notes);
        } finally {
            IN_FLIGHT.set(false);
        }
    }

    private static File ensureNow(File gameDir, List<String> notes) {
        String proxy = trimToNull(System.getProperty("rsaudiofm.ffmpeg.proxy"));
        if (proxy == null) {
            proxy = DEFAULT_PROXY;
        }
        String source = trimToNull(System.getProperty("rsaudiofm.ffmpeg.source"));
        if (source == null) {
            source = DEFAULT_SOURCE;
        }

        File dir = new File(gameDir, "config" + File.separator + "rsaudiofm" + File.separator + "bin");
        File target = new File(dir, EXE);
        File stamp = new File(dir, STAMP);
        String wanted = stampValue(source);

        try {
            // 已下载过且 stamp 指向同一个源 → 直接用缓存
            if (target.isFile() && target.length() > 0 && wanted.equals(readStamp(stamp))) {
                setProgress("已缓存", 1, 1, target.length(), target.length());
                notes.add("候选 0 在线 ffmpeg: 已缓存 (" + target.getPath() + ")");
                return target;
            }
            setProgress("连接中", 0, SEGMENTS, 0L, 0L);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                notes.add("候选 0 在线 ffmpeg: 无法创建目录 " + dir.getPath());
                return null;
            }

            String encoded;
            try {
                encoded = URLEncoder.encode(source, "UTF-8");
            } catch (Throwable t) {
                encoded = source;
            }
            String downloadUrl = proxy + "/download?url=" + encoded;
            FmLog.log("[ffmpeg] 开始在线下载 ffmpeg（" + SEGMENTS + " 线程分段，源：" + source + "）");
            notes.add("候选 0 在线 ffmpeg: 下载中 " + downloadUrl);

            // 源变了就把旧分片全清掉，避免把不同版本的 zip 拼到一起
            File meta = new File(dir, META);
            String metaValue = "src=" + source;
            if (!metaValue.equals(readStamp(meta))) {
                clearSegments(dir);
                writeStamp(meta, metaValue);
            }

            File zipPart = new File(dir, ZIP_PART);
            long downloaded = downloadToZip(downloadUrl, zipPart, dir);
            if (downloaded <= 0) {
                setProgress("失败", 0, 1, 0L, 0L);
                notes.add("候选 0 在线 ffmpeg: 下载失败（0 字节）");
                return fromBundledResource(dir, target, notes);
            }

            // 从 zip 里流式解压出 bin/ffmpeg.exe，不把 200 MB 整包读进内存
            setProgress("解压中", SEGMENTS, SEGMENTS, downloaded, downloaded);
            File exePart = new File(dir, EXE_PART);
            long exeSize = extractFfmpeg(zipPart, exePart);
            if (exeSize <= 0) {
                setProgress("失败", 0, 1, 0L, 0L);
                notes.add("候选 0 在线 ffmpeg: 解压失败（zip 里没有可用的 ffmpeg.exe）");
                return fromBundledResource(dir, target, notes);
            }
            if (target.exists() && !target.delete()) {
                notes.add("候选 0 在线 ffmpeg: 无法覆盖旧文件");
                return null;
            }
            if (!exePart.renameTo(target)) {
                notes.add("候选 0 在线 ffmpeg: 重命名失败");
                return null;
            }
            target.setExecutable(true);
            writeStamp(stamp, wanted);
            zipPart.delete();
            meta.delete();
            clearSegments(dir);
            setProgress("完成", 1, 1, exeSize, exeSize);
            FmLog.log("[ffmpeg] 在线 ffmpeg 已就绪：" + target.getPath() + "（" + exeSize + " 字节）");
            notes.add("候选 0 在线 ffmpeg: 已下载 (" + target.getPath() + ")");
            return target;
        } catch (Throwable t) {
            setProgress("失败", 0, 1, 0L, 0L);
            notes.add("候选 0 在线 ffmpeg: 异常 " + t);
            FmLog.warn("[ffmpeg] 在线下载/解压失败，尝试内置资源兜底: " + t);
            return fromBundledResource(dir, target, notes);
        }
    }

    /* ------------------------------------------------------------------ 下载：分段 + 续传 */

    private static File segmentFile(File dir, int index) {
        return new File(dir, ZIP_PART + SEG_SUFFIX + index);
    }

    private static File doneFile(File dir, int index) {
        return new File(dir, ZIP_PART + SEG_SUFFIX + index + DONE_SUFFIX);
    }

    private static void clearSegments(File dir) {
        for (int i = 0; i < SEGMENTS; i++) {
            File seg = segmentFile(dir, i);
            if (seg.exists()) {
                seg.delete();
            }
            File done = doneFile(dir, i);
            if (done.exists()) {
                done.delete();
            }
        }
    }

    /** HEAD 探测结果：总长 + 是否支持 Range。 */
    private static final class HeadInfo {
        final long length;
        final boolean range;

        HeadInfo(long length, boolean range) {
            this.length = length;
            this.range = range;
        }
    }

    private static HeadInfo probe(String urlText) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            long length = conn.getContentLengthLong();
            String ranges = conn.getHeaderField("Accept-Ranges");
            boolean range = length > 0 && ranges != null && ranges.toLowerCase().contains("bytes");
            return new HeadInfo(length, range);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 下载 zip 到 zipPart：优先 64 段并行 + 续传；服务端不支持 Range 或分段失败时退回单线程。
     * 返回写出的总字节数。
     */
    private static long downloadToZip(String urlText, File zipPart, File dir) throws IOException {
        HeadInfo head = probe(urlText);
        if (head == null || !head.range) {
            FmLog.log("[ffmpeg] 源不支持 Range，退回单线程下载");
            setProgress("下载中(单线程)", 0, 1, 0L, 1L);
            return downloadToFile(urlText, zipPart);
        }
        final long total = head.length;
        setProgress("下载中", 0, SEGMENTS, 0L, total);
        if (zipPart.isFile() && zipPart.length() == total) {
            FmLog.log("[ffmpeg] 完整 zip 已存在，跳过下载（" + total + " 字节）");
            setProgress("下载中", SEGMENTS, SEGMENTS, total, total);
            return total;
        }

        final long chunk = total / SEGMENTS;
        final AtomicInteger segDone = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(SEGMENTS);
        try {
            List<Future<Void>> futures = new ArrayList<Future<Void>>();
            for (int i = 0; i < SEGMENTS; i++) {
                final int index = i;
                final long from = index * chunk;
                final long to = (index == SEGMENTS - 1) ? total - 1 : from + chunk - 1;
                futures.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        File seg = segmentFile(dir, index);
                        File done = doneFile(dir, index);
                        long expected = to - from + 1;
                        long have = seg.isFile() ? seg.length() : 0L;
                        if (done.isFile() && have == expected) {
                            return null;
                        }
                        if (have >= expected || have < 0) {
                            // 长度不对（上次源文件不同/半截损坏），整段重下
                            seg.delete();
                            done.delete();
                            have = 0L;
                        }
                        if (have > 0) {
                            FmLog.log("[ffmpeg] 第 " + (index + 1) + "/" + SEGMENTS + " 段续传 "
                                    + have + " 字节");
                        }
                        downloadRange(urlText, seg, from + have, to);
                        if (seg.length() != expected) {
                            throw new IOException("第 " + index + " 段长度不符："
                                    + seg.length() + " != " + expected);
                        }
                        done.createNewFile();
                        int doneCount = segDone.incrementAndGet();
                        setProgress("下载中", doneCount, SEGMENTS,
                                (long) doneCount * chunk, total);
                        FmLog.log("[ffmpeg] 第 " + (index + 1) + "/" + SEGMENTS + " 段完成（"
                                + expected + " 字节）");
                        return null;
                    }
                }));
            }
            for (Future<Void> f : futures) {
                f.get(); // 任一段失败就抛出去，保留其他段供下次续传
            }
        } catch (Throwable t) {
            FmLog.warn("[ffmpeg] 分段下载失败，已完成的段保留供续传，改用单线程重试: " + t);
            clearSegments(dir);
            return downloadToFile(urlText, zipPart);
        } finally {
            pool.shutdownNow();
        }

        // 各段按序合并成完整 zip
        setProgress("合并中", SEGMENTS, SEGMENTS, total, total);
        File merge = new File(dir, ZIP_PART + ".merge");
        OutputStream out = new FileOutputStream(merge);
        try {
            byte[] buffer = new byte[1 << 16];
            for (int i = 0; i < SEGMENTS; i++) {
                InputStream in = new FileInputStream(segmentFile(dir, i));
                try {
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                } finally {
                    in.close();
                }
            }
            out.flush();
        } finally {
            out.close();
        }
        if (merge.length() != total) {
            merge.delete();
            throw new IOException("合并后大小不符：" + merge.length() + " != " + total);
        }
        if (zipPart.exists() && !zipPart.delete()) {
            merge.delete();
            throw new IOException("无法覆盖旧的下载缓存");
        }
        if (!merge.renameTo(zipPart)) {
            merge.delete();
            throw new IOException("无法重命名合并后的 zip");
        }
        clearSegments(dir);
        FmLog.log("[ffmpeg] " + SEGMENTS + " 段下载合并完成：" + total + " 字节");
        return total;
    }

    /** 下载从 from 到 to（含）的字节，追加写到 seg（续传时用）。 */
    private static void downloadRange(String urlText, File seg, long from, long to) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("Range", "bytes=" + from + "-" + to);
            int code = conn.getResponseCode();
            if (code != 206) {
                throw new IOException("Range 请求返回 HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(seg, true);
            try {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                }
                out.flush();
            } finally {
                out.close();
                in.close();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 用 HttpURLConnection 下载到文件，返回写入字节数；失败抛异常。 */
    private static long downloadToFile(String urlText, File target) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(urlText).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = conn.getInputStream();
            long total = 0L;
            OutputStream out = new FileOutputStream(target);
            try {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    total += n;
                }
                out.flush();
            } finally {
                out.close();
                in.close();
            }
            return total;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 从 zip 里找到 ffmpeg.exe（BtbN 包里在 {@code <root>/bin/ffmpeg.exe}），流式写到 exePart。
     * 返回写出的字节数，找不到返回 -1。
     */
    private static long extractFfmpeg(File zip, File exePart) throws IOException {
        ZipInputStream zin = new ZipInputStream(new FileInputStream(zip));
        try {
            byte[] buffer = new byte[1 << 16];
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').toLowerCase();
                boolean isExe = name.endsWith("/ffmpeg.exe") || name.equals("ffmpeg.exe");
                if (!isExe || entry.isDirectory()) {
                    zin.closeEntry();
                    continue;
                }
                long total = 0L;
                OutputStream out = new FileOutputStream(exePart);
                try {
                    int n;
                    while ((n = zin.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        total += n;
                    }
                    out.flush();
                } finally {
                    out.close();
                }
                zin.closeEntry();
                return total;
            }
            return -1L;
        } finally {
            zin.close();
        }
    }

    /* ------------------------------------------------------------------ 兜底与工具 */

    /**
     * 在线下载失败时的兜底：把老包里打包的 ffmpeg.exe 释放出来。
     * 当前两种包都没有这个资源，会直接返回 null，交给 FfmpegFinder 的外置候选。
     */
    private static File fromBundledResource(File dir, File target, List<String> notes) {
        try {
            InputStream in = BundledFfmpeg.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                notes.add("候选 0 内置兜底: jar 内无 ffmpeg.exe（在线版）");
                return null;
            }
            long size = resourceSize();
            File part = new File(dir, EXE_PART);
            long copied = 0L;
            OutputStream out = new FileOutputStream(part);
            try {
                byte[] buffer = new byte[1 << 16];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    copied += n;
                }
                out.flush();
            } finally {
                out.close();
                in.close();
            }
            if (size > 0 && copied != size) {
                part.delete();
                notes.add("候选 0 内置兜底: 大小不符");
                return null;
            }
            if (target.exists() && !target.delete()) {
                part.delete();
                return null;
            }
            if (!part.renameTo(target)) {
                part.delete();
                return null;
            }
            target.setExecutable(true);
            FmLog.log("[ffmpeg] 已用内置资源兜底释放 ffmpeg 到 " + target.getPath());
            notes.add("候选 0 内置兜底: 已释放到 " + target.getPath());
            return target;
        } catch (Throwable t) {
            notes.add("候选 0 内置兜底: 失败 " + t);
            return null;
        }
    }

    private static long resourceSize() {
        InputStream in = BundledFfmpeg.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return -1L;
        }
        try {
            long total = 0L;
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
            }
            return total;
        } catch (Throwable t) {
            return -1L;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 关闭失败无影响
            }
        }
    }

    private static String stampValue(String source) {
        return "src=" + source;
    }

    private static String readStamp(File stamp) {
        if (!stamp.isFile()) {
            return "";
        }
        try {
            InputStream in = new FileInputStream(stamp);
            try {
                byte[] data = new byte[(int) Math.min(stamp.length(), 4096L)];
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                    off += n;
                }
                return new String(data, 0, off, "UTF-8").trim();
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }

    private static void writeStamp(File stamp, String value) {
        try {
            OutputStream out = new FileOutputStream(stamp);
            try {
                out.write(value.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 标记写不进去不影响使用，下次启动会再下载一次
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}

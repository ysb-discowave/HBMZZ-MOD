package com.rsaudio.formats.convert;

import com.rsaudio.formats.FmConfig;
import com.rsaudio.formats.FmLog;
import com.rsaudio.formats.bridge.AudioBridge;
import com.rsaudio.formats.format.FormatRegistry;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Serial conversion queue for the "native route": any source format ffmpeg can decode becomes an OGG
 * Vorbis file in the cache directory, so the vanilla sound engine can stream it.
 *
 * <p>All work happens on one daemon thread; {@link #tick()} only drains finished results and is safe
 * to call from the render/tick thread.</p>
 */
public final class ConvertQueue {

    private static final String META_SUFFIX = ".meta";
    private static final int MAX_SCAN_DEPTH = 3;
    private static final int MAX_NAME = 80;
    private static final int DEFAULT_CACHE_LIMIT_MB = 512;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int TICK_DRAIN_LIMIT = 64;

    private static File audioDir;
    private static File cacheDir;
    private static volatile boolean initialized;
    private static volatile Thread workerThread;

    private static final LinkedBlockingQueue<Runnable> WORK = new LinkedBlockingQueue<Runnable>();
    private static final LinkedBlockingQueue<Done> RESULTS = new LinkedBlockingQueue<Done>();
    private static final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());

    /** Jobs enqueued but not yet reported through the result queue (= "pending"). */
    private static final AtomicInteger queued = new AtomicInteger();
    private static final AtomicInteger done = new AtomicInteger();
    private static final AtomicInteger failed = new AtomicInteger();
    /** Successes since the last consumed batch boundary. */
    private static final AtomicInteger batchOk = new AtomicInteger();
    private static volatile boolean inFlight;
    private static volatile String lastError;
    private static volatile boolean justFinishedBatch;
    private static volatile boolean rescanRequested;
    private static volatile boolean autoConvertWarned;

    private ConvertQueue() {
    }

    /** One finished job, handed from the worker to {@link #tick()}. */
    private static final class Done {
        File src;
        File target;
        OggConverter.Result res;
    }

    /** One queued conversion. */
    private static final class Job implements Runnable {
        final File src;
        final File target;

        Job(File src, File target) {
            this.src = src;
            this.target = target;
        }

        @Override
        public void run() {
            Done finished = new Done();
            finished.src = src;
            finished.target = target;
            try {
                pruneCacheIfNeeded();
                finished.res = OggConverter.convert(
                        FfmpegFinder.path(), src, target, clampQuality(FmConfig.oggQuality()), 0L);
                if (finished.res.ok) {
                    writeMeta(target, src);
                }
            } catch (Throwable t) {
                finished.res = new OggConverter.Result();
                finished.res.src = src;
                finished.res.target = target;
                finished.res.stderr = FmLog.describe(t);
                OggConverter.deleteQuietly(new File(target.getPath() + ".tmp"));
            } finally {
                // A job leaves "pending" only when its result becomes visible to tick().
                RESULTS.add(finished);
                queued.decrementAndGet();
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    public static synchronized void init(File audioDirectory, File cacheDirectory) {
        audioDir = audioDirectory;
        cacheDir = cacheDirectory;
        WORK.clear();
        RESULTS.clear();
        seen.clear();
        queued.set(0);
        done.set(0);
        failed.set(0);
        batchOk.set(0);
        inFlight = false;
        lastError = null;
        justFinishedBatch = false;
        rescanRequested = false;
        initialized = true;
        mkdirs(cacheDir);
        mkdirs(audioDir);
        ensureWorker();
        FmLog.log("[convert] 初始化: audio=" + (audioDir == null ? "-" : audioDir.getPath())
                + " cache=" + (cacheDir == null ? "-" : cacheDir.getPath())
                + " q=" + clampQuality(FmConfig.oggQuality()) + " limit=" + cacheLimitMb() + "MB");
    }

    private static void mkdirs(File dir) {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            FmLog.warn("[convert] 无法创建目录: " + dir.getPath());
        }
    }

    private static synchronized void ensureWorker() {
        if (workerThread != null && workerThread.isAlive()) {
            return;
        }
        workerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                workerLoop();
            }
        }, "rsaudioformats-convert-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    private static void workerLoop() {
        while (true) {
            Runnable task;
            try {
                task = WORK.take();
            } catch (InterruptedException ie) {
                return;
            }
            inFlight = true;
            try {
                task.run();
            } catch (Throwable t) {
                FmLog.error("[convert] 工作线程异常: " + FmLog.describe(t));
            } finally {
                inFlight = false;
            }
        }
    }

    // ---------------------------------------------------------------- public contract

    /** True when the cached OGG already matches the source (size + mtime) and is newer than it. */
    public static boolean isConverted(File src) {
        if (src == null || !src.isFile()) {
            return false;
        }
        File ogg = cacheOggOf(src);
        if (ogg == null || !ogg.isFile() || ogg.length() <= 0L) {
            return false;
        }
        if (ogg.lastModified() < src.lastModified()) {
            return false;
        }
        File meta = metaOf(ogg);
        if (!meta.isFile()) {
            // No bookkeeping file: a cache entry newer than its source is still trustworthy.
            return true;
        }
        try {
            String text = new String(Files.readAllBytes(meta.toPath()), UTF8).trim();
            String[] lines = text.split("\\r?\\n");
            if (lines.length < 2) {
                return false;
            }
            return Long.parseLong(lines[0].trim()) == src.length()
                    && Long.parseLong(lines[1].trim()) == src.lastModified();
        } catch (Exception e) {
            return false;
        }
    }

    /** Absolute path the cache OGG will live at; the file may not exist yet. */
    public static File cacheOggOf(File src) {
        if (src == null || cacheDir == null) {
            return null;
        }
        String name = src.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "bin";
        String stem = sanitizeStem(dot > 0 ? name.substring(0, dot) : name);
        if (stem.length() > MAX_NAME) {
            stem = stem.substring(0, MAX_NAME);
        }
        /*
         * The path hash is always appended: sanitize() maps every non [a-z0-9_-] run to '_', so
         * different CJK names collapse onto the same stem, and long stems get truncated. The hash
         * keeps keys distinct and inside MAX_NAME + suffix.
         */
        String hash = hash8(src.getAbsolutePath().toLowerCase(Locale.ROOT));
        return new File(cacheDir, stem + "_" + hash + "." + ext + ".ogg");
    }

    private static String sanitizeStem(String stem) {
        String sanitized = AudioBridge.sanitize(stem);
        if (sanitized == null || sanitized.trim().isEmpty()) {
            sanitized = localSanitize(stem);
        }
        return sanitized;
    }

    /** Same rule as {@code AudioLibrary.sanitize}, used when the bridge yields nothing usable. */
    private static String localSanitize(String raw) {
        if (raw == null) {
            return "track";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < raw.length(); i++) {
            char lower = Character.toLowerCase(raw.charAt(i));
            boolean keep = (lower >= 'a' && lower <= 'z') || (lower >= '0' && lower <= '9')
                    || lower == '-' || lower == '_';
            if (keep) {
                sb.append(lower);
                lastUnderscore = lower == '_';
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String trimmed = sb.toString();
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '_') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '_') {
            end--;
        }
        String out = trimmed.substring(start, end);
        return out.isEmpty() ? "track" : out;
    }

    private static String hash8(String key) {
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(UTF8));
        String hex = Long.toHexString(crc.getValue());
        while (hex.length() < 8) {
            hex = "0" + hex;
        }
        return hex.substring(0, 8);
    }

    private static File metaOf(File ogg) {
        return new File(ogg.getPath() + META_SUFFIX);
    }

    /** Records {@code size} and {@code mtime} of the source, so a changed source invalidates. */
    private static void writeMeta(File ogg, File src) {
        try {
            String text = src.length() + "\n" + src.lastModified() + "\n";
            Files.write(metaOf(ogg).toPath(), text.getBytes(UTF8));
            ogg.setLastModified(System.currentTimeMillis());
        } catch (Exception e) {
            FmLog.warn("[convert] 写 meta 失败: " + metaOf(ogg).getName() + " (" + FmLog.describe(e) + ")");
        }
    }

    /** Walks the audio directory and enqueues every supported, not-yet-converted file. Idempotent. */
    public static void scanAndEnqueue() {
        if (!initialized) {
            return;
        }
        doScan();
    }

    private static void doScan() {
        if (FfmpegFinder.path() == null) {
            FmLog.warn("[convert] 未找到 ffmpeg，原生路线不可用（PCM 直出仍可用）");
            return;
        }
        if (!FmConfig.autoConvert()) {
            if (!autoConvertWarned) {
                autoConvertWarned = true;
                FmLog.log("[convert] autoConvert=false，跳过自动转码扫描");
            }
            return;
        }
        if (audioDir == null || !audioDir.isDirectory()) {
            FmLog.warn("[convert] 音频目录不存在: " + (audioDir == null ? "-" : audioDir.getPath()));
            return;
        }
        List<File> found = new ArrayList<File>();
        collect(audioDir, 0, found);
        int added = 0;
        int skipped = 0;
        for (File file : found) {
            String key = file.getAbsolutePath().toLowerCase(Locale.ROOT);
            if (seen.contains(key)) {
                continue;
            }
            if (isConverted(file)) {
                skipped++;
                continue;
            }
            File target = cacheOggOf(file);
            if (target == null) {
                continue;
            }
            seen.add(key);
            queued.incrementAndGet();
            added++;
            WORK.add(new Job(file, target));
        }
        if (added > 0 || skipped > 0) {
            FmLog.log("[convert] 扫描完成: 待转 " + added + " 个, 已缓存跳过 " + skipped + " 个, 队列 " + queued.get());
        }
    }

    private static void collect(File dir, int depth, List<File> out) {
        if (depth > MAX_SCAN_DEPTH) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (child.equals(cacheDir) || child.getName().startsWith(".")) {
                    continue;
                }
                collect(child, depth + 1, out);
            } else if (needsConversion(child)) {
                out.add(child);
            }
        }
    }

    /** True for anything the decoder layer knows but the vanilla engine cannot play as-is. */
    private static boolean needsConversion(File file) {
        try {
            FormatRegistry.Kind kind = FormatRegistry.kindOf(file);
            return kind != null && kind != FormatRegistry.Kind.NATIVE_OGG;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Main-thread pump: drains finished results only. Never blocks, never spawns work inline. */
    public static void tick() {
        if (!initialized) {
            return;
        }
        if (rescanRequested) {
            rescanRequested = false;
            WORK.add(new Runnable() {
                @Override
                public void run() {
                    doScan();
                }
            });
        }
        for (int i = 0; i < TICK_DRAIN_LIMIT; i++) {
            Done finished = RESULTS.poll();
            if (finished == null) {
                break;
            }
            apply(finished);
        }
        if (queued.get() == 0 && !inFlight && batchOk.get() > 0) {
            synchronized (ConvertQueue.class) {
                if (batchOk.get() > 0) {
                    justFinishedBatch = true;
                    batchOk.set(0);
                }
            }
        }
    }

    private static void apply(Done finished) {
        OggConverter.Result r = finished.res;
        if (r == null) {
            return;
        }
        if (r.ok) {
            done.incrementAndGet();
            batchOk.incrementAndGet();
            FmLog.log("[convert] 完成 " + finished.src.getName()
                    + " in=" + kb(r.inBytes) + " out=" + kb(r.outBytes)
                    + " 耗时=" + r.elapsedMs + "ms code=" + r.exitCode
                    + " -> " + finished.target.getName());
        } else {
            failed.incrementAndGet();
            String reason = finished.src.getName() + " " + r.reason();
            lastError = reason;
            FmLog.warn("[convert] 失败 " + reason
                    + " in=" + kb(r.inBytes) + " 耗时=" + r.elapsedMs + "ms code=" + r.exitCode);
        }
    }

    private static String kb(long bytes) {
        return (bytes / 1024L) + "KB";
    }

    /** True exactly once per batch that finished with at least one successful conversion. */
    public static boolean consumeJustFinishedBatch() {
        synchronized (ConvertQueue.class) {
            if (justFinishedBatch) {
                justFinishedBatch = false;
                return true;
            }
            return false;
        }
    }

    public static int pending() {
        return queued.get();
    }

    public static int done() {
        return done.get();
    }

    public static int failed() {
        return failed.get();
    }

    public static String lastError() {
        return lastError;
    }

    public static void requestRescan() {
        rescanRequested = true;
    }

    // ---------------------------------------------------------------- cache budget

    private static int cacheLimitMb() {
        int mb = FmConfig.cacheLimitMb();
        return mb > 0 ? mb : DEFAULT_CACHE_LIMIT_MB;
    }

    private static int clampQuality(int quality) {
        return quality < 3 ? 3 : (quality > 8 ? 8 : quality);
    }

    /** Deletes least-recently-used cache OGGs while the cache is above the configured limit. */
    private static void pruneCacheIfNeeded() {
        if (cacheDir == null || !cacheDir.isDirectory()) {
            return;
        }
        long limit = (long) cacheLimitMb() * 1024L * 1024L;
        File[] files = cacheDir.listFiles();
        if (files == null) {
            return;
        }
        List<File> oggs = new ArrayList<File>();
        long total = 0L;
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".ogg")) {
                oggs.add(file);
                total += file.length();
            }
        }
        if (total <= limit) {
            return;
        }
        Collections.sort(oggs, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        long budget = limit - (limit / 10L);
        int removed = 0;
        long freed = 0L;
        for (File ogg : oggs) {
            if (total <= budget) {
                break;
            }
            long size = ogg.length();
            OggConverter.deleteQuietly(metaOf(ogg));
            if (OggConverter.deleteQuietlyChecked(ogg)) {
                total -= size;
                freed += size;
                removed++;
            }
        }
        if (removed > 0) {
            FmLog.log("[convert] 缓存超限(" + (limit / 1048576L) + "MB)，按 LRU 清理 "
                    + removed + " 个 ogg，释放 " + (freed / 1024L) + "KB，当前 " + (total / 1048576L) + "MB");
        }
    }

    /** Test hook: cancels the in-flight transcode (used by the interrupt self-test). */
    static void cancelCurrentConversion() {
        OggConverter.requestCancel();
    }
}

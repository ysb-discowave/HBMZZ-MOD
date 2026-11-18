package com.rsaudio.formats.convert;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * Transcodes one source file into a cache OGG with a single, serialised ffmpeg child process.
 *
 * <p>Contract: output never appears under its final name unless it is complete. The child writes
 * {@code <target>.tmp}, and only a clean exit + non-empty tmp promotes it with an atomic move.</p>
 */
public final class OggConverter {

    /** Minimum / maximum wall clock budget for one transcode. */
    private static final long MIN_TIMEOUT_MS = 15000L;
    private static final long MAX_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final int STDERR_KEEP = 4000;
    private static final int STDERR_LOG = 300;
    private static final long CANCEL_POLL_MS = 100L;

    /** Currently running child, so a cancel (or shutdown) can kill it. */
    private static volatile Process active;

    private OggConverter() {
    }

    /** Result of one transcode attempt. */
    public static final class Result {
        public File src;
        public File target;
        public boolean ok;
        public int exitCode = -1;
        /** True once the child process was actually spawned (distinguishes "no exit code" from a failure). */
        public boolean started;
        public long inBytes;
        public long outBytes;
        public long elapsedMs;
        public long timeoutMs;
        public long durationMs;
        public boolean timedOut;
        public boolean canceled;
        /** stderr, already capped to the tail. */
        public String stderr = "";

        /** Single-line reason suitable for {@code ConvertQueue.lastError()}. */
        public String reason() {
            StringBuilder sb = new StringBuilder();
            if (timedOut) {
                sb.append("超时(").append(timeoutMs).append("ms)");
            } else if (canceled) {
                sb.append("已取消");
            } else if (!started) {
                sb.append("无法启动 ffmpeg");
            } else {
                sb.append("ffmpeg 退出码 ").append(exitCode);
            }
            String err = lastStderrLine();
            if (!err.isEmpty()) {
                sb.append(": ").append(err.length() > STDERR_LOG ? err.substring(0, STDERR_LOG) : err);
            }
            return sb.toString();
        }

        public String lastStderrLine() {
            if (stderr == null || stderr.isEmpty()) {
                return "";
            }
            String[] lines = stderr.split("\\r?\\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String l = lines[i].trim();
                if (!l.isEmpty()) {
                    return l;
                }
            }
            return "";
        }
    }

    /** Kill the child process currently transcoding, if any. Safe to call from any thread. */
    public static void cancelActive() {
        Process p = active;
        if (p != null) {
            try {
                p.destroyForcibly();
            } catch (Throwable ignored) {
                // best effort
            }
        }
    }

    /** Reads the media duration from {@code ffmpeg -i} stderr; 0 when unknown. */
    public static long probeDurationMs(String ffmpeg, File src) {
        if (ffmpeg == null || src == null) {
            return 0L;
        }
        Process p = null;
        try {
            p = new ProcessBuilder(ffmpeg, "-hide_banner", "-nostdin", "-i", src.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            final StringBuilder sb = new StringBuilder();
            final Process proc = p;
            Thread drain = new Thread(new Runnable() {
                @Override
                public void run() {
                    BufferedReader r = null;
                    try {
                        r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (line.contains("Duration:")) {
                                sb.append(line);
                                break;
                            }
                        }
                    } catch (Exception ignored) {
                        // best effort
                    } finally {
                        closeQuietly(r);
                    }
                }
            }, "rsaudioformats-ffmpeg-duration");
            drain.setDaemon(true);
            drain.start();
            if (!p.waitFor(10000L, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                return 0L;
            }
            drain.join(500L);
            return parseDuration(sb.toString());
        } catch (Exception e) {
            return 0L;
        } finally {
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    private static long parseDuration(String line) {
        int idx = line.indexOf("Duration:");
        if (idx < 0) {
            return 0L;
        }
        String rest = line.substring(idx + "Duration:".length()).trim();
        if (rest.startsWith("N/A")) {
            return 0L;
        }
        int comma = rest.indexOf(',');
        if (comma > 0) {
            rest = rest.substring(0, comma).trim();
        }
        String[] parts = rest.split(":");
        if (parts.length != 3) {
            return 0L;
        }
        try {
            long h = Long.parseLong(parts[0].trim());
            long m = Long.parseLong(parts[1].trim());
            double s = Double.parseDouble(parts[2].trim());
            return h * 3600000L + m * 60000L + (long) (s * 1000.0);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * Transcodes {@code src} into {@code target}. Blocking; callers must be on the single worker
     * thread. {@code timeoutMs <= 0} derives the budget from the media duration.
     */
    public static Result convert(String ffmpeg, File src, File target, int quality, long timeoutMs) {
        Result res = new Result();
        res.src = src;
        res.target = target;
        res.inBytes = src == null ? 0L : src.length();

        File tmp = new File(target.getPath() + ".tmp");
        deleteQuietly(tmp);

        if (ffmpeg == null || !new File(ffmpeg).isFile()) {
            res.stderr = "ffmpeg 不可用";
            return res;
        }
        if (src == null || !src.isFile()) {
            res.stderr = "源文件不存在";
            return res;
        }

        long start = System.currentTimeMillis();
        Process p = null;
        StringBuilder err = new StringBuilder();
        try {
            res.durationMs = probeDurationMs(ffmpeg, src);
            res.timeoutMs = timeoutMs > 0
                    ? timeoutMs
                    : Math.min(MAX_TIMEOUT_MS, Math.max(MIN_TIMEOUT_MS, res.durationMs * 3L));

            p = new ProcessBuilder(
                    ffmpeg,
                    "-hide_banner",
                    "-nostdin",
                    "-y",
                    "-loglevel", "error",
                    "-i", src.getAbsolutePath(),
                    "-vn",
                    "-map_metadata", "-1",
                    "-ac", "2",
                    "-ar", "44100",
                    "-c:a", "libvorbis",
                    "-q:a", String.valueOf(quality),
                    "-f", "ogg",
                    tmp.getAbsolutePath())
                    .redirectErrorStream(false)
                    .start();
            active = p;
            res.started = true;

            Thread drain = drainThread(p.getErrorStream(), err);
            Thread drainOut = drainThread(p.getInputStream(), new StringBuilder());
            drain.start();
            drainOut.start();

            long deadline = start + res.timeoutMs;
            while (true) {
                if (p.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                    if (cancelRequested.get()) {
                        // the watchdog killed it before our poll saw the flag
                        res.canceled = true;
                    }
                    break;
                }
                if (Thread.currentThread().isInterrupted() || cancelRequested.get()) {
                    res.canceled = true;
                    p.destroyForcibly();
                    p.waitFor(3000L, TimeUnit.MILLISECONDS);
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    res.timedOut = true;
                    p.destroyForcibly();
                    p.waitFor(3000L, TimeUnit.MILLISECONDS);
                    break;
                }
            }
            try {
                res.exitCode = p.exitValue();
            } catch (IllegalThreadStateException e) {
                res.exitCode = -1;
            }
            drain.join(1000L);
            drainOut.join(500L);
            res.stderr = capTail(err.toString());

            if (!res.timedOut && !res.canceled && res.exitCode == 0 && tmp.isFile() && tmp.length() > 0) {
                promote(tmp, target);
                res.outBytes = target.length();
                res.ok = true;
            } else {
                if (!res.timedOut && !res.canceled && res.exitCode == 0) {
                    res.stderr = res.stderr + "\n empty output";
                }
                deleteQuietly(tmp);
            }
        } catch (Exception e) {
            res.stderr = e.getClass().getSimpleName() + ": " + e.getMessage();
            deleteQuietly(tmp);
        } finally {
            active = null;
            cancelRequested.set(false);
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
            res.elapsedMs = System.currentTimeMillis() - start;
        }
        return res;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean cancelRequested =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Requests cancellation of the in-flight transcode from the queue/watchdog side. */
    static void requestCancel() {
        cancelRequested.set(true);
        cancelActive();
    }

    private static Thread drainThread(final InputStream in, final StringBuilder sink) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                BufferedReader r = null;
                try {
                    r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (sink.length() < STDERR_KEEP) {
                            sink.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // the child was killed, the stream simply ends
                } finally {
                    closeQuietly(r);
                }
            }
        }, "rsaudioformats-ffmpeg-drain");
        t.setDaemon(true);
        return t;
    }

    private static String capTail(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= STDERR_KEEP ? s : s.substring(s.length() - STDERR_KEEP);
    }

    private static void promote(File tmp, File target) throws Exception {
        Path from = tmp.toPath();
        Path to = target.toPath();
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception atomicFailed) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Same as {@link #deleteQuietly(File)} but reports whether the file is gone afterwards. */
    static boolean deleteQuietlyChecked(File f) {
        if (f == null || !f.exists()) {
            return false;
        }
        deleteQuietly(f);
        return !f.exists();
    }

    static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                Files.deleteIfExists(f.toPath());
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
    }
}

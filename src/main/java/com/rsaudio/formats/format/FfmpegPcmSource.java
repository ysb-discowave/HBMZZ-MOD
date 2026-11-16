package com.rsaudio.formats.format;

import com.rsaudio.formats.FormatsProps;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;

/**
 * Audio decoding through an external ffmpeg process. ffmpeg writes signed
 * 16-bit little-endian PCM to stdout; a bounded queue decouples the blocking
 * process pipe from {@link #read(byte[], int, int)} so a stuck ffmpeg can never
 * block the caller forever.
 */
final class FfmpegPcmSource implements PcmSource {

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int CHUNK_BYTES = 16384;
    private static final int QUEUE_CHUNKS = 8;
    private static final int START_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS =
            FormatsProps.getInt("ffmpeg.timeoutMs", 10000);
    private static final byte[] END = new byte[0];

    private final Process process;
    private final String fileName;
    private final AudioFormat format;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(QUEUE_CHUNKS);
    private final ArrayDeque<String> errorLines = new ArrayDeque<String>(12);
    private final Thread outputPump;
    private final Thread errorPump;

    private volatile double duration = -1;
    private volatile boolean closed;
    private boolean endOfStream;
    private byte[] pending;
    private int pendingPosition;

    FfmpegPcmSource(File file, String ffmpegPath) throws IOException {
        String executable = locate(ffmpegPath);
        if (executable == null) {
            throw new IOException("ffmpeg not found (set -Drsaudiofm.ffmpeg or add it to PATH)");
        }
        this.fileName = file.getName();
        List<String> command = new ArrayList<String>();
        Collections.addAll(
                command,
                executable,
                "-hide_banner",
                "-nostdin",
                "-v",
                "info",
                "-i",
                file.getAbsolutePath(),
                "-vn",
                "-f",
                "s16le",
                "-ar",
                String.valueOf(SAMPLE_RATE),
                "-ac",
                String.valueOf(CHANNELS),
                "-");
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(false);
        Process started;
        try {
            started = builder.start();
        } catch (IOException e) {
            throw new IOException("cannot start ffmpeg: " + e.getMessage(), e);
        }
        this.process = started;
        this.format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, CHANNELS, CHANNELS * 2, SAMPLE_RATE, false);
        this.outputPump = new Thread(new Runnable() {
            @Override
            public void run() {
                pumpOutput();
            }
        }, "rsaudioformats-ffmpeg-out");
        this.outputPump.setDaemon(true);
        this.errorPump = new Thread(new Runnable() {
            @Override
            public void run() {
                pumpErrors();
            }
        }, "rsaudioformats-ffmpeg-err");
        this.errorPump.setDaemon(true);
        this.outputPump.start();
        this.errorPump.start();
        awaitFirstOutput();
    }

    static String locate(String explicit) {
        String candidate = explicit;
        if (candidate == null || candidate.trim().isEmpty()) {
            candidate = FormatsProps.get("ffmpeg");
        }
        if (candidate != null && !candidate.trim().isEmpty()) {
            File file = new File(candidate.trim());
            return file.isFile() ? file.getAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        String[] directories = path.split(File.pathSeparator);
        for (String directory : directories) {
            if (directory.isEmpty()) {
                continue;
            }
            File exe = new File(directory, "ffmpeg.exe");
            if (exe.isFile()) {
                return exe.getAbsolutePath();
            }
            File plain = new File(directory, "ffmpeg");
            if (plain.isFile()) {
                return plain.getAbsolutePath();
            }
        }
        return null;
    }

    @Override
    public AudioFormat format() {
        return format;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (closed || endOfStream) {
            return -1;
        }
        if (buf == null) {
            throw new NullPointerException("buf");
        }
        if (off < 0 || len < 0 || off + len > buf.length) {
            throw new IndexOutOfBoundsException("off=" + off + " len=" + len);
        }
        if (len == 0) {
            return 0;
        }
        int written = 0;
        while (written < len) {
            if (pending != null && pendingPosition < pending.length) {
                int copy = Math.min(len - written, pending.length - pendingPosition);
                System.arraycopy(pending, pendingPosition, buf, off + written, copy);
                pendingPosition += copy;
                written += copy;
                continue;
            }
            pending = null;
            byte[] chunk;
            try {
                chunk = queue.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (chunk == null) {
                if (!process.isAlive()) {
                    endOfStream = true;
                    break;
                }
                FmLog.warn("ffmpeg stalled for " + READ_TIMEOUT_MS + " ms while decoding " + fileName
                        + "; process killed");
                killProcess();
                endOfStream = true;
                break;
            }
            if (chunk == END) {
                endOfStream = true;
                break;
            }
            pending = chunk;
            pendingPosition = 0;
        }
        return written == 0 ? -1 : written;
    }

    @Override
    public double durationSeconds() {
        return duration;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        killProcess();
        outputPump.interrupt();
        errorPump.interrupt();
        queue.clear();
    }

    private void awaitFirstOutput() throws IOException {
        long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (!queue.isEmpty()) {
                return;
            }
            if (!process.isAlive()) {
                String summary = errorSummary();
                killProcess();
                throw new IOException(summary.isEmpty()
                        ? "ffmpeg exited with code " + process.exitValue()
                        : summary);
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pumpOutput() {
        InputStream in = null;
        try {
            in = process.getInputStream();
            byte[] buffer = new byte[CHUNK_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                byte[] chunk = new byte[read];
                System.arraycopy(buffer, 0, chunk, 0, read);
                queue.put(chunk);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // process was killed or the pipe closed
        } finally {
            closeQuietly(in);
            offerEnd();
        }
    }

    private void offerEnd() {
        while (!closed) {
            try {
                if (queue.offer(END, 200L, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void pumpErrors() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(process.getErrorStream(), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                parseDuration(line);
                recordError(line);
            }
        } catch (IOException ignored) {
            // process was killed or the pipe closed
        } finally {
            closeQuietly(reader);
        }
    }

    private void parseDuration(String line) {
        int marker = line.indexOf("Duration: ");
        if (marker < 0) {
            return;
        }
        String value = line.substring(marker + "Duration: ".length()).trim();
        int comma = value.indexOf(',');
        if (comma >= 0) {
            value = value.substring(0, comma).trim();
        }
        String[] parts = value.split(":");
        if (parts.length != 3) {
            return;
        }
        try {
            double hours = Double.parseDouble(parts[0]);
            double minutes = Double.parseDouble(parts[1]);
            double seconds = Double.parseDouble(parts[2]);
            duration = hours * 3600.0 + minutes * 60.0 + seconds;
        } catch (NumberFormatException ignored) {
            // "Duration: N/A" and friends are not an error
        }
    }

    private void recordError(String line) {
        if (line.trim().isEmpty()) {
            return;
        }
        synchronized (errorLines) {
            if (errorLines.size() >= 12) {
                errorLines.removeFirst();
            }
            errorLines.addLast(line);
        }
    }

    private String errorSummary() {
        synchronized (errorLines) {
            for (Iterator<String> iterator = errorLines.descendingIterator(); iterator.hasNext();) {
                String line = iterator.next();
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("error")
                        || lower.contains("invalid")
                        || lower.contains("no such")
                        || lower.contains("failed")
                        || lower.contains("unable")) {
                    return line.trim();
                }
            }
            if (!errorLines.isEmpty()) {
                return errorLines.peekLast().trim();
            }
        }
        return "";
    }

    private void killProcess() {
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // best effort
        }
        process.destroy();
        try {
            if (!process.waitFor(400L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}

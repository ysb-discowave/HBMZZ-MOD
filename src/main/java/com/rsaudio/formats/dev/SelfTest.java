package com.rsaudio.formats.dev;

import com.rsaudio.formats.FmLog;
import com.rsaudio.formats.FormatsProps;
import com.rsaudio.formats.bridge.AudioBridge;
import com.rsaudio.formats.bridge.LibraryInjector;
import com.rsaudio.formats.convert.ConvertQueue;
import com.rsaudio.formats.convert.FfmpegFinder;
import com.rsaudio.formats.format.AudioProbe;
import com.rsaudio.formats.format.FormatRegistry;
import com.rsaudio.formats.format.PcmSource;
import com.rsaudio.formats.format.Sources;
import com.rsaudio.formats.play.PcmPlayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;

/**
 * Acceptance harness. Enabled only by {@code -Drsaudio-formats.selftest=1} (the pre-merge spelling
 * {@code -Drsaudiofm.selftest=1} is still honoured, see {@code FormatsProps}); shipped runs are a
 * no-op otherwise. It drives the whole pipeline from a client that never leaves the main menu:
 * inject entries -> convert -> regenerate the dynamic sound pack -> resolve the sound events ->
 * push one decoded file straight to the sound card. Every step is written to the FML log with a
 * {@code [SELFTEST]} prefix so the result can be checked without a human clicking anything.
 */
public final class SelfTest {

    private static final String PREFIX = "[SELFTEST] ";

    private static boolean enabled;
    private static boolean initialised;
    private static int phase;
    private static long phaseDeadline;
    private static long startedAt;
    private static final List<File> testFiles = new ArrayList<File>();
    private static PcmPlayer player;
    private static PcmSource playing;
    private static int pcmStartedAtTick;
    private static int tickCounter;

    private SelfTest() {
    }

    public static void tick(Minecraft mc) {
        if (!enabled) {
            enabled = FormatsProps.get("selftest") != null;
            if (!enabled) {
                return;
            }
            startedAt = System.currentTimeMillis();
            FmLog.info(PREFIX + "enabled, waiting for the client to settle");
            return;
        }
        if (mc == null) {
            return;
        }
        ++tickCounter;
        if (!initialised) {
            if (tickCounter < 40) {
                // Give rsaudio's own first-tick rescan/generate a chance to run first.
                return;
            }
            initialised = true;
            begin();
            return;
        }
        switch (phase) {
            case 1:
                waitForConversion();
                break;
            case 2:
                startPcm();
                break;
            case 3:
                finishPcm();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ phases

    private static void begin() {
        FmLog.info(PREFIX + "phase0: audioDir=" + AudioBridge.audioDir()
                + " available=" + AudioBridge.available()
                + " ffmpeg=" + FfmpegFinder.path());
        collectTestFiles();
        FmLog.info(PREFIX + "phase0: test files=" + testFiles.size());
        try {
            LibraryInjector.syncNow();
        } catch (Throwable t) {
            FmLog.warn(PREFIX + "phase0: syncNow failed: " + FmLog.describe(t));
        }
        dumpEntries("after first sync");
        try {
            ConvertQueue.scanAndEnqueue();
        } catch (Throwable t) {
            FmLog.warn(PREFIX + "phase0: scanAndEnqueue failed: " + FmLog.describe(t));
        }
        FmLog.info(PREFIX + "phase1: converting, pending=" + ConvertQueue.pending());
        if (FfmpegFinder.path() == null) {
            // Without ffmpeg there is nothing to convert; go straight to the direct-route probe so
            // the run still produces evidence instead of waiting out the deadline.
            FmLog.warn(PREFIX + "phase1: ffmpeg missing, skipping conversion");
            phase = 2;
            return;
        }
        phase = 1;
        phaseDeadline = System.currentTimeMillis() + 180000L;
    }

    private static void collectTestFiles() {
        File dir = AudioBridge.audioDir();
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f == null || !f.isFile() || f.getName().startsWith(".")) {
                continue;
            }
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".ogg")) {
                continue;
            }
            if (!FormatRegistry.supportedExtensions().contains(extension(lower))) {
                continue;
            }
            if (testFiles.size() < 12) {
                testFiles.add(f);
            }
        }
        Collections.sort(testFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
    }

    private static void waitForConversion() {
        int expected = 0;
        int doneCount = 0;
        for (File f : testFiles) {
            if (FormatRegistry.kindOf(f) == FormatRegistry.Kind.NATIVE_OGG) {
                continue;
            }
            ++expected;
            if (ConvertQueue.isConverted(f)) {
                ++doneCount;
            }
        }
        boolean allDone = doneCount >= expected;
        boolean timedOut = System.currentTimeMillis() > phaseDeadline;
        if (!allDone && !timedOut) {
            return;
        }
        FmLog.info(PREFIX + "phase1: converted=" + doneCount + "/" + expected
                + (timedOut ? " (TIMEOUT)" : "")
                + " done=" + ConvertQueue.done() + " failed=" + ConvertQueue.failed()
                + " lastError=" + ConvertQueue.lastError());
        for (File f : testFiles) {
            FmLog.info(PREFIX + "  file " + f.getName()
                    + " -> converted=" + ConvertQueue.isConverted(f)
                    + " cache=" + name(ConvertQueue.cacheOggOf(f)));
        }
        try {
            LibraryInjector.syncNow();
            dumpEntries("after conversion");
            boolean changed = AudioBridge.generate();
            FmLog.info(PREFIX + "phase2: sounds.json changed=" + changed);
            AudioBridge.enableAndReload();
            FmLog.info(PREFIX + "phase2: resource pack reloaded");
            for (File f : testFiles) {
                String soundName = soundNameOf(f);
                FmLog.info(PREFIX + "  nativeReady(" + soundName + ")=" + AudioBridge.nativeReady(soundName)
                        + " direct=" + LibraryInjector.isDirect(soundName));
            }
        } catch (Throwable t) {
            FmLog.warn(PREFIX + "phase2: pack regeneration failed: " + FmLog.describe(t));
        }
        phase = 2;
    }

    private static void startPcm() {
        File source = pickPcmSource();
        if (source == null) {
            FmLog.warn(PREFIX + "phase3: no openable source for the PCM probe");
            phase = 4;
            conclude();
            return;
        }
        FmLog.info(PREFIX + "phase3: pcm probe with " + source.getName());
        try {
            AudioProbe.Probe probe = AudioProbe.probe(source);
            FmLog.info(PREFIX + "phase3: probe codec=" + (probe == null ? "null" : probe.codec)
                    + " rate=" + (probe == null ? -1 : probe.sampleRate)
                    + " channels=" + (probe == null ? -1 : probe.channels)
                    + " seconds=" + (probe == null ? -1 : probe.seconds));
            playing = Sources.open(source);
            if (playing == null) {
                FmLog.warn(PREFIX + "phase3: Sources.open returned null");
                phase = 4;
                conclude();
                return;
            }
            player = new PcmPlayer();
            boolean ok = player.start(playing.format(), new SourceStream(playing), 0.5f, 0f, false);
            FmLog.info(PREFIX + "phase3: line opened=" + ok + " format=" + playing.format()
                    + " duration=" + playing.durationSeconds() + "s");
            if (!ok) {
                closePcm();
                phase = 4;
                conclude();
                return;
            }
            pcmStartedAtTick = tickCounter;
            phase = 3;
        } catch (Throwable t) {
            FmLog.warn(PREFIX + "phase3: pcm probe failed: " + FmLog.describe(t));
            closePcm();
            phase = 4;
            conclude();
        }
    }

    private static void finishPcm() {
        if (tickCounter - pcmStartedAtTick < 80) {
            return;
        }
        try {
            FmLog.info(PREFIX + "phase3: playedSeconds=" + String.format(Locale.ROOT, "%.3f", player.playedSeconds())
                    + " playing=" + player.isPlaying());
        } catch (Throwable t) {
            FmLog.warn(PREFIX + "phase3: diag failed: " + FmLog.describe(t));
        }
        closePcm();
        phase = 4;
        conclude();
    }

    private static void conclude() {
        FmLog.info(PREFIX + "DONE in " + (System.currentTimeMillis() - startedAt) + "ms"
                + " injected=" + LibraryInjector.injectedCount()
                + " entries=" + size(AudioBridge.entries()));
        runShutdownAfterDelay();
    }

    private static void runShutdownAfterDelay() {
        String exit = FormatsProps.get("selftest.exitSec");
        if (exit == null) {
            return;
        }
        int seconds;
        try {
            seconds = Integer.parseInt(exit.trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (seconds <= 0) {
            return;
        }
        FmLog.info(PREFIX + "shutting the client down in " + seconds + "s");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(seconds * 1000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
                FmLog.info(PREFIX + "requesting client shutdown");
                Minecraft.getMinecraft().shutdown();
            }
        }, "rsaudioformats-selftest-exit");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ helpers

    private static File pickPcmSource() {
        for (File f : testFiles) {
            if (FormatRegistry.kindOf(f) == null) {
                continue;
            }
            if (FormatRegistry.kindOf(f) == FormatRegistry.Kind.NATIVE_OGG) {
                continue;
            }
            if (!ConvertQueue.isConverted(f)) {
                // Not yet converted: the pure-Java decoders must carry this one, which is exactly
                // the branch the direct route needs.
                return f;
            }
        }
        for (File f : testFiles) {
            if (FormatRegistry.kindOf(f) != null) {
                return f;
            }
        }
        return null;
    }

    private static void dumpEntries(String when) {
        List<Object> entries = AudioBridge.entries();
        if (entries == null) {
            FmLog.info(PREFIX + "entries(" + when + ")=null");
            return;
        }
        int injected = 0;
        StringBuilder sb = new StringBuilder();
        for (Object entry : new ArrayList<Object>(entries)) {
            String soundName = field(entry, "soundName");
            String displayName = field(entry, "displayName");
            if (soundName == null) {
                continue;
            }
            if (!looksInjected(soundName)) {
                continue;
            }
            ++injected;
            if (injected <= 20) {
                sb.append("\n  ").append(soundName)
                        .append(" | ").append(displayName)
                        .append(" | direct=").append(LibraryInjector.isDirect(soundName));
            }
        }
        FmLog.info(PREFIX + "entries(" + when + "): total=" + entries.size() + " ours=" + injected + sb);
    }

    private static boolean looksInjected(String soundName) {
        return LibraryInjector.fileOf(soundName) != null;
    }

    private static String soundNameOf(File f) {
        String base = AudioBridge.sanitize(f.getName());
        if (base == null) {
            base = f.getName().toLowerCase(Locale.ROOT);
        }
        return base;
    }

    private static String field(Object entry, String name) {
        try {
            Field f = entry.getClass().getField(name);
            Object v = f.get(entry);
            return v == null ? null : String.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String name(File f) {
        return f == null ? "null" : f.getName();
    }

    private static int size(List<?> list) {
        return list == null ? -1 : list.size();
    }

    private static String extension(String lower) {
        int dot = lower.lastIndexOf('.');
        return dot > 0 ? lower.substring(dot + 1) : "";
    }

    private static void closePcm() {
        if (player != null) {
            try {
                player.stop();
                player.close();
            } catch (Throwable ignored) {
                // Nothing useful to do.
            }
            player = null;
        }
        if (playing != null) {
            try {
                playing.close();
            } catch (Throwable ignored) {
                // Nothing useful to do.
            }
            playing = null;
        }
    }

    /** Adapts a {@link PcmSource} to the byte stream {@link PcmPlayer} consumes. */
    private static final class SourceStream extends InputStream {
        private final PcmSource source;
        private final byte[] one = new byte[1];

        SourceStream(PcmSource source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return source.read(b, off, len);
        }
    }
}

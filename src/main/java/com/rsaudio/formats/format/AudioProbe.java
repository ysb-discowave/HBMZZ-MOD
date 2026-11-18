package com.rsaudio.formats.format;

import java.io.File;
import java.io.IOException;
import javax.sound.sampled.AudioFormat;

/**
 * Lightweight format probe: opens the file just far enough to learn sample
 * rate, channel count, duration and codec, then closes it again.
 */
public final class AudioProbe {

    private AudioProbe() {}

    /** Returns null when the file cannot be probed; never throws. */
    public static Probe probe(File file) {
        if (file == null) {
            return null;
        }
        PcmSource source = null;
        try {
            source = Sources.open(file);
            if (source == null) {
                return null;
            }
            AudioFormat format = source.format();
            if (format == null) {
                return null;
            }
            int sampleRate = Math.round(format.getSampleRate());
            int channels = format.getChannels();
            if (sampleRate <= 0 || channels <= 0) {
                return null;
            }
            return new Probe(sampleRate, channels, source.durationSeconds(), FormatRegistry.codecLabel(file));
        } catch (Throwable t) {
            String message = t.getMessage();
            FmLog.warn("probe: " + file.getName() + ": " + t.getClass().getSimpleName()
                    + (message == null || message.isEmpty() ? "" : ": " + message));
            return null;
        } finally {
            if (source != null) {
                try {
                    source.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    /** Immutable probe result. */
    public static final class Probe {

        public final int sampleRate;
        public final int channels;
        public final double seconds;
        public final String codec;

        public Probe(int sampleRate, int channels, double seconds, String codec) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.seconds = seconds;
            this.codec = codec;
        }

        @Override
        public String toString() {
            return codec + " " + sampleRate + " Hz " + channels + " ch " + seconds + " s";
        }
    }
}

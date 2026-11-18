package com.rsaudio.formats.format;

/**
 * Minimal logging hook of the decode layer. Defaults to stdout/stderr; the mod
 * layer may replace the sink at startup.
 */
public final class FmLog {

    /** Receives log lines. Implementations must not throw. */
    public interface Sink {
        void log(String message);

        void warn(String message);
    }

    private static final Sink DEFAULT = new Sink() {
        @Override
        public void log(String message) {
            System.out.println("[rsaudio-formats] " + message);
        }

        @Override
        public void warn(String message) {
            System.err.println("[rsaudio-formats] " + message);
        }
    };

    private static volatile Sink sink = DEFAULT;

    private FmLog() {}

    public static void setSink(Sink newSink) {
        sink = newSink == null ? DEFAULT : newSink;
    }

    public static void log(String message) {
        Sink current = sink;
        try {
            current.log(message);
        } catch (RuntimeException ignored) {
            // A broken sink must never break decoding.
        }
    }

    public static void warn(String message) {
        Sink current = sink;
        try {
            current.warn(message);
        } catch (RuntimeException ignored) {
            // A broken sink must never break decoding.
        }
    }
}

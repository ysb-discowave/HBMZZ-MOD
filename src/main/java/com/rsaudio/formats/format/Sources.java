package com.rsaudio.formats.format;

import java.io.File;

/**
 * Entry point of the decode layer: picks an implementation for the given file
 * and returns a 16-bit PCM stream, or null when the file cannot be decoded.
 */
public final class Sources {

    private Sources() {}

    public static PcmSource open(File file) {
        if (file == null) {
            FmLog.warn("open: no file given");
            return null;
        }
        if (!file.isFile()) {
            FmLog.warn("open: not a file: " + file);
            return null;
        }
        if (file.length() <= 0) {
            FmLog.warn("open: empty file (0 bytes): " + file.getName());
            return null;
        }
        FormatRegistry.Kind kind = FormatRegistry.kindOf(file);
        if (kind == null) {
            FmLog.warn("open: unsupported format: " + file.getName());
            return null;
        }
        switch (kind) {
            case NATIVE_OGG:
                FmLog.log("open: " + file.getName() + " is Ogg Vorbis, the native path handles it");
                return null;
            case FFMPEG:
                return openWithFfmpeg(file, null);
            case PURE_JAVA:
            default:
                return openPureJava(file);
        }
    }

    private static PcmSource openPureJava(File file) {
        String failure = null;
        if (FormatRegistry.isFlac(file)) {
            try {
                return FlacPcmSource.open(file);
            } catch (Throwable t) {
                failure = describe(t);
            }
        } else {
            try {
                PcmSource source = JavaSoundPcmSource.open(file);
                if (source != null) {
                    return source;
                }
                failure = "javax.sound produced no stream";
            } catch (Throwable t) {
                failure = describe(t);
            }
        }
        return openWithFfmpeg(file, failure);
    }

    private static PcmSource openWithFfmpeg(File file, String previousFailure) {
        String ffmpeg = FfmpegPcmSource.locate(null);
        if (ffmpeg == null) {
            StringBuilder message = new StringBuilder("open: ffmpeg is not available for ");
            message.append(file.getName());
            if (previousFailure != null) {
                message.append(" (pure Java failed: ").append(previousFailure).append(')');
            }
            FmLog.warn(message.toString());
            return null;
        }
        try {
            PcmSource source = new FfmpegPcmSource(file, ffmpeg);
            FmLog.log("open: decoding " + file.getName() + " through ffmpeg" + (previousFailure == null
                    ? ""
                    : " (pure Java failed: " + previousFailure + ")"));
            return source;
        } catch (Throwable t) {
            StringBuilder message = new StringBuilder("open: ffmpeg failed for ");
            message.append(file.getName()).append(": ").append(describe(t));
            if (previousFailure != null) {
                message.append(" (pure Java failed: ").append(previousFailure).append(')');
            }
            FmLog.warn(message.toString());
            return null;
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return t.getClass().getSimpleName() + ": " + message;
    }
}

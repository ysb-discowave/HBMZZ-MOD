package com.rsaudio.formats.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Maps a file to the decoding backend that can handle it. Content is inspected
 * first (magic bytes), the extension is only a fallback for files whose header
 * is unknown or absent.
 */
public final class FormatRegistry {

    public enum Kind {
        /** Ogg Vorbis: handled natively by Minecraft, not by this layer. */
        NATIVE_OGG,
        /** javax.sound.sampled or the bundled jFLAC decoder. */
        PURE_JAVA,
        /** External ffmpeg process. */
        FFMPEG
    }

    private static final Set<String> EXTENSIONS;

    static {
        LinkedHashSet<String> set = new LinkedHashSet<String>();
        Collections.addAll(
                set,
                "wav",
                "wave",
                "aif",
                "aiff",
                "aifc",
                "au",
                "snd",
                "mp3",
                "flac",
                "ogg",
                "oga",
                "m4a",
                "m4b",
                "aac",
                "opus",
                "wma",
                "ape",
                "wv",
                "mka",
                "amr");
        EXTENSIONS = Collections.unmodifiableSet(set);
    }

    private FormatRegistry() {}

    public static Kind kindOf(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            // 64 bytes: enough to reach the codec marker inside the first Ogg page body
            // ("OpusHead" / "Speex" / FLAC-in-Ogg sit ~28 bytes in, past the page header).
            byte[] head = new byte[64];
            int count = readHead(file, head);
            Kind fromMagic = fromMagic(head, count);
            if (fromMagic != null) {
                return fromMagic;
            }
            return fromExtension(extensionOf(file));
        } catch (Throwable t) {
            FmLog.warn("format: cannot inspect " + nameOf(file) + ": " + describe(t));
            return null;
        }
    }

    public static boolean isSupported(File file) {
        return kindOf(file) != null;
    }

    public static Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    static boolean isFlac(File file) {
        if (file == null || !file.isFile()) {
            return false;
        }
        try {
            byte[] head = new byte[4];
            return readHead(file, head) >= 4 && tag(head, 0, "fLaC");
        } catch (IOException e) {
            return false;
        }
    }

    /** Human readable codec name used by probes, e.g. "flac" or "mp3". */
    static String codecLabel(File file) {
        try {
            byte[] head = new byte[64];
            int count = readHead(file, head);
            if (count >= 4) {
                if (tag(head, 0, "fLaC")) {
                    return "flac";
                }
                if (tag(head, 0, "OggS")) {
                    if (containsAscii(head, count, "OpusHead")) {
                        return "opus";
                    }
                    if (containsAscii(head, count, "Speex   ")) {
                        return "speex";
                    }
                    if (containsAscii(head, count, "\u007fFLAC")) {
                        return "ogg-flac";
                    }
                    return "ogg";
                }
                if (tag(head, 0, "ID3")) {
                    return "mp3";
                }
                if (tag(head, 0, ".snd")) {
                    return "au";
                }
                if (tag(head, 0, "RIFF") || tag(head, 0, "RF64")) {
                    return count >= 12 && tag(head, 8, "WAVE") ? "wav" : "riff";
                }
                if (tag(head, 0, "FORM")) {
                    if (count >= 12 && tag(head, 8, "AIFC")) {
                        return "aifc";
                    }
                    return "aiff";
                }
            }
            if (count >= 8 && tag(head, 4, "ftyp")) {
                return "m4a";
            }
            if (count >= 2 && isMpegAudioSync(head)) {
                return "mp3";
            }
        } catch (IOException ignored) {
            // fall through to the extension
        }
        String extension = extensionOf(file);
        return extension.isEmpty() ? "unknown" : extension;
    }

    private static Kind fromMagic(byte[] head, int count) {
        if (count >= 12) {
            if (tag(head, 0, "RIFF") || tag(head, 0, "RF64")) {
                if (tag(head, 8, "WAVE")) {
                    return Kind.PURE_JAVA;
                }
            }
            if (tag(head, 0, "FORM") && (tag(head, 8, "AIFF") || tag(head, 8, "AIFC"))) {
                return Kind.PURE_JAVA;
            }
        }
        if (count >= 4) {
            if (tag(head, 0, "fLaC")) {
                return Kind.PURE_JAVA;
            }
            if (tag(head, 0, "OggS")) {
                // An Ogg container is only playable by the vanilla engine when the payload is
                // Vorbis. Opus, Speex and FLAC-in-Ogg all live in Ogg too, and handing those to
                // Minecraft loads the sound event and then decodes nothing, so route them to
                // ffmpeg instead of mislabelling them as native.
                if (containsAscii(head, count, "OpusHead")
                        || containsAscii(head, count, "Speex   ")
                        || containsAscii(head, count, "\u007fFLAC")) {
                    return Kind.FFMPEG;
                }
                return Kind.NATIVE_OGG;
            }
            // An ID3v2 tag hides the first MPEG frame; javax.sound (mp3spi) can
            // still read it, and ffmpeg is the fallback if it cannot.
            if (tag(head, 0, "ID3")) {
                return Kind.PURE_JAVA;
            }
            if (tag(head, 0, ".snd")) {
                return Kind.PURE_JAVA;
            }
            if (tag(head, 0, "wvpk") || tag(head, 0, "MAC ") || tag(head, 0, "MPCK") || tag(head, 0, "TTA1")) {
                return Kind.FFMPEG;
            }
        }
        if (count >= 8 && tag(head, 4, "ftyp")) {
            return Kind.FFMPEG;
        }
        if (count >= 2 && isMpegAudioSync(head)) {
            return Kind.PURE_JAVA;
        }
        return null;
    }

    private static boolean isMpegAudioSync(byte[] head) {
        if ((head[0] & 0xFF) != 0xFF || (head[1] & 0xE0) != 0xE0) {
            return false;
        }
        // Layer bits 00 belong to ADTS/AAC, not to MPEG audio.
        return (head[1] & 0x06) != 0;
    }

    /** Literal ASCII search inside the sniffed header bytes. */
    private static boolean containsAscii(byte[] data, int length, String needle) {
        int n = needle.length();
        if (n == 0 || length < n) {
            return false;
        }
        outer:
        for (int i = 0; i + n <= length; ++i) {
            for (int j = 0; j < n; ++j) {
                if (data[i + j] != (byte) needle.charAt(j)) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Kind fromExtension(String extension) {
        if (extension.equals("wav")
                || extension.equals("wave")
                || extension.equals("aif")
                || extension.equals("aiff")
                || extension.equals("aifc")
                || extension.equals("au")
                || extension.equals("snd")
                || extension.equals("mp3")
                || extension.equals("flac")) {
            return Kind.PURE_JAVA;
        }
        if (extension.equals("ogg") || extension.equals("oga")) {
            return Kind.NATIVE_OGG;
        }
        if (extension.equals("m4a")
                || extension.equals("m4b")
                || extension.equals("aac")
                || extension.equals("opus")
                || extension.equals("wma")
                || extension.equals("ape")
                || extension.equals("wv")
                || extension.equals("mka")
                || extension.equals("amr")) {
            return Kind.FFMPEG;
        }
        return null;
    }

    private static String extensionOf(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int readHead(File file, byte[] head) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            int total = 0;
            while (total < head.length) {
                int read = in.read(head, total, head.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }

    private static boolean tag(byte[] data, int offset, String ascii) {
        if (offset + ascii.length() > data.length) {
            return false;
        }
        for (int i = 0; i < ascii.length(); i++) {
            if ((data[offset + i] & 0xFF) != ascii.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static String nameOf(File file) {
        return file.getName();
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName()
                : t.getClass().getSimpleName() + ": " + message;
    }
}

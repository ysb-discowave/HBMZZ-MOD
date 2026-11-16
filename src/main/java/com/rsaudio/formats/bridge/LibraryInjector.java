package com.rsaudio.formats.bridge;

import com.rsaudio.formats.FmLog;
import com.rsaudio.formats.FormatsRuntime;
import com.rsaudio.formats.convert.ConvertQueue;
import com.rsaudio.formats.format.FormatRegistry;
import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the rsaudio library populated with the non-OGG files of the audio folder.
 *
 * <p>The only thing this class owns is bookkeeping: which {@code soundName} we injected, which source
 * file it came from, and whether that entry currently points at the source file (PCM route) or at a
 * cached OGG (native route). The library itself is a plain ArrayList that rsaudio clears on every
 * {@code AudioLibrary.rescan()} — so {@link #syncNow()} is written to be idempotent: it re-adds what is
 * missing and re-points entries whose cached ogg appeared after the first injection.</p>
 *
 * <p>Cost: one {@code listFiles()} plus name checks, no file contents. A few hundred files every ten
 * ticks is fine; nothing here blocks on IO besides that directory listing.</p>
 */
public final class LibraryInjector {

    /** How many client ticks between two real synchronisations. */
    private static final int SYNC_INTERVAL_TICKS = 10;

    /** Our soundName -> the file we injected it for (the source file, never the cache ogg). */
    private static final Map<String, File> SOURCE_OF = new LinkedHashMap<String, File>();
    /** Our soundName -> the file the live entry currently points at. */
    private static final Map<String, File> INJECTED_AS = new LinkedHashMap<String, File>();
    /** Our soundName -> the cached ogg reported through {@link #markConverted(File, File)}. */
    private static final Map<String, File> CONVERTED_TO = new LinkedHashMap<String, File>();
    /** Our soundNames that must play through the PCM route right now. */
    private static final Set<String> DIRECT = new HashSet<String>();

    private static int tickCounter;
    private static boolean initialised;

    private LibraryInjector() {
    }

    /** Records the startup state; the first real sync happens from {@link #tick()}. */
    public static synchronized void init() {
        tickCounter = 0;
        initialised = true;
        boolean ready = AudioBridge.available();
        FmLog.info("LibraryInjector init: rsaudio=" + (ready ? "present" : "missing")
                + " audioDir=" + path(AudioBridge.audioDir()));
        if (!ready) {
            FmLog.warnOnce("injector.nomod", "rsaudio is not available; format support stays inactive");
        }
    }

    /** Called from the client tick; the directory is scanned once every ten ticks. */
    public static synchronized void tick() {
        if (!initialised) {
            return;
        }
        if (++tickCounter % SYNC_INTERVAL_TICKS != 0) {
            return;
        }
        syncNow();
    }

    /** Injects or re-points every supported non-ogg file of the audio folder. Idempotent. */
    public static synchronized void syncNow() {
        if (!AudioBridge.available()) {
            FmLog.warnOnce("injector.sync.nomod", "sync skipped: rsaudio is not available");
            return;
        }
        File dir = AudioBridge.audioDir();
        if (dir == null || !dir.isDirectory()) {
            FmLog.warnOnce("injector.sync.nodir", "sync skipped: audio dir is " + path(dir));
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }

        Set<String> present = new HashSet<String>();
        int added = 0;
        for (File file : files) {
            if (file == null) {
                continue;
            }
            String name = file.getName();
            if (!file.isFile() || name.isEmpty() || name.charAt(0) == '.') {
                continue;
            }
            String ext = extensionOf(name);
            if (ext.isEmpty() || "ogg".equals(ext) || !isSupported(ext)) {
                continue;
            }
            String soundName = AudioBridge.sanitize(name);
            // HOTFIX: sanitize() drops every non-ASCII character, so two Chinese-named files
            // collapse onto the same key (both "朋友的酒_DJ版.mp3" and "琵琶曲_DJ版.mp3" became
            // "dj") and the second one silently overwrote the first. Disambiguate with a short
            // hash of the real file name whenever the key is already taken by another file.
            if (soundName != null && !soundName.isEmpty()) {
                File owner = SOURCE_OF.get(soundName);
                if (owner != null && !owner.getName().equals(name)) {
                    soundName = soundName + "_" + shortHash(name);
                }
            }
            if (soundName == null || soundName.isEmpty()) {
                continue;
            }
            present.add(soundName);

            File target = file;
            boolean direct = true;
            File cached = convertedOggOf(file);
            if (cached != null) {
                target = cached;
                direct = false;
            } else if (!FormatsRuntime.directRouteAvailable() && !isOggVorbisExt(extensionOf(name))) {
                // No direct route (MixinBooter absent, or route=NATIVE): nothing intercepts
                // rsaudio's play call, so an entry here would point at a file the vanilla engine
                // cannot decode. Wait for the transcode instead - the next sync re-points it below.
                // An .oga is the exception: it is Ogg Vorbis, the vanilla engine plays it as is.
                continue;
            }

            // HOTFIX: our own bookkeeping decides whether the entry already exists.
            // AudioBridge.hasEntry() reads a field name that does not match every rsaudio build, so
            // relying on it re-injected the same file on every 10-tick sync and littered the library
            // with duplicate entries (the player GUI then showed a scrambled list).
            File previous = INJECTED_AS.get(soundName);
            if (previous != null) {
                if (previous.equals(target)) {
                    SOURCE_OF.put(soundName, file);
                    remember(soundName, direct);
                    continue;
                }
                AudioBridge.remove(soundName);
                INJECTED_AS.remove(soundName);
            }

            AudioBridge.inject(soundName, displayNameFor(file, soundName), target);
            FmLog.log("injected " + soundName + " <- " + name + " (" + (direct ? "pcm" : "ogg") + ")");
            SOURCE_OF.put(soundName, file);
            INJECTED_AS.put(soundName, target);
            remember(soundName, direct);
            if (cached != null) {
                markConverted(file, cached);
            }
            added++;
        }

        int libSize = AudioBridge.entryCount();
        if (present.size() > 0 && libSize >= 0 && libSize < present.size()) {
            // Self-heal: rsaudio's own scan never ran (or ran before the files existed). One forced
            // rescan repopulates the native entries, then the next sync re-adds ours on top.
            FmLog.warn("library has " + libSize + " entries but " + present.size()
                    + " non-ogg files exist; forcing rsaudio to rescan once");
            AudioBridge.rescan();
        }
        FmLog.info("library size after sync: " + AudioBridge.entryCount()
                + ", ours " + INJECTED_AS.size() + ", supported files " + present.size());
        dropVanished(present);
        if (added > 0) {
            FmLog.info("library synced: injected " + added + " file(s), total " + INJECTED_AS.size()
                    + " (direct=" + DIRECT.size() + ")");
        }
    }

    /** Source file of a soundName we injected ({@code null} when it was not ours). */
    public static synchronized File fileOf(String soundName) {
        return soundName == null ? null : SOURCE_OF.get(soundName);
    }

    /** True when this soundName currently has to be played by the PCM route. */
    public static synchronized boolean isDirect(String soundName) {
        return soundName != null && DIRECT.contains(soundName);
    }

    /**
     * Conversion finished for {@code src}. Only bookkeeping happens here, so this is safe on the
     * converter thread; the live entry is re-pointed by the next {@link #syncNow()} on the client thread.
     */
    public static synchronized void markConverted(File src, File ogg) {
        if (src == null || ogg == null) {
            return;
        }
        String soundName = AudioBridge.sanitize(src.getName());
        if (soundName == null || soundName.isEmpty()) {
            return;
        }
        CONVERTED_TO.put(soundName, ogg);
        DIRECT.remove(soundName);
        File injected = INJECTED_AS.get(soundName);
        if (injected != null && !injected.equals(ogg)) {
            INJECTED_AS.remove(soundName);
        }
    }

    /** Number of entries this mod currently contributed to the rsaudio library. */
    public static synchronized int injectedCount() {
        return INJECTED_AS.size();
    }

    // ------------------------------------------------------------------ internals

    /** Cached ogg for {@code src}, or null while the file is not converted yet. */
    private static File convertedOggOf(File src) {
        try {
            if (!ConvertQueue.isConverted(src)) {
                return null;
            }
            File ogg = ConvertQueue.cacheOggOf(src);
            return ogg != null && ogg.isFile() ? ogg : null;
        } catch (Throwable t) {
            FmLog.warnOnce("injector.converted", "ConvertQueue lookup failed (" + FmLog.describe(t) + ")");
            return null;
        }
    }

    private static void remember(String soundName, boolean direct) {
        if (direct) {
            DIRECT.add(soundName);
        } else {
            DIRECT.remove(soundName);
        }
    }

    /** Forgets entries whose source file is gone, and drops them from the library as well. */
    /** Stable 8-hex-digit tag for a file name, used to keep collided soundNames apart. */
    private static String shortHash(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(text.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4 && i < d.length; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Throwable t3) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static void dropVanished(Set<String> present) {
        for (Iterator<Map.Entry<String, File>> it = INJECTED_AS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, File> entry = it.next();
            if (present.contains(entry.getKey())) {
                continue;
            }
            AudioBridge.remove(entry.getKey());
            SOURCE_OF.remove(entry.getKey());
            CONVERTED_TO.remove(entry.getKey());
            DIRECT.remove(entry.getKey());
            it.remove();
        }
    }

    /**
     * Display name = file name without extension; a clash with an existing display name gets the
     * extension appended ("track (mp3)") so the GUI list stays unambiguous.
     */
    private static String displayNameFor(File file, String soundName) {
        String name = file.getName();
        String ext = extensionOf(name);
        String base = stripExtension(name);
        if (base.trim().isEmpty()) {
            base = name;
        }
        if (!AudioBridge.hasDisplayName(base, soundName)) {
            return base;
        }
        String candidate = base + " (" + ext + ")";
        for (int n = 2; AudioBridge.hasDisplayName(candidate, soundName) && n < 100; n++) {
            candidate = base + " (" + ext + " " + n + ")";
        }
        return candidate;
    }

    /**
     * Name-only support check: {@code FormatRegistry.supportedExtensions()} rather than
     * {@code FormatRegistry.isSupported(File)}, because the latter sniffs file magic and this method
     * runs over the whole folder every ten ticks. Content is the decoder's business, not ours.
     */
    private static boolean isSupported(String ext) {
        return FormatRegistry.supportedExtensions().contains(ext);
    }

    /** True for the extension the vanilla engine can stream without any help from this package. */
    private static boolean isOggVorbisExt(String extension) {
        return "ogg".equals(extension) || "oga".equals(extension);
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String path(File file) {
        return file == null ? "-" : file.getPath();
    }
}

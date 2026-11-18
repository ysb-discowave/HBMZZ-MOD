package com.rsaudio.formats;

import java.io.File;
import net.minecraftforge.common.config.Configuration;

/**
 * Forge {@link Configuration} wrapper for {@code config/rsaudiofm.cfg}.
 *
 * <p>Every getter is safe to call before {@link #init(File)}: the fields start at their documented
 * defaults, so a broken or missing config file degrades to "AUTO route, no explicit ffmpeg" instead of
 * failing the mod load.</p>
 */
public final class FmConfig {

    /** How a non-OGG audio file reaches the speakers. */
    public enum Route {
        /** NATIVE when ffmpeg was found, PCM otherwise. */
        AUTO,
        /** Transcode to OGG Vorbis in the cache and let rsaudio/the vanilla engine play it. */
        NATIVE,
        /** Decode to PCM and push it straight to the sound card. */
        PCM
    }

    private static final String CATEGORY = "general";
    private static final String[] ROUTE_VALUES = {"AUTO", "NATIVE", "PCM"};

    private static final Route DEFAULT_ROUTE = Route.AUTO;
    private static final boolean DEFAULT_AUTO_CONVERT = true;
    private static final String DEFAULT_FFMPEG_PATH = "";
    private static final int DEFAULT_OGG_QUALITY = 5;
    private static final int DEFAULT_CACHE_LIMIT_MB = 512;
    private static final int DEFAULT_PCM_MAX_CONCURRENT = 2;
    private static final boolean DEFAULT_AUTO_RELOAD = false;

    private static Configuration config;

    private static Route route = DEFAULT_ROUTE;
    private static boolean autoConvert = DEFAULT_AUTO_CONVERT;
    private static String ffmpegPath = DEFAULT_FFMPEG_PATH;
    private static int oggQuality = DEFAULT_OGG_QUALITY;
    private static int cacheLimitMb = DEFAULT_CACHE_LIMIT_MB;
    private static int pcmMaxConcurrent = DEFAULT_PCM_MAX_CONCURRENT;
    private static boolean autoReload = DEFAULT_AUTO_RELOAD;

    private FmConfig() {
    }

    /** Loads (and creates on first run) {@code rsaudiofm.cfg} inside the mod configuration directory. */
    public static void init(File configDir) {
        try {
            if (configDir == null) {
                FmLog.warnOnce("config.nodir", "no configuration directory; using built-in defaults");
                return;
            }
            if (!configDir.isDirectory() && !configDir.mkdirs() && !configDir.isDirectory()) {
                FmLog.warnOnce("config.mkdir", "cannot create " + configDir.getPath() + "; using built-in defaults");
                return;
            }
            Configuration cfg = new Configuration(new File(configDir, "rsaudiofm.cfg"));
            cfg.load();
            config = cfg;

            route = parseRoute(cfg.getString("route", CATEGORY, DEFAULT_ROUTE.name(),
                    "AUTO = NATIVE when ffmpeg is found, PCM otherwise. "
                            + "NATIVE = transcode to ogg and play through rsaudio. "
                            + "PCM = decode and push straight to the sound card.",
                    ROUTE_VALUES));
            autoConvert = cfg.getBoolean("autoConvert", CATEGORY, DEFAULT_AUTO_CONVERT,
                    "Start transcoding the audio folder in the background after launch.");
            ffmpegPath = trim(cfg.getString("ffmpegPath", CATEGORY, DEFAULT_FFMPEG_PATH,
                    "Explicit ffmpeg(.exe) path. Empty = search mods dir, game dir and PATH."));
            oggQuality = clamp(cfg.getInt("oggQuality", CATEGORY, DEFAULT_OGG_QUALITY, 3, 8,
                    "libvorbis VBR quality used for the ogg cache (3..8)."), 3, 8);
            cacheLimitMb = cfg.getInt("cacheLimitMb", CATEGORY, DEFAULT_CACHE_LIMIT_MB, 64, 40960,
                    "Upper bound of the ogg cache folder in MB (pruned least-recently-used first).");
            pcmMaxConcurrent = clamp(cfg.getInt("pcmMaxConcurrent", CATEGORY, DEFAULT_PCM_MAX_CONCURRENT, 1, 4,
                    "Maximum simultaneous PCM playback sessions (1..4)."), 1, 4);
            autoReload = cfg.getBoolean("autoReload", CATEGORY, DEFAULT_AUTO_RELOAD,
                    "Reload the rsaudio resource pack once a transcoding batch finishes.");

            saveIfChanged();
            FmLog.info("config: route=" + route + " autoConvert=" + autoConvert
                    + " ffmpeg=\"" + ffmpegPath + "\" q=" + oggQuality
                    + " cache=" + cacheLimitMb + "MB pcm=" + pcmMaxConcurrent
                    + " autoReload=" + autoReload);
        } catch (Throwable t) {
            FmLog.warnOnce("config.init", "FmConfig.init failed (" + FmLog.describe(t) + "); using built-in defaults");
        }
    }

    public static Route route() {
        return route;
    }

    public static boolean autoConvert() {
        return autoConvert;
    }

    /** Configured ffmpeg path, or {@code ""} when it should be auto-discovered. */
    public static String ffmpegPath() {
        return ffmpegPath;
    }

    public static int oggQuality() {
        return oggQuality;
    }

    public static int cacheLimitMb() {
        return cacheLimitMb;
    }

    public static int pcmMaxConcurrent() {
        return pcmMaxConcurrent;
    }

    public static boolean autoReload() {
        return autoReload;
    }

    /** Writes the file back only when a value actually changed (e.g. a new option was added). */
    public static void saveIfChanged() {
        Configuration cfg = config;
        if (cfg == null) {
            return;
        }
        try {
            if (cfg.hasChanged()) {
                cfg.save();
            }
        } catch (Throwable t) {
            FmLog.warnOnce("config.save", "cannot save rsaudiofm.cfg (" + FmLog.describe(t) + ")");
        }
    }

    private static Route parseRoute(String raw) {
        String value = trim(raw);
        for (Route candidate : Route.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        if (!value.isEmpty()) {
            FmLog.warnOnce("config.route." + value, "unknown route \"" + value + "\"; falling back to AUTO");
        }
        return DEFAULT_ROUTE;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}

package com.rsaudio.formats;

import com.rsaudio.formats.bridge.AudioBridge;
import com.rsaudio.formats.bridge.LibraryInjector;
import com.rsaudio.formats.convert.ConvertQueue;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Client-side wiring of the multi-format audio support, now a package inside HBMZZ instead of the
 * standalone {@code rsaudiofm} addon.
 *
 * <p>What changed compared to the addon: there is no {@code @Mod} and no {@code dependencies} /
 * {@code requireHost()} pre-flight any more - the code ships in the host jar, so "is the host
 * installed" is not a question a user can get wrong. What remains is an <em>internal integrity</em>
 * check: the rsaudio container classes must be in this same jar, and if a build ever drops them the
 * format support switches itself off with one loud line instead of failing somewhere later with a
 * NoClassDefFoundError.</p>
 *
 * <p><b>没有 MixinBooter 这一层了。</b> 这个包原本是独立插件 {@code rsaudiofm}，宿主在另一枚 jar
 * 里、改不了，只能靠 Mixin 注入 {@code ClientSoundPlayer} 的播放入口。合并进 hbmzz 之后宿主代码
 * 就在同一枚 jar 里，于是那把钩子直接写进了 {@code ClientSoundPlayer} 自己的方法体（五个入口：
 * play / stop / stopAll / preview / stopPreview），玩家不必为了听 mp3 再装一个 mixin 运行时。
 * {@link FormatsRuntime} 仍然是唯一的事实来源：直出路线是否可用只由配置的 {@code route} 决定。</p>
 *
 * <p>Every startup step and every per-tick step is individually wrapped: a broken neighbour module
 * must degrade to "no format support", never to a crashed tick. Repeated failures of the same step
 * are logged at most once per minute.</p>
 */
public final class FormatsBootstrap {

    /** Log tag, also the log4j logger name of {@link FmLog}. */
    public static final String TAG = "rsaudio-formats";

    /**
     * On-disk folder under {@code config/}. Unchanged on purpose: this is where an existing
     * installation already keeps the transcoded ogg cache and {@code rsaudiofm.cfg}, and losing them
     * to a rename would mean re-transcoding the whole audio folder.
     */
    public static final String CONFIG_DIR = "rsaudiofm";

    /** The mod id of the mod this code used to depend on; only used for log wording. */
    public static final String HOST_MODID = "hbmzz";

    /** A class that only exists when this jar really carries the rsaudio container. */
    private static final String HOST_ANCHOR_CLASS = "com.rsaudio.client.ClientSoundPlayer";


    private static final long FAILURE_LOG_INTERVAL_MS = 60000L;

    /** {@code config/rsaudiofm/cache} - the transcode target directory. */
    private static File cacheDir;
    /** Last log time per failing step, plus how many repetitions were swallowed. */
    private static final Map<String, long[]> FAILURES = new HashMap<String, long[]>();

    private static boolean initialised;
    private static boolean usable;
    private static boolean firstClientTick;
    private static boolean announced;

    private FormatsBootstrap() {
    }

    /**
     * Called once from the client proxy's preInit. Idempotent: a second call returns immediately, so
     * a caller that runs the hook twice cannot end up with two conversion queues.
     */
    public static synchronized void init(FMLPreInitializationEvent event) {
        try {
            File cfgDir = event.getModConfigurationDirectory();
            DocsNotice.ensure(cfgDir == null ? null : cfgDir.getParentFile());
            PackHousekeeping.schedule(cfgDir == null ? null : cfgDir.getParentFile());
        } catch (Throwable ignored) {
            // 说明文件写不进去不影响任何功能
        }

        if (initialised) {
            return;
        }
        initialised = true;

        if (!checkIntegrity()) {
            return;
        }

        File configDir = null;
        try {
            configDir = event.getModConfigurationDirectory();
        } catch (Throwable t) {
            FmLog.warnOnce("preinit.configdir", "no configuration directory (" + FmLog.describe(t) + ")");
        }

        try {
            FmConfig.init(configDir);
        } catch (Throwable t) {
            FmLog.error("FmConfig.init failed", t);
        }

        // 直出（PCM）路线能不能用，现在只看配置：钩子已经写进 rsaudio 自己的
        // ClientSoundPlayer（play / stop / stopAll / preview / stopPreview 五个入口），
        // 不再需要 MixinBooter —— 宿主代码就在同一枚 jar 里，让玩家为了 mp3 再装一个
        // mixin 运行时是没有道理的。原先的 Addon 形态必须靠 mixin 注入，是因为它改不了宿主。
        boolean direct;
        if (FmConfig.route() == FmConfig.Route.NATIVE) {
            direct = false;
            FmLog.info("config route=NATIVE：直出路线按配置停用，仅转码路线");
        } else {
            direct = true;
            FmLog.info("直出（PCM）与转码两条路线均可用");
        }
        FormatsRuntime.setDirectRouteAvailable(direct);

        try {
            boolean ready = AudioBridge.available();
            FmLog.info("rsaudio bridge: " + (ready ? "available" : "not available"));
        } catch (Throwable t) {
            FmLog.error("AudioBridge probe failed", t);
        }

        try {
            LibraryInjector.init();
        } catch (Throwable t) {
            FmLog.error("LibraryInjector.init failed", t);
        }

        try {
            cacheDir = new File(new File(configDir == null ? new File(".") : configDir, CONFIG_DIR), "cache");
            ConvertQueue.init(AudioBridge.audioDir(), cacheDir);
        } catch (Throwable t) {
            FmLog.error("ConvertQueue.init failed", t);
        }

        if (direct) {
            try {
                com.rsaudio.formats.play.PlaybackManager.init();
            } catch (Throwable t) {
                FmLog.error("PlaybackManager.init failed", t);
            }
        }

        // Registered here rather than through @EventBusSubscriber: this package lives in the host's
        // own mod container, so an explicit registration keeps the single hook point auditable.
        try {
            MinecraftForge.EVENT_BUS.register(new TickHandler());
        } catch (Throwable t) {
            FmLog.error("cannot register the client tick handler", t);
            return;
        }

        usable = true;
        try {
            FmLog.info("ready: route=" + FmConfig.route().name()
                    + ", direct=" + (direct ? "on" : "off")
                    + ", ffmpeg=\"" + FmConfig.ffmpegPath() + "\""
                    + ", audioDir=" + AudioBridge.audioDir()
                    + ", cacheDir=" + cacheDir);
        } catch (Throwable t) {
            FmLog.warnOnce("init.ready", "ready line failed (" + FmLog.describe(t) + ")");
        }
    }

    /**
     * Internal integrity check: the rsaudio container has to be in this very jar.
     *
     * <p>Looked up by name and never as a type, so a jar that lost the container still loads far
     * enough to say so. {@code false} switches the whole package off - there is nothing to attach to,
     * and a half-running transcode queue would only produce noise.</p>
     */
    private static boolean checkIntegrity() {
        try {
            Class.forName(HOST_ANCHOR_CLASS, false, FormatsBootstrap.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            FmLog.error("internal integrity check failed: " + HOST_ANCHOR_CLASS
                    + " is missing from this jar; multi-format audio is disabled (" + FmLog.describe(t) + ")");
            return false;
        }
    }


    // ------------------------------------------------------------------ client tick

    static void onClientTick() {
        if (!usable) {
            return;
        }
        Minecraft mc;
        try {
            mc = Minecraft.getMinecraft();
        } catch (Throwable t) {
            return;
        }
        if (mc == null) {
            return;
        }

        try {
            com.rsaudio.formats.dev.SelfTest.tick(mc);
        } catch (Throwable t) {
            logFailure("selftest.tick", t);
        }

        if (!firstClientTick) {
            firstClientTick = true;
            // Starting the scan here (instead of during preInit) keeps the conversion thread out of
            // the mod loading phase; ConvertQueue is the only owner of that thread.
            try {
                com.rsaudio.formats.convert.FfmpegFinder.setGameHint(mc.mcDataDir);
                com.rsaudio.formats.convert.FfmpegFinder.reset();
                ConvertQueue.scanAndEnqueue();
            } catch (Throwable t) {
                logFailure("convert.scan", t);
            }
        }

        boolean inWorld = mc.world != null;

        if (inWorld && !announced) {
            announced = true;
            if (!AudioBridge.available()) {
                FmLog.chatOnce("bridge.missing",
                        "Redstone Audio (rsaudio) not found — extra audio formats stay disabled.");
            }
        }

        if (inWorld) {
            try {
                LibraryInjector.tick();
            } catch (Throwable t) {
                logFailure("injector.tick", t);
            }
        }

        try {
            ConvertQueue.tick();
        } catch (Throwable t) {
            logFailure("convert.tick", t);
        }

        if (inWorld && FormatsRuntime.directRouteAvailable()) {
            try {
                com.rsaudio.formats.play.PlaybackManager.tick(mc);
            } catch (Throwable t) {
                logFailure("playback.tick", t);
            }
        }

        if (inWorld) {
            boolean finished;
            try {
                finished = ConvertQueue.consumeJustFinishedBatch();
            } catch (Throwable t) {
                logFailure("convert.batch", t);
                finished = false;
            }
            if (finished) {
                afterConvertBatch();
            }
        }
    }

    /**
     * Runs exactly once per finished transcoding batch: re-point the library entries at the cached oggs,
     * then let rsaudio rebuild and reload its resource pack. The reload is a full client resource reload,
     * which is why it is driven from this single place.
     */
    private static void afterConvertBatch() {
        try {
            LibraryInjector.syncNow();
        } catch (Throwable t) {
            logFailure("injector.sync", t);
        }
        // HOTFIX: never regenerate rsaudio's sound pack from here.
        // generate()+enableAndReload() rebuild sounds.json from scratch and a full resource reload; 
        // in the field that left the player GUI listing only our own entries while the user's
        // own ogg library disappeared from it. Converted files are played through the PCM route
        // instead, which needs no pack registration at all.
        FmLog.info("transcode batch finished; sound pack left untouched (playback via PCM)");
    }

    /** Same failure within a minute is logged once, with the number of swallowed repetitions. */
    private static void logFailure(String step, Throwable t) {
        long now = System.currentTimeMillis();
        long[] record = FAILURES.get(step);
        if (record == null) {
            record = new long[]{0L, 0L};
            FAILURES.put(step, record);
        }
        if (now - record[0] < FAILURE_LOG_INTERVAL_MS) {
            record[1]++;
            return;
        }
        String repeats = record[1] > 0 ? " (" + record[1] + " repeats suppressed)" : "";
        record[0] = now;
        record[1] = 0L;
        FmLog.warn(step + " failed" + repeats + ": " + FmLog.describe(t));
    }

    /** Client-only tick handler; registered by {@link #init} on the Forge event bus. */
    private static final class TickHandler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            try {
                FormatsBootstrap.onClientTick();
            } catch (Throwable t) {
                FmLog.warnOnce("tick.outer", "client tick failed: " + FmLog.describe(t));
            }
        }
    }
}

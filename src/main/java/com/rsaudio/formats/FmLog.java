package com.rsaudio.formats;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.TextComponentString;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The only logging entry point of the mod.
 *
 * <p>Everything goes to the FML log (log4j logger named "rsaudio-formats"); {@link #chatOnce(String)} adds a
 * single in-game line per key so a player notices "ffmpeg missing" style problems without digging in
 * the log. Both the per-key de-duplication and a minimum interval between chat lines keep a broken
 * tick from spamming the chat.</p>
 */
public final class FmLog {

    private static final String PREFIX = "[rsaudio-formats] ";
    private static final long CHAT_MIN_INTERVAL_MS = 3000L;

    private static final Logger LOG = LogManager.getLogger("rsaudio-formats");

    /** Keys already written to the log once. */
    private static final Set<String> WARNED_ONCE = ConcurrentHashMap.newKeySet();
    /** Keys already shown to the player. */
    private static final Set<String> CHATTED = ConcurrentHashMap.newKeySet();
    private static final AtomicLong LAST_CHAT_AT = new AtomicLong(0L);

    private FmLog() {
    }

    public static void log(String message) {
        info(message);
    }

    public static void info(String message) {
        try {
            LOG.info(PREFIX + message);
        } catch (Throwable ignored) {
            // Logging must never break a tick.
        }
    }

    public static void warn(String message) {
        try {
            LOG.warn(PREFIX + message);
        } catch (Throwable ignored) {
        }
    }

    public static void error(String message) {
        try {
            LOG.error(PREFIX + message);
        } catch (Throwable ignored) {
        }
    }

    public static void error(String message, Throwable t) {
        error(message + (t == null ? "" : ": " + describe(t)));
    }

    /** Logs the message the first time this key is seen; later calls with the same key are dropped. */
    public static void warnOnce(String key, String message) {
        if (key == null) {
            warn(message);
            return;
        }
        if (!WARNED_ONCE.add(key)) {
            return;
        }
        warn(message);
    }

    /** "ExceptionClass: message" — the form used in every degraded-path log line. */
    public static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String message = t.getMessage();
        return t.getClass().getName() + (message == null ? "" : ": " + message);
    }

    /** One in-game line, keyed by the message itself. */
    public static void chatOnce(String message) {
        chatOnce(message, message);
    }

    /**
     * Shows {@code message} in chat once per {@code key}. Safe from any thread: the actual send runs on
     * the client thread. Messages closer together than {@value #CHAT_MIN_INTERVAL_MS} ms are dropped
     * (the key is not marked, so they get another chance on a later tick).
     */
    public static void chatOnce(String key, final String message) {
        if (key == null || message == null || message.isEmpty()) {
            return;
        }
        if (CHATTED.contains(key)) {
            return;
        }
        long now = System.currentTimeMillis();
        long last = LAST_CHAT_AT.get();
        if (now - last < CHAT_MIN_INTERVAL_MS) {
            return;
        }
        if (!LAST_CHAT_AT.compareAndSet(last, now)) {
            return;
        }
        try {
            final Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (mc.player != null) {
                            mc.player.sendMessage(new TextComponentString(PREFIX + message));
                            // Only a delivered line counts as "shown"; before the first world the
                            // message stays pending so it can go out later.
                            CHATTED.add(key);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            warnOnce("chatOnce." + key, "chat unavailable (" + describe(t) + "); message was: " + message);
        }
    }
}

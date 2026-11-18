package com.rsaudio.formats.play;

import com.rsaudio.formats.FmConfig;
import com.rsaudio.formats.FmLog;
import com.rsaudio.formats.bridge.AudioBridge;
import com.rsaudio.formats.bridge.LibraryInjector;
import com.rsaudio.formats.format.PcmSource;
import com.rsaudio.formats.format.Sources;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * The "direct route": decodes a non-OGG track to PCM and pushes it straight to the sound card,
 * bypassing the vanilla sound engine (and therefore the resource pack) entirely.
 *
 * <p>One {@link Session} owns one {@link PcmPlayer}. Everything that mutates playlist state runs on
 * the client thread ({@link #play}, {@link #stop}, {@link #tick}); the audio writer thread only ever
 * runs {@code PcmPlayer.onFinish}, which is the single owner of decoder release. That split is what
 * keeps file handles from leaking: a session is never closed by two threads.</p>
 *
 * <p>All public entry points swallow their own failures - this class is called from inside a mixin
 * injected into another mod's playback path, so it must never throw into that path.</p>
 */
public final class PlaybackManager {

    /** Distance at which a block is heard at full volume. */
    private static final float NEAR_DISTANCE = 4.0f;
    /** Distance at which a block becomes inaudible (and the session is dropped). */
    private static final float FAR_DISTANCE = 24.0f;
    /** Hard pan limit: a mono track never snaps fully to one ear. */
    private static final float MAX_PAN = 0.8f;

    /** Live block playback, keyed by block position (one sound per block, like rsaudio). */
    private static final Map<BlockPos, Session> SESSIONS = new ConcurrentHashMap<BlockPos, Session>();

    /** The GUI preview (no position, no distance falloff), or null. */
    private static volatile Session previewSession;

    /** World the sessions belong to; a change (dimension switch, world load) drops them all. */
    private static World lastWorld;

    private PlaybackManager() {
    }

    /** Called once from the mod's preInit; safe to call more than once. */
    public static void init() {
        try {
            FmLog.info("pcm route ready (max " + FmConfig.pcmMaxConcurrent() + " concurrent sessions)");
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.init", "PlaybackManager.init failed (" + FmLog.describe(t) + ")");
        }
    }

    /**
     * Takes over one block playback.
     *
     * @return true when the PCM route owns this sound and the vanilla engine must not be involved.
     *         False hands the call back to rsaudio untouched.
     */
    public static boolean play(BlockPos pos, String soundName, boolean loop, float blockVolume) {
        try {
            if (pos == null || soundName == null || soundName.isEmpty()) {
                return false;
            }
            File file = sourceFor(soundName);
            if (file == null) {
                return false;
            }
            int limit = FmConfig.pcmMaxConcurrent();
            if (SESSIONS.size() >= limit) {
                // Hand the sound back instead of dropping it: rsaudio's own failure path then tells the
                // server the playback is over, which keeps a redstone player from hanging forever.
                FmLog.warnOnce("pcm.max", "pcmMaxConcurrent=" + limit + " reached; \"" + soundName
                        + "\" is left to the vanilla engine");
                return false;
            }

            BlockPos key = pos.toImmutable();
            stop(key);

            Session session = new Session(key, blockIdAt(key), soundName, file, loop, blockVolume);
            if (!start(session)) {
                FmLog.warnOnce("pcm.start." + soundName, "cannot start PCM playback for \"" + soundName
                        + "\"; the vanilla engine gets the call instead");
                return false;
            }
            SESSIONS.put(key, session);
            FmLog.log("direct play " + soundName + " at " + key + " (loop=" + loop
                    + ", volume=" + blockVolume + ")");
            return true;
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.play", "play(\"" + soundName + "\") failed: " + FmLog.describe(t));
            return false;
        }
    }

    /** Registry name of the block at {@code pos}, or null when it cannot be resolved. */
    private static String blockIdAt(BlockPos pos) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
            if (mc == null || mc.world == null || pos == null) {
                return null;
            }
            net.minecraft.block.Block block = mc.world.getBlockState(pos).getBlock();
            return block == null ? null : String.valueOf(block.getRegistryName());
        } catch (Throwable t2) {
            return null;
        }
    }

    /** Stops the block session at {@code pos} (no-op when the PCM route does not own it). */
    public static void stop(BlockPos pos) {
        try {
            if (pos == null) {
                return;
            }
            Session session = SESSIONS.get(pos);
            if (session != null) {
                // Mark it stopped before unlisting it: the writer thread must see "stopped" before it
                // sees the session gone, otherwise a natural end could still send a finished packet.
                session.stopNow();
                SESSIONS.remove(pos, session);
            }
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.stop", "stop failed: " + FmLog.describe(t));
        }
    }

    /** Stops every block session. The GUI preview is left alone (rsaudio treats them separately). */
    public static void stopAll() {
        try {
            for (Map.Entry<BlockPos, Session> entry : SESSIONS.entrySet()) {
                Session session = entry.getValue();
                session.stopNow();
                SESSIONS.remove(entry.getKey(), session);
            }
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.stopall", "stopAll failed: " + FmLog.describe(t));
        }
    }

    /**
     * Takes over the GUI audition (no block position, so no distance falloff and centre pan).
     *
     * @return true when the PCM route owns the preview.
     */
    public static boolean preview(String soundName, float volume) {
        try {
            if (soundName == null || soundName.isEmpty()) {
                return false;
            }
            File file = sourceFor(soundName);
            if (file == null) {
                return false;
            }
            stopPreview();
            Session session = new Session(null, null, soundName, file, false, volume);
            if (!start(session)) {
                FmLog.warnOnce("pcm.preview." + soundName, "cannot preview \"" + soundName + "\" through PCM");
                return false;
            }
            previewSession = session;
            FmLog.log("direct preview " + soundName);
            return true;
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.preview", "preview(\"" + soundName + "\") failed: " + FmLog.describe(t));
            return false;
        }
    }

    /** Stops the GUI preview (no-op when the PCM route does not own it). */
    public static void stopPreview() {
        try {
            Session session = previewSession;
            previewSession = null;
            retire(session);
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.stoppreview", "stopPreview failed: " + FmLog.describe(t));
        }
    }

    /** Per-tick volume/pan update, distance cut-off and world-change cleanup. Client thread only. */
    public static void tick(Minecraft mc) {
        if (mc == null) {
            return;
        }
        try {
            World world = mc.world;
            if (world == null) {
                // Back at the main menu: nothing may keep playing.
                if (!SESSIONS.isEmpty()) {
                    stopAll();
                }
                if (previewSession != null) {
                    stopPreview();
                }
                lastWorld = null;
                return;
            }
            if (lastWorld != null && lastWorld != world) {
                // Dimension change or a different world: the old sessions are stale.
                stopAll();
            }
            lastWorld = world;

            EntityPlayer player = mc.player;
            if (player == null) {
                stopAll();
                return;
            }
            // A broken/removed player block must silence its session: rsaudio does not tell us when the
            // block is gone, so the session used to keep streaming after the block was destroyed.
            java.util.List<BlockPos> vanished = null;
            for (Session session : SESSIONS.values()) {
                if (session.pos == null || session.blockId == null) {
                    continue;
                }
                String now = blockIdAt(session.pos);
                if (!session.blockId.equals(now)) {
                    if (vanished == null) {
                        vanished = new java.util.ArrayList<BlockPos>();
                    }
                    vanished.add(session.pos);
                }
            }
            if (vanished != null) {
                for (BlockPos gone : vanished) {
                    FmLog.log("player block gone at " + gone + ", stopping playback");
                    stop(gone);
                }
            }
            for (Session session : SESSIONS.values()) {
                updateMix(session, mc, player);
            }
            Session preview = previewSession;
            if (preview != null) {
                updateMix(preview, mc, player);
            }
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.tick", "tick failed: " + FmLog.describe(t));
        }
    }

    /** Stops everything and forgets the current world. Safe to call twice. */
    public static void shutdown() {
        stopAll();
        stopPreview();
        lastWorld = null;
    }

    /** True while the PCM route is playing something for {@code pos}. */
    public static boolean isActive(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        Session session = SESSIONS.get(pos);
        return session != null && session.isLive();
    }

    /** Number of live block sessions (the GUI preview is not counted against the concurrency limit). */
    public static int activeCount() {
        return SESSIONS.size();
    }

    // ------------------------------------------------------------------ decision

    /**
     * The file the PCM route would play for {@code soundName}, or null when this sound must stay with
     * the vanilla engine.
     *
     * <p>Two ways in: the library says the entry is a native (unconverted) file - that is the normal
     * case - or the resource pack has no entry for it yet, in which case a PCM fallback beats silence.
     * The fallback deliberately requires a decodable file: {@link Sources#open} returns null for Ogg
     * Vorbis (the native path owns it), so taking over an unconverted ogg would cancel vanilla and
     * play nothing.</p>
     */
    private static File sourceFor(String soundName) {
        File file;
        boolean direct;
        try {
            direct = LibraryInjector.isDirect(soundName);
            file = LibraryInjector.fileOf(soundName);
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.lookup", "library lookup failed (" + FmLog.describe(t) + ")");
            return null;
        }
        if (file == null || !file.isFile()) {
            return null;
        }
        if (!direct) {
            if (AudioBridge.nativeReady(soundName)) {
                return null;
            }
            if (isOgg(file)) {
                return null;
            }
            FmLog.warnOnce("pcm.fallback." + soundName,
                    "\"" + soundName + "\" is not in the sound pack yet; using the PCM route as a fallback");
        }
        return file;
    }

    private static boolean isOgg(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".ogg") || name.endsWith(".oga");
    }

    // ------------------------------------------------------------------ session lifecycle

    /** Opens the decoder and starts the writer. On false the caller must not keep the session. */
    private static boolean start(Session session) {
        PcmSource source;
        try {
            source = Sources.open(session.file);
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.open." + session.soundName,
                    "decode failed for \"" + session.soundName + "\": " + FmLog.describe(t));
            return false;
        }
        if (source == null) {
            return false;
        }
        return begin(session, new SourceStream(source)) && session.isLive();
    }

    /** Wires a fresh stream to a fresh player for {@code session}; used for start and for loop restarts. */
    private static boolean begin(final Session session, SourceStream stream) {
        AudioFormat format;
        try {
            format = stream.format();
        } catch (Throwable t) {
            closeQuietly(stream);
            return false;
        }
        if (format == null) {
            closeQuietly(stream);
            return false;
        }

        PcmPlayer player = new PcmPlayer();
        player.setOnFinish(new Runnable() {

            @Override
            public void run() {
                onPlayerFinished(session);
            }
        });
        session.stream = stream;
        session.player = player;

        Minecraft mc = safeMinecraft();
        EntityPlayer owner = mc == null ? null : mc.player;
        float gain = gainOf(session, mc, owner);
        float pan = session.pos == null || owner == null ? 0.0f : panOf(owner, session.pos);

        // loop=false on purpose: our SourceStream cannot mark/reset (an 8 MiB mark buffer per track is
        // not worth it), and PcmPlayer would only log that once per pass. PlaybackManager restarts the
        // session from onPlayerFinished instead - the documented "re-starting is always correct" path.
        boolean started;
        try {
            started = player.start(format, stream, gain, pan, false);
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.play.start." + session.soundName,
                    "player.start failed: " + FmLog.describe(t));
            started = false;
        }
        if (!started) {
            closeQuietly(stream);
            return false;
        }
        if (session.stopped) {
            // stop() landed between opening the line and this point.
            player.stop();
            return false;
        }
        return true;
    }

    /**
     * Runs on the audio writer thread exactly once per pass. This is the only place a decoder is
     * closed, which is why no read can ever race a close.
     */
    private static void onPlayerFinished(Session session) {
        try {
            if (session.stopped) {
                release(session);
                return;
            }
            if (session.loop && SESSIONS.get(session.pos) == session) {
                closeQuietly(session.stream);
                session.stream = null;
                if (restart(session)) {
                    return;
                }
            }
            release(session);
            notifyFinished(session.pos);
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.finish", "finish handling failed: " + FmLog.describe(t));
            release(session);
        }
    }

    /** Re-opens the file for one more loop pass. Writer thread only. */
    private static boolean restart(Session session) {
        if (session.stopped) {
            return false;
        }
        PcmSource source;
        try {
            source = Sources.open(session.file);
        } catch (Throwable t) {
            return false;
        }
        if (source == null) {
            return false;
        }
        if (session.stopped || SESSIONS.get(session.pos) != session) {
            closeQuietly(source);
            return false;
        }
        return begin(session, new SourceStream(source));
    }

    /**
     * Idempotent final teardown. Only ever called from the audio writer thread, i.e. at a point where
     * nothing can be reading the decoder any more - that is what makes closing it here race-free.
     */
    private static void release(Session session) {
        if (!session.released.compareAndSet(false, true)) {
            return;
        }
        SourceStream stream = session.stream;
        session.stream = null;
        closeQuietly(stream);
        detach(session);
    }

    /** Main-thread teardown: silence it first, then drop it from the tables. */
    private static void retire(Session session) {
        if (session == null) {
            return;
        }
        session.stopNow();
        detach(session);
    }

    private static void detach(Session session) {
        if (session.pos == null) {
            if (previewSession == session) {
                previewSession = null;
            }
        } else {
            SESSIONS.remove(session.pos, session);
        }
    }

    /** Tells the server the playback ended; the packet must go out on the client thread. */
    private static void notifyFinished(final BlockPos pos) {
        if (pos == null) {
            return;
        }
        Minecraft mc = safeMinecraft();
        if (mc == null) {
            return;
        }
        try {
            mc.addScheduledTask(new Runnable() {

                @Override
                public void run() {
                    try {
                        AudioBridge.sendFinished(pos);
                    } catch (Throwable t) {
                        FmLog.warnOnce("pcm.finished.send",
                                "sendFinished failed: " + FmLog.describe(t));
                    }
                }
            });
        } catch (Throwable t) {
            FmLog.warnOnce("pcm.finished.queue", "cannot queue sendFinished: " + FmLog.describe(t));
        }
    }

    // ------------------------------------------------------------------ mix

    /** Applies master volume, block volume, distance falloff and pan; drops the session when silent. */
    private static void updateMix(Session session, Minecraft mc, EntityPlayer player) {
        if (!session.isLive()) {
            return;
        }
        PcmPlayer player2 = session.player;
        if (player2 == null) {
            return;
        }
        float master = masterVolume(mc);
        if (session.pos == null) {
            player2.setGain(clamp01(master * session.blockVolume));
            player2.setPan(0.0f);
            return;
        }
        float atten = distanceAtten(distanceTo(player, session.pos));
        if (atten <= 0.0f) {
            // Out of earshot: keep the decoder and the line for nothing.
            retire(session);
            return;
        }
        player2.setGain(clamp01(master * session.blockVolume * atten));
        player2.setPan(panOf(player, session.pos));
    }

    private static float gainOf(Session session, Minecraft mc, EntityPlayer player) {
        float master = masterVolume(mc);
        if (session.pos == null || player == null) {
            return clamp01(master * session.blockVolume);
        }
        return clamp01(master * session.blockVolume * distanceAtten(distanceTo(player, session.pos)));
    }

    /** {@code Minecraft.getMinecraft()} never throws in practice, but this runs inside a mixin. */
    private static Minecraft safeMinecraft() {
        try {
            return Minecraft.getMinecraft();
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@code GameSettings.getSoundLevel(SoundCategory)} - the "Records" slider, 0..1. */
    private static float masterVolume(Minecraft mc) {
        if (mc == null || mc.gameSettings == null) {
            return 1.0f;
        }
        try {
            float volume = mc.gameSettings.getSoundLevel(SoundCategory.RECORDS);
            return volume >= 0.0f && volume <= 1.0f ? volume : clamp01(volume);
        } catch (Throwable t) {
            return 1.0f;
        }
    }

    /** Eye position to block centre. */
    private static double distanceTo(EntityPlayer player, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - player.posX;
        double dy = (pos.getY() + 0.5) - (player.posY + player.getEyeHeight());
        double dz = (pos.getZ() + 0.5) - player.posZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 1.0 up to 4 blocks, linear to 0 at 24 blocks, 0 beyond. */
    private static float distanceAtten(double distance) {
        if (distance <= NEAR_DISTANCE) {
            return 1.0f;
        }
        if (distance >= FAR_DISTANCE) {
            return 0.0f;
        }
        return (float) ((FAR_DISTANCE - distance) / (FAR_DISTANCE - NEAR_DISTANCE));
    }

    /**
     * Left/right component of the block relative to where the player looks, scaled to +-0.8.
     *
     * <p>Minecraft yaw 0 faces +Z; the camera's right vector is {@code (-cos yaw, -sin yaw)} in
     * (x, z), which is the horizontal cross product of the view direction with "up". Only the
     * horizontal part is used, so something behind the player is not panned to a side.</p>
     */
    private static float panOf(EntityPlayer player, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - player.posX;
        double dz = (pos.getZ() + 0.5) - player.posZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 0.001) {
            return 0.0f;
        }
        double yaw = Math.toRadians(player.rotationYaw);
        double rightX = -Math.cos(yaw);
        double rightZ = -Math.sin(yaw);
        double pan = (dx * rightX + dz * rightZ) / horizontal;
        if (pan > 1.0) {
            pan = 1.0;
        } else if (pan < -1.0) {
            pan = -1.0;
        }
        return (float) (pan * MAX_PAN);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0.0f;
        }
        if (value < 0.0f) {
            return 0.0f;
        }
        return value > 1.0f ? 1.0f : value;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Throwable ignored) {
            // best effort
        }
    }

    // ------------------------------------------------------------------ types

    /** One playback: the block it belongs to, the file, and the current player/stream pair. */
    private static final class Session {

        /** Immutable block position, or null for the GUI preview. */
        final BlockPos pos;
        /** Registry name of the block that owns this session; null for the GUI preview. */
        final String blockId;
        final String soundName;
        final File file;
        final boolean loop;
        volatile float blockVolume;
        final AtomicBoolean released = new AtomicBoolean();
        volatile PcmPlayer player;
        volatile SourceStream stream;
        volatile boolean stopped;

        Session(BlockPos pos, String blockId, String soundName, File file, boolean loop, float blockVolume) {
            this.pos = pos;
            this.blockId = blockId;
            this.soundName = soundName;
            this.file = file;
            this.loop = loop;
            this.blockVolume = blockVolume;
        }

        boolean isLive() {
            return !stopped && !released.get();
        }

        /** Silences the device right away; the writer thread does the actual cleanup. */
        void stopNow() {
            stopped = true;
            PcmPlayer current = player;
            if (current != null) {
                try {
                    current.stop();
                } catch (Throwable t) {
                    FmLog.warnOnce("pcm.session.stop", "player.stop failed: " + FmLog.describe(t));
                }
            }
        }
    }

    /**
     * {@link PcmSource} as an {@link InputStream}.
     *
     * <p>{@code markSupported()} stays false on purpose: {@link PcmPlayer} would then buffer up to
     * 8 MiB per track just to loop, and looping is already handled one level up.</p>
     */
    private static final class SourceStream extends InputStream {

        private final PcmSource source;
        private volatile boolean closed;

        SourceStream(PcmSource source) {
            this.source = source;
        }

        AudioFormat format() {
            return source.format();
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int read = read(one, 0, 1);
            return read <= 0 ? -1 : (one[0] & 0xFF);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (closed) {
                return -1;
            }
            return source.read(buffer, offset, length);
        }

        @Override
        public int available() {
            return 0;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            source.close();
        }
    }
}

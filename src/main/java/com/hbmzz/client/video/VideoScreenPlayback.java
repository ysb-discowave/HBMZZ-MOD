/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client.video;

import com.hbmzz.tile.TileEntityVideoScreen;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Decoders for the video walls in the world, one per screen.
 *
 * <p>A wall is not a GUI: its player is created by the first frame the renderer
 * draws and lives until the lever goes off, the screen is broken, or the world is
 * left. Three rules keep that from turning into a memory leak with a picture on
 * it:</p>
 *
 * <ul>
 *   <li><b>A stopped screen owns nothing.</b> {@link #active} releases the decoder
 *       the moment {@code playing} goes false, which is the same tick the lever
 *       moved - not when the block is eventually removed.</li>
 *   <li><b>Entries are dropped, not swapped.</b> The map is keyed by tile entity
 *       and rebuilt by identity, so two walls showing the same clip keep two
 *       clocks. Sharing one decoder between them would make a screen that started
 *       later jump straight to the other one's playhead.</li>
 *   <li><b>The budget is finite.</b> A wall of screens in a big room would
 *       otherwise start one decode thread and one frame ring each. The oldest
 *       screen gives up its decoder when the budget is hit; it shows nothing until
 *       the lever is cycled, which is a visible, recoverable failure - unlike an
 *       out-of-memory crash.</li>
 * </ul>
 */
public final class VideoScreenPlayback {

    /** Simultaneously decoding walls on this client. */
    public static final int MAX_ACTIVE = 4;

    /**
     * Playback level when the broadcaster says 100%.
     *
     * <p>Half, not full: the audio path clamps at 1.0, so a 100% baseline that was
     * already 0.65 would make the top of the 0-200% slider inaudible - the knob
     * would move and nothing would happen.</p>
     */
    private static final float BASE_VOLUME = 0.5F;

    private static final Map<TileEntityVideoScreen, VideoPlayer> ACTIVE = new IdentityHashMap<>();
    private static final Map<TileEntityVideoScreen, String> CLIPS = new IdentityHashMap<>();
    private static final Map<TileEntityVideoScreen, Long> LAST_SEEN = new IdentityHashMap<>();
    /** Walls that were turned away because the budget was full, and when. */
    private static final Map<TileEntityVideoScreen, Long> REFUSED = new IdentityHashMap<>();

    /** How long a refused wall waits before asking again. */
    private static final long REFUSAL_MS = 5000L;

    /**
     * How far the local clock may drift from the wall's published playhead.
     *
     * <p>The screen's position is server-side truth (a seek, a pause, the tick the
     * film started on); the local decoder runs on its own stopwatch. They agree
     * to within a frame or two, so correcting anything smaller than this would
     * mean seeking - clearing the buffer - several times a second for no visible
     * gain. Anything larger is a jump somebody asked for, and gets applied.</p>
     */
    private static final double DRIFT_SECONDS = 0.35D;

    private VideoScreenPlayback() {
    }

    /**
     * The decoder for this wall, advanced to the current frame.
     *
     * @return null when the wall is stopped, unset, or its clip is not on this
     *         client - the caller draws nothing in all three cases
     */
    public static VideoPlayer active(TileEntityVideoScreen te) {
        if (te == null || te.getWorld() == null) {
            return null;
        }
        if (!te.isPlaying() || te.getVideo().isEmpty()) {
            release(te);
            return null;
        }
        double target = te.positionMs() / 1000.0D;
        VideoPlayer player = ACTIVE.get(te);
        // A wall switched to a different clip must not keep decoding the old one.
        if (player != null && !te.getVideo().equals(CLIPS.get(te))) {
            release(te);
            player = null;
        }
        if (player == null) {
            if (!mayStart(te)) {
                return null;
            }
            player = VideoPlayer.open(te.getVideo());
            if (player == null) {
                // Not on this client. Nothing to draw, and retrying every frame
                // would open a zip 60 times a second.
                return null;
            }
            player.setSpeed(te.getSpeed());
            // Before start(): the decode thread then begins at the frame the wall
            // is on, instead of decoding the whole film up to it.
            player.seekTo(within(player, target));
            player.setPaused(te.isPaused());
            player.start();
            ACTIVE.put(te, player);
            CLIPS.put(te, te.getVideo());
            REFUSED.remove(te);
        }
        player.setSpeed(te.getSpeed());
        // The wall owns the playhead; the decoder owns the pixels. Every frame,
        // the clock is nudged towards the published position and the pause state
        // is copied across, so a seek made by anyone anywhere lands here too.
        player.setPaused(te.isPaused());
        LAST_SEEN.put(te, System.currentTimeMillis());

        if (!player.started()) {
            // Sound starts with the picture, never before it - and at the film
            // time the wall is showing, not at zero.
            if (player.showFrame(player.frameIndex(player.filmSeconds()))) {
                player.beginPlayback(BASE_VOLUME * (te.getVolume() / 100.0F));
            }
        } else {
            // Both sides clamped to the film. The wall's clock keeps running past
            // the last frame (it is a stopwatch, not a frame counter), so without
            // this an ended film would look like a drift of seconds and be seeked
            // back to the end on every frame - clearing the decoder each time.
            double local = within(player, player.filmSeconds());
            if (Math.abs(local - within(player, target)) > DRIFT_SECONDS) {
                player.seekTo(within(player, target));
            }
            player.showFrame(player.frameIndex(player.filmSeconds()));
        }
        return player;
    }

    /**
     * A film time clamped into the clip this client actually has.
     *
     * <p>The server can only bound the playhead by a sane maximum; the real length
     * of the film is known here, and only here. Clamping both the requested and
     * the local position with it is what keeps "the film ended two minutes ago"
     * from looking like a drift worth correcting.</p>
     */
    private static double within(VideoPlayer player, double seconds) {
        double duration = player.durationSeconds();
        if (seconds < 0.0D) {
            return 0.0D;
        }
        return duration > 0.0D ? Math.min(seconds, duration) : seconds;
    }

    /**
     * Releases decoders for screens that are gone or stopped.
     *
     * <p>Called once per client tick. The renderer only runs for walls inside the
     * view frustum, so a screen behind the player would otherwise keep its decode
     * thread alive for as long as the chunk stays loaded.</p>
     */
    public static void tick() {
        if (ACTIVE.isEmpty()) {
            return;
        }
        // Copy the keys: releasing an entry while iterating the same map is the
        // textbook ConcurrentModificationException.
        for (TileEntityVideoScreen te : new java.util.ArrayList<>(ACTIVE.keySet())) {
            VideoPlayer player = ACTIVE.get(te);
            if (player == null) {
                continue;
            }
            if (te.isInvalid() || te.getWorld() == null || !te.isPlaying()) {
                release(te);
            }
        }
    }

    /** Releases everything; called when the client leaves a world. */
    public static void clear() {
        for (VideoPlayer player : ACTIVE.values()) {
            try {
                player.close();
            } catch (Throwable ignored) {
                // Tearing a world down; nothing useful to do per screen.
            }
        }
        ACTIVE.clear();
        CLIPS.clear();
        LAST_SEEN.clear();
        REFUSED.clear();
    }

    private static void release(TileEntityVideoScreen te) {
        VideoPlayer player = ACTIVE.remove(te);
        CLIPS.remove(te);
        LAST_SEEN.remove(te);
        REFUSED.remove(te);
        if (player != null) {
            player.close();
        }
    }

    /**
     * Whether this wall may take a decoder slot.
     *
     * <p>Refusing - rather than evicting somebody else - is what stops a room full
     * of screens from thrashing: with eviction, the wall that lost its slot reopens
     * on the very next frame and evicts another, sixty times a second. A refusal is
     * remembered for a few seconds, so the screen simply stays dark until the
     * player cycles its lever.</p>
     */
    private static boolean mayStart(TileEntityVideoScreen te) {
        long now = System.currentTimeMillis();
        Long refusedAt = REFUSED.get(te);
        if (refusedAt != null && now - refusedAt < REFUSAL_MS) {
            return false;
        }
        if (ACTIVE.size() >= MAX_ACTIVE) {
            REFUSED.put(te, now);
            return false;
        }
        return true;
    }
}

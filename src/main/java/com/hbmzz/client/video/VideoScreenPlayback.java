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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Plays clips on screens that stand in the world.
 *
 * <h3>One decoder per clip, not per screen</h3>
 * <p>A wall of eight screens showing the same film is one film. Giving each
 * screen its own {@link VideoPlayer} would mean eight decode threads, eight
 * copies of the frame ring and eight textures - and eight clocks that drift
 * apart, so screens standing next to each other would show different frames.
 * So playbacks are keyed by clip name and reference-counted: the first screen
 * starts the film, every later screen on the same clip shares its texture and
 * its clock, and the last one to stop closes it.</p>
 *
 * <h3>Cost control</h3>
 * <ul>
 *   <li>At most {@link #MAX_CLIPS} clips are decoded at once. Beyond that a
 *       screen stays on standby rather than stealing frames from the ones the
 *       player is actually looking at.</li>
 *   <li>Past {@link #FAR_BLOCKS} the picture freezes instead of decoding: the
 *       screen keeps its last uploaded frame (so it never reads as broken) but
 *       stops costing anything. A TESR only runs when it is in the frustum, so
 *       this is what makes an off-screen wall of screens free.</li>
 *   <li>Playback is muted. The audio path is a global {@code Sound} with no
 *       position, so two screens on the same clip would double the volume; sound
 *       is what the watching window is for.</li>
 * </ul>
 */
public final class VideoScreenPlayback {

    /** Distinct clips that may decode at the same time. */
    private static final int MAX_CLIPS = 8;

    /** Past this distance a screen stops advancing (but keeps painting). */
    private static final double FAR_BLOCKS = 96.0D;

    /** Give up on a clip that never produces a first frame. */
    private static final double FIRST_FRAME_TIMEOUT = 6.0D;

    /** How long to wait before retrying a file that is missing on this client. */
    private static final long MISSING_RETRY_NANOS = 5_000_000_000L;

    private static final class Shared {
        VideoPlayer player;
        int refs;
        boolean started;
        long openedAt;
        long retryAt;
    }

    /** One shared playback per clip name. */
    private static final Map<String, Shared> BY_NAME = new HashMap<String, Shared>();

    /**
     * Which clip each screen is currently holding a reference to. Weak keys so
     * a chunk that unloads (and whose tiles are dropped) cannot pin a playback;
     * {@link #tick()} releases the ones that go invalid while still loaded.
     */
    private static final WeakHashMap<TileEntityVideoScreen, String> ASSIGNED =
            new WeakHashMap<TileEntityVideoScreen, String>();

    private VideoScreenPlayback() {
    }

    /**
     * The texture this screen should paint this frame, or 0 when there is
     * nothing to show yet (no clip, missing file, still decoding, or over the
     * concurrency cap).
     *
     * @param distSq squared distance from the camera, in blocks
     */
    public static int textureFor(TileEntityVideoScreen te, String clip, double distSq) {
        String wanted = clip == null ? "" : clip.trim();
        if (wanted.isEmpty()) {
            release(te);
            return 0;
        }
        String held = ASSIGNED.get(te);
        if (!wanted.equals(held)) {
            if (!assign(te, wanted)) {
                return 0;
            }
        }
        Shared shared = BY_NAME.get(wanted);
        if (shared == null) {
            return 0;
        }
        if (distSq <= FAR_BLOCKS * FAR_BLOCKS) {
            advance(shared, wanted);
        }
        return shared.player == null ? 0 : shared.player.textureId();
    }

    /** Frame size of the clip a screen is playing, or null when unknown. */
    public static int[] frameSize(String clip) {
        Shared shared = clip == null ? null : BY_NAME.get(clip.trim());
        if (shared == null || shared.player == null || shared.player.frameWidth() <= 0) {
            return null;
        }
        return new int[]{shared.player.frameWidth(), shared.player.frameHeight()};
    }

    /** Drops a screen's claim; called when it stops showing anything. */
    public static void release(TileEntityVideoScreen te) {
        String held = ASSIGNED.remove(te);
        if (held != null) {
            releaseOne(held);
        }
    }

    /**
     * Called once per client tick: a screen whose tile has gone away must give
     * its reference back, or the decoder would keep running for a screen that
     * no longer exists until the world is left.
     */
    public static void tick() {
        if (ASSIGNED.isEmpty()) {
            return;
        }
        List<TileEntityVideoScreen> dead = null;
        for (Iterator<TileEntityVideoScreen> it = ASSIGNED.keySet().iterator(); it.hasNext(); ) {
            TileEntityVideoScreen te = it.next();
            if (te == null || te.isInvalid() || te.getWorld() == null) {
                if (dead == null) {
                    dead = new ArrayList<TileEntityVideoScreen>();
                }
                dead.add(te);
            }
        }
        if (dead != null) {
            for (TileEntityVideoScreen te : dead) {
                release(te);
            }
        }
    }

    /** Releases every texture; called when the client leaves a world. */
    public static void clearAll() {
        for (Shared shared : BY_NAME.values()) {
            close(shared);
        }
        BY_NAME.clear();
        ASSIGNED.clear();
    }

    // ------------------------------------------------------------- internals

    /** @return false when the concurrency cap refused this clip */
    private static boolean assign(TileEntityVideoScreen te, String clip) {
        String held = ASSIGNED.get(te);
        if (clip.equals(held)) {
            return true;
        }
        Shared shared = BY_NAME.get(clip);
        if (shared == null) {
            if (BY_NAME.size() >= MAX_CLIPS) {
                return false;
            }
            shared = new Shared();
            BY_NAME.put(clip, shared);
        }
        if (held != null) {
            releaseOne(held);
        }
        shared.refs++;
        ASSIGNED.put(te, clip);
        return true;
    }

    private static void releaseOne(String clip) {
        Shared shared = BY_NAME.get(clip);
        if (shared == null) {
            return;
        }
        shared.refs--;
        if (shared.refs <= 0) {
            close(shared);
            BY_NAME.remove(clip);
        }
    }

    private static void close(Shared shared) {
        if (shared.player != null) {
            try {
                shared.player.close();
            } catch (Throwable ignored) {
                // Tearing down a world is not a good moment to be strict.
            }
            shared.player = null;
        }
        shared.started = false;
    }

    /** Moves the shared playhead on, looping at the end and retrying absent files. */
    private static void advance(Shared shared, String clip) {
        long now = System.nanoTime();
        if (shared.player == null) {
            if (now < shared.retryAt) {
                return;
            }
            VideoPlayer player = VideoPlayer.open(clip);
            if (player == null) {
                shared.retryAt = now + MISSING_RETRY_NANOS;
                return;
            }
            shared.player = player;
            shared.started = false;
            shared.openedAt = now;
            player.start();
            return;
        }

        VideoPlayer player = shared.player;
        if (!shared.started) {
            if (player.failure() != null) {
                close(shared);
                shared.retryAt = now + MISSING_RETRY_NANOS;
                return;
            }
            if (player.showFrame(0)) {
                player.beginPlayback(false);
                shared.started = true;
            } else if ((now - shared.openedAt) / 1.0e9D > FIRST_FRAME_TIMEOUT) {
                close(shared);
                shared.retryAt = now + MISSING_RETRY_NANOS;
            }
            return;
        }

        if (player.finished()) {
            // A screen in the world is a loop, not a one-off: reopen straight
            // away so the next frame is the first one again.
            close(shared);
            shared.retryAt = 0L;
            return;
        }
        player.showFrame(player.frameIndex(player.elapsedSeconds()));
    }
}

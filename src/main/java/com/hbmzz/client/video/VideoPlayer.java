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

import com.hbmzz.HBMZZConfig;
import com.hbmzz.video.VideoClip;
import com.hbmzz.video.VideoLibrary;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Playback: a decode thread, a short ring of decoded frames, and a clock.
 *
 * <p>Three rules shape the whole thing.</p>
 *
 * <p><b>Never decode on the render thread.</b> A 480p JPEG takes a few
 * milliseconds to decode; a few milliseconds multiplied by every frame is a
 * visibly stuttering game. Decoding happens on a daemon thread and the render
 * thread only ever picks up finished {@link BufferedImage}s.</p>
 *
 * <p><b>Never hold the whole clip in memory.</b> 30 seconds at 12 fps is 360
 * frames; at 854x480 that is roughly 580 MB if they were all kept. So the
 * decoder runs a bounded distance ahead of the playhead
 * ({@code videoCacheFrames}) and frames behind it are dropped. Playback is
 * clocked, not counted: if the machine cannot keep up, a frame is skipped and
 * the sound stays in sync. That is the correct failure for video - late frames
 * are worse than missing ones.</p>
 *
 * <h3>One cursor, every loop mode</h3>
 * <p>The playhead is a <em>monotonic</em> position in slots, not a slot number:
 * a clip with 60 slots counts 0..59, then 60..119, and so on. The clip folds
 * that back into a slot, which is what makes looping free - the seam between
 * the last frame and the first is just another decode step - and ping-pong
 * cheap, because the reverse pass was pre-decoded by the same forward walk.</p>
 *
 * <h3>Sound follows picture</h3>
 * <p>The clock starts when the first frame is actually on screen, never when
 * the screen opens. The track is restarted at every forward pass (so a looping
 * alarm does not slowly slide out of sync), paused on the reverse half of a
 * ping-pong, and nudged by a seek when it drifts more than
 * {@link #AUDIO_TOLERANCE}. The picture never waits for the sound.</p>
 */
public final class VideoPlayer implements Closeable {

    /** How far ahead the decoder may run, in frames, regardless of config. */
    private static final int MIN_CACHE = 2;
    private static final int MAX_CACHE = 24;

    /** Drift worth a seek; below this the mixer is just being a mixer. */
    private static final double AUDIO_TOLERANCE = 0.30D;

    private final VideoClip clip;
    private final int maxWidth;
    private final int maxHeight;
    private final int cacheFrames;

    private final VideoTexture texture = new VideoTexture();
    private final Object lock = new Object();
    private final Map<Long, BufferedImage> ready = new LinkedHashMap<Long, BufferedImage>();

    private volatile boolean running = true;
    private volatile long consumed;
    private volatile String failure;
    private volatile int frameWidth;
    private volatile int frameHeight;

    private Thread worker;
    private VideoAudio audio;
    private long startNanos;
    private boolean started;
    private int uploaded = -1;
    private int audioCycle = -1;

    private VideoPlayer(VideoClip clip) {
        this.clip = clip;
        this.maxWidth = Math.max(64, HBMZZConfig.videoMaxWidth);
        this.maxHeight = Math.max(64, HBMZZConfig.videoMaxHeight);
        this.cacheFrames = Math.max(MIN_CACHE, Math.min(MAX_CACHE, HBMZZConfig.videoCacheFrames));
    }

    /** @return null when the clip is missing or unreadable; check {@link VideoLibrary} first */
    public static VideoPlayer open(String name) {
        return open(name, false);
    }

    /**
     * @param singlePass force one pass regardless of what the pack declares,
     *                   which is what a full-screen broadcast must do
     */
    public static VideoPlayer open(String name, boolean singlePass) {
        VideoClip clip = VideoLibrary.open(name);
        if (clip == null) {
            return null;
        }
        return new VideoPlayer(singlePass ? clip.asSinglePass() : clip);
    }

    // --------------------------------------------------------------- control

    public void start() {
        if (this.worker != null) {
            return;
        }
        this.worker = new Thread(this::decodeLoop, "hbmzz-video-" + this.clip.name());
        this.worker.setDaemon(true);
        this.worker.setPriority(Thread.NORM_PRIORITY - 1);
        this.worker.start();
    }

    /**
     * Walks the playhead forward for as long as the clip lasts, prefetching
     * frames for the render thread.
     *
     * <p>Positions, not frames, are the unit here: two slots may show the same
     * image (a hold, a repeated part), and the decoded bitmap is reused instead
     * of decoded twice.</p>
     */
    private void decodeLoop() {
        long position = 0L;
        long limit = limitPositions();
        int lastEntry = -1;
        BufferedImage lastImage = null;
        try {
            while (this.running && position < limit) {
                synchronized (this.lock) {
                    while (this.running && position - this.consumed >= this.cacheFrames) {
                        this.lock.wait(200L);
                    }
                }
                if (!this.running) {
                    return;
                }
                int entry = this.clip.entryAt(this.clip.slotAt(position));
                BufferedImage image;
                if (entry == lastEntry && lastImage != null) {
                    image = lastImage;
                } else {
                    image = normalise(this.clip.readFrame(entry));
                    lastEntry = entry;
                    lastImage = image;
                }
                synchronized (this.lock) {
                    this.ready.put(position, image);
                    this.lock.notifyAll();
                }
                position++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // A corrupt frame at the start is fatal; later on it just means the
            // clip ends early, which the clock already handles.
            if (position == 0L) {
                this.failure = "无法解码第 1 帧：" + t;
            }
        }
    }

    private long limitPositions() {
        int passes = this.clip.passes();
        if (passes <= 0) {
            return Long.MAX_VALUE;
        }
        return (long) passes * this.clip.periodSlots();
    }

    /**
     * Anchors the clock and starts the soundtrack.
     *
     * <p>Called when the first frame is actually on screen, not when the screen
     * opens: audio that starts before the picture is the one sync error an
     * audience always notices. It also runs once - a sync packet arriving twice
     * must not jump the playhead back to zero.</p>
     */
    public void beginPlayback() {
        beginPlayback(true);
    }

    /**
     * Same, but able to stay silent.
     *
     * <p>A screen standing in the world plays muted: the audio path is a plain
     * {@code Sound} with no position, so two screens showing the same clip
     * would double the volume, and a wall of them would be unusable. Sound is
     * what the watching window is for. The clock is anchored either way, so
     * muting never desynchronises the picture.</p>
     */
    public void beginPlayback(boolean withAudio) {
        if (this.started) {
            return;
        }
        this.started = true;
        this.startNanos = System.nanoTime();
        if (withAudio && HBMZZConfig.videoAudio && this.clip.hasAudio()) {
            try {
                this.audio = VideoAudio.play(this.clip.openAudio(), 0.65F, this.clip.audioOffset());
                this.audioCycle = 0;
            } catch (IOException ignored) {
            }
        }
    }

    public boolean started() {
        return this.started;
    }

    public boolean hasAudio() {
        return this.clip.hasAudio();
    }

    public double elapsedSeconds() {
        return this.started ? (System.nanoTime() - this.startNanos) / 1.0e9D : 0.0D;
    }

    /** Length of one cycle; what the progress bar spans. */
    public double durationSeconds() {
        return this.clip.cycleSeconds();
    }

    /** True once a finite clip has played out, with a tail for the last frame. */
    public boolean finished() {
        if (!this.started) {
            return false;
        }
        int passes = this.clip.passes();
        if (passes <= 0) {
            return false;
        }
        double tail = this.clip.frameSeconds() + 0.25D;
        return elapsedSeconds() >= passes * durationSeconds() + tail;
    }

    /** False for a one-shot clip: the caller should stop showing it. */
    public boolean looping() {
        return this.clip.repeating();
    }

    public String failure() {
        return this.failure;
    }

    // ----------------------------------------------------------------- frames

    /**
     * Puts a position on the texture if it is decoded.
     *
     * @return true when something is (or already was) on screen
     */
    public boolean showFrame(int position) {
        if (position < 0) {
            position = 0;
        }
        BufferedImage image;
        synchronized (this.lock) {
            image = this.ready.get((long) position);
            if (image != null) {
                if (position > this.consumed) {
                    this.consumed = position;
                }
                // One frame of slack behind the playhead covers a slow render
                // tick; anything older can never be shown again.
                final long floor = position - 1L;
                this.ready.keySet().removeIf(key -> key < floor);
                this.lock.notifyAll();
            }
        }
        if (image != null && position != this.uploaded) {
            this.texture.upload(image);
            this.uploaded = position;
        }
        if (this.audio != null && this.started) {
            syncAudio(elapsedSeconds());
        }
        return this.uploaded >= 0;
    }

    /** Elapsed seconds to a position on this clip's timeline. */
    public int positionAt(double seconds) {
        return this.clip.positionAt(seconds);
    }

    /** The final position of a finite clip, for holding its last frame. */
    public int lastPosition() {
        return this.clip.lastPosition();
    }

    public int textureId() {
        return this.texture.id();
    }

    public int frameWidth() {
        return this.frameWidth;
    }

    public int frameHeight() {
        return this.frameHeight;
    }

    public int frameCount() {
        return this.clip.frameCount();
    }

    public double fps() {
        return this.clip.fps();
    }

    public String title() {
        return this.clip.title();
    }

    public VideoClip.LoopMode loopMode() {
        return this.clip.loopMode();
    }

    public double percent() {
        int passes = this.clip.passes();
        if (passes > 0) {
            double total = passes * durationSeconds();
            return total <= 0.0D ? 0.0D : Math.min(1.0D, elapsedSeconds() / total);
        }
        double cycle = durationSeconds();
        return cycle <= 0.0D ? 0.0D : Math.min(1.0D, (elapsedSeconds() % cycle) / cycle);
    }

    // ------------------------------------------------------------- internals

    /**
     * Keeps the soundtrack on the picture.
     *
     * <p>A cycle is a natural sync point: restart the track there and the two
     * agree to within one mixer buffer. Inside a cycle, drift beyond
     * {@link #AUDIO_TOLERANCE} is corrected by seeking the audio, never by
     * delaying the frame - the picture is the master clock.</p>
     */
    private void syncAudio(double elapsed) {
        double cycle = this.clip.cycleSeconds();
        if (cycle <= 0.0D) {
            return;
        }
        int cycleIndex = (int) Math.floor(elapsed / cycle);
        double inCycle = elapsed - cycleIndex * cycle;
        boolean forward = inCycle <= this.clip.forwardSeconds();
        if (!forward) {
            // Ping-pong: the reverse half runs backward with no sound. Playing
            // the track again there would double a one-pass soundtrack.
            this.audio.setPaused(true);
            return;
        }
        if (cycleIndex != this.audioCycle) {
            this.audioCycle = cycleIndex;
            this.audio.setPaused(false);
            this.audio.restart(this.clip.audioOffset());
            return;
        }
        this.audio.setPaused(false);
        double expected = this.clip.audioOffset() + inCycle;
        if (expected < this.audio.lengthSeconds()) {
            this.audio.align(expected, AUDIO_TOLERANCE);
        }
    }

    private BufferedImage normalise(BufferedImage source) {
        int w = source.getWidth();
        int h = source.getHeight();
        if (this.frameWidth == 0) {
            double scale = Math.min(1.0D, Math.min((double) this.maxWidth / w, (double) this.maxHeight / h));
            this.frameWidth = Math.max(1, (int) Math.round(w * scale));
            this.frameHeight = Math.max(1, (int) Math.round(h * scale));
        }
        int tw = this.frameWidth;
        int th = this.frameHeight;
        if (w == tw && h == th && source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, tw, th, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    @Override
    public void close() {
        this.running = false;
        synchronized (this.lock) {
            this.lock.notifyAll();
        }
        if (this.worker != null) {
            this.worker.interrupt();
            try {
                this.worker.join(400L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            this.worker = null;
        }
        if (this.audio != null) {
            this.audio.stop();
            this.audio = null;
        }
        this.clip.close();
        this.texture.delete();
        synchronized (this.lock) {
            this.ready.clear();
        }
    }
}

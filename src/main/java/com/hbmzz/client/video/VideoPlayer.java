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
 * <p><b>Normalise once.</b> Every frame is redrawn into {@code TYPE_INT_RGB} at
 * one fixed size on the decode thread (flattening any alpha onto black, which is
 * what a video screen does with it). The texture is therefore allocated exactly
 * once, and the render path is a single sub-image upload.</p>
 */
public final class VideoPlayer implements Closeable {

    /** How far ahead the decoder may run, in frames, regardless of config. */
    private static final int MIN_CACHE = 2;
    private static final int MAX_CACHE = 24;

    private final VideoClip clip;
    private final int maxWidth;
    private final int maxHeight;
    private final int cacheFrames;

    private final VideoTexture texture = new VideoTexture();
    private final Object lock = new Object();
    private final Map<Integer, BufferedImage> ready = new LinkedHashMap<>();

    private volatile boolean running = true;
    private volatile int decoded;
    private volatile int consumed;
    private volatile boolean decodeDone;
    private volatile String failure;
    private volatile int frameWidth;
    private volatile int frameHeight;

    private Thread worker;
    private VideoAudio audio;
    private long startNanos;
    private boolean started;
    private int uploaded = -1;

    private VideoPlayer(VideoClip clip) {
        this.clip = clip;
        this.maxWidth = Math.max(64, HBMZZConfig.videoMaxWidth);
        this.maxHeight = Math.max(64, HBMZZConfig.videoMaxHeight);
        this.cacheFrames = Math.max(MIN_CACHE, Math.min(MAX_CACHE, HBMZZConfig.videoCacheFrames));
    }

    /** @return null when the clip is missing or unreadable; check {@link VideoLibrary} first */
    public static VideoPlayer open(String name) {
        VideoClip clip = VideoLibrary.open(name);
        return clip == null ? null : new VideoPlayer(clip);
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

    private void decodeLoop() {
        int index = 0;
        try {
            while (this.running && index < this.clip.frameCount()) {
                synchronized (this.lock) {
                    while (this.running && this.decoded - this.consumed >= this.cacheFrames) {
                        this.lock.wait(200L);
                    }
                }
                if (!this.running) {
                    return;
                }
                BufferedImage raw = this.clip.readFrame(index);
                BufferedImage normalised = normalise(raw);
                synchronized (this.lock) {
                    this.ready.put(index, normalised);
                    this.decoded = index + 1;
                    this.lock.notifyAll();
                }
                index++;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // A corrupt frame at the start is fatal; later on it just means the
            // clip ends early, which the clock already handles.
            if (index == 0) {
                this.failure = "无法解码第 1 帧：" + t;
            }
        } finally {
            this.decodeDone = true;
            synchronized (this.lock) {
                this.lock.notifyAll();
            }
        }
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
        if (this.started) {
            return;
        }
        this.started = true;
        this.startNanos = System.nanoTime();
        if (HBMZZConfig.videoAudio && this.clip.hasAudio()) {
            try {
                this.audio = VideoAudio.play(this.clip.openAudio(), 0.65F);
            } catch (IOException ignored) {
            }
        }
    }

    public boolean started() {
        return this.started;
    }

    public double elapsedSeconds() {
        return this.started ? (System.nanoTime() - this.startNanos) / 1.0e9D : 0.0D;
    }

    public double durationSeconds() {
        return this.clip.durationSeconds();
    }

    /** True once the clip has played out, with a short tail for the last frame. */
    public boolean finished() {
        if (!this.started) {
            return false;
        }
        double tail = 1.0D / this.clip.fps() + 0.25D;
        return elapsedSeconds() >= durationSeconds() + tail;
    }

    /** True when decoding is over and nothing is left to show. */
    public boolean exhausted() {
        return this.decodeDone && this.uploaded >= this.clip.frameCount() - 1;
    }

    public String failure() {
        return this.failure;
    }

    // ----------------------------------------------------------------- frames

    /**
     * Puts frame {@code index} on the texture if it is decoded.
     *
     * @return true when something is (or already was) on screen
     */
    public boolean showFrame(int index) {
        BufferedImage image;
        synchronized (this.lock) {
            image = this.ready.get(index);
            if (image != null) {
                this.consumed = Math.max(this.consumed, index);
                // One frame of slack behind the playhead covers a slow render
                // tick; anything older can never be shown again.
                this.ready.keySet().removeIf(key -> key < index - 1);
                this.lock.notifyAll();
            }
        }
        if (image != null && index != this.uploaded) {
            this.texture.upload(image);
            this.uploaded = index;
        }
        return this.uploaded >= 0;
    }

    public int frameIndex(double seconds) {
        int index = (int) Math.floor(seconds * this.clip.fps());
        return Math.max(0, Math.min(this.clip.frameCount() - 1, index));
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

    public int fps() {
        return this.clip.fps();
    }

    public String title() {
        return this.clip.title();
    }

    public double percent() {
        double duration = durationSeconds();
        return duration <= 0.0D ? 0.0D : Math.min(1.0D, elapsedSeconds() / duration);
    }

    // ------------------------------------------------------------- internals

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

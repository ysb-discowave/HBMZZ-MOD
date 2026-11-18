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
    /** Frame the decode thread should jump to next; -1 while it is in step. */
    private volatile int seekRequest = -1;
    /**
     * Frame the decode thread is working on next.
     *
     * <p>Readable so {@link #showFrame(int)} can tell "the frame I want is not
     * decoded yet" from "the decoder is a whole buffer behind the playhead and
     * will never catch up". The difference matters: the first is a normal tick of
     * latency, the second is a permanently black screen.</p>
     */
    private volatile int decodeIndex;
    /**
     * The frame the render thread last asked for.
     *
     * <p>Published so the decoder can notice it is behind on its own. The old
     * design throttled a jump from the render side (once per 400 ms), which meant
     * a slow machine showed a frozen picture, then jumped, then froze again -
     * three visible hitches a second at high speed.</p>
     */
    private volatile int wanted = -1;
    private volatile boolean decodeDone;
    private volatile String failure;
    private volatile int frameWidth;
    private volatile int frameHeight;

    private Thread worker;
    private VideoAudio audio;
    /**
     * Whether this decoder is allowed to make a sound right now.
     *
     * <p>Not a pause: the picture keeps running, the clock keeps running, only the
     * track is held. {@link VideoAudioPolicy} owns the decision and pushes it
     * down here, so "which of the four things that could be playing is actually
     * audible" is answered in one place instead of in every window.</p>
     */
    private volatile boolean muted;
    private long startNanos;
    /**
     * Film time the clock is measured from, in seconds.
     *
     * <p>Playback is a position plus a stopwatch, not a frame counter: the
     * playhead is {@code baseSeconds}, and seeking only moves the base. That is
     * what lets a wall start a film at 23 seconds, hold it there while the lever
     * is paused, and carry on from the same place afterwards without the decoder
     * knowing anything changed.</p>
     */
    private double baseSeconds;
    private boolean started;
    private volatile boolean paused;
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

    /**
     * Decodes forward, one bounded buffer ahead of the playhead.
     *
     * <p>The loop never finishes on its own: a wall can seek backwards after the
     * film has already played out, and a decode thread that exited would leave
     * those frames undecodable. It stops at the end of the clip and parks on the
     * lock instead, waking up when {@link #seekTo(double)} asks for another
     * position or when the wall is closed.</p>
     *
     * <p>Back-pressure is the size of the buffer rather than a frame counter,
     * because a seek rewinds both the counter and the content: what matters is
     * how many frames are waiting, not how many have ever been produced.</p>
     */
    private void decodeLoop() {
        int index = 0;
        try {
            while (this.running) {
                synchronized (this.lock) {
                    if (this.seekRequest >= 0) {
                        index = this.seekRequest;
                        this.seekRequest = -1;
                        // Frames from the old position are worthless now, and
                        // holding them would make a backwards seek decode nothing.
                        this.ready.clear();
                        this.lock.notifyAll();
                    }
                    this.decodeIndex = index;
                    while (this.running && this.seekRequest < 0 && this.ready.size() >= this.cacheFrames) {
                        // 播放头已经跑到缓冲外面了：别再等空位，直接丢帧追上去。
                        // 等下去只会解出一堆播放头早就越过的帧 —— 那正是「画面
                        // 卡住、声音在走」，也是高倍速下每秒几次的抽动。
                        if (this.wanted - index > this.cacheFrames) {
                            break;
                        }
                        this.lock.wait(200L);
                    }
                    if (this.seekRequest >= 0) {
                        continue;
                    }
                    if (this.wanted - index > this.cacheFrames) {
                        // 丢掉中间那段，接着解播放头要的那一帧。不清整张缓存：
                        // 已经解好、还在播放头前面的帧仍然有用。
                        index = this.wanted;
                        final int from = index;
                        this.ready.keySet().removeIf(key -> key < from);
                    }
                }
                if (!this.running) {
                    return;
                }
                if (index >= this.clip.frameCount()) {
                    // End of the film: keep the last frames servable and wait for
                    // a seek (or a close).
                    this.decodeIndex = this.clip.frameCount();
                    this.decodeDone = true;
                    synchronized (this.lock) {
                        while (this.running && this.seekRequest < 0) {
                            this.lock.wait(200L);
                        }
                    }
                    continue;
                }
                BufferedImage raw = this.clip.readFrame(index);
                BufferedImage normalised = normalise(raw);
                synchronized (this.lock) {
                    // A seek that arrived while this frame was decoding wins:
                    // putting the stale frame back would defeat the seek.
                    if (this.seekRequest < 0) {
                        this.ready.put(index, normalised);
                    }
                    this.lock.notifyAll();
                }
                index++;
                this.decodeIndex = index;
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
        beginPlayback(0.65F);
    }

    /**
     * Same, at a caller-chosen level.
     *
     * <p>The wall screen has its own volume setting, which is why this is not a
     * constant: a 32x32 wall at cinema volume and a 1x1 panel in a corridor should
     * not sound identical. The clock is anchored either way.</p>
     */
    public void beginPlayback(float volume) {
        if (this.started) {
            return;
        }
        this.started = true;
        this.startNanos = System.nanoTime();
        if (HBMZZConfig.videoAudio && this.clip.hasAudio()) {
            try {
                // From baseSeconds: a wall that starts at 23s hears 23s of the
                // soundtrack, which is the whole point of being able to seek.
                this.audio = VideoAudio.play(this.clip.openAudio(), volume, this.baseSeconds);
                // A wall that starts while paused must stay silent: the track is
                // opened at the right offset and then held, so resuming is a
                // start rather than a fresh clip from zero.
                this.audio.setPaused(this.paused);
                // Same for a decoder that was opened while somebody else owns the
                // speakers - a settings window, or another wall.
                this.audio.setMuted(this.muted);
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Silences or restores this decoder's soundtrack.
     *
     * <p>The clock and the picture are untouched: unmuting jumps the track back
     * onto the film time the decoder is actually showing, because while it was
     * muted the wall's playhead kept moving and a track that just resumed would
     * be however many seconds behind the picture.</p>
     */
    public void setMuted(boolean muted) {
        if (this.muted == muted) {
            return;
        }
        this.muted = muted;
        VideoAudio audio = this.audio;
        if (audio == null) {
            return;
        }
        if (muted) {
            audio.setMuted(true);
        } else {
            audio.setMuted(false);
            audio.seek(filmSeconds());
        }
    }

    public boolean muted() {
        return this.muted;
    }

    /**
     * Moves the playhead.
     *
     * <p>Only the base moves: the decode thread is asked for that frame, the
     * soundtrack is repositioned, and the next {@link #showFrame(int)} paints it.
     * Playback continues from there - seeking is not a restart, so the wall keeps
     * whatever pause state it had.</p>
     *
     * <p>The target is clamped to the clip: negative times are "before the film"
     * and anything past the end would park a wall on a black frame forever.</p>
     */
    public void seekTo(double seconds) {
        double duration = durationSeconds();
        double target = seconds;
        if (target < 0.0D) {
            target = 0.0D;
        }
        if (duration > 0.0D && target > duration) {
            target = duration;
        }
        this.baseSeconds = target;
        this.startNanos = System.nanoTime();
        this.seekRequest = frameIndex(target);
        // A decoder parked at the end of the film is not waiting on anything but
        // this lock, so a backwards seek has to wake it.
        synchronized (this.lock) {
            this.lock.notifyAll();
        }
        if (this.audio != null) {
            this.audio.seek(target);
        }
    }

    /**
     * Freezes or resumes the clock.
     *
     * <p>Pausing keeps the decoder and the current frame: the picture holds, the
     * playhead does not advance, and resuming continues from the same place. A
     * wall that released its decoder on pause would go black until it restarted
     * the film, which is not what a pause means.</p>
     */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        if (paused) {
            // Read the playhead while the clock is still running.
            this.baseSeconds = filmSeconds();
            this.paused = true;
        } else {
            this.startNanos = System.nanoTime();
            this.paused = false;
        }
        if (this.audio != null) {
            this.audio.setPaused(paused);
        }
    }

    public boolean paused() {
        return this.paused;
    }

    /** Clip time reached so far: the anchor plus wall-clock time since it. */
    public double filmSeconds() {
        if (!this.started || this.paused) {
            return this.baseSeconds;
        }
        return this.baseSeconds + elapsedSeconds();
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
        return filmSeconds() >= durationSeconds() + tail;
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
        // Published every render tick, including the ticks where the frame is not
        // decoded yet: that is precisely when the decoder needs to know.
        this.wanted = index;
        BufferedImage image;
        synchronized (this.lock) {
            image = this.ready.get(index);
            if (image != null) {
                // One frame of slack behind the playhead covers a slow render
                // tick; anything older can never be shown again. This is also what
                // releases the decode thread's back-pressure.
                this.ready.keySet().removeIf(key -> key < index - 1);
                this.lock.notifyAll();
            }
        }
        if (image != null && index != this.uploaded) {
            this.texture.upload(image);
            this.uploaded = index;
        }
        // 缺帧时这里不改任何东西：纹理上还留着上一帧，解码线程会按
        // {@link #wanted} 跳过一段自己追上来（见 decodeLoop）。从这个位置发
        // seek 的旧写法会清缓存，于是画面「卡一下、跳一格、再卡一下」。
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
        return duration <= 0.0D ? 0.0D : Math.min(1.0D, filmSeconds() / duration);
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

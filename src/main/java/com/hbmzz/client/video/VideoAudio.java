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

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.Closeable;
import java.io.InputStream;

/**
 * The optional soundtrack, played through the JRE's own mixer.
 *
 * <p>Free of any dependency but {@code javax.sound}, and deliberately allowed to
 * fail: a machine with no sound card, a track in a format the platform will not
 * take, or a mixer already held by something else all end in the same place -
 * the video plays silently and the world keeps turning. A broadcast that
 * refuses to start because audio would not open would be a worse bug than a
 * mute one.</p>
 *
 * <h3>Seekable on purpose</h3>
 * <p>A {@link Clip} keeps the decoded samples in memory, which is what makes
 * {@link #seek(double)} cheap. The video player uses that to keep the sound on
 * the picture: every loop restarts the track at the animation's first frame,
 * and a drift of more than a few hundred milliseconds is corrected by moving
 * the playhead rather than by dropping frames. Picture timing never follows
 * audio - a late frame is invisible, a picture that stutters to chase a sound
 * is not.</p>
 */
public final class VideoAudio implements Closeable {

    /** Ignore seeks this close to where the track already is. */
    private static final double SEEK_EPSILON = 0.05D;

    private Clip clip;
    private boolean paused;

    private VideoAudio() {
    }

    /** @return a started audio track, or null when it could not be played */
    public static VideoAudio play(InputStream stream, float volume) {
        return play(stream, volume, 0.0D);
    }

    /**
     * @param offsetSeconds where in the track the animation's first frame sits;
     *                      lets a pack skip a leader of silence
     */
    public static VideoAudio play(InputStream stream, float volume, double offsetSeconds) {
        if (stream == null) {
            return null;
        }
        Clip clip = null;
        try {
            AudioInputStream audio = AudioSystem.getAudioInputStream(stream);
            clip = AudioSystem.getClip();
            clip.open(audio);
            float level = Math.max(0.0F, Math.min(1.0F, volume));
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                // Not every mixer's gain curve is in dB, so clamp into its range
                // instead of assuming 0 dB is the top.
                float db = level <= 0.0001F ? gain.getMinimum()
                        : (float) (20.0 * Math.log10(level));
                gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db)));
            }
            double start = Math.max(0.0D, offsetSeconds);
            if (start < clip.getMicrosecondLength() / 1.0e6D) {
                clip.setMicrosecondPosition((long) (start * 1.0e6D));
                clip.start();
            }
            audio.close();
        } catch (Throwable t) {
            if (clip != null) {
                try {
                    clip.close();
                } catch (Throwable ignored) {
                }
            }
            return null;
        } finally {
            try {
                stream.close();
            } catch (Throwable ignored) {
            }
        }
        VideoAudio result = new VideoAudio();
        result.clip = clip;
        return result;
    }

    /** Current playhead in seconds, or -1 when the track is gone. */
    public double positionSeconds() {
        if (this.clip == null) {
            return -1.0D;
        }
        try {
            return this.clip.getMicrosecondPosition() / 1.0e6D;
        } catch (Throwable t) {
            return -1.0D;
        }
    }

    /** Length in seconds; 0 when unknown. */
    public double lengthSeconds() {
        if (this.clip == null) {
            return 0.0D;
        }
        try {
            return this.clip.getMicrosecondLength() / 1.0e6D;
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    /** Moves the playhead; a target past the end simply stops the track. */
    public void seek(double seconds) {
        if (this.clip == null) {
            return;
        }
        double target = Math.max(0.0D, seconds);
        try {
            if (target >= lengthSeconds()) {
                this.clip.stop();
                return;
            }
            this.clip.setMicrosecondPosition((long) (target * 1.0e6D));
            if (!this.paused) {
                this.clip.start();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Back to the start of the animation, playing. */
    public void restart(double offsetSeconds) {
        if (this.clip == null) {
            return;
        }
        try {
            this.paused = false;
            double start = Math.max(0.0D, offsetSeconds);
            this.clip.stop();
            if (start >= lengthSeconds()) {
                return;
            }
            this.clip.setMicrosecondPosition((long) (start * 1.0e6D));
            this.clip.start();
        } catch (Throwable ignored) {
        }
    }

    /** Pause without losing the position (the reverse half of a ping-pong). */
    public void setPaused(boolean paused) {
        if (this.clip == null || this.paused == paused) {
            return;
        }
        this.paused = paused;
        try {
            if (paused) {
                this.clip.stop();
            } else if (this.clip.getMicrosecondPosition() < this.clip.getMicrosecondLength()) {
                this.clip.start();
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Corrects drift without stuttering the picture.
     *
     * @param expected the playhead the picture says the sound should be at
     * @param tolerance seconds of drift that are not worth a seek
     */
    public void align(double expected, double tolerance) {
        double actual = positionSeconds();
        if (actual < 0.0D) {
            return;
        }
        if (Math.abs(actual - expected) > Math.max(tolerance, SEEK_EPSILON)) {
            seek(expected);
        }
    }

    public void stop() {
        if (this.clip != null) {
            try {
                this.clip.stop();
                this.clip.close();
            } catch (Throwable ignored) {
            }
            this.clip = null;
        }
    }

    @Override
    public void close() {
        stop();
    }
}

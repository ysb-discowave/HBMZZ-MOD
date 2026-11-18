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
 * The optional WAV track, played through the JRE's own mixer.
 *
 * <p>Free of any dependency but {@code javax.sound}, and deliberately allowed to
 * fail: a machine with no sound card, a WAV in a format the platform will not
 * take, or a mixer already held by something else all end in the same place -
 * the video plays silently and the world keeps turning. A broadcast that
 * refuses to start because audio would not open would be a worse bug than a
 * mute one.</p>
 */
public final class VideoAudio implements Closeable {

    private Clip clip;
    private boolean paused;
    /**
     * Muted: the track keeps its position but makes no sound.
     *
     * <p>Different from {@link #paused} on purpose. A paused wall is paused for
     * everybody; a muted one is still playing, it is just not this client's turn
     * to be the speaker - the same wall shown full-screen, or another wall in the
     * same room, is the one carrying the sound. Folding the two together would
     * freeze the picture of every wall in the room whenever a window opened.</p>
     */
    private boolean muted;

    private VideoAudio() {
    }

    /** @return a started audio track, or null when it could not be played */
    public static VideoAudio play(InputStream stream, float volume) {
        return play(stream, volume, 0.0D);
    }

    /**
     * Same, starting at a film time.
     *
     * <p>A wall that joins a film already in progress - the player walked into the
     * room late, or the lever was flipped after somebody seeked - must hear the
     * soundtrack from the same place the picture starts. Playing from zero and
     * letting the clock catch up would be nearly a minute of the wrong audio.</p>
     *
     * @return a started audio track, or null when it could not be played
     */
    public static VideoAudio play(InputStream stream, float volume, double startSeconds) {
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
            clip.setMicrosecondPosition(microsOf(clip, startSeconds));
            clip.start();
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

    /**
     * Jumps the soundtrack to a film time.
     *
     * <p>{@code Clip} keeps its own frame position, so a seek is a stop, a
     * reposition and (unless the wall is paused) a start. The position is clamped
     * into the track's own length: a clip whose audio is shorter than the picture
     * would otherwise throw on the seek and kill the whole soundtrack.</p>
     */
    public void seek(double seconds) {
        if (this.clip == null) {
            return;
        }
        try {
            this.clip.stop();
            this.clip.setMicrosecondPosition(microsOf(this.clip, seconds));
            if (!this.paused && !this.muted) {
                this.clip.start();
            }
        } catch (Throwable ignored) {
            // A seek that the mixer refuses is not worth losing the track over.
        }
    }

    /** Freezes or resumes the track in place; the position is kept either way. */
    public void setPaused(boolean paused) {
        if (this.paused == paused) {
            return;
        }
        this.paused = paused;
        apply();
    }

    /**
     * Silences or restores the track without moving it.
     *
     * <p>Used by {@code VideoAudioPolicy}, which decides whose soundtrack the
     * player is actually hearing: one wall at a time in the world, nobody while a
     * settings window is open.</p>
     */
    public void setMuted(boolean muted) {
        if (this.muted == muted) {
            return;
        }
        this.muted = muted;
        apply();
    }

    public boolean muted() {
        return this.muted;
    }

    /** One place that decides whether the clip should currently be running. */
    private void apply() {
        if (this.clip == null) {
            return;
        }
        try {
            if (this.paused || this.muted) {
                this.clip.stop();
            } else {
                this.clip.start();
            }
        } catch (Throwable ignored) {
        }
    }

    /** Film seconds to microseconds, clamped into the track. */
    private static long microsOf(Clip clip, double seconds) {
        long micros = Math.max(0L, Math.round(seconds * 1_000_000.0D));
        try {
            long length = clip.getMicrosecondLength();
            if (length > 0L) {
                micros = Math.min(micros, length);
            }
        } catch (Throwable ignored) {
        }
        return micros;
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

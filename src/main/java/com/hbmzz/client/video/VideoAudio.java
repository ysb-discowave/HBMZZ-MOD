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

    private VideoAudio() {
    }

    /** @return a started audio track, or null when it could not be played */
    public static VideoAudio play(InputStream stream, float volume) {
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
            clip.setFramePosition(0);
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

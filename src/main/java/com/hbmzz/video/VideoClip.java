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

package com.hbmzz.video;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One video, as a numbered sequence of still frames.
 *
 * <p>There is no codec here on purpose. Minecraft 1.12.2 ships a JRE, not an
 * H.264 decoder, and every "video mod" that bolted one on ended up shipping
 * native binaries per platform. A frame sequence plus {@link ImageIO} and an
 * optional WAV track needs nothing but the JRE, decodes on any OS the game runs
 * on, and can be built on a laptop with ffmpeg in one command
 * (see {@code tools/video/pack.py}).</p>
 *
 * <p>A clip lives either in a directory or in a ZIP with the same layout:</p>
 * <pre>
 * meta.json      {"fps":12,"frames":240,"title":"...","audio":"audio.wav"}
 * f000001.jpg    frames, zero padded so plain name sort is playback order
 * f000002.jpg
 * audio.wav      optional
 * </pre>
 *
 * <p>{@code meta.json} is optional: without it the frame rate falls back to
 * {@link #DEFAULT_FPS} and the frame count is however many images are there.
 * Every image is decoded on demand, so opening a clip costs one directory
 * listing, not four hundred megabytes of bitmaps.</p>
 *
 * <p>Nothing in this class touches Minecraft, which is why the same code runs
 * in the GUI thread, the decode thread and (for tests) a plain JVM.</p>
 */
public final class VideoClip implements Closeable {

    /** Frame rate assumed when {@code meta.json} does not say. */
    public static final int DEFAULT_FPS = 12;

    /** Hard ceiling, so a pathological folder cannot make the player loop forever. */
    public static final int MAX_FRAMES = 20000;

    private final String name;
    private final String descriptor;
    private final ZipFile zip;
    private final File dir;
    private final List<String> entries;
    private final int fps;
    private final int frames;
    private final String title;
    private final String audioEntry;

    private VideoClip(String name, String descriptor, ZipFile zip, File dir, List<String> entries,
                      int fps, int frames, String title, String audioEntry) {
        this.name = name;
        this.descriptor = descriptor;
        this.zip = zip;
        this.dir = dir;
        this.entries = entries;
        this.fps = fps;
        this.frames = frames;
        this.title = title;
        this.audioEntry = audioEntry;
    }

    // ------------------------------------------------------------------ open

    /**
     * Opens a clip from a ZIP or a directory.
     *
     * @return null when the source holds no readable frame
     */
    public static VideoClip open(String name, File source) {
        if (source == null || !source.exists()) {
            return null;
        }
        try {
            if (source.isDirectory()) {
                return openDir(name, source);
            }
            if (source.getName().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                return openZip(name, source);
            }
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("hbmzz-video")
                    .warn("cannot open video '{}': {}", name, e.toString());
        }
        return null;
    }

    private static VideoClip openDir(String name, File dir) throws IOException {
        List<String> entries = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && isImage(f.getName())) {
                    entries.add(f.getName());
                }
            }
        }
        Collections.sort(entries);
        if (entries.isEmpty()) {
            return null;
        }
        JsonObject meta = readMeta(new File(dir, "meta.json"));
        boolean hasAudio = new File(dir, "audio.wav").isFile();
        return build(name, dir.getPath(), null, dir, entries, meta, hasAudio,
                hasAudio ? "audio.wav" : null);
    }

    private static VideoClip openZip(String name, File file) throws IOException {
        ZipFile zip = new ZipFile(file);
        List<String> entries = new ArrayList<>();
        boolean hasAudio = false;
        String[] audioName = new String[1];
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (entry.isDirectory()) {
                continue;
            }
            String entryName = entry.getName();
            if (isImage(entryName)) {
                entries.add(entryName);
            } else if (entryName.equalsIgnoreCase("audio.wav")) {
                hasAudio = true;
                audioName[0] = entryName;
            }
        }
        Collections.sort(entries);
        if (entries.isEmpty()) {
            zip.close();
            return null;
        }
        JsonObject meta = null;
        ZipEntry metaEntry = zip.getEntry("meta.json");
        if (metaEntry != null) {
            try (InputStream in = zip.getInputStream(metaEntry)) {
                meta = parseMeta(in);
            }
        }
        // meta.json may point at a differently named audio track; the default is
        // still audio.wav so the common case needs no JSON at all.
        if (meta != null && meta.has("audio")) {
            String declared = meta.get("audio").getAsString();
            if (zip.getEntry(declared) != null) {
                hasAudio = true;
                audioName[0] = declared;
            }
        }
        return build(name, file.getPath(), zip, null, entries, meta, hasAudio, audioName[0]);
    }

    private static VideoClip build(String name, String descriptor, ZipFile zip, File dir,
                                   List<String> entries, JsonObject meta, boolean hasAudio,
                                   String audioEntry) {
        int fps = DEFAULT_FPS;
        String title = name;
        int declaredFrames = -1;
        if (meta != null) {
            if (meta.has("fps")) {
                try {
                    fps = meta.get("fps").getAsInt();
                } catch (RuntimeException ignored) {
                }
            }
            if (meta.has("title")) {
                title = meta.get("title").getAsString();
            }
            if (meta.has("frames")) {
                try {
                    declaredFrames = meta.get("frames").getAsInt();
                } catch (RuntimeException ignored) {
                }
            }
        }
        fps = Math.max(1, Math.min(60, fps));
        int frames = entries.size();
        // A declared count larger than what is on disk would make the player wait
        // for frames that will never arrive, so the smaller of the two wins.
        if (declaredFrames > 0) {
            frames = Math.min(frames, declaredFrames);
        }
        frames = Math.min(frames, MAX_FRAMES);
        return new VideoClip(name, descriptor, zip, dir, entries, fps, frames, title,
                hasAudio ? audioEntry : null);
    }

    private static boolean isImage(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private static JsonObject readMeta(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return parseMeta(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonObject parseMeta(InputStream in) {
        try {
            return new JsonParser().parse(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ read

    /**
     * Decodes one frame. Blocking and CPU-bound by nature: call it off the
     * render thread (the player does).
     */
    public BufferedImage readFrame(int index) throws IOException {
        if (index < 0 || index >= this.frames) {
            throw new IOException("frame " + index + " out of range (0.." + this.frames + ")");
        }
        String entry = this.entries.get(index);
        try (InputStream in = openEntry(entry)) {
            BufferedImage image = ImageIO.read(in);
            if (image == null) {
                throw new IOException("not a readable image: " + entry);
            }
            return image;
        }
    }

    /** The declared audio track, or null when the clip has none. */
    public InputStream openAudio() throws IOException {
        if (this.audioEntry == null) {
            return null;
        }
        return new BufferedInputStream(openEntry(this.audioEntry), 8192);
    }

    private InputStream openEntry(String entry) throws IOException {
        if (this.zip != null) {
            ZipEntry zipEntry = this.zip.getEntry(entry);
            if (zipEntry == null) {
                throw new IOException("missing entry " + entry);
            }
            return this.zip.getInputStream(zipEntry);
        }
        return new FileInputStream(new File(this.dir, entry));
    }

    // ---------------------------------------------------------------- access

    public String name() {
        return this.name;
    }

    /** Human-readable location, used by the "file is missing" panel. */
    public String descriptor() {
        return this.descriptor;
    }

    public int fps() {
        return this.fps;
    }

    public int frameCount() {
        return this.frames;
    }

    public String title() {
        return this.title;
    }

    public boolean hasAudio() {
        return this.audioEntry != null;
    }

    public double durationSeconds() {
        return (double) this.frames / this.fps;
    }

    @Override
    public void close() {
        if (this.zip != null) {
            try {
                this.zip.close();
            } catch (IOException ignored) {
            }
        }
    }
}

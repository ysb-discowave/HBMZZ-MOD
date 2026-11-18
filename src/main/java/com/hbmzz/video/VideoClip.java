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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One video: a numbered sequence of still frames, a frame rate, a loop policy
 * and an optional soundtrack.
 *
 * <p>There is no codec here on purpose. Minecraft 1.12.2 ships a JRE, not an
 * H.264 decoder, and every "video mod" that bolted one on ended up shipping
 * native binaries per platform. A frame sequence plus {@link ImageIO} and an
 * optional WAV track needs nothing but the JRE, decodes on any OS the game runs
 * on, and can be built on a laptop with ffmpeg in one command
 * (see {@code tools/video/pack.py}).</p>
 *
 * <h3>What is on the shelf</h3>
 * <pre>
 * meta.json      {"fps":12,"frames":240,"title":"...","audio":"audio.wav",
 *                 "loop":true,"order":"list","playlist":["f000002.jpg","f000001.jpg"]}
 * desc.txt       Android 开机动画格式（可选，和 meta.json 二选一）
 * f000001.jpg    frames
 * f000002.jpg
 * audio.wav      optional
 * </pre>
 *
 * <h3>The three questions a frame pack has to answer</h3>
 * <ul>
 *   <li><b>Order.</b> Files are sorted <em>naturally</em> - {@code f2} before
 *       {@code f10}, which plain string sort gets backwards. A pack that wants
 *       something else can say so: {@code "order":"list"} plus a
 *       {@code "playlist"} of file names, or a boot-animation {@code desc.txt}
 *       whose {@code p} lines name part folders in order. Explicit order wins
 *       over the file system, always - the point of an animation is that the
 *       author decided what comes next.</li>
 *   <li><b>Speed.</b> {@code fps} is a number, not an integer, and is clamped
 *       to {@link #MIN_FPS}..{@link #MAX_FPS}. A frame therefore lasts
 *       {@code 1/fps} seconds; 0.5 fps is a two-second slideshow, 60 fps is
 *       smooth motion.</li>
 *   <li><b>Looping.</b> {@code "loop"} takes {@code true} (forever),
 *       {@code false} (once), {@code "pingpong"} (forward then back), or an
 *       integer (that many passes). A broadcast forces one pass regardless,
 *       because a full-screen takeover that never ends is a trap.</li>
 * </ul>
 *
 * <h3>Timeline, not a second frame list</h3>
 * <p>{@code entries} is the set of image files, each listed once.
 * {@code timeline} is what plays: a slot may point at a frame that already
 * appeared (a hold, a repeated part), so "freeze the logo for two seconds" or
 * "play part0 twice" costs no extra images. {@link #entryAt(int)} translates a
 * slot to a file; {@link #slotAt(long)} translates elapsed playback into a slot,
 * applying the loop mode.</p>
 *
 * <p>Every frame is decoded on demand, so opening a clip costs one directory
 * listing, not four hundred megabytes of bitmaps. Nothing in this class touches
 * Minecraft, which is why the same code runs in the GUI thread, the decode
 * thread and (for tests) a plain JVM.</p>
 */
public final class VideoClip implements Closeable {

    /** How the clip repeats when nothing else says. */
    public static final LoopMode DEFAULT_LOOP = LoopMode.LOOP;

    /** Frame rate assumed when {@code meta.json} does not say. */
    public static final int DEFAULT_FPS = 12;

    /** Hard ceiling, so a pathological folder cannot make the player loop forever. */
    public static final int MAX_FRAMES = 20000;

    /** Slowest useful animation: one frame every two seconds. */
    public static final double MIN_FPS = 0.5D;

    /** Fastest rate the decode ring can still keep up with on a 20 TPS client. */
    public static final double MAX_FPS = 60.0D;

    /** A {@code desc.txt} pause longer than this is a typo, not an intent. */
    private static final double MAX_PAUSE_SECONDS = 10.0D;

    /** Most passes a clip may declare; {@code 0} means "forever" in the file. */
    private static final int MAX_PASSES = 1000;

    /** What the clip does at the end of a pass. */
    public enum LoopMode {
        /** Play once, then hold (or hand control back, depending on the caller). */
        ONCE,
        /** Restart from the first frame. */
        LOOP,
        /** Forward, then backward, then forward again. */
        PING_PONG;

        public static LoopMode parse(String text) {
            if (text == null) {
                return null;
            }
            String value = text.trim().toLowerCase(Locale.ROOT);
            if (value.equals("once") || value.equals("single") || value.equals("none")
                    || value.equals("off") || value.equals("false")) {
                return ONCE;
            }
            if (value.equals("loop") || value.equals("repeat") || value.equals("true")) {
                return LOOP;
            }
            if (value.equals("pingpong") || value.equals("ping-pong") || value.equals("boomerang")
                    || value.equals("reverse")) {
                return PING_PONG;
            }
            return null;
        }
    }

    private final String name;
    private final String descriptor;
    private final ZipFile zip;
    private final File dir;
    /** Distinct frame files, in playback order. */
    private final List<String> entries;
    /** One loop: slot -> index into {@link #entries}. */
    private final int[] timeline;
    private final double fps;
    private final String title;
    private final String audioEntry;
    private final double audioOffset;
    private final LoopMode loopMode;
    /** Passes before holding; {@code -1} means forever. */
    private final int passes;

    private VideoClip(String name, String descriptor, ZipFile zip, File dir, List<String> entries,
                      int[] timeline, double fps, String title, String audioEntry, double audioOffset,
                      LoopMode loopMode, int passes) {
        this.name = name;
        this.descriptor = descriptor;
        this.zip = zip;
        this.dir = dir;
        this.entries = entries;
        this.timeline = timeline;
        this.fps = fps;
        this.title = title;
        this.audioEntry = audioEntry;
        this.audioOffset = audioOffset;
        this.loopMode = loopMode;
        this.passes = passes;
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
            log().warn("cannot open video '{}': {}", name, e.toString());
        }
        return null;
    }

    private static VideoClip openDir(String name, File dir) throws IOException {
        List<String> all = new ArrayList<>();
        // Recursive, with '/' separators: a boot-animation pack keeps its frames
        // in part folders, and a flat listing would find no frames at all.
        collectFiles(dir, "", all, 0);
        Collections.sort(all, NATURAL);
        List<String> images = imagesOf(all);
        if (images.isEmpty()) {
            return null;
        }
        JsonObject meta = readMeta(new File(dir, "meta.json"));
        String descText = readText(new File(dir, "desc.txt"));
        return build(name, dir.getPath(), null, dir, all, images, meta, descText);
    }

    /** Lists files under {@code dir} as relative '/'-joined paths. */
    private static void collectFiles(File dir, String prefix, List<String> out, int depth) {
        if (depth > 8) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.getName().startsWith(".")) {
                continue;
            }
            String path = prefix.isEmpty() ? f.getName() : prefix + "/" + f.getName();
            if (f.isDirectory()) {
                collectFiles(f, path, out, depth + 1);
            } else if (f.isFile()) {
                out.add(path);
            }
        }
    }

    private static VideoClip openZip(String name, File file) throws IOException {
        ZipFile zip = new ZipFile(file);
        List<String> all = new ArrayList<>();
        for (ZipEntry entry : Collections.list(zip.entries())) {
            if (!entry.isDirectory()) {
                all.add(entry.getName());
            }
        }
        Collections.sort(all, NATURAL);
        List<String> images = imagesOf(all);
        if (images.isEmpty()) {
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
        String descText = null;
        ZipEntry descEntry = zip.getEntry("desc.txt");
        if (descEntry != null) {
            try (InputStream in = zip.getInputStream(descEntry)) {
                descText = readAll(in);
            }
        }
        return build(name, file.getPath(), zip, null, all, images, meta, descText);
    }

    private static VideoClip build(String name, String descriptor, ZipFile zip, File dir,
                                   List<String> all, List<String> images, JsonObject meta, String descText) {
        Desc desc = parseDesc(descText);

        double fps = DEFAULT_FPS;
        if (desc != null && desc.fps > 0.0D) {
            fps = desc.fps;
        }
        if (meta != null && meta.has("fps")) {
            try {
                fps = meta.get("fps").getAsDouble();
            } catch (RuntimeException ignored) {
            }
        }
        fps = Math.max(MIN_FPS, Math.min(MAX_FPS, fps));

        Layout layout = null;
        if (desc != null) {
            layout = layoutFromDesc(desc, images, fps);
        }
        if (layout == null) {
            layout = layoutFromPlaylist(meta, images);
        }
        if (layout == null) {
            layout = layoutSequential(images);
        }

        // "frames" is a claim about the pack, not an instruction: a file that
        // declares more frames than it carries would make the player wait for
        // images that will never arrive, so the smaller number always wins.
        int declared = metaInt(meta, "frames", -1);
        if (declared > 0) {
            trimTo(layout, declared);
        }
        trimTo(layout, MAX_FRAMES);

        LoopMode mode = DEFAULT_LOOP;
        int passes = -1;
        String title = name;
        double audioOffset = 0.0D;
        String audioEntry = null;

        if (meta != null) {
            LoopMode declaredMode = LoopMode.parse(metaString(meta, "loopMode", null));
            if (declaredMode != null) {
                mode = declaredMode;
                passes = declaredMode == LoopMode.ONCE ? 1 : -1;
            }
            if (meta.has("loop")) {
                LoopMode fromLoop = parseLoop(meta.get("loop"));
                if (fromLoop != null) {
                    mode = fromLoop;
                    passes = fromLoop == LoopMode.ONCE ? 1 : -1;
                } else {
                    int count = metaInt(meta, "loop", 0);
                    if (count > 0) {
                        mode = LoopMode.LOOP;
                        passes = Math.min(count, MAX_PASSES);
                    }
                }
            }
            if (meta.has("repeats")) {
                int count = metaInt(meta, "repeats", -1);
                if (count > 0) {
                    passes = Math.min(count, MAX_PASSES);
                }
            }
            title = metaString(meta, "title", name);
            audioOffset = Math.max(0.0D, metaDouble(meta, "audioOffset", 0.0D));
            String declaredAudio = metaString(meta, "audio", null);
            if (declaredAudio != null && all.contains(declaredAudio)) {
                audioEntry = declaredAudio;
            }
        }
        if (mode == LoopMode.ONCE && passes < 0) {
            passes = 1;
        }
        // meta.json may point at a differently named audio track; the default is
        // still audio.wav so the common case needs no JSON at all.
        if (audioEntry == null) {
            audioEntry = findDefaultAudio(all);
        }
        if (layout.entries.isEmpty()) {
            return null;
        }

        int[] slots = new int[layout.slots.size()];
        for (int i = 0; i < slots.length; i++) {
            slots[i] = layout.slots.get(i);
        }
        return new VideoClip(name, descriptor, zip, dir, layout.entries, slots, fps, title,
                audioEntry, audioOffset, mode, passes);
    }

    // -------------------------------------------------------------- ordering

    /**
     * The layout of one loop: files, each once, plus the slot sequence that
     * plays them.
     */
    private static final class Layout {
        final List<String> entries = new ArrayList<>();
        final List<Integer> slots = new ArrayList<>();
    }

    private static Layout layoutSequential(List<String> images) {
        Layout layout = new Layout();
        for (String image : images) {
            layout.entries.add(image);
            layout.slots.add(layout.entries.size() - 1);
        }
        return layout;
    }

    /** {@code "order":"list"} + {@code "playlist":[...]}; null when unusable. */
    private static Layout layoutFromPlaylist(JsonObject meta, List<String> images) {
        if (meta == null || !"list".equalsIgnoreCase(metaString(meta, "order", ""))) {
            return null;
        }
        JsonElement playlist = meta.get("playlist");
        if (playlist == null || !playlist.isJsonArray()) {
            return null;
        }
        Layout layout = new Layout();
        JsonArray array = playlist.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String resolved = resolve(images, element.getAsString());
            if (resolved != null && !layout.entries.contains(resolved)) {
                layout.entries.add(resolved);
            }
        }
        if (layout.entries.isEmpty()) {
            // Every name in the list was wrong. Falling back to the file system
            // beats playing nothing at all, and the log line says why.
            log().warn("clip playlist names no existing frame; falling back to file order");
            return null;
        }
        for (int i = 0; i < layout.entries.size(); i++) {
            layout.slots.add(i);
        }
        return layout;
    }

    /** Boot-animation parts: repeat counts and pauses become timeline slots. */
    private static Layout layoutFromDesc(Desc desc, List<String> images, double fps) {
        Layout layout = new Layout();
        for (Desc.Part part : desc.parts) {
            String prefix = part.folder.isEmpty() ? "" : part.folder + "/";
            List<String> files = new ArrayList<>();
            for (String image : images) {
                boolean inside = part.folder.isEmpty() || image.startsWith(prefix);
                if (inside) {
                    files.add(image);
                }
            }
            if (files.isEmpty()) {
                continue;
            }
            int[] indexes = new int[files.size()];
            for (int i = 0; i < files.size(); i++) {
                int existing = layout.entries.indexOf(files.get(i));
                if (existing < 0) {
                    layout.entries.add(files.get(i));
                    existing = layout.entries.size() - 1;
                }
                indexes[i] = existing;
            }
            int repeat = Math.max(1, Math.min(part.repeat, MAX_PASSES));
            for (int r = 0; r < repeat; r++) {
                for (int index : indexes) {
                    layout.slots.add(index);
                }
            }
            int hold = (int) Math.round(part.pause * fps);
            hold = Math.max(0, Math.min(hold, (int) Math.round(MAX_PAUSE_SECONDS * fps)));
            for (int h = 0; h < hold; h++) {
                layout.slots.add(indexes[indexes.length - 1]);
            }
        }
        return layout.entries.isEmpty() ? null : layout;
    }

    /** Drops frames past {@code limit} and every slot that pointed at them. */
    private static void trimTo(Layout layout, int limit) {
        if (layout.entries.size() > limit) {
            layout.entries.subList(limit, layout.entries.size()).clear();
        }
        int max = layout.entries.size();
        for (int i = layout.slots.size() - 1; i >= 0; i--) {
            if (layout.slots.get(i) >= max) {
                layout.slots.remove(i);
            }
        }
        if (layout.slots.size() > MAX_FRAMES) {
            layout.slots.subList(MAX_FRAMES, layout.slots.size()).clear();
        }
    }

    /** Exact name first, then a unique basename - playlists are often hand-written. */
    private static String resolve(List<String> images, String wanted) {
        if (wanted == null) {
            return null;
        }
        String trimmed = wanted.trim();
        if (images.contains(trimmed)) {
            return trimmed;
        }
        String found = null;
        for (String image : images) {
            if (baseName(image).equalsIgnoreCase(baseName(trimmed))) {
                if (found != null) {
                    return null;
                }
                found = image;
            }
        }
        return found;
    }

    private static String baseName(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /**
     * Natural order: {@code f2} sorts before {@code f10}.
     *
     * <p>Plain {@link String#compareTo} puts {@code f10} before {@code f2},
     * which turns any pack that did not zero-pad its numbering into a shuffled
     * mess. Comparing digit runs by value fixes that without demanding a naming
     * convention from the artist.</p>
     */
    private static final Comparator<String> NATURAL = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            int i = 0;
            int j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i);
                char cb = b.charAt(j);
                if (isDigit(ca) && isDigit(cb)) {
                    int endA = i;
                    while (endA < a.length() && isDigit(a.charAt(endA))) {
                        endA++;
                    }
                    int endB = j;
                    while (endB < b.length() && isDigit(b.charAt(endB))) {
                        endB++;
                    }
                    int startA = i;
                    while (startA < endA - 1 && a.charAt(startA) == '0') {
                        startA++;
                    }
                    int startB = j;
                    while (startB < endB - 1 && b.charAt(startB) == '0') {
                        startB++;
                    }
                    int lengthA = endA - startA;
                    int lengthB = endB - startB;
                    if (lengthA != lengthB) {
                        return lengthA - lengthB;
                    }
                    for (int k = 0; k < lengthA; k++) {
                        char x = a.charAt(startA + k);
                        char y = b.charAt(startB + k);
                        if (x != y) {
                            return x - y;
                        }
                    }
                    i = endA;
                    j = endB;
                } else {
                    char la = Character.toLowerCase(ca);
                    char lb = Character.toLowerCase(cb);
                    if (la != lb) {
                        return la - lb;
                    }
                    if (ca != cb) {
                        return ca - cb;
                    }
                    i++;
                    j++;
                }
            }
            return (a.length() - i) - (b.length() - j);
        }
    };

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ------------------------------------------------------------------ meta

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

    private static String metaString(JsonObject meta, String key, String fallback) {
        if (meta == null || !meta.has(key)) {
            return fallback;
        }
        try {
            JsonElement element = meta.get(key);
            return element.isJsonPrimitive() ? element.getAsString() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int metaInt(JsonObject meta, String key, int fallback) {
        if (meta == null || !meta.has(key)) {
            return fallback;
        }
        try {
            return meta.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double metaDouble(JsonObject meta, String key, double fallback) {
        if (meta == null || !meta.has(key)) {
            return fallback;
        }
        try {
            return meta.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** {@code loop} as a mode; null means "it is a number, read it as passes". */
    private static LoopMode parseLoop(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            com.google.gson.JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean() ? LoopMode.LOOP : LoopMode.ONCE;
            }
            if (primitive.isNumber()) {
                return null;
            }
            if (primitive.isString()) {
                // A number written as text ("loop":"3") is a pass count, not a
                // mode: return null so the integer branch below reads it.
                return LoopMode.parse(primitive.getAsString());
            }
        }
        return null;
    }

    // ------------------------------------------------------------- desc.txt

    /** Parsed {@code desc.txt}: the boot-animation format. */
    private static final class Desc {
        final double fps;
        final List<Part> parts;

        Desc(double fps, List<Part> parts) {
            this.fps = fps;
            this.parts = parts;
        }

        static final class Part {
            final String folder;
            final int repeat;
            final double pause;

            Part(String folder, int repeat, double pause) {
                this.folder = folder;
                this.repeat = repeat;
                this.pause = pause;
            }
        }
    }

    /**
     * Parses {@code desc.txt}:
     * <pre>
     * 854 480 24          width height fps
     * p 2 0 part0         2 passes, no pause, frames from part0/
     * p 1 1.5 part1       once, then freeze the last frame for 1.5 s
     * c 1 0 part0         "c" (clock) behaves like "p" here
     * </pre>
     * A malformed file returns null and the pack falls back to meta.json.
     */
    private static Desc parseDesc(String text) {
        if (text == null) {
            return null;
        }
        double fps = 0.0D;
        boolean header = false;
        List<Desc.Part> parts = new ArrayList<>();
        for (String rawLine : text.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] tokens = line.split("\\s+");
            if (!header) {
                if (tokens.length >= 3 && isNumber(tokens[0]) && isNumber(tokens[1]) && isNumber(tokens[2])) {
                    fps = parseNumber(tokens[2], 0.0D);
                    header = true;
                }
                // The first line is always the header, valid or not; never a part.
                continue;
            }
            boolean play = tokens[0].equalsIgnoreCase("p") || tokens[0].equalsIgnoreCase("c");
            if (!play || tokens.length < 4) {
                continue;
            }
            int repeat = (int) parseNumber(tokens[1], 1.0D);
            double pause = Math.max(0.0D, parseNumber(tokens[2], 0.0D));
            parts.add(new Desc.Part(tokens[3], repeat, pause));
        }
        return parts.isEmpty() ? null : new Desc(fps, parts);
    }

    private static boolean isNumber(String text) {
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static double parseNumber(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ----------------------------------------------------------------- files

    private static List<String> imagesOf(List<String> all) {
        List<String> images = new ArrayList<>();
        for (String entry : all) {
            if (isImage(entry) && !isNoise(entry)) {
                images.add(entry);
            }
        }
        return images;
    }

    private static boolean isImage(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    /** Archive droppings: macOS resource forks and editor backups are not frames. */
    private static boolean isNoise(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        if (lower.startsWith("__macosx/") || lower.contains("/__macosx/")) {
            return true;
        }
        String base = baseName(entryName);
        return base.startsWith(".") || lower.endsWith(".png~") || lower.endsWith(".jpg~");
    }

    private static String findDefaultAudio(List<String> all) {
        String[] names = {"audio.wav", "audio.aiff", "audio.aif", "audio.au"};
        String best = null;
        for (String entry : all) {
            String base = baseName(entry);
            for (String name : names) {
                if (base.equalsIgnoreCase(name)) {
                    if (best == null || entry.length() < best.length()) {
                        best = entry;
                    }
                }
            }
        }
        return best;
    }

    private static String readText(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static org.apache.logging.log4j.Logger log() {
        return org.apache.logging.log4j.LogManager.getLogger("hbmzz-video");
    }

    // --------------------------------------------------------------- timeline

    /** Frames in the pack, each counted once. */
    public int frameCount() {
        return this.entries.size();
    }

    /** Slots in one loop, including holds and repeated parts. */
    public int slotCount() {
        return this.timeline.length;
    }

    /** Which frame a slot shows. */
    public int entryAt(int slot) {
        if (this.timeline.length == 0) {
            return 0;
        }
        int index = slot % this.timeline.length;
        if (index < 0) {
            index += this.timeline.length;
        }
        return this.timeline[index];
    }

    /** Seconds per frame, from {@code fps}. */
    public double frameSeconds() {
        return 1.0D / this.fps;
    }

    /** Slots in one complete cycle: forward+back for ping-pong. */
    public long periodSlots() {
        int slots = this.timeline.length;
        if (this.loopMode == LoopMode.PING_PONG && slots > 1) {
            return 2L * slots - 2L;
        }
        return Math.max(1, slots);
    }

    /** Seconds per cycle. */
    public double cycleSeconds() {
        return this.periodSlots() / this.fps;
    }

    /** Seconds of forward motion inside one cycle (all of it unless ping-pong). */
    public double forwardSeconds() {
        return this.timeline.length / this.fps;
    }

    /**
     * Elapsed seconds to a monotonic slot position.
     *
     * <p>Position only ever grows: a looping clip keeps counting past its slot
     * count, and the loop is folded back in {@link #slotAt(long)}. That is what
     * lets the decoder simply walk forward - including over the seam between
     * the end of a pack and its first frame - and lets the frame cache evict by
     * "older than the playhead" without special cases.</p>
     */
    public int positionAt(double seconds) {
        long raw = (long) Math.floor(Math.max(0.0D, seconds) * this.fps);
        if (this.passes > 0) {
            long limit = (long) this.passes * periodSlots();
            if (raw > limit - 1) {
                raw = limit - 1;
            }
        }
        return (int) Math.min(raw, Integer.MAX_VALUE - 2);
    }

    /** Position one past the last playable slot; meaningful when passes are finite. */
    public int lastPosition() {
        if (this.passes <= 0) {
            return Integer.MAX_VALUE - 2;
        }
        long last = (long) this.passes * periodSlots() - 1;
        return (int) Math.min(Math.max(0L, last), Integer.MAX_VALUE - 2);
    }

    /** A monotonic position to the slot it shows, honouring ping-pong. */
    public int slotAt(long position) {
        int slots = this.timeline.length;
        if (slots <= 0) {
            return 0;
        }
        long period = periodSlots();
        long k = position % period;
        if (k < 0) {
            k += period;
        }
        if (this.loopMode == LoopMode.PING_PONG && slots > 1) {
            return (int) (k < slots ? k : period - k);
        }
        return (int) (k % slots);
    }

    /** False on the backward half of a ping-pong cycle. */
    public boolean forwardAt(long position) {
        if (this.loopMode != LoopMode.PING_PONG || this.timeline.length <= 1) {
            return true;
        }
        long period = periodSlots();
        long k = position % period;
        if (k < 0) {
            k += period;
        }
        return k < this.timeline.length;
    }

    // ------------------------------------------------------------------ read

    /**
     * Decodes one frame. Blocking and CPU-bound by nature: call it off the
     * render thread (the player does).
     */
    public BufferedImage readFrame(int index) throws IOException {
        if (index < 0 || index >= this.entries.size()) {
            throw new IOException("frame " + index + " out of range (0.." + this.entries.size() + ")");
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

    public double fps() {
        return this.fps;
    }

    public String title() {
        return this.title;
    }

    public boolean hasAudio() {
        return this.audioEntry != null;
    }

    /** Seconds into the track where the animation's first frame lines up. */
    public double audioOffset() {
        return this.audioOffset;
    }

    public LoopMode loopMode() {
        return this.loopMode;
    }

    /** Passes before holding; {@code -1} means it never stops. */
    public int passes() {
        return this.passes;
    }

    public boolean repeating() {
        return this.loopMode != LoopMode.ONCE && (this.passes < 0 || this.passes > 1);
    }

    /** One cycle, kept for the progress bar and old callers. */
    public double durationSeconds() {
        return cycleSeconds();
    }

    /** Whole runtime, or {@code -1} when the clip loops forever. */
    public double totalSeconds() {
        return this.passes <= 0 ? -1.0D : this.passes * cycleSeconds();
    }

    /**
     * The same clip with the loop policy forced to one pass.
     *
     * <p>A broadcast takes over the whole screen and must give it back; a clip
     * declaring {@code "loop":true} would otherwise hold the player hostage
     * until they alt-F4'd. The forced copy shares the archive handle, so only
     * one of the two may ever be used and closed.</p>
     */
    public VideoClip asSinglePass() {
        if (this.loopMode == LoopMode.ONCE && this.passes == 1) {
            return this;
        }
        return new VideoClip(this.name, this.descriptor, this.zip, this.dir, this.entries,
                this.timeline, this.fps, this.title, this.audioEntry, this.audioOffset,
                LoopMode.ONCE, 1);
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

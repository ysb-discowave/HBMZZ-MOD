package com.rsaudio.client;

import com.rsaudio.Config;
import com.rsaudio.RsAudio;
import net.minecraft.util.ResourceLocation;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * config/rsaudio/audio 目录里音频文件的索引。
 * 文件名会被规范化成 Minecraft 能识别的资源名（小写字母/数字/_/-）。
 */
public class AudioLibrary {

    public static class Entry {
        /** 规范化后的名字，用于资源包和 sounds.json */
        public final String soundName;
        /** 界面里显示的名字（原始文件名，去掉后缀） */
        public final String displayName;
        public final File file;

        Entry(String soundName, String displayName, File file) {
            this.soundName = soundName;
            this.displayName = displayName;
            this.file = file;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<Entry>();
    private static boolean scanned = false;

    /**
     * 目录里发现的、模组<b>放不了</b>的音频文件（比如 mp3）。
     * <p>
     * 1.12.2 的声音系统只注册了 Ogg Vorbis 解码器（SoundSystemConfig.setCodec("ogg", CodecJOrbis.class)），
     * mp3 / m4a / wav 一律读不出来。玩家把 mp3 丢进来只会"什么都没发生"，
     * 所以这里统计一下，在界面里明确告诉他要先转成 ogg。
     */
    private static final Set<String> UNPLAYABLE_EXTENSIONS =
            new HashSet<String>(Arrays.asList("mp3", "m4a", "mp4", "wav", "flac",
                    "aac", "wma", "opus", "oga", "aiff", "aif", "ape", "mid", "midi"));
    private static int unplayableCount = 0;
    private static final Set<String> unplayableTypes = new TreeSet<String>();

    public static List<Entry> getEntries() {
        if (!scanned) {
            rescan();
        }
        return ENTRIES;
    }

    /** 目录里有多少个"1.12.2 放不了"的音频文件（mp3 等） */
    public static int getUnplayableCount() {
        return unplayableCount;
    }

    /** 这些文件都是什么后缀，例如 "mp3" */
    public static String getUnplayableTypes() {
        return unplayableTypes.toString().replaceAll("[\\[\\]]", "");
    }

    public static ResourceLocation soundLocation(String soundName) {
        return new ResourceLocation(RsAudio.MODID, soundName);
    }

    /** 按规范化后的名字找到原始文件（读时长要用）；找不到返回 null */
    public static File fileOf(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        }
        for (Entry entry : getEntries()) {
            if (entry.soundName.equals(soundName)) {
                return entry.file;
            }
        }
        return null;
    }

    /**
     * 按<b>原始文件名</b>（不含后缀）反查规范化后的 {@code soundName}。
     * <p>
     * ★ 为什么要这么一条别扭的 API：{@link #sanitize} 会把中文名整个压成一串下划线，
     * 于是 "广播 (10).ogg" → {@code 10} → {@code track_10}、"法斯广播.ogg" → {@code track_1f2a}…
     * 名字里除了数字什么都不剩，重名还要靠文件名哈希去消歧 —— <b>那个哈希是不可预测的</b>
     * （它以"当前目录里有哪些文件、按什么顺序扫"为前提）。
     * 所以代码里<b>绝不能</b>写死 {@code "track_10"} 这种规范化名：
     * 玩家往 audio 目录里多丢一首歌，整个映射就全错位了，而且不报错、只是"放错音乐"。
     * <p>
     * 反过来按"人看到的那个文件名"去查，就和玩家往目录里添加/删除文件这件事解耦了。
     * 找不到返回 {@code null}（调用方应当记一条日志，而不是静默不播）。
     *
     * @param displayName 原始文件名，带不带 {@code .ogg} 后缀都行
     */
    public static String soundNameOf(String displayName) {
        if (displayName == null || displayName.isEmpty()) {
            return null;
        }
        String want = displayName.trim();
        if (want.toLowerCase(Locale.ROOT).endsWith(".ogg")) {
            want = want.substring(0, want.length() - 4);
        }
        for (Entry entry : getEntries()) {
            if (entry.displayName.equalsIgnoreCase(want)) {
                return entry.soundName;
            }
        }
        return null;
    }

    /**
     * 模糊查找：找第一个文件名里<b>包含</b>关键字的条目。
     * <p>
     * 用来给"我知道大概有这么一首、但记不准全名"的场景兜底（比如
     * {@code 新版本P1融毁音乐} 与 {@code 新版本P2反应堆融毁音乐} 这种长名字）。
     * 精确匹配优先由 {@link #soundNameOf} 负责。
     */
    public static String soundNameContaining(String keyword) {
        if (keyword == null || keyword.isEmpty()) {
            return null;
        }
        for (Entry entry : getEntries()) {
            if (entry.displayName.contains(keyword)) {
                return entry.soundName;
            }
        }
        return null;
    }

    /** 重新扫描目录 */
    public static void rescan() {
        scanned = true;
        ENTRIES.clear();
        unplayableCount = 0;
        unplayableTypes.clear();
        File dir = Config.getAudioDir();
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        Set<String> used = new HashSet<String>();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String lower = file.getName().toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".ogg")) {
                if (!file.getName().startsWith(".")) {
                    int dot = lower.lastIndexOf('.');
                    String ext = dot > 0 ? lower.substring(dot + 1) : "";
                    if (UNPLAYABLE_EXTENSIONS.contains(ext)) {
                        unplayableCount++;
                        unplayableTypes.add(ext);
                    }
                }
                continue;
            }
            String base = sanitize(file.getName());
            String name = base;
            if (!used.add(name)) {
                // 重名：用「原文件名哈希」做后缀，结果只跟文件名有关、不受扫描顺序影响。
                // 这样以后增删文件不会让已经选好的曲目错位。
                String suffix = Integer.toHexString(file.getName().hashCode() & 0xFFFF);
                name = base + "_" + suffix;
                int i = 2;
                while (!used.add(name)) {
                    name = base + "_" + suffix + "_" + i;
                    i++;
                }
            }
            String display = file.getName().substring(0, file.getName().length() - 4);
            ENTRIES.add(new Entry(name, display, file));
        }
        Collections.sort(ENTRIES, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        RsAudio.log("扫描到 %d 个音频文件", ENTRIES.size());
        if (unplayableCount > 0) {
            RsAudio.warn("音频目录里有 %d 个 1.12.2 无法播放的文件(%s)，需要先转成 .ogg",
                    unplayableCount, getUnplayableTypes());
        }
    }

    /** 把文件名转换成合法的资源路径：只保留 a-z 0-9 _ - */
    public static String sanitize(String fileName) {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '/') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        // 去掉路径分隔符，避免生成子目录
        out = out.replace('/', '_');
        if (out.isEmpty()) {
            out = "track";
        } else if (out.matches("[0-9]+")) {
            // 纯中文名（例如「人声 (10)」）会被压成纯数字，加个前缀更好认
            out = "track_" + out;
        }
        return out;
    }
}

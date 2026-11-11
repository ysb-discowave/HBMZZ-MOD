package com.rsaudio.tts;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * 合成结果的磁盘缓存（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 播报这东西重复率极高 —— "反应堆温度超限"这类句子一晚上能说几十遍，
 * 每次都去跑一遍 TTS 既费 CPU 又让玩家等。所以按内容哈希缓存成 wav 文件，
 * 命中就零成本。
 * <p>
 * 缓存键必须把<b>引擎参数</b>也算进去（模型名 / 语速 / 说话人）：
 * 只按文本哈希的话，玩家换了语音模型之后听到的还是旧声音。
 */
public final class TtsCache {

    private final File dir;
    private final int maxEntries;
    private final long maxBytes;

    public TtsCache(File dir, int maxEntries, long maxBytes) {
        this.dir = dir;
        this.maxEntries = Math.max(1, maxEntries);
        this.maxBytes = Math.max(1024L, maxBytes);
    }

    public static TtsCache standard(File ttsDir) {
        return new TtsCache(new File(ttsDir, "cache"),
                TtsSpec.CACHE_MAX_ENTRIES, TtsSpec.CACHE_MAX_BYTES);
    }

    public File getDir() {
        return dir;
    }

    // ------------------------------------------------------------------ 键

    /**
     * 合成参数的"声音指纹"。任何一个变了，同一个文本也该重新合成。
     */
    public static String voiceTag(String modelFileName, double lengthScale, int speakerId) {
        return (modelFileName == null ? "?" : modelFileName)
                + "#" + String.format(java.util.Locale.ROOT, "%.3f", lengthScale)
                + "#" + speakerId;
    }

    /** 缓存键 = 语音指纹 + 文本。返回十六进制字符串，可直接当文件名 */
    public static String keyFor(String text, String voiceTag) {
        return sha1((voiceTag == null ? "" : voiceTag) + "\u0000" + (text == null ? "" : text));
    }

    public static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 在任何一个 JRE 里都有，走不到这里；真走到了用一个退化的版本，
            // 也绝不能抛异常把调用方带崩
            return "x" + Integer.toHexString(s == null ? 0 : s.hashCode());
        }
    }

    // ------------------------------------------------------------------ 读写

    public File fileFor(String key) {
        return new File(dir, key + ".wav");
    }

    /** 命中返回文件；未命中或文件已损坏（太短）返回 null */
    public File get(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        File f = fileFor(key);
        if (f.isFile() && f.length() > 44L) {
            // 顺手更新访问时间，让 LRU 淘汰知道它还有用
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    /** 写入缓存并返回文件。空间不够时先淘汰 */
    public File put(String key, byte[] data) throws IOException {
        if (key == null || key.isEmpty() || data == null || data.length == 0) {
            return null;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("无法创建缓存目录: " + dir);
        }
        File target = fileFor(key);
        TtsEngine.writeAtomic(target, data);
        evictIfNeeded();
        return target;
    }

    // ------------------------------------------------------------------ 淘汰

    /** 按最近使用时间从旧到新淘汰，直到条目数与总字节数都回到上限内 */
    public int evictIfNeeded() {
        File[] list = dir.listFiles();
        if (list == null || list.length == 0) {
            return 0;
        }
        List<File> wavs = new ArrayList<File>(list.length);
        long total = 0L;
        for (File f : list) {
            if (f.isFile() && f.getName().endsWith(".wav")) {
                wavs.add(f);
                total += f.length();
            }
        }
        if (wavs.size() <= maxEntries && total <= maxBytes) {
            return 0;
        }

        File[] ordered = wavs.toArray(new File[0]);
        Arrays.sort(ordered, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });

        int removed = 0;
        int count = ordered.length;
        for (File f : ordered) {
            if (count <= maxEntries && total <= maxBytes) {
                break;
            }
            long len = f.length();
            if (f.delete()) {
                removed++;
                count--;
                total -= len;
            }
        }
        return removed;
    }

    /** 清空缓存。返回删掉的文件数 */
    public int clear() {
        File[] list = dir.listFiles();
        if (list == null) {
            return 0;
        }
        int removed = 0;
        for (File f : list) {
            if (f.isFile() && f.delete()) {
                removed++;
            }
        }
        return removed;
    }

    public long totalBytes() {
        File[] list = dir.listFiles();
        if (list == null) {
            return 0L;
        }
        long total = 0L;
        for (File f : list) {
            if (f.isFile()) {
                total += f.length();
            }
        }
        return total;
    }

    public int count() {
        File[] list = dir.listFiles();
        if (list == null) {
            return 0;
        }
        int n = 0;
        for (File f : list) {
            if (f.isFile()) {
                n++;
            }
        }
        return n;
    }
}

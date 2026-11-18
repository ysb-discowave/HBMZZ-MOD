package com.rsaudio.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 从 ogg 文件里读出播放时长（秒），不依赖任何解码器。
 * <p>
 * 原理：Ogg 每一页的头部（27 字节）里第 6~13 字节是这一页结束时的
 * <b>granule position</b>；对 Vorbis 来说它就是"到这一页为止的采样总数"。
 * 所以顺序扫一遍所有页头，取最后一个有效的 granule，再除以采样率（在
 * 第一个页的正文里，也就是 Vorbis 识别头 offset 12 处的 4 字节小端整数）就是时长。
 * <p>
 * 只读文件、只走页头，不解码音频，几 MB 的文件毫秒级完成。
 * 注意：解析失败一律返回 -1，调用方要能接受"不知道时长"这种情况。
 */
public final class OggDuration {

    /** 单文件最大处理 64MB，防止误传一个巨大文件把内存吃满 */
    private static final long MAX_BYTES = 64L << 20;

    private OggDuration() {
    }

    /**
     * @return 时长（秒）；文件不存在 / 不是 ogg / 结构异常时返回 -1
     */
    public static double readSeconds(File file) {
        if (file == null || !file.isFile()) {
            return -1.0D;
        }
        byte[] data = readAll(file);
        if (data == null) {
            return -1.0D;
        }

        int sampleRate = -1;
        long lastGranule = -1L;
        int offset = 0;

        while (offset + 27 <= data.length) {
            if (!isCapturePattern(data, offset)) {
                break;
            }
            if (data[offset + 4] != 0) {
                break;                                   // 版本号必须为 0
            }
            int segments = data[offset + 26] & 0xFF;
            int tableEnd = offset + 27 + segments;
            if (tableEnd > data.length) {
                break;
            }
            int bodySize = 0;
            for (int i = 0; i < segments; i++) {
                bodySize += data[offset + 27 + i] & 0xFF;
            }
            int next = tableEnd + bodySize;
            if (next > data.length) {
                break;
            }

            long granule = readLongLE(data, offset + 6);
            if (granule >= 0L) {
                // -1（全 1）表示"本页没有包结束"，是合法的哨兵值，要跳过
                lastGranule = granule;
            }

            // 第一个页的正文开头就是 Vorbis 识别头： 0x01 "vorbis" ...
            if (sampleRate < 0 && bodySize >= 16 && isVorbisIdHeader(data, tableEnd)) {
                sampleRate = readIntLE(data, tableEnd + 12);
            }

            offset = next;
        }

        if (sampleRate <= 0 || lastGranule <= 0L) {
            return -1.0D;
        }
        return lastGranule / (double) sampleRate;
    }

    private static byte[] readAll(File file) {
        long length = file.length();
        if (length <= 0L || length > MAX_BYTES) {
            return null;
        }
        byte[] data = new byte[(int) length];
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return read == data.length ? data : null;
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                    // 关不掉也无所谓
                }
            }
        }
    }

    private static boolean isCapturePattern(byte[] d, int o) {
        return d[o] == 'O' && d[o + 1] == 'g' && d[o + 2] == 'g' && d[o + 3] == 'S';
    }

    private static boolean isVorbisIdHeader(byte[] d, int o) {
        return d[o] == 1 && d[o + 1] == 'v' && d[o + 2] == 'o' && d[o + 3] == 'r'
                && d[o + 4] == 'b' && d[o + 5] == 'i' && d[o + 6] == 's';
    }

    private static long readLongLE(byte[] d, int o) {
        long v = 0L;
        for (int i = 7; i >= 0; i--) {
            v = (v << 8) | (d[o + i] & 0xFFL);
        }
        return v;
    }

    private static int readIntLE(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }
}

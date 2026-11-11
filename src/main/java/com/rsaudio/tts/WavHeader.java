package com.rsaudio.tts;

import java.nio.charset.StandardCharsets;

/**
 * 极简 WAV 头解析（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 为什么要有它：Piper 直接吐 WAV，而我们<b>不走 Minecraft 的资源包</b>去播它
 * （资源包那条路要 {@code refreshResources()}，每说一句话重载一次资源会卡死），
 * 所以客户端是用 {@code javax.sound.sampled} 直接播的。客户端需要知道
 * 采样率 / 声道 / 位深才能建 {@code AudioFormat}，服务端需要知道时长才能决定
 * "这个弹窗该显示多久"和"这个音频该不该发"。
 * <p>
 * 不能假设"piper 就是 22050 单声道 16bit"：voice 模型里写着采样率，
 * 换一个 voice 就变了。所以必须真解析。
 * <p>
 * <b>解析器的关键是老老实实遍历 chunk，不能硬编码 44 字节头</b> ——
 * piper 的输出里 {@code data} 段前面可能还有 {@code LIST} 之类的块，
 * 硬编码偏移会在某些 voice 上读出垃圾。
 */
public final class WavHeader {

    public final int sampleRate;
    public final int channels;
    public final int bitsPerSample;
    /** data 段实际字节数 */
    public final int dataSize;

    public WavHeader(int sampleRate, int channels, int bitsPerSample, int dataSize) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
        this.dataSize = dataSize;
    }

    /** 每秒字节数。参数非法时返回 0（调用方要判 0，别直接除） */
    public long bytesPerSecond() {
        return (long) sampleRate * channels * bitsPerSample / 8L;
    }

    public double durationSeconds() {
        long bps = bytesPerSecond();
        if (bps <= 0L) {
            return 0.0D;
        }
        return dataSize / (double) bps;
    }

    public boolean isPcm16() {
        return bitsPerSample == 16;
    }

    /**
     * 解析 WAV 字节。
     *
     * @return 解析成功返回头信息；不是 WAV / 结构损坏返回 {@code null}（调用方必须判空，
     * 不能"反正是我们自己生成的就不会坏"——磁盘写一半、网络截断都会到这）
     */
    public static WavHeader parse(byte[] b) {
        if (b == null || b.length < 44) {
            return null;
        }
        if (!tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) {
            return null;
        }

        int fmtFound = 0;
        int sampleRate = 0;
        int channels = 0;
        int bits = 0;
        int dataSize = 0;

        int p = 12;
        while (p + 8 <= b.length) {
            int size = le32(b, p + 4);
            int body = p + 8;
            boolean isData = tag(b, p, "data");

            if (size < 0) {
                return null;
            }
            if (body + (long) size > b.length) {
                if (isData) {
                    // 声明长度超出实际：多半是文件被截断。
                    // data 段按实际可用长度收敛，这样"半截音频"还能放出来一部分，
                    // 而不是整个丢掉 —— 广播场景下能听到半句好过完全没声音。
                    size = b.length - body;
                } else {
                    return null;
                }
            }

            if (tag(b, p, "fmt ")) {
                if (size < 16) {
                    return null;
                }
                channels = le16(b, body + 2);
                sampleRate = le32(b, body + 4);
                bits = le16(b, body + 14);
                fmtFound = 1;
            } else if (isData) {
                dataSize = size;
            }

            // chunk 之间按偶数字节对齐
            p = body + size + (size & 1);
        }

        if (fmtFound == 0 || sampleRate <= 0 || channels <= 0 || bits <= 0) {
            return null;
        }
        return new WavHeader(sampleRate, channels, bits, dataSize);
    }

    /** 解析出来的人类可读摘要，进日志用 */
    public String describe() {
        return sampleRate + "Hz " + channels + "ch " + bitsPerSample + "bit "
                + dataSize + "B " + String.format("%.2f", durationSeconds()) + "s";
    }

    // ------------------------------------------------------------------ 工具

    private static boolean tag(byte[] b, int off, String expect) {
        if (off + 4 > b.length) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if ((b[off + i] & 0xFF) != (expect.charAt(i) & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    // ------------------------------------------------------ 生成（测试与工具用）

    /** ASCII 标记转字节，写测试用例时省事 */
    public static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * 拼一个最小的 44 字节 WAV 头。
     * <p>
     * 测试用它造样本；离线工具也用它给"只有裸 PCM"的场景补头。
     */
    public static byte[] buildHeader(int sampleRate, int channels, int bits, int dataSize) {
        byte[] h = new byte[44];
        byte[] riff = ascii("RIFF");
        System.arraycopy(riff, 0, h, 0, 4);
        putLe32(h, 4, 36 + dataSize);
        byte[] wave = ascii("WAVE");
        System.arraycopy(wave, 0, h, 8, 4);
        byte[] fmt = ascii("fmt ");
        System.arraycopy(fmt, 0, h, 12, 4);
        putLe32(h, 16, 16);
        putLe16(h, 20, 1);                                                   // PCM
        putLe16(h, 22, channels);
        putLe32(h, 24, sampleRate);
        putLe32(h, 28, sampleRate * channels * bits / 8);
        putLe16(h, 32, channels * bits / 8);
        putLe16(h, 34, bits);
        byte[] data = ascii("data");
        System.arraycopy(data, 0, h, 36, 4);
        putLe32(h, 40, dataSize);
        return h;
    }

    private static void putLe16(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }
}

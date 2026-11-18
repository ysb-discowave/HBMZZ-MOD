package com.rsaudio.tts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 音频分片重组（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 为什么必须分片：Minecraft 1.12.2 的网络帧有大小上限，一次性把几十万字节的 WAV
 * 塞进一个包会被直接掐断。一句 20 字的中文播报大约 260KB，得拆成十几个包。
 * <p>
 * 为什么这段逻辑单独抽出来：分包重组是"静默出错"的重灾区 ——
 * 少收一个包、收到重复包、上一个播报的残片混进这一个，症状都是
 * "偶尔有一段噪音"或者"偶发无声"，没人能稳定复现。
 * 所以它必须能被单测直接喂各种畸形输入。
 * <p>
 * 不接收"未知 id 的分片"：必须 {@link #begin} 之后才收，否则一个乱序到达的尾巴
 * 会在客户端里生出半截音频。
 * <p>
 * 非线程安全，只在客户端主线程用。
 */
public final class AudioReassembler {

    public static final int DEFAULT_MAX_CONCURRENT = 4;
    public static final long DEFAULT_MAX_TOTAL_BYTES = 4L * 1024 * 1024;
    public static final long DEFAULT_TTL_MS = 30_000L;

    private static final class Partial {
        final int totalBytes;
        final int chunkCount;
        final byte[][] chunks;
        final boolean[] got;
        int receivedChunks;
        int receivedBytes;
        final long createdMs;

        Partial(int totalBytes, int chunkCount, long createdMs) {
            this.totalBytes = totalBytes;
            this.chunkCount = chunkCount;
            this.chunks = new byte[chunkCount][];
            this.got = new boolean[chunkCount];
            this.createdMs = createdMs;
        }

        boolean complete() {
            return receivedChunks == chunkCount && receivedBytes == totalBytes;
        }
    }

    private final Map<Integer, Partial> partials = new HashMap<Integer, Partial>();
    private final int maxConcurrent;
    private final long maxTotalBytes;
    private final long ttlMs;

    public AudioReassembler() {
        this(DEFAULT_MAX_CONCURRENT, DEFAULT_MAX_TOTAL_BYTES, DEFAULT_TTL_MS);
    }

    public AudioReassembler(int maxConcurrent, long maxTotalBytes, long ttlMs) {
        this.maxConcurrent = Math.max(1, maxConcurrent);
        this.maxTotalBytes = Math.max(1024L, maxTotalBytes);
        this.ttlMs = Math.max(1000L, ttlMs);
    }

    /**
     * 开始接收一个音频。
     *
     * @return false 表示这个请求本身就不合法（尺寸超限 / 分片数不合理 / 并发太多），
     * 调用方应当直接丢弃，而不是"等着看"
     */
    public boolean begin(int id, int totalBytes, int chunkCount, long nowMs) {
        if (totalBytes <= 44 || totalBytes > TtsSpec.MAX_WAV_BYTES) {
            return false;
        }
        if (chunkCount <= 0 || chunkCount > totalBytes) {
            return false;
        }
        if (partials.containsKey(id)) {
            // 重复的 begin：当作"重来一遍"，丢掉旧的残片
            partials.remove(id);
        }
        expire(nowMs, ttlMs);
        if (partials.size() >= maxConcurrent) {
            return false;
        }
        partials.put(id, new Partial(totalBytes, chunkCount, nowMs));
        return true;
    }

    /**
     * 收一个分片。
     *
     * @return 是否被接受
     */
    public boolean offer(int id, int index, byte[] data) {
        Partial p = partials.get(id);
        if (p == null || data == null) {
            return false;
        }
        if (index < 0 || index >= p.chunkCount) {
            return false;
        }
        if (p.got[index]) {
            // 重复分片：忽略而不是累加，否则 receivedBytes 会虚高、永远凑不齐
            return true;
        }
        if (p.receivedBytes + data.length > p.totalBytes) {
            return false;
        }
        p.chunks[index] = data;
        p.got[index] = true;
        p.receivedChunks++;
        p.receivedBytes += data.length;
        return true;
    }

    public boolean isComplete(int id) {
        Partial p = partials.get(id);
        return p != null && p.complete();
    }

    public boolean isPending(int id) {
        return partials.containsKey(id);
    }

    /**
     * 取出拼好的音频并移除该条目。没拼好返回 {@code null}（条目<b>保留</b>，
     * 后续分片还能继续填）。
     */
    public byte[] take(int id) {
        Partial p = partials.get(id);
        if (p == null || !p.complete()) {
            return null;
        }
        partials.remove(id);
        byte[] out = new byte[p.totalBytes];
        int off = 0;
        for (int i = 0; i < p.chunkCount; i++) {
            byte[] c = p.chunks[i];
            System.arraycopy(c, 0, out, off, c.length);
            off += c.length;
        }
        return off == p.totalBytes ? out : null;
    }

    /** 放弃一个未完成的条目（切换播报、被新的打断时用） */
    public boolean abort(int id) {
        return partials.remove(id) != null;
    }

    /** 丢弃超时的残片。返回丢弃的条目数 */
    public int expire(long nowMs, long ttlMsOverride) {
        long ttl = ttlMsOverride > 0L ? ttlMsOverride : ttlMs;
        List<Integer> dead = new ArrayList<Integer>(2);
        Iterator<Map.Entry<Integer, Partial>> it = partials.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Partial> e = it.next();
            if (nowMs - e.getValue().createdMs > ttl) {
                dead.add(e.getKey());
            }
        }
        for (Integer id : dead) {
            partials.remove(id);
        }
        // 总字节超限：优先丢最旧的
        while (totalPendingBytes() > maxTotalBytes && !partials.isEmpty()) {
            int oldestId = -1;
            long oldest = Long.MAX_VALUE;
            for (Map.Entry<Integer, Partial> e : partials.entrySet()) {
                if (e.getValue().createdMs < oldest) {
                    oldest = e.getValue().createdMs;
                    oldestId = e.getKey();
                }
            }
            if (oldestId < 0) {
                break;
            }
            partials.remove(oldestId);
            dead.add(oldestId);
        }
        return dead.size();
    }

    public int pendingCount() {
        return partials.size();
    }

    public long totalPendingBytes() {
        long total = 0L;
        for (Partial p : partials.values()) {
            total += p.receivedBytes;
        }
        return total;
    }

    public void clear() {
        partials.clear();
    }
}

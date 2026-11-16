package com.rsaudio.formats.convert;

import com.rsaudio.formats.FmLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 把打包在 jar 内的 ffmpeg.exe 释放到磁盘，首次播放时执行一次。
 *
 * <p>资源路径 {@code /assets/rsaudiofm/ffmpeg.exe}。释放到 {@code <游戏目录>/config/rsaudiofm/bin/}，
 * 用同目录的 {@code .stamp} 记录大小与修改时间，避免每次启动都重写 100 MB。
 *
 * <p>释放失败（只读目录、磁盘满、资源缺失）只是返回 null，调用方会继续走外置候选路径，
 * 不会因此让播放器整体不可用。
 */
public final class BundledFfmpeg {

    private static final String RESOURCE = "/assets/rsaudiofm/ffmpeg.exe";
    private static final String EXE = "ffmpeg.exe";
    private static final String STAMP = ".stamp";

    private BundledFfmpeg() {
    }

    /** 返回可执行的 ffmpeg 路径；jar 内没有资源或释放失败时返回 null。 */
    public static File ensure(File gameDir, List<String> notes) {
        if (gameDir == null) {
            return null;
        }
        File dir = new File(gameDir, "config" + File.separator + "rsaudiofm" + File.separator + "bin");
        File target = new File(dir, EXE);
        try {
            long size = resourceSize();
            if (size <= 0) {
                notes.add("候选 0 内置 ffmpeg: jar 内无资源");
                return null;
            }
            if (target.isFile() && target.length() == size && new File(dir, STAMP).isFile()) {
                notes.add("候选 0 内置 ffmpeg: 已释放 (" + target.getPath() + ")");
                return target;
            }
            if (!dir.isDirectory() && !dir.mkdirs()) {
                notes.add("候选 0 内置 ffmpeg: 无法创建目录 " + dir.getPath());
                return null;
            }
            File part = new File(dir, EXE + ".part");
            InputStream in = BundledFfmpeg.class.getResourceAsStream(RESOURCE);
            if (in == null) {
                notes.add("候选 0 内置 ffmpeg: 资源读取失败");
                return null;
            }
            long copied = 0L;
            try {
                OutputStream out = new FileOutputStream(part);
                try {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                        copied += n;
                    }
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (part.length() != size) {
                part.delete();
                notes.add("候选 0 内置 ffmpeg: 释放后大小不符 (" + part.length() + " / " + size + ")");
                return null;
            }
            if (target.exists() && !target.delete()) {
                notes.add("候选 0 内置 ffmpeg: 无法覆盖旧文件");
                return null;
            }
            if (!part.renameTo(target)) {
                part.delete();
                notes.add("候选 0 内置 ffmpeg: 重命名失败");
                return null;
            }
            target.setExecutable(true);
            writeStamp(new File(dir, STAMP), size);
            FmLog.log("[ffmpeg] 已释放内置 ffmpeg 到 " + target.getPath() + "（" + copied + " 字节，仅首次）");
            notes.add("候选 0 内置 ffmpeg: 已释放到 " + target.getPath());
            return target;
        } catch (Throwable t) {
            notes.add("候选 0 内置 ffmpeg: 释放异常 " + t);
            FmLog.warn("[ffmpeg] 释放内置 ffmpeg 失败: " + t);
            return null;
        }
    }

    private static long resourceSize() {
        InputStream in = BundledFfmpeg.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            return -1L;
        }
        try {
            long total = 0L;
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
            }
            return total;
        } catch (Throwable t) {
            return -1L;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 关闭失败无影响
            }
        }
    }

    private static void writeStamp(File stamp, long size) {
        try {
            OutputStream out = new FileOutputStream(stamp);
            try {
                out.write(("size=" + size + "\n").getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
            // 标记写不进去不影响使用，下次启动会再校验一次大小
        }
    }
}

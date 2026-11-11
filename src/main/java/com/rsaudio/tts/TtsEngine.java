package com.rsaudio.tts;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Piper TTS 引擎的发现与调用（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 为什么是 Piper：MIT 协议的开源 TTS，有中文 voice 模型，而且<b>直接输出 WAV</b> ——
 * 这点很关键，省掉了"没有系统 ffmpeg 就转不了 OGG"那一整摊麻烦。
 * <p>
 * 为什么用子进程而不是 JNI：进程崩了不会带走服务端；换模型就是换个文件；
 * 没装引擎时降级干净（只剩弹窗和文字，不报错）。
 * <p>
 * <b>为什么要有 engine.txt</b>：Piper 有两种常见装法 ——
 * 官方独立发布包（一个真正的 {@code piper.exe}）和 {@code pip install piper-tts}
 * （装出来的是个 Python 启动器，<b>拷到别处就失效</b>，因为它要靠自己所在目录找 Python）。
 * 后者没法靠"在 TTS 目录里放一个 piper.exe"来适配。所以引擎的调用方式做成可配置：
 * {@code engine.txt} 里<b>每行一个参数</b>，拼在 {@code --model ...} 之前。
 * 写成"每行一个参数"而不是一整行命令，是为了彻底绕开引号与空格转义 ——
 * 路径里带空格的用户不会踩坑。
 * <p>
 * <b>线程</b>：{@link #synthesize} 会阻塞若干秒，只能在后台线程调用。
 */
public final class TtsEngine {

    /** 引擎状态快照 */
    public static final class Status {
        public final boolean available;
        /** 启动引擎的命令前缀（每项一个参数），例如 [piper.exe] 或 [python.exe, -m, piper] */
        public final List<String> prefix;
        public final File model;
        /** 不可用时给人看的原因；可用时是一句描述 */
        public final String message;

        Status(boolean available, List<String> prefix, File model, String message) {
            this.available = available;
            this.prefix = prefix;
            this.model = model;
            this.message = message;
        }

        /** 给日志/指令看的命令行摘要 */
        public String describeCommand() {
            if (prefix == null || prefix.isEmpty()) {
                return "(未配置)";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < prefix.size(); i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(prefix.get(i));
            }
            return sb.toString();
        }
    }

    private TtsEngine() {
    }

    // ------------------------------------------------------------------ 发现

    public static Status discover(File ttsDir) {
        if (ttsDir == null || !ttsDir.isDirectory()) {
            return new Status(false, null, null,
                    "TTS 目录不存在: " + (ttsDir == null ? "(null)" : ttsDir.getAbsolutePath()));
        }

        List<String> prefix = null;
        File engineFile = new File(ttsDir, TtsSpec.ENGINE_CONFIG);
        if (engineFile.isFile()) {
            List<String> parsed = parseEngineConfig(engineFile);
            if (parsed.isEmpty()) {
                return new Status(false, null, null,
                        TtsSpec.ENGINE_CONFIG + " 是空的或者每行都被注释掉了");
            }
            prefix = parsed;
        } else {
            File exe = findPiperExe(ttsDir);
            if (exe != null) {
                prefix = new ArrayList<String>(1);
                prefix.add(exe.getAbsolutePath());
            }
        }

        if (prefix == null) {
            return new Status(false, null, null,
                    "找不到 " + TtsSpec.PIPER_EXE_WIN + "，也没有 " + TtsSpec.ENGINE_CONFIG
                            + "。把独立版 piper 放进 " + ttsDir.getAbsolutePath()
                            + "（或其 piper/ 子目录），或者写一个 engine.txt 指明启动命令");
        }

        File model = findModel(ttsDir);
        if (model == null) {
            return new Status(false, prefix, null,
                    "找不到语音模型 *.onnx（放在 " + ttsDir.getAbsolutePath()
                            + " 或其 models/ 子目录下，且需要同名的 .onnx.json 配置文件）");
        }

        return new Status(true, prefix, model,
                "就绪: " + join(prefix) + " + " + model.getName());
    }

    /**
     * 解析 engine.txt：<b>每行一个参数</b>。
     * <ul>
     *   <li>{@code #} 开头的行是注释</li>
     *   <li>空行忽略</li>
     *   <li>每行整体作为一个参数，<b>不做分割</b> —— 所以带空格的路径直接写就行，
     *       不需要也不用加引号（加了引号会变成参数的一部分，这是最容易踩的一个坑）</li>
     * </ul>
     */
    public static List<String> parseEngineConfig(File file) {
        List<String> out = new ArrayList<String>(4);
        if (file == null || !file.isFile()) {
            return out;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                out.add(t);
            }
        } catch (IOException ioe) {
            return new ArrayList<String>(0);
        } finally {
            close(reader);
        }
        return out;
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static File findPiperExe(File ttsDir) {
        File[] candidates = {
                new File(ttsDir, TtsSpec.PIPER_EXE_WIN),
                new File(ttsDir, TtsSpec.PIPER_EXE_NIX),
                new File(new File(ttsDir, "piper"), TtsSpec.PIPER_EXE_WIN),
                new File(new File(ttsDir, "piper"), TtsSpec.PIPER_EXE_NIX),
        };
        for (File f : candidates) {
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    private static File findModel(File ttsDir) {
        // 先按默认名找，再退而求其次找任意一个配对完整的 onnx
        File[] dirs = {ttsDir, new File(ttsDir, "models")};
        for (File dir : dirs) {
            File preferred = new File(dir, TtsSpec.DEFAULT_MODEL);
            if (isModelUsable(preferred)) {
                return preferred;
            }
        }
        for (File dir : dirs) {
            File[] list = dir.listFiles();
            if (list == null) {
                continue;
            }
            File[] sorted = list.clone();
            Arrays.sort(sorted);
            for (File f : sorted) {
                if (f.isFile() && f.getName().endsWith(".onnx") && isModelUsable(f)) {
                    return f;
                }
            }
        }
        return null;
    }

    /** onnx 必须配一个同名的 .onnx.json，否则 piper 起不来 */
    private static boolean isModelUsable(File onnx) {
        return onnx.isFile() && new File(onnx.getPath() + TtsSpec.MODEL_CONFIG_EXT).isFile();
    }

    // ------------------------------------------------------------------ 命令行

    /**
     * 拼完整命令行（纯函数，方便单测比对参数，不用真起进程）。
     * <p>
     * 统一用长选项：piper 各版本的短选项改过（{@code -m} 在某个版本被别的含义占走了），
     * 长选项是稳的。
     */
    public static List<String> buildCommand(List<String> prefix, File model, File outWav,
                                            double lengthScale, int speakerId) {
        List<String> cmd = new ArrayList<String>(10);
        if (prefix != null) {
            cmd.addAll(prefix);
        }
        cmd.add("--model");
        cmd.add(model.getAbsolutePath());
        cmd.add("--output_file");
        cmd.add(outWav.getAbsolutePath());
        if (lengthScale > 0.0D && Math.abs(lengthScale - 1.0D) > 0.001D) {
            cmd.add("--length_scale");
            cmd.add(String.format(Locale.ROOT, "%.2f", lengthScale));
        }
        if (speakerId >= 0) {
            cmd.add("--speaker");
            cmd.add(Integer.toString(speakerId));
        }
        return cmd;
    }

    // ------------------------------------------------------------------ 合成

    public static boolean synthesize(Status status, String text, File outWav) {
        return synthesize(status, text, outWav, 1.0D, -1);
    }

    /**
     * 把文本合成到 {@code outWav}。<b>会阻塞</b>，只在后台线程调。
     *
     * @return 成功（进程退出码 0 且文件非空）返回 true
     */
    public static boolean synthesize(Status status, String text, File outWav,
                                     double lengthScale, int speakerId) {
        if (status == null || !status.available) {
            return false;
        }
        String clean = TtsSpec.sanitizeTts(text);
        if (clean.isEmpty()) {
            return false;
        }

        // 上一次没写完的残留会让 piper 覆盖失败，先清掉
        if (outWav.exists() && !outWav.delete()) {
            return false;
        }
        File parent = outWav.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return false;
        }

        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    buildCommand(status.prefix, status.model, outWav, lengthScale, speakerId));
            // 工作目录设成引擎可执行文件所在目录：独立版 piper.exe 要靠这个相对路径
            // 去找同目录的 onnxruntime 动态库和 espeak-ng-data 音素数据。
            // 少这一行，症状是进程秒退、只留一句关于 espeak 的报错。
            // 对 "python -m piper" 这种前缀，设了这个目录也无害。
            if (status.prefix != null && !status.prefix.isEmpty()) {
                File exe = new File(status.prefix.get(0));
                File workDir = exe.getAbsoluteFile().getParentFile();
                if (workDir != null && workDir.isDirectory()) {
                    pb.directory(workDir);
                }
            }
            pb.redirectErrorStream(true);
            process = pb.start();

            // 文本走 stdin：不经命令行，省掉一整类引号/编码逃逸问题
            OutputStream stdin = process.getOutputStream();
            stdin.write(clean.getBytes(StandardCharsets.UTF_8));
            stdin.write('\n');
            stdin.flush();
            stdin.close();

            // 必须把 stdout 读干净，否则管道缓冲区满了子进程会写阻塞、永远不退出
            drain(process.getInputStream());

            if (!process.waitFor(TtsSpec.synthTimeoutSeconds(clean.length()), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            if (process.exitValue() != 0) {
                return false;
            }
            return outWav.isFile() && outWav.length() > 44L;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException ioe) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buf = new byte[4096];
        try {
            while (in.read(buf) != -1) {
                // 故意丢弃：piper 的日志对玩家没用，只有排障时才需要
            }
        } finally {
            in.close();
        }
    }

    /**
     * 读整个文件，超出上限直接返回 {@code null}。
     * <p>
     * 上限检查在这里而不是调用方做，是因为发现"超限"的时机越早越好，
     * 免得先把 200MB 读进堆再判断。
     */
    public static byte[] readCapped(File file, int cap) throws IOException {
        long len = file.length();
        if (len <= 0L || len > cap) {
            return null;
        }
        byte[] data = new byte[(int) len];
        InputStream in = new FileInputStream(file);
        try {
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
            if (off != data.length) {
                return null;
            }
        } finally {
            in.close();
        }
        return data;
    }

    /** 写文件（先写 .tmp 再改名，避免别的线程读到写了一半的文件） */
    public static void writeAtomic(File target, byte[] data) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录: " + parent);
        }
        File tmp = new File(target.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("无法覆盖: " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("无法重命名: " + tmp + " -> " + target);
        }
    }

    private static void close(BufferedReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (IOException ignored) {
            // 读配置失败不值得让调用方崩
        }
    }
}

package com.rsaudio.tts;

/**
 * TTS 子系统的规格常量与文本清洗（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 清洗函数放在这里而不是散在 GUI 里，是因为"服务端收到什么就该说什么"——
 * 长度上限、换行折叠这些必须在<b>两端用同一份代码</b>算，否则客户端截一次、
 * 服务端再截一次，会出现"我明明打了 300 个字，播出来只有 240 个"这种玄学问题。
 */
public final class TtsSpec {

    private TtsSpec() {
    }

    // ------------------------------------------------------------ 引擎发现

    public static final String PIPER_EXE_WIN = "piper.exe";
    public static final String PIPER_EXE_NIX = "piper";
    public static final String DEFAULT_MODEL = "zh_CN-huayan-medium.onnx";
    /**
     * 模型配置文件的扩展名，拼在 onnx 全名<b>之后</b>。
     * <p>
     * Piper 的 voice 是两个文件成对发布的：
     * {@code zh_CN-huayan-medium.onnx} 和 {@code zh_CN-huayan-medium.onnx.json}。
     * 所以这里是 ".json" 而不是 ".onnx.json" —— 写成后者会拼出
     * {@code xxx.onnx.onnx.json}，于是<b>模型装得再对也永远找不到</b>。
     * 这个 bug 有个讨厌的性质：你自己写测试时很容易也拼错同一串，
     * 于是测试和代码一起错、互相验证通过。
     */
    public static final String MODEL_CONFIG_EXT = ".json";
    /**
     * 可选的引擎启动配置。存在时优先读它，<b>每行一个参数</b>。
     * <p>
     * 存在的意义：{@code pip install piper-tts} 装出来的 {@code piper.exe} 只是个
     * Python 启动器，拷到别处就失效，没法用"往目录里放一个 exe"来适配。
     * 有了这个文件，用户可以写成：
     * <pre>
     * # 每行一个参数，带空格的路径直接写，不要加引号
     * C:\Python\python.exe
     * -m
     * piper
     * </pre>
     */
    public static final String ENGINE_CONFIG = "engine.txt";

    // -------------------------------------------------------------- 长度上限

    /**
     * 朗读文本长度上限（字符）。
     * <p>
     * 比聊天栏播报的 160 窄，而且<b>必须卡得比"看起来合理"更严</b>：合成是有真实成本的。
     * 在开发机上实测（zh_CN-huayan-medium，纯 CPU 的 onnxruntime）：
     * <pre>
     *   5 字  → 约 17 秒（其中约 15 秒是固定开销：起 Python + 载入 60MB 模型）
     *   44 字 → 约 34 秒（合成本身约 0.4 秒/字）
     * </pre>
     * 也就是说 300 字要念两分多钟、产出 2.7MB 的 wav（还会超过 {@link #MAX_WAV_BYTES}）。
     * 100 字 ≈ 55 秒、约 0.9MB，已经是一段相当长的口播了，够用。
     */
    public static final int MAX_TTS_CHARS = 100;
    /** 弹窗标题长度上限 */
    public static final int MAX_TITLE_CHARS = 48;
    /** 弹窗正文长度上限。正文会自动折行，这里限的是原始字符数 */
    public static final int MAX_CONTENT_CHARS = 240;

    // ------------------------------------------------------------ 音频尺寸上限

    /** 单次合成的音频字节上限（约 34 秒 @ 22050Hz 单声道 16bit） */
    public static final int MAX_WAV_BYTES = 1536 * 1024;

    // ---------------------------------------------------------------- 超时
    //   超时不能是一个常数：合成的固定开销（起进程 + 载模型）就有十几秒，
    //   再线性叠上"每字若干秒"。写死 30 秒的后果是"44 个字就必然超时"，
    //   而那恰恰是最常见的一句话长度 —— 这个 bug 只有在真跑过引擎之后才看得见。

    /** 超时基线（秒），覆盖固定开销 */
    public static final int SYNTH_TIMEOUT_SECONDS = 45;
    /** 每多一个字多给几秒（实测约 0.4 秒/字，这里留 2 倍以上余量给慢机器） */
    public static final int SYNTH_TIMEOUT_SECONDS_PER_CHAR = 1;
    /** 超时上限（秒），防止一个卡住的进程把唯一的合成线程占太久 */
    public static final int SYNTH_TIMEOUT_MAX_SECONDS = 180;

    /** 按文本长度算超时 */
    public static int synthTimeoutSeconds(int charCount) {
        int seconds = SYNTH_TIMEOUT_SECONDS
                + SYNTH_TIMEOUT_SECONDS_PER_CHAR * Math.max(0, charCount);
        return Math.min(SYNTH_TIMEOUT_MAX_SECONDS, seconds);
    }

    // ------------------------------------------------------------ 网络分片

    /**
     * 每个网络包携带的音频字节数。
     * <p>
     * 一段 20 字的中文播报大约 260KB，拆成 16KB 一片就是十几个包 ——
     * 既远低于 1.12.2 的网络帧上限（一次性塞进去会被掐断），
     * 又不至于碎成上百个包把带宽全花在包头开销上。
     */
    public static final int CHUNK_BYTES = 16 * 1024;

    /**
     * 一段音频要拆成几个包。
     * <p>
     * ★ 这个算法在服务端（切）和客户端（{@code AudioReassembler.begin} 的入参）各用一次，
     * 所以只留这一个实现 —— 两边各写一遍很容易出现"服务端切了 17 片、客户端以为有 16 片"，
     * 症状是偶发无声，且只在特定长度下出现。
     */
    public static int chunkCount(int totalBytes) {
        if (totalBytes <= 0) {
            return 0;
        }
        return (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    /** 第 {@code index} 个分片的字节数（最后一片通常短一截） */
    public static int chunkLength(int totalBytes, int index) {
        if (totalBytes <= 0 || index < 0) {
            return 0;
        }
        int start = index * CHUNK_BYTES;
        if (start >= totalBytes) {
            return 0;
        }
        return Math.min(CHUNK_BYTES, totalBytes - start);
    }

    /** 分片的起始下标 */
    public static int chunkOffset(int index) {
        return index <= 0 ? 0 : index * CHUNK_BYTES;
    }

    // -------------------------------------------------------------- 语速

    /** 默认语速（1.0 = 模型自带语速）。数值越大越慢（piper 的 length_scale 是"时长倍率"） */
    public static final double DEFAULT_LENGTH_SCALE = 1.0D;
    public static final double MIN_LENGTH_SCALE = 0.6D;
    public static final double MAX_LENGTH_SCALE = 1.8D;

    /** 语速裁剪。界面按 ±0.1 步进，浮点累积误差用四舍五入吸掉 */
    public static double clampLengthScale(double value) {
        if (Double.isNaN(value)) {
            return DEFAULT_LENGTH_SCALE;
        }
        double step = Math.round(value * 10.0D) / 10.0D;
        if (step < MIN_LENGTH_SCALE) {
            return MIN_LENGTH_SCALE;
        }
        if (step > MAX_LENGTH_SCALE) {
            return MAX_LENGTH_SCALE;
        }
        return step;
    }

    // ------------------------------------------------------------------ 播报

    /** 广播器默认作用半径（格） */
    public static final int DEFAULT_RANGE = 96;
    public static final int MIN_RANGE = 8;
    public static final int MAX_RANGE = 256;

    /** 弹窗默认停留时长（tick）。20 tick = 1 秒 */
    public static final int DEFAULT_NOTICE_TICKS = 200;
    public static final int MIN_NOTICE_TICKS = 40;
    public static final int MAX_NOTICE_TICKS = 2400;

    // ------------------------------------------------------------------ 缓存

    public static final int CACHE_MAX_ENTRIES = 256;
    public static final long CACHE_MAX_BYTES = 128L * 1024 * 1024;

    // -------------------------------------------------------------- 文本清洗

    /** 把任意输入折成"单行、去首尾空白、不超限" */
    public static String singleLine(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String value = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        // 连续的空白折叠成一个空格（长文本里贴进来的换行会留下一堆空格）
        StringBuilder sb = new StringBuilder(value.length());
        boolean lastSpace = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ' ') {
                if (!lastSpace) {
                    sb.append(' ');
                }
                lastSpace = true;
            } else {
                sb.append(c);
                lastSpace = false;
            }
        }
        String out = sb.toString();
        return out.length() > maxChars ? out.substring(0, maxChars) : out;
    }

    /** 弹窗标题 */
    public static String sanitizeTitle(String text) {
        return singleLine(text, MAX_TITLE_CHARS);
    }

    /** 弹窗正文。允许换行（正文是分行的），所以只做折叠与限长 */
    public static String sanitizeContent(String text) {
        if (text == null) {
            return "";
        }
        String value = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        return value.length() > MAX_CONTENT_CHARS ? value.substring(0, MAX_CONTENT_CHARS) : value;
    }

    /**
     * 朗读文本。TTS 对换行很敏感（会被当成停顿或直接读错），所以强制单行。
     */
    public static String sanitizeTts(String text) {
        return singleLine(text, MAX_TTS_CHARS);
    }

    public static int clampRange(int value) {
        if (value < MIN_RANGE) {
            return MIN_RANGE;
        }
        if (value > MAX_RANGE) {
            return MAX_RANGE;
        }
        return value;
    }

    public static int clampNoticeTicks(int value) {
        if (value < MIN_NOTICE_TICKS) {
            return MIN_NOTICE_TICKS;
        }
        if (value > MAX_NOTICE_TICKS) {
            return MAX_NOTICE_TICKS;
        }
        return value;
    }
}

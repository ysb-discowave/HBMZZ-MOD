package com.rsaudio.util;

import java.util.Locale;

/**
 * 延迟时间的文本 <-> 刻数换算。
 * <p>
 * 玩家在界面里可以直接写：{@code 2}（秒）、{@code 1.5s}、{@code 500ms}、{@code 10t}（刻）、{@code 1m}（分），
 * 中文单位 {@code 秒 / 刻 / 分 / 毫秒} 一样认。
 * <p>
 * 换算关系：1 秒 = 20 刻，1 刻 = 50 毫秒。
 */
public final class DelayText {

    public static final int TICKS_PER_SECOND = 20;

    private DelayText() {
    }

    /**
     * 解析玩家输入，返回刻数。
     *
     * @return 解析失败（空/非数字/非正数/超出 int）时返回 -1
     */
    public static int parseTicks(String raw) {
        if (raw == null) {
            return -1;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) {
            return -1;
        }

        double ticksPerUnit;
        String number;
        // 单位匹配：注意 "ms" 要在 "s" 之前、"min" 要在 "m" 之前
        if (s.endsWith("毫秒")) {
            ticksPerUnit = 0.05D;
            number = cut(s, 2);
        } else if (s.endsWith("ms")) {
            ticksPerUnit = 0.05D;
            number = cut(s, 2);
        } else if (s.endsWith("ticks")) {
            ticksPerUnit = 1.0D;
            number = cut(s, 5);
        } else if (s.endsWith("tick")) {
            ticksPerUnit = 1.0D;
            number = cut(s, 4);
        } else if (s.endsWith("刻")) {
            ticksPerUnit = 1.0D;
            number = cut(s, 1);
        } else if (s.endsWith("t")) {
            ticksPerUnit = 1.0D;
            number = cut(s, 1);
        } else if (s.endsWith("min")) {
            ticksPerUnit = TICKS_PER_SECOND * 60.0D;
            number = cut(s, 3);
        } else if (s.endsWith("分")) {
            ticksPerUnit = TICKS_PER_SECOND * 60.0D;
            number = cut(s, 1);
        } else if (s.endsWith("m")) {
            ticksPerUnit = TICKS_PER_SECOND * 60.0D;
            number = cut(s, 1);
        } else if (s.endsWith("秒")) {
            ticksPerUnit = TICKS_PER_SECOND;
            number = cut(s, 1);
        } else if (s.endsWith("s")) {
            ticksPerUnit = TICKS_PER_SECOND;
            number = cut(s, 1);
        } else {
            // 纯数字默认按秒
            ticksPerUnit = TICKS_PER_SECOND;
            number = s;
        }

        number = number.trim();
        if (number.isEmpty()) {
            return -1;
        }

        double value;
        try {
            value = Double.parseDouble(number);
        } catch (NumberFormatException e) {
            return -1;
        }
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0D) {
            return -1;
        }

        long ticks = Math.round(value * ticksPerUnit);
        if (ticks <= 0L || ticks > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) ticks;
    }

    /** 刻数 -> 秒（保留 2 位小数），例如 30 -> "1.50" */
    public static String formatSeconds(int ticks) {
        return String.format(Locale.ROOT, "%.2f", ticks / (double) TICKS_PER_SECOND);
    }

    /** 刻数 -> 人类可读时长，例如 "2.00 秒" / "1 分 30.00 秒" */
    public static String formatDuration(int ticks) {
        if (ticks < TICKS_PER_SECOND * 60) {
            return formatSeconds(ticks) + " 秒";
        }
        double seconds = ticks / (double) TICKS_PER_SECOND;
        long minutes = (long) Math.floor(seconds / 60.0D);
        double rest = seconds - minutes * 60.0D;
        return minutes + " 分 " + String.format(Locale.ROOT, "%.2f", rest) + " 秒";
    }

    /**
     * 把刻数写回输入框时用的文本 —— **必须和 {@link #parseTicks(String)} 的默认单位一致**。
     * <p>
     * ★ 这里踩过坑（用户报"输入十秒自动变成 200 tick，但被识别成 200 秒"）：
     * 以前直接写 {@code String.valueOf(ticks)}，即把**刻数**写回框里；而 {@code parseTicks}
     * 把**纯数字当秒**。于是 10 秒 → 200 刻 → 框里回填 "200" → 再解析成 200 秒 = 4000 刻，
     * 整整翻 20 倍。凡是"解析"和"回填"不是同一个单位，就一定会出这种自乘错误。
     * <p>
     * 所以这里统一写**秒**（和纯数字默认按秒对齐）：1 刻 = 0.05 秒，刻数 / 20 最多两位小数，
     * 用 {@code %.2f} 回填可以**精确回环**（{@code parseTicks(toInputText(t)) == t}）。
     * <p>
     * 这个回环有自动化校验：{@code rsaudio/tools/check_delay_roundtrip.py}
     * （把本类脱离 MC 单独编译后，对 1..12000 刻全量断言）。改了这里的单位记得跑一遍。
     */
    public static String toInputText(int ticks) {
        double seconds = ticks / (double) TICKS_PER_SECOND;
        long whole = Math.round(seconds);
        if (Math.abs(seconds - whole) < 1.0E-9D) {
            // 整秒就写整数，玩家看到的还是他敲进去的样子（10 秒 -> "10"）
            return String.valueOf(whole);
        }
        // 非整秒保留两位（1 刻 = 0.05 秒，最多就是两位），并去掉没意义的尾零（1.50 -> 1.5）
        String text = String.format(Locale.ROOT, "%.2f", seconds);
        while (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.endsWith(".")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    private static String cut(String s, int suffixLength) {
        return s.substring(0, s.length() - suffixLength);
    }
}

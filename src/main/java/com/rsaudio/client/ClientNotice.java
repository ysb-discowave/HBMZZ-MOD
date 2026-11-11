package com.rsaudio.client;

import com.rsaudio.tts.TtsSpec;

/**
 * 右下角弹窗的<b>状态</b>（只有数据，没有绘制代码 —— 绘制在 {@link ClientNoticeRenderer}）。
 * <p>
 * 一次只显示最新的一条：同时弹好几个框会互相压住，而且广播本来就是
 * "现在最重要的事"，旧的那条没什么可惜的。
 * <p>
 * 计时用<b>游戏刻</b>而不是墙上时钟：单人游戏里按 ESC 打开菜单会把世界暂停，
 * 用墙钟计时的话弹窗会在你暂停的时候自己消失掉。
 */
public final class ClientNotice {

    /** 淡出用多少刻（1 秒） */
    public static final int FADE_TICKS = 20;

    private static String title = "";
    private static String content = "";
    private static int duration;
    private static int elapsed;
    private static boolean active;
    /** 每次刷新内容就 +1，渲染器靠它判断要不要重新折行 */
    private static int serial;

    private ClientNotice() {
    }

    /** 显示一条通知。标题和正文都为空时忽略（广播器那边已经挡过一次，这里再挡一次） */
    public static void show(String newTitle, String newContent, int noticeTicks) {
        // ★ 客户端也要自己清洗一遍：服务端发来的东西同样按"不可信"处理，
        //   否则一个改过的服务端就能让别人的 HUD 上出现几十屏长的文字。
        String t = TtsSpec.sanitizeTitle(newTitle);
        String c = TtsSpec.sanitizeContent(newContent);
        if (t.isEmpty() && c.isEmpty()) {
            return;
        }
        title = t;
        content = c;
        duration = Math.max(1, noticeTicks);
        elapsed = 0;
        active = true;
        serial++;
    }

    /** 由客户端每 tick 调用 */
    public static void tick() {
        if (!active) {
            return;
        }
        elapsed++;
        if (elapsed >= duration) {
            active = false;
            title = "";
            content = "";
        }
    }

    public static void clear() {
        active = false;
        title = "";
        content = "";
        elapsed = 0;
        duration = 0;
        serial++;
    }

    public static boolean isActive() {
        return active;
    }

    public static int getSerial() {
        return serial;
    }

    public static String getTitle() {
        return title;
    }

    public static String getContent() {
        return content;
    }

    /** 剩余多少刻 */
    public static int remainingTicks() {
        return active ? Math.max(0, duration - elapsed) : 0;
    }

    /** 不透明度：1.0 = 完全不透明，最后 {@link #FADE_TICKS} 刻线性淡出到 0 */
    public static float alpha() {
        if (!active) {
            return 0.0F;
        }
        int remain = duration - elapsed;
        if (remain >= FADE_TICKS) {
            return 1.0F;
        }
        return Math.max(0.0F, remain / (float) FADE_TICKS);
    }
}

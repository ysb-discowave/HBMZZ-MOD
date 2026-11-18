/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client.video;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;

/**
 * 播放窗口的全屏开关，替掉直接调 {@code Minecraft.toggleFullscreen()}。
 *
 * <h3>为什么不直接用它</h3>
 * <p>原版那个方法有两处会让玩家以为键按反了：</p>
 *
 * <ul>
 *   <li><b>它按自己的 {@code fullscreen} 字段翻面，而不是按桌面真实状态。</b>
 *       那个字段在启动参数、F11、驱动切换之后都可能与 {@code Display.isFullscreen()}
 *       不一致。一旦不一致，一次"进全屏"实际执行的是"退全屏"，玩家看到的就是
 *       "按 F 之后游戏框反而变小了"。</li>
 *   <li><b>退全屏时它回到启动尺寸</b>（{@code tempDisplayWidth/Height}，通常是
 *       854x480），而不是玩家刚才用的窗口大小。窗口被拉大过的人，一按 F 就掉回
 *       一个小框，还得自己再拉一次。</li>
 * </ul>
 *
 * <p>所以这里做两件事：以 {@code Display.isFullscreen()} 为准决定方向；进全屏
 * 之前记下当前窗口尺寸，退出来时按那个尺寸还原。播放窗口打开时是普通游戏窗口
 * （小框），F 或右键进全屏，再按一次回到原尺寸——不会更小。</p>
 */
public final class VideoFullscreen {

    /** 第一个播放窗口打开时的桌面状态，用来在窗口全部关掉后还回去。 */
    private static boolean captured;
    private static boolean wasFullscreen;
    private static int windowWidth = -1;
    private static int windowHeight = -1;
    /**
     * 第几次"打开播放窗口"。
     *
     * <p>关窗的还原是排队到下一个 tick 执行的（不能在一次界面切换中间改显示
     * 模式），而玩家完全可能在这一 tick 里又开了另一个播放窗口。带着代次回来，
     * 迟到的还原就不会把新窗口记下的状态当成自己的。</p>
     */
    private static int generation;
    /**
     * 这个窗口自己把桌面切成全屏的，才由它切回去。
     *
     * <p>很多人本来就是全屏玩游戏（{@code Display.isFullscreen()} 为真）。那时
     * 按 F 只是"让画面铺满这个窗口"，桌面模式一点没变；再按 F 也不该把全屏的
     * 游戏切成窗口——那是"退出全屏"，不是"退出画面全屏"。</p>
     */
    private static boolean weEntered;

    private VideoFullscreen() {
    }

    /**
     * 画面要铺满：桌面本来不是全屏才切，本来是全屏就什么都不做。
     *
     * <p>区别只在"谁动过桌面"。记下来，退的时候才好决定要不要还回去。</p>
     */
    public static void enterForPicture(Minecraft mc) {
        if (mc == null || active()) {
            return;
        }
        capture();
        try {
            enter(mc);
            weEntered = true;
        } catch (Throwable refused) {
            weEntered = false;
        }
    }

    /** 画面回到电视窗：只有自己切过全屏时，才把桌面还回原来的窗口。 */
    public static void leaveForPicture(Minecraft mc) {
        if (mc == null || !weEntered) {
            return;
        }
        weEntered = false;
        try {
            leave(mc);
        } catch (Throwable refused) {
            // 驱动拒绝切换时画面留在原处，比把人扔回一个 854x480 的小框好。
        }
    }

    /** 桌面此刻是不是真的全屏（问 LWJGL，不问 Minecraft 自己的字段）。 */
    public static boolean active() {
        try {
            return Display.isFullscreen();
        } catch (Throwable outsideDisplay) {
            return false;
        }
    }

    /**
     * 记下打开播放窗口之前的显示状态。
     *
     * <p>只在第一个窗口上生效：连续开关几个播放窗口，最后要回到的是"看片之前"
     * 的样子，而不是上一个窗口退出时的样子。</p>
     */
    public static int capture() {
        if (captured) {
            return generation;
        }
        captured = true;
        generation++;
        wasFullscreen = active();
        try {
            windowWidth = Display.getWidth();
            windowHeight = Display.getHeight();
        } catch (Throwable outsideDisplay) {
            windowWidth = -1;
            windowHeight = -1;
        }
        return generation;
    }

    /** F 键 / 右键：全屏与窗口之间切换，方向以真实状态为准。 */
    public static void toggle(Minecraft mc) {
        if (mc == null) {
            return;
        }
        capture();
        try {
            if (active()) {
                leave(mc);
            } else {
                enter(mc);
            }
        } catch (Throwable refused) {
            // 驱动拒绝切换不该把片子一起带走：画面留在原来的模式里。
        }
    }

    private static void enter(Minecraft mc) throws Exception {
        // 先让 Minecraft 自己的账也对上，否则它下一次切换又会朝反方向走。
        if (!mc.isFullScreen()) {
            mc.toggleFullscreen();
        }
        if (!active()) {
            // 它的切换没生效（字段与桌面不同步的另一种表现），直接让 LWJGL 来。
            Display.setDisplayModeAndFullscreen(Display.getDesktopDisplayMode());
            mc.resize(Display.getWidth(), Display.getHeight());
        }
    }

    private static void leave(Minecraft mc) throws Exception {
        if (mc.isFullScreen()) {
            // 走它的路径是为了让它把 fullscreen 字段、framebuffer 和 GUI 一起更新；
            // 尺寸下面我们自己改回来。
            mc.toggleFullscreen();
        }
        if (active()) {
            Display.setFullscreen(false);
        }
        if (windowWidth > 0 && windowHeight > 0
                && (Display.getWidth() != windowWidth || Display.getHeight() != windowHeight)) {
            Display.setDisplayMode(new DisplayMode(windowWidth, windowHeight));
            mc.resize(Display.getWidth(), Display.getHeight());
        }
    }

    /**
     * 所有播放窗口都关了：把桌面恢复到第一次打开之前的样子。
     *
     * <p>进去之前是全屏的人继续全屏，是窗口的人回到那个窗口尺寸；中途自己按过
     * F 的也不留下痕迹。</p>
     */
    public static void restore(int generationAtOpen, Minecraft mc) {
        if (!captured || generationAtOpen != generation) {
            return;
        }
        boolean wantFullscreen = wasFullscreen;
        int width = windowWidth;
        int height = windowHeight;
        captured = false;
        weEntered = false;
        windowWidth = -1;
        windowHeight = -1;
        if (mc == null) {
            return;
        }
        try {
            if (active() != wantFullscreen) {
                if (wantFullscreen) {
                    enter(mc);
                } else {
                    leave(mc);
                }
            } else if (!wantFullscreen && width > 0 && height > 0
                    && (Display.getWidth() != width || Display.getHeight() != height)) {
                Display.setDisplayMode(new DisplayMode(width, height));
                mc.resize(Display.getWidth(), Display.getHeight());
            }
        } catch (Throwable refused) {
            // 窗口已经不在了（退出游戏）时就没什么可恢复的。
        }
    }
}

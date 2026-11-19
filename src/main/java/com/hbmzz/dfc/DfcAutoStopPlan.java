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

package com.hbmzz.dfc;

/**
 * 自动停堆 / 强制抑制流程，作为纯逻辑状态机，完全不依赖 Minecraft 类型。
 *
 * <h3>术语（既定，不自行改写）</h3>
 * <ul>
 *   <li>停堆 —— 正常 / 待机状态。</li>
 *   <li>自动停堆 —— DFC 温度超过 {@link #HOT_THRESHOLD_C} 且 DFC 正在破坏建筑时
 *       立即进入，不存在任何中间等待状态。</li>
 *   <li>强制抑制 —— 自动停堆的逐步降温结束后温度仍高于阈值时进入；先播放一段
 *       明确的特效过程，特效结束时温度立即降为 0。它既不是持续降温，也不是没有
 *       过程的直接归零。</li>
 * </ul>
 *
 * <h3>为什么独立成类</h3>
 * <p>每个决定都是"当前温度 + 是否正在破坏建筑"的纯函数，因此可以用
 * {@code tools/dfccheck} 离线逐步断言，而不必守着一台真实 DFC 等它融毁。
 * 特效、声音与字段写入由 {@link DfcAutoShutdown} 负责，本类只做判断。</p>
 */
public final class DfcAutoStopPlan {

    /** 触发自动停堆的温度阈值（摄氏度）。 */
    public static final double HOT_THRESHOLD_C = 5000.0D;

    /** 对整个流程统一对外显示的三个词。 */
    public enum State {
        STOPPED("停堆"),
        AUTO_STOP("自动停堆"),
        FORCE_SUPPRESS("强制抑制");

        private final String label;

        State(String label) {
            this.label = label;
        }

        /** 统一对外文案，只允许这三个词。 */
        public String label() {
            return this.label;
        }
    }

    private final double stepC;
    private final int maxAutoTicks;
    private final int suppressFxTicks;

    private State state = State.STOPPED;
    private int age;
    private double lastTemperature;
    private int calm;

    /** 本 tick 是否刚刚进入自动停堆（供触发音乐用，读取后自动清除）。 */
    private boolean enteredAutoStop;
    /** 本 tick 是否刚刚进入强制抑制（供触发一次性音效 / 日志用）。 */
    private boolean enteredSuppress;
    /** 本 tick 是否由强制抑制写入了最终归零（特效刚结束）。 */
    private boolean zeroWrittenThisTick;

    /**
     * @param stepC           自动停堆每 tick 的降温步长（摄氏度）
     * @param maxAutoTicks    逐步降温阶段最多持续的 tick 数，兜底用
     * @param suppressFxTicks 强制抑制特效持续的 tick 数；此期间不动温度，
     *                        结束时一次性写 0
     */
    public DfcAutoStopPlan(double stepC, int maxAutoTicks, int suppressFxTicks) {
        this.stepC = Math.max(1.0D, stepC);
        this.maxAutoTicks = Math.max(1, maxAutoTicks);
        this.suppressFxTicks = Math.max(1, suppressFxTicks);
    }

    public State state() {
        return this.state;
    }

    /** 统一显示文案：停堆 / 自动停堆 / 强制抑制。 */
    public String displayState() {
        return this.state.label();
    }

    public int age() {
        return this.age;
    }

    /** 强制抑制特效持续的 tick 数。 */
    public int suppressFxTicks() {
        return this.suppressFxTicks;
    }

    public boolean enteredAutoStop() {
        return this.enteredAutoStop;
    }

    public boolean enteredSuppress() {
        return this.enteredSuppress;
    }

    /** 本 tick 是否刚由强制抑制把温度归零（特效过程刚结束）。 */
    public boolean zeroWrittenThisTick() {
        return this.zeroWrittenThisTick;
    }

    /** 流程正在运行（自动停堆或强制抑制）。 */
    public boolean active() {
        return this.state != State.STOPPED;
    }

    /** 强制抑制特效是否应在这一 tick 播放。 */
    public boolean emittingSuppressFx() {
        return this.state == State.FORCE_SUPPRESS && this.age < this.suppressFxTicks;
    }

    /**
     * 本 tick 应写入核心的温度，或 {@code null} 表示不要碰温度。
     *
     * <p>自动停堆阶段每 tick 往下降一个步长；强制抑制阶段特效播放期间完全不
     * 动温度，直到特效结束的那一 tick 一次性写 0（立即归零，有前置过程）。</p>
     */
    public Double desiredTemperature() {
        switch (this.state) {
            case AUTO_STOP:
                return Math.max(0.0D, this.lastTemperature - this.stepC);
            case FORCE_SUPPRESS:
                if (this.age >= this.suppressFxTicks) {
                    this.zeroWrittenThisTick = true;
                    return 0.0D;
                }
                // 特效过程中完全不碰温度：既不是持续降温，也不是无过程直接归零。
                return null;
            default:
                return null;
        }
    }

    /**
     * 推进一个 tick。
     *
     * @param temperature 当前核心温度（摄氏度）
     * @param destroying  DFC 是否正在破坏建筑
     */
    public void tick(double temperature, boolean destroying) {
        this.enteredAutoStop = false;
        this.enteredSuppress = false;
        this.zeroWrittenThisTick = false;
        this.lastTemperature = temperature;
        boolean hotEnough = temperature > HOT_THRESHOLD_C;

        switch (this.state) {
            case STOPPED:
                if (hotEnough && destroying) {
                    enter(State.AUTO_STOP);
                    this.enteredAutoStop = true;
                }
                return;

            case AUTO_STOP:
                this.age++;
                // 降温已经把温度拉回阈值以下，且不再破坏 -> 直接回到停堆。
                if (!destroying && !hotEnough) {
                    enter(State.STOPPED);
                    return;
                }
                // 逐步降温阶段结束：若仍高于阈值，进入强制抑制；否则停堆。
                if (this.age >= this.maxAutoTicks) {
                    if (temperature > HOT_THRESHOLD_C) {
                        enter(State.FORCE_SUPPRESS);
                        this.enteredSuppress = true;
                    } else {
                        enter(State.STOPPED);
                    }
                }
                return;

            case FORCE_SUPPRESS:
                this.age++;
                // 特效播放满 suppressFxTicks 个 tick 后，desiredTemperature() 会在
                // 紧接着的采样里返回 0（立即归零）；状态本身再回到停堆。
                // 注意不能在这里提前回 STOPPED，否则那个 0 永远不会被写出。
                if (this.age > this.suppressFxTicks) {
                    enter(State.STOPPED);
                }
                return;

            default:
                enter(State.STOPPED);
        }
    }

    /** 彻底复位。核心被解绑 / 换核心 / 服务器停止时调用。 */
    public void release() {
        enter(State.STOPPED);
    }

    private void enter(State next) {
        this.state = next;
        this.age = 0;
        this.calm = 0;
    }
}

package com.rsaudio.tile;

/**
 * 音频播放器"红石输入 → 开播/停播"的**纯决策逻辑**。
 * <p>
 * 单独抽出来的唯一目的是**可测**：这个类不 import 任何 Minecraft 类，
 * 所以能脱离 Forge 单独 javac 编译、在几秒内跑仿真断言
 * （见 {@code rsaudio/tools/check_player_trigger.py}）。
 * {@link TileAudioPlayer} 只负责把 {@code world.isBlockPowered(pos)} 喂进来、
 * 按返回的动作去调 start/stop。
 * <p>
 * ★ 这里放着一个踩过的坑：**自激振荡**。
 * <pre>
 * 音频播放器是「六面全向输入 + 六面全向输出」。开了"播完输出红石信号"之后：
 *   整首放完 → 对外发 4 刻脉冲（getWeakPower 六个方向都返回 15）
 *            → 紧贴它的红石线立刻被点亮
 *            → World.isBlockPowered(pos) 查的正是这些邻居 → 判定"有信号"
 *            → 上升沿 → 又开播 → 放完又发脉冲 → 无限循环
 * </pre>
 * 用户报的"播完输出红石还会驱动再次播放"就是这个。
 * 修法：发脉冲的同时进入 {@link #SELF_TRIGGER_GUARD_TICKS} 刻的"自激保护"，
 * 这段时间内一律不处理输入（但**必须继续同步 lastPowered**，
 * 否则保护结束时残留的回音会被当成一个全新的上升沿，照样多播一遍）。
 * <p>
 * 注意这里用的是**电平**（{@code isBlockPowered}）而不是邻居变化事件，
 * 所以保护只能靠时间窗，不能靠"区分是谁给的信号" —— 红石线把信号带回来时
 * 我们没法知道源头是自己。窗口取脉冲长度 + 6 刻，足够让相邻红线的回音散掉。
 */
public final class RedstoneTrigger {

    /** 本刻的判定结果 */
    public enum Result {
        /** 输入没变化 */
        NONE,
        /** 处于自激保护期，本刻完全不处理输入 */
        GUARDED,
        /** 输入出现上升沿（无信号 → 有信号） */
        RISING,
        /** 输入出现下降沿（有信号 → 无信号） */
        FALLING
    }

    /** "播完输出红石信号"的脉冲长度（刻） */
    public static final int FINISH_PULSE_TICKS = 4;

    /**
     * 播完脉冲发出后忽略输入的时长（刻）。
     * <p>
     * 脉冲 4 刻 + 6 刻余量 = 10 刻（0.5 秒）。余量是留给相邻红石线复位用的：
     * 红线在我们的脉冲消失后的同刻～下刻就会掉回 0。
     * 这 0.5 秒内玩家按按钮不生效，换来的是不会自己振荡。
     */
    public static final int SELF_TRIGGER_GUARD_TICKS = FINISH_PULSE_TICKS + 6;

    /** 上一刻的输入电平 */
    private boolean lastPowered;
    /** 自激保护剩余刻数，> 0 时 step() 一律返回 GUARDED */
    private int guardTicks;

    /**
     * 方块刚加载/刚放置时对齐一次（只记电平，不产生上升沿，避免读档就响）。
     */
    public void reset(boolean powered) {
        this.lastPowered = powered;
        this.guardTicks = 0;
    }

    /** 发了"播完脉冲"之后调用，进入自激保护期 */
    public void armSelfTriggerGuard() {
        this.guardTicks = SELF_TRIGGER_GUARD_TICKS;
    }

    /** 自激保护剩余刻数（调试/测试用） */
    public int getGuardTicks() {
        return guardTicks;
    }

    /** 上一刻的电平（调试/测试用） */
    public boolean isLastPowered() {
        return lastPowered;
    }

    /**
     * 推进一刻。
     *
     * @param powered 本刻的输入电平，即 {@code world.isBlockPowered(pos)}
     * @return 本刻该做什么
     */
    public Result step(boolean powered) {
        boolean rising = powered && !lastPowered;
        boolean falling = !powered && lastPowered;

        // ★ 先同步电平，再看保护期。顺序反了的话，保护期结束时
        //   残留的回音会变成一个"全新的上升沿"，等于白保护。
        this.lastPowered = powered;

        if (guardTicks > 0) {
            guardTicks--;
            return Result.GUARDED;
        }
        if (rising) {
            return Result.RISING;
        }
        if (falling) {
            return Result.FALLING;
        }
        return Result.NONE;
    }
}

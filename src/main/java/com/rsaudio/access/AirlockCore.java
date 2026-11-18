package com.rsaudio.access;

import java.util.Arrays;

/**
 * 气闸（airlock）的<b>策略内核</b> —— 纯逻辑，<b>不 import 任何 Minecraft 类</b>。
 *
 * <h2>它解决什么问题</h2>
 * 之前"气闸"是靠 {@link AccessCore} 的联锁 + 循环倒计时<b>近似</b>出来的：
 * 同频道两道门互斥，一扇关上给另一扇起一段倒计时。玩家感觉上差不多，但少了一件事 ——
 * <b>按一下按钮就走完整个流程</b>。真实的闸室不是"手动开、手动关、手动开另一扇"，
 * 而是：走进去 → 按一下 → 身后的门关上 → 舱室净化一段固定时间 → 对面的门打开。
 * <p>
 * 这个类就是那台"按一下就自动走完"的状态机：
 * <pre>
 *   IDLE ──requestTransfer──▶ LOCKING ──两侧都关上──▶ DECON
 *     ▲                                                  │
 *     │                                              倒计时归零
 *     │                                                  ▼
 *     └──────────────目标侧确实开了────────────── UNLOCKING
 * </pre>
 *
 * <h2>★ 净化期间<b>两侧都必须封死</b></h2>
 * 这是气闸之所以是气闸的全部意义。所以循环一开始就要把两侧同时封住 ——
 * 而"封住"这个动作<b>不能占用玩家或 FAAS 的门闩位</b>，否则循环结束时解封会顺手
 * 把他们的锁一起解掉（见 {@link AccessCore#REASON_AIRLOCK} 的注释）。
 * 内核这边只负责"我现在处于循环中"，真正落到门上的动作由方块实体去做。
 *
 * <h2>★ 只有<b>目标侧</b>的门闩会挡住流程</h2>
 * 净化结束后要开的是目标侧（玩家要去的那一边）。如果它被 FAAS 封锁了，
 * 这时候<b>不能中止</b>、也<b>不能强开</b>：玩家已经封在舱室里了，
 * 中止等于白净化一次，强开等于把封锁当摆设。正确做法是<b>憋住</b> ——
 * 停在"净化已完成、等放行"的状态，两边都保持关闭，直到封锁解除。
 * 这就是隔离该有的样子：封锁生效时，人就被关在闸室里。
 * <p>
 * 反过来，<b>入口侧</b>的门闩不影响流程（那正是"把门锁在身后"的常规操作），
 * 所以它被排除在检查之外。
 *
 * <h2>★ 超时而不是死等</h2>
 * LOCKING 和 UNLOCKING 两段都在等一个"物理动作"发生（门关上 / 门打开）。
 * 物理动作可能永远完不成（门被拆了、方块实体所在区块没加载、玩家站在门里挡着），
 * 所以每一段都有 {@link #getLockTimeout()} 刻的上限，超了就 {@link #abort()}：
 * 回 IDLE、两侧保持关闭、把气闸位解掉。★ 中止<b>不会</b>打开任何一侧 ——
 * 一个走不下去的循环最安全的收场是"保持密闭"，而不是"猜一个方向打开"。
 *
 * <h2>两侧是怎么分出来的</h2>
 * 内核只认 A / B 两个编号，谁是谁由方块实体按几何位置确定（见 {@code TileAirlockController}）。
 * 这样做是为了让内核可以被独立测：同一套规则喂 A/B 两种输入应当给出完全对称的结果。
 *
 * <h2>★ 调用约定：报状态 → 再问</h2>
 * 这个内核<b>不做任何 IO</b>，它眼里的世界完全由 {@link #setSideClosed} /
 * {@link #setSideLatched} / {@link #setDoorCount} 三个入口决定。由此推出一条硬约定：
 * <b>任何"做决定"的调用之前，都必须先把两侧的现状重新报一遍。</b>
 * <ul>
 *   <li>{@link #tick()} 之前要报 —— 不然会拿上一刻的状态判断，各段都慢一拍；</li>
 *   <li>{@link #requestTransfer} 之前<b>更</b>要报 —— 按按钮是"任意时刻"发生的，
 *       而报上来的门闩最多差 50 毫秒。漏了这一步，玩家在一扇刚被锁上的门旁边
 *       按按钮，还能把气闸跑起来。</li>
 * </ul>
 * 这不是洁癖：方块实体那边一次 {@code getTileEntity} 就能拿到最新的锁状态，
 * 而"用一份过期的锁状态去决定要不要开门"正是安全方向上最不该有的捷径。
 */
public final class AirlockCore {

    // ------------------------------------------------------------------ 两侧

    /** 甲侧。哪一侧是甲由方块实体按几何位置定，内核不关心 */
    public static final int SIDE_A = 0;
    /** 乙侧 */
    public static final int SIDE_B = 1;
    /** 无效侧（还没配对 / 拆掉了） */
    public static final int SIDE_NONE = -1;
    /** 一个气闸恰好两侧 */
    public static final int SIDES = 2;

    // ------------------------------------------------------------------ 状态

    public static final int STATE_IDLE = 0;
    /** 正在把两侧关上并封死 */
    public static final int STATE_LOCKING = 1;
    /** 两侧已封死，净化倒计时中 */
    public static final int STATE_DECON = 2;
    /** 净化完成，正在打开目标侧 */
    public static final int STATE_UNLOCKING = 3;

    // ------------------------------------------------------------------ tick() 的事件

    public static final int EVENT_NONE = 0;
    /** 两侧刚封死，净化开始 —— 播报/音效挂在这里 */
    public static final int EVENT_DECON_START = 1;
    /** 净化倒计时刚好归零 */
    public static final int EVENT_DECON_DONE = 2;
    /** 该去开目标侧了（调用方读 {@link #getDestSide()}） */
    public static final int EVENT_OPEN_SIDE = 3;
    /** 净化完成但目标侧被封锁 —— 憋住。每次进入"憋住"只报一次 */
    public static final int EVENT_HOLD = 4;
    /** 超时/被打断，回 IDLE。两侧保持关闭，调用方负责解开气闸位 */
    public static final int EVENT_ABORT = 5;
    /** 目标侧确实打开了，整个流程走完。调用方负责解开两侧的气闸位 */
    public static final int EVENT_DONE = 6;

    // ------------------------------------------------------------------ 起不来的原因

    public static final int REFUSE_NONE = 0;
    /** 已经在跑一个循环了 */
    public static final int REFUSE_BUSY = 1;
    /** 侧编号非法 */
    public static final int REFUSE_SIDE = 2;
    /** 两侧里至少有一侧被封锁（手动 / FAAS / 别的气闸） */
    public static final int REFUSE_LOCKED = 3;
    /** 还没配齐两扇门 */
    public static final int REFUSE_SETUP = 4;

    // ------------------------------------------------------------------ 配置

    /** 净化时长（刻）。★ 默认 180 = 9 秒，照抄参考实现 {@code DECONTAMINATION_TICKS} */
    public static final int DECON_MIN = 0;
    public static final int DECON_MAX = 3600;
    public static final int DECON_DEFAULT = 180;

    /** 等一个物理动作（门关上/打开）的上限（刻）。5 秒走不完就别等了 */
    public static final int TIMEOUT_MIN = 20;
    public static final int TIMEOUT_MAX = 1200;
    public static final int TIMEOUT_DEFAULT = 100;

    /** 存档槽：state / timer / fromSide / deconTicks / lockTimeout */
    public static final int SLOTS = 5;

    // ------------------------------------------------------------------ 字段

    private int state = STATE_IDLE;
    /** LOCKING：已经等了多久；DECON：还剩多少；UNLOCKING：已经等了多久 */
    private int timer;
    /**
     * 本次转移的<b>起点侧</b>（玩家从哪一侧进来的）。
     * <p>
     * ★ 循环结束<b>不</b>清空：留着当"上一次的方向"给界面显示，也让调用方在收到
     * {@link #EVENT_DONE} 时还能读出目标侧是哪一边。只有 {@link #reset()} 会清掉。
     */
    private int fromSide = SIDE_NONE;
    private int deconTicks = DECON_DEFAULT;
    private int lockTimeout = TIMEOUT_DEFAULT;

    /** 方块实体报上来的：已经配对上的门数（0..2）。配不齐两扇就没法开闸 */
    private int doorCount;
    /** 方块实体报上来的：这一侧现在是关着的吗 */
    private final boolean[] closed = new boolean[SIDES];
    /** 方块实体报上来的：这一侧现在被锁着吗（<b>不含</b>本气闸自己封的那一把） */
    private final boolean[] latched = new boolean[SIDES];
    /** "憋住"事件只报一次，不然每 tick 都刷一条播报 */
    private boolean holdReported;

    /** 跑完过几次（不持久化，纯统计） */
    private int completedCycles;
    /** 中止过几次（不持久化，纯统计） */
    private int abortedCycles;

    // ------------------------------------------------------------------ 静态工具

    public static boolean isValidSide(int side) {
        return side == SIDE_A || side == SIDE_B;
    }

    /** 另一侧。非法输入返回 {@link #SIDE_NONE} */
    public static int other(int side) {
        if (side == SIDE_A) {
            return SIDE_B;
        }
        if (side == SIDE_B) {
            return SIDE_A;
        }
        return SIDE_NONE;
    }

    public static int clampDecon(int value) {
        if (value < DECON_MIN) {
            return DECON_MIN;
        }
        return value > DECON_MAX ? DECON_MAX : value;
    }

    public static int clampTimeout(int value) {
        if (value < TIMEOUT_MIN) {
            return TIMEOUT_MIN;
        }
        return value > TIMEOUT_MAX ? TIMEOUT_MAX : value;
    }

    /** 给界面选语言键用 */
    public static String stateName(int state) {
        switch (state) {
            case STATE_LOCKING:
                return "LOCKING";
            case STATE_DECON:
                return "DECON";
            case STATE_UNLOCKING:
                return "UNLOCKING";
            default:
                return "IDLE";
        }
    }

    // ------------------------------------------------------------------ 读取

    public int getState() {
        return state;
    }

    public String getStateName() {
        return stateName(state);
    }

    public int getTimer() {
        return timer;
    }

    /**
     * 净化还剩多少刻。
     * <p>
     * ★ 只有真的在净化时才报数。LOCKING 阶段报 {@code deconTicks} 会让界面显示
     * "剩余 180" 却没在倒计时，玩家会以为卡住了。
     */
    public int getDeconRemaining() {
        return state == STATE_DECON ? timer : 0;
    }

    public int getFromSide() {
        return fromSide;
    }

    /** 本次（或上一次）转移的目标侧 —— 净化完要开的就是这一边 */
    public int getDestSide() {
        return other(fromSide);
    }

    public int getDeconTicks() {
        return deconTicks;
    }

    public int getLockTimeout() {
        return lockTimeout;
    }

    public int getDoorCount() {
        return doorCount;
    }

    public boolean isRunning() {
        return state != STATE_IDLE;
    }

    /** 正在等"两侧关好"这一步 */
    public boolean isLocking() {
        return state == STATE_LOCKING;
    }

    /** 净化中（界面据此显示倒计时） */
    public boolean isDecontaminating() {
        return state == STATE_DECON;
    }

    /**
     * 舱室现在是<b>密闭</b>的吗（循环中 且 两侧都关着）。
     * <p>
     * 注意它不是"两侧都关着"——IDLE 且两侧关着是常态（没人用的时候），
     * 那不叫密闭，叫关机。密闭是"循环正在进行、而且确实封上了"。
     */
    public boolean isSealed() {
        return isRunning() && closed[SIDE_A] && closed[SIDE_B];
    }

    /**
     * 两侧<b>同时开着</b> —— 气闸失效（漏气）。
     * <p>
     * 这不是本内核造成的：循环期间两侧都被封死。会同时开着只可能是
     * 门没开联锁、或者被人用别的手段打开的。留这个读数是为了让控制器能报警。
     */
    public boolean isLeaking() {
        return closed[SIDE_A] == false && closed[SIDE_B] == false;
    }

    public boolean isClosed(int side) {
        return isValidSide(side) && closed[side];
    }

    public boolean isLatched(int side) {
        return isValidSide(side) && latched[side];
    }

    public int getCompletedCycles() {
        return completedCycles;
    }

    public int getAbortedCycles() {
        return abortedCycles;
    }

    // ------------------------------------------------------------------ 方块实体报状态

    /** 每 tick 由方块实体报上来：现在配对上了几扇门（0..2） */
    public void setDoorCount(int value) {
        this.doorCount = value < 0 ? 0 : (value > SIDES ? SIDES : value);
    }

    /**
     * 方块实体报上来：这一侧现在关着吗。
     * <p>
     * ★ 报的是<b>实际位置</b>（门开了几行），不是"意图"。重型门走好几刻，
     * 用意图的话 LOCKING 会在门还没落到底时就以为关好了，净化提前开始 ——
     * 而那一刻舱室其实是敞的。
     */
    public void setSideClosed(int side, boolean value) {
        if (isValidSide(side)) {
            closed[side] = value;
        }
    }

    /**
     * 方块实体报上来：这一侧现在被锁着吗。
     * <p>
     * ★ 必须<b>排除本气闸自己封的那一把</b>：循环期间两侧都被自己封着，
     * 把自己的封锁算进来的话，净化一结束就会判定"目标侧被封锁"→ 永久憋住。
     * 所以调用方报的是"除气闸位以外还有没有锁"（手动位 / 远程位）。
     */
    public void setSideLatched(int side, boolean value) {
        if (isValidSide(side)) {
            latched[side] = value;
        }
    }

    // ------------------------------------------------------------------ 能不能开

    /**
     * 界面用：这一侧现在允许被手动打开吗。
     * <p>
     * 循环期间两侧一律不许手动开 —— 不然玩家可以在净化中间把身后的门推开，
     * 那整个循环就没意义了。
     */
    public boolean canOpenSide(int side) {
        if (!isValidSide(side)) {
            return false;
        }
        return state == STATE_IDLE;
    }

    /** 界面用：这一侧被循环挡着开不了的原因（复用 {@link AccessCore} 的 REASON_*） */
    public int sideBlockReason(int side) {
        return canOpenSide(side) ? AccessCore.REASON_NONE : AccessCore.REASON_CYCLE;
    }

    // ------------------------------------------------------------------ 起一次转移

    /**
     * 能不能起一次转移。
     *
     * @param from 玩家所在的这一侧（净化完会开另一侧）
     * @return {@link #REFUSE_NONE} = 可以起
     */
    public int checkStart(int from) {
        if (state != STATE_IDLE) {
            return REFUSE_BUSY;
        }
        if (!isValidSide(from)) {
            return REFUSE_SIDE;
        }
        if (doorCount < SIDES) {
            return REFUSE_SETUP;
        }
        if (latched[SIDE_A] || latched[SIDE_B]) {
            return REFUSE_LOCKED;
        }
        return REFUSE_NONE;
    }

    public boolean canStart(int from) {
        return checkStart(from) == REFUSE_NONE;
    }

    /**
     * 起一次转移：两侧关上 → 净化 → 打开 against 侧。
     *
     * @param from 玩家所在的这一侧
     * @return 是不是真的起来了
     */
    public boolean requestTransfer(int from) {
        if (checkStart(from) != REFUSE_NONE) {
            return false;
        }
        this.fromSide = from;
        this.state = STATE_LOCKING;
        this.timer = 0;
        this.holdReported = false;
        return true;
    }

    /** 中止当前循环（急停按钮 / 拆机器）。没在跑就什么都不做 */
    public boolean cancel() {
        if (state == STATE_IDLE) {
            return false;
        }
        abort();
        return true;
    }

    private void abort() {
        this.state = STATE_IDLE;
        this.timer = 0;
        this.holdReported = false;
        this.abortedCycles++;
    }

    private void finish() {
        this.state = STATE_IDLE;
        this.timer = 0;
        this.holdReported = false;
        this.completedCycles++;
    }

    // ------------------------------------------------------------------ 每 tick

    /**
     * 推进一步。调用方应当<b>先</b>把两侧的实际状态报进来（{@link #setSideClosed} 等），
     * <b>再</b>调这个方法 —— 顺序反了的话会拿上一刻的状态做判断，各段都慢一拍。
     *
     * @return {@code EVENT_*}，{@link #EVENT_NONE} = 什么都没发生
     */
    public int tick() {
        switch (state) {
            case STATE_LOCKING: {
                timer++;
                if (closed[SIDE_A] && closed[SIDE_B]) {
                    state = STATE_DECON;
                    timer = deconTicks;
                    holdReported = false;
                    return EVENT_DECON_START;
                }
                if (timer >= lockTimeout) {
                    abort();
                    return EVENT_ABORT;
                }
                return EVENT_NONE;
            }
            case STATE_DECON: {
                if (timer > 0) {
                    timer--;
                    // 归零的这一 tick 只报"净化完成"，不顺手去开 ——
                    // 让调用方有独立的一刻去播"净化完成"的提示/音效
                    return timer == 0 ? EVENT_DECON_DONE : EVENT_NONE;
                }
                int dest = getDestSide();
                if (dest == SIDE_NONE) {
                    abort();
                    return EVENT_ABORT;
                }
                if (latched[dest]) {
                    // ★ 目标侧被封锁 → 憋住。不是中止、也不是强开，见类注释
                    if (!holdReported) {
                        holdReported = true;
                        return EVENT_HOLD;
                    }
                    return EVENT_NONE;
                }
                holdReported = false;
                state = STATE_UNLOCKING;
                timer = 0;
                return EVENT_OPEN_SIDE;
            }
            case STATE_UNLOCKING: {
                timer++;
                int dest = getDestSide();
                if (dest == SIDE_NONE) {
                    abort();
                    return EVENT_ABORT;
                }
                if (!closed[dest]) {
                    // 目标侧确实开了 —— 整个流程走完
                    finish();
                    return EVENT_DONE;
                }
                if (timer >= lockTimeout) {
                    abort();
                    return EVENT_ABORT;
                }
                return EVENT_NONE;
            }
            default:
                return EVENT_NONE;
        }
    }

    // ------------------------------------------------------------------ 配置

    public void setDeconTicks(int value) {
        this.deconTicks = clampDecon(value);
        if (state == STATE_DECON && timer > deconTicks) {
            // 净化中途把时长改短了：夹回来，免得倒计时比配置还长
            timer = deconTicks;
        }
    }

    public void setLockTimeout(int value) {
        this.lockTimeout = clampTimeout(value);
    }

    // ------------------------------------------------------------------ 存档

    public int[] saveState() {
        int[] a = new int[SLOTS];
        a[0] = state;
        a[1] = timer;
        // ★ fromSide 的 -1 不能直接进 int[]（能，但读回来要靠约定）。
        //   这里编成 0 / 1 / 2，0 表示"没有方向"
        a[2] = fromSide == SIDE_NONE ? 0 : fromSide + 1;
        a[3] = deconTicks;
        a[4] = lockTimeout;
        return a;
    }

    /**
     * 读档。
     * <p>
     * ★ 中途存档（区块卸载）后<b>可以接着跑</b>：state / timer / fromSide 都存了，
     * 两侧的实际状态在下一 tick 会被方块实体重新报上来。所以净化进度不会因为
     * 玩家走远而被重置。但要是 fromSide 丢了，这一轮就没方向了 —— 直接回 IDLE 重来，
     * 而不是猜一个方向强行开一侧。
     * <p>
     * ★ 无论走哪条分支，<b>配置（净化时长 / 超时）都必须保住</b>：
     * 它是玩家在界面上填的参数，因为一次动到损坏的存档就被清回默认值，
     * 玩家是看不出原因、也想不到要去重填的。
     */
    public void loadState(int[] a) {
        if (a == null || a.length < SLOTS) {
            reset();
            return;
        }
        int st = a[0];
        if (st < STATE_IDLE || st > STATE_UNLOCKING) {
            reset();
            return;
        }
        int side = a[2] - 1;
        if (!isValidSide(side)) {
            // 没方向 → 退到 IDLE（配置照旧保留）
            side = SIDE_NONE;
            st = STATE_IDLE;
        }
        this.state = st;
        this.timer = a[1] < 0 ? 0 : a[1];
        this.fromSide = side;
        this.deconTicks = clampDecon(a[3]);
        this.lockTimeout = clampTimeout(a[4]);
        if (this.state == STATE_DECON && this.timer > this.deconTicks) {
            this.timer = this.deconTicks;
        }
        if (this.state == STATE_IDLE) {
            this.timer = 0;
        }
        this.holdReported = false;
        // closed / latched / doorCount 都是"外部现状"的投影，不持久化：
        // 存下来的只会是一份过期的快照，下一 tick 就会拿到真的
        clearReports();
    }

    private void clearReports() {
        this.closed[SIDE_A] = false;
        this.closed[SIDE_B] = false;
        this.latched[SIDE_A] = false;
        this.latched[SIDE_B] = false;
        this.doorCount = 0;
    }

    public void reset() {
        this.state = STATE_IDLE;
        this.timer = 0;
        this.fromSide = SIDE_NONE;
        this.deconTicks = DECON_DEFAULT;
        this.lockTimeout = TIMEOUT_DEFAULT;
        this.holdReported = false;
        this.completedCycles = 0;
        this.abortedCycles = 0;
        clearReports();
    }

    @Override
    public String toString() {
        return "AirlockCore{" + getStateName() + ", t=" + timer + "/" + deconTicks
                + ", from=" + sideName(fromSide) + ", dest=" + sideName(getDestSide())
                + ", doors=" + doorCount
                + ", closed=[" + closed[SIDE_A] + "," + closed[SIDE_B] + "]"
                + ", latched=[" + latched[SIDE_A] + "," + latched[SIDE_B] + "]"
                + ", done=" + completedCycles + ", aborted=" + abortedCycles + "}";
    }

    private static String sideName(int side) {
        if (side == SIDE_A) {
            return "A";
        }
        return side == SIDE_B ? "B" : "-";
    }

    /** 调试辅助：把存档数组打成一行 */
    public static String dump(int[] a) {
        return Arrays.toString(a);
    }
}

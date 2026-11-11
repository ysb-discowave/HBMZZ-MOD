package com.rsaudio.access;

import java.util.Arrays;

/**
 * 门禁与联锁的<b>策略内核</b> —— 纯逻辑，<b>不 import 任何 Minecraft 类</b>。
 *
 * <h2>它解决什么问题</h2>
 * QSERF 里的门不止"开/关"两种状态，真正撑起玩法的是这三件事：
 * <ol>
 *   <li><b>FAAS 远程锁定门</b> —— 门可以被上锁，上锁后本地怎么按都开不了
 *       （只有解除锁定才行）。这正是"封锁隔离"的机制基础。</li>
 *   <li><b>气闸（airlock）</b> —— 两道门<b>互斥</b>：一扇开着，另一扇就必须关着。
 *       再加一段"循环时间"（憋压/泄压），期间另一侧是锁死的。</li>
 *   <li><b>塔塔鲁斯大门</b> —— 一扇又大又重的闸门，不是"啪"地一下打开，
 *       而是<b>逐行升起</b>、每一行之间有间隔，看起来才像重型机构。</li>
 * </ol>
 * 这三件事的规则互相咬合，而且失败方式全是<b>静默</b>的（门能开但顺序不对、
 * 循环时间算错导致门永远打不开、锁定解除不掉……），所以把它们全收进这个
 * 零依赖的类里，由 {@code tools/check_access_core.py} 用真 javac 编译、真运行去钉死。
 *
 * <h2>锁定的三层来源</h2>
 * 门的"能不能开"不是一个布尔量，而是<b>三个独立来源</b>叠出来的，优先级从高到低：
 * <pre>
 *   ① 门闩 latch      —— 手动 / FAAS 远程 / 气闸循环，只在<b>显式解锁</b>时消失（会持久化）
 *   ② 循环倒计时       —— 有倒计时，时间到了<b>自己</b>消失
 *   ③ 联锁互斥         —— 同频道另一扇门开着，那扇门一关就<b>立刻</b>消失
 * </pre>
 * ★ 为什么必须分开而不是合并成一个 {@code locked} 布尔量：
 * 合并之后，气闸循环结束会把玩家手动上的封锁<b>一起清掉</b> ——
 * "我在隔离区门口拉了封锁，刚才有人过气闸，封锁自己没了"。
 * 这是那种上线很久才会被玩家发现、但一旦发现就很致命的 bug。
 *
 * <h3>★ 三个门闩位必须各管各的</h3>
 * 一开始这里只有一个 {@code latch}，谁后写谁生效。问题是三个方向都错：
 * <ul>
 *   <li>玩家<b>手动</b>封锁了隔离门，FAAS 一发"解除远程锁定"就把它解开了
 *       —— 远程把本地的决定覆盖掉了。</li>
 *   <li>反过来，FAAS 远程封锁之后，玩家按一下界面上的「解除封锁」也把它解开了
 *       —— 本地把远程的决定覆盖掉了。</li>
 *   <li>气闸控制器循环结束时必须解封，而它借用上面任一位的话，
 *       循环一结束就顺手把玩家/FAAS 上的锁一起解了。</li>
 * </ul>
 * 所以拆成三个位各管各的：界面上的「封锁 / 解除封锁」只动<b>手动</b>那一位，
 * 门禁终端的"锁定 / 放行"只动<b>远程</b>那一位，气闸控制器只动<b>气闸</b>那一位。
 * 三方各自坚持自己的意见，门只要能开就说明三方都放行了 —— 这是封锁隔离该有的语义。
 *
 * <h2>★ 关门永远允许</h2>
 * 上面三层只挡"开"。{@link #canClose()} 恒为 {@code true}。
 * 被锁死的门如果连关都关不上，一旦它当时是开着的就会留下一个<b>永久敞开的缺口</b> ——
 * 在"防核门"这种设定下，这比"锁不住"严重得多。安全阀只该朝一个方向使劲。
 */
public final class AccessCore {

    // ------------------------------------------------------------------ 常量

    /** 没有阻塞 */
    public static final int REASON_NONE = 0;
    /** 门闩：界面/终端手动封锁 */
    public static final int REASON_MANUAL = 1;
    /** 门闩：FAAS 远程封锁（红石／门禁终端下发） */
    public static final int REASON_REMOTE = 2;
    /** 气闸循环倒计时中 */
    public static final int REASON_CYCLE = 3;
    /** 联锁：同频道还有别的门开着 */
    public static final int REASON_INTERLOCK = 4;
    /** 气闸控制器把这一侧封死了（整个循环期间，两侧都不许开） */
    public static final int REASON_AIRLOCK = 5;

    /** 频道 0 = 不受门禁管理、也不参与联锁（单独一扇门时用这个） */
    public static final int CHANNEL_NONE = 0;
    public static final int CHANNEL_MIN = 1;
    public static final int CHANNEL_MAX = 16;
    public static final int CHANNEL_DEFAULT = CHANNEL_NONE;

    /**
     * 气闸循环时长（刻）。
     * <p>
     * 0 = 不循环（开一扇就只是关掉另一扇，不额外锁）。上限 1200 刻 = 60 秒，
     * 比这更长的话玩家会以为门坏了。
     */
    public static final int CYCLE_MIN = 0;
    public static final int CYCLE_MAX = 1200;
    public static final int CYCLE_DEFAULT = 60;

    /** 重型门每一行之间的间隔（刻）。4 刻 = 0.2 秒一行，7 行的门约 1.4 秒走完 */
    public static final int STAGE_MIN = 1;
    public static final int STAGE_MAX = 20;
    public static final int STAGE_DEFAULT = 4;

    public static final int FLAG_INTERLOCK = 1;
    public static final int FLAG_HEAVY = 2;

    /** 存档位：手动门闩 */
    public static final int LATCH_BIT_MANUAL = 1;
    /** 存档位：远程（FAAS）门闩 */
    public static final int LATCH_BIT_REMOTE = 2;
    /** 存档位：气闸控制器封的（见 {@link #REASON_AIRLOCK}） */
    public static final int LATCH_BIT_AIRLOCK = 4;

    /** 三个合法门闩位合起来 —— 读档时只认这几位，其余（手改的存档）一律忽略 */
    public static final int LATCH_BITS_KNOWN = LATCH_BIT_MANUAL | LATCH_BIT_REMOTE | LATCH_BIT_AIRLOCK;

    /** 存档槽数：latch / cycleRemaining / channel / cycleTicks / flags / progress / stageDelay */
    public static final int SLOTS = 7;

    // ------------------------------------------------------------------ 字段

    /** 门闩：玩家在界面上自己上的那一把 */
    private boolean manualLatch;
    /** 门闩：FAAS / 门禁终端下发的那一把 */
    private boolean remoteLatch;
    /**
     * 门闩：气闸控制器在整个循环期间封的那一把。
     * <p>
     * ★ 它必须是<b>独立的第三位</b>，不能借用手动位或远程位：
     * 气闸循环只有十几秒，结束时要<b>自己消失</b>。借用任一已有的位，
     * 循环结束时的那次解锁都会顺手把玩家或 FAAS 上的锁一起解掉 ——
     * 而"我在隔离区门口拉了封锁，有人过了次气闸，封锁自己没了"正是最危险的故障。
     */
    private boolean airlockLatch;
    /** 气闸循环剩余刻数 */
    private int cycleRemaining;
    /** 门禁频道。0 = 不参与门禁与联锁 */
    private int channel = CHANNEL_DEFAULT;
    /** 循环时长配置 */
    private int cycleTicks = CYCLE_DEFAULT;
    /** 是否参与联锁（同频道互斥）—— 两道门都打开这一项就是气闸 */
    private boolean interlock;
    /** 是否重型门（逐行开启） */
    private boolean heavy;
    /** 重型门每行间隔 */
    private int stageDelay = STAGE_DEFAULT;
    /** 分级运动的节拍累加器 */
    private int stageCooldown;
    /** 已开启的行数，0..height */
    private int progress;

    /**
     * 联锁状态：由外部（服务端扫同频道门之后）每 tick 写入。
     * <p>
     * ★ 刻意<b>不持久化</b>：它是"邻居当前状态"的投影，邻居一变就该重算，
     * 存进存档里只会得到一份过期的互相矛盾的状态。
     */
    private boolean interlockBlocked;

    // ------------------------------------------------------------------ 夹取

    public static int clampChannel(int value) {
        if (value <= CHANNEL_NONE) {
            return CHANNEL_NONE;
        }
        return value > CHANNEL_MAX ? CHANNEL_MAX : value;
    }

    public static int clampCycle(int value) {
        if (value < CYCLE_MIN) {
            return CYCLE_MIN;
        }
        return value > CYCLE_MAX ? CYCLE_MAX : value;
    }

    public static int clampStage(int value) {
        if (value < STAGE_MIN) {
            return STAGE_MIN;
        }
        return value > STAGE_MAX ? STAGE_MAX : value;
    }

    /** 把 {@code channel} 在"无门禁 ↔ 1..16"之间循环，供界面上的 ◀ / ▶ 用 */
    public static int stepChannel(int value, int delta) {
        int v = clampChannel(value);
        // 把 0 当成"16 的下一个"，这样从 0 往回按能到 16，是连续的一圈
        int size = CHANNEL_MAX + 1;
        int idx = (v + delta) % size;
        if (idx < 0) {
            idx += size;
        }
        return idx;
    }

    // ------------------------------------------------------------------ 读取

    /**
     * 当前生效的门闩种类（给界面选提示文字用）。
     * <p>
     * 两个位都亮着时报 MANUAL —— 让本地玩家的那把锁"说话"。
     * 玩家看到"已手动封锁"就知道是自己上的锁，按「解除封锁」；
     * 报 REMOTE 的话他会以为自己解不掉，反而不会去尝试。
     */
    public int getLatch() {
        if (manualLatch) {
            return REASON_MANUAL;
        }
        if (remoteLatch) {
            return REASON_REMOTE;
        }
        return airlockLatch ? REASON_AIRLOCK : REASON_NONE;
    }

    public boolean hasManualLatch() {
        return manualLatch;
    }

    public boolean hasRemoteLatch() {
        return remoteLatch;
    }

    /** 气闸控制器是不是正封着这一扇（见 {@link #REASON_AIRLOCK}） */
    public boolean hasAirlockLatch() {
        return airlockLatch;
    }

    public int getCycleRemaining() {
        return cycleRemaining;
    }

    public int getChannel() {
        return channel;
    }

    public int getCycleTicks() {
        return cycleTicks;
    }

    public boolean isInterlock() {
        return interlock;
    }

    public boolean isHeavy() {
        return heavy;
    }

    public int getStageDelay() {
        return stageDelay;
    }

    public int getProgress() {
        return progress;
    }

    public boolean isLatched() {
        return manualLatch || remoteLatch || airlockLatch;
    }

    public boolean isInterlockBlocked() {
        return interlockBlocked;
    }

    /** 门的开启进度百分比（0..100），给 HUD 用 */
    public int getProgressPercent(int height) {
        if (height <= 0) {
            return 0;
        }
        int p = progress * 100 / height;
        if (p < 0) {
            return 0;
        }
        return p > 100 ? 100 : p;
    }

    public boolean isFullyOpen(int height) {
        return height > 0 && progress >= height;
    }

    public boolean isFullyClosed() {
        return progress <= 0;
    }

    /** 参与联锁（频道有效 + 打开了联锁开关） */
    public boolean isInterlocked() {
        return interlock && channel != CHANNEL_NONE;
    }

    // ------------------------------------------------------------------ 能不能动

    /**
     * 当前挡着"开门"的原因，{@link #REASON_NONE} = 可以开。
     * <p>
     * 优先级：门闩 &gt; 循环 &gt; 联锁。门闩排最前是为了让提示信息<b>稳定</b> ——
     * 玩家上了锁、同时又在循环里，界面上应该一直显示"已封锁"，
     * 而不是循环一走完就跳成别的说法、让人以为锁没了。
     */
    public int blockReason() {
        if (manualLatch) {
            return REASON_MANUAL;
        }
        if (remoteLatch) {
            return REASON_REMOTE;
        }
        if (airlockLatch) {
            return REASON_AIRLOCK;
        }
        if (cycleRemaining > 0) {
            return REASON_CYCLE;
        }
        if (interlockBlocked) {
            return REASON_INTERLOCK;
        }
        return REASON_NONE;
    }

    public boolean canOpen() {
        return blockReason() == REASON_NONE;
    }

    /**
     * 能不能关。★ <b>恒为 true</b>。
     * <p>
     * 被锁死的门如果连关都关不上，而它当时恰好是开着的，就会留下一个永久缺口。
     * 见类注释"关门永远允许"。
     */
    public boolean canClose() {
        return true;
    }

    // ------------------------------------------------------------------ 门闩

    /**
     * 上门闩。只接受 {@link #REASON_MANUAL} / {@link #REASON_REMOTE} / {@link #REASON_AIRLOCK}。
     * <p>
     * ★ 三个来源各管各的位，互不覆盖 —— 见类注释"手动门闩和远程门闩也是两个独立位"。
     * 所以 FAAS 远程封锁一道已经被玩家手动封锁的门，返回 {@code false} ——
     * 返回值表达的是"这一位原来就亮着吗"，而不是"门闩状态有没有变"。
     */
    public boolean latch(int reason) {
        if (reason == REASON_MANUAL) {
            if (manualLatch) {
                return false;
            }
            manualLatch = true;
            return true;
        }
        if (reason == REASON_REMOTE) {
            if (remoteLatch) {
                return false;
            }
            remoteLatch = true;
            return true;
        }
        if (reason == REASON_AIRLOCK) {
            if (airlockLatch) {
                return false;
            }
            airlockLatch = true;
            return true;
        }
        return false;
    }

    /**
     * 解门闩。
     *
     * @param reason 只解这一位；{@link #REASON_NONE} 表示三位全解（拆机器/复位时用）
     */
    public boolean unlatch(int reason) {
        if (reason == REASON_MANUAL) {
            if (!manualLatch) {
                return false;
            }
            manualLatch = false;
            return true;
        }
        if (reason == REASON_REMOTE) {
            if (!remoteLatch) {
                return false;
            }
            remoteLatch = false;
            return true;
        }
        if (reason == REASON_AIRLOCK) {
            if (!airlockLatch) {
                return false;
            }
            airlockLatch = false;
            return true;
        }
        if (reason == REASON_NONE) {
            boolean changed = manualLatch || remoteLatch || airlockLatch;
            manualLatch = false;
            remoteLatch = false;
            airlockLatch = false;
            return changed;
        }
        return false;
    }

    /** 气闸控制器封门 / 放行。循环结束由控制器自己解，和另外两把锁互不干扰 */
    public boolean latchAirlock(boolean locked) {
        return locked ? latch(REASON_AIRLOCK) : unlatch(REASON_AIRLOCK);
    }

    /**
     * ★ 只解<b>手动</b>那一把。
     * <p>
     * 界面上那颗「解除封锁」是玩家自己的动作，不该顺手把 FAAS 下发的封锁也解掉。
     * 远程那把锁要由下发它的那一侧解除（门禁终端 / 红石）。
     */
    public boolean unlatch() {
        return unlatch(REASON_MANUAL);
    }

    /** 翻转<b>手动</b>门闩（界面上那个「封锁/解除封锁」按钮） */
    public boolean toggleLatch() {
        return manualLatch ? unlatch(REASON_MANUAL) : latch(REASON_MANUAL);
    }

    // ------------------------------------------------------------------ 联锁 / 循环

    /**
     * 开始一次气闸循环：把另一侧的等待时间重新拉满。
     * <p>
     * 返回是否真的起了 —— 没开联锁、或循环时长为 0 时不起，
     * 调用方据此决定要不要播"气闸循环"的提示。
     */
    public boolean beginCycle() {
        if (!isInterlocked() || cycleTicks <= 0) {
            cycleRemaining = 0;
            return false;
        }
        cycleRemaining = cycleTicks;
        return true;
    }

    /** 直接结束循环（调试／维护用） */
    public void clearCycle() {
        cycleRemaining = 0;
    }

    /** 由服务端每 tick 扫同频道门之后写入 */
    public void setInterlockBlocked(boolean value) {
        this.interlockBlocked = value;
    }

    // ------------------------------------------------------------------ 配置

    public void setChannel(int value) {
        this.channel = clampChannel(value);
    }

    public void setCycleTicks(int value) {
        this.cycleTicks = clampCycle(value);
        if (cycleRemaining > cycleTicks) {
            cycleRemaining = cycleTicks;
        }
    }

    public void setInterlock(boolean value) {
        this.interlock = value;
        if (!value) {
            cycleRemaining = 0;
        }
    }

    public void setHeavy(boolean value) {
        this.heavy = value;
        this.stageCooldown = 0;
    }

    public void setStageDelay(int value) {
        this.stageDelay = clampStage(value);
        this.stageCooldown = 0;
    }

    // ------------------------------------------------------------------ 每 tick

    /** 倒计时。返回是否刚好归零（调用方据此刷新提示） */
    public boolean tick() {
        if (cycleRemaining <= 0) {
            return false;
        }
        cycleRemaining--;
        return cycleRemaining == 0;
    }

    /**
     * 推进分级运动。
     *
     * @param wantOpen  目标：true = 开到顶，false = 关到底
     * @param height    门高（行数）
     * @return 本 tick 进度是否变了 —— 变了才需要去改门板方块状态
     */
    public boolean stepMotion(boolean wantOpen, int height) {
        int h = height < 0 ? 0 : height;
        if (progress > h) {
            progress = h;
        }
        int target = wantOpen ? h : 0;
        if (progress == target) {
            stageCooldown = 0;
            return false;
        }
        if (!heavy || stageDelay <= 0) {
            // 普通门：直接到位
            progress = target;
            stageCooldown = 0;
            return true;
        }
        if (++stageCooldown < stageDelay) {
            return false;
        }
        stageCooldown = 0;
        progress += target > progress ? 1 : -1;
        return true;
    }

    /** 立刻把进度设成某个值（建造、拆除、读档后用） */
    public void setProgress(int value) {
        this.progress = value < 0 ? 0 : value;
        this.stageCooldown = 0;
    }

    /**
     * 分级运动时，第 {@code dy} 行当前是否已经"开"了。
     *
     * <h3>方向决定从哪头开始</h3>
     * <ul>
     *   <li>向上收（{@code retractUp}）：门往上滑，所以<b>下面先露出来</b> → dy &lt; progress</li>
     *   <li>向下收：门往下沉，所以<b>上面先露出来</b> → dy &gt;= height - progress</li>
     * </ul>
     * 两头都验算过：progress = height 时两种都全开，progress = 0 时都全关。
     */
    public static boolean rowIsOpen(int dy, int height, int progress, boolean retractUp) {
        if (dy < 0 || dy >= height || progress <= 0) {
            return false;
        }
        if (progress >= height) {
            return true;
        }
        if (retractUp) {
            return dy < progress;
        }
        return dy >= height - progress;
    }

    /** 前 {@code count} 行里，有几行需要改状态（配合 rowIsOpen 逐行刷新） */
    public static boolean[] rowsOpen(int height, int progress, boolean retractUp) {
        int h = height < 0 ? 0 : height;
        boolean[] out = new boolean[h];
        for (int dy = 0; dy < h; dy++) {
            out[dy] = rowIsOpen(dy, h, progress, retractUp);
        }
        return out;
    }

    // ------------------------------------------------------------------ 存档

    public int[] saveState() {
        int[] a = new int[SLOTS];
        a[0] = latchBits();
        a[1] = cycleRemaining;
        a[2] = channel;
        a[3] = cycleTicks;
        int flags = 0;
        if (interlock) {
            flags |= FLAG_INTERLOCK;
        }
        if (heavy) {
            flags |= FLAG_HEAVY;
        }
        a[4] = flags;
        a[5] = progress;
        a[6] = stageDelay;
        return a;
    }

    /** 三个门闩位打包成一个整数（存档 / 网络同步用） */
    public int latchBits() {
        int bits = 0;
        if (manualLatch) {
            bits |= LATCH_BIT_MANUAL;
        }
        if (remoteLatch) {
            bits |= LATCH_BIT_REMOTE;
        }
        if (airlockLatch) {
            bits |= LATCH_BIT_AIRLOCK;
        }
        return bits;
    }

    /**
     * 读档。<b>长度不对就整份丢弃</b>（回默认值），不做"有几个读几个"的部分恢复：
     * 部分恢复出来的状态是"半新半旧"的，比全默认更难排查。
     * <p>
     * 每个字段都夹回合法范围 —— 存档可能是手改的、也可能是旧版本留下的。
     */
    public void loadState(int[] a) {
        if (a == null || a.length < SLOTS) {
            reset();
            return;
        }
        // 只认三个已知位；其它位（手改的存档）一律忽略，不让它们凭空生效
        this.manualLatch = (a[0] & LATCH_BIT_MANUAL) != 0;
        this.remoteLatch = (a[0] & LATCH_BIT_REMOTE) != 0;
        this.airlockLatch = (a[0] & LATCH_BIT_AIRLOCK) != 0;
        this.cycleRemaining = clampCycle(a[1]);
        this.channel = clampChannel(a[2]);
        this.cycleTicks = clampCycle(a[3]);
        this.interlock = (a[4] & FLAG_INTERLOCK) != 0;
        this.heavy = (a[4] & FLAG_HEAVY) != 0;
        this.progress = a[5] < 0 ? 0 : a[5];
        this.stageDelay = clampStage(a[6]);
        this.stageCooldown = 0;
        this.interlockBlocked = false;
        if (!interlock) {
            // 联锁关着的话，残留的循环倒计时没有意义（会显示"气闸循环中"却没有气闸）
            this.cycleRemaining = 0;
        }
    }

    public void reset() {
        this.manualLatch = false;
        this.remoteLatch = false;
        this.airlockLatch = false;
        this.cycleRemaining = 0;
        this.channel = CHANNEL_DEFAULT;
        this.cycleTicks = CYCLE_DEFAULT;
        this.interlock = false;
        this.heavy = false;
        this.stageDelay = STAGE_DEFAULT;
        this.stageCooldown = 0;
        this.progress = 0;
        this.interlockBlocked = false;
    }

    @Override
    public String toString() {
        return "AccessCore{ch=" + channel + ", latch=" + latchBits() + ", cycle=" + cycleRemaining + "/" + cycleTicks
                + ", interlock=" + (interlock ? "on" : "off") + ", blocked=" + interlockBlocked
                + ", heavy=" + (heavy ? "on" : "off") + ", progress=" + progress + "}";
    }

    /** 调试辅助：把状态打成一行，断言失败时能直接看到差在哪 */
    public static String dump(int[] a) {
        return Arrays.toString(a);
    }
}

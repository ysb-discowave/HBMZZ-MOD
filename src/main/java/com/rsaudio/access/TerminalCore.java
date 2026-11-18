package com.rsaudio.access;

/**
 * FAAS 门禁终端的<b>策略内核</b> —— 纯逻辑，<b>不 import 任何 Minecraft 类</b>。
 *
 * <h2>它解决什么问题</h2>
 * 终端不是"一堆门的遥控器"，而是<b>一片区域的规定</b>。这个区别看着小，但它决定了
 * 整个功能对不对：遥控器只在按下去的那一瞬间起作用，区域规定则一直有效。
 * <ul>
 *   <li><b>按了封锁之后才造的门，同样该是封死的</b>。做成"遥控器"的话，玩家在封锁区里
 *       补一面墙、装一扇门，那扇门就是敞开的 —— 封锁形同虚设，而且没人会立刻发现。</li>
 *   <li><b>未封锁时按了一下「封锁」，两下「解除」</b>，门的状态必须是"回到没被终端管过"，
 *       而不是"多按一下反而锁上"。所以终端要记住自己想让区域处在哪个状态，
 *       而不是每次按下都翻转一遍每扇门。</li>
 * </ul>
 * 所以这里的模型是：终端持有一个<b>区域封锁状态</b>（{@link #isLocked()}，会持久化），
 * 然后不断把这个状态"校准"到频道里的每一扇门上（{@link #reconcile}）。
 *
 * <h2>★ 校准只动"远程"那一位</h2>
 * 门上的门闩是两个独立的位（手动 / 远程，见 {@link AccessCore}）。终端只负责<b>远程</b>那一位：
 * <ul>
 *   <li>要封锁时：远程位没亮就点亮它（手动位亮不亮都不管）；</li>
 *   <li>要解除时：<b>只熄灭远程位</b> —— 玩家自己在门上拉的封锁必须原样留着。</li>
 * </ul>
 * 这条规则如果写错成"解除 = 清掉所有门闩"，就会出现"FAAS 解除封锁顺手把玩家的隔离锁也打开了"。
 * 这是个安全方向上的错误，所以 {@link #reconcile} 只接受门闩位、只回答"该点亮 / 熄灭 / 别动"，
 * 把"绝不碰手动位"写死在内核里，由 {@code tools/check_terminal_core.py} 钉住。
 *
 * <h2>★ 为什么要"定期校准"而不是"只在按键时发一次"</h2>
 * 只在按键时发一次的话，上面说的"新造的门"就漏了。反过来，如果每 tick 都无脑刷一遍
 * 频道里所有门，每次都会触发 {@code markDirty} + 方块更新（几十扇门一起刷是实打实的开销）。
 * 所以：
 * <ul>
 *   <li>状态<b>变化</b>时立刻校准一次（{@link #setLocked} 返回 true，调用方马上跑一轮）；</li>
 *   <li>此外每 {@link #REASSERT_INTERVAL} 刻做一次兜底校准（{@link #tick()} 返回 true）。</li>
 * </ul>
 * 兜底校准也<b>不是</b>无脑刷：{@link #reconcile} 对"已经对了"的门返回 {@code NOTHING}，
 * 调用方就不会去碰它。所以定期校准的代价只是遍历一遍方块实体列表，不产生方块更新。
 *
 * <h2>什么时候会播报</h2>
 * 只有区域状态<b>真的翻转</b>时才播（{@link #setLocked} 的返回值），
 * 而不是每次兜底校准都播 —— 否则每 20 刻循环一次的大喇叭能把人吵疯。
 */
public final class TerminalCore {

    // ------------------------------------------------------------------ 常量

    /** 终端必须挂在某个频道上，所以这里的下限是 1（和门不一样，门上的 0 表示"不参与门禁"） */
    public static final int CHANNEL_MIN = 1;
    public static final int CHANNEL_MAX = 16;
    public static final int CHANNEL_DEFAULT = 1;

    /** 兜底校准间隔（刻）。20 刻 = 1 秒 */
    public static final int REASSERT_INTERVAL = 20;

    /** 校准结果：这扇门已经是对的，别碰它 */
    public static final int RECONCILE_NOTHING = 0;
    /** 校准结果：点亮它的远程门闩 */
    public static final int RECONCILE_LOCK = 1;
    /** 校准结果：熄灭它的远程门闩（手动那份留着） */
    public static final int RECONCILE_UNLOCK = 2;

    public static final int FLAG_LOCKED = 1;
    public static final int FLAG_INVERT = 2;
    public static final int FLAG_ANNOUNCE = 4;

    /** 存档槽数：flags / channel */
    public static final int SLOTS = 2;

    // ------------------------------------------------------------------ 字段

    /** 区域封锁状态：终端想让这个频道处于锁定还是放行 */
    private boolean locked;
    /** 红石反向：有信号时反而"解除封锁" */
    private boolean invert;
    /** 状态翻转时是否播报 */
    private boolean announce = true;
    /** 终端管的频道，恒在 1..16 */
    private int channel = CHANNEL_DEFAULT;

    /** 兜底校准的节拍累加器 */
    private int reassertCooldown;

    // ---- 统计（不持久化：都是"扫一遍算出来的"，存进存档只会得到一份过期的数）----

    /** 频道里已建造的门有几扇 */
    private int doorCount;
    /** 其中远程门闩亮着的有几扇 */
    private int lockedCount;
    /** 上一次校准实际改了几扇门 */
    private int lastAffected;

    // ------------------------------------------------------------------ 夹取

    /**
     * 频道夹取。★ 和 {@link AccessCore#clampChannel} <b>不一样</b>：
     * 那里 0 是合法的"不受门禁管理"，这里 0 是<b>无效值</b>，一律落到 1。
     * 终端说"我管 0 号频道"是没有意义的 —— 门根本不会响应 0 号频道。
     */
    public static int clampChannel(int value) {
        if (value < CHANNEL_MIN) {
            return CHANNEL_MIN;
        }
        return value > CHANNEL_MAX ? CHANNEL_MAX : value;
    }

    /**
     * 把频道在 1..16 之间循环，供界面的 ◀ / ▶ 用。
     * <p>
     * 注意这里是 16 格一圈（门的 {@code stepChannel} 是 17 格，因为门多一个 0 号"无门禁"）。
     * 从 16 再往前按回到 1、从 1 再往回按到 16，是连续的一圈。
     */
    public static int stepChannel(int value, int delta) {
        int v = clampChannel(value);
        int size = CHANNEL_MAX - CHANNEL_MIN + 1;
        int idx = (v - CHANNEL_MIN + delta) % size;
        if (idx < 0) {
            idx += size;
        }
        return idx + CHANNEL_MIN;
    }

    // ------------------------------------------------------------------ 读取

    public int getChannel() {
        return channel;
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isInvert() {
        return invert;
    }

    public boolean isAnnounce() {
        return announce;
    }

    public int getDoorCount() {
        return doorCount;
    }

    public int getLockedCount() {
        return lockedCount;
    }

    public int getLastAffected() {
        return lastAffected;
    }

    /**
     * 区域状态是不是已经全部落到门上了。
     * <p>
     * 要封锁时看"是不是全锁上了"，要放行时看"远程锁是不是都撤干净了" ——
     * 两个方向都得看，否则"放行之后还剩一半锁着"会被误报成正常。
     */
    public boolean isSettled() {
        return doorCount > 0 && lockedCount == (locked ? doorCount : 0);
    }

    // ------------------------------------------------------------------ 配置

    public void setChannel(int value) {
        this.channel = clampChannel(value);
    }

    public void setInvert(boolean value) {
        this.invert = value;
    }

    public void setAnnounce(boolean value) {
        this.announce = value;
    }

    /**
     * 设置区域封锁状态。
     *
     * @return <b>状态真的变了</b>才返回 true —— 调用方据此决定要不要立刻校准一次、
     *         要不要播报。重复按同一个方向（比如连按两次「封锁」）不会返回 true，
     *         也就不会有第二次播报。
     */
    public boolean setLocked(boolean value) {
        if (this.locked == value) {
            return false;
        }
        this.locked = value;
        return true;
    }

    /** 翻转区域锁定（界面上的「封锁 / 解除」一颗按钮） */
    public boolean toggleLocked() {
        return setLocked(!this.locked);
    }

    /**
     * 红石电平映射到"区域该不该封锁"。
     * <p>
     * 默认（不反向）：来信号 = 封锁 —— 这才是事故场景该有的默认方向
     * （反应堆出事时红石掉下去才是异常，这里反过来：出事时给出信号 = 封锁）。
     * 反向之后：来信号 = 解除封锁。
     */
    public boolean targetFromSignal(boolean powered) {
        return invert ? !powered : powered;
    }

    // ------------------------------------------------------------------ 校准

    /**
     * ★ 内核的核心：给一扇门当前的门闩位，回答"该动它吗、怎么动"。
     *
     * @param latchBits 门的门闩位（{@link AccessCore#latchBits()}）。高位的未知位一律忽略
     * @return {@link #RECONCILE_NOTHING} / {@link #RECONCILE_LOCK} / {@link #RECONCILE_UNLOCK}
     */
    public int reconcile(int latchBits) {
        return reconcile(latchBits, false);
    }

    /**
     * 同上，但可以要求<b>只收紧</b>。
     *
     * <h3>★ 为什么兜底校准必须"只收紧"</h3>
     * 兜底校准每 {@link #REASSERT_INTERVAL} 刻跑一次，它的职责只有一件事 ——
     * <b>补上封锁之后才造出来的门</b>。而"区域放行"时新建的门本来就该是能通的，
     * 兜底校准对它无事可做。所以放行方向根本不需要兜底。
     * <p>
     * 反过来说，如果兜底校准两个方向都做，就会引出一个很难查的现象：
     * <b>同一个频道上放两台终端、一台封锁一台放行，门会每 20 刻来回换一次状态</b> ——
     * 两台终端的兜底周期互相把对方的结果推翻。谁都没有错，但门在抖。
     * 限制成"兜底只收紧"之后，两台终端的结果是<b>确定的</b>（封锁胜出），
     * 而放行变成一件只有人按下按钮 / 来红石信号才会发生的、明确的事。
     * <p>
     * 这和 {@link AccessCore#canClose()} 恒为 true 是同一个思路：
     * <b>安全阀只朝一个方向使劲</b>。自动的东西负责"变严"，"变松"必须有人明确要求。
     *
     * @param onlyTighten true 时：区域处于放行状态就什么也不做
     */
    public int reconcile(int latchBits, boolean onlyTighten) {
        boolean remote = (latchBits & AccessCore.LATCH_BIT_REMOTE) != 0;
        if (locked) {
            // 要封锁：远程位没亮才去点。手动位亮不亮都无视 —— 那是别人的意见
            return remote ? RECONCILE_NOTHING : RECONCILE_LOCK;
        }
        if (onlyTighten) {
            return RECONCILE_NOTHING;
        }
        // 要放行：只熄远程位。手动位留着，玩家自己上的锁不该被终端解开
        return remote ? RECONCILE_UNLOCK : RECONCILE_NOTHING;
    }

    /** 便捷版：这扇门需要动吗 */
    public boolean needsReconcile(int latchBits) {
        return reconcile(latchBits) != RECONCILE_NOTHING;
    }

    /**
     * 兜底校准节拍。每 {@link #REASSERT_INTERVAL} 刻返回一次 true。
     * <p>
     * 状态刚变化时不用等它 —— 那条路径由 {@link #setLocked} 的返回值直接触发。
     */
    public boolean tick() {
        if (++reassertCooldown < REASSERT_INTERVAL) {
            return false;
        }
        reassertCooldown = 0;
        return true;
    }

    /** 立刻把节拍器归零（刚做过一次完整校准，不必马上再来一次） */
    public void resetCadence() {
        reassertCooldown = 0;
    }

    // ------------------------------------------------------------------ 统计

    /** 扫描模式：只收紧（兜底校准）。见 {@link #reconcile(int, boolean)} */
    private boolean scanOnlyTighten;

    /** 一次完整校准扫描开始前调用（两个方向都做） */
    public void beginScan() {
        beginScan(false);
    }

    /**
     * 一次校准扫描开始前调用。
     *
     * @param onlyTighten 兜底校准传 true —— 只补锁、不撤锁。
     *                    理由见 {@link #reconcile(int, boolean)}
     */
    public void beginScan(boolean onlyTighten) {
        this.scanOnlyTighten = onlyTighten;
        this.doorCount = 0;
        this.lockedCount = 0;
        this.lastAffected = 0;
    }

    /**
     * 扫到一扇<b>已建造的、频道匹配的</b>门时调用，同时把校准结果累加进来。
     *
     * @return 这扇门该怎么动（{@link #reconcile} 的结果原样返回）
     */
    public int acceptDoor(int latchBits) {
        int action = reconcile(latchBits, scanOnlyTighten);
        doorCount++;
        // ★ 这里数的是"<b>现在</b>远程位亮着的有几扇"，不是"校准完会有几扇" ——
        //   界面要显示的是现状（"3 / 5 已锁定"），扫完就变成 5/5 的话玩家看不到过程
        if ((latchBits & AccessCore.LATCH_BIT_REMOTE) != 0) {
            lockedCount++;
        }
        if (action != RECONCILE_NOTHING) {
            lastAffected++;
        }
        return action;
    }

    /**
     * 扫描结束、门已经被真的改过之后调用，用"实际动了几扇"覆盖掉预测值。
     * <p>
     * 预测值和实际值可能不一致：门可能正好在这一刻没建成、或者已经不存在了。
     * 界面/播报要报的是实际发生的事。
     */
    public void setLastAffected(int value) {
        this.lastAffected = value < 0 ? 0 : value;
    }

    /**
     * 直接写入统计量（<b>只给客户端显示用</b>）。
     * <p>
     * 服务端通过方块更新包报过来的计数是<b>权威</b>的：客户端手里的方块实体列表语义
     * 和服务端不一样（客户端没有"这一格的门建好了没有"这种服务端状态），
     * 自己扫一遍只会得到另一组数。所以这条路必须能<b>直接</b>写入，
     * 而不是靠客户端"模拟一次扫描"。
     * <p>
     * 每个值都夹回自洽范围 —— 网络包不是绝对可信的输入。
     */
    public void setCounts(int doors, int locked, int affected) {
        this.doorCount = doors < 0 ? 0 : doors;
        int l = locked < 0 ? 0 : locked;
        this.lockedCount = l > this.doorCount ? this.doorCount : l;
        this.lastAffected = affected < 0 ? 0 : affected;
    }

    // ------------------------------------------------------------------ 存档

    public int[] saveState() {
        int[] a = new int[SLOTS];
        int flags = 0;
        if (locked) {
            flags |= FLAG_LOCKED;
        }
        if (invert) {
            flags |= FLAG_INVERT;
        }
        if (announce) {
            flags |= FLAG_ANNOUNCE;
        }
        a[0] = flags;
        a[1] = channel;
        return a;
    }

    /**
     * 读档。长度不对就整份丢弃（回默认值）；未知标志位一律忽略。
     * <p>
     * ★ 这里刻意<b>不</b>像 {@code AccessCore} 那样"没有就 reset" —— 默认值里
     * {@code announce} 是开的，重置一份存档会让终端悄悄变成静音，所以默认值要显式给全。
     */
    public void loadState(int[] a) {
        if (a == null || a.length < SLOTS) {
            reset();
            return;
        }
        this.locked = (a[0] & FLAG_LOCKED) != 0;
        this.invert = (a[0] & FLAG_INVERT) != 0;
        this.announce = (a[0] & FLAG_ANNOUNCE) != 0;
        this.channel = clampChannel(a[1]);
        this.reassertCooldown = 0;
        this.scanOnlyTighten = false;
        this.doorCount = 0;
        this.lockedCount = 0;
        this.lastAffected = 0;
    }

    public void reset() {
        this.locked = false;
        this.invert = false;
        this.announce = true;
        this.channel = CHANNEL_DEFAULT;
        this.reassertCooldown = 0;
        this.scanOnlyTighten = false;
        this.doorCount = 0;
        this.lockedCount = 0;
        this.lastAffected = 0;
    }

    @Override
    public String toString() {
        return "TerminalCore{ch=" + channel + ", " + (locked ? "LOCKED" : "RELEASED")
                + (invert ? ", invert" : "") + (announce ? ", announce" : ", silent")
                + ", doors=" + lockedCount + "/" + doorCount + "}";
    }
}

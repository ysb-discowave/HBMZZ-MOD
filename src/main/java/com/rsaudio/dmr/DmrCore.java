package com.rsaudio.dmr;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 暗物质反应堆的状态机与数值模拟（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 这个类只做一件事：<b>每 tick 推演一次反应堆</b>。它不碰世界、不碰方块、不碰网络，
 * 所有对外影响都通过 {@link #drainEvents()} 交出去的事件键表达，
 * 由 MC 层翻译成广播、音效、爆炸和字幕。
 * <p>
 * 这么切是为了三件事：
 * <ol>
 *   <li>能在 {@code tools/check_dmr_core.py} 里被真 javac 编译、真跑边界用例，
 *       而不是靠"进游戏点一下看看"来验证；</li>
 *   <li>服务端权威 —— 同一份模拟跑在服务端，客户端只收同步包渲染；</li>
 *   <li>数值稳定 —— 这些 double 会在一个长期运行的服务器上被跑上百万 tick，
 *       必须有测试兜住 NaN、负温度、越界这些事。</li>
 * </ol>
 *
 * <h3>三条失控路径</h3>
 * 正常运行是"负荷 1.0 + 热管理全开 → 稳态 900K"。危险来自三个互不相同的地方：
 * <ol>
 *   <li><b>热管理系统失效</b>（玩家关掉、或辅助电网掉了）→ 同一负荷稳态冲到约 2600K
 *       → 越过 2300K 之后完整性开始掉，并且燃料<b>反向上涨</b>
 *       → 燃料越足催化器越猛 → 温度更高 → 正反馈到熔毁；</li>
 *   <li><b>燃料耗尽</b> → 催化器效率掉到 0 → 供热中断 → 无法自持 → 失速；</li>
 *   <li><b>过冷</b>：热管理系统常开而负荷归零 → 堆芯被主动冷却一路压到 150K 以下 → 失速。
 *       冷却系统能把堆芯压得比环境温度还低，所以"把负荷调零晾着"不是安全的待机姿势。</li>
 * </ol>
 *
 * <h3>线程</h3>
 * 非线程安全。约定只在服务端主线程 tick，同步包只读快照。
 */
public final class DmrCore {

    // ---------------------------------------------------------------- 构造

    private final DmrSettings cfg;
    private final Random rng;

    // ------------------------------------------------------------ 运行状态

    private int state = DmrSpec.ST_OFFLINE;
    private int sub = 0;

    private double tempK;
    private final double[] cells = new double[DmrSpec.FUEL_CELL_COUNT];
    /** 维护模式下已经换上去的新燃料单元数。要凑满 {@link DmrSpec#FUEL_CELL_COUNT} 才能重启 */
    private int cellsReady;
    private double integrity = 100.0;
    /** 玩家设定的输出负荷（额定百分比） */
    private int loadPct;
    private double eff = 1.0;

    /**
     * EFSS 水位 (0..100)。停机前置条件之一：不足 {@link DmrSpec#EFSS_SHUTDOWN_MIN} 就按不下去。
     * 消耗/补充的规则见 {@link DmrSettings#efssDrainPerTick}。
     */
    private double efss = DmrSpec.EFSS_MAX;
    /** 还在工作的功率激光根数（0..{@link DmrSpec#LASER_COUNT}）。停机要求"一根都不能少" */
    private int lasers = DmrSpec.LASER_COUNT;
    /**
     * Phase 2 的物理停机通道：已经拔下来几块燃料单元。
     * 照 QSERF，要<b>三个人同时各拔一块</b>；同一个人拔第二块会让整条通道锁死。
     */
    private int cellPulled;
    /** 每一块燃料单元是谁拔的（玩家 id）。用来判"同一个人拔了两块" */
    private final int[] pullers = new int[DmrSpec.FUEL_CELL_COUNT];
    /** 换料通道是否已经锁死（有人拔了第二块）。锁死之后只剩内爆 */
    private boolean cellChannelLocked;
    private int efssWarned;
    private int laserWarned;

    private int ignitionTimer;
    private int coldTimer;
    private int sustainLost;
    private int fuelWarned;
    private int monitorFailed;
    private int integrityWarned;
    /** 当前所处的温度带（0 正常 / 1 安全告警 / 2 警告），用来只在跨带时播报一次 */
    private int tempBand;
    /** 告警面板的最高优先级告警 */
    private int alarm = DmrSpec.AL_NONE;

    private int phase;
    private int phaseTimer;
    private int shutdownCode;
    private String implodeReason = "";

    private long ticks;
    private int explosionPending;

    /** 功率订单 */
    private int orderTargetPct;
    private int orderTimer;
    private int orderGoodTicks;
    private int orderIdle;
    private boolean orderActive;
    private boolean orderExtreme;
    private int ordersDone;
    private int ordersFailed;
    private int ordersTotal;

    private final List<String> events = new ArrayList<String>(8);

    /**
     * @param settings 物理参数。内部会 {@link DmrSettings#copy()} 一份，
     *                 免得调用方之后改配置把正在跑的堆改了
     */
    public DmrCore(DmrSettings settings) {
        this.cfg = settings.copy();
        this.rng = new Random(this.cfg.seed);
        this.tempK = this.cfg.ambientK;
        for (int i = 0; i < cells.length; i++) {
            cells[i] = 100.0;
        }
        for (int i = 0; i < pullers.length; i++) {
            pullers[i] = -1;
        }
    }

    // -------------------------------------------------------------- 主循环

    /** 推演一 tick */
    public void tick() {
        ticks++;
        orderTick();
        switch (state) {
            case DmrSpec.ST_STARTUP:
                tickStartup();
                break;
            case DmrSpec.ST_ONLINE:
                tickOnline();
                break;
            case DmrSpec.ST_MELTDOWN_P1:
            case DmrSpec.ST_MELTDOWN_P2:
                tickMeltdown();
                break;
            case DmrSpec.ST_MAINTENANCE:
            case DmrSpec.ST_STALLED:
                tickStandby();
                break;
            case DmrSpec.ST_OFFLINE:
            case DmrSpec.ST_SAFE:
                tickIdle();
                break;
            default:
                // ST_IMPLODED：终态，什么都不再发生
                break;
        }
        updateAlarm();
    }

    private void tickIdle() {
        // 停机状态：只有自然散热，往环境温度收敛
        tempK -= cfg.naturalCool * (tempK - cfg.ambientK);
        if (Math.abs(tempK - cfg.ambientK) < 0.5) {
            tempK = cfg.ambientK;
        }
        // 停机期泵站会把消防水位补满
        updateEfss();
    }

    private void tickStandby() {
        // 维护 / 失速：堆停着，热管理如果还开着就继续带走余温
        applyThermal(0.0);
        updateEfss();
    }

    private void tickStartup() {
        ignitionTimer--;
        if (ignitionTimer <= 0) {
            state = DmrSpec.ST_ONLINE;
            coldTimer = 0;
            sustainLost = 0;
            emit(DmrSpec.EV_ONLINE);
        }
    }

    private void tickOnline() {
        eff = catalyzerEfficiency();
        double load = loadPct / 100.0;
        applyThermal(cfg.heatPerTickPerLoad * load * eff);
        updateFuel(load * eff);
        updateIntegrity();
        updateEfss();
        updateLasers();
        if (state != DmrSpec.ST_ONLINE) {
            return;
        }
        checkFuelState();
        checkColdState();
        if (state != DmrSpec.ST_ONLINE) {
            return;
        }
        if (integrity <= DmrSpec.INTEGRITY_MELTDOWN) {
            startMeltdown();
        }
    }

    private void tickMeltdown() {
        // 失控之后温度不再听使唤：催化器满负荷烧，燃料照涨，完整性边掉边等停机码
        eff = catalyzerEfficiency();
        double load = Math.max(loadPct, 100) / 100.0;
        applyThermal(cfg.heatPerTickPerLoad * load * eff * cfg.runawayHeatMult);
        addFuel(cfg.fuelGainPerTick);
        integrity -= cfg.integrityDropHigh;
        if (integrity < 0.0) {
            integrity = 0.0;
        }
        updateEfss();
        updateLasers();

        phaseTimer--;
        if (phaseTimer > 0) {
            return;
        }
        if (phase == 1) {
            phase = 2;
            // state 必须跟着一起走：phase 只给"该用哪套规则"用，
            // state 才是对外（GUI / 告警 / 别的系统）看到的那个。漏了这一行，
            // 状态机会永远停在 MELTDOWN_P1，第二阶段的热管理前置条件就形同虚设。
            state = DmrSpec.ST_MELTDOWN_P2;
            phaseTimer = cfg.phase2Ticks;
            emit(DmrSpec.EV_PHASE2);
        } else {
            implode("timeout");
        }
    }

    // ---------------------------------------------------------------- 物理

    /**
     * 供热与散热的净效果。
     * <p>
     * 高于环境温度时，散热正比于温差；低于环境温度时，只有热管理系统能继续压 ——
     * 它是主动冷却，能把堆芯拉到 {@link DmrSettings#coolFloorK}（默认 80K）。
     * 这条分支就是"过冷失速"的物理来源。
     */
    private void applyThermal(double heatIn) {
        boolean thermal = hasSub(DmrSpec.SUB_THERMAL_SYSTEMS);
        tempK += heatIn;
        if (thermal && tempK <= cfg.ambientK) {
            tempK -= cfg.forcedCool * (tempK - cfg.coolFloorK);
        } else {
            double k = cfg.naturalCool + (thermal ? cfg.forcedCool : 0.0);
            tempK -= k * (tempK - cfg.ambientK);
        }
        if (tempK < 0.0) {
            // 物理上不可能，但数值上必须兜住：负温度会让后面的除法把整台机器变成 NaN
            tempK = 0.0;
        }
    }

    /**
     * 催化器效率。燃料越少供热越弱，燃料超 100% 反而更强 —— 后面这一条是正反馈的燃料。
     */
    private double catalyzerEfficiency() {
        double avg = avgFuel();
        if (avg <= 0.0) {
            return 0.0;
        }
        double e;
        if (avg < DmrSpec.FUEL_LOW_WARN) {
            e = cfg.lowFuelEffFloor
                    + (1.0 - cfg.lowFuelEffFloor) * (avg / DmrSpec.FUEL_LOW_WARN);
        } else {
            e = 1.0;
        }
        if (avg > 100.0) {
            e += Math.min(cfg.overFuelBoostMax, (avg - 100.0) * cfg.overFuelBoostPerPct);
        }
        return e;
    }

    private void updateFuel(double loadEff) {
        if (tempK > DmrSpec.TEMP_WARN_LIMIT) {
            addFuel(cfg.fuelGainPerTick);
            if (avgFuel() > 100.0 && monitorFailed == 0) {
                monitorFailed = 1;
                emit(DmrSpec.EV_FUEL_MONITOR_FAIL);
            }
        } else {
            addFuel(-cfg.fuelBurnPerTick * loadEff);
        }
    }

    private void updateIntegrity() {
        double drop = 0.0;
        if (tempK > DmrSpec.TEMP_RUNAWAY) {
            double ramp = Math.min(1.0, (tempK - DmrSpec.TEMP_RUNAWAY) / cfg.integrityRampK);
            drop = cfg.integrityDropHigh
                    + (cfg.integrityDropHighMax - cfg.integrityDropHigh) * ramp;
        } else if (tempK > DmrSpec.TEMP_WARN_LIMIT) {
            drop = cfg.integrityDropLow
                    * (tempK - DmrSpec.TEMP_WARN_LIMIT)
                    / (DmrSpec.TEMP_RUNAWAY - DmrSpec.TEMP_WARN_LIMIT);
        }
        if (drop <= 0.0) {
            return;
        }
        integrity -= drop;
        if (integrity < 0.0) {
            integrity = 0.0;
        }
        if (integrityWarned == 0) {
            integrityWarned = 1;
            emit(DmrSpec.EV_INTEGRITY_DROP);
        }
    }

    /**
     * 维护 EFSS 水位：<b>只有热管理在跑、并且堆芯已经越过安全线</b>时才放水；
     * 反过来，堆芯凉着、热管理关着的时候泵站补水。
     * <p>
     * 这条规则把"降温"和"保住水位"变成了一对必须同时考虑的事 ——
     * 也正是 QSERF 里那条 "EFSS must be above 70% full" 前置条件产生张力的地方。
     */
    private void updateEfss() {
        boolean thermal = hasSub(DmrSpec.SUB_THERMAL_SYSTEMS);
        if (thermal && tempK > DmrSpec.TEMP_SAFE_LIMIT) {
            setEfss(efss - cfg.efssDrainPerTick);
        } else if (!thermal && tempK < DmrSpec.TEMP_SAFE_LIMIT) {
            setEfss(efss + cfg.efssRefillPerTick);
        }
        if (efss <= DmrSpec.EFSS_WARN) {
            if (efssWarned == 0) {
                efssWarned = 1;
                emit(DmrSpec.EV_EFSS_LOW);
            }
        } else if (efss > DmrSpec.EFSS_WARN + 5.0) {
            // 补回来之后重新武装预警，免得一次低水位之后再也不报
            efssWarned = 0;
        }
    }

    private void setEfss(double value) {
        if (value < 0.0) {
            value = 0.0;
        } else if (value > DmrSpec.EFSS_MAX) {
            value = DmrSpec.EFSS_MAX;
        }
        efss = value;
    }

    /**
     * 功率激光烧毁。只在越过失控线之后发生 —— 照 QSERF，这是"吊车塌下来砸到激光"，
     * 随机、不可预防。停机要求所有激光都在工作，所以坏掉的必须去硬重启。
     */
    private void updateLasers() {
        if (lasers <= 0) {
            return;
        }
        if (tempK <= DmrSpec.TEMP_RUNAWAY) {
            return;
        }
        if (rng.nextDouble() < cfg.laserLossChance) {
            lasers--;
            laserWarned = 1;
            emit(DmrSpec.EV_LASER_LOST);
        }
    }

    private void checkFuelState() {
        double avg = avgFuel();
        if (avg <= DmrSpec.FUEL_LOW_WARN && fuelWarned == 0) {
            fuelWarned = 1;
            emit(DmrSpec.EV_FUEL_LOW);
        }
        if (avg <= 0.0) {
            stall("fuel");
        }
    }

    /**
     * 过冷检查。低于 230K 播报可持续性丧失；累计低于 275K 太久、或一步掉到 150K 以下，
     * 反应就彻底打不着了。
     *
     * <p>
     * ★ 这条判据是<b>与负载无关</b>的，而且这是有意的 —— 测试 {@code t6_overcoolStall}
     * 钉住了"负荷归零 + 热管理常开 → 过冷失速"这条死亡路径（路径三）。
     * 后果要说清楚：热管理系统是主动冷却，开着它点火，空载堆芯会在
     * <b>11.2 秒</b>内从 293K 被压过 230K，于是聊天栏弹一条"反应可持续性正在丧失（堆芯过冷）"，
     * 再撑约 90 秒就直接失速。这是设计内的一条死法，物理不动。
     * 玩家觉得它"莫名其妙"是因为它其实是一条<b>状态</b>而不是一次事件 ——
     * 不让它刷屏是 {@link TileDmrController} 的职责（见那边的 {@code QUIET_EVENTS}）。
     */
    private void checkColdState() {
        if (tempK < DmrSpec.TEMP_STALL_WARN && sustainLost == 0) {
            sustainLost = 1;
            emit(DmrSpec.EV_SUSTAIN_LOSS);
        }
        if (tempK < DmrSpec.TEMP_STALL_EDGE) {
            coldTimer++;
        } else if (tempK > DmrSpec.TEMP_STALL_EDGE + 20.0) {
            coldTimer = 0;
            sustainLost = 0;
        }
        if (tempK < DmrSpec.TEMP_CATALYZER_DEATH || coldTimer > cfg.stallTicks) {
            stall("cold");
        }
    }

    // ------------------------------------------------------------ 状态迁移

    private void stall(String reason) {
        state = DmrSpec.ST_STALLED;
        loadPct = 0;
        coldTimer = 0;
        emit(DmrSpec.EV_STALL);
    }

    private void startMeltdown() {
        state = DmrSpec.ST_MELTDOWN_P1;
        phase = 1;
        phaseTimer = cfg.phase1Ticks;
        shutdownCode = DmrSpec.SHUTDOWN_CODE_MIN
                + rng.nextInt(DmrSpec.SHUTDOWN_CODE_MAX - DmrSpec.SHUTDOWN_CODE_MIN + 1);
        // Phase 2 的换料通道从零开始：谁都没拔过、也没锁死。
        // 注意这里只清换料状态，不动激光和水位 —— 那两样是这场事故本身留下的伤，
        // 是要玩家去处理的，不是每次熔毁自动满血复活。
        cellPulled = 0;
        cellChannelLocked = false;
        for (int i = 0; i < pullers.length; i++) {
            pullers[i] = -1;
        }
        emit(DmrSpec.EV_MELTDOWN);
    }

    private void implode(String reason) {
        state = DmrSpec.ST_IMPLODED;
        implodeReason = reason;
        loadPct = 0;
        explosionPending = 1;
        emit(DmrSpec.EV_IMPLODE);
    }

    // -------------------------------------------------------------- 玩家操作

    /** 切换一个子系统的开关。返回切换之后该位是否为 1 */
    public boolean toggleSubsystem(int bit) {
        if ((bit & DmrSpec.SUB_ALL) == 0) {
            return false;
        }
        if (DmrSpec.isTerminal(state) || DmrSpec.isMeltdown(state)) {
            // 熔毁之后能给玩家动的只剩三样：热管理系统（降温）、两把钥匙（应急停机授权）。
            // 别的一律锁死 —— 那时候还让人去开关辅助电网，只会让人误以为有救。
            if (bit != DmrSpec.SUB_THERMAL_SYSTEMS
                    && bit != DmrSpec.SUB_KEY
                    && bit != DmrSpec.SUB_KEY2) {
                return hasSub(bit);
            }
        }
        sub ^= bit;
        return hasSub(bit);
    }

    /** 直接置位/清位一个子系统 */
    public void setSubsystem(int bit, boolean on) {
        if (on) {
            sub |= bit;
        } else {
            sub &= ~bit;
        }
    }

    /** 设定输出负荷（额定百分比），自动钳到合法区间 */
    public void setLoadPct(int pct) {
        if (pct < DmrSpec.LOAD_MIN_PCT) {
            pct = DmrSpec.LOAD_MIN_PCT;
        }
        if (pct > DmrSpec.LOAD_MAX_PCT) {
            pct = DmrSpec.LOAD_MAX_PCT;
        }
        loadPct = pct;
    }

    /** 当前实际输出功率 (kW) */
    public int outputKw() {
        if (state != DmrSpec.ST_ONLINE) {
            return 0;
        }
        return (int) Math.round(DmrSpec.RATED_KW * (loadPct / 100.0));
    }

    /**
     * 按主点火按钮。
     *
     * @return 是否真的点着了。失败时 {@link #drainEvents()} 里会有对应的拒绝原因
     */
    public boolean pressIgnition() {
        if (state != DmrSpec.ST_OFFLINE) {
            return false;
        }
        if (!subsystemsReady()) {
            emit(DmrSpec.EV_IGNITION_DENIED_SUBSYS);
            return false;
        }
        if (avgFuel() <= 0.0) {
            emit(DmrSpec.EV_IGNITION_DENIED_FUEL);
            return false;
        }
        state = DmrSpec.ST_STARTUP;
        ignitionTimer = cfg.ignitionTicks;
        integrityWarned = 0;
        fuelWarned = 0;
        monitorFailed = 0;
        tempBand = 0;
        orderIdle = 0;
        resetSafetyHardware();
        emit(DmrSpec.EV_IGNITION_START);
        return true;
    }

    /**
     * 点火需要的子系统是不是全开了。
     * <p>
     * ★★ 抽出来的唯一理由是<b>让判据只有一份</b>：{@link #pressIgnition()} 用它决定
     * 拒不拒绝，控制器也用同一句去决定"能不能对玩家说『可以点火了』"。
     * <p>
     * ★★★ 这一条是踩出来的。控制器原来自己写了一句近似判据
     * （只看 {@code state == ST_OFFLINE}），于是子系统没开齐时聊天栏刚喊完
     * "多方块结构已成立，可以点火了"，玩家一点就收到"点火被拒：……必须全部就位" ——
     * 两次实测间隔 4 秒和 1 秒（2026-09-21 21:52:02→21:52:06、22:00:16→22:00:17）。
     * <b>同一件事的前置条件写在两处、只写对一处，是这类"广播说谎"的唯一成因。</b>
     */
    public boolean subsystemsReady() {
        return (sub & DmrSpec.SUB_ALL_REQUIRED) == DmrSpec.SUB_ALL_REQUIRED;
    }

    /**
     * 现在按点火按钮真的会点着吗。
     * <p>
     * ★★★ 这里的条件必须与 {@link #pressIgnition()} 的<b>全部</b>前置条件一一对应。
     * 以后往 pressIgnition 里加条件，<b>必须同时加到这里</b>，否则玩家又会看到
     * "说可以、点了被拒"。控制器那边的"可以点火了"就是读这个方法。
     */
    public boolean canIgnite() {
        return state == DmrSpec.ST_OFFLINE && subsystemsReady() && avgFuel() > 0.0;
    }

    /**
     * 现在为什么点不着（短中文，只进日志/GUI 调试用；玩家可见的那句在控制器层）。
     * <p>
     * ★ 留着它是为了下一次"为什么没收到可以点火"时不用再猜 —— 日志里会直接写出来。
     */
    public String ignitionBlockReason() {
        if (state != DmrSpec.ST_OFFLINE) {
            return "状态不是未点火（state=" + state + "）";
        }
        if (!subsystemsReady()) {
            return "子系统没全开（当前 0x" + Integer.toHexString(sub)
                    + "，需要 0x" + Integer.toHexString(DmrSpec.SUB_ALL_REQUIRED) + "）";
        }
        if (avgFuel() <= 0.0) {
            return "燃料单元是空的（avgFuel=" + avgFuel() + "）";
        }
        return "可以点火";
    }

    /**
     * 点火/维护重启时把"安全硬件"恢复到出厂状态：激光全好、水位补满、换料通道解锁。
     * <p>
     * 放在这里而不是构造函数里，是因为它表达的是"这次检修把上一轮事故的伤修好了" ——
     * 失速之后走维护重启，是玩家<b>唯一</b>能把烧掉的激光和放干的水位一次性修回来的机会。
     * 熔毁后的 P2 换料锁死则<b>不</b>受此影响（那时已经不在 ONLINE 了，谁也点不了火）。
     */
    private void resetSafetyHardware() {
        lasers = DmrSpec.LASER_COUNT;
        laserWarned = 0;
        setEfss(DmrSpec.EFSS_MAX);
        efssWarned = 0;
        cellPulled = 0;
        cellChannelLocked = false;
        for (int i = 0; i < pullers.length; i++) {
            pullers[i] = -1;
        }
    }

    /**
     * 正常停堆（不是熔毁应急停机）。回到未点火状态，要重新走一遍点火序列。
     *
     * <h3>★ 为什么这里必须有前置条件</h3>
     * 这一条以前只判 {@code state == ST_ONLINE} 就放行 —— 于是反应堆只要在运行，
     * <b>任何时候、任何读数下都能一键停下来</b>，日志里连一条拒绝原因都没有。
     * 那正是玩家报的「无论如何都能停机」。
     * <p>
     * 停堆在物理上<b>不可能是免费动作</b>：控制棒插下去以后堆芯里还剩大量余热，
     * 这部分热量只能靠 EFSS 的水带走。所以照 QSERF「Phase 1: Emergency Shutdown」
     * 的设定，这里卡两条：
     * <ol>
     *   <li>堆芯温度必须已经降到 {@link DmrSpec#TEMP_RUNAWAY}（3500K）以下 ——
     *       堆芯还在失控线上，插棒只会让它更不稳定；</li>
     *   <li>EFSS 水位 ≥ {@link DmrSpec#EFSS_SHUTDOWN_MIN}（70%）——
     *       水不够就带不走余热，停等于把堆留在半路上。</li>
     * </ol>
     * 这两条和应急停机的前两条<b>刻意保持一致</b>（见 {@link #checkShutdownPreconditions()}），
     * 这样玩家学到的是一条规则而不是两条。后两条（激光全在 / 双钥匙）不在这里要求：
     * 激光是"点火时安全硬件"，钥匙是"应急停机授权"，正常停堆不需要授权。
     * <p>
     * <b>不满足时返回 false 并发出<i>对应那一条</i>的拒绝原因</b>，而不是笼统一句"停不下来" ——
     * 玩家必须知道该去做什么（降温 / 补水）。
     *
     * @return 是否真的停了
     */
    public boolean beginCooldown() {
        if (state != DmrSpec.ST_ONLINE) {
            return false;
        }
        // ★ 前置条件：温度先于水位 —— 与 checkShutdownPreconditions() 同序，
        //   因为降温手段（热管理）本身在消耗 EFSS，先报温度才能把玩家
        //   引到"先降温 → 再补水"这条正确路径上，而不是让他一边补水一边还在过热。
        if (tempK >= DmrSpec.TEMP_RUNAWAY) {
            emit(DmrSpec.EV_COOLDOWN_FAILED_TEMP);
            return false;
        }
        if (efss < DmrSpec.EFSS_SHUTDOWN_MIN) {
            emit(DmrSpec.EV_COOLDOWN_FAILED_EFSS);
            return false;
        }
        state = DmrSpec.ST_OFFLINE;
        loadPct = 0;
        orderActive = false;
        emit(DmrSpec.EV_COOLDOWN_DONE);
        return true;
    }

    /**
     * 能不能进维护模式。判据照 QSERF：平均燃料低于 20% 才解锁，
     * 或者反应已经失速了（失速必须走维护重启）。
     */
    public boolean canEnterMaintenance() {
        if (state == DmrSpec.ST_STALLED) {
            return true;
        }
        return state == DmrSpec.ST_ONLINE && avgFuel() <= DmrSpec.FUEL_LOW_WARN;
    }

    public boolean enterMaintenance() {
        if (!canEnterMaintenance()) {
            return false;
        }
        state = DmrSpec.ST_MAINTENANCE;
        loadPct = 0;
        cellsReady = 0;
        orderActive = false;
        emit(DmrSpec.EV_MAINTENANCE_IN);
        return true;
    }

    /**
     * 现在装得进燃料单元吗（处于维护模式、且还没装满）。
     * <p>
     * ★ 单独抽出来是因为它有两个消费者：{@link #installFuelCell()} 自己，
     * 以及远程控制面板上"装料"那颗按钮的指示灯（见 {@code PanelCore#ST_DMR_CELL_READY}）。
     * 灯亮 = 按下去一定装得进去。判据写两份的必然结局是"灯亮着、按了没反应"。
     */
    public boolean canInstallFuelCell() {
        return state == DmrSpec.ST_MAINTENANCE && cellsReady < DmrSpec.FUEL_CELL_COUNT;
    }

    /**
     * 在维护模式下装入一块新燃料单元。
     *
     * @return 是否装进去了（不在维护模式、或已经装满了就失败）
     */
    public boolean installFuelCell() {
        if (!canInstallFuelCell()) {
            return false;
        }
        cellsReady++;
        return true;
    }

    /**
     * 结束维护、重启反应堆。必须三个燃料单元都换好了。
     * <p>
     * 重启的代价：平均燃料倒扣 {@link DmrSpec#FUEL_RESTART_PENALTY}。
     * 所以"烧到快没了再重启"是亏的 —— 早换早划算。
     */
    public boolean exitMaintenance() {
        if (state != DmrSpec.ST_MAINTENANCE) {
            return false;
        }
        if (cellsReady < DmrSpec.FUEL_CELL_COUNT) {
            emit(DmrSpec.EV_MAINTENANCE_NEED_CELLS);
            return false;
        }
        for (int i = 0; i < cells.length; i++) {
            cells[i] = Math.max(0.0, 100.0 - DmrSpec.FUEL_RESTART_PENALTY);
        }
        cellsReady = 0;
        fuelWarned = 0;
        monitorFailed = 0;
        coldTimer = 0;
        sustainLost = 0;
        resetSafetyHardware();
        state = DmrSpec.ST_ONLINE;
        emit(DmrSpec.EV_MAINTENANCE_OUT);
        return true;
    }

    /**
     * 提交应急停机码。只在熔毁的两个阶段有效。
     *
     * <h3>为什么"光有码"远远不够</h3>
     * 这是整个 DMR 从"随便就停下来了"变成"真的要人去救"的地方。照 QSERF 的设定，
     * 按下停机按钮之前必须<b>同时</b>满足：
     * <ol>
     *   <li>堆芯温度在 {@link DmrSpec#TEMP_RUNAWAY} 以下（3500K）—— 否则按钮按了也白按；</li>
     *   <li>EFSS 水位 ≥ {@link DmrSpec#EFSS_SHUTDOWN_MIN}（70%）；</li>
     *   <li>{@link DmrSpec#LASER_COUNT} 根功率激光<b>一根不少</b>都在工作；</li>
     *   <li>1 号、2 号两把钥匙<b>同时</b>转到位。</li>
     * </ol>
     * 每一条不满足都会给出<b>各自</b>的拒绝原因（而不是笼统一句"停机失败"），
     * 否则玩家只会知道"按不下去"，不知道该去修哪一样。
     * <p>
     * 另外照 QSERF 的设定：每次输入正确停机码，都有 1/100 的概率被静默跳过，
     * 设施直接下发 Code Black，反应堆内爆。这个概率没法靠技术规避，只能承担。
     */
    public boolean submitShutdownCode(int code) {
        if (!DmrSpec.isMeltdown(state)) {
            return false;
        }
        if (code != shutdownCode) {
            emit(DmrSpec.EV_CODE_REJECTED);
            return false;
        }
        // ★ 前置条件排在 1/100 之前：条件不齐时"停机流程"根本没开始，
        //   那 1/100 的跳过也就无从谈起（QSERF 原文说的也是"输入停机码执行停机时"
        //   才有 1/100 概率被跳过）。顺序反过来的话，玩家会被一个"连门都没进就黑掉"
        //   的结果莫名其妙地搞死，而且单测也没法稳定复现任何一条前置条件。
        if (!checkShutdownPreconditions()) {
            // 条件不足的一次尝试也要吃窗口 —— 否则"一直乱按"没有任何代价
            phaseTimer = Math.max(1, phaseTimer - cfg.phase2RetryPenalty);
            return false;
        }
        if (rng.nextInt(100) == 0) {
            emit(DmrSpec.EV_CODE_BLACK);
            implode("code_black");
            return false;
        }
        int saved = phase;
        state = DmrSpec.ST_SAFE;
        loadPct = 0;
        orderActive = false;
        emit(saved == 1 ? DmrSpec.EV_SHUTDOWN_OK_P1 : DmrSpec.EV_SHUTDOWN_OK_P2);
        return true;
    }

    /**
     * 逐条核对停机前置条件，不满足就发出<b>对应那一条</b>的事件键并返回 false。
     * <p>
     * 顺序不是随手排的：温度排在第一位，因为它是唯一一条"你不动手就永远不会满足"的；
     * 而降温的手段（热管理）又在消耗 EFSS —— 于是玩家自然会被引导到
     * <b>先降温 → 再核对水位 → 修激光 → 转第二把钥匙 → 提交码</b> 这条正确路径上。
     */
    private boolean checkShutdownPreconditions() {
        if (tempK >= DmrSpec.TEMP_RUNAWAY) {
            emit(DmrSpec.EV_SHUTDOWN_FAILED_THERMAL);
            return false;
        }
        if (efss < DmrSpec.EFSS_SHUTDOWN_MIN) {
            emit(DmrSpec.EV_SHUTDOWN_FAILED_EFSS);
            return false;
        }
        if (lasers < DmrSpec.LASER_COUNT) {
            emit(DmrSpec.EV_SHUTDOWN_FAILED_LASER);
            return false;
        }
        if (!hasSub(DmrSpec.SUB_KEY) || !hasSub(DmrSpec.SUB_KEY2)) {
            emit(DmrSpec.EV_SHUTDOWN_FAILED_KEYS);
            return false;
        }
        return true;
    }

    /**
     * Phase 2 的物理停机通道：拔掉一块燃料单元。
     * <p>
     * 照 QSERF 的原文，解除 Code Black 的唯一办法是"下到 DMR 里去，
     * <b>至少三个人同时各拔一块</b>"；而且 ——
     * "if one person accidentally separates the fuel cell, the reactor cell can no
     * longer be separated"。所以这里做成：
     * <ul>
     *   <li>每块燃料单元只能由<b>一个此前没拔过的人</b>拔；</li>
     *   <li>同一个人来拔第二块 → 整条换料通道<b>永久锁死</b>（{@link DmrSpec#EV_CELL_TRAP}），
     *       从那以后这条路上只剩内爆；</li>
     *   <li>三个人各拔一块 → 反应堆安全下线（{@link DmrSpec#EV_SHUTDOWN_OK_P2}）。</li>
     * </ul>
     * 单人模式下凑不齐三个人，所以"提交停机码"那条路始终保留 —— 两条路并存，
     * 但走物理通道能省下那 1/100 的 Code Black 风险。
     *
     * @param pullerId 拔料的人。由 MC 层给一个<b>本次会话内稳定</b>的玩家 id
     *                 （实体 id 就够用）；不需要跨存档稳定 —— 熔毁窗口只有几分钟。
     * @return 这一次拔料是否被接受
     */
    public boolean pullFuelCell(int pullerId) {
        if (!DmrSpec.isMeltdown(state)) {
            return false;
        }
        if (cellChannelLocked) {
            emit(DmrSpec.EV_CELL_TRAP);
            return false;
        }
        if (cellPulled >= DmrSpec.FUEL_CELL_COUNT) {
            return false;
        }
        for (int i = 0; i < cellPulled; i++) {
            if (pullers[i] == pullerId) {
                // 同一个人拔第二块 —— 通道锁死
                cellChannelLocked = true;
                emit(DmrSpec.EV_CELL_TRAP);
                return false;
            }
        }
        pullers[cellPulled] = pullerId;
        cellPulled++;
        // 视觉/读数上要看得出来"燃料被拿走了"：从最后一格往前清零
        int index = DmrSpec.FUEL_CELL_COUNT - cellPulled;
        if (index >= 0 && index < cells.length) {
            cells[index] = 0.0;
        }
        emit(DmrSpec.EV_CELL_PULLED);
        if (cellPulled >= DmrSpec.FUEL_CELL_COUNT) {
            state = DmrSpec.ST_SAFE;
            loadPct = 0;
            orderActive = false;
            emit(DmrSpec.EV_SHUTDOWN_OK_P2);
        }
        return true;
    }

    /**
     * 硬重启功率激光（对应 QSERF 的 "Power Laser 3 Hard Restart"）。
     * <p>
     * ★ 关键在拒绝条件：<b>太热的时候修不了</b>。堆芯还在 3150K（失控线的 90%）以上时
     * 拒绝重启 —— 这就把顺序钉死成"先降温，再修"，而不是"先修，再慢慢想"。
     *
     * @return 是否修好了
     */
    public boolean repairLasers() {
        if (lasers >= DmrSpec.LASER_COUNT) {
            return false;
        }
        if (tempK >= DmrSpec.TEMP_RUNAWAY * 0.9) {
            emit(DmrSpec.EV_SHUTDOWN_FAILED_LASER);
            return false;
        }
        lasers = DmrSpec.LASER_COUNT;
        laserWarned = 0;
        emit(DmrSpec.EV_LASER_REPAIRED);
        return true;
    }

    // -------------------------------------------------------------- 功率订单

    private void orderTick() {
        if (state != DmrSpec.ST_ONLINE) {
            orderActive = false;
            return;
        }
        if (!orderActive) {
            orderIdle++;
            if (orderIdle >= cfg.orderGapTicks) {
                newOrder();
            }
            return;
        }
        orderTimer--;
        if (Math.abs(loadPct - orderTargetPct) <= DmrSpec.ORDER_TOLERANCE) {
            orderGoodTicks++;
        }
        if (orderTimer > 0) {
            return;
        }
        if (orderGoodTicks >= (int) (cfg.orderTicks * cfg.orderPassRatio)) {
            ordersDone++;
            emit(DmrSpec.EV_ORDER_DONE);
        } else {
            ordersFailed++;
            emit(DmrSpec.EV_ORDER_FAIL);
        }
        orderActive = false;
        orderIdle = 0;
    }

    private void newOrder() {
        orderExtreme = rng.nextDouble() < cfg.extremeOrderChance;
        if (orderExtreme) {
            orderTargetPct = DmrSpec.ORDER_EXTREME_MIN
                    + rng.nextInt(DmrSpec.ORDER_EXTREME_MAX - DmrSpec.ORDER_EXTREME_MIN + 1);
        } else {
            orderTargetPct = DmrSpec.ORDER_NORMAL_MIN
                    + rng.nextInt(DmrSpec.ORDER_NORMAL_MAX - DmrSpec.ORDER_NORMAL_MIN + 1);
        }
        orderTimer = cfg.orderTicks;
        orderGoodTicks = 0;
        orderActive = true;
        ordersTotal++;
        emit(DmrSpec.EV_ORDER_NEW);
    }

    /** 班次评分：1 到 3 级。照 QSERF 的说法，订单全达成才是三级 */
    public int rating() {
        if (ordersTotal == 0) {
            return 1;
        }
        double ratio = ordersDone / (double) ordersTotal;
        if (ratio >= 0.90) {
            return 3;
        }
        if (ratio >= 0.60) {
            return 2;
        }
        return 1;
    }

    // ------------------------------------------------------------------ 告警

    /**
     * 刷新告警等级，并在跨温度带时播报一次。
     * <p>
     * 播报只在"带往上跨"的时候发，且回落时带 25K 迟滞 —— 否则温度在 1120K 上下来回蹭，
     * 每几 tick 就刷一条广播，读起来像噪声而不是警报。
     */
    private void updateAlarm() {
        if (DmrSpec.isMeltdown(state) || state == DmrSpec.ST_IMPLODED) {
            alarm = DmrSpec.AL_MELTDOWN;
            return;
        }
        int band;
        if (tempK > DmrSpec.TEMP_WARN_LIMIT) {
            band = 2;
        } else if (tempK > DmrSpec.TEMP_SAFE_LIMIT) {
            band = 1;
        } else if (tempBand > 0 && tempK > DmrSpec.TEMP_SAFE_LIMIT - 25.0) {
            band = tempBand;
        } else {
            band = 0;
        }
        if (band > tempBand) {
            emit(band == 2 ? DmrSpec.EV_WARN_TEMP : DmrSpec.EV_SAFE_TEMP);
        }
        tempBand = band;

        if (band == 2) {
            alarm = DmrSpec.AL_WARN;
        } else if (avgFuel() <= DmrSpec.FUEL_LOW_WARN && state == DmrSpec.ST_ONLINE) {
            alarm = DmrSpec.AL_FUEL;
        } else if (band == 1) {
            alarm = DmrSpec.AL_SAFE;
        } else {
            alarm = DmrSpec.AL_NONE;
        }
    }

    // -------------------------------------------------------------- 事件出口

    /** 取出并清空这一 tick 攒下的事件键。MC 层每 tick 调一次 */
    public List<String> drainEvents() {
        if (events.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        List<String> out = new ArrayList<String>(events);
        events.clear();
        return out;
    }

    private void emit(String key) {
        if (!events.contains(key)) {
            events.add(key);
        }
    }

    // ------------------------------------------------------------ 燃料工具

    private double avgFuel() {
        double s = 0.0;
        for (double c : cells) {
            s += c;
        }
        return s / cells.length;
    }

    private void addFuel(double delta) {
        for (int i = 0; i < cells.length; i++) {
            double v = cells[i] + delta;
            if (v < 0.0) {
                v = 0.0;
            }
            if (v > DmrSpec.FUEL_CEILING) {
                v = DmrSpec.FUEL_CEILING;
            }
            cells[i] = v;
        }
    }

    // ------------------------------------------------------------------ 读值

    public int getState() {
        return state;
    }

    public String getStateName() {
        return DmrSpec.stateName(state);
    }

    public int getSubsystems() {
        return sub;
    }

    public boolean hasSub(int bit) {
        return (sub & bit) != 0;
    }

    public double getTempK() {
        return tempK;
    }

    public double getIntegrity() {
        return integrity;
    }

    public double getAvgFuel() {
        return avgFuel();
    }

    public double getFuelCell(int index) {
        if (index < 0 || index >= cells.length) {
            return 0.0;
        }
        return cells[index];
    }

    public int getCellsReady() {
        return cellsReady;
    }

    public int getLoadPct() {
        return loadPct;
    }

    public double getCatalyzerEfficiency() {
        return eff;
    }

    public int getAlarm() {
        return alarm;
    }

    public long getTicks() {
        return ticks;
    }

    public int getPhase() {
        return phase;
    }

    public int getPhaseTimer() {
        return phaseTimer;
    }

    /** 熔毁期间要下发到控制台的停机码。不在熔毁阶段返回 0 */
    public int getShutdownCode() {
        return DmrSpec.isMeltdown(state) ? shutdownCode : 0;
    }

    /** 六位补零的停机码文本 */
    public String getShutdownCodeText() {
        int c = getShutdownCode();
        return c == 0 ? "------" : String.format("%06d", c);
    }

    /** EFSS 水位 (%) */
    public double getEfss() {
        return efss;
    }

    /** 还在工作的功率激光根数 */
    public int getLasers() {
        return lasers;
    }

    /** Phase 2 里已经拔下来的燃料单元数 */
    public int getCellPulled() {
        return cellPulled;
    }

    /** Phase 2 的换料通道是否已经锁死（有人拔了第二块） */
    public boolean isCellChannelLocked() {
        return cellChannelLocked;
    }

    /**
     * 离"能按下停机按钮"还差几条。0 = 全齐，可以停机。
     * <p>
     * 给界面用的：把"还差什么"直接摊在面板上，比让玩家一条条去猜友善得多。
     */
    public int shutdownBlockers() {
        int n = 0;
        if (tempK >= DmrSpec.TEMP_RUNAWAY) {
            n++;
        }
        if (efss < DmrSpec.EFSS_SHUTDOWN_MIN) {
            n++;
        }
        if (lasers < DmrSpec.LASER_COUNT) {
            n++;
        }
        if (!hasSub(DmrSpec.SUB_KEY) || !hasSub(DmrSpec.SUB_KEY2)) {
            n++;
        }
        return n;
    }

    public String getImplodeReason() {
        return implodeReason;
    }

    /**
     * 点火序列期间，反应堆腔体内部是致命的（人会被"微波"掉）。
     * MC 层拿这个去杀腔体里的实体。
     */
    public boolean isChamberLethal() {
        return state == DmrSpec.ST_STARTUP;
    }

    /** 内爆的爆炸只应生成一次。取了就清标记 */
    public boolean consumeExplosionPending() {
        if (explosionPending == 0) {
            return false;
        }
        explosionPending = 0;
        return true;
    }

    public boolean isOrderActive() {
        return orderActive;
    }

    public boolean isOrderExtreme() {
        return orderExtreme;
    }

    public int getOrderTargetPct() {
        return orderTargetPct;
    }

    public int getOrderTimer() {
        return orderTimer;
    }

    public int getOrdersDone() {
        return ordersDone;
    }

    public int getOrdersFailed() {
        return ordersFailed;
    }

    public int getOrdersTotal() {
        return ordersTotal;
    }

    public String getOrderTargetText() {
        if (!orderActive) {
            return "-";
        }
        return (orderTargetPct >= DmrSpec.ORDER_EXTREME_MIN ? "!" : "")
                + orderTargetPct + "%";
    }

    public DmrSettings getSettings() {
        return cfg;
    }

    // ---------------------------------------------------------- 存档 / 调试

    /**
     * 调试用：直接置完整性。给管理员指令和测试用 ——
     * 想让整台机器走到熔毁要跑几千 tick，测试里没法那么干。
     */
    public void debugSetIntegrity(double pct) {
        integrity = Math.max(0.0, Math.min(100.0, pct));
    }

    /** 调试用：直接置堆芯温度 */
    public void debugSetTempK(double k) {
        tempK = Math.max(0.0, k);
    }

    /** 调试用：直接置燃料 */
    public void debugSetFuel(double pct) {
        for (int i = 0; i < cells.length; i++) {
            cells[i] = Math.max(0.0, Math.min(DmrSpec.FUEL_CEILING, pct));
        }
    }

    /** 调试用：直接置 EFSS 水位 */
    public void debugSetEfss(double pct) {
        setEfss(pct);
    }

    /** 调试用：直接置功率激光根数 */
    public void debugSetLasers(int count) {
        if (count < 0) {
            count = 0;
        } else if (count > DmrSpec.LASER_COUNT) {
            count = DmrSpec.LASER_COUNT;
        }
        lasers = count;
    }

    // ------------------------------------------------------------- 状态存盘

    /**
     * 状态槽位总数。布局是<b>固定</b>的：以后要加字段只能<b>往后追加</b>，
     * 绝不能往中间插 —— 旧存档里的 int[] 会按新布局读，插一下整台机器的
     * 温度就变成燃料、阶段计时变成停机码，而且不会有任何报错。
     * <p>
     * 追加历史：0..37 = 第一版 38 槽；38..47 = 安全硬件（激光 / 换料通道 / EFSS）。
     * <p>
     * ★ 数槽位时记住 {@link #putD} <b>一个 double 吃两个槽</b>：
     * EFSS 放在 46，所以总长必须是 48 而不是 47 —— 写成 47 的话
     * {@code a[47]} 会在存档时直接抛 ArrayIndexOutOfBounds。
     */
    public static final int STATE_SLOTS = 48;

    /** double 用原始位拆成两个槽：无损，且不需要 NBT 支持 double 数组 */
    private static void putD(int[] a, int i, double v) {
        long bits = Double.doubleToRawLongBits(v);
        a[i] = (int) (bits >>> 32);
        a[i + 1] = (int) bits;
    }

    private static double getD(int[] a, int i) {
        return Double.longBitsToDouble(((long) a[i] << 32) | (a[i + 1] & 0xFFFFFFFFL));
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 清洗一个从存档里读回来的 double。
     * <p>
     * ★ 这里必须<b>单独盯住 NaN</b>：`v &lt; lo` 和 `v &gt; hi` 对 NaN <b>都是 false</b>，
     * 所以朴素的 clamp 会把 NaN 原样放过去（本类第一版就是这么写的，后果见下）。
     * <p>
     * NaN 是这个内核里唯一"一旦进去就再也出不来"的量：它会在之后每一次加减乘除里
     * 被完整地带着走，`getIntegrity()` 之类又都是原样返回，于是玩家会在控制柜面板上
     * 直接看到"结构完整性 NaN"，而且怎么修都修不回来 —— 只能拆了重建。
     * 所以 NaN 一律当作"这个字段彻底丢了"，退回该字段的正常默认值。
     */
    private static double scrubDouble(double v, double lo, double hi, double fallback) {
        if (Double.isNaN(v)) {
            return fallback;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /**
     * 导出整台机器的运行状态。
     * <p>
     * 为什么要做成"纯 int[]"而不是直接写 NBT：这个类是零 MC 依赖内核，
     * 一旦让它 import NBT，{@code check_dmr_core.py} 那条纯 javac 自检链路就断了。
     * 拆成 int[] 之后，<b>存盘/读档这件事本身也能被单测</b>（跑一遍存、读回来、
     * 再跑 1000 tick，两边必须逐步一致）。
     */
    public int[] saveState() {
        int[] a = new int[STATE_SLOTS];
        a[0] = state;
        a[1] = sub;
        a[2] = loadPct;
        a[3] = ignitionTimer;
        a[4] = coldTimer;
        a[5] = sustainLost;
        a[6] = fuelWarned;
        a[7] = monitorFailed;
        a[8] = integrityWarned;
        a[9] = tempBand;
        a[10] = alarm;
        a[11] = phase;
        a[12] = phaseTimer;
        a[13] = shutdownCode;
        a[14] = explosionPending;
        a[15] = orderTargetPct;
        a[16] = orderTimer;
        a[17] = orderGoodTicks;
        a[18] = orderIdle;
        a[19] = orderActive ? 1 : 0;
        a[20] = orderExtreme ? 1 : 0;
        a[21] = ordersDone;
        a[22] = ordersFailed;
        a[23] = ordersTotal;
        a[24] = (int) (ticks >>> 32);
        a[25] = (int) ticks;
        putD(a, 26, tempK);
        putD(a, 28, integrity);
        putD(a, 30, eff);
        for (int i = 0; i < cells.length; i++) {
            putD(a, 32 + i * 2, cells[i]);
        }
        // ---- 第二段（槽 38 起）：安全硬件。只能往后追加，见 STATE_SLOTS 的注释
        a[38] = lasers;
        a[39] = cellPulled;
        a[40] = pullers[0];
        a[41] = pullers[1];
        a[42] = pullers[2];
        a[43] = cellChannelLocked ? 1 : 0;
        a[44] = efssWarned;
        a[45] = laserWarned;
        putD(a, 46, efss);
        return a;
    }

    /**
     * 读回状态。和方块实体读 NBT 一样的规矩：<b>旧存档、手改过的数据都可能越界</b>，
     * 一律过一遍清洗，宁可把数值夹回合法范围，也不要让 NaN 或负温度进到 tick 里
     * （那会把整台机器变成 NaN，而且再也恢复不了）。
     * <p>
     * 长度不对就<b>整份丢弃</b>：半份状态（比如温度读到了、燃料没读到）比全新一台
     * 机器更糟 —— 那是台"温度五千度但燃料满格"的鬼东西。
     */
    public void loadState(int[] a) {
        if (a == null || a.length < STATE_SLOTS) {
            return;
        }
        state = clampInt(a[0], DmrSpec.ST_OFFLINE, DmrSpec.ST_IMPLODED);
        sub = a[1] & DmrSpec.SUB_ALL;
        loadPct = clampInt(a[2], DmrSpec.LOAD_MIN_PCT, DmrSpec.LOAD_MAX_PCT);
        ignitionTimer = Math.max(0, a[3]);
        coldTimer = Math.max(0, a[4]);
        sustainLost = a[5] == 0 ? 0 : 1;
        fuelWarned = a[6] == 0 ? 0 : 1;
        monitorFailed = a[7] == 0 ? 0 : 1;
        integrityWarned = a[8] == 0 ? 0 : 1;
        tempBand = clampInt(a[9], 0, 2);
        alarm = clampInt(a[10], DmrSpec.AL_NONE, DmrSpec.AL_MELTDOWN);
        phase = clampInt(a[11], 0, 2);
        phaseTimer = Math.max(0, a[12]);
        shutdownCode = a[13];
        explosionPending = a[14] == 0 ? 0 : 1;
        orderTargetPct = a[15];
        orderTimer = Math.max(0, a[16]);
        orderGoodTicks = Math.max(0, a[17]);
        orderIdle = Math.max(0, a[18]);
        orderActive = a[19] != 0;
        orderExtreme = a[20] != 0;
        ordersDone = Math.max(0, a[21]);
        ordersFailed = Math.max(0, a[22]);
        ordersTotal = Math.max(0, a[23]);
        ticks = ((long) a[24] << 32) | (a[25] & 0xFFFFFFFFL);
        if (ticks < 0) {
            ticks = 0;
        }
        // 四个 double 字段各自给一个"正常默认值"当 NaN 的落点：
        // 温度回落环境温度、完整性回满、效率回额定、燃料回空 —— 都是不会把机器
        // 推进"看起来正常其实已经坏了"或"瞬间自毁"两个极端的选择。
        tempK = scrubDouble(getD(a, 26), 0.0, 1.0e7, cfg.ambientK);
        integrity = scrubDouble(getD(a, 28), 0.0, 100.0, 100.0);
        eff = scrubDouble(getD(a, 30), 0.0, 10.0, 1.0);
        for (int i = 0; i < cells.length; i++) {
            cells[i] = scrubDouble(getD(a, 32 + i * 2), 0.0, DmrSpec.FUEL_CEILING, 0.0);
        }
        // ---- 第二段：安全硬件。NaN 落点同样取"该字段的正常默认值"
        lasers = clampInt(a[38], 0, DmrSpec.LASER_COUNT);
        cellPulled = clampInt(a[39], 0, DmrSpec.FUEL_CELL_COUNT);
        for (int i = 0; i < pullers.length; i++) {
            pullers[i] = a[40 + i];
        }
        // 一致性回修：已经标记"拔了几块"，那前几格必须真的有主人；
        // 脏数据里出现"拔了 2 块但没有主人"会让"同一人拔两次"的判定失效。
        for (int i = 0; i < pullers.length; i++) {
            if (i >= cellPulled) {
                pullers[i] = -1;
            }
        }
        cellChannelLocked = a[43] != 0;
        efssWarned = a[44] == 0 ? 0 : 1;
        laserWarned = a[45] == 0 ? 0 : 1;
        setEfss(scrubDouble(getD(a, 46), 0.0, DmrSpec.EFSS_MAX, DmrSpec.EFSS_MAX));
    }
}

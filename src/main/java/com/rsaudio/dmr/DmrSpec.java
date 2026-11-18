package com.rsaudio.dmr;

/**
 * DMR（Dark Matter Reactor，暗物质反应堆）的规格常量：状态、子系统、告警、阈值。
 * <p>
 * <b>零 Minecraft 依赖。</b>和 {@code com.rsaudio.redstone.asm} 一套内核同样的路子 ——
 * 反应堆的物理与状态机放在这边，只做纯算术，好让 {@code tools/check_dmr_core.py}
 * 用真 javac 编译、真跑边界用例。复刻一份逻辑去测是测不出生产代码回归的。
 * <p>
 * 阈值取自 QSERF 的设定（1120K 安全线 / 2300K 警告线 / 3500K 失控线 / 燃料 20% 预警），
 * 但时间尺度是按 Minecraft 的 20 tick/秒重新标定的：一"班次"不再是现实的两小时，
 * 而是一条几分钟内走完的失事过程，好让几个人在同一个世界里配合得起来。
 */
public final class DmrSpec {

    private DmrSpec() {
    }

    // ------------------------------------------------------------------ 状态
    /** 未点火。新机器、或正常停机之后的冷却完成态 */
    public static final int ST_OFFLINE = 0;
    /** 点火序列进行中：全设施断电、重力激光把堆芯托到中心位 */
    public static final int ST_STARTUP = 1;
    /** 在线运行 */
    public static final int ST_ONLINE = 2;
    /** 维护模式：停堆换燃料单元 */
    public static final int ST_MAINTENANCE = 3;
    /** 燃烧失速：反应无法自持，必须走维护重启 */
    public static final int ST_STALLED = 4;
    /** 熔毁第一阶段。有停机窗口 */
    public static final int ST_MELTDOWN_P1 = 5;
    /** 熔毁第二阶段。窗口更短，且必须热管理系统在线才救得回 */
    public static final int ST_MELTDOWN_P2 = 6;
    /** 已安全：熔毁被成功制止，或正常冷却完成。终态之一 */
    public static final int ST_SAFE = 7;
    /** 已内爆。终态之一，不可逆 */
    public static final int ST_IMPLODED = 8;

    // -------------------------------------------------------------- 子系统位
    /** 反应堆腔内的燃料单元已全部锁定（要人进腔体手动拉杆） */
    public static final int SUB_FUEL_CELLS_LOCKED = 1;
    /** 功率激光给水泵已启动 */
    public static final int SUB_POWER_LASER_PUMPS = 1 << 1;
    /** 辅助电网已接入 —— 主堆点火前得靠它把所有系统先带起来 */
    public static final int SUB_AUX_GRID = 1 << 2;
    /** 点火钥匙已插入（1 号钥匙，控制室那一把） */
    public static final int SUB_KEY = 1 << 3;
    /** 热管理系统（冷却/热交换回路） */
    public static final int SUB_THERMAL_SYSTEMS = 1 << 4;
    /**
     * 2 号钥匙已插入。
     * <p>
     * 应急停机<b>必须两把钥匙同时转到位</b>（照 QSERF：Code Red 之后要两位操作员各自转钥匙）。
     * 点火本身只要 1 号钥匙，所以这个位不在 {@link #SUB_ALL_REQUIRED} 里 ——
     * 它是一道只在"出事之后"才存在的门槛。
     */
    public static final int SUB_KEY2 = 1 << 5;

    /** 点火之前必须全部就位的子系统 */
    public static final int SUB_ALL_REQUIRED =
            SUB_FUEL_CELLS_LOCKED | SUB_POWER_LASER_PUMPS | SUB_AUX_GRID | SUB_KEY;

    /** 所有可切换子系统的掩码 */
    public static final int SUB_ALL =
            SUB_ALL_REQUIRED | SUB_THERMAL_SYSTEMS | SUB_KEY2;

    // ---------------------------------------------------------------- 告警等级
    /** 一切正常 */
    public static final int AL_NONE = 0;
    /** 安全告警：温度越过 1120K */
    public static final int AL_SAFE = 1;
    /** 警告：温度越过 2300K，结构完整性开始掉 */
    public static final int AL_WARN = 2;
    /** 燃料告警：平均燃料低于 20% */
    public static final int AL_FUEL = 3;
    /** 熔毁告警：完整性低于 10%，已进入不可逆阶段 */
    public static final int AL_MELTDOWN = 4;

    // ------------------------------------------------------------ 温度阈值 (K)
    /** 环境温度 */
    public static final double TEMP_AMBIENT = 293.0;
    /** 热管理系统能把堆芯拉到的最低温度 */
    public static final double TEMP_COOL_FLOOR = 80.0;
    /** 低于此温度反应彻底无法自持，立即失速 */
    public static final double TEMP_CATALYZER_DEATH = 150.0;
    /** 低于此温度开始播报"反应可持续性正在丧失" */
    public static final double TEMP_STALL_WARN = 230.0;
    /** 275K 是"不能长时间待在下面"的那条线 */
    public static final double TEMP_STALL_EDGE = 275.0;
    /** 安全运行上限 */
    public static final double TEMP_SAFE_LIMIT = 1120.0;
    /** 警告线：越过它完整性就开始掉，且燃料会反向上涨 */
    public static final double TEMP_WARN_LIMIT = 2300.0;
    /** 失控线：越过它完整性掉得很快 */
    public static final double TEMP_RUNAWAY = 3500.0;
    /** 额定运行温度 */
    public static final double TEMP_NOMINAL = 900.0;

    // ------------------------------------------------------------------ 燃料
    /** 燃料单元的个数。换燃料时必须三个一起换 */
    public static final int FUEL_CELL_COUNT = 3;
    /** 平均燃料低于这个值就播报换料预警，且维护按钮解锁 */
    public static final double FUEL_LOW_WARN = 20.0;
    /** 每次重启，平均燃料要倒扣这个数 */
    public static final double FUEL_RESTART_PENALTY = 10.0;
    /** 燃料值的上限。允许超 100 是故意的 —— 超了会亮"燃料监控系统故障" */
    public static final double FUEL_CEILING = 300.0;

    // ---------------------------------------------- EFSS（应急消防 / 冷却水）
    /**
     * EFSS = Emergency Fire Suppression System，应急消防系统的水位（%）。
     * <p>
     * QSERF 的停机前置条件里有明明白白的一条：<b>"the Emergency Fire Suppression
     * System must be above 70% full"</b>。水位不满 70%，就算停机码完全正确也按不下去。
     * <p>
     * 它的消耗和热管理系统绑在一起：<b>只要热管理在跑、而且堆芯已经越过安全线</b>，
     * 消防回路就在放水（掉水位）；反过来，堆芯凉着、热管理关着的时候，泵站会把它补回来。
     * 由此得到一条贯穿整个事故的张力 —— 想降温就得开热管理，开热管理就在烧水位。
     * 慌了神一直按住热管理不放的人，最后手里会剩一桶不够 70% 的水。
     */
    public static final double EFSS_MAX = 100.0;
    /** 停机要求的最低水位 */
    public static final double EFSS_SHUTDOWN_MIN = 70.0;
    /** 水位低于这个值就播报预警（比停机线高一点，好留出反应时间） */
    public static final double EFSS_WARN = 75.0;

    // ------------------------------------------------ 功率激光（催化器）健康
    /**
     * 功率激光的根数。QSERF 里 Power Laser 3 会被倒塌的吊车砸坏，
     * 而且是<b>随机、不可预防</b>的；停机的前置条件要求<b>所有</b>功率激光都在工作。
     * <p>
     * 这里做成：堆芯进入失控线以上之后，每 tick 有极小概率烧掉一根。
     * 坏掉的可以硬重启修回来，但"太热的时候修不了" —— 所以顺序必须是
     * <b>先降温、再修激光、最后提交停机码</b>。
     */
    public static final int LASER_COUNT = 3;

    // ---------------------------------------------------------------- 完整性
    /** 完整性低于此值即进入熔毁，不可逆 */
    public static final double INTEGRITY_MELTDOWN = 10.0;

    // ------------------------------------------------------------ 功率与订单
    /** 额定功率 (kW)。功率订单用"额定百分比"表示，80% 就是 0.8 倍负荷 */
    public static final int RATED_KW = 200000;
    /** 输出负荷的允许范围（%） */
    public static final int LOAD_MIN_PCT = 0;
    public static final int LOAD_MAX_PCT = 600;
    /** 订单容差（%）：设定负荷和订单目标的差距在这个范围内算达标 */
    public static final int ORDER_TOLERANCE = 10;
    /** 常规订单的目标区间（%） */
    public static final int ORDER_NORMAL_MIN = 80;
    public static final int ORDER_NORMAL_MAX = 180;
    /** 极端订单的目标区间（%）。接了会过热，拒了要掉评分 */
    public static final int ORDER_EXTREME_MIN = 330;
    public static final int ORDER_EXTREME_MAX = 550;

    /**
     * 停机码的取值范围。
     * <p>
     * ★ 是 <b>6 位</b>，不是 4 位 —— QSERF 原文写的是 "find a six-digit code that spawns
     * at a random set spawn location within the facility"。而且那串码<b>不会送到你手上</b>：
     * 它以一张便签的形式散落在设施里，得有人跑去找回来念给控制室听。
     * 这两点合起来才是"反应堆没那么容易停机"的真正原因。
     */
    public static final int SHUTDOWN_CODE_MIN = 100000;
    public static final int SHUTDOWN_CODE_MAX = 999999;
    /** 停机码的位数（GUI 输入框长度、便签文本都用它） */
    public static final int SHUTDOWN_CODE_DIGITS = 6;

    // ------------------------------------------------------------------ 事件
    // 内核只负责产出这些键，翻译成广播文本 / 音效 / 字幕是 MC 层的事。
    public static final String EV_IGNITION_START = "ignition_start";
    public static final String EV_IGNITION_DENIED_SUBSYS = "ignition_denied_subsys";
    public static final String EV_IGNITION_DENIED_FUEL = "ignition_denied_fuel";
    public static final String EV_ONLINE = "online";
    public static final String EV_COOLDOWN_DONE = "cooldown_done";
    public static final String EV_COOLDOWN_FAILED_TEMP = "cooldown_failed_temp";
    public static final String EV_COOLDOWN_FAILED_EFSS = "cooldown_failed_efss";
    public static final String EV_SAFE_TEMP = "safe_temp";
    public static final String EV_WARN_TEMP = "warn_temp";
    public static final String EV_INTEGRITY_DROP = "integrity_drop";
    public static final String EV_FUEL_LOW = "fuel_low";
    public static final String EV_FUEL_MONITOR_FAIL = "fuel_monitor_fail";
    public static final String EV_SUSTAIN_LOSS = "sustain_loss";
    public static final String EV_STALL = "stall";
    public static final String EV_MAINTENANCE_IN = "maintenance_in";
    public static final String EV_MAINTENANCE_OUT = "maintenance_out";
    public static final String EV_MAINTENANCE_NEED_CELLS = "maintenance_need_cells";
    public static final String EV_MELTDOWN = "meltdown";
    public static final String EV_PHASE2 = "phase2";
    public static final String EV_SHUTDOWN_OK_P1 = "shutdown_ok_p1";
    public static final String EV_SHUTDOWN_OK_P2 = "shutdown_ok_p2";
    public static final String EV_CODE_REJECTED = "code_rejected";
    /**
     * 停机被拒：堆芯温度还在 3500K 以上。
     * <p>
     * ★ 这个名字是从"必须开热管理"那版留下来的。现在它代表的是<b>温度</b>这一条卡口，
     * 而温度这一条恰恰只能靠热管理系统压下去 —— 所以提示文案里第一条写给玩家的
     * 建议仍然是"去开热管理"，语义没变。
     */
    public static final String EV_SHUTDOWN_FAILED_THERMAL = "shutdown_failed_thermal";
    /** 停机被拒：EFSS 水位不足 70% */
    public static final String EV_SHUTDOWN_FAILED_EFSS = "shutdown_failed_efss";
    /** 停机被拒：有功率激光不工作 */
    public static final String EV_SHUTDOWN_FAILED_LASER = "shutdown_failed_laser";
    /** 停机被拒：两把钥匙没有同时转到位 */
    public static final String EV_SHUTDOWN_FAILED_KEYS = "shutdown_failed_keys";
    /** 换料通道被拒：拔燃料单元的人数/顺序不对 */
    public static final String EV_SHUTDOWN_FAILED_CELLS = "shutdown_failed_cells";
    /** 烧坏了一根功率激光 */
    public static final String EV_LASER_LOST = "laser_lost";
    /** 功率激光硬重启成功 */
    public static final String EV_LASER_REPAIRED = "laser_repaired";
    /** EFSS 水位掉到预警线以下 */
    public static final String EV_EFSS_LOW = "efss_low";
    /** 成功拔下一块燃料单元（Phase 2 物理停机通道） */
    public static final String EV_CELL_PULLED = "cell_pulled";
    /**
     * 有人在 Phase 2 里拔了第二块燃料单元 —— 照 QSERF，"one person accidentally
     * separates the fuel cell, the reactor cell can no longer be separated"，
     * 于是换料通道彻底锁死，只剩内爆一条路。
     */
    public static final String EV_CELL_TRAP = "cell_trap";
    /** 停机码便签被找到了（MC 层在玩家捡起便签时发） */
    public static final String EV_CODE_LOCATED = "code_located";
    public static final String EV_CODE_BLACK = "code_black";
    public static final String EV_IMPLODE = "implode";
    public static final String EV_ORDER_NEW = "order_new";
    public static final String EV_ORDER_DONE = "order_done";
    public static final String EV_ORDER_FAIL = "order_fail";

    /**
     * 状态码 -> 供 GUI / 命令用的英文短名。语言文件里查 {@code dmr.state.<name>}。
     */
    public static String stateName(int state) {
        switch (state) {
            case ST_OFFLINE:
                return "offline";
            case ST_STARTUP:
                return "startup";
            case ST_ONLINE:
                return "online";
            case ST_MAINTENANCE:
                return "maintenance";
            case ST_STALLED:
                return "stalled";
            case ST_MELTDOWN_P1:
                return "meltdown_p1";
            case ST_MELTDOWN_P2:
                return "meltdown_p2";
            case ST_SAFE:
                return "safe";
            case ST_IMPLODED:
                return "imploded";
            default:
                return "unknown";
        }
    }

    /** 这个状态是不是"已经没法再抢救了"的终态 */
    public static boolean isTerminal(int state) {
        return state == ST_SAFE || state == ST_IMPLODED;
    }

    /** 这个状态是不是熔毁相关（告警面板要闪红） */
    public static boolean isMeltdown(int state) {
        return state == ST_MELTDOWN_P1 || state == ST_MELTDOWN_P2;
    }
}

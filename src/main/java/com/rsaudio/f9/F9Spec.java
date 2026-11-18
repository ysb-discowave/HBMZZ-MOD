package com.rsaudio.f9;

/**
 * F9（Foxtrot Mk. 9 Mobile Nuclear Fission Reactor / Project Helios）的规格常量。
 * <p>
 * <b>零 Minecraft 依赖。</b>和 {@code com.rsaudio.dmr.DmrSpec} 同一套路子 ——
 * 反应堆的物理与状态机放在这里，只做纯算术，好让 {@code tools/check_f9_core.py}
 * 用真 javac 编译、真跑边界用例。
 *
 * <h3>与 DMR 的关键区别（照着做的时候别把两者搞混）</h3>
 * DMR 是<b>暗物质惯性约束聚变堆</b>，失控路径是"温度 → 完整性 → 熔毁"，
 * 玩家要处理的是热管理、EFSS 水位、功率激光、双钥匙。
 * <p>
 * F9 是<b>裂变堆 + 斯特林转换器</b>，完全不同的四条物理量，而且**压力才是最脆弱的**：
 * <ul>
 *   <li><b>控制棒插入度</b>（0..100%）：插得越深反应越弱 → 温度/压力越低。
 *       ★ 反直觉的一点：<b>拔棒（插入度下降）才升功率</b>；插棒是刹车。</li>
 *   <li><b>中子通量</b>（0..{@link #FLUX_SCRAM}%）：<b>平时恒为 0</b>，
 *       只有"正在拔棒"的时候才涨，松手立刻回 0。它的作用是<b>推高压力</b>。
 *       ★ 这是整个 F9 里最容易让新手翻车的量：1% 看着没事，转眼 12%、压力飞涨。</li>
 *   <li><b>压力</b>（PSI）：最脆弱。{@link #PSI_DANGER} 以上危险、
 *       {@link #PSI_SCRAM} 自动 SCRAM、{@link #PSI_EXPLODE} 直接炸。</li>
 *   <li><b>温度</b>（°C）：越高越好（到某个度）。{@link #TEMP_ALARM} 热功率警报、
 *       {@link #TEMP_SCRAM} 自动 SCRAM。</li>
 * </ul>
 * 另外还有 <b>热功率</b>（kW）—— 那才是最终要同步到电网的东西，
 * 同步窗口是 {@link #SYNC_KW_MIN}..{@link #SYNC_KW_MAX}。
 *
 * <h3>数值来源</h3>
 * 全部来自 QSERF 官方/社区维基与两版玩家攻略（见 {@code QSERF_SPEC.md}）。
 * 时间尺度按 Minecraft 的 20 tick/秒 重新标定 —— 现实里启动一台 SMR 要几小时，
 * 这里必须压缩到几分钟内走完，好让几个人在同一个世界里配合得起来。
 */
public final class F9Spec {

    private F9Spec() {
    }

    // ------------------------------------------------------------------ 状态
    /** 冷停堆。新机器、或正常停机流程走完之后的终态 */
    public static final int ST_COLD = 0;
    /**
     * 运行中。<b>注意：F9 没有 DMR 那种"点火序列"</b> ——
     * 它没有需要人跑腿预备的子系统（燃料单元在容器里，装好就一直在），
     * 所以启动就是"把棒拔出来"这一个动作，没有中间的 STARTUP 态。
     */
    public static final int ST_RUNNING = 1;
    /**
     * 已同步到辅助电网。这是 F9 的<b>目标状态</b> ——
     * 只有进到这里才真的在给设施供电（见 {@link #outputKw}）。
     */
    public static final int ST_SYNCED = 2;
    /**
     * 已 SCRAM（紧急停堆）。控制棒全插到底，反应终止，但<b>余热还在</b> ——
     * 压力/温度要靠泄压阀和热循环压下来，这中间仍然可能炸。
     * <p>
     * ★★ 这正是 QSERF "Blowout" 事故的物理来源：SCRAM 之后棒会掉落，
     * 但中子通量会让压力反而更高 → 炸。所以 SCRAM <b>不等于</b>安全。
     */
    public static final int ST_SCRAMMED = 3;
    /** 熔毁。不可逆终态之一 */
    public static final int ST_MELTDOWN = 4;
    /** 炸了。不可逆终态之一。{@link #SCRAM_BLOWOUT_TICKS} 之后刷新为全新 */
    public static final int ST_BLOWN = 5;

    // -------------------------------------------------------------- 控制系统位
    /**
     * 反应堆安全系统（Reactor Safety System）。
     * ★★ <b>默认打开，而且必须保持打开。</b>关掉之后压力/温度过高<b>不再自动 SCRAM</b>
     * → 很容易炸。这就是"绕过安全系统制造爆堆"的那条路。
     * <p>
     * 做成位而不是 boolean，是为了和别的开关同构（见 {@link #SUB_ALL}）。
     */
    public static final int SUB_SAFETY = 1;
    /** 控制棒连续移动。开启后拔/插棒不会在 1 秒后自动停，而是持续动 */
    public static final int SUB_CONTINUOUS_TRANSIT = 1 << 1;
    /** 控制棒速度 2x（关 = 1x） */
    public static final int SUB_ROD_SPEED_2X = 1 << 2;
    /** 热循环（thermal loop）。★ 被安全系统锁住：只有先关安全系统才动得了 */
    public static final int SUB_THERMAL_LOOP = 1 << 3;

    /** 单个转换器活塞 i 是否已接入电网（第 i 位） */
    public static final int CONVERTER_BIT = 1 << 4;
    /** 单个泄压阀 i 是否打开（第 i 位） */
    public static final int VALVE_BIT = 1 << 8;

    /** 所有可切换开关的掩码（前 4 个是单一位的开关） */
    public static final int SUB_ALL = SUB_SAFETY | SUB_CONTINUOUS_TRANSIT | SUB_ROD_SPEED_2X | SUB_THERMAL_LOOP;

    /** 转换器位区掩码 */
    public static final int CONVERTER_MASK = 0xF << 4;
    /** 泄压阀位区掩码 */
    public static final int VALVE_MASK = 0xF << 8;

    // ------------------------------------------------------------------ 数量
    /** 控制棒根数（QSERF 原文：with 9 control rods） */
    public static final int ROD_COUNT = 9;
    /** 转换器活塞个数 */
    public static final int CONVERTER_COUNT = 4;
    /** 泄压阀个数 */
    public static final int VALVE_COUNT = 4;
    /**
     * 控制棒分区数。
     * <p>
     * 照 QSERF 的 Reactor Overview："It displays the temperature and pressure of the
     * different <b>sectors</b> of the Reactor"。堆体分成若干个区，
     * 泄压阀"降低<b>所选分区</b>的压力"（原文：lower the pressure of the selected sector）。
     * 9 根棒按 3 区 × 3 根分。
     */
    public static final int SECTOR_COUNT = 3;
    /** 每个分区几根控制棒 */
    public static final int RODS_PER_SECTOR = ROD_COUNT / SECTOR_COUNT;

    // ------------------------------------------------------------ 控制棒行程
    /**
     * 控制棒插入度（%）。
     * <p>
     * ★ <b>100 = 全插入 = 反应几乎停止</b>（这是停堆姿势）；
     * <b>0 = 全拔出 = 满功率</b>。
     * 注意方向别记反了 —— QSERF 原文里 "Raise the Rods to ~35%" 说的是
     * "把棒拔出来，直到插入度剩 35%"，所以 35% 是<b>高功率</b>工况。
     */
    public static final int ROD_INSERT_MIN = 0;
    public static final int ROD_INSERT_MAX = 100;
    /** 停机流程要求插到底 */
    public static final int ROD_INSERT_SHUTDOWN = 100;
    /**
     * 攻略里推荐的运行插入度。
     * "Raise the Rods to ~35%"；同步之后 "lower the control rods to 40% insertion"。
     */
    public static final int ROD_INSERT_RUN = 35;
    public static final int ROD_INSERT_SYNC = 40;
    /**
     * 爆堆的经典配方里那个插入度。
     * "getting the pressure into the red zone with around 50% insertion of
     * control rods and then raising the rods until the safety system activates
     * the SCRAM" —— 半插入既能压住功率、又不至于让反应死掉，
     * 然后猛拔棒 → 通量暴涨 → 压力冲顶。
     */
    public static final int ROD_INSERT_BLOWOUT = 50;
    /**
     * 单次拉杆的默认行程（tick）。
     * 照 QSERF："If Enabled, it will allow the movement of the Control Rod insertion
     * to not be stopped after <b>1 second</b> and be constant."
     * 所以默认拉一下 = 动 1 秒 = 20 tick，连续移动模式下则一直动。
     */
    public static final int ROD_TRANSIT_TICKS = 20;
    /** 拉杆每 tick 改变多少插入度（%）。乘速度倍率 */
    public static final double ROD_RATE_PER_TICK = 0.6;

    // -------------------------------------------------------------- 中子通量
    /**
     * 中子通量（%）。★ 平时恒为 0，只有"正在拔棒"时才涨，松手立刻回 0。
     * <p>
     * 它的作用是<b>推高压力</b>。这条规则是整个 F9 的核心张力：
     * 想升功率就必须拔棒，而拔棒就在推压力 —— 功率和压力是同一根杠杆的两头。
     */
    public static final double FLUX_MAX = 100.0;
    /** 正在拔棒时每 tick 涨多少（%）—— 乘速度倍率 */
    public static final double FLUX_RISE_PER_TICK = 0.55;
    /** 停止拔棒后每 tick 回落多少（%）—— 掉得比涨得快，所以"松手"是有效的止损 */
    public static final double FLUX_FALL_PER_TICK = 0.9;
    /**
     * 安全系统的中子通量上限。
     * QSERF 原文："High Neutron Flux Rate (Maximum of 14%)" —— 越过 14% 就自动 SCRAM。
     */
    public static final double FLUX_SCRAM = 14.0;
    /**
     * 爆堆时通量的"作弊"上限。
     * <p>
     * ★ SCRAM 之后控制棒会掉落、通量本该归零，但 QSERF 明确说
     * "the neutron flux will cause the pressure to spike even higher, exploding
     * the reactor"。所以这里给爆堆路径一个更高的天花板，
     * 让"SCRAM 之后压力继续冲"在数值上真的成立 —— 否则 SCRAM 一插棒压力就掉，
     * 那套事故根本不可能出现。
     */
    public static final double FLUX_BLOWOUT_MAX = 30.0;

    // ------------------------------------------------------------------ 压力
    /**
     * 压力（PSI）。★ <b>最脆弱也最重要的量</b>。
     * 四条线，一条比一条致命，全部来自 QSERF 原文：
     */
    public static final double PSI_MIN = 0.0;
    /** 冷停堆 / 稳定待机压力。"After the temperature and pressure reach stable levels (75*C and 1060)" */
    public static final double PSI_STABLE = 1060.0;
    /** 危险线。原文："You do not want to go over 3500 Psi, as that would be dangerous." */
    public static final double PSI_DANGER = 3500.0;
    /** 自动 SCRAM。"If it goes above 3700 Psi, the Auto-SCRAM will activate" */
    public static final double PSI_SCRAM = 3700.0;
    /** 爆炸。"if it crosses 4000 Psi, the Reactor will explode" */
    public static final double PSI_EXPLODE = 4000.0;
    /**
     * 同步前压力应该被拉到哪个区间附近。
     * 攻略："You start by raising the pressure to about the red zone with control
     * rods fully raised." 红区 ≈ 3500。同步成功后压力会掉到黄区。
     */
    public static final double PSI_SYNC_TARGET = 3400.0;

    // ------------------------------------------------------------------ 温度
    /**
     * 温度（°C）。
     * 原文："The higher it is, the better (to a degree)... If it goes above around
     * 850C, the Thermal Power alarm will activate and not much higher after that
     * the Reactor will activate the Auto-SCRAM."
     */
    public static final double TEMP_AMBIENT = 20.0;
    /** 稳定待机温度。"reach stable levels (75*C and 1060)" */
    public static final double TEMP_STABLE = 75.0;
    /** 热功率警报。原文 "around 850C" */
    public static final double TEMP_ALARM = 850.0;
    /** 自动 SCRAM。"not much higher after that" */
    public static final double TEMP_SCRAM = 950.0;
    /**
     * 容器水温的自动 SCRAM 线。
     * <p>
     * ★ 这一条来自维基 Trivia："Despite there being an automatic SCRAM at
     * <b>420*C</b> to prevent a meltdown, there is no annunciator for high vessel
     * water temperature." —— 也就是说 <b>420°C 有保护但没有任何告警提示</b>，
     * 玩家会在毫无征兆的情况下被保护性停堆。这个"沉默的保护"是个很妙的细节，
     * 所以这里照做：越过 420 直接 SCRAM，且<b>不</b>发告警事件。
     * <p>
     * 它比 {@link #TEMP_ALARM} 低得多，意味着正常升温路上就会先撞上它 ——
     * 这也符合现实 PWR 的"先撞水温保护"。
     */
    public static final double TEMP_VESSEL_SCRAM = 420.0;

    // ------------------------------------------------------------------ 热功率
    /**
     * 热功率（kW）。额定 40000，峰值 50000。
     * <p>
     * 热功率 = 在这条"拔棒 → 通量 → 压力/温度"的链子上真正做功的那一项。
     * 同步之后它就是往电网送的功率。
     */
    public static final int KW_RATED = 40000;
    public static final int KW_MAX = 50000;
    /** 安全系统的热功率上限。原文："High Thermal Power (Around 48000 kW)" */
    public static final int KW_SCRAM = 48000;
    /**
     * 停机流程第一步的功率上限。
     * 原文："This can be done by using relief valves to lower power below 1200 kW,
     * then desynchronizing the reactor."
     */
    public static final int KW_SHUTDOWN_MAX = 1200;

    // ------------------------------------------------------------------ 同步
    /**
     * 同步窗口（kW）。
     * 原文："look at the bottom if there is no number that is not 39000 to 41000 then
     * if its below 39000 raise the rods on Control Rod speed 1x if above 41000 open
     * relief valves until its as close as you can get it to 40000"。
     */
    public static final int SYNC_KW_MIN = 39000;
    public static final int SYNC_KW_MAX = 41000;
    /** 电网频率（Hz）。指针过 0 = 60 Hz 才同步得上 */
    public static final double GRID_HZ = 60.0;
    /**
     * 同步指针的合格区间。
     * <p>
     * 一篇攻略说 "-1 to 1"，另一篇说 "hovers over Zero value"；
     * 社区攻略说 "get the meter nearly or exactly still at -1 to 1"。
     * 取 <b>±1.0</b> —— 比"必须精确为 0"宽容，但比"随便就同步上"严格得多。
     */
    public static final double SYNC_NEEDLE_TOLERANCE = 1.0;
    /**
     * 指针方向语义（很反直觉，别搞反）：
     * <b>Lag 方向 = 压力太低</b>，<b>Lead 方向 = 压力太高</b>。
     * 指针值 = (热功率 - 40000) 按比例缩放，所以：
     * 功率低于 40000 → 负值 → Lag（要拔棒／升速）；
     * 功率高于 40000 → 正值 → Lead（要开阀泄压）。
     */
    public static final double NEEDLE_LAG = -1.0;
    public static final double NEEDLE_LEAD = 1.0;
    /** 指针满偏对应的功率偏差（kW）。偏离 40000 这么多就是满偏 ±1 */
    public static final double NEEDLE_FULL_SCALE_KW = 8000.0;
    /** 转换器活塞全部接入时总共能把压力压掉多少 PSI（原文每个 600-800） */
    public static final double CONVERTER_PSI_RELIEF = 700.0;

    // ------------------------------------------------------------- 时间尺度
    /**
     * SCRAM 之后到"反应堆刷新为全新"的 tick 数。
     * 原文："The blowout lasts for roughly <b>5 minutes</b> before the security door
     * (which closes immediately after the explosion) opens up to a refreshed reactor."
     * 5 分钟 = 6000 tick。
     */
    public static final int SCRAM_BLOWOUT_TICKS = 6000;
    /** 熔毁之后到刷新的 tick 数。原文只说 "After a few minutes" */
    public static final int MELTDOWN_REGEN_TICKS = 3600;
    /**
     * 爆堆时"控制棒掉落 → 通量反冲"的持续 tick。
     * <p>
     * ★★ 这是整个事故里最反直觉的一段，也是它之所以致命的原因：
     * SCRAM 让棒掉下去，但通量不降反升，压力<b>继续往上冲</b>。
     * 原文："Once the SCRAM is activated, the control rods will drop, however,
     * the neutron flux will cause the pressure to spike even higher,
     * exploding the reactor."
     * <p>
     * 所以玩家在 SCRAM 之后的这几秒里是<b>救不了</b>的 —— 只能眼看着它冲到 4000。
     * 这个"来不及了"必须给足，否则爆堆就退化成一个可以抢救的普通事故。
     */
    public static final int BLOWOUT_BACKLASH_TICKS = 100;

    // ------------------------------------------------------------ 控制棒失效
    /**
     * 单次移动控制棒发生卡棒的概率（<b>逐根、逐 tick</b> 判）。
     * <p>
     * 原文："When moving the rods, a control rod failure can occur, resulting in the
     * rod becoming jammed. It is currently unknown what causes this to occur."
     * 成因不明 → 那就做成随机。
     * <p>
     * ★★ <b>这个数必须按"一次完整启动要判多少次"来定，不能凭感觉。</b>
     * 它是全项目里最容易标错的概率，因为乘数有三层：
     * 每一 tick 判一次 × 9 根棒 × 而"连续移动"下玩家每 tick 都在推杆。
     * 于是"每 tick 至少卡一根"的概率是 {@code 1-(1-p)^9}，再乘上
     * 一次启动需要的 109 tick（从 100% 拔到 35% 是 65 个点、0.6/tick）：
     * <pre>
     *   p = 0.0045  ->  109 tick 内卡棒概率 <b>98.8%</b>   ← 初版取值
     *   p = 0.0008  ->  109 tick 内卡棒概率 50%
     *   p = 0.00018 ->  109 tick 内卡棒概率 15%    ← 现值
     * </pre>
     * 初版那个 0.0045 的后果是<b>灾难性</b>的：几乎每一次启动都会卡棒。
     * 而卡棒会把插入度变化率乘上 {@link #ROD_JAM_RATE_MULT}（0.08），
     * 平均插入度于是被拖在一个永远下不去的区间（实测停在 49.1%），
     * 玩家<b>根本拔不到攻略要求的 35% 运行位</b>，而且没有任何报错 ——
     * 机器照转、压力照涨、温度照升，就是摸不到那个点。
     * <p>
     * 0.00018 的体感：正常运营十来次启动撞上一次，撞上了要连打
     * {@link #ROD_JAM_CLEAR_SCRAMS} 次 SCRAM 才修好 —— 是个"少见但确实会发生"的
     * 真实麻烦，而不是每次开机都来一次的例行公事。
     */
    public static final double ROD_JAM_CHANCE = 0.00018;
    /**
     * 卡住的棒"还能动但极慢"的倍率。
     * 原文："Moving the rod still causes a raise in Neutron Flux, but very slowly."
     */
    public static final double ROD_JAM_RATE_MULT = 0.08;
    /**
     * 修一根卡棒的代价：连续 SCRAM 几次。
     * 原文："The easiest way to fix a Control Rod Failure is repeatedly putting the
     * reactor into SCRAM."
     * <p>
     * 做成"需要连打 N 次 SCRAM"而不是"打一次就好"，是为了让"卡棒"真的有代价 ——
     * 每次 SCRAM 都会把功率清零、要重新拔棒重启，而重启路上又要过 420°C 那道
     * 沉默保护。所以卡一根棒 = 停机 + 重启一轮。
     */
    public static final int ROD_JAM_CLEAR_SCRAMS = 3;

    // ---------------------------------------------------------------- 完整性
    /**
     * 容器完整性（%）。只有压力进红区、或者温度越过熔毁线才掉。
     * 归零 → 熔毁。这是 F9 里唯一"真正不可逆"的量。
     */
    public static final double INTEGRITY_MAX = 100.0;
    /** 低于此值即熔毁 */
    public static final double INTEGRITY_MELTDOWN = 0.0;
    /** 压力越过 {@link #PSI_DANGER} 之后，每 tick 每超出 100 PSI 掉多少完整性 */
    public static final double INTEGRITY_DROP_PER_100PSI = 0.012;
    /** 温度越过 {@link #TEMP_VESSEL_SCRAM} 之后，每 tick 每超出 100°C 掉多少完整性 */
    public static final double INTEGRITY_DROP_PER_100C = 0.02;

    // ------------------------------------------------------------------ 事件
    // 内核只产键；翻译成广播文本 / 音效 / 字幕是 MC 层（TileF9Controller）的事。
    public static final String EV_ROD_MOVING = "rod_moving";
    public static final String EV_ROD_JAMMED = "rod_jammed";
    public static final String EV_ROD_CLEARED = "rod_cleared";
    public static final String EV_SCRAM = "scram";
    public static final String EV_TEMP_ALARM = "temp_alarm";
    public static final String EV_PSI_DANGER = "psi_danger";
    public static final String EV_PSI_SAFE_AGAIN = "psi_safe_again";
    public static final String EV_SAFETY_DISABLED = "safety_disabled";
    public static final String EV_SAFETY_ENABLED = "safety_enabled";
    public static final String EV_SYNC_OK = "sync_ok";
    public static final String EV_SYNC_FAIL_NEEDLE = "sync_fail_needle";
    public static final String EV_SYNC_FAIL_KW = "sync_fail_kw";
    public static final String EV_SYNC_FAIL_RODS = "sync_fail_rods";
    public static final String EV_SYNC_FAIL_VALVES = "sync_fail_valves";
    public static final String EV_DESYNC = "desync";
    public static final String EV_BLOWOUT = "blowout";
    public static final String EV_MELTDOWN = "meltdown";
    public static final String EV_REGEN = "regen";
    public static final String EV_SHUTDOWN_DENIED_POWER = "shutdown_denied_power";
    public static final String EV_SHUTDOWN_DENIED_TEMP = "shutdown_denied_temp";
    public static final String EV_SHUTDOWN_OK = "shutdown_ok";
    public static final String EV_THERMAL_LOCKED = "thermal_locked";
    public static final String EV_FLUX_HIGH = "flux_high";

    // ------------------------------------------------------- SCRAM 触发原因
    // ★★ 每一条原因都必须能单独报到玩家面前，不许笼统一句"安全系统动作了"。
    //   这是本项目反复确立的约定：故障必须自报成因。
    //   玩家看到 SCRAM 后第一句话应该是"因为压力太高"，而不是去猜。
    /** 高压：越过 {@link #PSI_SCRAM} */
    public static final String SCRAM_PSI = "psi";
    /** 高通量：越过 {@link #FLUX_SCRAM} */
    public static final String SCRAM_FLUX = "flux";
    /** 高热功率：越过 {@link #KW_SCRAM} */
    public static final String SCRAM_KW = "kw";
    /**
     * 容器高温：越过 {@link #TEMP_SCRAM}。
     * 这一条<b>会</b>报出来（有 "high vessel temperature" 的告警）。
     */
    public static final String SCRAM_TEMP = "temp";
    /**
     * 容器水温保护：越过 {@link #TEMP_VESSEL_SCRAM}（420°C）。
     * ★ 这一条<b>不报</b> —— 维基明确说 "there is no annunciator for high vessel
     * water temperature"。它是个沉默的保护，玩家只会看到"机器莫名其妙停了"。
     * 所以它和 {@link #SCRAM_TEMP} 必须是两个不同的原因码。
     */
    public static final String SCRAM_VESSEL = "vessel";
    /** 爆堆时的 SCRAM（棒落下但通量反冲） */
    public static final String SCRAM_BLOWOUT = "blowout";

    /**
     * 状态码 -> 供 GUI / 命令用的英文短名。语言文件里查 {@code f9.state.<name>}。
     */
    public static String stateName(int state) {
        switch (state) {
            case ST_COLD:
                return "cold";
            case ST_RUNNING:
                return "running";
            case ST_SYNCED:
                return "synced";
            case ST_SCRAMMED:
                return "scrammed";
            case ST_MELTDOWN:
                return "meltdown";
            case ST_BLOWN:
                return "blown";
            default:
                return "unknown";
        }
    }

    /** 这个状态是不是"已经没法再抢救了"的终态（要等刷新） */
    public static boolean isTerminal(int state) {
        return state == ST_MELTDOWN || state == ST_BLOWN;
    }

    /** 这个状态算不算"反应堆在热着"（余热还在 → 还能出事） */
    public static boolean isHot(int state) {
        return state == ST_RUNNING || state == ST_SYNCED || state == ST_SCRAMMED;
    }

    /** 这个状态是不是已经接上电网在供电 */
    public static boolean isOnline(int state) {
        return state == ST_SYNCED;
    }

    /** 转换器 i 的位 */
    public static int converterBit(int index) {
        return CONVERTER_BIT << clampIndex(index, CONVERTER_COUNT);
    }

    /** 泄压阀 i 的位 */
    public static int valveBit(int index) {
        return VALVE_BIT << clampIndex(index, VALVE_COUNT);
    }

    private static int clampIndex(int index, int count) {
        if (index < 0) {
            return 0;
        }
        if (index >= count) {
            return count - 1;
        }
        return index;
    }

    /**
     * 一根控制棒属于哪个分区。
     * <p>
     * 前 3 根归 0 区、中间 3 根归 1 区、后 3 根归 2 区 ——
     * 分区在几何上对应堆体的三段，泄压阀按分区泄压（对应 QSERF 的 "selected sector"）。
     */
    public static int sectorOfRod(int rodIndex) {
        int s = rodIndex / RODS_PER_SECTOR;
        if (s < 0) {
            return 0;
        }
        return s >= SECTOR_COUNT ? SECTOR_COUNT - 1 : s;
    }

    /**
     * 同步指针读数。
     * <p>
     * 返回 -1..+1 之间的值：<b>负 = Lag = 压力太低</b>，<b>正 = Lead = 压力太高</b>。
     * 定义取自 QSERF 攻略："The synchroscope moving in the 'Lag' direction means your
     * pressure is too low, and the 'Lead' direction means the pressure is too high."
     * <p>
     * ★ 单位说明：指针反映的是"与电网 60Hz 的相位差"，而相位差在数值上正比于
     * <b>热功率相对额定值的偏差</b> —— 功率偏高则发电机跑得快、相位超前（Lead）。
     * 所以这里用 (功率 - 额定) / 满偏 来换算，而不是直接用压力（压力只是它的因）。
     */
    public static double needle(double kw) {
        double v = (kw - KW_RATED) / NEEDLE_FULL_SCALE_KW;
        if (v < NEEDLE_LAG) {
            return NEEDLE_LAG;
        }
        if (v > NEEDLE_LEAD) {
            return NEEDLE_LEAD;
        }
        return v;
    }

    /**
     * 热功率是否落在可同步窗口内。
     * <p>
     * ★ 注意这里用的是<b>区间判定</b>（39000..41000），和指针判定是两个独立的关卡。
     * 按 QSERF 攻略，同步失败时玩家要看的就是这个区间：
     * "if its below 39000 raise the rods on Control Rod speed 1x if above 41000
     * open relief valves"。所以这两个数必须原样保留，且要能让玩家在界面上看到。
     */
    public static boolean kwInSyncWindow(int kw) {
        return kw >= SYNC_KW_MIN && kw <= SYNC_KW_MAX;
    }
}

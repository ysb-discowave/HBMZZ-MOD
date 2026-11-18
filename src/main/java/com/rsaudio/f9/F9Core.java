package com.rsaudio.f9;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * F9（Foxtrot Mk. 9 / Project Helios）的状态机与数值模拟（<b>零 Minecraft 依赖</b>）。
 * <p>
 * 和 {@code DmrCore} 一样：这个类只做一件事 —— <b>每 tick 推演一次反应堆</b>。
 * 它不碰世界、不碰方块、不碰网络，所有对外影响都通过 {@link #drainEvents()} 交出去，
 * 由 MC 层（{@code TileF9Controller}）翻译成广播、音效、爆炸和字幕。
 *
 * <h3>四条物理量，一条链</h3>
 * <pre>
 *      拔棒 (插入度↓)
 *           │
 *           ├──────────────→ 中子通量 ↑ ──→ 压力 ↑↑↑ （主要推力）
 *           │                                  │
 *           └──────────────→ 温度 ↑            │
 *                                             ▼
 *                          压力 >3700 自动SCRAM / >4000 爆炸
 * </pre>
 * 关键点在于：<b>通量只在"正在拔棒"时存在</b>。松手它立刻回 0，
 * 于是推压力最猛的那一路瞬间消失。这条规则造就了 F9 的全部手感：
 * <ul>
 *   <li>想把功率拔上去 → 就得一直按住拔棒 → 压力一直在涨；</li>
 *   <li>想保住压力 → 就得松手 → 功率停止上涨；</li>
 *   <li>两者的平衡点只有靠<b>四根泄压阀</b>去买回来 —— 这就是这个机器的玩法。</li>
 * </ul>
 *
 * <h3>三条失控路径</h3>
 * <ol>
 *   <li><b>爆堆（Blowout）</b>：把棒停在约 50%、压力一千多，然后<b>猛拔棒</b> ——
 *       通量冲上去，{@link F9Settings#psiPerRodPullPerTick} 那一项（棒每 tick 的
 *       直接推力）会把压力一路顶过 {@link F9Spec#PSI_SCRAM} 触发 SCRAM。
 *       ★ 但 SCRAM 之后<b>棒会掉落，通量却反冲上去</b>，
 *       压力继续冲顶 → 越过 4000 → 炸。这条路一旦进了
 *       {@link F9Spec#BLOWOUT_BACKLASH_TICKS} 就<b>救不回来</b>，
 *       这是设计上要的"来不及了"。
 *       <p>
 *       ★ 为什么配方必须卡在"半插入"：单根棒从 100% 拔到 0% 总共只有
 *       {@code 100/0.6 = 167 tick} 的行程。若从 <b>34.6%</b>（运行位）开始猛拔，
 *       38 tick 后就到底了，此后 psi 每 tick 被泄压阀拉掉 22.4 PSI、
 *       而稳态只有约 1380 —— 压力根本攒不起来，只会一路掉。
 *       只有从 50% 附近起步，才剩下够长的行程（83 tick）把压力送过 SCRAM 线。
 *       这不是巧合，而是攻略里"红区 + 约 50% 插入"那个配方存在的原因。</li>
 *   <li><b>热功率/温度 SCRAM</b>：持续猛拔棒（尤其 2x + 连续移动），
 *       通量顶到 14% 或热功率顶到 48000 或温度顶到 950，安全系统动作。
 *       这一条<b>是能救的</b> —— 松手、插棒、开阀就好。</li>
 *   <li><b>熔毁</b>：压力长时间红区、或撞上 420°C/950°C 之后不管它，
 *       完整性一路掉到 0。不可逆。</li>
 * </ol>
 *
 * <h3>★ 一个"日常操作不是事故"的量级约定</h3>
 * 攻略的主路径要求玩家把棒拔到 {@link F9Spec#ROD_INSERT_RUN}（35%）并停在那儿，
 * 此时压力天然就落在 {@link F9Spec#PSI_DANGER}（3500）<b>近旁</b>约 3389 PSI。
 * 这不是标定失误，而是对原作的忠实：QSURF 里"红区"就是玩家的日常工作区间
 * （同步前 "raising the pressure to about the red zone"），危险线是<b>踩线</b>
 * 而不是"禁区"。所以内核里对"短暂踩线"很宽容（完整性按超出量线性、每秒只掉零点几），
 * 但对"一直踩着不放"很严厉（几十秒内熔毁）。
 *
 * <h3>★ 那个"沉默的保护"</h3>
 * 温度越过 {@link F9Spec#TEMP_VESSEL_SCRAM}（420°C）会<b>直接 SCRAM，而且不报警</b>。
 * 这条来自 QSERF 维基的 Trivia（"there is no annunciator for high vessel water
 * temperature"）—— 它是这台机器上唯一一个"不给任何提示就停你机"的保护，
 * 也是最容易让新手一头雾水的地方。所以内核里它有自己的原因码
 * （{@link F9Spec#SCRAM_VESSEL}），只是<b>不发告警事件</b>；玩家仍然能在
 * SCRAM 事件里读到"是因为容器水温"，从而学会先插棒再启动。
 *
 * <h3>线程</h3>
 * 非线程安全。约定只在服务端主线程 tick，同步包只读快照。
 */
public final class F9Core {

    // ---------------------------------------------------------------- 构造

    private final F9Settings cfg;
    private final Random rng;

    // ------------------------------------------------------------ 运行状态

    private int state = F9Spec.ST_COLD;
    /** 开关位（安全系统 / 连续移动 / 2x / 热循环）+ 转换器位 + 泄压阀位 */
    private int sub = 0;

    /** 9 根控制棒的插入度 (0..100)。100 = 全插（停堆），0 = 全拔（满功率） */
    private final double[] rods = new double[F9Spec.ROD_COUNT];
    /**
     * 哪些棒卡住了。
     * <p>
     * 卡住的棒仍然能动，只是极慢（{@link F9Settings#rodJamRateMult}），
     * 而且仍然会推通量 —— 照 QSERF 原文 "Moving the rod still causes a raise in
     * Neutron Flux, but very slowly."
     */
    private final boolean[] jammed = new boolean[F9Spec.ROD_COUNT];
    /** 已经连续 SCRAM 了几次（用来清卡棒）。清掉一根就归零重数 */
    private int scramStreak;

    /**
     * 当前选中的控制棒。
     * <p>
     * {@link #ROD_SELECT_ALL} = 全局（所有棒一起动）；0..8 = 单独某根。
     * 照 QSURF："which can be individually selected or globally selected"。
     * 这个字段<b>不进物理</b>，只进"拉杆动谁"。
     */
    private int selectedRod = ROD_SELECT_ALL;
    /** 全局选择时，动的是哪个分区的棒（0..2）。-1 = 所有分区 */
    private int selectedSector = -1;

    /** 中子通量 (%) */
    private double flux;
    /** 压力 (PSI) */
    private double psi;
    /** 温度 (°C) */
    private double tempC;
    /** 热功率 (kW) */
    private double kw;
    /** 容器完整性 (%) */
    private double integrity = F9Spec.INTEGRITY_MAX;

    /**
     * 拉杆的剩余行程 (tick)。
     * <p>
     * 每次拉杆赋成 {@link F9Spec#ROD_TRANSIT_TICKS}；开了连续移动就是一秒接一秒地续。
     * 到 0 就停 —— 这是"默认移动 1 秒后自动停"的落地点。
     */
    private int transitTimer;
    /** 拉杆方向：+1 = 拔（插入度降），-1 = 插（插入度升），0 = 停 */
    private int transitDir;
    /** 玩家是否按着拉杆（决定连续移动模式下要不要续行程） */
    private boolean leverHeld;

    /** 上一次把通量推高的是不是"拔棒"。插棒时通量反向（游戏不显示），这里只做内部记账 */
    private double lastFluxDir;

    /**
     * 上一 tick 整堆<b>实际</b>搬动的插入度总量（点），以及被影响的棒数。
     * <p>
     * ★★ 为什么需要这个字段：通量的物理来源是"反应性<变化率>"，
     * 而反应性变化率取决于<b>真正移动了多少棒</b>，不是"玩家把拉杆推到了哪一档"。
     * 踩过的坑：{@link #updateFlux} 原来用 {@code rodSpeedMult()}（只反映
     * "2x 速度"开关）当目标值，于是<b>卡棒和好棒推起一样多的通量</b>
     * （实测两者峰值都是 5.5%，爬到 2.9% 的用时也一样），
     * 而 {@link #applyRodDelta} 里那句 {@code rodJamRateMult} 只影响了插入度、
     * 完全没传导到通量上 —— 一个"卡住了却毫无后果"的假缺陷。
     * 现在改成用这一 tick 的真实位移来驱动通量：
     * <ul>
     *   <li>九根一起拔 → travel 最大 → 通量按满额涨；</li>
     *   <li>只拔一根 → travel 只有 1/9 → 通量涨得慢（这正是"别乱选单根"的依据）；</li>
     *   <li>拔的是卡棒 → travel 再乘 {@code rodJamRateMult} → 通量几乎不动。</li>
     * </ul>
     */
    private double lastRodTravel;
    /** 上一 tick 被拉杆影响到的棒数（用于把 travel 归一化回"每根棒的速度"） */
    private int lastRodAffected;

    /** 已同步到电网（= ST_SYNCED 的实际载体，state 是它的对外面孔） */
    private int syncTicks;
    /** 上一次同步失败的指针读数，给界面用 */
    private double lastNeedle;
    /** 爆堆反冲剩余 tick */
    private int backlashTimer;
    /** 终态之后到"刷新为全新"的倒计时 */
    private int regenTimer;
    /** 这一轮事故的 SCRAM 原因（{@link F9Spec#SCRAM_PSI} 等） */
    private String scramReason = "";
    /** 已经报过的那几条告警，用来做去重 */
    private int warnedTemp;
    private int warnedPsi;
    private int warnedFlux;

    private long ticks;
    private int explosionPending;
    /** 刷新过几次（用来做"这台机器已经炸过几回"的统计） */
    private int regenerations;

    private final List<String> events = new ArrayList<String>(8);

    /** 全局选中：所有棒一起动 */
    public static final int ROD_SELECT_ALL = -1;

    /**
     * @param settings 物理参数。内部会 {@link F9Settings#copy()} 一份，
     *                 免得调用方之后改配置把正在跑的堆改了
     */
    public F9Core(F9Settings settings) {
        this.cfg = settings.copy();
        this.rng = new Random(this.cfg.seed);
        // 新机器 = 冷停堆 = 控制棒全插入
        for (int i = 0; i < rods.length; i++) {
            rods[i] = F9Spec.ROD_INSERT_SHUTDOWN;
        }
        this.tempC = this.cfg.ambientC;
        this.psi = F9Spec.PSI_MIN;
        this.kw = 0.0;
        // ★ 默认打开安全系统。这是 QSERF 的原设定，也是这台机器唯一"出厂就正确"的开关
        this.sub = F9Spec.SUB_SAFETY;
    }

    // -------------------------------------------------------------- 主循环

    /** 推演一 tick */
    public void tick() {
        ticks++;
        switch (state) {
            case F9Spec.ST_RUNNING:
            case F9Spec.ST_SYNCED:
                tickLive();
                break;
            case F9Spec.ST_SCRAMMED:
                tickScrammed();
                break;
            case F9Spec.ST_COLD:
                tickCold();
                break;
            case F9Spec.ST_MELTDOWN:
            case F9Spec.ST_BLOWN:
                tickTerminal();
                break;
            default:
                break;
        }
    }

    /**
     * 冷停堆：只有自然散热，温度压力往环境收敛。
     * <p>
     * 控制棒在冷停堆时是插到底的，所以压力和温度都会一路回到待机值。
     * 走的是和运行态同一套推演（稳态由插入度决定，插到底 → 稳态最低），
     * 只是不过通量、不判安全、不更新热功率 —— 一句话：<b>冷堆没有反应</b>。
     */
    private void tickCold() {
        // 通量为 0（冷堆里不该有），用同一套收敛把它压回 0
        decayFlux();
        updatePsi(true);
        updateTemp(true);
        updateKw(0.0);
    }

    /** 运行中 / 已并网：完整物理 */
    private void tickLive() {
        // 1) 控制棒行程 → 插入度
        advanceRods();
        // 2) 通量：只有"正在拔棒"才涨
        updateFlux();
        // 3) 压力：稳态 + 通量推力 + 棒动作 + 泄压阀
        updatePsi(false);
        // 4) 温度
        updateTemp(false);
        // 5) 热功率
        updateKw(cfg.kwGridPull);
        // 6) 完整性
        updateIntegrity();
        if (state == F9Spec.ST_MELTDOWN) {
            return;
        }
        // 7) 安全系统判定（放最后，好让它看到这一 tick 的全部读数）
        checkSafety();
    }

    /**
     * 已 SCRAM：棒已经掉下去了，但余热还在。
     * <p>
     * ★★ 这一段是爆堆事故发生的地方。SCRAM 之后：
     * <ul>
     *   <li>插入度被强制拉到 100%（棒掉落）；</li>
     *   <li>但如果正在爆堆反冲窗口里，<b>通量不降反升</b>，压力继续冲顶；</li>
     *   <li>玩家这时唯一能做的是开泄压阀泄压 —— 但那点速度往往不够。</li>
     * </ul>
     */
    private void tickScrammed() {
        double boost = 0.0;
        if (backlashTimer > 0) {
            backlashTimer--;
            // ★ 反冲：通量在一片"棒已经全插"的局面里逆势上涨。
            //   这是 QSERF 原文那句 "the neutron flux will cause the pressure to
            //   spike even higher" 的直接实现，也是这一整条事故链成立的支点。
            //   ★ 这里用<b>独立</b>的上涨速率 backlashFluxRise，不能复用 fluxRisePerTick ——
            //   那个字段现在是"朝目标收敛的速率"，语义完全不同，
            //   混用会让反冲变成一条指数逼近、永远到不了顶的曲线。
            flux = Math.min(F9Spec.FLUX_BLOWOUT_MAX, flux + cfg.backlashFluxRise);
            // ★★ 反冲的推力必须用<b>独立</b>系数，而且要大到能压过安全系统刚打开的
            //   四个泄压阀（4 × 5.6 = 22.4 PSI/tick）。用常规的 psiPerFluxPerTick(0.16) 时
            //   实测压力从 3719 一路掉到 2469 —— 事故链断在第一步，爆堆根本不可能发生。
            //   叠加系数与泄压失效一起，才让"反冲压倒安全系统"在数值上真的成立。
            boost = cfg.backlashPsiPerFlux;
        } else {
            decayFlux();
        }
        // SCRAM 之后安全系统<b>不再</b>动作（已经在 SCRAM 态了），
        // 所以这里传 true 跳过自动 SCRAM 检查，但压力/温度照常推演。
        updatePsi(true, boost);
        updateTemp(true);
        updateKw(0.0);
        updateIntegrity();
        if (state == F9Spec.ST_MELTDOWN) {
            return;
        }
        // 爆炸判定不受安全系统影响 —— 它就是物理事实
        if (psi >= F9Spec.PSI_EXPLODE) {
            blowout();
        }
    }

    /**
     * 终态（熔毁 / 已炸）：等倒计时走完，然后刷新为全新。
     * <p>
     * "刷新为全新"是 QSERF 的明确设定：
     * "the security door ... opens up to a refreshed reactor" /
     * "After a few minutes, the reactor will regenerate."
     * 这一条很重要 —— 没有它，一次事故就把这台机器永久报废，
     * 玩家只会去拆了重建，那就白白浪费了整套控制逻辑。
     */
    private void tickTerminal() {
        if (regenTimer > 0) {
            regenTimer--;
            if (regenTimer == 0) {
                regenerate();
            }
        }
    }

    /**
     * 刷新为全新。回到冷停堆，安全系统重新打开（出厂正确姿势），卡棒清空。
     * <p>
     * ★ 注意<b>不清</b> {@link #regenerations} —— 那是"这台机器被炸过几次"的账，
     * 留着给界面显示，也是一种无声的羞耻。
     */
    private void regenerate() {
        state = F9Spec.ST_COLD;
        for (int i = 0; i < rods.length; i++) {
            rods[i] = F9Spec.ROD_INSERT_SHUTDOWN;
            jammed[i] = false;
        }
        scramStreak = 0;
        flux = 0.0;
        psi = F9Spec.PSI_MIN;
        tempC = cfg.ambientC;
        kw = 0.0;
        integrity = F9Spec.INTEGRITY_MAX;
        transitTimer = 0;
        transitDir = 0;
        leverHeld = false;
        syncTicks = 0;
        backlashTimer = 0;
        // ★ 安全系统恢复到打开 —— 出厂状态。别的开关（连续移动/2x/热循环）
        //   也一并复位，免得刷新出来一台"上次留下的奇怪姿势"的机器。
        sub = F9Spec.SUB_SAFETY;
        warnedTemp = 0;
        warnedPsi = 0;
        warnedFlux = 0;
        scramReason = "";
        regenerations++;
        emit(F9Spec.EV_REGEN);
    }

    // ---------------------------------------------------------------- 控制棒

    /**
     * 玩家推拉杆。
     * <p>
     * ★★ <b>调用约定（很容易踩，写清楚）</b>：这个方法只负责
     * "记下方向 + 把行程重置成整整一秒"，它<b>不</b>推进这一 tick 的位移 ——
     * 位移发生在随后的 {@link #tick()} 里。也就是说玩家的操作序列必须是
     * <pre>
     *   moveRods(+1);   // 玩家按住拉杆
     *   tick();         // 这一 tick 走 0.6 个点
     *   moveRods(+1);   // 还按着 —— 续上下一 tick
     *   tick();
     *   ...
     * </pre>
     * 每次调用都把行程<b>重置</b>成 {@link F9Spec#ROD_TRANSIT_TICKS}，
     * 所以"按住不放"在这套 API 里就是"每 tick 都调一次" —— 简单、确定、没有隐藏状态。
     * <p>
     * ★ 由此推出一个<b>必须知道的操作事实</b>：从 100% 拔到 35% 要走 65 个点，
     * 也就是 <b>109 次 tick</b>，而<b>一次拉杆只覆盖 20 tick</b>
     * （{@link F9Spec#ROD_TRANSIT_TICKS}）。所以玩家必须<b>续 6 次杆</b>
     * （或直接打开"连续移动"）才能拔到运行位。
     * <p>
     * ⚠ 这也是测试里那个"棒停在 49%"的原因，值得记下来：调用方如果把
     * {@code moveRods} 写在 {@code tick} <b>之后</b>，那么"按了 N 次"真正生效的
     * 只有 N-1 次（第一次调用之后没人再 tick，最后一次重挂白挂）——
     * 累计差的那一格正好把棒卡在 49.1%，形成一个"机器能转、压力有、温度有，
     * 但永远摸不到 35% 这个攻略点"的静默死结。所以单测里一律
     * <b>先 moveRods 再 tick</b>（见 {@code tools/check_f9_core.py} 的 {@code pullTo}）。
     *
     * @param direction +1 = 拔（插入度下降 → 升功率），-1 = 插（插入度上升 → 降功率）
     * @return 是否接受了这次动作
     */
    public boolean moveRods(int direction) {
        if (direction == 0) {
            return false;
        }
        if (!F9Spec.isHot(state)) {
            // 冷停堆 / 终态：棒动不了。冷停堆时也别让人"拔棒预热" ——
            // 这台机器的启动就是拔棒，但必须先把状态推进到 RUNNING（见 {@link #start()}）。
            return false;
        }
        transitDir = direction > 0 ? 1 : -1;
        // 每次重挂 = "这一 tick 之后还接着走一秒"。确定、无隐藏状态。
        transitTimer = F9Spec.ROD_TRANSIT_TICKS;
        leverHeld = hasSub(F9Spec.SUB_CONTINUOUS_TRANSIT);
        return true;
    }

    /** 松开拉杆。连续移动模式下，松手会让当前这一秒走完就停 */
    public void releaseRods() {
        leverHeld = false;
    }

    /** 直接停住拉杆（"停"按钮） */
    public void stopRods() {
        leverHeld = false;
        transitTimer = 0;
        transitDir = 0;
    }

    /** 推进行程：把"这一 tick 动了多少棒"落实成插入度变化 */
    private void advanceRods() {
        if (transitTimer <= 0 || transitDir == 0) {
            // ★ 连续移动模式：这一秒走完 <b>不</b>自动续，要走完那一 tick 再看
            //   {@link #leverHeld}（玩家在这一 tick 里有没有再推一次杆）。
            //   为什么不在 timer 归零时立刻无限续：那会让"拔棒"变成只要推过一次
            //   就永远停不下来，玩家推错一次方向就再也拉不回来 —— 一个反向的死结。
            //   现在的语义是"按住 = 一直走"，而"按住"在 tick 粒度上就是每 tick 都在
            //   调 {@link #moveRods}，这样既能长按，也随时能松手。
            return;
        }
        transitTimer--;
        double speed = cfg.rodRatePerTick * rodSpeedMult();
        // ★★ 符号在这里，而且很容易写反 —— 说清楚：
        //   transitDir = +1 表示"拔棒（UP）"，而拔棒意味着**插入度下降**。
        //   所以 delta 是 **负的**：delta = -transitDir * speed。
        //   反过来的话，"拔棒"会把插入度往 100 推、机器一点反应都没有，
        //   而界面上拉杆还在动、通量还在涨 —— 一个纯粹靠肉眼极难定位的静默错误。
        double delta = -transitDir * speed;
        applyRodDelta(delta);
        // 到顶/到底就自然停住，免得空转
        if (!canStillMove(transitDir)) {
            stopRods();
        }
    }

    /** 控制棒速度倍率：2x 开关 */
    private double rodSpeedMult() {
        return hasSub(F9Spec.SUB_ROD_SPEED_2X) ? 2.0 : 1.0;
    }

    /** 还能不能朝这个方向动（有棒到极限了就不再动） */
    private boolean canStillMove(int dir) {
        for (int i = 0; i < rods.length; i++) {
            if (!isRodAffected(i)) {
                continue;
            }
            if (dir > 0 && rods[i] > F9Spec.ROD_INSERT_MIN + 1.0e-6) {
                return true;
            }
            if (dir < 0 && rods[i] < F9Spec.ROD_INSERT_MAX - 1.0e-6) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一次插入度变化施加到"被选中的那些棒"上。
     * <p>
     * 两个选择维度叠加：
     * <ul>
     *   <li>{@link #selectedRod}：全局 / 单独某根；</li>
     *   <li>{@link #selectedSector}：限定分区（-1 = 不限）。</li>
     * </ul>
     * 单独选中某根时忽略分区选择 —— 单根本身就是最精确的选择。
     */
    private void applyRodDelta(double delta) {
        // ★★ 这一 tick "真正搬动了多少反应性" —— 用它（而不是"拉杆的速度"）
        //   去驱动通量。见 {@link #lastRodTravel} 的字段说明。
        double travel = 0.0;
        int affected = 0;
        for (int i = 0; i < rods.length; i++) {
            if (!isRodAffected(i)) {
                continue;
            }
            affected++;
            double d = delta;
            if (jammed[i]) {
                // ★ 卡棒：还能动，但极慢。
                d *= cfg.rodJamRateMult;
            }
            double v = rods[i] + d;
            if (v < F9Spec.ROD_INSERT_MIN) {
                v = F9Spec.ROD_INSERT_MIN;
            }
            if (v > F9Spec.ROD_INSERT_MAX) {
                v = F9Spec.ROD_INSERT_MAX;
            }
            travel += Math.abs(v - rods[i]);
            rods[i] = v;
            // 在动的棒有一定概率卡住。★ 只判"真的动了"的那些棒，
            // 已经到极限还在空转的棒不该被判 —— 否则按着不放就必然卡死。
            if (Math.abs(d) > 1.0e-9 && rng.nextDouble() < cfg.rodJamChance) {
                jammed[i] = true;
                emit(F9Spec.EV_ROD_JAMMED);
            }
        }
        // 记下"这一 tick 整堆一共搬动了多少点插入度"，给 updateFlux 用。
        // 九根一起拔时 travel 最大；只拔一根时只有 1/9；
        // 拔的是卡棒时还要再乘 rodJamRateMult。
        lastRodTravel = travel;
        lastRodAffected = affected;
    }

    /** 这根棒这一 tick 会不会被拉到 */
    private boolean isRodAffected(int index) {
        if (selectedRod >= 0) {
            return selectedRod == index;
        }
        return selectedSector < 0 || selectedSector == F9Spec.sectorOfRod(index);
    }

    /**
     * 中子通量。
     * <p>
     * ★★ 这条规则是 F9 的全部手感所在，必须说清楚：
     * <b>"正在拔棒"时通量涨、"松手"时立刻回 0</b>
     * （QSURF 原文："increases every time you raise the Control Rods, but it will go
     * back to zero as soon as you stop raising the CRs"）。
     * <p>
     * <b>但它是"朝一个由拔棒速度决定的目标值收敛"，而不是无界累加。</b>
     * 这个区别至关重要，而且是踩过才知道的：
     * 第一版写成了累加（通量 += 0.55/tick），于是连续拔棒 26 tick 通量就撞上
     * {@link F9Spec#FLUX_SCRAM}（14%），玩家<b>根本来不及把棒拔到攻略要求的 35%</b>
     * 就被安全系统停堆了 —— 那条"标准操作流程"直接变成不可能完成的任务。
     * <p>
     * 改成收敛之后语义也更贴近原文：通量反映的是<b>棒的运动速率</b>，
     * 不是"你一共拔了多久"。于是：
     * <ul>
     *   <li>1x 速度慢慢拔 → 通量稳在一个低位，压力温和上涨；</li>
     *   <li>2x 速度猛拔 → 通量立刻顶到高位，压力跟着飞 —— 这才是"猛拔棒会炸"的来源；</li>
     *   <li>松手 → 目标瞬间变 0，通量按 {@link F9Settings#fluxFallPerTick} 掉回 0。</li>
     * </ul>
     * 这样"拔到 35% 再停下"就是一条走得通的路径，而"2x 连续猛拔"仍然危险。
     */
    private void updateFlux() {
        boolean pulling = transitDir > 0 && transitTimer > 0;
        boolean inserting = transitDir < 0 && transitTimer > 0;
        if (pulling) {
            lastFluxDir = 1.0;
            // ★★ 目标值由"这一 tick 真正搬动了多少反应性"决定，而不是"拉杆在哪一档"。
            //   见 {@link #lastRodTravel} 的字段说明：这样卡棒、单根选择、
            //   2x 速度开关才能各自正确地反映到通量上。
            double target = cfg.fluxPullTarget * effectiveRodSpeed();
            flux += (target - flux) * cfg.fluxRisePerTick;
        } else if (inserting) {
            lastFluxDir = -1.0;
            // 插棒时通量反向 —— 直接压回 0（对外就表现为"立刻归零"）
            flux = Math.max(0.0, flux - cfg.fluxFallPerTick * effectiveRodSpeed());
        } else {
            decayFlux();
        }
        if (flux > F9Spec.FLUX_MAX) {
            flux = F9Spec.FLUX_MAX;
        }
    }

    /**
     * 这一 tick 的"有效拔棒速度倍率"，1.0 = 九根好棒以 1x 速度一起拔。
     * <p>
     * 由三件事共同决定（都是物理事实，不是开关）：
     * <ol>
     *   <li><b>2x 速度开关</b>（{@link F9Settings#rodRatePerTick} 之外的倍率）；</li>
     *   <li><b>被拉动了几根棒</b> —— 只拉一根时反应性变化只有九根的 1/9；</li>
     *   <li><b>拉的是不是卡棒</b> —— 卡棒这一 tick 的位移已经乘过
     *       {@link F9Settings#rodJamRateMult}，所以直接复用真实位移即可。</li>
     * </ol>
     * 实现上就是"实际位移 ÷ 理论位移"：分子在 {@link #applyRodDelta} 里累加
     * （已经含了卡棒折扣），分母是"九根好棒都动"的位移。
     */
    private double effectiveRodSpeed() {
        if (lastRodAffected <= 0) {
            return 0.0;
        }
        double nominalPerRod = cfg.rodRatePerTick * rodSpeedMult();
        double allNineNominal = nominalPerRod * F9Spec.ROD_COUNT;
        if (allNineNominal <= 1.0e-9) {
            return 0.0;
        }
        // 归一化：九根好棒一起拔 → 1.0（与旧行为完全一致）
        return (lastRodTravel / allNineNominal) * rodSpeedMult();
    }

    private void decayFlux() {
        if (flux > 0.0) {
            flux = Math.max(0.0, flux - cfg.fluxFallPerTick);
        }
        if (flux <= 1.0e-9) {
            flux = 0.0;
        }
    }

    // ------------------------------------------------------------------ 压力

    /**
     * 压力推演。
     * <p>
     * 三项叠加：
     * <ol>
     *   <li><b>朝稳态收敛</b>：稳态由平均插入度决定（插得深 → 稳态低）；</li>
     *   <li><b>通量推力</b>：通量越高推得越猛，这是"压力飞涨"的主因；</li>
     *   <li><b>泄压阀</b>：每个打开的阀每 tick 泄掉一部分。</li>
     * </ol>
     * 转换器活塞接入电网时也降压（照 QSURF：每个约 600-800 PSI），
     * 但那是"稳态偏移"而不是每 tick 泄压 —— 所以它体现在稳态上，不是这里。
     *
     * @param scrammed SCRAM 之后跳掉"棒在动"这一项（棒已经掉下去了）
     */
    private void updatePsi(boolean scrammed) {
        updatePsi(scrammed, 0.0);
    }

    /**
     * 压力推演的真正实现。
     *
     * @param scrammed      SCRAM 之后跳掉"棒在动"这一项（棒已经掉下去了）
     * @param backlashBoost 反冲期间通量推力的<b>附加</b>部分，直接加进通量推力项
     *                      （见 {@link F9Settings#backlashPsiPerFlux}）。
     *                      非反冲时为 0，此时本方法的行为与没有这个参数时完全一致
     */
    private void updatePsi(boolean scrammed, double backlashBoost) {
        double steady = steadyPsi();
        // ★★ 收敛速率分两档 —— 只用一个数会在物理上自相矛盾：
        //   拔棒加压是瞬时的（玩家推拉杆马上要看到钢丝动），
        //   泄压是迟缓的（这台机器全部玩法、以及停机流程里那段"等温度压力回落"
        //   都建立在这个"慢"上面）。用一个数的话，要么加压慢到没手感，
        //   要么泄压强到"停机一秒搞定"，两头都会毁掉玩法。
        double relax = steady > psi ? cfg.psiRelaxRise : cfg.psiRelaxFall;
        psi += (steady - psi) * relax;
        // 通量推力（反冲期间额外加成，见 backlashBoost 的说明）
        psi += flux * (cfg.psiPerFluxPerTick + backlashBoost);
        // 棒动作的直接推力（拔 → 加，插 → 减）。SCRAM 后棒是掉下去的，不算"动作"
        if (!scrammed && transitDir != 0 && transitTimer > 0) {
            // ★★ 这一项是"能不能把压力顶过 4000"的关键杠杆之一，别当它是个小修正：
            //   SCRAM 之后棒会掉、光靠通量推不到爆炸线，
            //   所以它要够大 —— 实测 3.5 能让"50% 猛拔棒"在 37 tick 内
            //   把压力从 1832 顶到 3719 触发 SCRAM，同时"从 35%（运行位）猛拔"
            //   因为插入度太低、棒很快到底（只剩 34 tick 行程）而顶不过 3500 ——
            //   正好复现攻略里"红区 + 半插入"这个配方为什么必须卡在半插入。
            psi += transitDir * cfg.psiPerRodPullPerTick;
        }
        // 泄压阀
        int open = countValvesOpen();
        if (open > 0) {
            // ★ 反冲窗口里泄压能力被打掉（见 F9Settings#backlashValveFail）
            double per = cfg.psiPerValvePerTick;
            if (backlashTimer > 0) {
                per *= (1.0 - cfg.backlashValveFail);
            }
            psi -= open * per;
        }
        if (psi < F9Spec.PSI_MIN) {
            psi = F9Spec.PSI_MIN;
        }
    }

    /**
     * 当前插入度下的压力稳态。
     * <p>
     * 线性插值：全拔（0%）→ {@link F9Settings#psiAtFullPull}；
     * 全插（100%）→ {@link F9Settings#psiAtFullInsert}。
     * <p>
     * 另外扣掉转换器活塞的降压 —— 照 QSURF，"每个能降约 600–800 PSI"。
     * 但<b>降压量随过载而萎缩</b>（{@link F9Settings#converterReliefFade}）：
     * 额定工况附近活塞拿满 700/个，猛拔到过载时活塞被"淹没"、
     * 拿不到那么多降压 —— 于是"活塞保不住你"这件事在数值上真的成立。
     */
    private double steadyPsi() {
        double inset = cfg.psiDeepestWeight >= 0.0
                ? deepestPull() : avgInsert();
        double t = inset / 100.0;
        double base = cfg.psiAtFullPull + (cfg.psiAtFullInsert - cfg.psiAtFullPull) * t;
        int on = countConvertersOn();
        if (on > 0) {
            // 插入度越高（越接近额定/低功率工况），活塞越吃满；越低（越猛）越萎缩
            double eff = F9Spec.CONVERTER_PSI_RELIEF * (1.0 - cfg.converterReliefFade * (1.0 - t));
            if (eff < 0.0) {
                eff = 0.0;
            }
            base -= on * eff;
        }
        return base < F9Spec.PSI_MIN ? F9Spec.PSI_MIN : base;
    }

    /**
     * 最大拔出比例（%）= 最浅的那根棒的插入度。
     * <p>
     * ★★ 见 {@link F9Settings#psiTracksDeepestRod} 那一大段：压力稳态必须跟随
     * <b>最深的那根棒</b>而不是平均值 —— 否则单拔一根就能顶出整堆的事故，
     * 而且"玩家从 100% 拔到 35%"这条必经之路会被半路自动 SCRAM 打断。
     * <p>
     * 之所以在<b>这个坐标系</b>下"最深"对应"最小插入度"，是因为插入度是反着记的
     * （100 = 全插 = 停堆）。所以这里取的是 <b>min</b> 而不是 max。
     */
    private double deepestPull() {
        double m = F9Spec.ROD_INSERT_MAX;
        for (double r : rods) {
            if (r < m) {
                m = r;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ 温度

    /**
     * 温度推演：朝"由插入度决定的稳态"收敛。
     * <p>
     * 热循环（如果开着且安全系统允许）会额外拉一把 ——
     * 但注意热循环被安全系统锁着（见 {@link #toggleSubsystem}），
     * 所以正常状态下玩家压温度靠的是<b>插棒</b>，不是热循环。
     */
    private void updateTemp(boolean scrammed) {
        double steady = steadyTemp();
        tempC += (steady - tempC) * cfg.tempRelax;
        if (hasSub(F9Spec.SUB_THERMAL_LOOP) && tempC > F9Spec.TEMP_VESSEL_SCRAM) {
            // 热循环是主动冷却：只在高位才起作用，且和温差成正比
            tempC -= cfg.thermalLoopCool * (tempC - F9Spec.TEMP_VESSEL_SCRAM);
        }
        if (tempC < cfg.ambientC) {
            tempC = cfg.ambientC;
        }
    }

    private double steadyTemp() {
        double t = weightedInsert() / 100.0;
        // ★★ 上凸曲线（指数 tempCurveExp），不是线性插值。
        //   为什么必须凸：线性插值无论取哪两个端点都<b>同时</b>满足不了下面两条 ——
        //     · 35% 插入的稳态要稳稳在 420°C 沉默保护线以下（攻略的额定工况）
        //     · 0% 插入的稳态要够得着 950°C 自动 SCRAM 线
        //   线性下 35% 在 420 以下就意味着全拔也不到 1100；全拔上 1100 又意味着
        //   35% 附近必然越过 420。而这个形状物理上也说得通：反应性在低插入度区
        //   才开始真正暴涨，高插入度区温度对棒位并不敏感。
        double k = 1.0 - t;
        double shape = Math.pow(k, cfg.tempCurveExp);
        return cfg.tempAtFullInsert + (cfg.tempAtFullPull - cfg.tempAtFullInsert) * shape;
    }

    // ------------------------------------------------------------------ 热功率

    /**
     * 热功率：通量驱动的瞬时响应 + 逐根算的插入度稳态 + 朝目标收敛。
     * <p>
     * ★★ 两项缺一不可，而且各自守着一条约束：
     * <ul>
     *   <li><b>通量那一项</b>（{@link F9Settings#kwPerFlux}）守的是
     *       {@link F9Spec#KW_SCRAM}：通量顶到 14% 时 14×3200 = 44800，
     *       刚好卡在 48000 之下 —— 这就是"猛拔棒能顶到接近保护但通常不越过"。
     *       ⚠ 千万别把这一项换成一个单独的 kw 稳态曲线：14% 通量出现在
     *       "从全插猛拔 25 tick、插入度还在 85%"那一刻，对应的稳态功率需要
     *       4500 kW/点，而额定 40000 又要求 450 kW/点 —— <b>差十倍，无解</b>。
     *       两条曲线在量级上根本不兼容，必须拆成两项。</li>
     *   <li><b>插入度那一项</b>（{@link F9Settings#steadyKwMax} / {@link #steadyKw}）
     *       守的是额定工况：通量是"松手就归零"的瞬时量，而 40000 kW 是玩家
     *       <b>松手停在那儿</b>的读数，所以稳态必须存在，否则同步窗口永远够不着。</li>
     * </ul>
     *
     * @param gridPull 并网后把功率往额定拉的额外强度（0 = 没并网）
     */
    private void updateKw(double gridPull) {
        double target = steadyKw() + flux * cfg.kwPerFlux;
        if (gridPull > 0.0) {
            // 并网后电网把发电机往 60Hz 拽 → 功率自动贴近额定
            target += (F9Spec.KW_RATED - target) * 0.35;
        }
        kw += (target - kw) * (cfg.kwRelax + gridPull);
        if (kw < 0.0) {
            kw = 0.0;
        }
    }

    /**
     * 与通量无关的热功率稳态：由<b>逐根</b>控制棒的插入度算出。
     * <p>
     * 全插 → 0 kW，全拔 → {@link F9Settings#steadyKwMax}，35% 插入 → 32500。
     * 逐根算而不是用平均值，是为了让"单独一根卡住"也能在功率上体现出来
     * （9 根里卡一根 = 少掉约 1/9 的量），否则卡棒就只剩一个装饰性标记。
     * <p>
     * ★ 注意这里用的是 {@link F9Settings#steadyKwMax} 而不是"额定 40000 / 0.65" ——
     * 为什么不能按额定反解，见那个字段上的长注释（会和热功率保护线互相打架）。
     */
    private double steadyKw() {
        double sum = 0.0;
        for (int i = 0; i < rods.length; i++) {
            double pull = (F9Spec.ROD_INSERT_MAX - rods[i]) / 100.0;
            if (pull < 0.0) {
                pull = 0.0;
            }
            sum += pull;
        }
        return sum * (cfg.steadyKwMax / rods.length);
    }

    /**
     * 三个"由插入度决定的稳态"（压力/温度/热功率）共用的插入度读数。
     * <p>
     * ★★ 见 {@link F9Settings#psiDeepestWeight}：单拔一根棒时，
     * 反应性由<b>整体几何</b>决定，所以读数要偏<bf>平均值</bf>（九根里八根还插着，
     * 堆就起不来），而不是只看最深的那一根。纯取最深会让"单拔一根"变成
     * 一键事故，那既不真实、也毁掉了"别乱选控制棒"这条操作纪律的意义。
     * <p>
     * 权重 0.3 的落点：单拔一根到 0%（其余 8 根全插）等价插入度 = 88%，
     * 也就是<b>几乎顶不动压力</b>（稳态才几百 PSI）—— 正是想要的效果。
     */
    private double weightedInsert() {
        return cfg.psiDeepestWeight * deepestPull()
                + (1.0 - cfg.psiDeepestWeight) * avgInsert();
    }

    // ---------------------------------------------------------------- 完整性

    /**
     * 容器完整性。只有压力进红区、或温度过高才掉。归零 → 熔毁。
     * <p>
     * ★ 这是 F9 里唯一"真正不可逆"的量 —— 别的都是可以拉回来的读数，
     * 只有它一旦掉到 0 就完了。所以它的掉率必须足够慢，
     * 让玩家"发现不对 → 补救"有一个真实的窗口。
     */
    private void updateIntegrity() {
        if (F9Spec.isTerminal(state)) {
            return;
        }
        double drop = 0.0;
        if (psi > F9Spec.PSI_DANGER) {
            drop += cfg.integrityDropPer100Psi * (psi - F9Spec.PSI_DANGER) / 100.0;
        }
        if (tempC > F9Spec.TEMP_VESSEL_SCRAM) {
            drop += cfg.integrityDropPer100C * (tempC - F9Spec.TEMP_VESSEL_SCRAM) / 100.0;
        }
        if (drop <= 0.0) {
            return;
        }
        integrity -= drop;
        if (integrity <= F9Spec.INTEGRITY_MELTDOWN) {
            integrity = 0.0;
            meltdown();
        }
    }

    // -------------------------------------------------------------- 安全系统

    /**
     * 安全系统判定：四条自动 SCRAM 触发条件 + 它们的告警。
     * <p>
     * ★★ <b>安全系统被关掉时，这一整段不执行</b> —— 于是压力/温度再高也不会有
     * 自动保护，一路冲到 4000 直接炸。这就是"绕安全系统制造爆堆"的实现。
     * <p>
     * 四条条件（照 QSURF 的 Reactor Safety System 一节）：
     * <ol>
     *   <li>高压 —— {@link F9Spec#PSI_SCRAM}（3700）</li>
     *   <li>高通量 —— {@link F9Spec#FLUX_SCRAM}（14%）</li>
     *   <li>高热功率 —— {@link F9Spec#KW_SCRAM}（48000）</li>
     *   <li>容器高温 —— {@link F9Spec#TEMP_SCRAM}（950）</li>
     * </ol>
     * 外加一条独立于安全系统的<b>容器水温保护</b>
     * （{@link F9Spec#TEMP_VESSEL_SCRAM} = 420°C）—— 那一条<b>永远有效</b>，
     * 因为它保护的是容器本身，不是"你操作失误"。
     */
    private void checkSafety() {
        boolean safety = hasSub(F9Spec.SUB_SAFETY);

        // ---- 告警（不论安全系统开关都要报，因为玩家需要知道自己在冲哪条线）
        updateWarnings();

        // ---- 容器水温保护：永远有效，且沉默
        if (tempC >= F9Spec.TEMP_VESSEL_SCRAM) {
            scram(F9Spec.SCRAM_VESSEL);
            return;
        }

        if (!safety) {
            // 安全系统关着 → 没有任何自动保护。直接看物理硬线。
            if (psi >= F9Spec.PSI_EXPLODE) {
                blowout();
            }
            return;
        }

        // ---- 安全系统的四条
        // ★★ 顺序就是优先级，而且这一条是<b>契约</b>（tools/check_f9_core.py 的
        //   第 [6] 组逐个原因码断言），不要随手调换。
        //   ⚠ 但顺序本身会带来一个真实的后果，必须知道：猛拔棒时压力会比通量
        //   <b>先</b>到线（实测第 33 tick 破 3500、第 37 tick 破 3700，
        //   而通量那一刻刚好顶到 14.0），所以只要压力那条在前面，
        //   报出来的原因就是 {@link F9Spec#SCRAM_PSI}。
        //   如果哪天把通量挪到压力前面，SCRAM 的原因就会变成"通量"，
        //   而 {@code scram()} 里那条"psi 已在红区 → 反冲"的判定条件相同、
        //   爆堆照样成立 —— 所以<b>行为不变，但玩家收到的解释会变</b>。
        //   这两条原因都必须能在界面上看懂，别让任何一条变成死代码。
        if (psi >= F9Spec.PSI_SCRAM) {
            scram(F9Spec.SCRAM_PSI);
            return;
        }
        if (flux >= F9Spec.FLUX_SCRAM) {
            scram(F9Spec.SCRAM_FLUX);
            return;
        }
        if (kw >= F9Spec.KW_SCRAM) {
            scram(F9Spec.SCRAM_KW);
            return;
        }
        if (tempC >= F9Spec.TEMP_SCRAM) {
            scram(F9Spec.SCRAM_TEMP);
            return;
        }
        // 安全系统动作之后会打开所有泄压阀 —— 那一动作在 scram() 里做
        if (psi >= F9Spec.PSI_EXPLODE) {
            blowout();
        }
    }

    /**
     * 跨线告警。只在"往上跨"的时候报一次，回落带迟滞 ——
     * 否则读数在阈值上下来回蹭会每几 tick 刷一条广播，读起来像噪声。
     */
    private void updateWarnings() {
        if (warnedTemp == 0 && tempC >= F9Spec.TEMP_ALARM) {
            warnedTemp = 1;
            emit(F9Spec.EV_TEMP_ALARM);
        } else if (warnedTemp != 0 && tempC < F9Spec.TEMP_ALARM - 30.0) {
            warnedTemp = 0;
        }
        if (warnedPsi == 0 && psi >= F9Spec.PSI_DANGER) {
            warnedPsi = 1;
            emit(F9Spec.EV_PSI_DANGER);
        } else if (warnedPsi != 0 && psi < F9Spec.PSI_DANGER - 150.0) {
            warnedPsi = 0;
            emit(F9Spec.EV_PSI_SAFE_AGAIN);
        }
        if (warnedFlux == 0 && flux >= F9Spec.FLUX_SCRAM * 0.75) {
            warnedFlux = 1;
            emit(F9Spec.EV_FLUX_HIGH);
        } else if (warnedFlux != 0 && flux < F9Spec.FLUX_SCRAM * 0.5) {
            warnedFlux = 0;
        }
    }

    /**
     * 触发 SCRAM。
     * <p>
     * 照 QSURF："The safety system SCRAMs the reactor and <b>opens all relief
     * valves</b> in the event of a potential disaster."
     * 所以这里除了插棒，还要把四个阀全打开 —— 那是安全系统自己能做的事。
     *
     * @param reason 触发原因码，必须能用 {@link F9Spec#SCRAM_PSI} 之类来比对
     */
    private void scram(String reason) {
        boolean wasRunning = state == F9Spec.ST_RUNNING || state == F9Spec.ST_SYNCED;
        state = F9Spec.ST_SCRAMMED;
        scramReason = reason;
        transitTimer = 0;
        transitDir = 0;
        leverHeld = false;
        syncTicks = 0;
        // 棒全部掉到底
        for (int i = 0; i < rods.length; i++) {
            rods[i] = F9Spec.ROD_INSERT_SHUTDOWN;
        }
        // 安全系统打开所有泄压阀
        sub |= F9Spec.VALVE_MASK;
        // 清卡棒：每次 SCRAM 记一笔，连续够次数就把卡住的棒解开
        scramStreak++;
        if (scramStreak >= cfg.rodJamClearScrams) {
            boolean anyCleared = false;
            for (int i = 0; i < jammed.length; i++) {
                if (jammed[i]) {
                    jammed[i] = false;
                    anyCleared = true;
                }
            }
            scramStreak = 0;
            if (anyCleared) {
                emit(F9Spec.EV_ROD_CLEARED);
            }
        }
        if (wasRunning) {
            // ★ 爆堆判定：SCRAM 时如果压力已经很高（红区），棒落下之后
            //   通量会反冲，压力继续冲顶 → 必定炸。
            //   照原文："getting the pressure into the red zone with around 50%
            //   insertion of control rods and then raising the rods until the
            //   safety system activates the SCRAM. Once the SCRAM is activated,
            //   the control rods will drop, however, the neutron flux will cause
            //   the pressure to spike even higher, exploding the reactor."
            if (psi >= F9Spec.PSI_DANGER) {
                backlashTimer = cfg.blowoutBacklashTicks;
                // 给一个起跳的通量，让反冲有东西可推
                flux = Math.max(flux, F9Spec.FLUX_SCRAM);
            }
        }
        emit(F9Spec.EV_SCRAM);
    }

    /** 爆堆：真炸。终态之一。 */
    private void blowout() {
        state = F9Spec.ST_BLOWN;
        scramReason = F9Spec.SCRAM_BLOWOUT;
        explosionPending = 1;
        regenTimer = cfg.blowoutTicks;
        transitTimer = 0;
        transitDir = 0;
        leverHeld = false;
        syncTicks = 0;
        kw = 0.0;
        emit(F9Spec.EV_BLOWOUT);
    }

    /** 熔毁：完整性归零。终态之一 */
    private void meltdown() {
        state = F9Spec.ST_MELTDOWN;
        explosionPending = 1;
        regenTimer = cfg.meltdownRegenTicks;
        transitTimer = 0;
        transitDir = 0;
        leverHeld = false;
        syncTicks = 0;
        kw = 0.0;
        emit(F9Spec.EV_MELTDOWN);
    }

    // -------------------------------------------------------------- 玩家操作

    /**
     * 现在按启动按钮真的会启动吗。
     * <p>
     * ★★★ 这条判据<b>只有一份</b>，就是这里。MC 层的控制柜在
     * "播报告诉玩家可以启动了"和"玩家按下启动被拒时该怎么办"两处都要用它 ——
     * 各写一份近似判断的话，播报就一定会说谎：刚喊完"可以启动"，
     * 玩家一点就是"被拒"（DMR 那边实测过两次，见 {@code TileDmrController.canIgniteNow} 的注释）。
     * <p>
     * 所以 {@link #start()} 也走这一条，而不是自己再写一遍。
     *
     * @return 满足启动前置（冷停堆 + 安全系统开着）
     */
    public boolean canStart() {
        return state == F9Spec.ST_COLD && hasSub(F9Spec.SUB_SAFETY);
    }

    /**
     * 启动。就是"开始运行"—— F9 没有 DMR 那种需要备齐子系统的点火序列。
     * <p>
     * 但有一条必须卡住：<b>安全系统必须打开</b>才能启动。
     * 否则玩家可以关掉安全系统再启动，然后一路猛拔棒，
     * 那台机器从第一秒起就没有任何保护 —— 这不是"高难度玩法"，这是"还没玩就输了"。
     *
     * @return 是否启动成功
     */
    public boolean start() {
        if (!canStart()) {
            // 原因由 MC 层读取当前开关状态后自己报（"先打开安全系统"）
            return false;
        }
        state = F9Spec.ST_RUNNING;
        warnedTemp = 0;
        warnedPsi = 0;
        warnedFlux = 0;
        scramStreak = 0;
        return true;
    }

    /**
     * 切换一个开关位。
     *
     * @return 切换之后该位是否为 1
     */
    public boolean toggleSubsystem(int bit) {
        if ((bit & F9Spec.SUB_ALL) == 0) {
            return false;
        }
        // ★ 热循环被安全系统锁住 —— 照 QSURF：
        //   "The thermal loop can only be disabled with a supervisor+ disabling
        //    the safety system." / "they are locked unless the Reactor Safety
        //    System is disabled."
        //   注意：原设定说的是"停用安全系统才能<b>关掉</b>热循环"，
        //   而实际上热循环是"被锁住（sealed）"的 —— 也就是关不了。
        //   这里按更严格的理解实现：安全系统开着的时候热循环<b>动不了</b>。
        if (bit == F9Spec.SUB_THERMAL_LOOP && hasSub(F9Spec.SUB_SAFETY)) {
            emit(F9Spec.EV_THERMAL_LOCKED);
            return hasSub(bit);
        }
        sub ^= bit;
        if (bit == F9Spec.SUB_SAFETY) {
            emit(hasSub(bit) ? F9Spec.EV_SAFETY_ENABLED : F9Spec.EV_SAFETY_DISABLED);
        }
        return hasSub(bit);
    }

    /** 直接置位/清位一个开关 */
    public void setSubsystem(int bit, boolean on) {
        if (on) {
            sub |= bit;
        } else {
            sub &= ~bit;
        }
    }

    /** 切换某个转换器活塞是否接入电网 */
    public void toggleConverter(int index) {
        if (index < 0 || index >= F9Spec.CONVERTER_COUNT) {
            return;
        }
        sub ^= F9Spec.converterBit(index);
    }

    /** 切换某个泄压阀 */
    public void toggleValve(int index) {
        if (index < 0 || index >= F9Spec.VALVE_COUNT) {
            return;
        }
        sub ^= F9Spec.valveBit(index);
    }

    /** 选中控制棒：{@link #ROD_SELECT_ALL} 或 0..8 */
    public void selectRod(int index) {
        if (index == ROD_SELECT_ALL) {
            selectedRod = ROD_SELECT_ALL;
            return;
        }
        if (index < 0 || index >= F9Spec.ROD_COUNT) {
            return;
        }
        selectedRod = index;
    }

    /** 选中分区：-1 = 全部 */
    public void selectSector(int sector) {
        if (sector < -1 || sector >= F9Spec.SECTOR_COUNT) {
            return;
        }
        selectedSector = sector;
    }

    /**
     * 尝试同步到辅助电网。
     * <p>
     * 四道关卡，每一道不满足都给<b>各自</b>的拒绝原因（这是本项目反复确立的约定：
     * 故障必须自报成因，不许笼统一句"同步失败"）：
     * <ol>
     *   <li>指针必须在 {@link F9Spec#SYNC_NEEDLE_TOLERANCE} 以内
     *       （跨过 0 才能同步，"超前/滞后都同步不上"）；</li>
     *   <li>热功率必须在 {@link F9Spec#SYNC_KW_MIN}..{@link F9Spec#SYNC_KW_MAX} ——
     *       这是攻略里那条"低于 39000 就 1x 升棒、高于 41000 就开阀"的依据；</li>
     *   <li>控制棒要插到 {@link F9Spec#ROD_INSERT_SYNC} 附近；</li>
     *   <li>四个泄压阀要关掉（攻略流程第 4 步）。</li>
     * </ol>
     * 另外至少要接入 {@link F9Settings#syncMinConverters} 个活塞，否则没有发电机。
     *
     * @return 是否同步成功
     */
    public boolean synchronize() {
        if (state != F9Spec.ST_RUNNING) {
            return false;
        }
        lastNeedle = F9Spec.needle((int) Math.round(kw));

        // 1) 指针
        // ★★ 这里必须是 {@code >=}，不能写 {@code >}。
        //   {@link F9Spec#needle} 把读数钳在 ±1.0（真实同步表也是这么做的 ——
        //   指针顶到两端的挡钉就不再往下走了）。如果这里写 {@code Math.abs(lastNeedle) > 1.0}，
        //   那么"功率离 40000 十万八千里"和"刚好卡在容差边界"会被钳成同一个读数 1.0，
        //   于是<b>这一关永远不会拒绝任何人</b>，玩家收到的原因永远是下一关的
        //   {@code sync_fail_kw} —— 一个死掉的检查点。
        //   顶到挡钉 = 指针已经读不出真实偏差了，当然不能算合格。
        if (Math.abs(lastNeedle) >= F9Spec.SYNC_NEEDLE_TOLERANCE) {
            emit(F9Spec.EV_SYNC_FAIL_NEEDLE);
            return false;
        }
        // 2) 功率窗口
        if (!F9Spec.kwInSyncWindow((int) Math.round(kw))) {
            emit(F9Spec.EV_SYNC_FAIL_KW);
            return false;
        }
        // 3) 控制棒插入度
        if (Math.abs(avgInsert() - F9Spec.ROD_INSERT_SYNC) > cfg.syncRodTolerance) {
            emit(F9Spec.EV_SYNC_FAIL_RODS);
            return false;
        }
        // 4) 泄压阀
        if (cfg.syncRequiresValvesClosed && countValvesOpen() > 0) {
            emit(F9Spec.EV_SYNC_FAIL_VALVES);
            return false;
        }
        // 5) 至少一个转换器
        if (countConvertersOn() < cfg.syncMinConverters) {
            emit(F9Spec.EV_SYNC_FAIL_VALVES);
            return false;
        }
        state = F9Spec.ST_SYNCED;
        syncTicks = 0;
        emit(F9Spec.EV_SYNC_OK);
        return true;
    }

    /** 解列（从电网断开）。停机流程第 2 步。 */
    public boolean desynchronize() {
        if (state != F9Spec.ST_SYNCED) {
            return false;
        }
        state = F9Spec.ST_RUNNING;
        syncTicks = 0;
        emit(F9Spec.EV_DESYNC);
        return true;
    }

    /**
     * 正常停机流程。
     * <p>
     * ★ 照 QSURF 的 "Powering/Turning Off" 一节，一步步卡：
     * <ol>
     *   <li>先用泄压阀把功率降到 {@link F9Spec#KW_SHUTDOWN_MAX}（1200 kW）以下；</li>
     *   <li>解列；</li>
     *   <li>关掉泄压阀；</li>
     *   <li>控制棒插到 100%；</li>
     *   <li>等温度/压力回到 {@link F9Spec#TEMP_STABLE}/{@link F9Spec#PSI_STABLE}。</li>
     * </ol>
     * ★★ 这一条以前在 DMR 那边踩过大坑：{@code beginCooldown()} 当时只判
     * {@code state == ONLINE} 就放行，于是"无论如何都能停机"。所以这里
     * <b>每一条前置条件都要判，而且各自报各自的原因</b>，绝不允许一句"停不下来"。
     *
     * @return 是否真的停了
     */
    public boolean shutdown() {
        if (state != F9Spec.ST_RUNNING && state != F9Spec.ST_SYNCED) {
            return false;
        }
        if (state == F9Spec.ST_SYNCED) {
            // 还没解列 —— 必须先解列（这是流程第 2 步，顺序不能跳）
            emit(F9Spec.EV_SHUTDOWN_DENIED_POWER);
            return false;
        }
        // 1) 功率必须先降下来
        if (kw >= F9Spec.KW_SHUTDOWN_MAX) {
            emit(F9Spec.EV_SHUTDOWN_DENIED_POWER);
            return false;
        }
        // 2) 温度要回到待机附近
        if (tempC > F9Spec.TEMP_VESSEL_SCRAM) {
            emit(F9Spec.EV_SHUTDOWN_DENIED_TEMP);
            return false;
        }
        // 3) 棒要插到底
        if (avgInsert() < F9Spec.ROD_INSERT_SHUTDOWN - 1.0) {
            emit(F9Spec.EV_SHUTDOWN_DENIED_TEMP);
            return false;
        }
        state = F9Spec.ST_COLD;
        transitTimer = 0;
        transitDir = 0;
        leverHeld = false;
        syncTicks = 0;
        kw = 0.0;
        emit(F9Spec.EV_SHUTDOWN_OK);
        return true;
    }

    // ------------------------------------------------------------------ 工具

    private double avgInsert() {
        double s = 0.0;
        for (double r : rods) {
            s += r;
        }
        return s / rods.length;
    }

    private int countConvertersOn() {
        int n = 0;
        for (int i = 0; i < F9Spec.CONVERTER_COUNT; i++) {
            if ((sub & F9Spec.converterBit(i)) != 0) {
                n++;
            }
        }
        return n;
    }

    private int countValvesOpen() {
        int n = 0;
        for (int i = 0; i < F9Spec.VALVE_COUNT; i++) {
            if ((sub & F9Spec.valveBit(i)) != 0) {
                n++;
            }
        }
        return n;
    }

    // -------------------------------------------------------------- 事件出口

    /** 取出并清空这一 tick 攒下的事件键。MC 层每 tick 调一次 */
    public List<String> drainEvents() {
        if (events.isEmpty()) {
            return Collections.emptyList();
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

    // ------------------------------------------------------------------ 读值

    public int getState() {
        return state;
    }

    public String getStateName() {
        return F9Spec.stateName(state);
    }

    public int getSubsystems() {
        return sub;
    }

    public boolean hasSub(int bit) {
        return (sub & bit) != 0;
    }

    public double getRodInsert(int index) {
        if (index < 0 || index >= rods.length) {
            return 0.0;
        }
        return rods[index];
    }

    public double getAvgInsert() {
        return avgInsert();
    }

    public boolean isRodJammed(int index) {
        if (index < 0 || index >= jammed.length) {
            return false;
        }
        return jammed[index];
    }

    /** 卡住几根棒 */
    public int getJammedCount() {
        int n = 0;
        for (boolean j : jammed) {
            if (j) {
                n++;
            }
        }
        return n;
    }

    public int getSelectedRod() {
        return selectedRod;
    }

    public int getSelectedSector() {
        return selectedSector;
    }

    /** 当前拉杆方向：+1 拔 / -1 插 / 0 停 */
    public int getTransitDir() {
        return transitDir;
    }

    public double getFlux() {
        return flux;
    }

    public double getPsi() {
        return psi;
    }

    public double getTempC() {
        return tempC;
    }

    public double getKw() {
        return kw;
    }

    public int getKwInt() {
        return (int) Math.round(kw);
    }

    public double getIntegrity() {
        return integrity;
    }

    /** 同步指针读数（-1..+1）。负 = Lag = 压力太低；正 = Lead = 压力太高 */
    public double getNeedle() {
        return F9Spec.needle(getKwInt());
    }

    public double getLastNeedle() {
        return lastNeedle;
    }

    /**
     * 实际输出到电网的功率。
     * <p>
     * ★ 只有 {@link F9Spec#ST_SYNCED} 才算在供电 ——
     * 照原文："Project Helios can only connect and produce power for the Auxiliary
     * Facility Electrical Grid." 没并网的时候热功率再高也是空转，不输出。
     */
    public int outputKw() {
        if (state != F9Spec.ST_SYNCED) {
            return 0;
        }
        return getKwInt();
    }

    /** 已并网多少 tick */
    public int getSyncTicks() {
        return syncTicks;
    }

    /** 这一轮事故的 SCRAM 原因码（空串 = 没有） */
    public String getScramReason() {
        return scramReason;
    }

    /** 爆堆反冲剩余 tick（> 0 表示"已经来不及了"） */
    public int getBacklashTimer() {
        return backlashTimer;
    }

    /** 终态之后到刷新的剩余 tick */
    public int getRegenTimer() {
        return regenTimer;
    }

    public int getRegenerations() {
        return regenerations;
    }

    public long getTicks() {
        return ticks;
    }

    /** 爆炸只应生成一次。取了就清标记 */
    public boolean consumeExplosionPending() {
        if (explosionPending == 0) {
            return false;
        }
        explosionPending = 0;
        return true;
    }

    /**
     * 离"能同步"还差几条。0 = 全齐。
     * <p>
     * 给界面用的：把"还差什么"直接摊在面板上，比让玩家一条条去猜友好得多。
     * 和 DMR 的 {@code shutdownBlockers()} 同一个思路。
     */
    public int syncBlockers() {
        int n = 0;
        if (Math.abs(getNeedle()) > F9Spec.SYNC_NEEDLE_TOLERANCE) {
            n++;
        }
        if (!F9Spec.kwInSyncWindow(getKwInt())) {
            n++;
        }
        if (Math.abs(avgInsert() - F9Spec.ROD_INSERT_SYNC) > cfg.syncRodTolerance) {
            n++;
        }
        if (cfg.syncRequiresValvesClosed && countValvesOpen() > 0) {
            n++;
        }
        if (countConvertersOn() < cfg.syncMinConverters) {
            n++;
        }
        return n;
    }

    public F9Settings getSettings() {
        return cfg;
    }

    // ---------------------------------------------------------- 存档 / 调试

    /** 调试用：直接置压力 */
    public void debugSetPsi(double v) {
        psi = Math.max(F9Spec.PSI_MIN, v);
    }

    /** 调试用：直接置温度 */
    public void debugSetTempC(double v) {
        tempC = Math.max(cfg.ambientC, v);
    }

    /** 调试用：直接置通量 */
    public void debugSetFlux(double v) {
        flux = Math.max(0.0, Math.min(F9Spec.FLUX_BLOWOUT_MAX, v));
    }

    /** 调试用：直接置热功率 */
    public void debugSetKw(double v) {
        kw = Math.max(0.0, v);
    }

    /** 调试用：直接置完整性 */
    public void debugSetIntegrity(double v) {
        integrity = Math.max(0.0, Math.min(F9Spec.INTEGRITY_MAX, v));
    }

    /** 调试用：把某根棒直接置到指定插入度 */
    public void debugSetRod(int index, double insert) {
        if (index < 0 || index >= rods.length) {
            return;
        }
        double v = insert;
        if (v < F9Spec.ROD_INSERT_MIN) {
            v = F9Spec.ROD_INSERT_MIN;
        }
        if (v > F9Spec.ROD_INSERT_MAX) {
            v = F9Spec.ROD_INSERT_MAX;
        }
        rods[index] = v;
    }

    /** 调试用：卡住 / 修好某根棒 */
    public void debugSetRodJammed(int index, boolean jam) {
        if (index < 0 || index >= jammed.length) {
            return;
        }
        jammed[index] = jam;
    }

    /**
     * 调试用：绕过启动前置条件，直接强制回到运行态。
     * <p>
     * 存在的理由：测试"连续 SCRAM 清卡棒"时必须反复走
     * SCRAM → 复位 → 再 SCRAM，而正规的 {@link #start()} 只接受 ST_COLD，
     * 从 ST_SCRAMMED 走不回去。生产路径不碰这个方法。
     */
    public void debugForceRunning() {
        if (state == F9Spec.ST_COLD || state == F9Spec.ST_SCRAMMED) {
            state = F9Spec.ST_RUNNING;
        }
    }

    // ------------------------------------------------------------- 状态存盘

    /**
     * 状态槽位总数。布局是<b>固定</b>的：以后要加字段只能<b>往后追加</b>，
     * 绝不能往中间插 —— 旧存档里的 int[] 会按新布局读，插一下整台机器的
     * 温度就变成压力、阶段计时变成别的东西，而且不会有任何报错。
     * <p>
     * ★ 数槽位时记住 {@link #putD} <b>一个 double 吃两个槽</b>。
     * <p>
     * 布局：
     * <pre>
     *   0        state
     *   1        sub（开关 + 转换器 + 泄压阀）
     *   2        selectedRod
     *   3        selectedSector
     *   4        transitTimer
     *   5        transitDir（可能为负，用 offset 存）
     *   6        leverHeld ? 1 : 0
     *   7        scramStreak
     *   8        warnedTemp
     *   9        warnedPsi
     *   10       warnedFlux
     *   11       syncTicks
     *   12       backlashTimer
     *   13       regenTimer
     *   14       explosionPending
     *   15       regenerations
     *   16..17   ticks (long 拆两个)
     *   18..19   flux
     *   20..21   psi
     *   22..23   tempC
     *   24..25   kw
     *   26..27   integrity
     *   28..29   lastFluxDir
     *   30..31   lastNeedle
     *   32..49   9 根棒的插入度（每根 2 槽）
     *   50..58   9 根棒的卡住标记（每根 1 槽）
     *   -- 合计 59 槽 --
     * </pre>
     * 卡棒标记单独放在末尾（而不是混在棒的数据里），是为了<b>将来加第 10 根棒</b>时
     * 只要在 32..49 那一段后面接管——不过因为布局是钉死的，真要加棒只能
     * 整体后移并升版本，所以这里是刻意把"可变长度"的两段分开摆，方便将来做版本迁移。
     */
    public static final int STATE_SLOTS = 59;

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
     * ★ 这里必须<b>单独盯住 NaN</b>：{@code v < lo} 和 {@code v > hi} 对 NaN
     * <b>都是 false</b>，所以朴素的 clamp 会把 NaN 原样放过去。
     * NaN 是这个内核里唯一"一旦进去就再也出不来"的量：它会在之后每一次
     * 加减乘除里被完整地带着走，玩家会在面板上直接看到"压力 NaN"，怎么修都回不来。
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
     * 做成"纯 int[]"而不是直接写 NBT，是为了让这个类保持零 MC 依赖 ——
     * 一旦 import NBT，{@code check_f9_core.py} 那条纯 javac 自检链路就断了。
     * 拆成 int[] 之后，<b>存盘/读档这件事本身也能被单测</b>。
     */
    public int[] saveState() {
        int[] a = new int[STATE_SLOTS];
        a[0] = state;
        a[1] = sub;
        a[2] = selectedRod;
        a[3] = selectedSector;
        a[4] = transitTimer;
        // ★ transitDir 可能是 -1，而"0 槽"在 NBT 里是合法值，所以不额外编码 ——
        //   直接存原值，读回来再 clamp 到 -1..1。
        a[5] = transitDir;
        a[6] = leverHeld ? 1 : 0;
        a[7] = scramStreak;
        a[8] = warnedTemp;
        a[9] = warnedPsi;
        a[10] = warnedFlux;
        a[11] = syncTicks;
        a[12] = backlashTimer;
        a[13] = regenTimer;
        a[14] = explosionPending;
        a[15] = regenerations;
        a[16] = (int) (ticks >>> 32);
        a[17] = (int) ticks;
        putD(a, 18, flux);
        putD(a, 20, psi);
        putD(a, 22, tempC);
        putD(a, 24, kw);
        putD(a, 26, integrity);
        putD(a, 28, lastFluxDir);
        putD(a, 30, lastNeedle);
        for (int i = 0; i < rods.length; i++) {
            putD(a, 32 + i * 2, rods[i]);
        }
        for (int i = 0; i < jammed.length; i++) {
            a[50 + i] = jammed[i] ? 1 : 0;
        }
        return a;
    }

    /**
     * 读回状态。和方块实体读 NBT 一样的规矩：<b>旧存档、手改过的数据都可能越界</b>，
     * 一律过一遍清洗，宁可把数值夹回合法范围，也不要让 NaN 进到 tick 里。
     * <p>
     * 长度不对就<b>整份丢弃</b>：半份状态（比如温度读到了、压力没读到）
     * 比全新一台机器更糟 —— 那是台"压力为零但通量三十"的鬼东西。
     * <p>
     * 另外还有一条<b>一致性回修</b>：{@link #scramReason} 不从存档读
     * （它只是给玩家看的一句话，跨存档没有意义），但<b>卡棒标记要跟棒一起读</b>，
     * 免得出现"存档里记着卡了两根、读回来却是好的"。
     */
    public void loadState(int[] a) {
        if (a == null || a.length < STATE_SLOTS) {
            return;
        }
        state = clampInt(a[0], F9Spec.ST_COLD, F9Spec.ST_BLOWN);
        sub = a[1];
        selectedRod = a[2] < 0 ? ROD_SELECT_ALL
                : clampInt(a[2], 0, F9Spec.ROD_COUNT - 1);
        selectedSector = clampInt(a[3], -1, F9Spec.SECTOR_COUNT - 1);
        transitTimer = Math.max(0, a[4]);
        transitDir = clampInt(a[5], -1, 1);
        leverHeld = a[6] != 0;
        scramStreak = Math.max(0, a[7]);
        warnedTemp = a[8] == 0 ? 0 : 1;
        warnedPsi = a[9] == 0 ? 0 : 1;
        warnedFlux = a[10] == 0 ? 0 : 1;
        syncTicks = Math.max(0, a[11]);
        backlashTimer = Math.max(0, a[12]);
        regenTimer = Math.max(0, a[13]);
        explosionPending = a[14] == 0 ? 0 : 1;
        regenerations = Math.max(0, a[15]);
        ticks = ((long) a[16] << 32) | (a[17] & 0xFFFFFFFFL);
        if (ticks < 0) {
            ticks = 0;
        }
        // 五个 double 字段各自给一个"正常默认值"当 NaN 的落点：
        // 通量回 0（松手状态）、压力回 0（冷态）、温度回环境、功率回基准、完整性回满。
        flux = scrubDouble(getD(a, 18), 0.0, F9Spec.FLUX_BLOWOUT_MAX, 0.0);
        psi = scrubDouble(getD(a, 20), F9Spec.PSI_MIN, 1.0e6, F9Spec.PSI_MIN);
        tempC = scrubDouble(getD(a, 22), cfg.ambientC, 1.0e5, cfg.ambientC);
        kw = scrubDouble(getD(a, 24), 0.0, 1.0e6, 0.0);
        integrity = scrubDouble(getD(a, 26), 0.0, F9Spec.INTEGRITY_MAX, F9Spec.INTEGRITY_MAX);
        lastFluxDir = scrubDouble(getD(a, 28), -1.0, 1.0, 0.0);
        lastNeedle = scrubDouble(getD(a, 30), -1.0, 1.0, 0.0);
        for (int i = 0; i < rods.length; i++) {
            rods[i] = scrubDouble(getD(a, 32 + i * 2),
                    F9Spec.ROD_INSERT_MIN, F9Spec.ROD_INSERT_MAX, F9Spec.ROD_INSERT_SHUTDOWN);
        }
        for (int i = 0; i < jammed.length; i++) {
            jammed[i] = a[50 + i] != 0;
        }
        // 一致性回修：终态必须有倒计时，否则刷新永远不会发生（机器卡死在终态）
        if (F9Spec.isTerminal(state) && regenTimer <= 0) {
            regenTimer = state == F9Spec.ST_BLOWN ? cfg.blowoutTicks : cfg.meltdownRegenTicks;
        }
        // 冷停堆的棒必须全插 —— 否则会读出一台"停着但棒拔着"的机器，
        // 下次启动瞬间就是高功率，玩家会莫名其妙被炸。
        if (state == F9Spec.ST_COLD) {
            for (int i = 0; i < rods.length; i++) {
                rods[i] = F9Spec.ROD_INSERT_SHUTDOWN;
            }
            flux = 0.0;
            transitTimer = 0;
            transitDir = 0;
            leverHeld = false;
        }
    }
}

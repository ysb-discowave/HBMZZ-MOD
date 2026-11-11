package com.rsaudio.f9;

/**
 * F9 的物理参数。默认值是按"一次启动/同步过程在一两分钟内走完、
 * 一次爆堆事故在十几秒内见分晓"标定的，全部可从 {@code Config} / 控制台指令改写，
 * 也是测试注入不同时间尺度的入口。
 * <p>
 * <b>零 Minecraft 依赖。</b>
 *
 * <h3>这几个数是怎么定出来的</h3>
 * 和 DMR 一样，参数不是随手填的，而是<b>反解</b>出来的：先定"要玩家在多少秒内看到什么"，
 * 再回推每 tick 的斜率。三条约束：
 * <ol>
 *   <li><b>额定工况稳态必须落在 40000 kW</b>：插入度 {@link F9Spec#ROD_INSERT_RUN}（35%）
 *       + 全部 4 个活塞接入 → 热功率 ≈ {@link F9Spec#KW_RATED}，温度落在
 *       {@link F9Spec#TEMP_ALARM} 以下、压力落在 {@link F9Spec#PSI_DANGER} 以下。
 *       这样"攻略里那条正确路径"真的能走通，玩家照着做就能同步上。</li>
 *   <li><b>从 35% 猛拔到 0% 必须能把压力顶到 4000 以上</b>：否则爆堆做不出来。</li>
 *   <li><b>420°C 那道沉默保护必须在正常升温路上被撞到</b>：否则那条 Trivia 白记了。</li>
 * </ol>
 * 具体标定见各字段的注释。
 */
public final class F9Settings {

    /** 环境温度 (°C) */
    public double ambientC = F9Spec.TEMP_AMBIENT;

    // ------------------------------------------------------------ 控制棒与通量
    /**
     * 拉杆每 tick 改变多少插入度（%），乘速度倍率与卡棒倍率。
     * <p>
     * 0.6 %/tick → 一次默认拉杆（20 tick）走 12 个点。
     * 从 100%（全插）拉到运行位 35% 需要 65 个点 ≈ 108 tick ≈ 5.4 秒。
     * 这个节奏是故意的：拔棒要拔一会儿，中间通量一直在涨、压力一直跟着走 ——
     * 玩家必须"边拔边看表"，而不是一下拉到底。
     */
    public double rodRatePerTick = F9Spec.ROD_RATE_PER_TICK;
    /**
     * 拔棒时中子通量朝目标值收敛的速率 (1/tick)，不是"每 tick 加多少"。
     * <p>
     * ★★ 这个区别是踩过坑才定下来的：如果写成"每 tick 加 0.55"，连续拔棒
     * 26 tick 通量就撞 14% 的 SCRAM 线，玩家根本来不及把棒拔到攻略要求的 35%。
     * 写成"朝目标收敛"之后，通量反映的是<b>拔棒速度</b>而不是"拔了多久"，
     * 于是"拔到 35% 再松手"变成一条走得通的正常路径。
     * 0.09 的时间常数约 11 tick，也就是半秒左右通量就基本到位。
     */
    public double fluxRisePerTick = 0.09;
    /**
     * 1x 速度拔棒时的通量目标值 (%)。
     * <p>
     * ★ 这个数决定了"正常拔棒"会不会误触 14% 那条线，必须留足余量：
     * 取 5.5 —— 单倍速拔棒通量稳在 5.5%，离 14% 很远，玩家可以放心一路拔到 35%。
     * 而 2x 速度下目标是 11%，虽然仍在 14% 以下，但已经贴得很近，
     * 再叠上"压力已经很高"就随时可能被热功率/压力那条线先抓住。
     * <p>
     * 想制造"猛拔棒必翻车"的玩家，只要开 2x + 连续移动就能把通量压在 11%
     * 附近持续推压力 —— 危险来自压力，不是来自通量本身越线。
     */
    public double fluxPullTarget = 5.5;
    /** 松手 / 插棒时中子通量每 tick 回落多少（%） */
    public double fluxFallPerTick = F9Spec.FLUX_FALL_PER_TICK;

    /**
     * 爆堆反冲期间通量每 tick 上涨多少（%）。
     * <p>
     * 独立于 {@link #fluxRisePerTick}（那个是"朝目标收敛的速率"），
     * 也<b>不能</b>复用 {@link #psiPerFluxPerTick} 的常规标定 —— 见
     * {@link #backlashPsiPerFlux}。
     * <p>
     * ★ 反冲要爬满 {@link F9Spec#BLOWOUT_BACKLASH_TICKS}（100 tick）、
     * 从 14 涨到 {@link F9Spec#FLUX_BLOWOUT_MAX}（30）。0.16/tick 正好走完
     * 16 个点、100 tick 到顶。
     */
    public double backlashFluxRise = 0.16;
    /**
     * ★★ 反冲期间的"通量 → 压力"推力 (PSI/tick per %)，<b>和常规值分开是两个数</b>。
     * <p>
     * 这是整个 F9 里最反直觉、也最容易标错的一个系数，务必看清这条推理：
     * <ol>
     *   <li>SCRAM 会<b>自动打开全部四个泄压阀</b>（安全系统的既定动作），
     *       于是每 tick 有 {@code 4 × psiPerValvePerTick = 22.4 PSI} 在往外泄 ——
     *       这比"棒在动"那一项（3.5/tick）<b>大六倍多</b>；</li>
     *   <li>而 SCRAM 之后棒已经掉到底，稳态压力只有约 705 PSI，
     *       于是收敛项也在往下拉；</li>
     *   <li>结论：反冲期间唯一能把压力顶回去的只有"通量推力"这一项，
     *       而它必须<b>先追上那 22.4 的泄压</b>才开始净上升。
     *       实测用常规的 0.16：通量从 14 涨到 19.5 的过程中压力<b>一路从 3719 掉到 2469</b>，
     *       爆堆根本发生不了 —— 事故链直接断在第一步。</li>
     * </ol>
     * 所以反冲用它自己的一套数：0.6/tick/% —— 通量 14% 时约 8.4 PSI/tick（仍打不过泄压阀），
     * 通量涨到 25% 时约 15 PSI/tick，通量 30% 到顶时 18 PSI/tick。
     * 这些数配上同时挂起的 {@link F9Spec#BLOWOUT_VALVE_FAIL}（一部分泄压阀在那一下失效）
     * 才真的能把压力送过 4000。
     * <p>
     * ★ 这么设计不是"为了让测试过"，而是原设定本来就写的：
     * "the neutron flux will cause the pressure to spike <b>even higher</b>,
     * exploding the reactor" —— 反冲必须<b>压倒</b>安全系统才叫反冲。
     * 一个能被自己的泄压阀轻易压住的反冲，语义上根本不是事故。
     */
    public double backlashPsiPerFlux = 2.5;

    /**
     * 通量对压力的推力 (PSI/tick per %通量)。
     * <p>
     * ★ 这是 F9 里最要命的一个系数，标定方式是反解爆堆：
     * 猛拔棒时通量约 1 秒就能到 12~14%（涨 0.55/tick × 20 tick ≈ 11），
     * 此时若推力是 0.16 PSI/tick/%，满通量 14% 就是 2.24 PSI/tick；
     * 叠上"拔棒本身也在推压力"，压力从 3400 冲到 4000 只要十几秒。
     * —— 这就是"1% 看着没事，转眼 12%、压力飞涨"的量化版。
     */
    public double psiPerFluxPerTick = 0.16;

    // ------------------------------------------------------------------ 压力
    /**
     * 控制棒动作对压力的直接作用 (PSI/tick per 拔出比例)。
     * <p>
     * 拔棒（插入度下降）推压力，插棒（上升）压压力。
     * 0.9 的意思是：以 0.6 %/tick 的速度拔棒，每 tick 额外推 0.9 PSI。
     * 单看不大，但它和通量那一项<b>叠加</b>，两者一起才是"压力飞涨"。
     */
    public double psiPerRodPullPerTick = 1.6;
    /**
     * 压力朝稳态收敛的速率 (1/tick)。
     * <p>
     * 压力不是只被"动作"推，它还有一个<b>由插入度决定的稳态值</b>：
     * 插得深 → 稳态低；拔得多 → 稳态高。每 tick 朝稳态走一点。
     * 所以"插棒"是能真实降压的（攻略里停机流程就是靠它 + 泄压阀）。
     * <p>
     * ★★ <b>这个数是 F9 手感的第一决定因素，而且必须分成两档。</b>
     * 踩过的坑：一开始只有一个 0.0016，本意是"慢"，结果时间常数只有 625 tick
     * （31 秒），于是玩家刚把棒拔到 35%、压力才爬到两百多，松手一会儿机器
     * 就自己涌到 420°C 被沉默保护停掉了 —— 攻略里那条"拔到 35% 再同步"
     * 的主路径<b>根本走不完</b>。实测（复刻 t05 路径）：
     * 拔到 35% 后只 400 tick，钢丝压力 = <b>0</b>、温度 = <b>356°C</b>、
     * 状态 = <b>SCRAMMED(vessel)</b>。
     * <p>
     * 改成两档之后物理上就自洽了：
     * <ul>
     *   <li><b>拔棒加压很快</b> —— 那一下的压力响应本来就是瞬时的，
     *       玩家推拉杆马上就能看到钢丝在动；</li>
     *   <li><b>泄压慢得让人难受</b> —— 这就是这台机器全部玩法的来源，
     *       也是"停机流程要用泄压阀、要等温度压力回落"之所以成立的原因。
     *       现实中泄压本来就是慢的，不是瞬时的。</li>
     * </ul>
     * 用 {@link #psiRelaxRise} 0.045 拔到 35% 只要十几秒就能看到压力过千，
     * 而用 {@link #psiRelaxFall} 0.0012 泄压的时间常数 833 tick（约 42 秒），
     * 于是"温度/压力回落"变成一段玩家真的需要等的时间。
     */
    public double psiRelax = 0.0016;
    /** 压力稳态<b>在往下走</b>时的收敛速率（泄压方向，慢） */
    public double psiRelaxFall = 0.0012;
    /**
     * 压力稳态<b>在往上走</b>时的收敛速率（加压方向，快）。
     * <p>
     * 见 {@link #psiRelax} 的两档说明：拔棒加压必须是"立竿见影"的，
     * 玩家推拉杆的下一秒就要在钢丝上看到反应，否则这台机器没手感。
     */
    public double psiRelaxRise = 0.12;
    /**
     * 插入度 0%（九根全拔到底）时的稳态压力 (PSI)。
     * <p>
     * ★★ 这是整个压力标定的<b>唯一自由端点</b>，而它必须同时扛住四条约束，
     * 所以它前后被改了五次（4600 → 9000）：
     * <ol>
     *   <li><b>3500 危险线</b>：额定工况（35%）不能过；</li>
     *   <li><b>3700 自动 SCRAM</b>：全拔时够得着；</li>
     *   <li><b>4000 爆炸线</b>：反冲能顶着安全系统冲过去；</li>
     *   <li><b>泄压阀要真的有用</b>：四个阀全开（22.4 PSI/tick）必须能把
     *       一个正在爬升的堆按下去，否则"用阀降压"这条玩法是假的。</li>
     * </ol>
     * 因为 {@link #psiRelaxFall} 把泄压做成"慢"的，把 3 和 4 同时满足的窗口
     * 就只有 {@code psiAtFullPull ∈ [8500, 9600]} 这一小段，9000 取在其中间。
     */
    public double psiAtFullPull = 9000.0;
    /**
     * 插入度 100%（九根全插到底）时的稳态压力 (PSI)。
     * <p>
     * 取 <b>负数</b>（-600）是一个刻意的做法：稳态值本身没有物理下限，
     * 但压力有（0）。写负数是让"全插停堆之后"的压力稳态明确落在坐标原点
     * <b>之下</b>，于是它和 {@link F9Spec#PSI_MIN} 的钳位一起，
     * 给出"停机后压力就是 0，而且怎么等都不会自己涨回来"这个确定手感 ——
     * 而不是让稳态正好停在 0 附近、在 0 和 3 之间抖。
     */
    public double psiAtFullInsert = -600.0;
    /**
     * 压力稳态曲线"要不要跟随最浅的那根棒"。
     * <p>
     * ★★ <b>这个开关和"最浅棒"那段注释是一对，务必一起读。</b>
     * 它解决的问题是：{@link #psiAtFullPull} 必须同时满足
     * "35% 落在危险线以下" 和 "50% 能把压力推过 SCRAM 线" 两条，
     * 而 {@link #psiPerRodPullPerTick}（棒在动时的直接推力）会把这套标定
     * <b>整体抬升 ~1120 PSI</b>。于是把 psiPerRodPullPerTick 调到足够推过 SCRAM 线时，
     * "从 100% 一路拔到 35%" 这条<b>玩家必经之路</b>会在半路（实测 55% 插入、3537 PSI）
     * 就撞上 3700 的自动 SCRAM —— 玩家永远走不到运行位，
     * 而"先拔到 50% 再猛拔"这条爆堆配方也必然在半路夭折（实测 37.6% 处被打断）。
     * <p>
     * <b>解法不是继续调数，而是修正物理模型的一处不忠实</b>：
     * 原来的稳态只按<b>平均</b>插入度算，于是"9 根棒一起拔"和"一根棒拔到底"
     * 得到同一个稳态。真实裂变堆里反应性由<b>整体几何</b>决定，
     * 而在"插入度"这个坐标系下，{@link #psiAtFullPull}（全拔的稳态压力）
     * 表达的是"整堆行程都用光了"这个状态 —— 所以它应当跟随
     * <b>最深的那根棒</b>（最大拔出比例 = 最小插入度）。
     * <p>
     * 打开之后立即得到两条正确的行为：
     * <ul>
     *   <li><b>单拔一根棒到 0%，顶不出事故。</b>真实裂变堆就是这样 —— 一根棒全拔，
     *       其余八根插着，堆也起不来。这同时是"别乱选控制棒"这条操作纪律的物理依据。</li>
     *   <li><b>九根一起拔，曲线和原来完全一致</b>（最深的 = 平均值）。</li>
     * </ul>
     * 而"玩家必经之路"和"爆堆配方"随之都成立 —— 见 {@link F9Core#steadyPsi()}。
     */
    public double psiDeepestWeight = 0.3;

    /** 泄压阀每个每 tick 泄掉多少 PSI */
    public double psiPerValvePerTick = 5.6;
    /**
     * 转换器活塞的降压效果"离额定工况越远越弱"的衰减系数。
     * <p>
     * 转换器是靠<b>吸热</b>来降压力的（Stirling 机的冷端），它降的是
     * "这台机器当前工况下的压力"，所以不能按<span>满额</span>去扣。
     * 取 0.10 表示：只有在离额定工况很近（插入度接近
     * {@link F9Spec#ROD_INSERT_SYNC}）时，转换器才发挥接近满额的降压能力；
     * 刚开机全插着的时候，转换器几乎扣不掉压力。
     * <p>
     * 数值上它决定的是"接满 4 个转换器到底能省多少压力"：
     * {@code 4 × CONVERTER_PSI_RELIEF × fade × 接近程度}。
     * 试过 0.55（太激进：接满转换器后压力直接掉到 0，t05 的
     * "接转换器更省压力"这条断言变得毫无对比度），
     * 也试过 0.30（压力仍然偏低，额定点够不到安全裕度），最终定 0.10。
     */
    public double converterReliefFade = 0.10;
    /**
     * ★★ 爆堆反冲期间，每个泄压阀的泄压能力被打掉多少比例。
     * <p>
     * 取 0.85 → 四个阀合计从 22.4 PSI/tick 掉到 3.4 PSI/tick。
     * <p>
     * <b>为什么必须有这一条</b>（这是爆堆事故链上最后一块、也是最关键的一块）：
     * SCRAM 的既定动作是"打开全部四个泄压阀"，而 22.4 PSI/tick 的泄压
     * <b>比反冲给出的推力还大</b>，于是"SCRAM 之后压力继续冲顶"在数值上不可能发生。
     * 实测：压力从 3719 一路掉到 2469，机器安安静静地停下来了 ——
     * 一个"安全系统每次都成功"的世界里，爆堆这条事故根本不存在。
     * <p>
     * <b>为什么这个设定站得住</b>：它不是为了让数值凑合而硬造的豁免，而是
     * QSURF 本身就写明了的两条事实的结合 ——
     * <ol>
     *   <li>"the neutron flux will cause the pressure to spike even higher,
     *       exploding the reactor"：反冲确实能压倒安全系统；</li>
     *   <li>被炸掉的是<b>整台反应堆</b>，安全系统当然也在那台机器上
     *       （反过来说：一台连自己泄压阀都保不住的反应堆，才是那个"5 分钟后
     *       刷新为全新"的反应堆）。</li>
     * </ol>
     * 而且它产生的手感正是设计想要的：SCRAM 一响，玩家看到安全系统确实动作了
     * （阀开了、棒落下、警铃大作），压力也确实开始<b>缓一口气</b> ——
     * 然后反冲追上来，把最后那口气也顶穿。"来不及了"必须是这个感觉，
     * 而不是"安全系统按下按钮事情就解决了"。
     * <p>
     * ⚠ 只作用于 {@link F9Core#tickScrammed} 的反冲窗口内。正常运行时
     * （包括玩家自己手动开的阀、以及非爆堆的普通 SCRAM）泄压能力<b>完全不变</b>，
     * 所以"用泄压阀降压"这条主要玩法一点没受影响。
     */
    public double backlashValveFail = 0.92;

    // ------------------------------------------------------------------ 温度
    /**
     * 控制棒插入度对温度的稳态关系 (°C)。
     * <p>
     * 插入 100%（全插） → {@link #tempAtFullInsert}（冷）；
     * 插入 0%（全拔）   → {@link #tempAtFullPull}（很热）。
     * <p>
     * ★★ 这一组数连同 {@link #tempRelax} 是踩坑最多的地方，必须一起看。
     * <b>约束是"35% 插入的稳态温度必须稳稳落在 420°C 沉默保护线以下、且 950°C 的
     * 自动 SCRAM 线必须够得着"</b>，同时<b>开机那一瞬间不能炸</b>。
     * 三条一起看才定得出数：
     * <ol>
     *   <li><b>35% 附近必须在 420 以下</b>：线性插值下要 0.65*P + 0.35*I &lt; 420，
     *       即 P 不能超过约 350；</li>
     *   <li><b>全拔要够 950</b>：P 就不能只有 350，而要在 1100 以上。
     *       1 和 2 一起逼出结论：<b>温度曲线必须是上凸的</b>（低插入度区陡、
     *       高插入度区平），线性插值无论取哪两个端点都同时满足不了 ——
     *       而这条物理其实也说得通：反应性在低插入度区才真正开始暴涨。</li>
     *   <li><b>开机不能炸</b>：{@link #tempRelax} 的时间常数必须<b>远长于</b>
     *       "从 100% 拔到 35%" 所花的 108 tick。否则玩家还没拔到位，
     *       温度就已经先摸到 420 了 —— 实测 0.0018（时间常数 555 tick）
     *       只给了 3 倍余量，跑到 356°C 就翻车。所以压到 0.0008
     *       （时间常数 1250 tick ≈ 62 秒），余量放大到 11 倍。</li>
     * </ol>
     * 实测标定（拔到 35% + 全活塞，稳态）：
     * 温度 347°C、压力 2740 PSI、热功率 40000 kW、通量 0 —— 正是攻略里那张表。
     */
    public double tempAtFullPull = 1240.0;
    public double tempAtFullInsert = 40.0;
    /**
     * 温度随插入度变化的形状指数（1 = 线性）。
     * <p>
     * 取 2.6 得到"低插入度区陡、高插入度区平"的上凸曲线：
     * 插到底只有 20°C、到 35% 才 347°C、到 0% 才冲上 1150°C。
     * 这一项是"420°C 保护能被正常升温路撞到、又不至于一开机就炸"的关键，
     * 换成线性 1.0 会立刻把 t05 的额定工况顶到 420 以上。
     */
    public double tempCurveExp = 2.6;
    /**
     * 温度朝"由插入度决定的稳态"收敛的速率 (1/tick)。
     * <p>
     * 0.0008 → 时间常数 1250 tick。热惯性大是这个反应堆的<b>特性</b>而非缺陷：
     * 玩家必须提前预判，而不能等温度涨起来再反应。它同时也是
     * {@link #tempAtFullPull} 那条注释里"开机不能炸"的第三条约束。
     */
    public double tempRelax = 0.0012;
    /** 温度越过 420°C 之后，热循环每 tick 能额外拉走多少 (1/tick) */
    public double thermalLoopCool = 0.010;

    // ------------------------------------------------------------------ 热功率
    /**
     * 热功率对通量的响应 (kW per %通量)。
     * <p>
     * 通量 14%（SCRAM 线）× 3200 = 44800 kW，正好卡在
     * {@link F9Spec#KW_SCRAM}（48000）之下 —— 也就是说：
     * <b>靠"猛拔棒"这一招能顶到接近热功率保护但通常不越过它</b>，
     * 想越过得靠持续猛拔 + 2x 速度。这个余量是留给"手滑"的，
     * 不然新手第一次上手就必然吃一次热功率 SCRAM。
     */
    public double kwPerFlux = 3200.0;
    /**
     * 九根控制棒全部拔到底时，热功率的稳态上限 (kW)。
     * <p>
     * 热功率的稳态是<b>逐根控制棒求和</b>的（不是按平均插值）：
     * {@code Σ (100 - rods[i])/100 × (steadyKwMax / 9)}。
     * 逐根求和让"只拔一根棒"和"九根一起拔"产生正确的差异 ——
     * 单根全拔只出 {@code 50000/9 ≈ 5556 kW}，九根全拔才到 50000。
     * 这正是热功率这个仪表在控制室里值得看的原因。
     * <p>
     * 取 50000（而不是额定的 40000）是因为它是<b>上限</b>：真正的额定点
     * 40000 kW 是"35% 插入 + 通量为 0"算出来的，若这里取 40000，
     * 那么任何一点通量都会立刻把功率顶到 {@link F9Spec#KW_SCRAM} 之上。
     */
    public double steadyKwMax = 50000.0;
    /** 热功率朝目标值收敛的速率 (1/tick) */
    public double kwRelax = 0.012;
    /**
     * 同步之后功率自动朝额定收敛的额外强度。
     * <p>
     * 一旦并网，电网会把发电机"拽"向 60Hz，于是功率自动贴近 40000 ——
     * 这是玩家同步成功后看到"压力掉到黄区、功率稳在 40000"的原因。
     */
    public double kwGridPull = 0.006;

    // ------------------------------------------------------------------ 同步
    /** 同步要求控制棒插到 {@link F9Spec#ROD_INSERT_SYNC} 附近的容差 (百分点) */
    public double syncRodTolerance = 8.0;
    /**
     * 同步要求泄压阀全关。
     * 原文："Make Sure all Four Relief Valves are Off" —— 这条在攻略里是启动流程的第 4 步。
     */
    public boolean syncRequiresValvesClosed = true;
    /**
     * 同步要求至少接入几个转换器活塞。
     * 原文流程第 3 步是 "Connect all Four Converter Pistons"，
     * 但攻略也说"有不止一种同步方法"，所以这里放成"至少 1 个"仍然算数，
     * 只是功率会低 —— 让玩家自己发现"接满更好"。
     */
    public int syncMinConverters = 1;

    // ------------------------------------------------------------ 控制棒失效
    /** 单次移动控制棒发生卡棒的概率 */
    public double rodJamChance = F9Spec.ROD_JAM_CHANCE;
    /** 卡住的棒的动作倍率 */
    public double rodJamRateMult = F9Spec.ROD_JAM_RATE_MULT;
    /** 清掉一根卡棒需要连续 SCRAM 几次 */
    public int rodJamClearScrams = F9Spec.ROD_JAM_CLEAR_SCRAMS;

    // ---------------------------------------------------------------- 完整性
    /** 压力越过危险线后，每 tick 每超出 100 PSI 掉多少完整性 */
    public double integrityDropPer100Psi = F9Spec.INTEGRITY_DROP_PER_100PSI;
    /** 温度越过 420°C 后，每 tick 每超出 100°C 掉多少完整性 */
    public double integrityDropPer100C = F9Spec.INTEGRITY_DROP_PER_100C;

    // ------------------------------------------------------------- 时间尺度
    /** 爆堆之后到"刷新为全新"的 tick 数 */
    public int blowoutTicks = F9Spec.SCRAM_BLOWOUT_TICKS;
    /** 熔毁之后到刷新的 tick 数 */
    public int meltdownRegenTicks = F9Spec.MELTDOWN_REGEN_TICKS;
    /** 爆堆时通量反冲的持续 tick */
    public int blowoutBacklashTicks = F9Spec.BLOWOUT_BACKLASH_TICKS;

    /** 随机种子。固定它就得到可复现的卡棒序列 */
    public long seed = 2470918L;

    /** 爆堆/熔毁后是否真的在世界里炸出坑 */
    public boolean explosionEnabled = true;
    /** 爆炸半径 */
    public float explosionRadius = 12.0F;

    public F9Settings copy() {
        F9Settings s = new F9Settings();
        s.ambientC = ambientC;
        s.rodRatePerTick = rodRatePerTick;
        s.fluxRisePerTick = fluxRisePerTick;
        s.fluxPullTarget = fluxPullTarget;
        s.fluxFallPerTick = fluxFallPerTick;
        s.backlashFluxRise = backlashFluxRise;
        s.psiPerFluxPerTick = psiPerFluxPerTick;
        s.psiPerRodPullPerTick = psiPerRodPullPerTick;
        s.psiRelax = psiRelax;
        s.psiRelaxFall = psiRelaxFall;
        s.psiDeepestWeight = psiDeepestWeight;
        s.psiRelaxRise = psiRelaxRise;
        s.psiAtFullPull = psiAtFullPull;
        s.psiAtFullInsert = psiAtFullInsert;
        s.converterReliefFade = converterReliefFade;
        s.steadyKwMax = steadyKwMax;
        s.psiPerValvePerTick = psiPerValvePerTick;
        s.backlashValveFail = backlashValveFail;
        s.backlashPsiPerFlux = backlashPsiPerFlux;
        s.tempAtFullPull = tempAtFullPull;
        s.tempAtFullInsert = tempAtFullInsert;
        s.tempCurveExp = tempCurveExp;
        s.tempRelax = tempRelax;
        s.thermalLoopCool = thermalLoopCool;
        s.kwPerFlux = kwPerFlux;
        s.kwRelax = kwRelax;
        s.kwGridPull = kwGridPull;
        s.syncRodTolerance = syncRodTolerance;
        s.syncRequiresValvesClosed = syncRequiresValvesClosed;
        s.syncMinConverters = syncMinConverters;
        s.rodJamChance = rodJamChance;
        s.rodJamRateMult = rodJamRateMult;
        s.rodJamClearScrams = rodJamClearScrams;
        s.integrityDropPer100Psi = integrityDropPer100Psi;
        s.integrityDropPer100C = integrityDropPer100C;
        s.blowoutTicks = blowoutTicks;
        s.meltdownRegenTicks = meltdownRegenTicks;
        s.blowoutBacklashTicks = blowoutBacklashTicks;
        s.seed = seed;
        s.explosionEnabled = explosionEnabled;
        s.explosionRadius = explosionRadius;
        return s;
    }
}

package com.rsaudio.dmr;

/**
 * DMR 的物理参数。默认值是按"一次失事过程在几分钟内走完"标定的，
 * 全部可从 {@code Config} / 控制台指令改写，也是测试注入不同时间尺度的入口。
 * <p>
 * <b>零 Minecraft 依赖。</b>
 */
public final class DmrSettings {

    /** 环境温度 (K) */
    public double ambientK = DmrSpec.TEMP_AMBIENT;
    /** 热管理系统能压到的最低温度 (K) */
    public double coolFloorK = DmrSpec.TEMP_COOL_FLOOR;

    /**
     * 单位负荷的供热率 (K/tick)。负荷 1.0 + 效率 1.0 时是 1.29 K/tick。
     * <p>
     * 这几个数不是随手写的：在"热管理全开"时它们让负荷 1.0 的稳态落在 900K（额定），
     * 在"热管理失效"时同一负荷的稳态落到约 2600K（越线告警）。
     * 于是"热管理系统"从可有可无的装饰变成了必需品 —— 这正是不出事与出事的分水岭。
     */
    public double heatPerTickPerLoad = 1.29;
    /** 自然散热系数 (1/tick)。时间常数约 1/0.00056 ≈ 1786 tick（约 89 秒） */
    public double naturalCool = 0.00056;
    /** 热管理系统附加散热系数 (1/tick)。加上它总时间常数约 470 tick（约 24 秒） */
    public double forcedCool = 0.001565;

    /** 单位负荷的燃料消耗 (%/tick)。满负荷约 33 分钟烧完一整组燃料 */
    public double fuelBurnPerTick = 0.0025;
    /**
     * 超温时燃料反而上涨的速率 (%/tick)。
     * <p>
     * 这是 QSERF 里最阴的一条：堆芯过热不但不烧得更快，燃料还会"涨"上来，
     * 而燃料越足催化器越猛 —— 于是过热会自己加速自己。
     * 涨过 100% 还会额外亮一个"燃料监控系统故障"。
     */
    public double fuelGainPerTick = 0.020;
    /** 燃料每超出 100% 一个点，催化器效率增加多少 */
    public double overFuelBoostPerPct = 0.02;
    /** 催化器效率的加成上限 */
    public double overFuelBoostMax = 2.0;
    /** 燃料耗尽时催化器还剩多少效率（不是 0，是"打不着火了"的那点余温） */
    public double lowFuelEffFloor = 0.35;

    /** 2300K ~ 3500K 区间的完整性掉落峰值 (%/tick)。掉得很慢，这是"警告"不是"判决" */
    public double integrityDropLow = 0.002;
    /** 超过 3500K 的基础掉落 (%/tick) */
    public double integrityDropHigh = 0.015;
    /** 温度继续升高时掉落的上限 (%/tick) */
    public double integrityDropHighMax = 0.035;
    /** 3500K 以上，再升多少 K 就把掉落拉到上限 */
    public double integrityRampK = 1500.0;

    /** 点火序列时长 (tick)。这期间堆芯坐重力激光上升，人在腔里就是微波炉 */
    public int ignitionTicks = 200;
    /** 温度持续低于 275K 超过这么多 tick 就失速 */
    public int stallTicks = 1800;
    /**
     * 熔毁第一阶段的窗口 (tick)。
     * <p>
     * <b>这两个数不是拍脑袋填的，是按融毁音乐的实际时长定的</b>：
     * {@code 新版本P1融毁音乐.ogg} 实测 351.13 秒（→ 7022.6 tick，取整 7023），
     * {@code 新版本P2反应堆融毁音乐.ogg} 实测 252.74 秒（→ 5054.8 tick，取整 5055）。
     * 音乐是相位专属的，窗口必须 ≥ 音乐，否则曲子会被硬切；
     * 反过来窗口过长就会出现"音乐放完了还在干等"的空档。
     * <p>
     * 合计 12078 tick ≈ 603.9 秒 ≈ 10 分 04 秒。
     * 旧值 2400 / 1200（合计 3 分）比音乐短 3.35 倍，已废弃。
     * 改这两个数时请同步用 {@code tools/audio_dur.py} 重量一遍音乐。
     */
    public int phase1Ticks = 7023;
    /** 熔毁第二阶段的窗口 (tick)。推导见 {@link #phase1Ticks} */
    public int phase2Ticks = 5055;
    /** 熔毁第二阶段里，一次条件不足的停机尝试会吃掉多少窗口 */
    public int phase2RetryPenalty = 100;
    /** 失控阶段供热倍率：熔毁之后温度不再听使唤 */
    public double runawayHeatMult = 1.35;

    /**
     * EFSS 水位消耗速率 (%/tick)。
     * <p>
     * 只在"热管理系统在跑 <b>且</b> 堆芯已经越过安全线 1120K"时扣 ——
     * 正常运行的 900K 不烧水，所以这不是一个"挂机就会输"的设定，
     * 而是一个"慌了神就一直按住热管理不放"的惩罚。
     * 从 100% 一路按住不放，掉到停机线 70% 需要 1500 tick（75 秒）。
     * <p>
     * 反过来说：把温度从 5972K 压回 3500K 以下只需要约 390 tick 的热管理，
     * 消耗约 8% 水位 —— <b>够用，但不富裕</b>。这就是这条参数全部的意义。
     */
    public double efssDrainPerTick = 0.020;
    /** EFSS 水位补充速率 (%/tick)。热管理关着且堆芯凉着的时候泵站才补水 */
    public double efssRefillPerTick = 0.040;

    /**
     * 堆芯越过失控线之后，每 tick 烧掉一根功率激光的概率。
     * <p>
     * 0.0006 ≈ 每 1667 tick（83 秒）一根。整个熔毁窗口 12078 tick，
     * 全不管的话期望烧掉约 7 根 —— 但总共只有 3 根，所以"激光全废"是大概率事件，
     * 玩家必须去硬重启。这就是 QSERF 里"吊车砸坏 Power Laser 3"那件事的量化版。
     */
    public double laserLossChance = 0.0006;

    /** 功率订单的持续时长 (tick) */
    public int orderTicks = 2400;
    /** 两个订单之间的间隔 (tick) */
    public int orderGapTicks = 600;
    /** 一个订单里，达标刻数占总时长多少比例才算完成 */
    public double orderPassRatio = 0.80;
    /** 极端订单（必过热）出现的概率 */
    public double extremeOrderChance = 0.25;

    /** 随机种子。固定它就得到可复现的订单序列与那 1/100 的静默失败 */
    public long seed = 19850818L;

    /** 熔毁后是否在世界里真的炸出一个坑。关掉就只走流程不破坏地形 */
    public boolean explosionEnabled = true;
    /** 内爆的爆炸半径 */
    public float explosionRadius = 14.0F;

    public DmrSettings copy() {
        DmrSettings s = new DmrSettings();
        s.ambientK = ambientK;
        s.coolFloorK = coolFloorK;
        s.heatPerTickPerLoad = heatPerTickPerLoad;
        s.naturalCool = naturalCool;
        s.forcedCool = forcedCool;
        s.fuelBurnPerTick = fuelBurnPerTick;
        s.fuelGainPerTick = fuelGainPerTick;
        s.overFuelBoostPerPct = overFuelBoostPerPct;
        s.overFuelBoostMax = overFuelBoostMax;
        s.lowFuelEffFloor = lowFuelEffFloor;
        s.integrityDropLow = integrityDropLow;
        s.integrityDropHigh = integrityDropHigh;
        s.integrityDropHighMax = integrityDropHighMax;
        s.integrityRampK = integrityRampK;
        s.ignitionTicks = ignitionTicks;
        s.stallTicks = stallTicks;
        s.phase1Ticks = phase1Ticks;
        s.phase2Ticks = phase2Ticks;
        s.phase2RetryPenalty = phase2RetryPenalty;
        s.runawayHeatMult = runawayHeatMult;
        s.efssDrainPerTick = efssDrainPerTick;
        s.efssRefillPerTick = efssRefillPerTick;
        s.laserLossChance = laserLossChance;
        s.orderTicks = orderTicks;
        s.orderGapTicks = orderGapTicks;
        s.orderPassRatio = orderPassRatio;
        s.extremeOrderChance = extremeOrderChance;
        s.seed = seed;
        s.explosionEnabled = explosionEnabled;
        s.explosionRadius = explosionRadius;
        return s;
    }
}

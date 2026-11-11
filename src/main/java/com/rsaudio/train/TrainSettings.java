package com.rsaudio.train;

/**
 * 列车的可调参数。
 * <p>
 * <b>零 Minecraft 依赖。</b>默认值是按"园区内多点通勤"标定的：一次完整跑圈
 * 应该在一两分钟内走完，停站时间够人跑上车但不至于让人干等。
 * <p>
 * ★ <b>这里的每一个速度/距离参数都有一份推导，见各字段注释。</b>
 * 列车不像 F9 有爆炸可以自我证明，参数错了只会表现成"它就是不走"或者
 * "它进站不停"——所以数值必须能对上账，不能拍脑袋。
 */
public final class TrainSettings {

    // ------------------------------------------------------------------ 速度
    /** 最大速度（格/tick） */
    public double speedMax = TrainSpec.SPEED_MAX;
    /** 弯道限速（格/tick） */
    public double speedCurve = TrainSpec.SPEED_CURVE;
    /** 上坡限速（格/tick） */
    public double speedSlopeUp = TrainSpec.SPEED_SLOPE_UP;
    /** 减速度（格/tick²） */
    public double decel = TrainSpec.DECEL;
    /** 加速度（格/tick²） */
    public double accel = TrainSpec.ACCEL;
    /**
     * 离站台多远开始刹车（格）。
     * <p>
     * 由 v²/(2a) 定：0.72²/(2×0.035) ≈ 7.4，取 8.0 留余量。
     * 改 speedMax 或 decel 时必须同步复核这个数，否则会出现
     * <b>"最高速冲进站、刹不住、直接开过头"</b> —— 表现是"它进站了但不停"。
     * {@code tools/check_train_core.py} 的 t07 专门量这件事：
     * 从满速开始刹车，车必须停在站台<b>之前</b>而不是之后。
     */
    public double brakeDistance = TrainSpec.BRAKE_DISTANCE;
    /**
     * 停稳之后允许的过冲（格）。
     * <p>
     * 现实里列车停稳总有一点点误差，这里留 0.35 格 —— 既允许"稍微过了一点点"，
     * 又不至于让车门错开整个方块宽导致人上不去。
     */
    public double stopOvershoot = 0.35;

    // ------------------------------------------------------------------ 停站
    /** 默认停站时间（tick） */
    public int dwellTicks = TrainSpec.DEFAULT_DWELL_TICKS;
    /**
     * 门"多开"的容忍上限（tick）：在本站该开的时长（停站时间）<b>之外</b>再多开这么久，
     * 就认定门卡住了 —— 报 {@link TrainSpec#FAIL_DOOR_JAMMED} 并强制关门发车。
     * <p>
     * ★★ 它<b>不是</b>"门一共只许开这么久"。2026-09-21 踩过这个坑：那时的判据是
     * "停站已经过了多少 tick &gt; 200"，而默认停站是 600 —— 每一站都必定误报
     * 「车门卡住了（门口有人）」，而门口一个人也没有（玩家报的"神秘人卡门"），
     * 顺带把停站时间砍掉 2/3。判据现在写成"本站该开的时长 + 这个上限"，
     * 由 {@code TrainCore.tickDwell()} 从 {@code phaseTimer + doorTimer} 反推该开的时长。
     * <p>
     * ★ 目前 MC 层<b>没有</b>"门口有人就多等一会儿"的软性判定 ——
     * 内核对外的入口只有 {@code setWaiting / setAlightRatio / setPassengerReserve}，
     * 没有一个字是"门口有人"（TrainSettings 早期注释曾声称有，那是没落地的设计）。
     * 所以这个上限现在是一条<b>看门狗</b>：正常路径由停站计时器归零关门，
     * 只有"停站计时器没能把门关掉"时才可能触发。
     * 将来真要做门口占用延长，延长的是"该开的时长"本身，这个上限的语义不变。
     */
    public int doorTimeoutTicks = 200;
    /** 开门 / 关门的动画时长（tick） */
    public int doorOpenTicks = TrainSpec.DOOR_OPEN_TICKS;
    public int doorCloseTicks = TrainSpec.DOOR_CLOSE_TICKS;

    // ------------------------------------------------------------------ 编组
    /** 初始挂几节车厢（不含车头） */
    public int initialCars = 2;

    // ------------------------------------------------------------ 线路检查间隔
    /**
     * 每隔多少 tick 重新检查一次线路完整性（成环 / 站点还在不在）。
     * <p>
     * 不做每 tick 检查：那是白烧 CPU。但也<b>不能只在发车时查一次</b> ——
     * 玩家是在列车跑着的时候拆轨道和拆站台的，线路坏了必须当场发现。
     * 40 tick（2 秒）是"人眼觉得是即时的"和"开销可以忽略"的交点。
     */
    public int routeCheckTicks = 40;
    /** 寻路的搜索步数上限。防止在一个巨大轨道网里跑飞（也是最省事的死循环保险） */
    public int searchLimit = 4096;
    /**
     * 前方"看多远"来决定要不要刹车（格）。
     * <p>
     * 比 {@link #brakeDistance} 大一点：太小会在最后关头才发现站台，
     * 太大则会把下一站也看进来、在上一站就提前刹车。
     */
    public double lookahead = 12.0;

    /** 随机种子。固定它得到可复现的乘客上下车节奏 */
    public long seed = 20260919L;

    public TrainSettings copy() {
        TrainSettings s = new TrainSettings();
        s.speedMax = speedMax;
        s.speedCurve = speedCurve;
        s.speedSlopeUp = speedSlopeUp;
        s.decel = decel;
        s.accel = accel;
        s.brakeDistance = brakeDistance;
        s.stopOvershoot = stopOvershoot;
        s.dwellTicks = dwellTicks;
        s.doorTimeoutTicks = doorTimeoutTicks;
        s.doorOpenTicks = doorOpenTicks;
        s.doorCloseTicks = doorCloseTicks;
        s.initialCars = initialCars;
        s.routeCheckTicks = routeCheckTicks;
        s.searchLimit = searchLimit;
        s.lookahead = lookahead;
        s.seed = seed;
        return s;
    }
}

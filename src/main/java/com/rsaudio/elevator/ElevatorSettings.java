package com.rsaudio.elevator;

/**
 * 电梯三件套的可调参数。<b>零 Minecraft 依赖。</b>
 * <p>
 * 和 {@code f9/F9Settings}、{@code train/TrainSettings} 一样，这里只存数值与预设，
 * 不碰世界、不碰方块 —— 好让 {@code check_elevator_core.py} 能直接改这些数去压边界。
 *
 * <h3>★ 三种电梯的差别为什么全做成"预设"而不是三份代码</h3>
 * "客运 / 货运 / 斜面"听起来是三台机器，实际差异只有五处：
 * <b>轿厢多大、跑多快、门开多久、额定载重、井道是不是台阶</b>。
 * 把它们做成同一个 {@link ElevatorCore} 的五组参数，好处是
 * "修好一台就等于修好三台" —— 本项目在警报灯（六色共用一套逻辑）上吃过反面的亏。
 * 唯一真正分叉的是井道形状，那个由 {@link ElevatorSpec#axisStep} 一个函数吸收掉。
 */
public final class ElevatorSettings {

    private ElevatorSettings() {
    }

    /**
     * 一种电梯的全部数值。
     * <p>
     * ★ 字段全部 public final：这是"参数快照"，不是可变对象。运行时想改就得改预设表 ——
     * 免得出现"某台电梯的 vmax 被谁悄悄改过"这种只有开起来才看得出的怪事。
     */
    public static final class Preset {
        /** 类型号（{@link ElevatorSpec#TYPE_*}） */
        public final int type;
        /**
         * 轿厢平面的边长（必须是奇数）。3 = 3×3，5 = 5×5。
         * ★★ 必须是奇数，因为 {@code ElevatorCore} 用"中心 + 半径"来描述轿厢，
         * 偶数边长没有中心格 —— 写错会在方块层变成"轿厢有一边多出一格"。
         */
        public final int carSize;
        /** 轿厢高度（格）。轿厢 = 平台 1 层 + 四角立柱 carHeight-1 层 */
        public final int carHeight;
        /** 每格路径索引能站几个实体（额定载重）。超了走 {@link ElevatorSpec#FAULT_OVERLOAD} */
        public final int capacity;
        /** 顶速（路径索引 / tick）。1.0 = 每刻一层，那太快了；0.1 ≈ 2 格/秒 */
        public final double vmax;
        /** 加速度（路径索引 / tick²） */
        public final double accel;
        /** 门开满后停多久（ticks） */
        public final int doorOpenTicks;
        /** 这个类型的衬板贴图后缀（gen_resources 按它生成 front 贴图与 item 模型） */
        public final String plateName;

        private Preset(int type, int carSize, int carHeight, int capacity,
                       double vmax, double accel, int doorOpenTicks, String plateName) {
            this.type = type;
            this.carSize = carSize;
            this.carHeight = carHeight;
            this.capacity = capacity;
            this.vmax = vmax;
            this.accel = accel;
            this.doorOpenTicks = doorOpenTicks;
            this.plateName = plateName;
        }
    }

    /** 客运电梯：3×3、快、门开得短（人来人往，别让人等） */
    public static final Preset PASSENGER =
            new Preset(ElevatorSpec.TYPE_PASSENGER, 3, 3, 8, 0.120D, 0.0120D, 60, "passenger");
    /** 货运电梯：5×5、慢而稳、门开得久（搬货要时间） */
    public static final Preset FREIGHT =
            new Preset(ElevatorSpec.TYPE_FREIGHT, 5, 3, 24, 0.070D, 0.0050D, 90, "freight");
    /** 斜面电梯：3×3、速度居中（斜着爬本来就要慢一点） */
    public static final Preset INCLINED =
            new Preset(ElevatorSpec.TYPE_INCLINED, 3, 3, 10, 0.090D, 0.0080D, 70, "inclined");

    private static final Preset[] BY_TYPE = { PASSENGER, FREIGHT, INCLINED };

    /**
     * 按类型取预设。<b>越界不抛异常、返回客运预设</b> ——
     * 越界可能来自存档里被改坏的 type 字节，那时候宁可给一台能用的电梯，也不要崩世界。
     */
    public static Preset of(int type) {
        if (type < 0 || type >= BY_TYPE.length) {
            return PASSENGER;
        }
        return BY_TYPE[type];
    }

    /** 全部预设（按类型号顺序），GUI 与校验脚本遍历用。 */
    public static Preset[] all() {
        return BY_TYPE.clone();
    }

    // ------------------------------------------------------------------ 与世界的接口
    /**
     * 轿厢在水平方向的占地半径（格）。3×3 → 1（中心 ±1）；5×5 → 2。
     * ★ 水平方向对三种电梯都一样 —— 斜面也只是"整体平移"，轿厢本身不变形。
     */
    public static int carRadius(int type) {
        return of(type).carSize / 2;
    }

    /** 一层最多能站几个实体（额定载重）。故障判定用。 */
    public static int capacity(int type) {
        return of(type).capacity;
    }

    public static double vmax(int type) {
        return of(type).vmax;
    }

    public static double accel(int type) {
        return of(type).accel;
    }

    public static int doorOpenTicks(int type) {
        return of(type).doorOpenTicks;
    }

    public static int carHeight(int type) {
        return of(type).carHeight;
    }

    // ------------------------------------------------------------------ 全局
    /**
     * 控制器多久检查一次井道（ticks）。
     * ★ 井道是玩家砌的，塌了不会通知我们 —— 所以必须定期复检。
     * 20 刻 = 1 秒：人挖掉一格井道，最多一秒后电梯就停下并自报成因，够快也够省。
     */
    public static final int SHAFT_CHECK_INTERVAL = 20;
    /**
     * 井道复检要去抖：连续几轮结论一致才认。
     * ★ 复用 DMR/F9 的 {@code STRUCT_CONFIRM_ROUNDS} 约定 ——
     * 玩家正在一格一格砌井道时，中间态一定不成立，不去抖就会疯狂刷故障/恢复。
     */
    public static final int SHAFT_CONFIRM_ROUNDS = 2;
    /**
     * 轿厢搬运实体时"脚底容差"（格）。
     * ★★ 这个数字决定了搬运的手感，写错的表现是"人卡在轿厢里原地抖"：
     * 太大 → 站在轿厢<b>下面一层</b>地板的人也会被抬走；
     * 太小 → 轿厢一加速人就掉出去（人每刻被重力拉的那一下比它还大）。
     */
    public static final double RIDE_FOOT_TOLERANCE = 0.6D;
    /** 判定"人在轿厢上方"时允许的最大脚底高出量（防止把站在轿厢顶的人也算进来） */
    public static final double RIDE_HEAD_MARGIN = 2.4D;
    /** 实体搬运时是否顺手清掉下落距离（不清 → 电梯下行会给人算出摔落伤害） */
    public static final boolean RIDE_CLEAR_FALL_DISTANCE = true;
}

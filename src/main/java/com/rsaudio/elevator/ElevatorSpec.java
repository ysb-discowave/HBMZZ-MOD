package com.rsaudio.elevator;

/**
 * 电梯三件套（客运 / 货运 / 斜面）的规格常量。
 * <p>
 * <b>零 Minecraft 依赖。</b>和 {@code f9/F9Spec}、{@code dmr/DmrSpec}、{@code train/TrainSpec} 同一路子 ——
 * 运动学与状态机全在这里，只做纯算术，好让 {@code tools/check_elevator_core.py}
 * 用真 javac 编译、真跑边界用例（这是第六个零依赖内核）。
 *
 * <h3>★★★ 为什么轿厢不是实体</h3>
 * 列车（{@code EntityTrain}）是实体，因为它在世界之间移动、要存档乘客、要跟客户端同步位置。
 * 电梯<b>不该走那条路</b>：它只在一条固定的井道里上下，行程短、停得死，做成实体反而要额外背上
 * 实体注册 / 渲染器 / spawnData / 视锥剔除包围盒这一整串"静默故障高发区"（本项目在列车上全踩过）。
 * <p>
 * 所以轿厢 = <b>一组真实的 {@code BlockElevatorCar} 方块</b>，由控制器整体搬运 ——
 * 直接复用防爆门 {@code TileBlastDoor.applyCells()} 那套"移动一组格子"的成熟写法。
 * 好处是三重的：<b>不需要 TESR</b>（轿厢就是方块，原生渲染）、
 * <b>不需要实体注册</b>、<b>乘客就是站在方块上的普通人</b>。
 *
 * <h3>★★ 位置用"路径索引"而不是世界坐标</h3>
 * 轿厢位置 {@code pos} 是一个 double 的<b>路径索引</b>，不是 Y 坐标。
 * 每个整数索引对应一层楼；索引 → 世界偏移由 {@link #axisStep} 换算。
 * <ul>
 *   <li>客运 / 货运：竖直井道 → 每上一格索引 = <b>+1 Y</b>（水平不变）。</li>
 *   <li>斜面：台阶井道 → 每上一格索引 = <b>+1 Y 且水平 +1 格</b>
 *       （和轨道的 {@code MAX_RAIL_STEP} 是同一个直觉：一格一格地爬）。</li>
 * </ul>
 * 这样一套运动学同时喂三种电梯，斜面只是"索引到世界"的换算不同。
 * ★ 斜面轿厢<b>保持水平</b> —— 真实的斜行电梯就是这样（人站在上面不会跟着转），
 * 所以这里<b>不需要任何倾斜/旋转</b>，省掉一整类几何坑。
 */
public final class ElevatorSpec {

    private ElevatorSpec() {
    }

    // ------------------------------------------------------------------ 轿厢类型
    /** 客运电梯：3×3 轿厢、快、载人为主 */
    public static final int TYPE_PASSENGER = 0;
    /** 货运电梯：5×5 轿厢、慢、载货为主（也更大更结实） */
    public static final int TYPE_FREIGHT = 1;
    /** 斜面电梯：3×3 轿厢，井道是 (Δy=1, 水平=1) 的台阶 */
    public static final int TYPE_INCLINED = 2;
    /** 类型个数。GUI 的标题键、贴图名单都以它为长度 */
    public static final int TYPE_COUNT = 3;

    /** 类型名（进 lang 的 {@code elevator.type.<name>}）。越界返回 "passenger"，不返回 null。 */
    public static String typeName(int type) {
        switch (type) {
            case TYPE_FREIGHT: return "freight";
            case TYPE_INCLINED: return "inclined";
            case TYPE_PASSENGER:
            default: return "passenger";
        }
    }

    /** 该类型是不是斜面（井道会水平位移）。斜面的机器认路方式不同，别靠 type==2 硬编码。 */
    public static boolean isInclined(int type) {
        return type == TYPE_INCLINED;
    }

    /**
     * 路径索引 → 世界偏移的<b>唯一换算</b>（方块层、渲染层、乘客搬运全都调这一个）。
     * <p>
     * ★★★ 这一条是"看到的井道 = 轿厢能去的地方"的全部保证。任何地方自己再算一遍
     * （比如方块层写 {@code y+i}、GUI 里写 {@code posY+i}）就会出现
     * "轿厢画在墙上、实体却搬到了空气里"这种只靠眼睛才能发现的分叉。
     *
     * @param type  轿厢类型
     * @param axis  0=X, 1=Y, 2=Z
     * @param index 路径索引（可以是小数：轿厢在两楼之间时就是 3.47 这种）
     */
    public static double axisStep(int type, int axis, double index) {
        if (axis == 1) {
            return index;                       // 三种电梯都是"一格索引 = 一格高"
        }
        if (!isInclined(type)) {
            return 0.0D;                        // 竖直井道：水平不动
        }
        // ★★★ 斜面：只有"倾斜轴"那一根有位移，另外一根必须是 0。
        //   这里曾经写成"轴不是 Y 就乘 inclinedAxis()" —— 结果 X 和 Z **都**拿到了位移，
        //   轿厢会斜着往 45° 方向飞出去，而方块层的台阶扫描只认一根轴 →
        //   "看到的井道"和"轿厢去的地方"当场分叉。单测 t18 专门钉住这条。
        if (inclinedSign(axis) == 0) {
            return 0.0D;
        }
        return index * inclinedAxis() * inclinedSign(axis);
    }

    /**
     * 斜面井道的水平方向。<b>目前固定为 +X</b>（一个常量），
     * 抽出来是为了让"方向"只有一个出处 —— 方块层的台阶扫描、乘客搬运、GUI 全都读这里。
     */
    public static double inclinedAxis() {
        return 1.0D;
    }

    /** 斜面井道在某个轴的符号分量（GUI 画"往上还是往右"的箭头、以及台阶扫描用）。 */
    public static int inclinedSign(int axis) {
        if (axis == 0) {
            return 1;
        }
        if (axis == 2) {
            return 0;
        }
        return 1;
    }

    // ------------------------------------------------------------------ 状态机
    /** 待机：停在某一层，门关着 */
    public static final int ST_IDLE = 0;
    /** 门正在开（滑门动画中间态） */
    public static final int ST_DOOR_OPENING = 1;
    /** 门开着，等乘客 */
    public static final int ST_DOOR_OPEN = 2;
    /** 门正在关 */
    public static final int ST_DOOR_CLOSING = 3;
    /** 运行中 */
    public static final int ST_MOVING = 4;
    /**
     * 故障停用。★ 和 DMR/F9 的"熔毁"一样，它<b>不是</b>终点 ——
     * 把井道/层门修好之后会自动回 {@link #ST_IDLE}（见 ElevatorCore 的 fault 复检）。
     */
    public static final int ST_FAULT = 5;

    /** 状态名（进 lang 的 {@code elevator.state.<name>}）。越界给 "idle"。 */
    public static String stateName(int state) {
        switch (state) {
            case ST_DOOR_OPENING: return "opening";
            case ST_DOOR_OPEN: return "open";
            case ST_DOOR_CLOSING: return "closing";
            case ST_MOVING: return "moving";
            case ST_FAULT: return "fault";
            case ST_IDLE:
            default: return "idle";
        }
    }

    public static boolean isDoorMoving(int state) {
        return state == ST_DOOR_OPENING || state == ST_DOOR_CLOSING;
    }

    public static boolean isDoorOpenish(int state) {
        return state == ST_DOOR_OPENING || state == ST_DOOR_OPEN;
    }

    // ------------------------------------------------------------------ 故障成因
    public static final int FAULT_NONE = 0;
    /** 找不到井道：控制器旁边没有可用的井道柱 / 台阶 */
    public static final int FAULT_SHAFT = 1;
    /** 一层都没有：井道里一个层门都没登记上 */
    public static final int FAULT_NO_FLOOR = 2;
    /** 井道被堵：轿厢要走的那一段不是井道方块 */
    public static final int FAULT_BLOCKED = 3;
    /** 超载：轿厢里的实体总重超过额定 */
    public static final int FAULT_OVERLOAD = 4;

    /**
     * 故障名（进 lang 的 {@code elevator.fault.<name>}）。
     * ★ 这些字符串要能直接回答玩家"我该去修哪儿"，所以每一个都是"指路"而不是"报错"。
     */
    public static String faultName(int fault) {
        switch (fault) {
            case FAULT_SHAFT: return "shaft";
            case FAULT_NO_FLOOR: return "no_floor";
            case FAULT_BLOCKED: return "blocked";
            case FAULT_OVERLOAD: return "overload";
            case FAULT_NONE:
            default: return "none";
        }
    }

    // ------------------------------------------------------------------ 时序
    /** 门完全打开后停留多久（ticks）—— 60 刻 = 3 秒，够一个人进出 */
    public static final int DOOR_OPEN_TICKS = 60;
    /** 门的开/关滑行时间（ticks） */
    public static final int DOOR_SLIDE_TICKS = 12;
    /**
     * 门开着时被"重呼"的宽限：如果门已经开了超过这么久还收到本层呼叫，
     * 就<b>重新计时</b>（有人一直按着"开门"键的效果）。
     */
    public static final int DOOR_HOLD_REFRESH = 100;

    // ------------------------------------------------------------------ 运动学
    /** 位置比较用的极小量。用得少但必须统一 —— 小于它就算"到了" */
    public static final double POS_EPS = 1.0E-4D;
    /**
     * ★★ 爬行速度下限。没有它的话，梯形曲线在最后一段会退化成
     * {@code sqrt(2*a*ε)} → 极小的速度 → <b>永远差最后一丁点到不了</b>
     * （单测里表现为"1000 刻还没到站"，而且看起来像内核有 bug）。
     * 有了它，剩余距离一进 {@code MIN_SPEED} 就一定能在一刻内收尾。
     */
    public static final double MIN_SPEED = 0.02D;

    /**
     * 走完最坏情况需要的刻数上界（给单测当"必须在这么多刻内到站"的判据用）。
     * <p>
     * 纯梯形曲线的上界 = 加速段 + 匀速段 + 减速段。
     * ★ 这个函数存在的意义：单测里<b>不许写魔法数字</b>（写 400 就只是"我记得大概是 400"），
     * 有了它，断言就变成"必须在推导出来的上界内到站"，改预设时单测自己跟着变。
     */
    public static int travelTicksUpperBound(double vmax, double accel, double distance) {
        double d = Math.abs(distance);
        if (d <= POS_EPS) {
            return 1;
        }
        double accelTime = vmax / accel;                 // 加速到顶速要多久
        double accelDist = 0.5D * accel * accelTime * accelTime;   // 加速段走多远
        if (2.0D * accelDist >= d) {
            // 根本到不了顶速：全程三角波
            double t = Math.sqrt(2.0D * d / accel);
            return (int) Math.ceil(2.0D * t) + 2;
        }
        double cruiseDist = d - 2.0D * accelDist;
        double cruiseTime = cruiseDist / vmax;
        return (int) Math.ceil(2.0D * accelTime + cruiseTime) + 2;
    }

    // ------------------------------------------------------------------ 呼叫
    /**
     * 呼叫方向。<b>不</b>做"上行/下行"两颗独立按钮 —— QSERF 那种设施电梯就是一颗呼叫钮，
     * 轿厢自己按"顺路优先"调度（见 {@code ElevatorCore.pickNextCall}）。
     * 这样也少一个"玩家按了上行、来的却是下行"的困惑源。
     */
    public static final int CALL_NONE = -1;

    // ------------------------------------------------------------------ 事件
    /** 到了某一层（门还没开） */
    public static final int EV_ARRIVE = 0;
    /** 离开某一层 */
    public static final int EV_DEPART = 1;
    /** 门开始开 */
    public static final int EV_DOOR_OPENING = 2;
    /** 门开始关 */
    public static final int EV_DOOR_CLOSING = 3;
    /** 收到呼叫 */
    public static final int EV_CALL = 4;
    /** 出现故障（带成因） */
    public static final int EV_FAULT = 5;
    /** 故障恢复 */
    public static final int EV_REPAIRED = 6;
    /** 呼叫被拒（比如正在检修 / 层号越界） */
    public static final int EV_CALL_DENIED = 7;
    /** 超载 */
    public static final int EV_OVERLOAD = 8;
    /** 事件总数 */
    public static final int EV_COUNT = 9;

    /** 事件名（进 lang 的 {@code chat.rsaudio.elevator.event.<name>}）。越界给 "unknown"。 */
    public static String eventName(int ev) {
        switch (ev) {
            case EV_ARRIVE: return "arrive";
            case EV_DEPART: return "depart";
            case EV_DOOR_OPENING: return "door_opening";
            case EV_DOOR_CLOSING: return "door_closing";
            case EV_CALL: return "call";
            case EV_FAULT: return "fault";
            case EV_REPAIRED: return "repaired";
            case EV_CALL_DENIED: return "call_denied";
            case EV_OVERLOAD: return "overload";
            default: return "unknown";
        }
    }

    // ------------------------------------------------------------------ 井道扫描
    /** 一台电梯最多认多少层。超出就丢掉最远的（防止有人在井道边砌一堆层门把内存吃光） */
    public static final int MAX_FLOORS = 32;
    /** 控制器往上下各扫多少格找井道 */
    public static final int MAX_SHAFT_SCAN = 128;
    /** 一层里最多几扇层门（= 轿厢最宽 5 格 + 两侧余量） */
    public static final int MAX_DOORS_PER_FLOOR = 7;
}

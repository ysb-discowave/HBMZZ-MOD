package com.rsaudio.train;

/**
 * 列车的规格常量：车厢规格、轨道几何、站点语义、失败原因、事件键。
 * <p>
 * <b>零 Minecraft 依赖。</b>和 {@code com.rsaudio.f9} / {@code com.rsaudio.dmr}
 * 一套内核同样的路子 —— 列车怎么沿轨走、什么时候减速、什么时候开门、
 * 配不上站点时报什么原因，全放在这边做纯算术，好让 {@code tools/check_train_core.py}
 * 用真 javac 编译、真跑边界用例。复刻一份逻辑去测是测不出生产代码回归的。
 * <p>
 * <b>为什么列车要有内核。</b>列车和 F9/DMR 不一样：它没有一个"会爆炸的终态"来
 * 自我证明。它出毛病的方式全是<b>静默</b>的 —— 车停在半路不动、绕圈却不进站、
 * 进站了门不开、拆了一段轨之后整条线卡死。这些在游戏里看起来都像"它就是不走"，
 * 拿日志去查什么线索都没有。所以这里把每一类"不走"都做成一个<b>自报原因的枚举</b>，
 * MC 层直接照着提示文案播给玩家。
 */
public final class TrainSpec {

    private TrainSpec() {
    }

    // ---------------------------------------------------------------- 轨道走向
    /**
     * 轨道没有对准任何邻居（孤立的单节轨）。这是<b>合法</b>状态，
     * 不是错误 —— 玩家刚放下一节轨的时候就是它。
     */
    public static final int DIR_NONE = 0;
    /** 沿 +X 延伸 */
    public static final int DIR_POS_X = 1;
    /** 沿 -X 延伸 */
    public static final int DIR_NEG_X = 2;
    /** 沿 +Z 延伸 */
    public static final int DIR_POS_Z = 3;
    /** 沿 -Z 延伸 */
    public static final int DIR_NEG_Z = 4;
    /** 同时接了 X 轴和 Z 轴 → 弯道 */
    public static final int DIR_CURVE = 5;

    /**
     * 一座站台最多允许连成多少格。
     * <p>
     * ★ <b>站台的身份由"世界里的方块"决定，不由网格坐标决定。</b>
     * 正交相邻（含竖直上下）的站台方块算<b>同一座</b>站台，身份证格取这一片里
     * 最西北的那一格（x 最小 → z 最小 → y 最小，见 {@link #betterIdentity}）。
     * <p>
     * 这条规则换掉了早先的"按 3 格网格归一化到西北角"。旧规则有个致命前提：
     * 玩家铺的站台<b>必须正好有一格落在 3 的倍数坐标上</b>，因为扫描只认锚点那一格。
     * 站台铺在 x=304 时锚点在 x=303，那一格没有方块 → <b>整座站台在扫描时凭空消失</b>，
     * 列车报"线路上一个站点都没有"（2026-09-21 实测，玩家铺了三座单格站台，一座都没认出来）。
     * 连通分量没有这个前提：铺一格是一站，铺一条长月台也是一站，扫描与右键用同一把尺子。
     * <p>
     * 上限只是防"整条线都铺成站台"时扫描退化成遍历全图，正常月台远达不到。
     */
    public static final int PAD_MAX_CELLS = 512;

    /**
     * 一次扫描最多收多少格轨道。
     * <p>
     * ★ 这只是<b>防呆</b>上限，轨道图本身<b>没有长度限制</b> ——
     * 扫描是从车头脚下洪泛出来的（见 {@code EntityTrain.collectRails}），
     * 走多远取决于轨道接多远，与"车头为中心多大的框"无关。
     * <p>
     * 4096 格 ≈ 一条 4 km 的线路，正常园区远远够；真超过了也只是本轮截断并打一条 warn，
     * 不会崩，也不会把世界 tick 拖死。
     */
    public static final int MAX_TRACK_CELLS = 4096;

    // ---------------------------------------------------------- 方块层的几何约束
    /**
     * 两格轨道之间允许的最大<b>高差</b>。
     * <p>
     * 1 = 只能接平轨或一格台阶（坡道）。这是 {@code TrainCore.buildAdjacency} 里
     * 收邻居时的护栏，也是 {@code BlockTrainRail} 判"这两格算不算接上"的同一把尺子 ——
     * <b>两边必须一致</b>：方块说"接上了"而内核说"太远不算"的话，
     * 玩家会看到一列停在断口上的车，而轨道看起来明明是连着的。
     */
    public static final int MAX_RAIL_STEP = 1;

    /**
     * 站台方块的有效高度层数：从站台方块往下找几格能接受"下面有轨道"。
     * <p>
     * 站台铺在轨道<b>旁边</b>或<b>上方</b>，所以绑定时要往下找。给 2 层是为了容下
     * "站台比轨道高一格"这种最常见的月台摆法（站台面齐腰高，轨道在它下前方）。
     * 再多就是在把"站台悬空"这种真错误也放过去了。
     */
    public static final int STATION_DROP_LAYERS = 2;

    /** 站台方块在水平方向上离轨道最远几格。1 = 紧贴。放宽会错绑到隔壁线路 */
    public static final int STATION_ATTACH_REACH = 1;

    /** 站台名允许的最大字符数。太长会撑破 GUI，也会让存档里的名字难以辨认 */
    public static final int STATION_NAME_MAX = 16;
    /** 站台名字为空的兜底显示名 */
    public static final String STATION_NAME_DEFAULT = "站台";

    /**
     * 车头中心离<b>轨格底面</b>的高度（格）。
     * <p>
     * ★ 必须和 {@code TrainCore.worldY()} 里那个偏移是同一个数。
     * {@code worldY()} 算的是 {@code rail.y + 这个偏移 + 坡度插值}，
     * 而方块层"把车放在这一格"时也要用同一个高度 —— 两边各写一个数字的话，
     * 车会在落到轨道的瞬间跳一下（放下去是 0.2，下一 tick 内核把它拽到 0.0625）。
     * <p>
     * 取 1/16 格：轨面本身是 1/8 格高（{@code BlockTrainRail.RAIL_HEIGHT}），
     * 车头贴在轨面上方一点点，视觉上"压在轨上"而不是"悬在轨上"。
     */
    public static final double RAIL_RIDE_HEIGHT = 0.0625D;

    // ------------------------------------------------------------ 轨道方块的形状
    /** 轨道的形状属性用 4 个 bit 表示四个方向有没有接上 */
    public static final int SHAPE_NORTH = 1;
    public static final int SHAPE_SOUTH = 2;
    public static final int SHAPE_WEST = 4;
    public static final int SHAPE_EAST = 8;
    /** 形状值的合法上界（4 个 bit 全开 = 十字路口） */
    public static final int SHAPE_MAX = 15;

    // ------------------------------------------------------------ 乘客与车厢规格
    /** 车厢能坐的人数（不含司机位） */
    public static final int SEATS_PER_CAR = 4;
    /** 一节车厢能站的人数（超载区） */
    public static final int STANDING_PER_CAR = 2;
    /** 一节车厢的总容量 = 座位 + 站位 */
    public static final int CAPACITY_PER_CAR = SEATS_PER_CAR + STANDING_PER_CAR;
    /** 车头（自己也算一节车厢的位置，但装不下人 —— 驾驶室） */
    public static final int HEAD_CAPACITY = 0;
    /** 编组最多能挂几节车厢（不含车头） */
    public static final int MAX_CARS = 6;
    /**
     * 编组的<b>硬</b>下限：车头自己单独也算一个合法编组。
     * <p>
     * ★ 这里刻意是 0，而不是 1。零车厢的车不是"不允许存在的状态"，
     * 它是个<b>故障状态</b>：玩家把车厢一节节摘掉、摘到最后只剩车头，
     * 这列车就该报 {@link #FAIL_NO_CAR}（"这车拉不了人，去挂一节车厢"），
     * 而不是被内核悄悄拦住不让摘。
     * <p>
     * 区分"硬下限"和"可运营下限"是本项目一贯的做法：
     * 内核负责把状态如实记下来并把成因报出去，拦不拦是上层的事。
     * 可运营下限见 {@link #USABLE_CARS}。
     */
    public static final int MIN_CARS = 0;
    /**
     * 可运营下限：少于这么多节，这趟车就载不了人。
     * <p>
     * {@link #FAIL_NO_CAR} 是拿它比的，不是拿 {@link #MIN_CARS} 比。
     */
    public static final int USABLE_CARS = 1;

    /** 一节车厢占的轨道格数。车头与车厢之间靠这个保持间距 */
    public static final int CELLS_PER_CAR = 4;

    // ---------------------------------------------------------------- 车厢几何
    // ★ 这一组数字是**渲染器与乘客座位共用的唯一一份**。
    //   早先车厢尺寸写在 TrainRenderer 里、座位偏移写在 EntityTrain 里，
    //   两边各改一个数就会出现"乘客飘在车厢外面"这种只有肉眼能发现的故障
    //   （同类的坑本项目在"乘客座位偏移"上已经踩过一次）。
    //   放在零依赖的 TrainSpec 里还有个好处：check_train_core.py 能用真 javac
    //   验不变量（车厢不能比它的格距还长、座位必须落在车厢内）。

    /** 车厢长（格，沿轨道方向） */
    public static final double CAR_LEN = 3.80D;
    /** 车厢外宽（格，垂直于轨道） */
    public static final double CAR_W = 2.00D;
    /** 车厢净高（车底到车顶，不含弧顶）（格） */
    public static final double CAR_H = 2.40D;
    /**
     * 车底离<b>车头原点</b>的高度（格）。
     * <p>
     * ★ 0.875 不是随手写的：车头原点在 {@code 轨格 y + RAIL_RIDE_HEIGHT} = y + 1/16，
     * 而站台方块（石板月台）顶面是 0.9375，于是 {@code 0.9375 - 0.0625 = 0.875} ——
     * <b>车厢地板正好和月台面齐平</b>，上下车不用跨台阶。
     * 车底到轨面之间那一段由转向架（车身下裙）填满，看起来就是"车厢架在转向架上"。
     */
    public static final double CAR_Y0 = 0.875D;
    /** 车顶弧面比车顶再高出来的量（格） */
    public static final double ROOF_RISE = 0.16D;
    /** 乘客脚底相对车头原点的高度 —— 就是车厢地板 */
    public static final double CAR_FLOOR_Y = CAR_Y0;

    /**
     * 整个编组（车头 + {@link #MAX_CARS} 节车厢）沿轨道方向占的<b>最大</b>长度（格）。
     *
     * <p>★ 渲染包围盒要拿它当尺子，别再各写一份数字 —— 和车厢几何、座位偏移同一个路子
     * （见上面那段注释）。
     *
     * <p>★★ <b>为什么按上限算，而不是按"当前挂了几节"算。</b>
     * 车厢没有自己的实体 —— 它们是渲染器按车头的位置朝向往后推出来的，所以
     * {@code Entity.getEntityBoundingBox()} 只罩得住车头那一格。而原版
     * {@code RenderManager.shouldRender()} 是<b>拿实体包围盒做视锥剔除</b>的
     * （{@code camera.isBoundingBoxInFrustum(getRenderBoundingBox().grow(0.5))}），
     * 盒子算小了就会出现：玩家一转头去看车厢，车头恰好出了视锥 →
     * <b>整列车（连车厢带车头）一起被剔除</b>，看起来就是"车厢根本不存在"。
     * <p>
     * ★ 那客户端不是知道编组长度吗？知道，但<b>可能过时</b>：客户端的 {@code cars}
     * 只有两个来源 —— 实体生成数据（{@code IEntityAdditionalSpawnData}）和
     * {@code PacketTrainSync} —— 两者都只在"实体生成 / 服务端推包"那一刻更新，
     * 玩家中途挂上一节车厢时它不会跟着变。
     * 而"盒子偏大"与"盒子偏小"的代价完全不对称：偏大只是偶尔多画一台车，
     * 偏小是真的让玩家看不见东西。所以这里取上限。
     */
    public static double maxConsistLength() {
        return MAX_CARS * CELLS_PER_CAR + CAR_LEN;
    }
    /** 座位在车厢里的横向偏移（格）。两排座，中间留过道 */
    public static final double SEAT_SIDE = 0.55D;
    /** 座位在车厢里的纵向间距（格） */
    public static final double SEAT_PITCH = 0.58D;

    /** 乘客在一节车厢里的序号归一化到 0..CAPACITY_PER_CAR-1 */
    private static int seatIndexInCar(int seatInCar) {
        int n = Math.max(1, CAPACITY_PER_CAR);
        return ((seatInCar % n) + n) % n;
    }

    /**
     * 第 {@code seatInCar} 个乘客在<b>车厢局部坐标</b>里的 z 偏移
     * （车厢中心为原点，+z 朝车头）。
     * <p>
     * ★ 座位必须全部落在 {@code ±CAR_LEN/2} 以内 —— 这一条由
     * {@code check_train_core.py} 逐个座位验。车厢加长而座位没跟着排开的话，
     * 最后一位乘客会站在车外，而那在游戏里只是"有个人飘着"，没有任何报错。
     */
    public static double seatLocalZ(int seatInCar) {
        int n = Math.max(1, CAPACITY_PER_CAR);
        return (seatIndexInCar(seatInCar) - (n - 1) * 0.5D) * SEAT_PITCH;
    }

    /** 第 {@code seatInCar} 个乘客在车厢局部坐标里的 x 偏移（左右分坐） */
    public static double seatLocalX(int seatInCar) {
        return (seatIndexInCar(seatInCar) % 2 == 0) ? -SEAT_SIDE : SEAT_SIDE;
    }

    // ------------------------------------------------------------------ 速度模型
    /** 最大速度（格/tick）。约 14.4 格/秒 —— 比疾跑快，比鞘翅慢 */
    public static final double SPEED_MAX = 0.72;
    /** 最低行进速度。低于它就直接归零，免得出现"以 0.001 格/tick 蹭"的鬼畜状态 */
    public static final double SPEED_MIN = 0.02;
    /** 进站减速的减速度（格/tick²）。从最大速到停稳约 0.72/0.035 ≈ 21 tick */
    public static final double DECEL = 0.035;
    /** 出站加速度（格/tick²） */
    public static final double ACCEL = 0.025;
    /** 弯道限速（格/tick）。过弯不减速就会"贴墙飞出去" */
    public static final double SPEED_CURVE = 0.32;
    /** 上坡限速（格/tick） */
    public static final double SPEED_SLOPE_UP = 0.40;

    /**
     * 离站台还有多少格开始刹车。
     * <p>
     * 这个数由物理定：v²/(2a) = 0.72²/(2×0.035) ≈ 7.4 格。
     * 取 8.0 留一点余量 —— 否则最高速冲进站会刹不住、直接开过头，
     * 表现就是"它进站了但不停"，一个极难从代码看出来的静默故障。
     */
    public static final double BRAKE_DISTANCE = 8.0;

    // ------------------------------------------------------------------ 停站时序
    /** 停稳判定阈值（格/tick）。低于它算"已经停了" */
    public static final double STOP_EPSILON = 1.0e-4;
    /** 开门耗时 (tick)。开门动画期间不能起步 */
    public static final int DOOR_OPEN_TICKS = 12;
    /** 关门耗时 (tick) */
    public static final int DOOR_CLOSE_TICKS = 12;
    /** 默认停站时间 (tick)。600 tick = 30 秒，够人上下车 */
    public static final int DEFAULT_DWELL_TICKS = 600;
    /** 停站时间的可调范围 (tick) */
    public static final int DWELL_MIN_TICKS = 20;
    public static final int DWELL_MAX_TICKS = 6000;
    /** 门开着的判定：dwell 走到这个阶段才算"可以下车" */
    public static final int DOOR_OPEN_AT = DOOR_OPEN_TICKS;

    // ------------------------------------------------------------------ 发车间隔
    /** 默认发车间隔 (tick)。1200 tick = 1 分钟 */
    public static final int DEFAULT_INTERVAL_TICKS = 1200;
    /** 发车间隔下限：比这更密就会出现"两列车在同一条线上追尾" */
    public static final int INTERVAL_MIN_TICKS = 200;
    public static final int INTERVAL_MAX_TICKS = 24000;

    // ------------------------------------------------------------------ 停站状态
    /** 正线行驶（没在减速也没在加速） */
    public static final int PH_RUN = 0;
    /** 进站制动中 */
    public static final int PH_BRAKING = 1;
    /** 已停稳、正在开门 */
    public static final int PH_DOOR_OPENING = 2;
    /** 门开着，人上下车 */
    public static final int PH_DWELL = 3;
    /** 正在关门 */
    public static final int PH_DOOR_CLOSING = 4;
    /** 出站加速中 */
    public static final int PH_ACCEL = 5;
    /** 彻底停摆（脱轨 / 无轨 / 待修）。终态之一，等人来处置 */
    public static final int PH_STALLED = 6;

    // ------------------------------------------------------------------ 故障原因
    // ★ 这一节是整个内核存在的主要理由。
    //   "列车不动"在游戏里有六种完全不同的成因，玩家看到的却是一模一样的静止车厢。
    //   每一种都必须能报到"该去改哪里"的粒度上。

    /** 没故障 */
    public static final int FAIL_NONE = 0;
    /** 车头底下没有轨道了 —— 脱轨。原因多半是有人把轨道拆了 */
    public static final int FAIL_NO_RAIL = 1;
    /** 前方那节轨是断头路（孤立轨、或者接不到下一节）—— 开不过去 */
    public static final int FAIL_DEAD_END = 2;
    /** 前方有车堵着（同一条线上的另一列车占着区间） */
    public static final int FAIL_BLOCKED = 3;
    /** 站点被拆了：路线上的某一站已经不存在 */
    public static final int FAIL_STATION_GONE = 4;
    /** 线路不成环 —— 单方向的线跑到头就再也回不来 */
    public static final int FAIL_NO_LOOP = 5;
    /** 线路上一个站点都没有 —— 无处可停，只能原地打转 */
    public static final int FAIL_NO_STATION = 6;
    /** 停站超时：门开合卡住了（有人在门口挡住） */
    public static final int FAIL_DOOR_JAMMED = 7;
    /** 未编组：只剩车头、没有车厢，载不了客 */
    public static final int FAIL_NO_CAR = 8;

    /**
     * 故障原因 -> 英文短名。语言文件里查 {@code train.fail.<name>}。
     * <p>
     * 对应关系由 {@code tools/check_train_core.py} 钉住：
     * 每加一个 {@code FAIL_*} 就必须在这里有条目、并且在中英文语言文件里都有文案。
     */
    public static String failName(int reason) {
        switch (reason) {
            case FAIL_NONE:
                return "none";
            case FAIL_NO_RAIL:
                return "no_rail";
            case FAIL_DEAD_END:
                return "dead_end";
            case FAIL_BLOCKED:
                return "blocked";
            case FAIL_STATION_GONE:
                return "station_gone";
            case FAIL_NO_LOOP:
                return "no_loop";
            case FAIL_NO_STATION:
                return "no_station";
            case FAIL_DOOR_JAMMED:
                return "door_jammed";
            case FAIL_NO_CAR:
                return "no_car";
            default:
                return "unknown";
        }
    }

    /** 全部故障原因，供"一个都不能漏"的自检遍历 */
    public static final int[] ALL_FAILS = {
            FAIL_NONE, FAIL_NO_RAIL, FAIL_DEAD_END, FAIL_BLOCKED, FAIL_STATION_GONE,
            FAIL_NO_LOOP, FAIL_NO_STATION, FAIL_DOOR_JAMMED, FAIL_NO_CAR,
    };

    /** 阶段码 -> 英文短名。语言文件里查 {@code train.phase.<name>}。 */
    public static String phaseName(int phase) {
        switch (phase) {
            case PH_RUN:
                return "run";
            case PH_BRAKING:
                return "braking";
            case PH_DOOR_OPENING:
                return "door_opening";
            case PH_DWELL:
                return "dwell";
            case PH_DOOR_CLOSING:
                return "door_closing";
            case PH_ACCEL:
                return "accel";
            case PH_STALLED:
                return "stalled";
            default:
                return "unknown";
        }
    }

    /** 走向码 -> 英文短名 */
    public static String dirName(int dir) {
        switch (dir) {
            case DIR_NONE:
                return "none";
            case DIR_POS_X:
                return "px";
            case DIR_NEG_X:
                return "nx";
            case DIR_POS_Z:
                return "pz";
            case DIR_NEG_Z:
                return "nz";
            case DIR_CURVE:
                return "curve";
            default:
                return "unknown";
        }
    }

    /**
     * 这个走向是不是"横向（沿 X 轴）"。
     * <p>
     * 车道占用判定要用它：X 向的轨道和 Z 向的轨道在交叉口是**可以同时有车**的
     * （十字路口），但同向的区间必须互斥。把这条算错，交叉口会永远堵死。
     */
    public static boolean isAlongX(int dir) {
        return dir == DIR_POS_X || dir == DIR_NEG_X;
    }

    /** 同上，沿 Z 轴 */
    public static boolean isAlongZ(int dir) {
        return dir == DIR_POS_Z || dir == DIR_NEG_Z;
    }

    // ------------------------------------------------------------------ 事件
    // 内核只负责产出这些键，翻译成广播文本 / 音效 / 字幕是 MC 层的事。
    public static final String EV_TRAIN_SPAWN = "train_spawn";
    public static final String EV_DEPART = "depart";
    public static final String EV_ARRIVE = "arrive";
    public static final String EV_DOOR_OPEN = "door_open";
    public static final String EV_DOOR_CLOSE = "door_close";
    public static final String EV_LOAD = "load";
    public static final String EV_UNLOAD = "unload";
    /** 找不到可停的站 —— 整条线在空转 */
    public static final String EV_NO_STATION = "no_station";
    /** 线不成环，车开到断头了 */
    public static final String EV_NO_LOOP = "no_loop";
    /** 脱轨（车头底下没轨了） */
    public static final String EV_DERAIL = "derail";
    /** 前方断头，开不过去（和 DERAIL 不同：车还在轨上，只是过不去） */
    public static final String EV_DEAD_END = "dead_end";
    /** 前方区间被别的车占着 */
    public static final String EV_BLOCKED = "blocked";
    /** 路线上的站点被拆掉了 */
    public static final String EV_STATION_GONE = "station_gone";
    /** 门开合超时 */
    public static final String EV_DOOR_JAMMED = "door_jammed";
    /** 挂上一节车厢 */
    public static final String EV_CAR_ADDED = "car_added";
    /** 摘掉一节车厢 */
    public static final String EV_CAR_REMOVED = "car_removed";
    /** 编组满载，加不上车厢了 */
    public static final String EV_CAR_FULL = "car_full";
    /** 编组只剩车头，摘不动了 */
    public static final String EV_CAR_EMPTY = "car_empty";
    /** 停在站里等着发车（发车间隔没到） */
    public static final String EV_HOLD = "hold";

    // ------------------------------------------------------------------ 存档槽
    /** 内核存档的槽位数。MC 层照这个开 int[]，逐槽塞/读 */
    public static final int STATE_SLOTS = 40;
    /** 编组信息占的槽数（每节车厢一个走向 + 一个偏移） */
    public static final int CAR_SLOTS = MAX_CARS * 2;
    /** 存档版本号。改槽位含义时必须一起改，读档时对不上就整份丢弃 */
    public static final int SAVE_VERSION = 1;

    /**
     * 两个格坐标的曼哈顿距离。
     * <p>
     * 刻意不做开方 —— 寻路和"离站台还有多远"的判断全用平方/曼哈顿即可，
     * 而且这样完全避开浮点误差带来的"看起来一样但比不出来"的问题。
     */
    public static int manhattan(int ax, int ay, int az, int bx, int by, int bz) {
        return Math.abs(ax - bx) + Math.abs(ay - by) + Math.abs(az - bz);
    }

    /** 两个格坐标是不是同一列（忽略高度，用于把 3×3 站台归一化到一个锚点） */
    public static boolean samePillar(int ax, int az, int bx, int bz) {
        return ax == bx && az == bz;
    }

    // ------------------------------------------------------- 形状位 <-> 走向码
    // 方块层（BlockTrainRail）用 4 个 bit 记"四个方向有没有接上"，
    // 内核层（TrainCore）用 DIR_* 表示"这节的走向"。两边必须靠下面两个函数互转，
    // 不允许任何一方自己手写映射 —— 手写的映射一旦漏一个分支，
    // 症状是"某个方向的轨道在游戏里看着是通的，但车不走"。

    /**
     * 由形状位推断走向码。
     * <p>
     * 规则：只接了 X 的两端 → 沿 X；只接了 Z 的两端 → 沿 Z；
     * 同时接了 X 和 Z → 弯道；一个都没接 → {@link #DIR_NONE}。
     * <p>
     * <b>只接了一头</b>（比如只有东边接上了）也算沿 X —— 那是线的端点，
     * 是合法的（玩家正在一段一段铺）。真正的"不成环"由 {@code TrainCore.isLoop()} 判，
     * 不该在这里就把端点判成非法。
     */
    public static int dirFromShape(int shape) {
        boolean x = (shape & (SHAPE_EAST | SHAPE_WEST)) != 0;
        boolean z = (shape & (SHAPE_NORTH | SHAPE_SOUTH)) != 0;
        if (x && z) {
            return DIR_CURVE;
        }
        if (x) {
            return DIR_POS_X;
        }
        if (z) {
            return DIR_POS_Z;
        }
        return DIR_NONE;
    }

    /**
     * 由走向码给出"默认形状位"。用于<b>没有邻居信息时</b>的兜底
     * （比如刚放下、还没更新过邻居的那一 tick）。
     * <p>
     * 注意 {@link #DIR_POS_X} / {@link #DIR_NEG_X} 在这里都展开成"东西两端都接上" ——
     * 走向码本身只记轴，不记哪一头；哪一头接上是<b>邻居</b>决定的事实，
     * 由 {@code getActualState} 现算，不该由走向码猜。
     */
    public static int shapeFromDir(int dir) {
        switch (dir) {
            case DIR_POS_X:
            case DIR_NEG_X:
                return SHAPE_EAST | SHAPE_WEST;
            case DIR_POS_Z:
            case DIR_NEG_Z:
                return SHAPE_NORTH | SHAPE_SOUTH;
            case DIR_CURVE:
                return SHAPE_MAX;
            default:
                return 0;
        }
    }

    /** 形状位里"接上了几个方向"（用于渲染选型与诊断） */
    public static int shapeDegree(int shape) {
        int n = 0;
        for (int i = 0; i < 4; i++) {
            if ((shape & (1 << i)) != 0) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------- 站台身份与分组
    // 这一节是"哪些站台方块属于同一座站台"的<b>唯一</b>判据。方块层
    // （BlockTrainStation.identityOf）和扫描层（EntityTrain.scanStations）
    // 都必须走这里 —— 两边各写一套的话，玩家右键改的是 A 座、列车读的是 B 座，
    // 表现是"站名改了但车上显示的还是旧名"，一种极难归因的静默故障。

    /**
     * 把三个格坐标打包成一个 Map 键。
     * <p>
     * ★★ <b>只能把它当键用，绝不能反解回坐标。</b>
     * 位运算解包负坐标会给垃圾值（本项目在列车扫描缓存上踩过一次，
     * 见 {@code EntityTrain.railStamp} 的注释）。坐标必须带着坐标走。
     * <p>
     * 布局照 {@code BlockPos.toLong}：x 26 位 | y 12 位 | z 26 位，正好 64 位，
     * 在 MC 世界的坐标范围内是双射。
     */
    public static long cellKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) y & 0xFFFL) << 26
                | ((long) z & 0x3FFFFFFL);
    }

    /**
     * a 是不是比 b 更该当一座站台的"身份证格"：<b>x 最小 → z 最小 → y 最小</b>。
     * <p>
     * 先 x 再 z 是"取最西北那一格"，和玩家看地图的直觉一致；最后才比 y，
     * 是为了让"同一列上叠了两格站台"有确定答案（取下面那格）。
     * <p>
     * 为什么需要一个<b>身份证格</b>而不是"整片站台"：内核的站点表是按格存的
     * （{@code TrainCore.addStation(x,y,z,...)}），站名/停站时间要有一个唯一的落脚点，
     * 而且它必须<b>由世界唯一决定</b> —— 不能是"玩家第一个放的那格"那种隐藏状态，
     * 否则拆掉那一格之后站名就飘了。
     */
    public static boolean betterIdentity(int ax, int ay, int az, int bx, int by, int bz) {
        if (ax != bx) {
            return ax < bx;
        }
        if (az != bz) {
            return az < bz;
        }
        return ay < by;
    }
}

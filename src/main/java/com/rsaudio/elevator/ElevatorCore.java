package com.rsaudio.elevator;

/**
 * 电梯内核：状态机 + 梯形速度曲线 + 顺路停靠 + 故障判定。<b>零 Minecraft 依赖。</b>
 * <p>
 * 服务端权威只有一份（{@code TileElevatorController} 持有），客户端只看快照。
 * 单测在 {@code tools/check_elevator_core.py}。
 *
 * <h3>★★★ 为什么位置是"路径索引"</h3>
 * {@link #pos} 是 double 的路径索引（0 = 最低层那一格，5 = 高五格那一格），
 * <b>不是 Y 坐标</b>。索引 → 世界偏移由 {@link ElevatorSpec#axisStep} 唯一换算：
 * 竖直井道只有 Y 变，斜面井道 Y 和 X 一起变。一套运动学喂三种电梯。
 *
 * <h3>★★ 轿厢是方块、乘客走"整步"</h3>
 * 轿厢在世界上是一组真实方块，只能落在<b>整数索引</b>上（见 {@link #cellIndex()}）。
 * 所以乘客的搬运也必须跟方块同步 —— 做成"整数索引一变就整格搬"，而不是按 double 平滑搬。
 * <p>
 * 这看起来是偷懒，其实是唯一正确的做法：MC 的方块是整数网格，方块一上一下就是一格。
 * 如果按 double 平滑搬乘客，乘客就会有半程悬在轿厢上方的空气里，重力每刻把他往下拽、
 * 下一 tick 又被抬起来 —— 表现是<b>人在轿厢里持续抖动</b>，而且找不到原因。
 * 代价是电梯跑起来是"一格一格"的（工业货梯本来就这样）。想要丝滑就得给轿厢做 TESR，
 * 那是另一条路（也是本项目在列车上踩满坑的那条）。
 *
 * <h3>★ 三处最容易写错、单测专门钉住的地方</h3>
 * <ol>
 *   <li><b>爬行速度下限</b>：梯形曲线最后一段会退化成无穷小 → 永远差一点到不了站。
 *       见 {@link ElevatorSpec#MIN_SPEED}。</li>
 *   <li><b>目标楼层下标 vs 路径索引</b>：{@code floors[]} 里存的是路径索引，
 *       而 {@code target} 存的是<b>数组下标</b>。混一次就是"电梯跑去一个不存在的层"。</li>
 *   <li><b>顺路捡人必须判"刹得住"</b>：不判的话电梯会在过了头之后才发现要停，
 *       表现是"明明按了 5 楼，电梯冲过去又倒回来"。</li>
 * </ol>
 */
public final class ElevatorCore {

    /** NBT 存档版本。改过字段布局就 +1，{@code loadState} 认不出就整份丢弃（宁可回默认也别读半份） */
    public static final int SAVE_VERSION = 1;
    /** 存档数组头长度（后面跟着 floors[] 和一张呼叫位图） */
    private static final int SAVE_HEADER = 10;

    private static final int[] NO_FLOORS = new int[0];

    // ------------------------------------------------------------------ 固有
    private final int type;
    private final ElevatorSettings.Preset preset;

    // ------------------------------------------------------------------ 井道（由控制器扫描后灌进来）
    private int[] floors = NO_FLOORS;      // 升序的路径索引
    private int shaftMin = 0;              // 井道最低索引
    private int shaftMax = 0;              // 井道最高索引
    private int shaftMissing = 0;          // 井道缺口格数

    // ------------------------------------------------------------------ 运动
    private double pos;                    // 路径索引
    private double vel;                    // 速度（>= 0，方向在 dir 里）
    private int dir;                       // -1 / 0 / +1
    private int target = -1;               // ★ floors 的<b>下标</b>，不是路径索引；-1 = 无目标

    // ------------------------------------------------------------------ 状态
    private int state = ElevatorSpec.ST_IDLE;
    private int doorTimer;
    private int fault = ElevatorSpec.FAULT_NONE;
    /**
     * 控制器有没有认到井道。
     * <p>
     * ★★ 为什么要单独一个标志：{@link ElevatorSpec#FAULT_SHAFT}（找不到井道）和
     * {@link ElevatorSpec#FAULT_NO_FLOOR}（井道在、但一层层门都没登记）对玩家来说
     * <b>要修的地方完全不同</b>。内核自己分不出来 —— 它只看到"层表是空的"。
     * 所以由控制器告诉它。
     */
    private boolean shaftFound = true;

    /** 呼叫：按<b>楼层下标</b>索引（和 floors[] 一一对应），不是按路径索引 */
    private boolean[] calls = new boolean[ElevatorSpec.MAX_FLOORS];
    private int callCount;

    /** 累计运行层数（GUI 上"已运行 N 层"这种读数；也让单测能验证"真的动过"） */
    private int trips;

    // ------------------------------------------------------------------ 事件出口
    // ★ 内核不许直接说话（不碰 I18n、不碰聊天栏），只把"发生了什么 + 参数"排进小队列，
    //   由控制器的 BROADCAST 层决定"要不要念、念给谁、念多大声"。
    //   这样"降噪"永远只做在一处，不会出现"内核也想说话、方块也想说话"的双份播报。
    private static final int EV_QUEUE = 12;
    private final int[] evCode = new int[EV_QUEUE];
    private final int[] evArg = new int[EV_QUEUE];
    private int evCount;

    public ElevatorCore(int type) {
        this.type = (type < 0 || type >= ElevatorSpec.TYPE_COUNT) ? ElevatorSpec.TYPE_PASSENGER : type;
        this.preset = ElevatorSettings.of(this.type);
    }

    // ================================================================== 只读接口

    public int type() {
        return type;
    }

    public ElevatorSettings.Preset preset() {
        return preset;
    }

    public double pos() {
        return pos;
    }

    public double vel() {
        return vel;
    }

    public int dir() {
        return dir;
    }

    public int state() {
        return state;
    }

    public int fault() {
        return fault;
    }

    public int trips() {
        return trips;
    }

    /** 井道缺口格数（控制器扫描时灌进来，GUI 直接显示"缺 N 格"） */
    public int shaftMissing() {
        return shaftMissing;
    }

    public int shaftMin() {
        return shaftMin;
    }

    public int shaftMax() {
        return shaftMax;
    }

    public int floorCount() {
        return floors.length;
    }

    /** 第 i 层对应的<b>路径索引</b>。越界返回 -1（不抛异常：GUI 与快照都可能拿到过期下标） */
    public int floorIndex(int i) {
        if (i < 0 || i >= floors.length) {
            return -1;
        }
        return floors[i];
    }

    public boolean hasCall(int floorSlot) {
        return floorSlot >= 0 && floorSlot < floors.length && calls[floorSlot];
    }

    public int callCount() {
        return callCount;
    }

    /** 目标层在 {@code floors[]} 里的下标；没有目标给 -1。 */
    public int targetSlot() {
        return target;
    }

    /** 目标层的路径索引；没有目标给 -1。 */
    public int targetIndex() {
        return target < 0 ? -1 : floorIndex(target);
    }

    /**
     * 轿厢此刻占据的<b>整数路径索引</b>（方块层放格子、乘客整步搬运都用它）。
     * <p>
     * ★ 用 {@code Math.round} 而不是 {@code (int)}：{@code (int)} 是朝零截断，
     * 索引小于 1 的时候 0.9 会变成 0 → 轿厢在到站前最后一格<b>提前跳过去</b>。
     */
    public int cellIndex() {
        return (int) Math.floor(pos + 0.5D);
    }

    /**
     * 轿厢此刻所在的那一层在 {@code floors[]} 里的下标；在两楼之间给 -1。
     * <p>
     * ★ 判据是"整数索引正好等于某一层"，不是"离哪层近" ——
     * 后者会让轿厢在两楼正中间时被算成"已经在上面那层"，于是门提前开。
     */
    public int currentSlot() {
        int cell = cellIndex();
        for (int i = 0; i < floors.length; i++) {
            if (floors[i] == cell) {
                return i;
            }
        }
        return -1;
    }

    /** 门开度 0..1（渲染/显示用）。门在关的时候要能从 1 平滑回 0。 */
    public double doorAmount() {
        switch (state) {
            case ElevatorSpec.ST_DOOR_OPEN:
                return 1.0D;
            case ElevatorSpec.ST_DOOR_OPENING:
                return clamp01(doorTimer / (double) ElevatorSpec.DOOR_SLIDE_TICKS);
            case ElevatorSpec.ST_DOOR_CLOSING:
                return clamp01(1.0D - doorTimer / (double) ElevatorSpec.DOOR_SLIDE_TICKS);
            default:
                return 0.0D;
        }
    }

    /** 门是不是处于"玩家可以进出"的状态（门开度 > 一半） */
    public boolean passable() {
        return doorAmount() > 0.5D;
    }

    public boolean isMoving() {
        return state == ElevatorSpec.ST_MOVING;
    }

    // ================================================================== 事件出口

    public int eventCount() {
        return evCount;
    }

    public int eventCode(int i) {
        return (i < 0 || i >= evCount) ? -1 : evCode[i];
    }

    public int eventArg(int i) {
        return (i < 0 || i >= evCount) ? -1 : evArg[i];
    }

    /** 控制器把事件取走之后必须调这个，否则同一件事会被反复播报 */
    public void clearEvents() {
        evCount = 0;
    }

    private void emit(int code, int arg) {
        if (evCount < EV_QUEUE) {
            evCode[evCount] = code;
            evArg[evCount] = arg;
            evCount++;
        }
    }

    // ================================================================== 井道注入

    /**
     * 控制器扫描完井道后调这个。<b>唯一</b>能改 {@code floors} 的入口。
     * <p>
     * ★★★ 这条是本次设计里最要紧的一道门禁：井道是玩家砌的，随时会变。
     * 如果允许别处（比如 GUI、NBT 读档）直接塞 floors，就会出现
     * "轿厢停在一层、floors 里却没有这一层"——那之后的每一次 {@link #currentSlot()}
     * 都是 -1，门永远不开，而且要开起来才发现。
     * <p>
     * 传进来的数组会被排序 + 去重 + 截断到 {@link ElevatorSpec#MAX_FLOORS}。
     *
     * @param floorIndices 层门的路径索引（可以乱序、可以有重复、可以为空）
     * @param min          井道最低索引
     * @param max          井道最高索引
     * @param missing      井道缺口格数（>0 会让电梯停用并报成因）
     * @return 是否发生了变化（控制器据此决定要不要立刻推一次快照）
     */
    public boolean setShaft(int[] floorIndices, int min, int max, int missing) {
        int[] sorted = normalize(floorIndices);
        boolean changed = !sameFloors(sorted)
                || min != shaftMin || max != shaftMax || missing != shaftMissing;
        floors = sorted;
        shaftMin = Math.min(min, max);
        shaftMax = Math.max(min, max);
        shaftMissing = Math.max(0, missing);

        // 呼叫位图必须跟着新层表走：层数变少时超出的那一截要清掉，
        // 否则 callCount 会算进一些"没有对应楼层"的呼叫 → 电梯永远在找下一层。
        boolean[] next = new boolean[ElevatorSpec.MAX_FLOORS];
        int nextCount = 0;
        int keep = Math.min(calls.length, floors.length);
        for (int i = 0; i < keep; i++) {
            if (calls[i]) {
                next[i] = true;
                nextCount++;
            }
        }
        if (nextCount != callCount) {
            changed = true;
        }
        calls = next;
        callCount = nextCount;

        // 目标层下标可能因为层表变短而越界 —— 当场作废，别让它去索引一个不存在的层。
        if (target >= floors.length) {
            target = -1;
        }

        // 轿厢被夹到井道范围内。★ 读档时 pos 可能来自"井道已经被拆了一半"的存档。
        if (floors.length > 0) {
            double lo = floors[0];
            double hi = floors[floors.length - 1];
            if (pos < lo) {
                pos = lo;
                vel = 0.0D;
                dir = 0;
                changed = true;
            } else if (pos > hi) {
                pos = hi;
                vel = 0.0D;
                dir = 0;
                changed = true;
            }
        }

        // 故障复检：井道好了就自己回来，不需要玩家去按什么"复位"。
        int nextFault = evaluateFault();
        if (nextFault != fault) {
            if (nextFault == ElevatorSpec.FAULT_NONE) {
                fault = ElevatorSpec.FAULT_NONE;
                if (state == ElevatorSpec.ST_FAULT) {
                    state = ElevatorSpec.ST_IDLE;
                }
                emit(ElevatorSpec.EV_REPAIRED, 0);
            } else {
                fault = nextFault;
                state = ElevatorSpec.ST_FAULT;
                vel = 0.0D;
                dir = 0;
                emit(ElevatorSpec.EV_FAULT, fault);
            }
            changed = true;
        }
        return changed;
    }

    private int evaluateFault() {
        if (!shaftFound) {
            return ElevatorSpec.FAULT_SHAFT;
        }
        if (floors.length == 0) {
            return ElevatorSpec.FAULT_NO_FLOOR;
        }
        if (shaftMissing > 0) {
            return ElevatorSpec.FAULT_BLOCKED;
        }
        return ElevatorSpec.FAULT_NONE;
    }

    /**
     * 告诉内核"控制器认没认到井道"。
     * <p>
     * ★ 这个方法<b>故意只存标志、不当场重算故障</b>：
     * 控制器总是"先 setShaftFound(false) 再 setShaft(空表)"这样成对调用，
     * 如果这里也算一次，就会在中间态多发一条毫无意义的故障事件
     * （先报"没有层"，下一行立刻改成"找不到井道"）。重算只留在 {@link #setShaft} 一处。
     */
    public void setShaftFound(boolean found) {
        this.shaftFound = found;
    }

    public boolean shaftFound() {
        return shaftFound;
    }

    private static int[] normalize(int[] raw) {
        if (raw == null || raw.length == 0) {
            return NO_FLOORS;
        }
        int[] tmp = new int[raw.length];
        int n = 0;
        for (int v : raw) {
            // ★ 负数索引直接丢掉：井道只会往"上"增长，负索引说明扫描时算错了。
            if (v < 0) {
                continue;
            }
            boolean dup = false;
            for (int i = 0; i < n; i++) {
                if (tmp[i] == v) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                tmp[n++] = v;
            }
        }
        int[] out = new int[n];
        System.arraycopy(tmp, 0, out, 0, n);
        java.util.Arrays.sort(out);
        if (out.length > ElevatorSpec.MAX_FLOORS) {
            int[] cut = new int[ElevatorSpec.MAX_FLOORS];
            System.arraycopy(out, 0, cut, 0, ElevatorSpec.MAX_FLOORS);
            return cut;
        }
        return out;
    }

    private boolean sameFloors(int[] other) {
        if (other.length != floors.length) {
            return false;
        }
        for (int i = 0; i < other.length; i++) {
            if (other[i] != floors[i]) {
                return false;
            }
        }
        return true;
    }

    // ================================================================== 呼叫

    /**
     * 呼叫第 {@code floorSlot} 层。
     *
     * @return 是否受理。★ 被拒时内核<b>自己</b>排一条 {@link ElevatorSpec#EV_CALL_DENIED} 事件
     *         （不让调用方去拼文案 —— 那样每个调用方都会编出不一样的说法）。
     */
    public boolean call(int floorSlot) {
        if (floorSlot < 0 || floorSlot >= floors.length) {
            emit(ElevatorSpec.EV_CALL_DENIED, floorSlot);
            return false;
        }
        if (fault == ElevatorSpec.FAULT_BLOCKED) {
            // 井道堵着：受理了也去不了，直接拒（并让控制器按"故障"那条路报成因）
            emit(ElevatorSpec.EV_CALL_DENIED, floorSlot);
            return false;
        }
        if (!calls[floorSlot]) {
            calls[floorSlot] = true;
            callCount++;
            // ★★ 只有"从来没被叫过"的那一次才发事件。
            //   同一 tick 里玩家连点五下呼叫钮（GUI 一次点击可能换来好几个包），
            //   不去重就会在聊天栏里看到五条一模一样的「收到呼叫」——
            //   这正是本项目反复踩过的"刷屏"坑，去重必须做在**内核**这一层，
            //   因为方块层根本分不清"五次真按"和"一次按了五下"。
            emit(ElevatorSpec.EV_CALL, floorSlot);
        }
        return true;
    }

    /** 清掉所有呼叫（检修用 / GUI 上的"取消"按钮） */
    public int clearCalls() {
        int n = callCount;
        calls = new boolean[ElevatorSpec.MAX_FLOORS];
        callCount = 0;
        target = -1;
        return n;
    }

    /**
     * "开门键"：门已经开着就重新计时（人还在往里走），门关到一半就重新打开。
     * ★ 和 F9 的拉杆一样，这类"按住"语义必须有个显式的收尾动作，不能靠自动超时兜。
     */
    public void holdDoor() {
        if (state == ElevatorSpec.ST_DOOR_OPEN) {
            doorTimer = Math.max(0, doorTimer - ElevatorSpec.DOOR_HOLD_REFRESH);
        } else if (state == ElevatorSpec.ST_DOOR_CLOSING) {
            state = ElevatorSpec.ST_DOOR_OPENING;
            doorTimer = ElevatorSpec.DOOR_SLIDE_TICKS - doorTimer;
        } else if (state == ElevatorSpec.ST_IDLE) {
            int slot = currentSlot();
            if (slot >= 0) {
                state = ElevatorSpec.ST_DOOR_OPENING;
                doorTimer = 0;
            }
        }
    }

    /** "关门键"：门开着就立刻开始关（不等满 {@code doorOpenTicks}） */
    public void nudgeClose() {
        if (state == ElevatorSpec.ST_DOOR_OPEN) {
            state = ElevatorSpec.ST_DOOR_CLOSING;
            doorTimer = 0;
        }
    }

    // ================================================================== 主循环

    public void tick() {
        // ★★★ 这里**故意不清**事件队列。
        //   清在这里会有一个极隐蔽的后果：玩家按呼叫钮 → handleAction 里 call() 排了一条
        //   "收到呼叫"事件 → 下一 tick 控制器先 tick()（当场清空）再取事件 → 永远取不到。
        //   症状是"电梯动了，但聊天栏里从来没说过收到呼叫"，而且只在"事件由外部动作产生"
        //   这一类上出现（tick 内部产生的事件不受影响），非常难查。
        //   ⇒ 约定改成：**控制器取走事件之后自己调 clearEvents()**（每 tick 取一次即可）。
        if (floors.length == 0) {
            // 没井道就没电梯。★ 这一句同时兜住"控制器刚放下、还没扫过井道"和
            //   "井道被拆光了"两种情况 —— 不兜的话内核会以 IDLE 假扮一切正常。
            //   ★ 故障成因要尊重 shaftFound：认不到井道报 SHAFT，井道在但没层报 NO_FLOOR，
            //     这两个要玩家去修的地方完全不同。
            fault = shaftFound ? ElevatorSpec.FAULT_NO_FLOOR : ElevatorSpec.FAULT_SHAFT;
            vel = 0.0D;
            dir = 0;
            state = ElevatorSpec.ST_FAULT;
            return;
        }

        if (fault != ElevatorSpec.FAULT_NONE) {
            // ★ 故障期间<b>什么都不做</b>，但也不清状态：
            //   保留 pos/state 是为了修好之后能在原地继续，而不是"从第一层重来"。
            vel = 0.0D;
            dir = 0;
            state = ElevatorSpec.ST_FAULT;
            return;
        }

        switch (state) {
            case ElevatorSpec.ST_IDLE:
                tickIdle();
                break;
            case ElevatorSpec.ST_DOOR_OPENING:
                tickDoorOpening();
                break;
            case ElevatorSpec.ST_DOOR_OPEN:
                tickDoorOpen();
                break;
            case ElevatorSpec.ST_DOOR_CLOSING:
                tickDoorClosing();
                break;
            case ElevatorSpec.ST_MOVING:
                tickMoving();
                break;
            default:
                state = ElevatorSpec.ST_IDLE;
                break;
        }
    }

    private void tickIdle() {
        if (floors.length == 0) {
            return;
        }
        // 本层有呼叫 → 直接开门（人就在门口）
        int here = currentSlot();
        if (here >= 0 && calls[here]) {
            calls[here] = false;
            callCount--;
            openDoors();
            return;
        }
        // 别处有呼叫 → 选一个目标开跑
        if (callCount > 0) {
            int next = pickNextCall();
            if (next >= 0) {
                target = next;
                int d = Double.compare(floors[next], pos);
                dir = d > 0 ? 1 : (d < 0 ? -1 : 0);
                if (dir == 0) {
                    // 呼叫的层就在脚下（浮点误差导致 pos 差一点点）—— 直接吸附过去开门，
                    // 不要启动"移动"，否则会看到电梯原地抽一下。
                    pos = floors[next];
                    tickIdle();
                    return;
                }
                state = ElevatorSpec.ST_MOVING;
                // ★ 事件参数必须是"离开的那一格的<b>整数索引</b>"，不是 floors[0]，
                //   也不能是 floors[here]（here 在两楼之间时是 -1）。
                //   播报里要念出"从 N 楼出发"，报错一层就等于给玩家指错方向。
                emit(ElevatorSpec.EV_DEPART, cellIndex());
            }
        }
    }

    /**
     * 挑下一层。
     * <p>
     * ★ 规则是"<b>最近的呼叫优先，平手时偏向当前行进方向</b>"，而不是"一路往上扫到底"。
     * 后者在只有一台电梯的设施里会让玩家等很久（想去 2 楼，电梯却先去 7 楼）。
     * 平手偏向当前方向 = 常见的 SCAN 调度的最简形式。
     */
    private int pickNextCall() {
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < floors.length; i++) {
            if (!calls[i]) {
                continue;
            }
            double d = Math.abs(floors[i] - pos);
            if (d < bestDist - ElevatorSpec.POS_EPS) {
                bestDist = d;
                best = i;
            } else if (Math.abs(d - bestDist) <= ElevatorSpec.POS_EPS && best >= 0) {
                // 平手：同方向的优先
                int candDir = Double.compare(floors[i], pos);
                if (candDir == dir) {
                    best = i;
                }
            }
        }
        return best;
    }

    private void openDoors() {
        state = ElevatorSpec.ST_DOOR_OPENING;
        doorTimer = 0;
        // ★ 同理：用 cellIndex() 而不是 floors[currentSlot()] —— currentSlot() 给 -1 时
        //   floors[-1] 是越界（Java 会抛 ArrayIndexOutOfBounds，直接崩 tick）。
        emit(ElevatorSpec.EV_DOOR_OPENING, cellIndex());
    }

    private void tickDoorOpening() {
        doorTimer++;
        if (doorTimer >= ElevatorSpec.DOOR_SLIDE_TICKS) {
            state = ElevatorSpec.ST_DOOR_OPEN;
            doorTimer = 0;
        }
    }

    private void tickDoorOpen() {
        doorTimer++;
        // 门开着的时候又有人按本层 → 重新计时（等于"别关，我还没进完"）
        int here = currentSlot();
        if (here >= 0 && calls[here]) {
            calls[here] = false;
            callCount--;
            doorTimer = 0;
            return;
        }
        if (doorTimer >= preset.doorOpenTicks) {
            state = ElevatorSpec.ST_DOOR_CLOSING;
            doorTimer = 0;
            // ★ 用 cellIndex()：这里 here 可能是 -1（轿厢在两楼之间被"卡"着开门时），
            //   floors[-1] 会直接抛异常把整个 tick 打断。
            emit(ElevatorSpec.EV_DOOR_CLOSING, cellIndex());
        }
    }

    private void tickDoorClosing() {
        // ★ 关到一半有人按本层 → 重新打开。这是电梯最基本的体贴，
        //   少了它，玩家会在门口被门夹一下然后看着电梯走掉。
        int here = currentSlot();
        if (here >= 0 && calls[here]) {
            calls[here] = false;
            callCount--;
            state = ElevatorSpec.ST_DOOR_OPENING;
            doorTimer = ElevatorSpec.DOOR_SLIDE_TICKS - doorTimer;
            return;
        }
        doorTimer++;
        if (doorTimer >= ElevatorSpec.DOOR_SLIDE_TICKS) {
            state = ElevatorSpec.ST_IDLE;
            doorTimer = 0;
        }
    }

    private void tickMoving() {
        if (target < 0 || target >= floors.length) {
            state = ElevatorSpec.ST_IDLE;
            vel = 0.0D;
            dir = 0;
            return;
        }

        // ---- 顺路捡人：行进路上如果有一层也在叫人，而且"还刹得住"，就改目标
        int detour = pickDetour();
        if (detour >= 0 && detour != target) {
            target = detour;
        }

        double destIdx = floors[target];
        double dist = destIdx - pos;
        if (Math.abs(dist) <= ElevatorSpec.POS_EPS) {
            arrive();
            return;
        }

        int newDir = Double.compare(destIdx, pos);
        if (dir == 0) {
            // ★★ 读档兜底：存档里可能是"state=MOVING 但 dir=0"（旧版本存的、或者写档时正好在换向点）。
            //   不补这一句的话位移恒为 0 —— 电梯永远停在两楼之间，
            //   玩家看到的是"电梯不动"，而日志、异常、校验全都是干净的。
            dir = newDir;
        }

        // ---- ★★★ 只按"沿行进方向的前方剩余"算速度，绝不用 |dist|
        //   用 |dist| 有一个致命分支：如果目标跑到我们身后（被改坏的存档、或将来某条
        //   合法的掉头逻辑），|dist| 会随着"越跑越远"而变大 → 目标速度不降反升 →
        //   电梯一路加速永不停车。玩家看到"电梯冲出去了"，日志一个字都没有。
        double signed = dir * dist;
        if (signed <= ElevatorSpec.POS_EPS) {
            // 目标已不在前方：先原地刹停，停住了再换向（这才是"过冲之后倒回来"的正确画法）
            vel = Math.max(0.0D, vel - preset.accel);
            if (vel <= ElevatorSpec.MIN_SPEED) {
                vel = 0.0D;
                dir = newDir;
            }
            return;
        }

        // ---- 梯形曲线：目标速度 = min(顶速, sqrt(2*a*前方剩余))，且不低于爬行下限
        //   ★ 那个下限（MIN_SPEED）不是调手感用的，是**收敛性的保证**：
        //     纯 sqrt(2*a*ε) 在 ε→0 时趋于 0，电梯会永远差最后一点点到不了站。
        double want = Math.min(preset.vmax, Math.sqrt(2.0D * preset.accel * signed));
        want = Math.max(want, ElevatorSpec.MIN_SPEED);
        if (vel < want) {
            vel = Math.min(want, vel + preset.accel);
        } else {
            vel = Math.max(want, vel - preset.accel);
        }

        if (vel >= signed) {
            pos = destIdx;                     // ★ 一步跨过终点就直接吸附，绝不过冲
            arrive();
            return;
        }
        pos += dir * vel;
    }

    /**
     * 行进方向上有没有"还刹得住"的顺路呼叫。
     * <p>
     * ★★★ 这条断言就是"刹得住"本身：剩余距离必须 <b>不小于</b> 制动距离，
     * 否则电梯会在过了那一层之后才发现要停，只能倒回去 ——
     * 玩家看到的是"我按了 5 楼，电梯冲上 6 楼又倒回来 5 楼"。
     * 所以这里宁可<b>不停</b>（继续去原目标，回头再来），也不做停不下来的停靠。
     */
    private int pickDetour() {
        if (dir == 0 || callCount == 0) {
            return target;
        }
        double destIdx = (target >= 0) ? floors[target] : pos;
        double stopNeed = stoppingDistance();
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i < floors.length; i++) {
            if (!calls[i] || i == target) {
                continue;
            }
            double fi = floors[i];
            boolean ahead = (dir > 0) ? (fi > pos + ElevatorSpec.POS_EPS && fi < destIdx - ElevatorSpec.POS_EPS)
                                      : (fi < pos - ElevatorSpec.POS_EPS && fi > destIdx + ElevatorSpec.POS_EPS);
            if (!ahead) {
                continue;
            }
            double d = Math.abs(fi - pos);
            if (d < stopNeed) {
                continue;                       // 刹不住了，这次不停它
            }
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best >= 0 ? best : target;
    }

    /** 从当前速度减到爬行下限所需走过的索引距离（加一格余量，避免"理论上刚好"的临界） */
    private double stoppingDistance() {
        return (vel * vel) / (2.0D * preset.accel) + vel + ElevatorSpec.POS_EPS;
    }

    private void arrive() {
        double destIdx = (target >= 0 && target < floors.length) ? floors[target] : pos;
        pos = destIdx;
        vel = 0.0D;
        dir = 0;
        trips++;
        int slot = target;
        target = -1;
        if (slot >= 0 && slot < floors.length) {
            calls[slot] = false;
            callCount--;
            emit(ElevatorSpec.EV_ARRIVE, floors[slot]);
        } else {
            emit(ElevatorSpec.EV_ARRIVE, (int) Math.round(pos));
        }
        state = ElevatorSpec.ST_DOOR_OPENING;
        doorTimer = 0;
        emit(ElevatorSpec.EV_DOOR_OPENING, (int) Math.round(pos));
    }

    // ================================================================== 存档

    /**
     * 序列化成 int 数组（NBT 里存成一个 {@code IntArray}，和 DMR/F9 一样）。
     * <p>
     * 布局：{@code [0]=版本 [1]=type [2]=pos*1000 [3]=vel*1000 [4]=dir [5]=state
     * [6]=doorTimer [7]=target [8]=fault [9]=层数, 之后是层表, 最后一张呼叫位图}
     * <p>
     * ★ 井道（floors）也一起存：读档时控制器会立刻重扫一遍并覆盖它，
     * 但存了就能保证"区块还没加载完的那一两秒"里电梯不至于把轿厢吸到第 0 层。
     */
    public int[] saveState() {
        int[] d = new int[SAVE_HEADER + floors.length + 1];
        d[0] = SAVE_VERSION;
        d[1] = type;
        d[2] = (int) Math.round(pos * 1000.0D);
        d[3] = (int) Math.round(vel * 1000.0D);
        d[4] = dir;
        d[5] = state;
        d[6] = doorTimer;
        d[7] = target;
        d[8] = fault;
        d[9] = floors.length;
        System.arraycopy(floors, 0, d, SAVE_HEADER, floors.length);
        d[SAVE_HEADER + floors.length] = packCalls();
        return d;
    }

    /**
     * 读档。★ 认不出（版本不符 / 长度不对）就<b>整份丢弃</b>、保持默认值。
     * 半份读进去比不读更糟：会出现"pos 从存档来、floors 是空的"这种自相矛盾的状态。
     */
    public boolean loadState(int[] d) {
        if (d == null || d.length < SAVE_HEADER + 1) {
            return false;
        }
        if (d[0] != SAVE_VERSION) {
            return false;
        }
        int n = d[9];
        if (n < 0 || n > ElevatorSpec.MAX_FLOORS || d.length < SAVE_HEADER + n + 1) {
            return false;
        }
        pos = d[2] / 1000.0D;
        vel = Math.max(0.0D, d[3] / 1000.0D);
        dir = clampDir(d[4]);
        state = (d[5] >= 0 && d[5] <= ElevatorSpec.ST_FAULT) ? d[5] : ElevatorSpec.ST_IDLE;
        doorTimer = Math.max(0, d[6]);
        target = d[7];
        fault = (d[8] >= 0 && d[8] <= ElevatorSpec.FAULT_OVERLOAD) ? d[8] : ElevatorSpec.FAULT_NONE;

        int[] f = new int[n];
        System.arraycopy(d, SAVE_HEADER, f, 0, n);
        floors = normalize(f);

        int mask = d[SAVE_HEADER + n];
        calls = new boolean[ElevatorSpec.MAX_FLOORS];
        callCount = 0;
        for (int i = 0; i < floors.length; i++) {
            if ((mask & (1 << i)) != 0) {
                calls[i] = true;
                callCount++;
            }
        }
        if (target >= floors.length) {
            target = -1;
        }
        return true;
    }

    private int packCalls() {
        int mask = 0;
        for (int i = 0; i < floors.length && i < 31; i++) {
            if (calls[i]) {
                mask |= (1 << i);
            }
        }
        return mask;
    }

    // ================================================================== 小工具

    private static int clampDir(int d) {
        return d > 0 ? 1 : (d < 0 ? -1 : 0);
    }

    private static double clamp01(double v) {
        if (v < 0.0D) {
            return 0.0D;
        }
        return v > 1.0D ? 1.0D : v;
    }

    /**
     * 状态名（给 GUI 直接用的便捷方法，等价于 {@code ElevatorSpec.stateName(state())}）。
     * ★ 抽出来只是因为 GUI 里这一行会写十几遍。
     */
    public String stateKey() {
        return ElevatorSpec.stateName(state);
    }

    /** 故障名；无故障给 "none"。 */
    public String faultKey() {
        return ElevatorSpec.faultName(fault);
    }
}

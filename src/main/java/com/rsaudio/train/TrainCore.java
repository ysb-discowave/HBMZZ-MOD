package com.rsaudio.train;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 列车内核：轨道图 + 沿轨行进 + 站点停靠时序 + 线路自检。
 * <p>
 * <b>零 Minecraft 依赖。</b>一辆车 = 一个 {@code TrainCore}。它不知道方块叫什么、
 * 不知道世界长什么样，只知道两件事：
 * <ol>
 *   <li>{@link #setRail} 交给它的<b>轨道图</b>（哪些格有轨、各通向哪里）；</li>
 *   <li>{@link #setStations} 交给它的<b>站点表</b>（哪些格是站台、叫什么名字、停多久）。</li>
 * </ol>
 * 它每 tick 往外吐：车头在哪一格、在格子里的偏移、朝向、速度、门开没开、
 * 以及<b>为什么不走</b>。
 * <p>
 * <b>★ 这个类为什么值得单测。</b>F9 坏了会炸，很显眼；列车坏了什么都不发生 ——
 * 它只是安静地停在那儿。而"停着不走"至少有六种互相毫无关系的成因
 * （脱轨 / 断头 / 前车占道 / 站被拆 / 不成环 / 门卡住），
 * 在游戏里玩家看到的都是"一节静止的车厢"。所以这里把每种成因都做成
 * {@link TrainSpec#FAIL_*} 里的独立枚举，并且在它<b>第一次</b>发生时就报出来，
 * 而不是等玩家来问"它怎么不动了"。
 * <p>
 * 线程模型：和 F9/DMR 一样，只在服务端主线程 tick，事件用 {@link #drainEvents()} 取走。
 */
public final class TrainCore {

    // ================================================================ 轨道图

    /** 轨道图里的一个节点：一格轨道。 */
    private static final class Rail {
        final int x;
        final int y;
        final int z;
        /** 走向码，见 {@link TrainSpec#DIR_*} */
        int dir = TrainSpec.DIR_NONE;
        /** 前一格（来路）。-1 表示没有 */
        int prev = -1;
        /** 后一格（去路）。-1 表示没有 */
        int next = -1;
        /** 这一格上有没有站。0 = 没有，否则是站点 id + 1 */
        int station = 0;
        /** 是不是坡道 */
        boolean slope = false;
        /**
         * 邻接表：这一格直接挨着的所有轨道格（不含方向含义）。
         * <p>
         * ★ 必须和 prev/next 分开。prev/next 是<b>有向</b>的、由全图遍历定下来的
         * "顺行 / 逆行"；adjs 是<b>无向</b>的、每格自己就能收全的"谁挨着我"。
         * 把这两件事混成一组字段，就会写出"每格自己分配方向"的代码 ——
         * 而那是没有全局视野的，环线的拐角必然被分错（见 {@link #rebuildLinks()}）。
         */
        final List<Rail> adjs = new ArrayList<Rail>(4);

        Rail(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /** 站点表里的一项。 */
    private static final class Station {
        /**
         * 站台的<b>身份证格</b>坐标 —— 一片连通的站台方块里最西北的那一格
         * （{@code BlockTrainStation.identityOf}）。
         * <p>
         * ★ 早先这里是"3×3 网格的西北角"，那是错的：它要求玩家把站台对齐到
         * 3 的倍数，否则锚点落在一格空气上、整座站在扫描里凭空消失。
         * 现在坐标由世界里的方块唯一决定，铺一格是一站、铺一条月台也是一站。
         */
        final int x;
        final int y;
        final int z;
        /** 站名。允许空串（"未命名站台"） */
        String name;
        /** 停站时间 (tick) */
        int dwell;
        /** 这个站点对应的轨道节点下标。-1 = 站台没有贴着轨道（配不上） */
        int rail = -1;

        Station(int x, int y, int z, String name, int dwell) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.dwell = dwell;
        }
    }

    // ================================================================ 字段

    private final TrainSettings cfg;
    private final Random rng;

    /** 轨道图：格坐标 -> 节点。用 LinkedHashMap 保证遍历顺序稳定（可复现） */
    private final Map<Long, Rail> rails = new LinkedHashMap<Long, Rail>();
    /** 站点表：锚点格坐标 -> 站点 */
    private final Map<Long, Station> stations = new LinkedHashMap<Long, Station>();
    /** 按发现顺序排好的站点列表，就是这趟车的停站顺序 */
    private final List<Long> route = new ArrayList<Long>();

    /**
     * 节点 -> 下标 的缓查表。
     * <p>
     * ★ 必须缓存。早先 {@link #indexOf} 是每次线性扫整张表，而建图时每格要调它 4 次
     * （自己 1 次 + 每个邻居 1 次），于是一条 200 格的轨道线光建图就是
     * 8 万次比较 —— 而建图是每 {@link TrainSettings#routeCheckTicks} 跑一次的。
     * 更要命的是它也把"下标"变成了一个需要维护的不变量：
     * 任何一处漏了同步，链接就会静默指到别的格上去。
     * <p>
     * 这里改成缓存之后，下标的语义被钉死成"rails 的插入顺序"，
     * 只要 {@link #clearRails} 和 {@link #removeRail} 记得清，就永远不会漂。
     */
    private final Map<Rail, Integer> railIndex = new LinkedHashMap<Rail, Integer>();
    /** 相邻两格的高度差超过这个值就不算接得上（防止把上下两层当成坡道接起来） */
    private static final int MAX_STEP = 1;

    // ---------------------------------------------------------------- 运行态
    /** 车头所在轨道节点下标。-1 = 还没上车（脱轨 / 无轨） */
    private int node = -1;
    /** 在 node 到 next 的方向上行进了多少（0..1）。0 = 正踩在 node 上 */
    private double progress = 0.0;
    /** 当前速度（格/tick） */
    private double speed = 0.0;
    /** 行进方向：+1 = 沿 next 走，-1 = 沿 prev 走 */
    private int heading = 1;

    /**
     * 定向走法覆盖了多少格（最近一次 {@link #orientDirection()} 的结果）。
     * <p>
     * ★ 它是"这张图到底是不是一条线"的判据之一：只有每个格都被写上了
     * prev/next，{@link #isChain()} 才可能为真。分叉（梯子那种度 3 的格子）
     * 或不连通的图，单遍走法一定覆盖不满 —— 那不是"线"，该报错。
     */
    private int orientedCount = 0;
    /** 停站阶段码，见 {@link TrainSpec#PH_*} */
    private int phase = TrainSpec.PH_RUN;
    /** 当前瞄准的站点 id + 1。0 = 没在进站 */
    /**
     * ★ <b>{@code targetStation} 一律是 route 下标（0 起），全类只此一种约定。</b>
     * <p>
     * 早先这里是混的：{@link #startAtStation} 写 {@code index + 1}（1 起），
     * {@link #tickRun} 写 {@link #stationIdxAhead()}（0 起），
     * {@link #currentDwell()} 又按 1 起去减一。三处约定不一致，
     * 实测的后果是三站环线上"停在 C 的位置上报出 B 的名字"，
     * 停站顺序出来 {@code ABAABA}，而每个单独看都"像是对的"。
     * <p>
     * 约定统一到 0 起，是因为对外面几个查询接口
     * （{@link #stationName(int)} / {@link #stationRailIndex(int)} /
     * {@link #getTargetStation()}）全都是 0 起 —— 让内部私有的那个变量
     * 跟外面一致，就不会再有人写错方向。
     */
    private int targetStation = 0;
    /** 当前阶段还剩多少 tick（开门/停站/关门用） */
    private int phaseTimer = 0;
    /** 全局 tick 计数 */
    private long ticks = 0L;
    /** 距离下次线路自检还有多少 tick */
    private int checkTimer = 0;

    /** 编组：车厢数（不含车头） */
    private int cars;
    /** 车上现有乘客数 */
    private int passengers;

    /** 当前故障原因，见 {@link TrainSpec#FAIL_*} */
    private int fail = TrainSpec.FAIL_NONE;
    /** 故障详情自由文本（给日志用，不用于语言文件） */
    private String failDetail = "";
    /** 本次故障是不是已经报过（去重：同一种故障只报一次，直到它被修好） */
    private boolean failReported = false;

    /** 上一 tick 报过"等发车"没有，用于事件去重 */
    private boolean holdReported = false;

    private final List<String> events = new ArrayList<String>();

    // ================================================================ 构造

    public TrainCore(TrainSettings settings) {
        this.cfg = settings == null ? new TrainSettings() : settings.copy();
        this.rng = new Random(this.cfg.seed);
        this.cars = clampInt(this.cfg.initialCars, TrainSpec.MIN_CARS, TrainSpec.MAX_CARS);
    }

    // ================================================================ 轨道图输入

    /** 一个格坐标打包成 long 键。y 只取低 12 位（0..4095），够 1.12.2 用了 */
    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    /**
     * 一格轨道的邻居快照。给 MC 层做调试渲染 / 给测试做断言用。
     * <p>
     * 这里刻意用<b>坐标</b>而不是下标回出去 —— 下标在轨道图重建之后会整体挪位，
     * 外部拿着旧下标没有意义。坐标是稳定的。
     */
    public static final class NeighbourInfo {
        /** 前驱在 rails 里的下标。-1 = 没有 */
        public int prev = -1;
        /** 后继下标。-1 = 没有 */
        public int next = -1;
        /** 前驱格坐标 */
        public int prevX;
        public int prevY;
        public int prevZ;
        /** 后继格坐标 */
        public int nextX;
        public int nextY;
        public int nextZ;
    }

    /**
     * 查某个格坐标的邻居。格不存在时返回一个全 -1 的快照。
     * <p>
     * 之所以需要它：{@link #rebuildLinks()} 的正确性没法从外面看出来 ——
     * "A 的 next 指向 B" 和 "B 的 prev 指向 A" 是两条独立的写入，
     * 漏了一条就是"车开出去回不来"。这个方法是唯一能把那张图打印出来的出口。
     */
    public NeighbourInfo neighbourOf(int x, int y, int z) {
        NeighbourInfo out = new NeighbourInfo();
        int idx = indexByCoord(x, y, z);
        Rail r = railAt(idx);
        if (r == null) {
            return out;
        }
        out.prev = r.prev;
        out.next = r.next;
        Rail p = railAt(r.prev);
        Rail n = railAt(r.next);
        if (p != null) {
            out.prevX = p.x;
            out.prevY = p.y;
            out.prevZ = p.z;
        }
        if (n != null) {
            out.nextX = n.x;
            out.nextY = n.y;
            out.nextZ = n.z;
        }
        return out;
    }

    /**
     * 校验整张轨道图是不是<b>无向对称</b>的：每个节点的邻居也要把本节点算作自己的邻居。
     * <p>
     * ★ 这是建图正确性的唯一硬判据。单向图（A.next=B 但 B.prev≠A）会让
     * "从 A 走到 B" 能走、"从 B 走回 A" 报断头 —— 表现就是"车开出去回不来"。
     * 建完图立刻验一次，比事后去查事故现场便宜得多。
     */
    public boolean linksSymmetric() {
        int i = 0;
        for (Rail r : rails.values()) {
            if (r.next >= 0) {
                Rail n = railAt(r.next);
                if (n == null || (n.prev != i && n.next != i)) {
                    return false;
                }
            }
            if (r.prev >= 0) {
                Rail p = railAt(r.prev);
                if (p == null || (p.prev != i && p.next != i)) {
                    return false;
                }
            }
            i++;
        }
        return true;
    }

    /** 某个站点（按加入顺序）绑在哪一格轨道上。绑不上返回 -1 */
    public int stationRailIndex(int stationIdx) {
        if (stationIdx < 0 || stationIdx >= route.size()) {
            return -1;
        }
        Station s = stations.get(route.get(stationIdx));
        return s == null ? -1 : s.rail;
    }

    /** 拆掉一格轨道（玩家把方块挖了） */
    public void removeRail(int x, int y, int z) {
        if (rails.remove(key(x, y, z)) != null) {
            reindex();
        }
    }

    /** 测试 / 调试用：按格数直接推进位置（绕过速度模型） */
    public void advanceBy(double dist) {
        advance(dist);
    }

    /** 测试 / 调试用：某个下标沿 next 走一步的下标。-1 = 走不了 */
    public int debugNextIndex(int idx) {
        Rail r = railAt(idx);
        return r == null ? -1 : r.next;
    }

    /** 测试 / 调试用：先把一节轨道的走向改成别的（模拟玩家转向） */
    public void debugSetDir(int x, int y, int z, int dir) {
        Rail r = rails.get(key(x, y, z));
        if (r != null) {
            r.dir = dir;
        }
    }

    /** 测试 / 调试用：这一格有没有轨道 */
    public boolean debugHasRail(int x, int y, int z) {
        return rails.containsKey(key(x, y, z));
    }

    /** 测试 / 调试用：这一格的下标。-1 = 没有 */
    public int debugIndexOf(int x, int y, int z) {
        return indexByCoord(x, y, z);
    }

    /** 测试 / 调试用：车头当前所在轨道的下标 */
    public int debugCellIndex() {
        return node;
    }

    /** 测试 / 调试用：某个下标的轨道格坐标，"x,y,z"；没有则 "n/a" */
    public String debugCoordOf(int idx) {
        Rail r = railAt(idx);
        return r == null ? "n/a" : (r.x + "," + r.y + "," + r.z);
    }

    /** 测试 / 调试用：某一格上盖的站号（0 = 不是站台）。-1 = 没有这一格 */
    public int debugStationSlot(int x, int y, int z) {
        Rail r = rails.get(key(x, y, z));
        return r == null ? -1 : r.station;
    }

    /**
     * 测试 / 调试用：从锚点沿 next 链走一圈，每格打印
     * {@code 下标 (x,y,z) 站号}。只在探针里用。
     */
    public void debugDumpRing() {
        Rail start = anchorRail();
        if (start == null) {
            return;
        }
        Rail cur = start;
        int guard = 0;
        while (guard++ <= rails.size() + 2) {
            System.out.println("    idx=" + indexOf(cur)
                    + " (" + cur.x + "," + cur.y + "," + cur.z + ")"
                    + " slot=" + cur.station
                    + " next=" + cur.next + " prev=" + cur.prev);
            Rail nxt = railAt(cur.next);
            if (nxt == null || nxt == start) {
                break;
            }
            cur = nxt;
        }
    }

    /** 车头在两格之间的插值进度（0..1） */
    public double getProgress() {
        return progress;
    }

    /** 当前行进方向：+1 沿 next，-1 沿 prev */
    public int getHeading() {
        return heading;
    }

    /**
     * 清空并重建轨道图。
     * <p>
     * MC 层每隔 {@link TrainSettings#routeCheckTicks} 把当前的轨道扫描结果整份灌进来,
     * 而不是做增量同步 —— 轨道网不大（一个园区几十到几百格），整份重建的开销
     * 远小于"增量更新写错了导致图和世界不一致"的风险。一致性 > 微优化。
     * <p>
     * ★ 重建<b>不会</b>重置列车的位置。位置靠 {@link #rebase()} 按坐标重新吸附 ——
     * 否则玩家每放一格轨道，全场的车都会被瞬移回起点。
     */
    public void clearRails() {
        rails.clear();
        reindex();
    }

    /** 加一格轨道。dir 见 {@link TrainSpec#DIR_*}，slope 表示坡道 */
    public void addRail(int x, int y, int z, int dir, boolean slope) {
        long k = key(x, y, z);
        Rail r = rails.get(k);
        if (r == null) {
            r = new Rail(x, y, z);
            rails.put(k, r);
            reindex();
        }
        r.dir = dir;
        r.slope = slope;
    }

    public int railCount() {
        return rails.size();
    }

    /**
     * 建图。<b>分三步：收邻接 -> 沿图定向 -> 对称收口。</b>
     * <p>
     * <b>第 1 步 —— 邻接（谁挨着谁）。</b>每格把六邻域里存在的邻居收进
     * {@link Rail#adjs}。这一步只产生一张<b>无向图</b>，<b>不写</b> prev/next。
     * <p>
     * <b>第 2 步 —— 定向（哪边是 next）。</b>从固定起点沿图走一遍，
     * 把走过的每一格的 next 指成"继续往下走的那一格"。
     * <p>
     * <b>第 3 步 —— 对称收口。</b>兜底修掉任何单边引用，让
     * {@link #linksSymmetric()} 恒为真。
     * <p>
     * ★★ <b>这个方法是整个类里最容易写错的地方，两个坑都踩过，记在这里。</b>
     * <p>
     * <b>坑一：让每格按自己声明的走向决定 next 放哪边。</b>
     * 玩家铺环线时，同一个拐角的两格对"走向"的描述可以互相矛盾
     * （顶边那几格常被铺成 {@code DIR_POS_X}，而它们实际往 -X 走）。
     * 于是拐角处两格互相把对方写进 prev，next 链断在这里。
     * 实测：12 格的环，next 链在 10 与 11 之间来回弹，{@code isLoop()} 判 false ——
     * 一列在好环线上的车报 {@code NO_LOOP} 然后停在原地。
     * <p>
     * <b>坑二：让"收邻接"那一步顺手把 next/prev 也分了。</b>
     * 邻接是"每格自己收自己的邻居"，没有任何全局视野。
     * 拐角那一格会同时被两个邻居当成"我的下一格"，
     * 于是它被写进两个不同的 next，环在那里打了个 2-循环。
     * <p>
     * 正确做法只有一条：<b>方向必须从一个固定起点沿图走一遍才能定下来</b>，
     * 每一步只认"当前格还有哪些邻居没走过"。这样 next 链天然是一条简单路径；
     * 图闭合时它自然闭合成环，图断头时它自然以 next = -1 结束。
     * <p>
     * 走完之后 {@code next} 的语义才被钉死成"顺行方向"，它与每格声明的走向
     * <b>完全无关</b> —— 走向从此只用于限速和渲染。
     */
    /**
     * <b>第 4 步 —— 给轨道格盖上站号。</b>
     * <p>
     * ★ 这一步<b>必须放在 {@link #rerouteAlongTrack()} 之后</b>，
     * 因为 {@code Rail.station} 存的是<b>站台在 route 里的序号</b>，
     * 而 route 会被 {@link #rerouteAlongTrack()} 按地理顺序重排。
     * 早先的顺序是"先盖号、后重排"，于是盖上去的号全部作废 ——
     * 实测三站环线（A 在 (5,0)、B 在 (20,0)、C 在 (29,10)）停在
     * B 的位置上报"B"、停在 C 的位置上报"B"，停站顺序出来 {@code ABAABA}。
     * <p>
     * 这类"先算好一个下标、再把这个下标指向的东西换掉"的错，排查时
     * 现象全都长得像"顺序随机的错乱"，而根因永远是<b>下标的生命周期跨越了一次重排</b>。
     */
    public void rebuildLinks() {
        buildAdjacency();
        orientDirection();
        enforceSymmetry();
        rebuildStationBindings();
        rerouteAlongTrack();
        stampStationSlots();
    }

    /**
     * 第 1 步：收邻接表。每格看六邻域，存在的邻居全收进来。
     * <p>
     * <b>只收邻居，不分配方向。</b>这是这一步的全部职责 ——
     * 一旦它开始"顺手决定谁是 next"，就会引入没有全局视野的错误分配
     * （见 {@link #rebuildLinks()} 的坑二）。
     */
    private void buildAdjacency() {
        for (Rail r : rails.values()) {
            r.adjs.clear();
            r.prev = -1;
            r.next = -1;
        }
        for (Rail r : rails.values()) {
            for (int[] o : NEIGHBOUR_OFFSETS) {
                Rail n = rails.get(key(r.x + o[0], r.y + o[1], r.z + o[2]));
                if (n != null && n != r && Math.abs(n.y - r.y) <= MAX_STEP) {
                    if (!r.adjs.contains(n)) {
                        r.adjs.add(n);
                    }
                    if (!n.adjs.contains(r)) {
                        n.adjs.add(r);
                    }
                }
            }
        }
    }

    /**
     * 第 2 步：从固定起点沿图走一遍，把 next/prev 写成"顺行 / 逆行"。
     * <p>
     * 起点取<b>最靠西北</b>的那一格（x 最小，同 x 取 z 最小，同 z 取 y 最小）。
     * 为什么不是"表里第一个"：起点决定顺行方向，而顺行方向必须可复现。
     * 挑一个由坐标唯一确定的格，同一张轨道图无论以什么顺序灌进来，
     * 定向结果都一样 —— 否则玩家每放一格轨道，整条线上的车都会掉个头。
     * <p>
     * 每一步在"还没走过的邻居"里按 {@link #ORIENT_ORDER} 挑第一个。
     * 这个顺序本身没有语义，它固定就够 —— 固定 = 可复现。
     */
    private void orientDirection() {
        orientedCount = 0;
        if (rails.isEmpty()) {
            return;
        }
        Rail start = anchorRail();
        if (start == null) {
            return;
        }
        java.util.HashSet<Rail> visited = new java.util.HashSet<Rail>();
        Rail cur = start;
        visited.add(cur);
        int guard = 0;
        while (guard++ <= rails.size()) {
            Rail nxt = pickNextUnvisited(cur, visited);
            if (nxt == null) {
                break;
            }
            cur.next = indexOf(nxt);
            nxt.prev = indexOf(cur);
            visited.add(nxt);
            cur = nxt;
        }
        // 闭合：环的最后一格指回起点。
        //   判据必须严格是"尾格和起点互为邻接"，不能只看"还有没有没走过的邻居"。
        //   用后者时，尾格恰好等于起点就写出了 next = 自己这种自指边 ——
        //   表现为一格指向自己，链接校验立刻不过，而且 advance() 会在那一格里
        //   无限循环（progress 永远是 0）。
        boolean closed = false;
        if (cur != start && cur.adjs.contains(start)) {
            cur.next = indexOf(start);
            start.prev = indexOf(cur);
            closed = true;
        }
        // ★★ 没闭合 = 断头线（"一条正经的线"）。这时上面的单遍走法只覆盖了锚点的<b>一侧</b>：
        //   锚点是"最西北那一格"，它落在链的中间时（某个朝向的 L 形正好如此，
        //   例如端点 (0,9)、拐角 (0,0)、端点 (9,0) —— 锚点就是那个拐角），
        //   另一侧一整段都没有 prev/next，全被算成"断头"。
        //   玩家看到的是一列车指着一条好端端的线报"轨道不成环（N 个断头）"，
        //   而 N 恰好等于"另一侧那些格子" —— 这就是"轨道不能拐弯"的真身之一。
        //   这里再从锚点往另一侧走一遍，把那一侧写成"逆行"链：
        //   cur.prev = 下一格、下一格.next = cur（与正向链正好相反的方向）。
        if (!closed) {
            guard = 0;
            cur = start;
            while (guard++ <= rails.size()) {
                Rail nxt = pickNextUnvisited(cur, visited);
                if (nxt == null) {
                    break;
                }
                cur.prev = indexOf(nxt);
                nxt.next = indexOf(cur);
                visited.add(nxt);
                cur = nxt;
            }
        }
        // 定向覆盖了多少格 —— 等于 railCount() 才说明图是"一条线"。
        // 分叉（梯子那种度 3 的格子）或断成几段时，单遍走法一定覆盖不满，
        // 那正是"必须报错"的情形（见 isChain()）。
        orientedCount = visited.size();
    }

    /**
     * 全图<b>唯一</b>的定向锚点：最靠西北的那一格（x 最小，同 x 取 z 最小，同 z 取 y 最小）。
     * <p>
     * ★ 这个方法必须是全类唯一的一份。定向（{@link #orientDirection()}）和
     * 排站序（{@link #rerouteAlongTrack()}）都要从同一格出发 ——
     * 两者起点不同的话，同一个环会被"定向成一种走法、站序按另一种走法编号"，
     * 表现为列车停在 X 站的位置却报出 Y 站的名字。
     * <p>
     * 挑坐标而不是"表里第一个"：表是 {@code LinkedHashMap}，
     * 顺序 = 玩家的放置顺序。同一个物理环线，玩家从不同地方开始铺，
     * 表的顺序就不同，定向结果也会不同 —— 每放一格轨道整条线掉个头。
     */
    private Rail anchorRail() {
        Rail start = null;
        for (Rail r : rails.values()) {
            if (start == null
                    || r.x < start.x
                    || (r.x == start.x && r.z < start.z)
                    || (r.x == start.x && r.z == start.z && r.y < start.y)) {
                start = r;
            }
        }
        return start;
    }

    /** 按固定优先级挑一个 cur 的邻居里还没走过的。挑不到返回 null */
    private Rail pickNextUnvisited(Rail cur, java.util.Set<Rail> visited) {
        for (int[] o : ORIENT_ORDER) {
            Rail n = rails.get(key(cur.x + o[0], cur.y + o[1], cur.z + o[2]));
            if (n != null && n != cur && !visited.contains(n) && cur.adjs.contains(n)) {
                return n;
            }
        }
        return null;
    }

    /**
     * 第 3 步：对称收口。把"我指向你、你却不指向我"的单边无条件修掉。
     * <p>
     * ★ 这是最后一道保险，也是 {@link #linksSymmetric()} 的判据来源：
     * 经过这里之后 {@code linksSymmetric()} 必须恒为 true。
     * 与其让定向算法去保证这件事（赋值分散在好几处，漏一处就静默失效），
     * 不如让一个一眼能看懂、且有一一对应断言的方法兜底。
     */
    private void enforceSymmetry() {
        int i = 0;
        for (Rail r : rails.values()) {
            if (r.next >= 0) {
                Rail n = railAt(r.next);
                if (n != null && n.prev != i && n.next != i) {
                    if (n.prev < 0) {
                        n.prev = i;
                    } else if (n.next < 0) {
                        n.next = i;
                    }
                }
            }
            if (r.prev >= 0) {
                Rail p = railAt(r.prev);
                if (p != null && p.prev != i && p.next != i) {
                    if (p.next < 0) {
                        p.next = i;
                    } else if (p.prev < 0) {
                        p.prev = i;
                    }
                }
            }
            i++;
        }
    }

    /**
     * 定向时选下一格的优先级。
     * <p>
     * 水平排在竖直前面：平地上的环会先在水平面绕一圈，坡道只在最后才被用到。
     * 顺序本身没有语义，固定就行。
     */
    private static final int[][] ORIENT_ORDER = {
            {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0},
    };

    /**
     * 邻域偏移表：水平四方 + 上下两方。
     * <p>
     * 含上下是必须的 —— 坡道就是靠"上一格 / 下一格"接起来的。
     * 但高度差被 {@link #MAX_STEP} 限制在 1 格以内，
     * 所以上下两层各铺一条独立轨道时不会错误地连成一条。
     */
    private static final int[][] NEIGHBOUR_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0},
    };


    /** 节点在 rails 里的下标。找不到返回 -1。走缓存表，O(1) */
    private int indexOf(Rail r) {
        Integer i = railIndex.get(r);
        return i == null ? -1 : i.intValue();
    }

    /** 按下标取节点。没有返回 null */
    private Rail railAt(int idx) {
        if (idx < 0) {
            return null;
        }
        int i = 0;
        for (Rail x : rails.values()) {
            if (i == idx) {
                return x;
            }
            i++;
        }
        return null;
    }

    /**
     * 重建下标缓存。任何改动 rails 的地方都必须调它。
     * <p>
     * 把它单独抽出来是为了让"忘了同步缓存"这件事<b>只有一个地方可能出错</b> ——
     * 而不是散在 addRail / removeRail / clearRails 三处各写一遍。
     */
    private void reindex() {
        railIndex.clear();
        int i = 0;
        for (Rail r : rails.values()) {
            railIndex.put(r, Integer.valueOf(i++));
        }
    }

    // ================================================================ 站点

    /** 加一个站点（锚点坐标 + 站名 + 停站时间） */
    public void addStation(int x, int y, int z, String name, int dwell) {
        long k = key(x, y, z);
        Station s = stations.get(k);
        if (s == null) {
            s = new Station(x, y, z, name, clampInt(dwell,
                    TrainSpec.DWELL_MIN_TICKS, TrainSpec.DWELL_MAX_TICKS));
            stations.put(k, s);
            route.add(k);
        } else {
            s.name = name;
            s.dwell = clampInt(dwell,
                    TrainSpec.DWELL_MIN_TICKS, TrainSpec.DWELL_MAX_TICKS);
        }
    }

    /**
     * 把 {@link #route} 重排成"沿轨道顺行的真实顺序"。
     * <p>
     * ★ <b>站序不能是"玩家放置顺序"。</b>上一版就是按加入顺序排的，结果：
     * 玩家先铺 A 站、再铺 B 站、最后补铺 C 站，可是 C 在轨道上位于 A 和 B 之间 ——
     * 于是车会以 A→B→C 的顺序去"找下一站"，而 C 每次都先于 B 被走到，
     * 表现就是<b>车在 A、C 之间来回弹</b>，B 永远不停。
     * 实测就是那个 {@code ABAABA} 的站序。
     * <p>
     * 正确的站序是<b>几何决定了就唯一确定</b>的：沿着 {@code next} 链从环上任意一点
     * 出发，依次遇到哪几站就是哪几站。玩家的放置顺序不该影响它。
     * <p>
     * 只有在环不闭合（断头线）时才退回"按加入顺序"，因为那时根本不存在
     * 一个绕回原点的自然顺序。
     */
    private void rerouteAlongTrack() {
        if (route.size() <= 1) {
            return;
        }
        boolean loop = isLoop();
        if (!loop && !isChain()) {
            // 图坏了（分叉 / 断成几段）—— 保持放置顺序，反正车也跑不起来
            return;
        }
        // ★ 必须从这里（anchorRail）出发，不能用 rails.values().iterator().next()。
        //   后者是"玩家的放置顺序"，同一个环线铺法不同就得到不同的站序 ——
        //   而 route 的序号会被盖进 Rail.station 变成"停站顺序"，
        //   于是"停站顺序按加入顺序循环"这条需求根本无从谈起。
        Rail start = anchorRail();
        if (start == null) {
            return;
        }
        // ★★ 断头线要先退到"链头"再开始走。
        //   锚点是"最西北那一格"，对链来说它未必是端点 —— 弯朝西北的 L 形，
        //   锚点就是那个拐角。从拐角出发只能走到链的一侧，另一侧的站整段收不到，
        //   于是 ordered.size() != route.size() → 不替换 → 站序又退回"放置顺序"，
        //   表现就是实测过的那个 ABAABA（车在两站之间来回弹，中间那站永不停）。
        if (!loop) {
            int back = 0;
            while (back++ <= rails.size()) {
                Rail p = railAt(start.prev);
                if (p == null) {
                    break;
                }
                start = p;
            }
        }
        List<Long> ordered = new ArrayList<Long>();
        Rail cur = start;
        int guard = 0;
        while (guard++ <= rails.size()) {
            Station s = stationAtCoord(cur.x, cur.y, cur.z);
            if (s != null) {
                long k = key(s.x, s.y, s.z);
                if (!ordered.contains(k)) {
                    ordered.add(k);
                }
            }
            Rail nxt = railAt(cur.next);
            if (nxt == null || nxt == start) {
                break;
            }
            cur = nxt;
        }
        // 起点那一格也要被算进来（上面循环是先记站再前进，起点已含）
        // ★ 只有"真的把每个站都收到了"才替换 route。
        //   数量对不上说明这一趟没走全（比如站点绑到了环外的孤岛轨道上），
        //   这时保持原顺序比丢掉一个站好 —— 丢站的后果是那个站永远不再停靠。
        if (ordered.size() == route.size()) {
            route.clear();
            route.addAll(ordered);
        }
    }

    /**
     * 这一格（或它正上方 / 正下方）有没有站点。没有返回 null。
     * <p>
     * 站台和轨道不在同一格，所以这里要把"驻在轨道格上的那个站"找出来 ——
     * {@link #rebuildStationBindings} 已经把 {@code rail} 下标算好了，
     * 这里反过来查一遍即可。
     */
    private Station stationAtCoord(int x, int y, int z) {
        int idx = indexByCoord(x, y, z);
        for (Station s : stations.values()) {
            if (s.rail == idx && idx >= 0) {
                return s;
            }
        }
        return null;
    }

    public void clearStations() {
        stations.clear();
        route.clear();
    }

    public int stationCount() {
        return stations.size();
    }

    /**
     * 把站点挂到轨道上。
     * <p>
     * ★ 站台和轨道<b>不在同一格</b>：站台方块铺在轨道旁边。所以这里要往下、往四周
     * 找一格轨道。找不到就 {@code rail = -1} —— 这个站点会被
     * {@link #checkRoute} 判成"贴着空气的站台"，列车永远不会在它那儿停。
     * <p>
     * 找的顺序刻意做成"先正下方，再四周同一层，最后四周下方" ——
     * 因为最常见、最该优先命中的摆法是"轨道在站台方块正下方的隔壁"，
     * 而玩家把站台放在轨道正上方一格的情况也不少。
     */
    private void rebuildStationBindings() {
        for (Station s : stations.values()) {
            s.rail = -1;
            // 正下 / 正上
            int[][] cands = {
                    {0, -1, 0}, {0, 0, 0},
                    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
                    {0, -2, 0},
                    {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},
            };
            for (int[] c : cands) {
                int idx = indexByCoord(s.x + c[0], s.y + c[1], s.z + c[2]);
                if (idx >= 0) {
                    s.rail = idx;
                    break;
                }
            }
        }
    }

    /**
     * 把所有轨道格上的站号清零，再按<b>当前 route</b> 重新盖一遍。
     * <p>
     * ★ 必须"先全清再盖"：站台被拆掉之后，它原来占的那一格
     * {@code Rail.station} 还挂着旧号，不清掉的话那一格会永远被当成站台，
     * 车开到那儿就停 —— 而玩家已经把那站拆了。
     * <p>
     * 一个站台只盖它绑定的那一格（{@code Station.rail}），
     * 号 = 它在 route 里的序号 + 1（0 表示"这格不是站台"，见 {@code Rail.station}）。
     */
    private void stampStationSlots() {
        for (Rail r : rails.values()) {
            r.station = 0;
        }
        for (int i = 0; i < route.size(); i++) {
            Station s = stations.get(route.get(i));
            if (s == null || s.rail < 0) {
                continue;
            }
            Rail r = railAt(s.rail);
            if (r != null) {
                r.station = i + 1;
            }
        }
    }

    private int indexByCoord(int x, int y, int z) {
        int i = 0;
        for (Rail r : rails.values()) {
            if (r.x == x && r.y == y && r.z == z) {
                return i;
            }
            i++;
        }
        return -1;
    }

    /** 站点被拆的时候从表里删掉 */
    public void removeStation(int x, int y, int z) {
        long k = key(x, y, z);
        if (stations.remove(k) != null) {
            route.remove(Long.valueOf(k));
        }
    }

    // ================================================================ 上车

    /**
     * 把车吸附到离 (x,y,z) 最近的轨道格上。找不到就脱轨。
     * <p>
     * ★ 这是"重建轨道图之后不瞬移"的关键：玩家每放一格轨道就重建一次图，
     * 车的位置必须按<b>世界坐标</b>重新找到最近的轨，而不是按旧的下标。
     * 下标在重建后会整体平移，按下标续接 = 车会跳到别的地方去。
     */
    public boolean rebase(int x, int y, int z) {
        int best = -1;
        long bestD = Long.MAX_VALUE;
        int i = 0;
        for (Rail r : rails.values()) {
            long d = TrainSpec.manhattan(r.x, r.y, r.z, x, y, z);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
            i++;
        }
        if (best < 0) {
            node = -1;
            speed = 0.0;
            phase = TrainSpec.PH_STALLED;
            raise(TrainSpec.FAIL_NO_RAIL, "rebase(" + x + "," + y + "," + z + ") 附近没有轨道");
            return false;
        }
        node = best;
        progress = 0.0;
        clearFail();
        if (phase == TrainSpec.PH_STALLED) {
            phase = TrainSpec.PH_RUN;
        }
        return true;
    }

    /**
     * 直接指定起始站台（发车站）。
     * <p>
     * ★ 走的是 {@link TrainSpec#PH_DOOR_OPENING}，不是直接跳进 {@code PH_DWELL}。
     * 上一版直接设成 DWELL 并把计时器填满，于是<b>开门那一段的上下客逻辑被整段跳过</b>——
     * 在站台上灌了多少候车人，车到站都不会有人上来。
     * "首站也要开门"这件事看着理所当然，但只有把初始状态摆到和正常到站
     * <b>同一条路径</b>上，才能保证它将来不会因为改了一处而只对一半情况生效。
     */
    public boolean startAtStation(int index) {
        if (index < 0 || index >= route.size()) {
            return false;
        }
        Station s = stations.get(route.get(index));
        if (s == null || s.rail < 0) {
            raise(TrainSpec.FAIL_STATION_GONE, "起始站台没有贴到轨道上");
            return false;
        }
        node = s.rail;
        progress = 0.0;
        speed = 0.0;
        heading = 1;
        targetStation = index;
        clearFail();
        emit(TrainSpec.EV_TRAIN_SPAWN);
        emit(TrainSpec.EV_ARRIVE);
        enter(TrainSpec.PH_DOOR_OPENING);
        return true;
    }

    // ================================================================ 主循环

    /** 推进一 tick */
    public void tick() {
        ticks++;
        // 线路自检（定期，不是每 tick）
        if (checkTimer <= 0) {
            checkTimer = Math.max(1, cfg.routeCheckTicks);
            checkRoute();
        } else {
            checkTimer--;
        }

        if (phase == TrainSpec.PH_STALLED) {
            // 停摆状态：什么都不做，等 MC 层修好了再 rebase
            return;
        }

        switch (phase) {
            case TrainSpec.PH_RUN:
                tickRun();
                break;
            case TrainSpec.PH_BRAKING:
                tickBraking();
                break;
            case TrainSpec.PH_DOOR_OPENING:
                tickDoorOpening();
                break;
            case TrainSpec.PH_DWELL:
                tickDwell();
                break;
            case TrainSpec.PH_DOOR_CLOSING:
                tickDoorClosing();
                break;
            case TrainSpec.PH_ACCEL:
                tickAccel();
                break;
            default:
                phase = TrainSpec.PH_RUN;
                break;
        }
    }

    // ---------------------------------------------------------------- 正线行进

    private void tickRun() {
        // ★ 还没上车（node < 0）不等于脱轨。
        //   刚造出来还没 rebase 的车、以及 MC 层正在搬运的行车，都处在这个状态。
        //   把它当成 NO_RAIL 报出去是错的 —— 玩家会收到"脱轨"，
        //   而实际原因可能是"这车还没放到轨道上"或者"它其实是没车厢"。
        //   位置没安置好的时候直接跳过去，让 checkRoute 去报真正的原因。
        if (node < 0) {
            return;
        }
        Rail here = railAt(node);
        if (here == null) {
            raise(TrainSpec.FAIL_NO_RAIL, "车头所在轨道节点不存在");
            return;
        }
        // 到站判定：前方 lookahead 内有站台、且现在这个速度刹得住，就转刹车。
        // ★ 距离一律用 double —— 站台可能就在半格之外（刚出站又进站），
        //   截成 int 会让"还差 0.7 格"变成"还有 0 格"，于是提前进 BRAKING。
        // ★ 这里判的是"该开始刹车了"，不是"到站了"。真的停下来是在
        //   tickBraking() 里按"贴到站台"判的 —— 两者混用会让车提前
        //   一个刹车距离停住，见 tickBraking 的注释。
        double ahead = stationAheadDistance();
        if (ahead >= 0.0 && ahead <= cfg.lookahead) {
            if (ahead <= brakingDistance(speed) + cfg.stopOvershoot) {
                // ★ targetStation 由"轨道上真的走在前面的是哪一站"决定，
                //   不由一个自己维护的计数器决定。见 advanceTarget 的注释。
                targetStation = stationIdxAhead();
                enter(TrainSpec.PH_BRAKING);
                return;
            }
        }
        double lim = speedLimitHere(here);
        if (speed < lim) {
            speed = Math.min(lim, speed + cfg.accel);
        } else if (speed > lim) {
            speed = Math.max(lim, speed - cfg.decel);
        }
        advance(speed);
    }

    /**
     * 刹车段。
     * <p>
     * ★ <b>两条判据必须分开，这是本项目在列车这里踩得最深的一个坑：</b>
     * <pre>
     *   ① "现在这个速度还刹得住吗"  → v²/(2a) + overshoot >= 剩余距离   （进刹车段的门槛）
     *   ② "到站了吗"               → 剩余距离 &lt;= overshoot              （停止判据）
     * </pre>
     * 上一版把 ② 也写成了 ①，于是车在<b>还差整整一个刹车距离</b>的地方就直接
     * {@code speed = 0} 进开门 —— 实测在 156 格的环线上，站台在 x=20，
     * 车停在 <b>x=13</b>，门对着七格空气。判据 ① 的物理含义是"来得及刹车"，
     * 它成立的位置恰恰是"该开始刹车了"，不是"已经到站了"。
     * <p>
     * 另一条：本段<b>不再</b>用 {@code speed < SPEED_MIN} 兜底停站。
     * 那个兜底会让"刹车到几乎停住但还差很远"这种情况也判成到站，
     * 掩盖掉上面那个错误。真正刹不停（比如坡道限速突变）应当由
     * {@link #checkRoute} 或 MC 层的占道判定去报，不能在这里悄悄停车。
     */
    private void tickBraking() {
        Rail here = railAt(node);
        if (here == null) {
            raise(TrainSpec.FAIL_NO_RAIL, "刹车途中轨道节点消失了");
            return;
        }
        double ahead = stationAheadDistance();
        if (ahead < 0.0) {
            // 站台在刹车途中被拆了 —— 报"站台没了"，不要装作到站。
            raise(TrainSpec.FAIL_STATION_GONE, "刹车途中站台不见了");
            return;
        }
        // ② 到站判据：真的贴到站台了才停。
        if (ahead <= cfg.stopOvershoot) {
            // 把最后那一点距离走完，绝不越过站台中心。
            advance(Math.max(0.0, ahead - cfg.stopOvershoot * 0.5));
            speed = 0.0;
            // ★ "到站"这个事件在这里报，不是在进刹车段那一刻报。
            //   进刹车段只是"开始减速"，离站台可能还有七八格 ——
            //   那时候报 ARRIVE，MC 层会提前播报站名。
            emit(TrainSpec.EV_ARRIVE);
            enter(TrainSpec.PH_DOOR_OPENING);
            return;
        }
        // 还没到，继续减速前进。每 tick 走的距离受"剩余距离 - 到站余量"限制，
        // 免得最后一 tick 直接跨过站台。
        double step = Math.min(speed, Math.max(0.0, ahead - cfg.stopOvershoot));
        speed = Math.max(0.0, speed - cfg.decel);
        advance(step);
    }

    // ---------------------------------------------------------------- 停站时序

    private void tickDoorOpening() {
        if (phaseTimer > 0) {
            phaseTimer--;
        }
        if (phaseTimer <= 0) {
            // ★ 到站先下后上。
            //   下车比例由 MC 层通过 setAlightRatio 灌进来（默认全下）——
            //   它不该写死成 1.0：一趟车在园区里跑五个站，每站都清空
            //   就等于每站都得重新排队，通勤体验会很差。
            int off = (int) Math.floor(passengers * alightRatio);
            if (off > passengerX) {          // 至少留一个"过路客"
                off = passengerX;
            }
            if (off > 0) {
                passengers -= off;
                emit(TrainSpec.EV_UNLOAD);
            }
            // 上客：这一站的"候车人数"由 MC 层通过 setWaiting 灌进来，
            // 内核按剩余容量拉人。没灌就是 0，空车照开。
            int room = capacity() - passengers;
            int board = Math.min(room, waitingAtStation);
            if (board > 0) {
                passengers += board;
                waitingAtStation -= board;
                emit(TrainSpec.EV_LOAD);
            }
            emit(TrainSpec.EV_DOOR_OPEN);
            // ★ 顺序要紧：先 enter（它会把计时器摆成阶段默认值），
            //   再按"停的是哪一站"覆写成真实停站时长。反过来的话
            //   刚算好的 dwell 会被 enter() 抹掉，见 enter() 的注释。
            enter(TrainSpec.PH_DWELL);
            phaseTimer = currentDwell();
            doorTimer = 0;
        }
    }

    /**
     * 门已经开了多少 tick（进 PH_DWELL 时清零，停站期间每 tick 加一）。
     * <p>
     * 和 {@code phaseTimer} 是同刻起算的一对：入场时 {@code phaseTimer = 本站停站时长}、
     * {@code doorTimer = 0}，之后一减一增。所以 {@code phaseTimer + doorTimer}
     * 恒等于入场那一刻定下的停站时长 —— 门卡住的判据就靠这个反推（见 {@link #tickDwell()}）。
     */
    private int doorTimer = 0;

    private void tickDwell() {
        doorTimer++;
        // ★★★ 门"开得算不算久"的基准是**本站该开多久**，不是 cfg.doorTimeoutTicks。
        //
        //   2026-09-21 实锤（玩家报"神秘人卡门"）：门 = PH_DWELL 这一段（见 doorOpen()），
        //   doorTimer 就是"停站已经过了几 tick"。旧判据 `doorTimer > cfg.doorTimeoutTicks`
        //   拿它去比一个写死的 200，而默认停站 DEFAULT_DWELL_TICKS = 600 ——
        //   于是**每一站**都必定在开门 200 tick 时报 FAIL_DOOR_JAMMED
        //   「车门卡住了（门口有人）」，而门口一个人也没有（"神秘人"就是这么来的）；
        //   顺带把停站时间砍掉 2/3（设了 600，实际只停 200）。
        //   doorTimeoutTicks 的含义是"比该开的时长还**多**开了多久"，所以判据必须是
        //   该开的时长 + doorTimeoutTicks。
        //
        // ★ 该开的时长用 `phaseTimer + doorTimer` 反推，不另开字段/存档槽：
        //   两个计数器都在进 PH_DWELL 那一刻从"全程 / 0"起算，之后每 tick 各走一步
        //   （phaseTimer 夹在 0 就不再减），所以它们在停站期间的值之和恒等于入场值。
        //   另存一个字段的话，中途改站台/重建线路会造成两份基准漂移 —— 那时候
        //   一个偏小的基准会把这条兜底变成新的误报源。
        int planned = phaseTimer + doorTimer;
        if (doorTimer > planned + cfg.doorTimeoutTicks) {
            // 门卡住：强制关门发车，并报出来（别让乘客等到天荒地老）
            raise(TrainSpec.FAIL_DOOR_JAMMED, "门已开 " + doorTimer + " tick（本站该开 "
                    + planned + " tick，多开的容忍上限 " + cfg.doorTimeoutTicks
                    + " tick），强制关门");
            enter(TrainSpec.PH_DOOR_CLOSING);
            return;
        }
        if (phaseTimer > 0) {
            phaseTimer--;
        }
        if (phaseTimer <= 0) {
            enter(TrainSpec.PH_DOOR_CLOSING);
        }
    }

    private void tickDoorClosing() {
        if (phaseTimer > 0) {
            phaseTimer--;
        }
        if (phaseTimer <= 0) {
            emit(TrainSpec.EV_DOOR_CLOSE);
            emit(TrainSpec.EV_DEPART);
            advanceTarget();
            enter(TrainSpec.PH_ACCEL);
        }
    }

    private void tickAccel() {
        Rail here = railAt(node);
        if (here == null) {
            raise(TrainSpec.FAIL_NO_RAIL, "出站加速时轨道节点消失");
            return;
        }
        double lim = speedLimitHere(here);
        speed = Math.min(lim, speed + cfg.accel);
        advance(speed);
        if (speed >= lim - 1.0e-9) {
            enter(TrainSpec.PH_RUN);
        }
    }

    // ---------------------------------------------------------------- 位置推进

    /**
     * 沿着 heading 方向前进 dist 格。
     * <p>
     * <b>★ 这里必须处理"一 tick 走完一整格"的情况。</b>最高速 0.72 格/tick 时不会越格，
     * 但如果 MC 层给了个大的 dist（比如存档恢复时的重定位），就必须循环跨格，
     * 不能只跨一次 —— 只跨一次会让列车卡在格边界上原地抖。
     */
    private void advance(double dist) {
        if (dist <= 0.0) {
            return;
        }
        double left = dist;
        int guard = 0;
        while (left > 0.0 && guard++ < 64 && phase != TrainSpec.PH_STALLED) {
            double toEdge = 1.0 - progress;
            if (left < toEdge) {
                progress += left;
                left = 0.0;
                break;
            }
            left -= toEdge;
            // 跨到下一格
            int nx = nextNode();
            if (nx < 0) {
                // ★★ 断头线（一条正经的线）到端点就折返。这是玩家最朴素的理解 ——
                //   轨道方块的 tooltip 从第一天起就写着"铺成一条直线也行（车到终点会自己倒回来）"，
                //   而代码里一直没有掉头，等于提示和实现打架。
                //   折返成功就 continue，用剩下的 left 接着走。
                if (turnAround()) {
                    continue;
                }
                // 折返不了（图坏了、或只有一格的线）→ 照旧报故障
                if (heading > 0) {
                    riseOnce(TrainSpec.FAIL_DEAD_END, "车头前方没有轨道（断头路）");
                } else {
                    // 反向走到头 = 不成环
                    riseOnce(TrainSpec.FAIL_NO_LOOP, "反向走到线路尽头，成不了环");
                }
                speed = 0.0;
                return;
            }
            node = nx;
            progress = 0.0;
        }
    }

    /**
     * 走到线路端点时掉头（直线 / L 形 / U 形都能跑的关键）。
     *
     * <h3>★ 为什么只改 {@code heading}，位置一点不用动</h3>
     * 调用点在 {@link #advance(double)} 里"toEdge 已经被 left 消耗完"之后 ——
     * 也就是车<b>物理上已经贴在当前这一格的末端</b>了。掉头之后，
     * 这一格的末端恰好就是新方向上的<b>起点</b>，所以 {@code progress = 0}
     * 就是它真实的参数化位置。不要画蛇添足写 {@code 1 - progress}：
     * 那是"掉头时车停在格中间"才需要的换轴，而这里车已经在格口上了。
     *
     * @return 真的掉头了才 true；图坏了或者只有一格的线都返回 false（交回给调用方报故障）
     */
    private boolean turnAround() {
        if (!isChain()) {
            return false;
        }
        int oldHeading = heading;
        double oldProgress = progress;
        heading = -heading;
        progress = 0.0;
        if (nextNode() < 0) {
            // 掉头之后还是尽头 —— 只有一格的"线"。回滚，让它照旧报故障；
            // 不回滚的话 advance() 的 while 会在两个方向之间来回掉头。
            heading = oldHeading;
            progress = oldProgress;
            return false;
        }
        return true;
    }

    /**
     * 当前 heading 方向的下一格下标。
     * <p>
     * ★ heading 为 -1（反向）时要往 prev 走。环线上前进方向永远是"沿着 next 一直走"，
     * 环自己会接回来；不成环的线则靠 {@link #turnAround()} 在端点掉头。
     */
    private int nextNode() {
        Rail here = railAt(node);
        if (here == null) {
            return -1;
        }
        return heading > 0 ? here.next : here.prev;
    }

    // ================================================================ 线路自检

    /**
     * 检查线路是否还能跑：站点还在不在、有没有成环、有没有站台贴不上轨。
     * <p>
     * ★ 每一种不正常都要报到"该去改哪一格"的粒度 ——
     * 所以 {@link #failDetail} 里带坐标。
     */
    private void checkRoute() {
        if (stations.isEmpty()) {
            riseOnce(TrainSpec.FAIL_NO_STATION, "线路上一个站点都没有");
            return;
        }
        // ★ 检查顺序 = 排查顺序：先说"你该去补什么"，再说"线本身坏了"。
        //   把 NO_CAR 放在 NO_LOOP 后面是错的 —— 一辆只剩车头的车停在一条好环线上，
        //   玩家收到的会是"线路不成环"，然后跑去查轨道，而真正该做的是加一节车厢。
        //   本项目的老规矩：每句失败提示都要能指到该去改哪个方块、改哪个参数。
        if (cars < TrainSpec.USABLE_CARS) {
            riseOnce(TrainSpec.FAIL_NO_CAR,
                    "编组里没有车厢（当前 " + cars + " 节，至少要 "
                            + TrainSpec.USABLE_CARS + " 节才载得了人）");
            return;
        }
        // 有没有站台贴着空气
        for (Station s : stations.values()) {
            if (s.rail < 0) {
                riseOnce(TrainSpec.FAIL_STATION_GONE,
                        "站台 (" + s.x + "," + s.y + "," + s.z + ") 没有贴在轨道上");
                return;
            }
        }
        if (node >= 0 && railAt(node) == null) {
            riseOnce(TrainSpec.FAIL_NO_RAIL, "车头所在的轨道被拆掉了");
            return;
        }
        if (!isLoop() && !isChain()) {
            riseOnce(TrainSpec.FAIL_NO_LOOP, describeBrokenGraph());
            return;
        }
        clearFail();
    }

    /**
     * 这张图是不是"一条正经的线"：不成环，但每个格都串在一条链上。
     * <p>
     * ★★ 有了它，直线 / L 形 / U 形才能跑 —— 车到端点自己掉头。
     * 这也是轨道方块 tooltip 从第一天起就承诺过的行为
     * （"铺成一条直线也行（车到终点会自己倒回来）"），此前代码里根本没实现。
     * <p>
     * 判据有两条，缺一不可：
     * <ol>
     *   <li>{@code orientedCount == railCount()}：每个格都被定向过。
     *       分叉（度 ≥ 3）或断成几段时，单遍走法覆盖不满；
     *       那些没被覆盖的格子 prev/next 都是 -1，被 {@link #deadEndCount()}
     *       一数就是一大片 —— 也正是"梯子形轨道报 72 个断头"的来历。</li>
     *   <li>{@code deadEndCount() == 2}：链恰好有两个端点（一头一尾）。
     *       0 个是环（那是 {@link #isLoop()} 的事），多于 2 个说明图是坏的。</li>
     * </ol>
     * ★ 不能只看 {@code deadEndCount() == 2}：一个环 + 一条断头线正好也是 2，
     * 但那两段互不相连，车只会在其中一段上跑，另一段的站永远停不到。
     */
    public boolean isChain() {
        if (rails.size() < 2) {
            return false;
        }
        if (orientedCount != rails.size()) {
            return false;
        }
        return deadEndCount() == 2;
    }

    /**
     * 图上有几个断头（只有一个邻居的格子）。
     * <p>
     * 只用来写进提示文案 —— 玩家看到"有 2 个断头"就知道自己少铺了两格，
     * 比"不成环"这三个字有用得多。
     */
    public int deadEndCount() {
        int n = 0;
        for (Rail r : rails.values()) {
            if (r.prev < 0 || r.next < 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * 真正只有一个邻居的格数（几何意义上的端点）。
     * <p>
     * ★ 和 {@link #deadEndCount()} 不是一回事，别混用：后者问的是"定向有没有覆盖到"
     * （单遍走法漏掉一整支时会虚高），这里问的是"图上这一格到底连着几个格"。
     * 一条正经的直线/弧线正好 2 个；环 0 个。
     */
    public int trueEndCount() {
        int n = 0;
        for (Rail r : rails.values()) {
            if (r.adjs.size() == 1) {
                n++;
            }
        }
        return n;
    }

    /** 分叉格（度 >= 3）的坐标，最多报 {@code max} 个。没有分叉就返回空串 */
    public String junctionReport(int max) {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Rail r : rails.values()) {
            if (r.adjs.size() < 3) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("、");
            }
            sb.append("(").append(r.x).append(",").append(r.y).append(",").append(r.z).append(")");
            if (++n >= max) {
                break;
            }
        }
        return sb.toString();
    }

    /**
     * "轨道不是一条能跑的线"要说到<b>哪一格</b>上。
     * <p>
     * ★★★ 这里原来只报 {@code deadEndCount()}，实测 104 格的图报 72 ——
     * 因为定向是<b>单遍不回溯</b>的，图一旦断/分叉，后面整段都拿不到 prev/next，
     * 全被数成"断头"。玩家拿着"72 个断头"完全不知道该动哪一格
     * （2026-09-21 实测：轨道环上只差 2 格没接，报的却是 72）。
     * 现在改成报<b>分叉坐标</b>或<b>真实端点数</b>，两种都是能直接去改的信息。
     * <p>
     * ★ 注意这一层<b>看不到世界</b>（内核零 Minecraft 依赖），所以只能从几何给出结论；
     * "端点外侧差一格就有轨道"这种需要读世界的判断在 {@code EntityTrain} 里做，
     * 见它的 {@code reportTerminusGaps}。
     */
    private String describeBrokenGraph() {
        StringBuilder sb = new StringBuilder();
        sb.append("轨道不是一条能跑的线（共 ").append(railCount()).append(" 格）");
        String junc = junctionReport(4);
        if (!junc.isEmpty()) {
            sb.append("：有分叉在 ").append(junc)
                    .append("。分叉处只能走一边 —— 想跑全图就把多余的岔口拆掉，或者把每一支各自接成环");
        } else {
            sb.append("：每格都有来路和去路，但真实端点有 ").append(trueEndCount())
                    .append(" 个（一条线应该正好 2 个，环应该 0 个）——检查有没有孤立的轨道格");
        }
        return sb.toString();
    }

    /**
     * 轨道图是不是一个环。
     * <p>
     * <b>★ 判据：每个节点都同时有 prev 和 next，且从任意点出发沿 next 能走满 railCount 步
     * 回到起点。</b>只查"都有两个邻居"是不够的 —— 一个 8 字形或者两条独立环
     * 也满足度数条件，车从其中一条出发永远到不了另一条。
     * <p>
     * ★ 遍历必须<b>直接沿对象引用走</b>，不要绕道"下标 -> railAt(下标)"：
     * 那样每步都是 O(n)，一条 500 格的线光判环就是 25 万次比较，
     * 而这是每 {@link TrainSettings#routeCheckTicks} 都要跑的。
     */
    public boolean isLoop() {
        if (rails.size() < 3) {
            return false;
        }
        for (Rail r : rails.values()) {
            if (r.prev < 0 || r.next < 0) {
                return false;
            }
        }
        // 从表里第一个节点出发，沿 next 走 railCount 步，必须正好回到它
        Rail start = rails.values().iterator().next();
        Rail cur = start;
        for (int i = 0; i < rails.size(); i++) {
            Rail nxt = railAt(cur.next);
            if (nxt == null) {
                return false;
            }
            cur = nxt;
        }
        return cur == start;
    }

    // ================================================================ 站台测距

    /**
     * 前方最近的站台还有多远（格）。没有则 -1。
     * <p>
     * ★ 必须沿轨道<b>数格子</b>，不能算欧氏距离。U 形轨道上，直线距离 5 格的两点
     * 沿轨道可能是 40 格 —— 按欧氏算会让车在 U 的底部就以为"到站了"然后停下。
     */
    public double stationAheadDistance() {
        int idx = stationIdxAhead();
        if (idx < 0) {
            return -1.0;
        }
        return distanceToStation(idx);
    }

    /** 前方最近的那个站点的序号（route 下标）。没有则 -1 */
    private int stationIdxAhead() {
        if (railAt(node) == null) {
            return -1;
        }
        // 从当前节点出发沿 heading 走，第一个带 station 标记的格
        int cur = node;
        double dist = 1.0 - progress;
        int steps = 0;
        while (steps++ < cfg.searchLimit) {
            Rail r = railAt(cur);
            if (r == null) {
                return -1;
            }
            // ★ 这里刻意<b>只</b>看 heading 方向的下一格。早先还多一句
            //   "if (r.next < 0) return -1;" —— 那句在 reverse 时是假阴性：
            //   车反向走到链的尾格（next = -1）时本该继续往 prev 走，却被它提前挡掉，
            //   于是折返之后前方永远一个站都找不到。
            int nx = heading > 0 ? r.next : r.prev;
            if (nx < 0) {
                // 走到端点了。折返由 turnAround() 负责；找站只报"这个方向上没有"。
                return -1;
            }
            cur = nx;
            if (nx == node) {
                // 绕了一圈回到自己（环上唯一的站就是自己这一站）
                break;
            }
            Rail n = railAt(cur);
            if (n != null && n.station > 0) {
                return n.station - 1;
            }
            dist += 1.0;
        }
        return -1;
    }

    private double distanceToStation(int stationIdx) {
        if (railAt(node) == null) {
            return -1.0;
        }
        int cur = node;
        double dist = 1.0 - progress;
        int steps = 0;
        while (steps++ < cfg.searchLimit) {
            Rail r = railAt(cur);
            if (r == null) {
                return -1.0;
            }
            int nx = heading > 0 ? r.next : r.prev;
            if (nx < 0) {
                return -1.0;
            }
            cur = nx;
            Rail n = railAt(cur);
            if (n != null && n.station > 0 && n.station - 1 == stationIdx) {
                return dist;
            }
            if (cur == node) {
                break;
            }
            dist += 1.0;
        }
        return -1.0;
    }

    /** 还有 dist 格到站，现在这个速度刹得住吗 */
    private boolean canStopAt(double dist) {
        return dist <= brakingDistance(speed) + cfg.stopOvershoot + 1.0e-9
                || speed <= 0.0;
    }

    /**
     * 从当前速度刹到 0 需要多少格：v²/(2a)。
     * <p>
     * ★ 这是一个纯函数，参数改了它自动跟着改 —— 不写死"8 格"。
     */
    public double brakingDistance(double v) {
        if (cfg.decel <= 0.0) {
            return Double.MAX_VALUE;
        }
        return (v * v) / (2.0 * cfg.decel);
    }

    /** 这一格允许的速度上限 */
    private double speedLimitHere(Rail r) {
        if (r.dir == TrainSpec.DIR_CURVE) {
            return Math.min(cfg.speedMax, cfg.speedCurve);
        }
        if (r.slope) {
            return Math.min(cfg.speedMax, cfg.speedSlopeUp);
        }
        // 前方一格是弯道也要提前降速
        int nx = heading > 0 ? r.next : r.prev;
        Rail n = railAt(nx);
        if (n != null && n.dir == TrainSpec.DIR_CURVE) {
            return Math.min(cfg.speedMax, cfg.speedCurve);
        }
        return cfg.speedMax;
    }

    /** 走到下一个目标站 */
    /**
     * 出站时把"下一站"往前推。
     * <p>
     * ★ <b>现在它什么也不做 —— 这是对的。</b>
     * <p>
     * 早先这里维护了一个自增的站点计数器，而 {@link #tickRun} 里又在车真的
     * 看到前方站台时把 {@code targetStation} 覆写成"轨道上真实在前的那一站"。
     * 两个来源互相打架：计数器以为是 B，车看到的其实是 C，
     * 于是站序变成 {@code ABAABA} —— 车在两站之间来回弹，中间那站永远不停。
     * <p>
     * 唯一的真相只能是"沿轨道顺行时，下一格带站标记的是哪一站"。
     * 那个值是<b>几何决定的</b>，不需要也不允许另有一个计数器去猜。
     * 保留这个方法是为了让调用点读起来仍然是一条清晰的时序
     * （停完 → 推下一站 → 加速出站），也留个显式的文档锚点说明为什么这里是空的。
     */
    private void advanceTarget() {
        // 有意留空：下一站在 tickRun 里由 stationIdxAhead() 现算，见上。
    }

    private int currentDwell() {
        // targetStation 就是 route 下标，这里不再减一。见字段上的约定说明。
        int idx = targetStation;
        if (idx >= 0 && idx < route.size()) {
            Station s = stations.get(route.get(idx));
            if (s != null) {
                return s.dwell;
            }
        }
        return cfg.dwellTicks;
    }

    // ================================================================ 乘客

    /** 这一站等着上车的人数（由 MC 层灌进来） */
    private int waitingAtStation = 0;

    /**
     * 到站时下车的人数占车上总人数的比例。
     * <p>
     * 默认 1.0（全下）—— 单站通勤最常见，也最不会让人困惑。
     * 多站环线上 MC 层可以调小它，让一部分乘客继续坐下一段。
     */
    private double alightRatio = 1.0;

    /** 每站至少留几个乘客不下来（防止"每站都清空"导致环线形同虚设） */
    private int passengerX = 0;

    public void setWaiting(int n) {
        waitingAtStation = Math.max(0, n);
    }

    /** 设置到站下车比例（0.0 ~ 1.0） */
    public void setAlightRatio(double r) {
        if (Double.isNaN(r) || r < 0.0) {
            r = 0.0;
        }
        if (r > 1.0) {
            r = 1.0;
        }
        alightRatio = r;
    }

    /**
     * 设置"每站最少留几个乘客不下车"。
     * <p>
     * 它是 {@link #alightRatio} 的下限兜底：比例算出来只剩 0 个人时，
     * 仍然留下这么多。环线上每站都清空的话，车会一直是空的 ——
     * 看起来像"上客没生效"，其实是下车把上客吃掉了。
     */
    public void setPassengerReserve(int n) {
        passengerX = Math.max(0, n);
    }

    public double getAlightRatio() {
        return alightRatio;
    }

    public int getWaiting() {
        return waitingAtStation;
    }

    /** 这些人是"在车上"的（MC 层用它决定实体里要不要渲染乘客） */
    public int seatedPassengers() {
        return Math.min(passengers, cars * TrainSpec.SEATS_PER_CAR);
    }

    /** 这些人是"站着"的 */
    public int standingPassengers() {
        return Math.max(0, passengers - seatedPassengers());
    }

    /** 整车容量 = 车厢数 × 每节容量 */
    public int capacity() {
        return cars * TrainSpec.CAPACITY_PER_CAR;
    }

    /** 车上还有几个位子 */
    public int roomLeft() {
        return Math.max(0, capacity() - passengers);
    }

    // ================================================================ 编组

    public int cars() {
        return cars;
    }

    public int passengers() {
        return passengers;
    }

    /**
     * 挂一节车厢。
     * <p>
     * ★ 上限到了要报 {@link TrainSpec#EV_CAR_FULL}，<b>但不能顺手把 fail 设成什么</b> ——
     * "加不上车厢"是个操作结果，不是一列车的健康状态。早先这里写的是
     * {@code raise(FAIL_NONE, ...)}，那等于用一次失败的操作把车上已有的故障
     * （比如"站台被拆"）给清掉了，玩家会看到故障提示突然消失、以为修好了。
     */
    public boolean addCar() {
        if (cars >= TrainSpec.MAX_CARS) {
            emit(TrainSpec.EV_CAR_FULL);
            return false;
        }
        cars++;
        emit(TrainSpec.EV_CAR_ADDED);
        return true;
    }

    /**
     * 摘一节车厢。摘到 0 节是允许的 —— 之后就由 {@link #checkRoute} 报
     * {@link TrainSpec#FAIL_NO_CAR}。见 {@link TrainSpec#MIN_CARS} 的说明。
     */
    public boolean removeCar() {
        if (cars <= TrainSpec.MIN_CARS) {
            emit(TrainSpec.EV_CAR_EMPTY);
            return false;
        }
        cars--;
        if (passengers > capacity()) {
            passengers = capacity();
        }
        emit(TrainSpec.EV_CAR_REMOVED);
        return true;
    }

    // ================================================================ 故障上报

    /**
     * ★ 故障上报：<b>第一次发生时报一次</b>，之后直到被 {@link #clearFail} 清掉
     * 都不再重复报。
     * <p>
     * 为什么必须去重：{@link #checkRoute} 每 40 tick 跑一次，
     * 一个被拆掉的站台会让它每 40 tick 报一次 —— 一分钟报三十条同样的日志，
     * 把别的线索全刷没了。项目里已经吃过一次这个亏（气闸的配对日志）。
     */
    private void riseOnce(int reason, String detail) {
        if (fail == reason && failReported) {
            return;
        }
        raise(reason, detail);
    }

    /**
     * 记下一个故障。
     * <p>
     * ★ 两类故障要分开处理，{@link #FAIL_NO_CAR} 是<b>非致命</b>的：
     * 一辆只剩车头的车照样能跑、能进站、能报"载不了人"，
     * 只是载不了客而已。把它当成停摆处理的话，玩家摘掉最后一节车厢的瞬间
     * 整列车会当场定死在轨道上 —— 而他想做的其实只是"少挂一节"。
     * <p>
     * 真正该停摆的是"线断了"这一类：{@code NO_RAIL} / {@code DEAD_END} /
     * {@code NO_LOOP} / {@code STATION_GONE} / {@code NO_STATION} / {@code BLOCKED}。
     * 停摆之后 {@link #tick} 直接 return，等 MC 层修好再 {@link #rebase} 唤醒。
     */
    private void raise(int reason, String detail) {
        fail = reason;
        failDetail = detail;
        failReported = true;
        String key = failEventKey(reason);
        if (key != null && !key.isEmpty()) {
            emit(key);
        }
        if (isFatal(reason)) {
            phase = TrainSpec.PH_STALLED;
            speed = 0.0;
        }
    }

    /**
     * 这个故障会不会把车钉在地上。
     * <p>
     * ★ 这条判定必须是一个<b>具名的谓词</b>，不能散在 {@code raise} 里写
     * {@code reason != FAIL_NONE && reason != FAIL_DOOR_JAMMED} ——
     * 那样每加一个故障枚举都要回来改一次这个条件，而漏改的表现是
     * "新故障报了但车照跑"，静默得查不出来。
     */
    public static boolean isFatal(int reason) {
        switch (reason) {
            case TrainSpec.FAIL_NONE:
            case TrainSpec.FAIL_DOOR_JAMMED:
            case TrainSpec.FAIL_NO_CAR:
                return false;
            default:
                return true;
        }
    }

    private void clearFail() {
        if (fail != TrainSpec.FAIL_NONE) {
            fail = TrainSpec.FAIL_NONE;
            failDetail = "";
            failReported = false;
            // 非致命故障清掉之后不该把车留在停摆里
            if (phase == TrainSpec.PH_STALLED) {
                phase = TrainSpec.PH_RUN;
            }
        }
    }

    /** 故障原因 -> 事件键 */
    public static String failEventKey(int reason) {
        switch (reason) {
            case TrainSpec.FAIL_NO_RAIL:
                return TrainSpec.EV_DERAIL;
            case TrainSpec.FAIL_DEAD_END:
                return TrainSpec.EV_DEAD_END;
            case TrainSpec.FAIL_BLOCKED:
                return TrainSpec.EV_BLOCKED;
            case TrainSpec.FAIL_STATION_GONE:
                return TrainSpec.EV_STATION_GONE;
            case TrainSpec.FAIL_NO_LOOP:
                return TrainSpec.EV_NO_LOOP;
            case TrainSpec.FAIL_NO_STATION:
                return TrainSpec.EV_NO_STATION;
            case TrainSpec.FAIL_DOOR_JAMMED:
                return TrainSpec.EV_DOOR_JAMMED;
            case TrainSpec.FAIL_NO_CAR:
                return TrainSpec.EV_CAR_EMPTY;
            default:
                return "";
        }
    }

    /** 让 MC 层主动报一个故障（比如"前车占道"是外部判定出来的） */
    public void reportExternalFail(int reason, String detail) {
        riseOnce(reason, detail);
    }

    // ================================================================ 状态查询

    public int getFail() {
        return fail;
    }

    public String getFailDetail() {
        return failDetail;
    }

    public int getPhase() {
        return phase;
    }

    /** 门开着没有（MC 层用它决定要不要显示门开动画、允不允许下车） */
    public boolean doorOpen() {
        return phase == TrainSpec.PH_DWELL;
    }

    public double getSpeed() {
        return speed;
    }

    public int getCarsCount() {
        return cars;
    }

    public long getTicks() {
        return ticks;
    }

    public int getTargetStation() {
        return targetStation;
    }

    public TrainSettings settings() {
        return cfg;
    }

    /**
     * 车头的世界坐标（浮点）。
     * <p>
     * ★ 返回值是"方块最小角 + 偏移"里的偏移部分之外的那个绝对坐标，
     * 也就是轨格中心 + 沿行进方向的 progress 插值。
     * MC 层直接拿它 setPosition 即可 —— 不要在 MC 层再插一次值，
     * 否则会出现两套插值互相打架的抖动。
     */
    public double worldX() {
        Rail a = railAt(node);
        if (a == null) {
            return lastX;
        }
        double t = progress;
        int b = heading > 0 ? a.next : a.prev;
        Rail nb = railAt(b);
        if (nb == null) {
            return a.x + 0.5;
        }
        return a.x + 0.5 + (nb.x - a.x) * t;
    }

    public double worldY() {
        Rail a = railAt(node);
        if (a == null) {
            return lastY;
        }
        double t = progress;
        int b = heading > 0 ? a.next : a.prev;
        Rail nb = railAt(b);
        if (nb == null) {
            return a.y + TrainSpec.RAIL_RIDE_HEIGHT;
        }
        return a.y + TrainSpec.RAIL_RIDE_HEIGHT + (nb.y - a.y) * t;
    }

    public double worldZ() {
        Rail a = railAt(node);
        if (a == null) {
            return lastZ;
        }
        double t = progress;
        int b = heading > 0 ? a.next : a.prev;
        Rail nb = railAt(b);
        if (nb == null) {
            return a.z + 0.5;
        }
        return a.z + 0.5 + (nb.z - a.z) * t;
    }

    private double lastX = 0.0;
    private double lastY = 0.0;
    private double lastZ = 0.0;

    /**
     * 车头朝向（度）。0 = +Z，90 = -X，180 = -Z，270 = +X，
     * 与 MC 的 {@code Entity.rotationYaw} 约定一致（绕 Y 轴，顺时针为正）。
     * <p>
     * ★ 1.12.2 里 yaw=0 面向 +Z（南），这是 {@code EnumFacing.getHorizontal} 的
     * 0=SOUTH 那条约定。写错的话整列车会横着开。
     */
    public float yaw() {
        Rail a = railAt(node);
        if (a == null) {
            return lastYaw;
        }
        int b = heading > 0 ? a.next : a.prev;
        Rail nb = railAt(b);
        int dx = nb == null ? 0 : nb.x - a.x;
        int dz = nb == null ? 0 : nb.z - a.z;
        if (dx == 0 && dz == 0) {
            return lastYaw;
        }
        return yaw4(dx, dz);
    }

    /** 四方向朝向表：0=+Z, 90=-X, 180=-Z, 270=+X */
    public static float yaw4(int dx, int dz) {
        if (dz > 0) {
            return 0.0F;
        }
        if (dz < 0) {
            return 180.0F;
        }
        if (dx < 0) {
            return 90.0F;
        }
        if (dx > 0) {
            return 270.0F;
        }
        return 0.0F;
    }

    private float lastYaw = 0.0F;

    /** 车头当前所在轨格的坐标（整数格） */
    public int cellX() {
        Rail a = railAt(node);
        return a == null ? (int) Math.floor(lastX) : a.x;
    }

    public int cellY() {
        Rail a = railAt(node);
        return a == null ? (int) Math.floor(lastY) : a.y;
    }

    public int cellZ() {
        Rail a = railAt(node);
        return a == null ? (int) Math.floor(lastZ) : a.z;
    }

    /** 站点名，供广播用。越界返回空串 */
    public String stationName(int idx) {
        if (idx < 0 || idx >= route.size()) {
            return "";
        }
        Station s = stations.get(route.get(idx));
        return s == null ? "" : s.name;
    }

    // ================================================================ 事件出口

    private void emit(String k) {
        if (k == null || k.isEmpty()) {
            return;
        }
        if (!events.contains(k)) {
            events.add(k);
        }
    }

    /**
     * 取走这一 tick 攒下的事件。同一 tick 内重复的同一个键只留一个 ——
     * 和 F9 的 {@code drainEvents} 一个约定。
     */
    public List<String> drainEvents() {
        List<String> out = new ArrayList<String>(events);
        events.clear();
        return out;
    }

    // ================================================================ 存档

    /**
     * 把状态压成 int[]。
     * <p>
     * ★ 浮点用 {@code Double.doubleToRawLongBits} 拆成两个 int —— 和 F9 一样，
     * 不要用 (int)(v*1000)，那会在 long 世界里丢精度、长途跑久了位置会漂。
     */
    public int[] saveState() {
        int[] s = new int[TrainSpec.STATE_SLOTS];
        s[0] = TrainSpec.SAVE_VERSION;
        s[1] = node;
        putDouble(s, 2, progress);
        putDouble(s, 4, speed);
        s[6] = heading;
        s[7] = phase;
        s[8] = targetStation;
        s[9] = phaseTimer;
        s[10] = (int) (ticks & 0x7FFFFFFF);
        s[11] = checkTimer;
        s[12] = cars;
        s[13] = passengers;
        s[14] = fail;
        s[15] = failReported ? 1 : 0;
        s[16] = waitingAtStation;
        putDouble(s, 17, lastX);
        putDouble(s, 19, lastY);
        putDouble(s, 21, lastZ);
        putDouble(s, 23, lastYaw);
        s[25] = doorTimer;
        s[26] = route.size();
        for (int i = 0; i < route.size() && i < 8; i++) {
            s[27 + i] = route.get(i).hashCode();
        }
        return s;
    }

    /**
     * 从 int[] 恢复。
     * <p>
     * ★ 版本对不上就整份丢弃（保持当前状态），越界的夹回、NaN 的一律退回默认 ——
     * 和 F9 的读档防呆一个路子。理由是同一个：<b>脏存档不能变成脏物理</b>。
     * 一次 NaN 传进来，后面的位置就永远是 NaN，车会从世界里消失。
     */
    public void loadState(int[] s) {
        if (s == null || s.length < TrainSpec.STATE_SLOTS) {
            return;
        }
        if (s[0] != TrainSpec.SAVE_VERSION) {
            return;
        }
        node = clampInt(s[1], -1, Math.max(0, rails.size() - 1));
        progress = saneDouble(getDouble(s, 2), 0.0, 0.0, 1.0);
        speed = saneDouble(getDouble(s, 4), 0.0, 0.0, cfg.speedMax * 4.0);
        heading = s[6] >= 0 ? 1 : -1;
        phase = clampInt(s[7], TrainSpec.PH_RUN, TrainSpec.PH_STALLED);
        // targetStation 现在是 route 下标（0 起），上界只能是 route 里真实存在的站。
        // 不夹的话，读一份"站被拆过"的档会让 currentDwell() 去查一个不存在的站。
        targetStation = clampInt(s[8], 0, Math.max(0, route.size() - 1));
        phaseTimer = Math.max(0, s[9]);
        ticks = Math.max(0, s[10]);
        checkTimer = Math.max(0, s[11]);
        cars = clampInt(s[12], TrainSpec.MIN_CARS, TrainSpec.MAX_CARS);
        passengers = clampInt(s[13], 0, Math.max(1, cars * TrainSpec.CAPACITY_PER_CAR));
        fail = clampInt(s[14], TrainSpec.FAIL_NONE, TrainSpec.FAIL_NO_CAR);
        failReported = s[15] != 0;
        waitingAtStation = Math.max(0, s[16]);
        lastX = saneDouble(getDouble(s, 17), 0.0, -3.0e7, 3.0e7);
        lastY = saneDouble(getDouble(s, 19), 0.0, -4096.0, 4096.0);
        lastZ = saneDouble(getDouble(s, 21), 0.0, -3.0e7, 3.0e7);
        lastYaw = (float) saneDouble(getDouble(s, 23), 0.0, -360.0, 360.0);
        doorTimer = Math.max(0, s[25]);
        // 位置一律夹在合法区间，且绝不放 NaN 出去
        if (Double.isNaN(progress) || progress < 0.0) {
            progress = 0.0;
        }
        if (progress >= 1.0) {
            progress = 0.0;
        }
        if (speed < 0.0 || Double.isNaN(speed)) {
            speed = 0.0;
        }
        if (phase == TrainSpec.PH_STALLED && fail == TrainSpec.FAIL_NONE) {
            // 存档里记着"停摆"但没记原因 —— 补一个，否则这车永远出不来
            fail = TrainSpec.FAIL_NO_RAIL;
            failReported = false;
        }
    }

    private static void putDouble(int[] a, int off, double v) {
        long b = Double.doubleToRawLongBits(v);
        a[off] = (int) (b >>> 32);
        a[off + 1] = (int) b;
    }

    private static double getDouble(int[] a, int off) {
        long b = ((long) a[off] << 32) | (a[off + 1] & 0xFFFFFFFFL);
        return Double.longBitsToDouble(b);
    }

    private static double saneDouble(double v, double def, double lo, double hi) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return def;
        }
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }

    private static int clampInt(int v, int lo, int hi) {
        if (v < lo) {
            return lo;
        }
        if (v > hi) {
            return hi;
        }
        return v;
    }

    // ================================================================ 调试

    /** 测试用：强制把车推进到"正线行驶" */
    public void debugForceRunning() {
        phase = TrainSpec.PH_RUN;
        speed = cfg.speedMax;
        fail = TrainSpec.FAIL_NONE;
        failReported = false;
    }

    /** 测试用：直接落位到某个格坐标 */
    public void debugPlaceAt(int x, int y, int z) {
        rebase(x, y, z);
        phase = TrainSpec.PH_RUN;
        speed = 0.0;
    }

    /** 测试用：读当前朝向上一帧缓存值 */
    public float cachedYaw() {
        return lastYaw;
    }

    /** 每 tick 结束时让 MC 层回灌实际坐标（用于 setPosition 之后的一致性） */
    public void syncBack(double x, double y, double z, float yaw) {
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
    }

    /**
     * 切到某个停站阶段，并按阶段把计时器摆好。
     * <p>
     * ★ <b>{@code PH_DWELL} 不在这个 switch 的 {@code default} 分支里。</b>
     * 上一版它是掉进 {@code default} 的，而 {@code default} 把 {@code phaseTimer}
     * 清零 —— 于是 {@link #tickDoorOpening} 里刚算好的"该停多久"
     * 在切进 DWELL 的那一刻被当场抹掉，停站只持续 1 tick。
     * 表现是"门开了、人还没上完，车就走了"，而调 {@code dwellTicks} 完全不起作用
     * （因为真正生效的是那个被清掉的 0）。
     * <p>
     * 停站时长不是常量，它依赖"停的是哪一站"，所以必须由调用方
     * 在 {@code enter()} <b>之后</b>覆写；这里给的是"没指定就用默认值"的兜底。
     */
    private void enter(int next) {
        phase = next;
        switch (next) {
            case TrainSpec.PH_DOOR_OPENING:
                phaseTimer = cfg.doorOpenTicks;
                break;
            case TrainSpec.PH_DOOR_CLOSING:
                phaseTimer = cfg.doorCloseTicks;
                break;
            case TrainSpec.PH_DWELL:
                // 兜底值。调用方（tickDoorOpening）随后会按站点覆写成真实停站时间。
                phaseTimer = cfg.dwellTicks;
                break;
            default:
                phaseTimer = 0;
                break;
        }
    }
}

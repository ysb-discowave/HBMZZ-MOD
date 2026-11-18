package com.rsaudio.panel;

/**
 * 远程控制面板的<b>布局与命中判定</b>。
 *
 * <h2>零 Minecraft 依赖</h2>
 * 和 {@code access.AirlockCore}、{@code dmr.DmrCore} 一个路子：这里的全是纯算术，
 * 好让 {@code tools/check_panel_core.py} 用真 javac 编译、真跑边界用例。
 * <p>
 * 这个包一旦 import 了 {@code World} / {@code EnumFacing}，那条纯 javac 自检链路就断了。
 * 所以"哪个朝向对应哪个右向量"这件事<b>由调用方算好再传进来</b>
 * （见 {@link #faceU}），内核只认数字。
 *
 * <h2>★★ 为什么布局必须单独抽出来：看到的和按到的必须是同一套数</h2>
 * 这个功能的本质是"<b>在方块正面的 6 个格子里各画一个按钮，右键按下当时对着的那个</b>"。
 * 于是有两份几何：
 * <ul>
 *   <li><b>画</b>的时候用量子 —— {@code PanelRenderer} 把归一化坐标变成世界坐标；</li>
 *   <li><b>按</b>的时候反过来 —— 原版给的 {@code hitX/hitY/hitZ} 要变回归一化坐标。</li>
 * </ul>
 * 这两份几何只要有一点点不一致（列宽差 0.01、v 轴上下颠倒），症状就是
 * <b>看到的按钮和按下去的按钮不是同一个</b> —— 按"停机"点着了"点火"。
 * 这是最坏的一种 bug：界面看起来完全正常，只是按错。
 * <p>
 * 所以两边<b>共用这一个类</b>：{@link #buttonRect} 是画图用的唯一来源，
 * {@link #hitButton} 是判定用的唯一来源，而且判定干脆就是拿 {@link #buttonRect}
 * 挨个比 —— 不存在"两套公式各自演化"的可能。
 *
 * <h2>坐标约定</h2>
 * 归一化面坐标 {@code (u, v)}，都是 0..1：
 * <ul>
 *   <li>{@code u}：站在方块<b>外面正对着它</b>看时的<b>左 → 右</b>；</li>
 *   <li>{@code v}：从方块底边算起的<b>下 → 上</b>（就是方块的局部 y，不用翻）。</li>
 * </ul>
 * 注意 {@code u} 是"观看者的左右"而不是世界坐标轴 —— 面板挂在哪个朝向的墙上，
 * 世界坐标里的"右"都不一样，所以调用方要先把它换算成 {@code u} 再进来。
 */
public final class PanelCore {

    private PanelCore() {
    }

    // ------------------------------------------------------------------ 模式

    /** 控制反应堆：目标是一台 {@code TileDmrController} */
    public static final int MODE_DMR = 0;
    /** 控制塔塔鲁斯大门（重型防核门）：目标是同频道的重型门 */
    public static final int MODE_GATE = 1;
    public static final int MODE_COUNT = 2;

    /** 目标搜索半径（格）。"远程"面板总得能装在控制室里 */
    public static final int SEARCH_RADIUS = 64;

    public static boolean isValidMode(int mode) {
        return mode >= 0 && mode < MODE_COUNT;
    }

    public static int clampMode(int mode) {
        return isValidMode(mode) ? mode : MODE_DMR;
    }

    /** 频道上下限，和防核门 / 门禁终端用的是同一套数字 */
    public static final int CHANNEL_MIN = 1;
    public static final int CHANNEL_MAX = 16;

    public static int clampChannel(int channel) {
        if (channel < CHANNEL_MIN) {
            return CHANNEL_MIN;
        }
        return channel > CHANNEL_MAX ? CHANNEL_MAX : channel;
    }

    // ------------------------------------------------------------------ 动作码

    public static final int ACT_NONE = 0;

    /** 点火序列（和 DMR 控制柜界面上的按钮同一个动作） */
    public static final int ACT_DMR_IGNITE = 1;
    /** 正常停机 / 冷却 */
    public static final int ACT_DMR_COOLDOWN = 2;
    /** 进/出维护模式（一个按钮两用，按当前状态决定往哪边切） */
    public static final int ACT_DMR_MAINT = 3;
    /** 装填燃料单元（只在维护模式下有效） */
    public static final int ACT_DMR_CELL = 4;
    /** 硬重启修好功率激光 */
    public static final int ACT_DMR_LASER = 5;
    /** 点火必需的子系统全开 / 全关（一个按钮翻四个位） */
    public static final int ACT_DMR_SUBSYS = 6;

    public static final int ACT_GATE_OPEN = 11;
    public static final int ACT_GATE_CLOSE = 12;
    public static final int ACT_GATE_LOCK = 13;
    public static final int ACT_GATE_UNLOCK = 14;
    /** 联锁 开/关 */
    public static final int ACT_GATE_INTERLOCK = 15;
    /** 重型（塔塔鲁斯那种逐行升降）开/关 */
    public static final int ACT_GATE_HEAVY = 16;

    // ---------------------------------------------------------------- 布局

    /** 列数。3 列 × 2 行 = 6 个按钮 —— 再多字就挤得看不清了 */
    public static final int COLS = 3;
    /** 行数 */
    public static final int ROWS = 2;
    /** 按钮总数 */
    public static final int BUTTONS = COLS * ROWS;

    /** 左右留边 */
    public static final double MARGIN_U = 0.08D;
    /** 按钮区下边界 */
    public static final double MARGIN_V0 = 0.12D;
    /** 按钮区上边界（再往上是标题/状态条） */
    public static final double MARGIN_V1 = 0.82D;
    /** 按钮之间横向的缝 */
    public static final double GAP_X = 0.04D;
    /** 按钮之间纵向的缝 */
    public static final double GAP_Y = 0.04D;

    /** 顶部标题/状态条占的竖直区间 */
    public static final double HEADER_V0 = 0.84D;
    public static final double HEADER_V1 = 0.96D;

    /** 单个按钮的宽（归一化） */
    public static double buttonWidth() {
        return (1.0D - 2.0D * MARGIN_U - (COLS - 1) * GAP_X) / COLS;
    }

    /** 单个按钮的高（归一化） */
    public static double buttonHeight() {
        return (MARGIN_V1 - MARGIN_V0 - (ROWS - 1) * GAP_Y) / ROWS;
    }

    /**
     * 第 {@code index} 个按钮的矩形，返回 {@code {u0, v0, u1, v1}}。
     * <p>
     * 编号是<b>从左上角起、按行</b>：{@code 0 1 2} 是上面一行，{@code 3 4 5} 是下面一行。
     */
    public static double[] buttonRect(int index) {
        if (index < 0 || index >= BUTTONS) {
            return null;
        }
        int col = index % COLS;
        int row = index / COLS;
        double w = buttonWidth();
        double h = buttonHeight();
        double u0 = MARGIN_U + col * (w + GAP_X);
        double vTop = MARGIN_V1 - row * (h + GAP_Y);
        return new double[]{u0, vTop - h, u0 + w, vTop};
    }

    /**
     * 归一化面坐标 → 按钮编号；没命中任何一个返回 {@link #NONE}。
     * <p>
     * ★ 判定就是拿 {@link #buttonRect} 挨个比 —— 画图也是用同一个函数，
     * 所以"看到的"和"按到的"不可能分叉。
     */
    public static final int NONE = -1;

    public static int hitButton(double u, double v) {
        if (u < 0.0D || u > 1.0D || v < 0.0D || v > 1.0D) {
            return NONE;
        }
        for (int i = 0; i < BUTTONS; i++) {
            double[] r = buttonRect(i);
            if (u >= r[0] && u <= r[2] && v >= r[1] && v <= r[3]) {
                return i;
            }
        }
        return NONE;
    }

    /**
     * 把"点击位置"换算成归一化面坐标的 u 分量。
     *
     * @param dx 命中点在<b>方块局部坐标</b>里的 x，<b>0..1，就是 {@code onBlockActivated}
     *           直接递进来的那个 {@code hitX} 本身</b>（见下面的 ★）
     * @param dz 同上，z 分量
     * @param ux 站在面板外面正对它看时，"观看者的右"在<b>世界坐标</b>里的 x 分量
     * @param uz 同上，z 分量
     * @return u ∈ [0,1]
     *
     * <h3>★★ {@code hitX/hitY/hitZ} 是局部坐标，不要再减 {@code pos}</h3>
     * 这一点很容易想当然搞错（"命中点当然是世界坐标，当然要减方块坐标"），
     * 而错了之后的症状是<b>整个面板的按钮全部错位</b>，且面板装得离原点越远偏得越离谱，
     * 很容易被误判成"渲染歪了"。
     * <p>
     * 事实（对着反编译源码核过，两处都对得上）：
     * <ul>
     *   <li>客户端 {@code PlayerControllerMP.processRightClickBlock}：
     *       {@code float f = (float)(vec.x - (double)pos.getX());} —— 在这里就已经减掉了；</li>
     *   <li>这些 {@code f/f1/f2} 原样装进 {@code CPacketPlayerTryUseItemOnBlock}，
     *       服务端 {@code NetHandlerPlayServer.processTryUseItemOnBlock} 取出
     *       {@code getFacingX/Y/Z()} 后<b>直接透传</b>给
     *       {@code ItemInteractionManager.processRightClickBlock}，
     *       一路传到 {@code Block.onBlockActivated}，中间没有任何再加回方块坐标的动作。</li>
     * </ul>
     * 所以两端的 {@code hitX} 都是"面板内的 0..1"，正好就是这里要的 {@code dx}。
     */
    public static double faceU(double dx, double dz, double ux, double uz) {
        return 0.5D + (dx - 0.5D) * ux + (dz - 0.5D) * uz;
    }

    /**
     * 把"点击位置"换算成归一化面坐标的 v 分量。
     * <p>
     * 就是方块的局部 y —— 面板只做水平四向，不存在上下颠倒的问题，
     * 所以这里不需要和朝向有关的任何东西。单独留一个函数是为了让
     * "v 从下往上"这条约定有一个唯一的出处（写反了按钮就会上下颠倒）。
     */
    public static double faceV(double dy) {
        return dy;
    }

    /**
     * "站在面板外面正对着它看"时，观看者的<b>右</b>方向在世界坐标里的 x 分量。
     *
     * <h3>推导（只做一次，调用方不要再各推一遍）</h3>
     * 视线方向 = {@code -正面法线 = -n}，观看者的右 = 视线 × 上方向：
     * <pre>
     *   (-n) × (0,1,0) = (n.z, 0, -n.x)
     * </pre>
     * 验算：正面朝北（{@code n = (0,0,-1)}）→ 右 = {@code (-1,0,0)} = 西。
     * 站在方块北边、脸朝南看它，右手边确实是西 —— 对得上。
     * <p>
     * ★ 这个函数存在的唯一理由是<b>画的和按的必须是同一个右方向</b>。
     * {@code PanelRenderer} 和 {@code BlockRemotePanel} 都从这里取，
     * 谁都不许自己写一遍 {@code n.z / -n.x}。
     *
     * @param frontX 正面法线的 x 分量（{@code EnumFacing#getFrontOffsetX}）
     * @param frontZ 正面法线的 z 分量
     */
    public static double faceRightX(double frontX, double frontZ) {
        return frontZ;
    }

    /** 观看者的右方向在世界坐标里的 z 分量，见 {@link #faceRightX} */
    public static double faceRightZ(double frontX, double frontZ) {
        return -frontX;
    }

    // ------------------------------------------------------------ 面板正面这个平面

    /**
     * 面板正面所在平面到方块<b>中心</b>的距离。
     * <p>
     * 0.5 就是"正好贴着方块那一面"，多出来的 0.003 是给方块自身的贴图让位 ——
     * 两层东西画在同一个平面上会 z-fighting（近距离看起来一闪一闪的）。
     * 和 {@code MonitorConsoleRenderer.SURFACE} 是同一个数。
     */
    public static final double FACE_SURFACE = 0.5D + 0.003D;

    /**
     * 归一化面坐标 {@code u} 处的那个点，在<b>方块局部坐标系</b>里的 x 分量。
     *
     * <h3>★★ 这里的每一项都是必须的，少一项整块面板就画歪</h3>
     * 方块实体的渲染入口 {@code translate(x, y, z)} 把原点放在这一格方块的
     * <b>最小角</b>上 —— 方块内部就是 {@code 0..1}。所以：
     * <ul>
     *   <li><b>{@code 0.5} 那一项</b>：把点从"角"挪到"面中心"。少了它，
     *       面板会沿 u 轴偏半格、x/z 落到 {@code -0.5..0.5} —— 那半个方格子落在
     *       <b>隔壁那一格</b>里。顶在墙上或嵌在凹槽里时，这些四边形会被推进邻居方块内部、
     *       被深度测试整块挡掉，玩家看到的正面就只剩方块自己的贴图：<b>一个按钮、一颗灯都没有</b>。
     *       （2026-09-21 实锤：本类的 {@code facePointX/Z} 就是这一条留下的唯一出处，
     *       {@code PanelRenderer} 不许再自己写一遍。）</li>
     *   <li><b>{@code frontX * surface} 那一项</b>：把面板推到方块正面<b>外侧</b>一丁点。
     *       少了它，面板和方块自己的面共面 → 一是 z-fighting，二是会被方块自身的面挡住。</li>
     * </ul>
     * 对照物：{@code MonitorConsoleRenderer.Frame#cx} 写的是 {@code 0.5D + nx * SURFACE}，
     * {@code BlastDoorRenderer} 的注释也明写"原点在最小角、中心是 {@code r*dx + 0.5}"。
     * 三处必须同一条约定 —— 这就是为什么这里只写一遍，让渲染器来调。
     *
     * <p>
     * {@code v} 不需要换算：它就是方块自身局部坐标系的 y（0 = 底边，1 = 顶边）。
     *
     * @param frontX  正面法线的 x 分量（{@code EnumFacing#getFrontOffsetX}）
     * @param frontZ  正面法线的 z 分量
     * @param u       归一化面坐标，0 = 站在外面看时的最左，1 = 最右
     * @param surface 平面到方块中心的距离，一般直接传 {@link #FACE_SURFACE}
     * @return x ∈ [0,1]（就在这一格方块内，前提是 surface 取 {@link #FACE_SURFACE}）
     */
    public static double facePointX(double frontX, double frontZ, double u, double surface) {
        return 0.5D + frontX * surface + faceRightX(frontX, frontZ) * (u - 0.5D);
    }

    /** 见 {@link #facePointX}：同一个点在方块局部坐标系里的 z 分量 */
    public static double facePointZ(double frontX, double frontZ, double u, double surface) {
        return 0.5D + frontZ * surface + faceRightZ(frontX, frontZ) * (u - 0.5D);
    }

    // ---------------------------------------------------------------- 外观

    /**
     * 某个模式下第 {@code index} 个按钮的动作码。
     * <p>
     * 越界一律返回 {@link #ACT_NONE}（调用方应当什么都不做），
     * 不要抛异常 —— 这个值最终来自网络/点击坐标，属于"不可信输入"。
     */
    public static int actionOf(int mode, int index) {
        if (index < 0 || index >= BUTTONS) {
            return ACT_NONE;
        }
        if (mode == MODE_DMR) {
            switch (index) {
                case 0:
                    return ACT_DMR_IGNITE;
                case 1:
                    return ACT_DMR_COOLDOWN;
                case 2:
                    return ACT_DMR_MAINT;
                case 3:
                    return ACT_DMR_CELL;
                case 4:
                    return ACT_DMR_LASER;
                case 5:
                    return ACT_DMR_SUBSYS;
                default:
                    return ACT_NONE;
            }
        }
        if (mode == MODE_GATE) {
            switch (index) {
                case 0:
                    return ACT_GATE_OPEN;
                case 1:
                    return ACT_GATE_CLOSE;
                case 2:
                    return ACT_GATE_LOCK;
                case 3:
                    return ACT_GATE_UNLOCK;
                case 4:
                    return ACT_GATE_INTERLOCK;
                case 5:
                    return ACT_GATE_HEAVY;
                default:
                    return ACT_NONE;
            }
        }
        return ACT_NONE;
    }

    /**
     * 按钮文案的语言键。渲染器拿去交给 I18n。
     * <p>
     * 文案一律 <b>≤ 3 个汉字</b>：一个按钮只占方块正面的六分之一，
     * 字再长就会溢出到隔壁按钮上，反而更难认。
     */
    public static String labelKey(int mode, int index) {
        if (index < 0 || index >= BUTTONS) {
            return null;
        }
        String suffix;
        if (mode == MODE_DMR) {
            suffix = new String[]{"ignite", "cooldown", "maint", "cell", "laser", "subsys"}
                    [Math.min(index, 5)];
        } else {
            suffix = new String[]{"open", "close", "lock", "unlock", "interlock", "heavy"}
                    [Math.min(index, 5)];
        }
        return "gui.rsaudio.panel.btn." + suffix;
    }

    /** 面板标题的语言键（按模式区分） */
    public static String titleKey(int mode) {
        return mode == MODE_DMR ? "gui.rsaudio.panel.title.dmr" : "gui.rsaudio.panel.title.gate";
    }

    /**
     * 按钮底色（0xRRGGBB）。放在内核里是为了和 {@link #labelKey} 挨着 ——
     * 颜色和文案是一个按钮的一个整体，分开两地迟早会错位。
     * <p>
     * 配色逻辑：会"干出不可逆的事"的按钮（点火、停机、拨料）用暖色，
     * 状态切换类的用冷色，开门/关门居中。
     */
    public static int colorOf(int mode, int index) {
        if (mode == MODE_DMR) {
            switch (index) {
                case 0:
                    return 0xC8781E;   // 点火：琥珀（大事，但可逆）
                case 1:
                    return 0xB03030;   // 停机：红（这一步之后就是不可逆的窗口了）
                case 2:
                    return 0x3A5CA8;   // 维护：蓝
                case 3:
                    return 0x2E7A46;   // 装料：绿
                case 4:
                    return 0x2F7C8A;   // 修激光：青
                case 5:
                    return 0x5A4A96;   // 子系统：紫
                default:
                    return 0x50565E;
            }
        }
        switch (index) {
            case 0:
                return 0x2E7A46;       // 开门：绿
            case 1:
                return 0xB03030;       // 关门：红
            case 2:
                return 0x8A6A20;       // 锁定：暗金
            case 3:
                return 0x3A5CA8;       // 解锁：蓝
            case 4:
                return 0x5A4A96;       // 联锁：紫
            case 5:
                return 0x2F7C8A;       // 重型：青
            default:
                return 0x50565E;
        }
    }

    /**
     * 这个按钮是不是"开关型"，即按一下切换一个<b>持续状态</b>（维护进/出、子系统全开/全关、
     * 联锁开/关、重型开/关），而不是"按一下触发一次动作"（点火、停机、开门……）。
     * <p>
     * ★ 注意它<b>不再</b>决定"这颗按钮有没有灯" —— 6 颗按钮现在都有一颗灯（见 {@link #lampBit}）。
     * 这里保留的是<b>语义</b>：开关型按钮的文案要跟着当前状态换说法（比如"维护 / 退出维护"），
     * 界面上说明图例时也用得上。
     */
    public static boolean isToggle(int mode, int index) {
        if (mode == MODE_DMR) {
            // 维护（进/出）、子系统（全开/全关）是开关；点火/停机/装料/修激光是一次性动作
            return index == 2 || index == 5;
        }
        return index == 4 || index == 5;
    }

    // ------------------------------------------------------------ 状态位（面板灯）

    /*
     * 面板上的"小灯"位。★ 这些位由方块实体在<b>服务端</b>算好（它是唯一能看到目标当前状态
     * 的一侧），然后随方块实体的更新包同步给客户端，渲染器只负责读。
     *
     * 为什么不放在渲染器里现算：渲染器要拿到目标的状态，就得自己去反查那台机器，
     * 而"哪台机器是目标"这件事的权威在服务端（客户端看到的最近目标可能不是同一个）。
     * 两边各算一次 = 又一份会分叉的逻辑 —— 这正是这个包要消灭的东西。
     */

    /** 找到目标了（灯不亮，但渲染器据此选标题条的颜色） */
    public static final int ST_LINKED = 1;
    /** DMR 正在维护模式（维护那颗灯） */
    public static final int ST_DMR_MAINT = 1 << 1;
    /** DMR 四个子系统全开（子系统那颗灯） */
    public static final int ST_DMR_SUBSYS = 1 << 2;
    /** DMR 正在运行 / 启动中（点火那颗灯的呼吸感，渲染器可选用） */
    public static final int ST_DMR_RUNNING = 1 << 3;
    /** 门是开着的 */
    public static final int ST_GATE_OPEN = 1 << 4;
    /** 门被任何一种锁挡着 */
    public static final int ST_GATE_LATCH = 1 << 5;
    /** 门开着联锁 */
    public static final int ST_GATE_INTERLOCK = 1 << 6;
    /** 门是重型（塔塔鲁斯那种逐行升降） */
    public static final int ST_GATE_HEAVY = 1 << 7;
    /** 反应堆已停堆（"停机"那颗灯的亮条件：这一下已经生效了） */
    public static final int ST_DMR_SAFE = 1 << 8;
    /** 现在<b>装得进</b>燃料单元（处于维护模式且还没装满）—— 和 {@code DmrCore#installFuelCell} 同一把尺子 */
    public static final int ST_DMR_CELL_READY = 1 << 9;
    /** 功率激光缺了（"修激光"那颗灯） */
    public static final int ST_DMR_LASER_BAD = 1 << 10;
    /** 门是关着的（"关门"那颗灯；和 {@link #ST_GATE_OPEN} 互为反相，同一时刻只亮一颗） */
    public static final int ST_GATE_CLOSED = 1 << 11;
    /** 门没被锁（"解除"那颗灯；和 {@link #ST_GATE_LATCH} 互为反相，同一时刻只亮一颗） */
    public static final int ST_GATE_UNLOCKED = 1 << 12;

    /**
     * 本类申明过的所有状态位。★ 新加位时必须同步加进来 ——
     * {@code check_panel_core} 用它把"灯位不能落到这个集合之外"钉死，
     * 防止两颗灯撞到同一位（撞了就是"按 A 的灯、亮 B"）。
     */
    public static final int ST_ALL =
            ST_LINKED | ST_DMR_MAINT | ST_DMR_SUBSYS | ST_DMR_RUNNING
                    | ST_GATE_OPEN | ST_GATE_LATCH | ST_GATE_INTERLOCK | ST_GATE_HEAVY
                    | ST_DMR_SAFE | ST_DMR_CELL_READY | ST_DMR_LASER_BAD
                    | ST_GATE_CLOSED | ST_GATE_UNLOCKED;

    // ------------------------------------------------------------ 灯

    /*
     * ★★ 为什么"每颗按钮都有灯"，而不是只有开关型才有：
     *
     * 第一版只给"开关型"（维护/子系统、联锁/重型）配了灯，理由是"一次性动作用不着记状态"。
     * 但玩家看到的是<b>一块深色铁板上什么指示都没有</b> —— 面板装在哪里、有没有连上目标、
     * 门现在是开是关，全靠猜。真实控制盘上从来不是"只有开关才有灯"：每一颗按钮都带一颗
     * 指示灯，指示的是<b>这一下按下去会生效/已经生效</b>的那个状态。
     *
     * 于是这里改成：6 颗按钮各有一颗灯，各自认一位（{@link #lampBit}）。
     * 三条硬规矩：
     *   1. 灯位两两不同、且都在本类申明的位里（{@link #ST_ALL}）—— 撞位会让两颗灯一起亮；
     *   2. "成对"的按钮（开/关、封锁/解除）用互补的两位，任何时刻恰好亮一颗
     *      —— 玩家一眼就能看出门现在在哪一边；
     *   3. 灯的颜色也在这里定（{@link #lampColorOn}）：正常用绿、值得注意用琥珀、
     *      封锁用红。渲染器只管照抄，不许自己配一遍。
     */

    /** 灯灭时的镜片颜色（0xRRGGBB）。★ 不能是"几乎全黑" —— 灭着的灯也得看得出<b>那里有颗灯</b> */
    public static final int LAMP_COLOR_OFF = 0x2A332C;
    /** 灯亮：正常 */
    public static final int LAMP_COLOR_ON = 0x3CFF66;
    /** 灯亮：值得注意（激光缺、联锁开着） */
    public static final int LAMP_COLOR_WARN = 0xFFC040;
    /** 灯亮：封锁着 */
    public static final int LAMP_COLOR_BAD = 0xFF5050;

    /**
     * 第 {@code index} 颗按钮的灯认哪一位状态；越界（或没有灯）返回 <b>0</b>。
     * <p>
     * 返回 0 而不是 -1 是刻意的：0 不是任何一位，{@link #lampOn} 里的
     * {@code (status & 0) != 0} 自然就是 false —— 越界输入不会"点亮一整排"。
     */
    public static int lampBit(int mode, int index) {
        if (index < 0 || index >= BUTTONS) {
            return 0;
        }
        if (mode == MODE_DMR) {
            switch (index) {
                case 0:
                    return ST_DMR_RUNNING;      // 点火：亮 = 堆芯正开着
                case 1:
                    return ST_DMR_SAFE;         // 停机：亮 = 已经停堆了
                case 2:
                    return ST_DMR_MAINT;        // 维护：亮 = 正在维护模式
                case 3:
                    return ST_DMR_CELL_READY;   // 装料：亮 = 现在装得进去
                case 4:
                    return ST_DMR_LASER_BAD;    // 修激光：亮 = 有激光缺了
                case 5:
                    return ST_DMR_SUBSYS;       // 子系统：亮 = 四个全开
                default:
                    return 0;
            }
        }
        switch (index) {
            case 0:
                return ST_GATE_OPEN;            // 开门：亮 = 门开着
            case 1:
                return ST_GATE_CLOSED;          // 关门：亮 = 门关着
            case 2:
                return ST_GATE_LATCH;           // 封锁：亮 = 锁着
            case 3:
                return ST_GATE_UNLOCKED;        // 解除：亮 = 没锁
            case 4:
                return ST_GATE_INTERLOCK;       // 联锁：亮 = 联锁开着
            case 5:
                return ST_GATE_HEAVY;           // 重型：亮 = 这是一扇重型门
            default:
                return 0;
        }
    }

    /** 这颗按钮有没有灯位（6 颗都有；越界没有） */
    public static boolean hasLamp(int mode, int index) {
        return lampBit(mode, index) != 0;
    }

    /**
     * 第 {@code index} 颗按钮上的灯该不该亮 —— 就是"它认的那一位在不在 {@code status} 里"。
     * <p>
     * 灯位和判据都只在 {@link #lampBit} 一处，所以不存在"渲染器画了灯、却没有对应状态位"
     * 或者"两颗灯认了同一位"的岔子。
     */
    public static boolean lampOn(int mode, int index, int status) {
        int bit = lampBit(mode, index);
        return bit != 0 && (status & bit) != 0;
    }

    /** 灯亮时的颜色（0xRRGGBB），见上面那组常量 */
    public static int lampColorOn(int mode, int index) {
        if (mode == MODE_DMR) {
            return index == 4 ? LAMP_COLOR_WARN : LAMP_COLOR_ON;    // 修激光：缺了才亮，亮就是告警
        }
        if (index == 2) {
            return LAMP_COLOR_BAD;                                  // 封锁
        }
        return index == 4 ? LAMP_COLOR_WARN : LAMP_COLOR_ON;         // 联锁
    }
}

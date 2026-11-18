package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.access.AccessCore;
import com.rsaudio.block.BlockAlarmLight;
import com.rsaudio.block.BlockBlastDoor;
import com.rsaudio.block.BlockBlastDoorPanel;
import com.rsaudio.util.MachineAudio;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * 防核门的方块实体（挂在控制器那一格上）。
 *
 * <h3>门的形状</h3>
 * 控制器永远在门的<b>左下角</b>。门面沿"控制器朝向的右手方向"水平展开 {@code 宽度} 格、
 * 向上展开 {@code 高度} 格，其余格子都是门板。这样玩家只要记住"以我放的那块为准，往右往上长"。
 *
 * <h3>开 / 关</h3>
 * <ul>
 *     <li>开门：控制器与所有门板切成不挡路的形状（模型收进门框），玩家能走过去。</li>
 *     <li>关门：<b>先检查门里有没有实体</b>；有就记下"待关"、每 tick 重试，直到清空才关。
 *         直接关会把人夹死在里面的方块里（窒息），这在服务器上是会被投诉的。</li>
 * </ul>
 *
 * <h3>红石</h3>
 * 只在电平<b>变化</b>时响应（边沿触发），不是每 tick 强制同步。
 * 这样玩家用手动开关门之后，不会立刻被"红石电平没变"的状态顶回去。
 *
 * <h2>★★ 门禁 / 联锁 / 分级运动（QSERF 移植）</h2>
 * 门不再只有"开/关"，而是三种 QSERF 里的机构共用同一个方块：
 * <ul>
 *   <li><b>FAAS 远程锁定门</b>：上了门闩的门本地怎么按都开不了。
 *       门闩有"手动"和"远程"两种来源，只有显式解锁才消失（见 {@link AccessCore}）。</li>
 *   <li><b>气闸</b>：同一<b>频道</b>上的门互斥 —— 一扇开着，另一扇就开不了；
 *       而且一扇<b>关上</b>之后会给同频道其他门起一段"循环时间"，期间它们锁死。
 *       两道门贴在一起、配上同一个频道，就是一道气闸。</li>
 *   <li><b>塔塔鲁斯大门</b>：打开"重型"之后门不再一步到位，而是<b>逐行</b>升起，
 *       每行之间隔 {@code 分级间隔} 刻 —— 看起来才像一扇巨大的装甲闸门。</li>
 * </ul>
 *
 * <h3>★ 意图与实际位置是两件事</h3>
 * 普通门里这两个量总是相等，所以以前只用一个 {@code open} 就够了。但重型门要走好几刻，
 * 中间必然存在"想让它是开的、但它还没开完"的状态。所以拆成
 * {@link #wantOpen}（意图）+ {@link AccessCore#getProgress()}（实际开了几行）。
 * 门板的 {@code OPEN} 现在是<b>逐行</b>算的（{@link AccessCore#rowIsOpen}），
 * 不再是"整扇门同一个值"。
 */
public class TileBlastDoor extends TileEntity implements ITickable {

    public static final int MIN_SIZE = 1;
    public static final int MAX_SIZE = 7;
    public static final int DEFAULT_WIDTH = 3;
    public static final int DEFAULT_HEIGHT = 3;

    /**
     * 联锁扫描间隔（刻）。
     * <p>
     * 每刻都去遍历 {@code loadedTileEntityList} 太浪费（那是个几千项的列表），
     * 而"另一扇门开了"这件事的实时性要求并不高 —— 10 刻 = 0.5 秒的延迟玩家感觉不到。
     */
    public static final int SCAN_INTERVAL = 10;

    /**
     * 平滑开合速度（每 tick 推进的 0..1 比例）。
     * <p>
     * ★ 门现在<b>真的会动</b>：之前开关只是把方块模型换成"开"的贴图，一帧切完、看不出运动。
     * 这里改成 0..1 的连续进度（{@link #openAmount}），渲染按它把整扇门沿收进方向平移，
     * 行数（碰撞）也由它推出来 —— 看到的和走上去的是同一个量。
     * 每 tick 走 {@code speed}，所以全程耗时 = {@code 1 / (speed * 20)} 秒：
     * 普通门 {@code 1/(0.042*20) ≈ 1.2 秒}，重型 {@code 1/(0.020*20) = 2.5 秒}。
     * 「重型」在这里的语义就是走得更慢、更有分量，不再是"逐行卡顿"。
     *
     * <h3>★ 上限是被气闸逼出来的，别再调慢</h3>
     * 气闸控制器等一个物理动作（两侧关好 / 目标侧打开）最多 {@code lockTimeout} 刻
     * （默认 100 刻 = 5 秒），超时就判定"这一步走不完"→ {@code abort}（急停）。
     * 上一版把速度设成 0.014 / 0.005（≈3.6 秒 / 10 秒），重型门直接超过 5 秒 →
     * 气闸每次都超时急停，玩家看到的就是"乱触发急停、急停后门又乱开"。
     * 所以<b>单程耗时必须明显小于 5 秒</b>（这里最慢 2.5 秒，留了一倍余量）。
     */
    public static final float SPEED_NORMAL = 0.042F;
    public static final float SPEED_HEAVY = 0.020F;

    /**
     * 门板"补不回来"时的重试上限（刻）。
     * 关门正好撞上一个刚踏进门框的生物时，那一格这次不能补（不能把生物砌进墙里），
     * 于是标记成待补、下 tick 再试 —— 但要有上限：万一生物就是赖着不走，
     * 也不能每 tick 扫 49 格扫到天荒地老。2 秒（40 刻）足够它走开，之后放弃并报一次日志。
     */
    private static final int CELL_RETRY_MAX = 40;

    private int width = DEFAULT_WIDTH;
    private int height = DEFAULT_HEIGHT;
    /** true = 向上收（收进门楣），false = 向下收（沉进地板） */
    private boolean retractUp = false;
    /** 反向：有信号时反而关门 */
    private boolean invert = false;

    private boolean built = false;
    /** 意图：希望门是开的。门要走好几刻才到位，所以它和"实际开了几行"是两件事 */
    private boolean wantOpen = false;
    /**
     * ★ 平滑开合进度：0 = 完全关、1 = 完全收起（门沿收进方向让开了整格高度）。
     * 渲染（TESR）按它平移门体，碰撞的行数也由它推出来，两边永远不会各说一套。
     */
    private float openAmount = 0.0F;
    /**
     * 客户端自己插值的副本。
     * <p>
     * ★ 刻意做成 {@code transient} 且<b>不</b>每 tick 走网络：服务端只在进度真的变了时才同步
     * {@code openAmount}，客户端自己朝它缓动，动画才连得上（否则会一顿一顿的）。
     */
    public transient float clientAnim = 0.0F;
    /** 客户端第一次拿到同步包时把 clientAnim 对齐过去，避免门"自己滑一下" */
    private transient boolean animSeeded = false;
    /** 因为门里有实体而推迟的关门 */
    private boolean pendingClose = false;
    /**
     * 关门途中因门里有实体而暂停时，只广播一次状态/日志用的去重旗标
     * （stepMotion 每 tick 都会进来，没有它一行就会刷成 20 行/秒）。
     */
    private boolean closeHeldAnnounced = false;
    /** 上一刻是不是"至少开了一行"。用来抓"刚关上"这个边沿 → 给同频道的另一侧起气闸循环 */
    private boolean wasOpen = false;
    /**
     * 关门时"某一格门板补不回来（被别的方块占着）"只播报一次的去重旗标。
     * 不复用 closeHeldAnnounced：那是"等实体走开"的暂停，两者是完全不同的成因，
     * 合成一个旗标的话，先出现哪个就会把另一个的播报吃掉（"故障必须自报成因"）。
     */
    private boolean cellBlockedReported = false;
    /**
     * 上一趟 {@link #applyCells()} 有没有"该补的门板因为格里有实体而没补成"。
     * <p>
     * ★ 有的话必须<b>接着重试</b>：这个状态只在"关门正好撞上一个刚踏进门框的生物"时出现，
     * 而且 applyCells() 平时只在开合过程中被调用 —— 不管的话那一格就<b>永远空着</b>，
     * 门看着关严了、实际缺一块，是典型的静默不一致。重试上限见 {@link #CELL_RETRY_MAX}。
     */
    private boolean cellsDirty = false;
    /** 上面那个重试已经试了几刻（达到 CELL_RETRY_MAX 就放弃并报日志） */
    private int cellRetryTicks = 0;

    private boolean initialized = false;
    private boolean lastPowered = false;
    /** 正在拆门：防止 setBlockToAir 反过来又调回来形成递归 */
    private boolean removing = false;
    /** 联锁扫描的节流计数 */
    private int scanCooldown = 0;

    private String status = "";

    /** 门上的旋转警报灯：是否启用（建造后默认开） */
    private boolean alarmLights = true;
    /** 警报灯颜色：复用 {@link BlockAlarmLight} 六色调色板下标（0..5），默认红 */
    private int alarmColor = 0;

    /** 门禁 / 联锁 / 分级运动的状态机。全部规则都在那个零依赖的内核里，这里只负责接线 */
    private final AccessCore access = new AccessCore();

    // ---------------------------------------------------------------- 参数

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean isRetractUp() {
        return retractUp;
    }

    public boolean isInvert() {
        return invert;
    }

    public boolean isBuilt() {
        return built;
    }

    /** 实际已经开到底了吗 */
    public boolean isOpen() {
        return access.isFullyOpen(height);
    }

    /** 意图上是想开着吗（重型门开合途中为 true） */
    public boolean wantsOpen() {
        return wantOpen;
    }

    /**
     * 正在开合途中（平滑进度还没走到意图）。
     * <p>
     * ★ 改成看 {@link #openAmount} 而不是行数：行数只在跨过整行时才变，
     *   拿它判断"还在动吗"会在行与行之间误报"已经停了"。
     */
    public boolean isMoving() {
        if (!built) {
            return false;
        }
        float target = wantOpen ? 1.0F : 0.0F;
        return Math.abs(openAmount - target) > 0.001F;
    }

    /** 平滑开合进度 0..1：0 = 完全关、1 = 完全收起（渲染按它平移门体） */
    public float getOpenAmount() {
        return openAmount;
    }

    /** 客户端缓动出来的进度（只有客户端这一份会自己动，见 {@link #clientAnim}） */
    public float getClientAnim() {
        return clientAnim;
    }

    /**
     * 客户端渲染每帧调用：把 {@link #clientAnim} 缓动到服务端同步过来的 {@link #openAmount}。
     * <p>
     * ★ 刻意放在这里、由渲染器调用，而不是放进 {@code update()}：
     *   客户端的方块实体不保证每 tick 被 tick 到，而渲染是每帧必定发生的 ——
     *   依赖客户端 tick 的话动画会在某些版本/某些情况下完全不动。
     */
    public void stepClientAnim() {
        float target = openAmount;
        if (Math.abs(clientAnim - target) <= 0.0005F) {
            clientAnim = target;
            return;
        }
        // 每帧最多追 0.08：几帧内追上新值，肉眼看是连续的、又不会出现"瞬移"
        float step = 0.08F;
        if (clientAnim < target) {
            clientAnim = Math.min(target, clientAnim + step);
        } else {
            clientAnim = Math.max(target, clientAnim - step);
        }
    }

    private static float clamp01(float v) {
        if (v < 0.0F) {
            return 0.0F;
        }
        return v > 1.0F ? 1.0F : v;
    }

    public String getStatus() {
        return status == null ? "" : status;
    }

    // ---- 门禁相关（转发给内核，界面 / 网络包用） ----

    public AccessCore getAccess() {
        return access;
    }

    public int getChannel() {
        return access.getChannel();
    }

    public boolean isInterlock() {
        return access.isInterlock();
    }

    public int getCycleTicks() {
        return access.getCycleTicks();
    }

    public int getCycleRemaining() {
        return access.getCycleRemaining();
    }

    public boolean isHeavy() {
        return access.isHeavy();
    }

    public int getStageDelay() {
        return access.getStageDelay();
    }

    public int getProgress() {
        return access.getProgress();
    }

    public boolean isLatched() {
        return access.isLatched();
    }

    /** 两个门闩位打包（{@link AccessCore#latchBits()}）。门禁终端据此判断该不该动这扇门 */
    public int getLatchBits() {
        return access.latchBits();
    }

    // ---- 旋转警报灯 ----

    public boolean isAlarmLights() {
        return alarmLights;
    }

    public int getAlarmColor() {
        return alarmColor;
    }

    /**
     * 设置门上旋转警报灯的开关与颜色（界面「警报灯」那一行用）。
     * 颜色下标越界会被夹回合法范围。
     */
    public void setAlarmConfig(boolean on, int color) {
        int c = Math.max(0, Math.min(BlockAlarmLight.COLOR_COUNT - 1, color));
        if (on == this.alarmLights && c == this.alarmColor) {
            return;
        }
        this.alarmLights = on;
        this.alarmColor = c;
        markDirty();
        syncToClient();
    }

    public boolean hasManualLatch() {
        return access.hasManualLatch();
    }

    public boolean hasRemoteLatch() {
        return access.hasRemoteLatch();
    }

    /** 气闸控制器是不是正封着这一扇（{@link AccessCore#REASON_AIRLOCK}） */
    public boolean hasAirlockLatch() {
        return access.hasAirlockLatch();
    }

    /**
     * 气闸控制器封门 / 放行。
     * <p>
     * ★ 只动<b>气闸</b>那一位。这是三把锁分开的全部理由：循环只有十几秒，
     * 结束时必须自己消失，而"自己消失"的那次解锁绝不能顺手解掉玩家手动上的
     * 或 FAAS 下发的锁。
     *
     * @return 那一位是不是真的变了（不变就不用写 NBT）
     */
    public boolean setAirlockLock(boolean locked, EntityPlayer player) {
        if (!access.latchAirlock(locked)) {
            return false;
        }
        setStatus(locked ? "气闸密闭" : "气闸放行");
        notify(player, status);
        return true;
    }

    /**
     * 清掉门自己的"另一侧刚关上"倒计时。
     * <p>
     * 气闸控制器接管时序之后，门那段倒计时就成了干扰：本次循环里门确实关过，
     * 于是它的 {@code cycleRemaining} 被拉满，控制器走到"该放行"这一步时会被它挡回来，
     * 白白晚几秒才开门。所以控制器在放行前显式清一次。
     */
    public void clearCycle() {
        if (access.getCycleRemaining() == 0) {
            return;
        }
        access.clearCycle();
        markDirty();
        syncToClient();
    }

    public int getLatch() {
        return access.getLatch();
    }

    /** 当前挡着开门的原因（{@link AccessCore} 里的 REASON_* 常量），界面据此选语言键 */
    public int getBlockReason() {
        return access.blockReason();
    }

    public static int clampSize(int value) {
        if (value < MIN_SIZE) {
            return MIN_SIZE;
        }
        return value > MAX_SIZE ? MAX_SIZE : value;
    }

    /** 把"开门被挡"的原因变成给玩家看的文字 */
    public static String blockReasonText(int reason) {
        switch (reason) {
            case AccessCore.REASON_MANUAL:
                return "门已被手动封锁，先在设置界面点「解除封锁」";
            case AccessCore.REASON_REMOTE:
                return "门已被 FAAS 远程锁定，要由门禁终端或红石解除";
            case AccessCore.REASON_CYCLE:
                return "气闸循环中，等这一轮走完";
            case AccessCore.REASON_AIRLOCK:
                return "气闸控制器正封着这一侧，等循环走完（或按急停）";
            case AccessCore.REASON_INTERLOCK:
                return "联锁：同一频道上还有另一扇门开着";
            default:
                return "";
        }
    }

    /**
     * 应用界面上的参数。
     * <p>
     * 如果已经建好了，改尺寸/收进方向会让旧门板变成"没人认领的孤儿"，
     * 所以这里<b>先把门拆掉</b>，提示玩家重新点「建造」。这比偷偷留下残骸好得多。
     * <p>
     * ★ 门禁参数（频道/联锁/循环/重型/分级间隔）<b>不需要拆门</b> ——
     * 它们不改几何，改了立刻生效，所以放在同一个方法里但走不同的分支。
     */
    public void applyParams(int newWidth, int newHeight, boolean newRetractUp, boolean newInvert,
                            int newChannel, boolean newInterlock, int newCycle,
                            boolean newHeavy, int newStageDelay) {
        int w = clampSize(newWidth);
        int h = clampSize(newHeight);
        boolean structureChanged = w != width || h != height || newRetractUp != retractUp;

        this.invert = newInvert;
        this.access.setChannel(newChannel);
        this.access.setCycleTicks(newCycle);
        this.access.setInterlock(newInterlock);
        this.access.setHeavy(newHeavy);
        this.access.setStageDelay(newStageDelay);

        if (structureChanged && built) {
            removeDoor(false, "尺寸/收进方向改了（旧门板会变成孤儿，先拆掉）");
            this.width = w;
            this.height = h;
            this.retractUp = newRetractUp;
            setStatus("尺寸/收进方向改了，旧门板已拆除，请重新点「建造」");
            markDirty();
            syncToClient();
        } else {
            this.width = w;
            this.height = h;
            this.retractUp = newRetractUp;
            // ★ 门变矮了的话，残留的进度会落在门高之外 —— 必须夹回来，
            //   否则会出现"门高 3、却开到第 7 行"这种自相矛盾的状态
            if (access.getProgress() > height) {
                access.setProgress(height);
            }
            applyCells();
            markDirty();
            syncToClient();
        }
    }

    public void setInvert(boolean value) {
        if (invert == value) {
            return;
        }
        invert = value;
        markDirty();
        syncToClient();
    }

    /**
     * 翻转门闩（界面上的「封锁 / 解除封锁」）。
     * <p>
     * ★ 只动<b>手动</b>那一位（{@link AccessCore#toggleLatch()}）。所以文字也必须只说手动，
     * 不能拿 {@link AccessCore#getLatch()} 去猜 —— 那把锁是"手动优先"的，
     * 在一扇已经被 FAAS 远程锁着的门上再拉一把手动锁，本来只是"又加了一把"，
     * 照 getLatch() 说成"已远程锁定"会让玩家以为远程那边动了。
     *
     * @return 翻转之后手动门闩是否处于锁定态
     */
    public boolean toggleLatch(EntityPlayer player) {
        boolean latched = access.toggleLatch();
        markDirty();
        syncToClient();
        String text = latched ? "已手动封锁这扇门" : "已解除手动封锁";
        if (!latched && access.hasRemoteLatch()) {
            // 手动那把解了、FAAS 那把还在 —— 说清楚，不然玩家会以为"解了怎么还开不了"
            text = "已解除手动封锁（FAAS 远程锁定还在）";
        }
        setStatus(text);
        if (player != null) {
            player.sendStatusMessage(new TextComponentString(text), true);
        }
        return latched;
    }

    /**
     * <b>按目标状态</b>设置手动门闩（远程控制面板上的「封锁 / 解除封锁」两颗按钮用）。
     *
     * <h3>为什么不是复用 {@link #toggleLatch}</h3>
     * 面板上那是<b>两颗独立的按钮</b>，不是一颗开关。用 toggle 的话，
     * "在已经锁着的门上再按一次封锁"会把它解锁 —— 这是最典型的"按钮和它写的事情不一致"，
     * 而远程操作又看不见门，玩家只会觉得"按了封锁门反而开了"。
     * 所以这里要的是幂等的 set 语义。
     *
     * <h3>★ 为什么动的是"手动"位，而不是像门禁终端那样动"远程"位</h3>
     * 两者是<b>故意分开</b>的两把锁（见 {@link AccessCore}）：
     * <ul>
     *   <li>{@code REASON_REMOTE} 是 FAAS 区域封锁用的，而且
     *       {@code TileAccessTerminal} 会<b>周期性重新校准</b>它；
     *       面板要是也去写这一位，就会和终端互相覆盖 ——
     *       症状是门上的锁一闪一闪、或者刚锁上就被解开，两个功能都变得不可信。</li>
     *   <li>面板是"有人站在墙边按了一下"，语义上就是手动那一把，
     *       和门自己界面上那颗「封锁」是同一把锁。</li>
     * </ul>
     * 于是顺带得到一条正确的安全性质：<b>区域封锁锁着的时候，墙上这块面板解不开它</b> ——
     * 必须先由终端解除。这正是隔离区该有的行为。
     *
     * @return 门闩是否真的变了（已经是目标状态时返回 false，且不报"已封锁"）
     */
    public boolean setManualLatch(boolean locked, EntityPlayer player) {
        boolean changed = locked
                ? access.latch(AccessCore.REASON_MANUAL)
                : access.unlatch(AccessCore.REASON_MANUAL);
        if (!changed) {
            return false;
        }
        markDirty();
        syncToClient();
        String text = locked ? "已手动封锁这扇门" : "已解除手动封锁";
        if (!locked && access.hasRemoteLatch()) {
            // 手动那把解了、FAAS 那把还在 —— 必须说清楚，不然玩家会以为"解了怎么还开不了"
            text = "已解除手动封锁（FAAS 远程锁定还在）";
        }
        setStatus(text);
        notify(player, text);
        return true;
    }

    /**
     * 远程锁定 / 解锁（FAAS 门禁终端、红石、指令）。
     *
     * <h3>★ 加锁加的是"远程"位，解锁也只能解"远程"位</h3>
     * 门上那份门闩是两个独立的位（手动 / 远程，见 {@link AccessCore}）。这里两个分支都必须
     * 明确指向<b>远程</b>那一位：
     * <ul>
     *   <li>加锁：{@code latch(REASON_REMOTE)}——只点亮自己的位，不碰玩家手动上的锁；</li>
     *   <li>解锁：{@code unlatch(REASON_REMOTE)}——<b>只能</b>写这个。</li>
     * </ul>
     * ★ 这里曾经写成无参的 {@code access.unlatch()}（它等价于 {@code unlatch(REASON_MANUAL)}，
     * 是留给界面上那颗「解除封锁」按钮的）。症状是<b>远程锁永远解不掉</b>：
     * 终端按「解除封锁」什么都不会发生，而且不报错、不刷日志 ——
     * 只会看到门一直打不开，玩家会以为是联锁或红石的问题。
     * 内核那边（{@code TerminalCore.reconcile}）明确会返回 {@code RECONCILE_UNLOCK}，
     * 所以问题一定出在这一句上。
     *
     * @param locked true = 锁上（来源记作"远程"，和手动封锁区分开，
     *               这样玩家自己解不掉 —— 必须由下发锁定的那一侧解除）
     */
    public boolean setRemoteLock(boolean locked, EntityPlayer player) {
        boolean changed = locked
                ? access.latch(AccessCore.REASON_REMOTE)
                : access.unlatch(AccessCore.REASON_REMOTE);
        if (!changed) {
            return false;
        }
        markDirty();
        syncToClient();
        String text = locked ? "已被 FAAS 远程锁定" : "远程锁定已解除";
        setStatus(text);
        if (player != null) {
            player.sendStatusMessage(new TextComponentString(text), true);
        }
        return true;
    }

    private void setStatus(String text) {
        this.status = text == null ? "" : text;
        markDirty();
        syncToClient();
    }

    private void notify(EntityPlayer player, String text) {
        if (player != null && text != null && !text.isEmpty()) {
            player.sendStatusMessage(new TextComponentString(text), true);
        }
    }

    // ---------------------------------------------------------------- 几何

    public net.minecraft.util.EnumFacing getFacing() {
        if (world == null) {
            return net.minecraft.util.EnumFacing.NORTH;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockBlastDoor) {
            return state.getValue(BlockBlastDoor.FACING);
        }
        return net.minecraft.util.EnumFacing.NORTH;
    }

    /** 门面内的"右"方向（俯视顺时针） */
    public net.minecraft.util.EnumFacing getRight() {
        return getFacing().rotateY();
    }

    /** 第 (dx, dy) 格门的位置（dx 向右，dy 向上，控制器是 (0,0)） */
    public BlockPos cellAt(int dx, int dy) {
        return pos.offset(getRight(), dx).up(dy);
    }

    /** 某个位置是不是这扇门的一格 */
    public boolean contains(BlockPos target) {
        if (target == null) {
            return false;
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                if (cellAt(dx, dy).equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 门面中心的那个位置（气闸配对、终端寻址时当"门的位置"用） */
    public BlockPos anchor() {
        return cellAt(width / 2, height / 2);
    }

    /**
     * 门体（整扇门占的所有格）到某个点的<b>最近距离平方</b> —— 也就是"这扇门离它有多远"。
     *
     * <h3>★★ 为什么不能用 {@code getPos().distanceSq(other)}</h3>
     * {@code getPos()} 是门的<b>左下角</b>，不是门的中心，更不是门的最近边。7×7 的门，
     * 从左下角量比从最近边量能多出 7 格多。气闸控制器就是踩了这个坑：
     * 它按"到角点的距离 ≤ 8"找门，而玩家造的气闸是控制器 (287,4,1349)、
     * 两扇门在 (279,4,1346) 和 (291,4,1348) —— 近的那扇门角点在 8.54 格外，
     * 被直接排除，于是"只看得到一扇门"、永远配不上对。
     * 按<b>最近边</b>量，同一扇门只有 5.52 格，稳稳在范围内。
     *
     * <p>
     * 量的是点到轴对齐包围盒的距离（点在盒内时为 0），所以门多大都按它真正挡在哪儿算。
     */
    public double distanceSqToNearestCell(BlockPos from) {
        if (from == null || world == null) {
            return Double.MAX_VALUE;
        }
        BlockPos far = cellAt(width - 1, height - 1);
        double minX = Math.min(pos.getX(), far.getX());
        double maxX = Math.max(pos.getX(), far.getX()) + 1.0D;
        double minY = Math.min(pos.getY(), far.getY());
        double maxY = Math.max(pos.getY(), far.getY()) + 1.0D;
        double minZ = Math.min(pos.getZ(), far.getZ());
        double maxZ = Math.max(pos.getZ(), far.getZ()) + 1.0D;
        double cx = from.getX() + 0.5D;
        double cy = from.getY() + 0.5D;
        double cz = from.getZ() + 0.5D;
        double dx = Math.max(0.0D, Math.max(minX - cx, cx - maxX));
        double dy = Math.max(0.0D, Math.max(minY - cy, cy - maxY));
        double dz = Math.max(0.0D, Math.max(minZ - cz, cz - maxZ));
        return dx * dx + dy * dy + dz * dz;
    }

    /** 供界面显示：{@code 3×3 · 已建 · 已开 · 频道3 · 已封锁} */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(width).append('×').append(height);
        sb.append(built ? " · 已建" : " · 未建");
        if (built) {
            if (isMoving()) {
                sb.append(" · 开合中 ").append(access.getProgressPercent(height)).append('%');
            } else {
                sb.append(wantOpen ? " · 已开" : " · 已关");
            }
        }
        if (access.getChannel() != AccessCore.CHANNEL_NONE) {
            sb.append(" · 频道").append(access.getChannel());
        }
        if (access.isInterlock()) {
            sb.append(" · 联锁");
        }
        if (access.isHeavy()) {
            sb.append(" · 重型");
        }
        if (access.isLatched()) {
            // ★ 三个位分开报。合成一句"已封锁"的话，玩家在远程封锁的门上又拉了一把手动锁、
            //   然后解掉手动锁，会以为"封锁解了怎么还开不了" —— 其实是远程那把还在
            if (access.hasManualLatch() && access.hasRemoteLatch()) {
                sb.append(" · 手动+远程封锁");
            } else if (access.hasRemoteLatch()) {
                sb.append(" · 远程封锁");
            } else if (access.hasAirlockLatch()) {
                // 气闸封只有十几秒，报清楚免得玩家以为门坏了
                sb.append(" · 气闸密闭中");
            } else {
                sb.append(" · 已封锁");
            }
        }
        if (access.getCycleRemaining() > 0) {
            sb.append(" · 循环").append(access.getCycleRemaining());
        }
        if (invert) {
            sb.append(" · 反向");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 建造 / 拆除

    /**
     * 建造门板。
     *
     * @return null = 成功；否则是给玩家看的错误文字
     */
    public String build(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return null;
        }
        if (built) {
            return "已经建好了（要改尺寸请先点「拆除」）";
        }
        if (pos.getY() + height - 1 > world.getActualHeight() - 1) {
            return "门太高了，超出世界高度";
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                BlockPos cell = cellAt(dx, dy);
                if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState existing = world.getBlockState(cell);
                if (!existing.getBlock().isReplaceable(world, cell)) {
                    setStatus("第 " + (dx + 1) + " 列 " + (dy + 1) + " 行被占着，先清空那一格");
                    return "第 " + (dx + 1) + " 列 " + (dy + 1) + " 行被别的方块占着，先清空";
                }
            }
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                BlockPos cell = cellAt(dx, dy);
                if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                    continue;
                }
                world.setBlockState(cell, BlockBlastDoorPanel.stateFor(false, retractUp), 3);
            }
        }
        built = true;
        // ★ 刚建好的门一律是"关到底"：即使门里有实体也保持关着。
        //   旧版本这里是"门里有实体就先开着"，但那样会绕过联锁和封锁 ——
        //   一扇被 FAAS 锁定的门如果因为建造时恰好有人站在里面就自动敞开，封锁就形同虚设。
        access.setProgress(0);
        wantOpen = false;
        wasOpen = false;
        pendingClose = false;
        // ★ openAmount 也要一起归零 —— applyCells() 是拿 getSlide()（由 openAmount 算出来）
        //   当唯一尺子的，不复位的话"改尺寸后重建"会按上一次的旧进度去长门板。
        openAmount = 0.0F;
        closeHeldAnnounced = false;
        cellBlockedReported = false;
        cellsDirty = false;
        cellRetryTicks = 0;
        applyCells();
        registerCells();
        setStatus("已建造 " + width + "×" + height + " 的门"
                + (entitiesInside() ? "（门里有实体，关门会稍等它走开）" : ""));
        // ★ 建造也要留痕：和 removeDoor 的"拆门"日志成对，才能从日志里看出
        //   "门是什么时候有的、什么时候没的"（拆门日志单独存在的话对不上时间点）
        RsAudio.log("[%s] 建造防核门 %d×%d（%s收，朝向 %s）", format(pos), Integer.valueOf(width),
                Integer.valueOf(height), retractUp ? "向上" : "向下", getFacing());
        markDirty();
        syncToClient();
        return null;
    }

    /**
     * 拆除门板。
     *
     * @param removeControllerBlock 是否连控制器方块一起拆掉（拆门板 / 拆门时用）
     * @param reason                给玩家/日志看的成因。<b>必填</b> —— 见下面的说明
     *
     * <h3>★★★ 为什么 "为什么拆" 是必填参数</h3>
     * 2026-09-25 的现场：气闸"放行完成"（= 把出口那扇门打开）的<b>同一秒</b>，
     * 频道里"已建造的门"从 2 扇变成 1 扇，半分钟后又变成 0 扇 —— 整扇门凭空消失，
     * 而<b>日志里一个字都没有</b>，只能靠存档里剩下几个方块实体反推。
     * 拆门是"把世界状态抹掉"的动作，必须能和别的日志对上时间点；
     * 成因做成参数（而不是让调用方自己 log）就是为了让每个调用点在编译期被迫说清楚。
     */
    public void removeDoor(boolean removeControllerBlock, String reason) {
        if (world == null || world.isRemote || removing) {
            return;
        }
        RsAudio.log("[%s] 拆门（%d×%d，%s）：%s", format(pos), Integer.valueOf(width),
                Integer.valueOf(height), removeControllerBlock ? "连控制器一起" : "保留控制器",
                reason == null ? "★ 未说明成因" : reason);
        removing = true;
        try {
            for (int dy = 0; dy < height; dy++) {
                for (int dx = 0; dx < width; dx++) {
                    BlockPos cell = cellAt(dx, dy);
                    if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                        continue;
                    }
                    if (world.getBlockState(cell).getBlock() instanceof BlockBlastDoorPanel) {
                        world.setBlockState(cell, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
                    }
                }
            }
            this.built = false;
            this.wantOpen = false;
            this.wasOpen = false;
            this.pendingClose = false;
            this.openAmount = 0.0F;
            this.closeHeldAnnounced = false;
            this.cellBlockedReported = false;
            this.cellsDirty = false;
            this.cellRetryTicks = 0;
            access.setProgress(0);
            // 门都没了，"气闸循环中"这种状态也就没有意义了
            access.clearCycle();
            access.setInterlockBlocked(false);
            // ★ 门闩刻意<b>保留</b>：封锁是"这片区域的规定"，和门板在不在没关系。
            //   拆了重盖一扇门就自动解除封锁，那封锁就太容易绕过了。
            if (removeControllerBlock) {
                world.setBlockToAir(pos);
            } else {
                refreshControllerState();
                markDirty();
                syncToClient();
            }
        } finally {
            removing = false;
        }
    }

    // ---------------------------------------------------------------- 开 / 关

    /**
     * 手动开关（<b>手持木斧右键</b>，见 {@code BlockBlastDoor#onBlockActivated}）。
     * <p>
     * ★ 2026-09-25 起"空手右键"不再开门（用户要求）：唯一的本地入口是手持木斧。
     * 本方法<b>不做</b>那条判断 —— 它同时被红石、气闸控制器、远程面板、设置界面调用，
     * 那些都是合法的自动化路径。"谁拿着木斧"只在两个<b>人为</b>入口上校验：
     * 方块交互（{@code WandHandler.isWand}）和界面发来的 {@code PacketSetDoor}（同样查它）。
     */
    public void toggleDoor(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return;
        }
        if (!built) {
            notify(player, "这扇门还没建造（用木斧潜行右键设置）");
            return;
        }
        requestOpen(!wantOpen, player);
    }

    public void requestOpen(boolean desired, EntityPlayer player) {
        if (!built) {
            setStatus("还没建造，先点「建造」");
            return;
        }
        requestOpenInternal(desired, player);
    }

    private void requestOpenInternal(boolean desired, EntityPlayer player) {
        if (desired == wantOpen) {
            pendingClose = false;
            return;
        }
        if (!desired && entitiesInside()) {
            // 关门会把人夹住 → 先记下来，等门里空了再关
            pendingClose = true;
            setStatus("门里有实体，等它走开再关");
            notify(player, "门里有实体，等它走开再关");
            return;
        }
        if (desired) {
            int reason = access.blockReason();
            if (reason != AccessCore.REASON_NONE) {
                // ★ 被挡住时<b>不记意图</b>。记了的话，等封锁一解除门就会自己弹开 ——
                //   玩家会觉得"我明明没按"，而且隔离区的门自己打开是要出事的。
                String msg = blockReasonText(reason);
                setStatus(msg);
                notify(player, msg);
                return;
            }
        }
        pendingClose = false;
        this.wantOpen = desired;
        markDirty();
        syncToClient();

        // ★ 开门/关门不再播报「大门开启 / 大门关闭」音效（用户觉得太吵，已移除）。
        //   MachineAudio.doorMotion 方法本身也已彻底删除 —— 只摘调用点的话，
        //   这个方法迟早会被别处重新接回去，又变成"响了才发现没删干净"。

        // ★ 不再"普通门立刻到位"：不论重型与否都走 {@link #stepMotion} 的连续进度，
        //   否则门还是一帧切完、看不出运动（用户要的就是动画）。状态先报"运动中"。
        setStatus(desired ? "门升起中…" : "门落下中…");
    }

    /**
     * 推进一格平滑进度，并把行数（= 碰撞 / 门板方块状态）推到它推导出来的位置。
     *
     * @return 行数有没有跨过整行（变了才需要去改门板的方块状态）
     */
    private boolean stepMotion() {
        float target = wantOpen ? 1.0F : 0.0F;
        if (Math.abs(openAmount - target) <= 1e-4F) {
            openAmount = target;
            closeHeldAnnounced = false;
            return false;
        }
        // ★★ 关门<b>途中</b>门里进来实体 → 原地暂停，等它走开再继续。
        //   只在"发起关门那一刻"查一次是不够的：关门要好几秒，
        //   生物完全可以在这几秒里溜进门框，门继续合拢就把生物压进门板里
        //   （窒息判定看的是眼睛所在格是不是 normal cube，跟碰撞盒无关，
        //    所以就算碰撞盒是部分高度也救不了它）—— 玩家看到的"被门夹死"就是这个。
        //   开门方向不用查：门让开只会放生物走，压不到。
        if (!wantOpen && entitiesInside()) {
            if (!closeHeldAnnounced) {
                closeHeldAnnounced = true;
                setStatus("关门暂停：门里有实体，等它走开");
                RsAudio.log("[" + format(pos) + "] 关门暂停（门里有实体）");
            }
            return false;
        }
        closeHeldAnnounced = false;
        // ★ 「重型」的语义现在是"走得更慢、更有分量"，而不是"逐行卡顿" ——
        //   门体由 TESR 连续平移，行与行之间的台阶已经被渲染抹平了
        float speed = access.isHeavy() ? SPEED_HEAVY : SPEED_NORMAL;
        if (openAmount < target) {
            openAmount = Math.min(target, openAmount + speed);
        } else {
            openAmount = Math.max(target, openAmount - speed);
        }
        int rows = Math.round(openAmount * height);
        if (rows == access.getProgress()) {
            return false;
        }
        access.setProgress(rows);
        return true;
    }

    // ---------------------------------------------------------------- 连续碰撞

    /** 日志用的坐标格式（x,y,z）—— 日志必须带坐标，一台世界里有很多扇门 */
    private static String format(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /**
     * 门体当前的滑动量（格）。渲染和碰撞<b>共用这一个量</b>。
     * 向上收为正、向下收为负；滑满一个门高就完全让开门洞。
     */
    public float getSlide() {
        return (retractUp ? 1.0F : -1.0F) * openAmount * Math.max(1, height);
    }

    /**
     * 第 {@code dy} 行这一格，此刻实际被门体盖住的竖直区间（<b>方块局部 0..1</b>）。
     *
     * <h3>★ 这一条才是"去掉瞬间开门"的关键</h3>
     * 以前碰撞是"整格开 / 整格关"：一行要么挡死、要么完全消失，
     * 于是门看着在动、实际能不能过去却是<b>一格一格瞬间翻</b>的 —— 动画只是贴在表面的皮。
     * 这里改成按门体的真实位置求交，算出一个<b>部分高度</b>的碰撞盒，
     * 门升到哪儿就只挡到哪儿。
     *
     * @return {@code null} = 这一格已经完全让开了（没有碰撞）
     */
    public static float[] coveredRange(int dy, int height, float slide, boolean retractUp) {
        float c0;
        float c1;
        if (retractUp) {
            // 门往上走：门体占 [slide, slide+height]，先让开下面。
            // 与这一格 [dy, dy+1] 求交 → 下边界被 slide 抬起来，上边界还是格顶
            c0 = Math.max(0.0F, slide - dy);
            c1 = 1.0F;
        } else {
            // 门往下沉：门体占 [slide, slide+height]，先让开上面 → 上边界往下缩
            c0 = 0.0F;
            c1 = Math.min(1.0F, slide + height - dy);
        }
        if (c1 - c0 <= 1e-4F) {
            return null;
        }
        return new float[] { c0, c1 };
    }

    /**
     * ★★★ 这一格此刻<b>本来就该是空气</b>吗 —— "门板消失"到底是门自己在收格，还是别人拆的。
     *
     * <h3>为什么必须有这一问</h3>
     * {@link #applyCells()} 是<b>把门板写成空气</b>来实现开门的（门开到哪儿、方块消失到哪儿，
     * 见那段注释）。而 1.12.2 里"方块变成空气"和"玩家把方块挖了"走的是<b>同一条回调</b>：
     * <pre>
     *   World.setBlockState(pos, AIR, 3)
     *     → Chunk.setBlockState(...)
     *        → if (block1 != block) block1.breakBlock(world, pos, oldState);   // 反编译源码
     *          // ★ 这一句不区分"谁把方块变成空气的"
     *     → BlockBlastDoorPanel.breakBlock(...)
     *        → findController(...) → removeDoor(true)   // "拆一格门板 = 拆整扇门"
     * </pre>
     * 于是"拆一格门板 = 拆整扇门"那条规则被门<b>自己的开门动作</b>触发了：
     * 门一开到让开第一行 → 门板变空气 → 走进"拆门板"分支 → 连控制器一起拆掉，
     * <b>整扇门凭空消失，而且全程静默</b>。
     * <p>
     * 2026-09-25 的日志把这条链钉死了：08:41:02 气闸配对上两扇门 →
     * 08:41:12「放行完成（出口 甲 侧保持开着）」→ <b>同一秒</b>"频道 1 里有 1 扇已建造的门"
     * → 08:41:46"频道 1 里没有已建造的门"。也就是说<b>每开一次门就少一扇门</b>。
     *
     * <h3>★ 判据用"现场重探"，而不是加一个"正在自己收格"的旗标</h3>
     * 旗标要记住在每条异常路径上复原，漏一处就是<b>反向</b>故障（真的被人拆时反而不拆门了），
     * 而且它和 {@link #removing} 那种"递归保护"是两种完全不同的成因，不该合成一个开关。
     * 这一问的答案只取决于门<b>此刻</b>的滑动量 —— 和 {@link #applyCells()} 用的是同一把尺子
     * （同一个 {@link #getSlide()} + 同一个 {@link #coveredRange}），所以"方块消失的那一刻"
     * 和"这一问判成'该消失'的那一刻"永远是同一刻。
     *
     * @return true = 门体此刻没有盖着这一格（是门自己收的格，别当成有人拆门板）；
     *         false = 门认为这一格该有门板（那就是真被拆了）
     */
    public boolean cellIsVacated(BlockPos cell) {
        if (!built) {
            // 没建造的门不认领任何格子：这时出现的门板是孤儿（或者就是刚放下的控制器旁边
            // 还没长出来的格子），拆它不该把控制器一起带走
            return true;
        }
        if (cell == null) {
            return false;
        }
        return coveredRange(cell.getY() - pos.getY(), height, getSlide(), retractUp) == null;
    }

    // ---------------------------------------------------------------- 门板归属缓存

    /**
     * 门板格 → 控制器 的归属表（按维度分桶）。
     * <p>
     * ★ 存在的唯一理由：门板<b>没有</b>方块实体（一扇门最多 49 格都塞 TE 太浪费），
     * 但碰撞查询又必须知道"我属于哪扇门、门滑到哪了"。
     * 每次查询都去 {@code findController} 扫一遍附近方块太贵（碰撞是每个实体每 tick 都在问的），
     * 所以建门时登记一次，之后 O(1) 查表。
     * 查表结果<b>一定会复核</b>（控制器还在吗、还覆盖这一格吗），不对就丢掉 —— 缓存过期不会说谎。
     */
    private static final java.util.Map<Integer, java.util.Map<BlockPos, BlockPos>> CELL_OWNER =
            new java.util.concurrent.ConcurrentHashMap<Integer, java.util.Map<BlockPos, BlockPos>>();

    /** 登记本扇门的所有格 */
    private void registerCells() {
        if (world == null || !built) {
            return;
        }
        java.util.Map<BlockPos, BlockPos> m = ownerMap(world, true);
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                m.put(cellAt(dx, dy), pos);
            }
        }
    }

    /** 注销本扇门的所有格（拆门 / 区块卸载时） */
    private void unregisterCells() {
        if (world == null) {
            return;
        }
        java.util.Map<BlockPos, BlockPos> m = ownerMap(world, false);
        if (m == null) {
            return;
        }
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                m.remove(cellAt(dx, dy));
            }
        }
    }

    private static java.util.Map<BlockPos, BlockPos> ownerMap(World w, boolean create) {
        int dim = w.provider == null ? 0 : w.provider.getDimension();
        java.util.Map<BlockPos, BlockPos> m = CELL_OWNER.get(dim);
        if (m == null && create) {
            m = new java.util.concurrent.ConcurrentHashMap<BlockPos, BlockPos>();
            CELL_OWNER.put(dim, m);
        }
        return m;
    }

    /**
     * 这一格属于哪扇门（控制器位置）；查不到 / 缓存已过期返回 {@code null}。
     */
    public static BlockPos ownerOf(World w, BlockPos cell) {
        if (w == null || cell == null) {
            return null;
        }
        java.util.Map<BlockPos, BlockPos> m = ownerMap(w, false);
        if (m == null) {
            return null;
        }
        BlockPos c = m.get(cell);
        if (c == null) {
            return null;
        }
        TileEntity te = w.getTileEntity(c);
        if (!(te instanceof TileBlastDoor) || !((TileBlastDoor) te).isBuilt()
                || !((TileBlastDoor) te).contains(cell)) {
            // 控制器没了 / 不再覆盖这一格 → 这份缓存已经过期，丢掉
            m.remove(cell);
            return null;
        }
        return c;
    }

    /**
     * 把当前的滑动量落到<b>门板方块的有无</b>上 —— 门开到哪儿，方块消失到哪儿。
     *
     * <h3>★★ 为什么改成"方块真的消失"，而不是只翻 {@code OPEN} 属性</h3>
     * 上一版是"门板一直在，只是把 {@code OPEN} 从 false 改成 true"。那套做法有两个洞，
     * 表现都是玩家报的"门开着也会被夹死"：
     * <ol>
     *   <li><b>窒息判定根本不看 {@code OPEN}</b>。1.12.2 的
     *       {@code EntityLivingBase.isEntityInsideOpaqueBlock()} 问的是
     *       {@code IBlockState.causesSuffocation()}，默认实现等于
     *       {@code material.blocksMovement() && getDefaultState().isFullCube()} ——
     *       问的是 <b>getDefaultState()</b>，门板默认状态恒为满立方体，
     *       于是<b>门开没开都恒定窒息</b>，采样点还是眼睛那一格，跟碰撞盒毫无关系。
     *       详见 {@link BlockBlastDoorPanel#causesSuffocation}。</li>
     *   <li>方块还在 = 仍然参与寻路、遮光、以及各种 {@code isFullCube} 判定；
     *       "开着"只是一个拿来算碰撞的标记，别的系统一概不知道。</li>
     * </ol>
     * 现在：<b>门开到哪儿，方块就消失到哪儿；关到哪儿，方块就长回哪儿。</b>
     * 开到底 = 门框里一格方块都没有（真空气），碰撞 / 窒息 / 寻路 / 遮光一次性归零，
     * 不需要任何"别忘了同步某个判定"的约定。
     *
     * <h3>★ 方块在不在，用的是和碰撞<b>完全同一把尺子</b></h3>
     * {@code present = built && coveredRange(dy, height, slide, retractUp) != null}，
     * 也就是"门体此刻还盖着这一格吗"。于是"方块消失的那一刻"和"碰撞消失的那一刻"
     * 是<b>同一刻</b>，不会出现"视觉上让开了、实际还挡着"或反过来的错位。
     * 行号只是个整数计数器（给联锁和界面用），真正的尺子永远是 {@link #getSlide()}。
     *
     * <p>
     * ★ 只改真正要改的那几格：{@code setBlockState} 会触发邻块更新和网络同步，
     * 7×7 的门每次无脑刷 49 格，逐行走完就是 49×7 = 343 次方块更新。
     */
    private void applyCells() {
        if (world == null || world.isRemote) {
            return;
        }
        float slide = getSlide();
        boolean skippedForEntity = false;
        for (int dy = 0; dy < height; dy++) {
            // ★ 这一格此刻还被门体盖着吗 —— 和 getCollisionBoundingBox 用的是同一个函数、同一个 slide。
            // ★★★ 但必须再与上 built。这一条漏过一次，就是玩家报的
            //   「放下门就自动长出一片方块挡住路，挖掉那一格又会把刚放下的门一起拆了」：
            //   还没建造时 openAmount 恒为 0 → slide = 0 → coveredRange 恒非空 → present 恒真，
            //   于是整片门框在第一次 update()（!initialized 那个分支）就被砌满门板方块；
            //   而门板是**可挖的真方块**，挖它 → BlockBlastDoorPanel.breakBlock
            //   → findController → removeDoor(true) → 连控制器（= 玩家刚放下的那扇门）一起拆掉。
            //   registerCells() 一开始就带着 !built 门禁，这里当时漏了 —— 两处必须同时成立，
            //   否则"没建造"这个状态就只剩半边有效。
            boolean present = built && coveredRange(dy, height, slide, retractUp) != null;
            for (int dx = 0; dx < width; dx++) {
                BlockPos cell = cellAt(dx, dy);
                if (cell.equals(pos) || !world.isBlockLoaded(cell)) {
                    continue;
                }
                IBlockState current = world.getBlockState(cell);
                boolean isPanel = current.getBlock() instanceof BlockBlastDoorPanel;
                if (present) {
                    if (isPanel) {
                        continue;
                    }
                    // 慢慢显现：只往"空着的"格子里长 —— 别把玩家自己放的东西盖掉
                    if (!current.getBlock().isReplaceable(world, cell)) {
                        // ★ 这一格本来就是实心方块：跳过<b>不影响阻挡</b>（它照样挡路），
                        //   只是这扇门不再管它的外观。不用重试，也报一次让玩家知道。
                        if (!cellBlockedReported) {
                            cellBlockedReported = true;
                            RsAudio.log("[" + format(pos) + "] 门板长不回来：" + format(cell)
                                    + " 被 " + current.getBlock().getUnlocalizedName()
                                    + " 占着（那一格保持原样，仍然挡路）");
                        }
                        continue;
                    }
                    // ★ 最后一道兜底：绝不往"有实体"的格子里放方块 ——
                    //   真放进去就是"把生物砌进墙里"，正是这个 bug 的原始形态。
                    //   代价是这一格这次缺一块 → 标记待补，下 tick 再试（见 cellsDirty）。
                    if (cellHasEntity(cell)) {
                        skippedForEntity = true;
                        continue;
                    }
                    world.setBlockState(cell, BlockBlastDoorPanel.stateFor(false, retractUp), 3);
                } else if (isPanel) {
                    // 慢慢消失：直接变空气。门板本来就是 INVISIBLE 渲染类型，
                    // 画面由 BlastDoorRenderer 画 —— 这里只管物理，和动画互不干扰。
                    // ★★★ 注意这一句会走进 BlockBlastDoorPanel.breakBlock()（1.12.2 里
                    //   "方块变成空气"和"玩家把方块挖了"是同一条回调），而那里有一条
                    //   "拆一格门板 = 拆整扇门"的规则。所以门板那边的第一步必须先问
                    //   {@link #cellIsVacated}："这一格是我自己收的吗"——
                    //   否则门一开就把自己整扇拆掉（2026-09-25 现场）。
                    world.setBlockState(cell, net.minecraft.init.Blocks.AIR.getDefaultState(), 3);
                }
            }
        }

        // ---- 待补重试的账
        if (skippedForEntity) {
            cellsDirty = true;
            if (++cellRetryTicks > CELL_RETRY_MAX) {
                // 认输：生物就是赖在门框里不走。这时候门是"关着但缺一块"，
                // 必须留一条带坐标的日志，否则玩家只会看到"门关上还能走过去"。
                cellsDirty = false;
                cellRetryTicks = 0;
                RsAudio.log("[" + format(pos) + "] 关门后有门板补不回来：门框里一直有实体挡着，"
                        + "门会缺这一格（等人走开后再开关一次门即可补上）");
            }
        } else {
            cellsDirty = false;
            cellRetryTicks = 0;
        }
        refreshControllerState();
    }

    /** 这一格（整格立方）里有没有实体的碰撞盒。放方块前的兜底，宁可留个洞也不砌墙。 */
    private boolean cellHasEntity(BlockPos cell) {
        if (world == null) {
            return false;
        }
        // ★ 往里缩一丁点：恰好贴边不算"在里面"，否则站在门根下的生物会让整排门板都补不回来
        double e = 0.001D;
        AxisAlignedBB box = new AxisAlignedBB(
                cell.getX() + e, cell.getY() + e, cell.getZ() + e,
                cell.getX() + 1.0D - e, cell.getY() + 1.0D - e, cell.getZ() + 1.0D - e);
        return !world.getEntitiesWithinAABB(Entity.class, box).isEmpty();
    }

    /**
     * 刷新控制器那一格的方块状态。
     * <p>
     * ★ 控制器在 (0, 0)，也就是门的<b>第 0 行</b>。它和门板不同 ——
     * 它<b>不能</b>变成空气（门的尺寸 / 朝向 / 进度全存在它身上），
     * 所以它只能靠 {@code OPEN} 来让路。
     * <p>
     * ★★ 但"第 0 行开没开"必须和门板用同一把尺子：这里直接问
     * {@code coveredRange(0, ...) == null}（= 门体已经离开控制器这一格了吗），
     * 而不是 {@code rowIsOpen(0, height, progress, retractUp)}（那是行号计数器）。
     * 用行号的话，控制器的放行时刻会和门板差最多一整行 ——
     * 表现就是"门板都消失了、控制器那一格还挡着"（1 格宽的门尤其明显，整扇门就这一格）。
     */
    private void refreshControllerState() {
        if (world == null || world.isRemote) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockBlastDoor)) {
            return;
        }
        boolean rowOpen = coveredRange(0, height, getSlide(), retractUp) == null;
        IBlockState wanted = state
                .withProperty(BlockBlastDoor.OPEN, rowOpen)
                .withProperty(BlockBlastDoor.RETRACT, retractUp);
        if (wanted != state) {
            world.setBlockState(pos, wanted, 3);
        }
    }

    /** 门里（整片门的那一层）有没有实体 */
    private boolean entitiesInside() {
        if (world == null) {
            return false;
        }
        BlockPos far = cellAt(width - 1, height - 1);
        AxisAlignedBB box = new AxisAlignedBB(
                Math.min(pos.getX(), far.getX()), Math.min(pos.getY(), far.getY()),
                Math.min(pos.getZ(), far.getZ()),
                Math.max(pos.getX(), far.getX()) + 1.0D, Math.max(pos.getY(), far.getY()) + 1.0D,
                Math.max(pos.getZ(), far.getZ()) + 1.0D);
        return !world.getEntitiesWithinAABB(Entity.class, box).isEmpty();
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ---------------------------------------------------------------- 门禁 / 联锁

    /**
     * 扫同频道的门，更新"联锁挡不挡我"。
     * <p>
     * 联锁规则：同频道、都开着联锁的另一扇门只要<b>还有一行是开的</b>，我就不能开。
     * 用"有进度"而不是"已开到底"来判断，是为了堵住那个缝 ——
     * 用"已开到底"的话，A 正在关（还剩 3 行）时 B 就能开，出现 A、B 同时半开的瞬间。
     */
    private void refreshInterlock() {
        boolean blocked = false;
        if (access.isInterlocked() && built) {
            int channel = access.getChannel();
            for (TileEntity te : world.loadedTileEntityList) {
                if (!(te instanceof TileBlastDoor)) {
                    continue;
                }
                TileBlastDoor other = (TileBlastDoor) te;
                if (other == this || !other.built) {
                    continue;
                }
                if (!other.access.isInterlocked() || other.access.getChannel() != channel) {
                    continue;
                }
                if (other.access.getProgress() > 0) {
                    blocked = true;
                    break;
                }
            }
        }
        access.setInterlockBlocked(blocked);
    }

    /**
     * 这一扇门刚关上 → 给同频道<b>其他</b>门起一段气闸循环。
     * <p>
     * ★ 刻意<b>不包括自己</b>：把刚关上的那扇门也锁住的话，
     * "按一下开、再按一下关、再按一下开"第三次就会莫名其妙开不了。
     * 真实的闸室也是这样 —— 需要等的是<b>另一侧</b>，不是刚走过的那一侧。
     */
    private void startCycleOnPeers() {
        if (!access.isInterlocked() || access.getCycleTicks() <= 0) {
            return;
        }
        int channel = access.getChannel();
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileBlastDoor)) {
                continue;
            }
            TileBlastDoor other = (TileBlastDoor) te;
            if (other == this || !other.built) {
                continue;
            }
            if (!other.access.isInterlocked() || other.access.getChannel() != channel) {
                continue;
            }
            if (other.access.beginCycle()) {
                other.setStatus("气闸循环中，另一侧刚关上");
                other.markDirty();
                other.syncToClient();
            }
        }
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote || removing) {
            return;
        }

        // 气闸循环倒计时
        boolean cycleJustEnded = access.tick();

        // 联锁扫描（节流）
        if (--scanCooldown <= 0) {
            scanCooldown = SCAN_INTERVAL;
            int before = access.blockReason();
            refreshInterlock();
            if (access.blockReason() != before) {
                syncToClient();
            }
        }

        boolean powered = world.isBlockPowered(pos);
        if (!initialized) {
            initialized = true;
            lastPowered = powered;
            // ★ 重进世界后归属表是空的（它不是存档的一部分），这里补登记一次，
            //   否则门板的连续碰撞会退化回"整格开/整格关"
            registerCells();
            // ★★ 再把"门板该在哪些格子里"整份按存档里的进度重算一遍。
            //   门板的有无现在是<b>世界状态</b>：如果上一次退出时门正在开合中途，
            //   存下来的门板布局就是半截的（而且它和"存了哪个 openAmount"不一定自洽）。
            //   全量重扫几格远比"想办法把每个改动都增量同步过去"可靠 ——
            //   漏一次就是"门看着开着、人走过去却被挡"这种要命的不一致。
            applyCells();
            return;
        }
        if (powered != lastPowered) {
            lastPowered = powered;
            requestOpenInternal(invert ? !powered : powered, null);
        }
        if (pendingClose && built && !entitiesInside()) {
            // 门里清空了，把推迟的关门补上
            pendingClose = false;
            requestOpenInternal(false, null);
        }

        if (built) {
            // ★ 平滑开合：openAmount 每 tick 朝意图推进，门板的有无（= 碰撞）由它推出来 ——
            //   于是"看到的门让到哪"和"能不能走过去"永远是同一个量，不会各说一套。
            boolean wasMoving = isMoving();
            boolean rowsChanged = stepMotion();
            boolean justStopped = wasMoving && !isMoving();
            if (rowsChanged || justStopped || cellsDirty) {
                // ★★ justStopped 这一趟<b>不能省</b>。stepMotion() 的最后一步经常是
                //   "openAmount 被夹到 target、但行号 round() 之后没变" —— 那次它返回 false，
                //   只看返回值就会漏掉最后一行：门看起来全开了，门框里却永远还留着一格方块。
                // ★ cellsDirty 是"上一趟有门板因为格里有实体而没补成"，得接着试到补上为止。
                //   （applyCells 是幂等的，重复调不会多刷方块）
                applyCells();
            }
            if (wasMoving) {
                // ★ 视觉在动就每 tick 同步一次平滑进度（和塔塔鲁斯同一个约定）。
                //   只报行数的话客户端拿不到"半开"这个状态，动画会一顿一顿的。
                markDirty();
                syncToClient();
                if (justStopped) {
                    setStatus(wantOpen ? "已开门" : "已关门");
                }
            }
            // "刚关上"这个边沿 → 给另一侧起气闸循环
            if (wasOpen && access.isFullyClosed()) {
                startCycleOnPeers();
            }
            wasOpen = access.getProgress() > 0;
        } else {
            wasOpen = false;
        }

        if (cycleJustEnded) {
            markDirty();
            syncToClient();
        }
    }

    // ---------------------------------------------------------------- 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.width = clampSize(compound.hasKey("Width") ? compound.getInteger("Width") : DEFAULT_WIDTH);
        this.height = clampSize(compound.hasKey("Height") ? compound.getInteger("Height") : DEFAULT_HEIGHT);
        this.retractUp = compound.getBoolean("Retract");
        this.invert = compound.getBoolean("Invert");
        this.built = compound.getBoolean("Built");
        this.wantOpen = compound.getBoolean("Open");
        this.pendingClose = compound.getBoolean("PendingClose");
        this.status = compound.getString("Status");
        // ★ 平滑进度要存档：退出时正开到一半的门，读回来就该还是半开的，
        //   而不是"门是关的、但 wantOpen 说要开"这种自相矛盾的状态
        this.openAmount = compound.hasKey("OpenAmount")
                ? clamp01(compound.getFloat("OpenAmount")) : (wantOpen ? 1.0F : 0.0F);
        this.alarmLights = !compound.hasKey("AlarmLights") || compound.getBoolean("AlarmLights");
        this.alarmColor = Math.max(0, Math.min(BlockAlarmLight.COLOR_COUNT - 1,
                compound.hasKey("AlarmColor") ? compound.getInteger("AlarmColor") : 0));
        // ★ 读不到就 reset 回默认；长度不对也要整份丢弃（见 AccessCore.loadState）
        access.loadState(compound.hasKey("Access") ? compound.getIntArray("Access") : null);
        // ★★ 从存档里读回来的"气闸封"一律丢弃。
        //   气闸封是一条<b>有时效的租约</b>：控制器每个 tick 都在续，循环结束/中止/拆机器时
        //   由它自己解掉。所以"存档里还亮着的气闸位"按定义就是过期的 ——
        //   要么是存档那一刻正好在循环中（控制器重新加载后 0.5 秒内就会重新封上），
        //   要么是崩溃/异常路径下漏解的孤儿封条。
        //   留着的后果是那扇门<b>永远开不了</b>，而且没有任何界面能看出原因。
        //   ★ 手动位和远程位不受影响 —— 那两把是"人的决定"，本来就该持久化。
        access.unlatch(AccessCore.REASON_AIRLOCK);
        // 存档里的进度可能是"上一次退出时开到一半"，如果门已经拆了就当没开
        if (!built) {
            access.setProgress(0);
            wantOpen = false;
            access.clearCycle();
        }
        this.wasOpen = access.getProgress() > 0;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Width", width);
        compound.setInteger("Height", height);
        compound.setBoolean("Retract", retractUp);
        compound.setBoolean("Invert", invert);
        compound.setBoolean("Built", built);
        compound.setBoolean("Open", wantOpen);
        compound.setBoolean("PendingClose", pendingClose);
        compound.setString("Status", status == null ? "" : status);
        compound.setFloat("OpenAmount", openAmount);
        compound.setBoolean("AlarmLights", alarmLights);
        compound.setInteger("AlarmColor", alarmColor);
        compound.setIntArray("Access", access.saveState());
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Width", width);
        tag.setInteger("Height", height);
        tag.setBoolean("Retract", retractUp);
        tag.setBoolean("Invert", invert);
        tag.setBoolean("Built", built);
        tag.setBoolean("Open", wantOpen);
        // ★ 平滑进度必须进同步包：客户端的动画全靠它，只报行数会变成一格一格地跳
        tag.setFloat("OpenAmount", openAmount);
        tag.setString("Status", status == null ? "" : status);
        tag.setBoolean("AlarmLights", alarmLights);
        tag.setInteger("AlarmColor", alarmColor);
        tag.setIntArray("Access", access.saveState());
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.width = clampSize(tag.hasKey("Width") ? tag.getInteger("Width") : DEFAULT_WIDTH);
        this.height = clampSize(tag.hasKey("Height") ? tag.getInteger("Height") : DEFAULT_HEIGHT);
        this.retractUp = tag.getBoolean("Retract");
        this.invert = tag.getBoolean("Invert");
        this.built = tag.getBoolean("Built");
        this.wantOpen = tag.getBoolean("Open");
        this.openAmount = tag.hasKey("OpenAmount")
                ? clamp01(tag.getFloat("OpenAmount")) : (wantOpen ? 1.0F : 0.0F);
        // ★ 客户端这份"动画起点"：第一次拿到同步包时直接对齐，避免从 0 缓动过去
        //   造成"刚看见这扇门，它正好自己滑了一下"
        if (!animSeeded) {
            animSeeded = true;
            this.clientAnim = this.openAmount;
        }
        this.status = tag.getString("Status");
        this.alarmLights = !tag.hasKey("AlarmLights") || tag.getBoolean("AlarmLights");
        this.alarmColor = Math.max(0, Math.min(BlockAlarmLight.COLOR_COUNT - 1,
                tag.hasKey("AlarmColor") ? tag.getInteger("AlarmColor") : 0));
        access.loadState(tag.hasKey("Access") ? tag.getIntArray("Access") : null);
        // ★ 联锁状态是"邻居当前状态"的投影，只有在服务端扫过之后才算数。
        //   客户端拿到的那份 0/1 是服务端当时的快照，不该被当成权威，重扫时会自己纠正。
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }

    /**
     * ★ 归属表必须跟着门一起消失，否则会越攒越多（而且过期的条目会让别的方块
     * 误以为自己属于某扇门）。方块实体被移除时 {@code invalidate} 必定会被调用
     * （拆机器 / 方块被替换 / 区块卸载都走这里），所以挂在这里最稳。
     */
    @Override
    public void invalidate() {
        unregisterCells();
        super.invalidate();
    }

    @Override
    public void onChunkUnload() {
        unregisterCells();
        super.onChunkUnload();
    }

    /**
     * ★ TESR 的可见范围必须覆盖<b>整扇门</b>，而且要把门体滑出去的那一段也算进去。
     * <p>
     * 默认只有控制器自己那一格，7×7 的门在控制器不在视锥内时会整扇突然消失；
     * 门体升到门楣里之后更是完全在默认盒子外面 —— 不扩的话"开门开到一半门不见了"。
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        BlockPos far = pos.offset(getRight(), w - 1).up(h - 1);
        int minX = Math.min(pos.getX(), far.getX());
        int maxX = Math.max(pos.getX(), far.getX()) + 1;
        int minZ = Math.min(pos.getZ(), far.getZ());
        int maxZ = Math.max(pos.getZ(), far.getZ()) + 1;
        // 向下收时门体沉到地板下（最低 -h），向上收时升到门楣里（最高 +h 再叠一层 h）
        int minY = pos.getY() - h - 1;
        int maxY = pos.getY() + 2 * h + 1;
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}

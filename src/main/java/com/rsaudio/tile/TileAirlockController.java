package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.access.AccessCore;
import com.rsaudio.access.AirlockCore;
import com.rsaudio.access.TerminalCore;
import com.rsaudio.block.BlockAirlockController;
import com.rsaudio.util.MachineAudio;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

/**
 * 气闸控制器的方块实体。所有规则都在 {@link AirlockCore} 里，这里只负责接线：
 * <b>配对两扇门 → 报状态 → 推内核 → 按事件动门</b>。
 *
 * <h2>★ 两扇门是怎么配上的</h2>
 * 控制器不做"绑定"这种需要持久化的关系，而是每 {@link #SCAN_INTERVAL} 刻<b>重新找一遍</b>：
 * 取 {@link #SEARCH_RADIUS} 格内、同频道、已建造的门里，在控制器<b>两侧各取最近的一扇</b>
 * （只有一侧有门时退回"最近的两扇"）。这样做的好处是
 * 拆了重盖一扇门、改个频道，控制器自己就跟上了，不会留下指向已消失方块的死引用。
 * <p>
 * ★ 配对结果<b>每次变化都会往游戏日志写一行</b>（见 {@link #notePairing}）——
 * 这块功能以前完全不打日志，于是"门联动不了"在日志里查不到任何线索，
 * 只能靠读存档取证。现在开一次世界就能在日志里看到它到底配上了没有。
 * <p>
 * 两扇门到"甲 / 乙"的编号按<b>位置的全序</b>（先 x 再 y 再 z）定，不看朝向。
 * ★ 这一条是必须的：内核的 {@code closed[]} / {@code latched[]} 是按编号索引的，
 * 如果编号每次扫描都可能翻转，"入口"和"出口"就会在两刻之间互换 ——
 * 于是人从甲侧进，结果甲侧的门开了（人又回到自己来的那边）。
 * <p>
 * 而"人从哪一侧进来"不看编号，看<b>哪一扇门现在是开着的</b>（{@link #openSide()}）：
 * 人只能从开着的那一侧走进舱室，所以它就是入口，净化完开另一侧。
 * 两侧都关着（初始状态）才退回"离按按钮的人近的那一侧"。
 * 所以控制器装反了也不会把进出弄颠倒。
 *
 * <h2>★ 封门用的是"气闸位"，不是玩家或 FAAS 的锁</h2>
 * 循环期间两侧都要封死，但封条必须能<b>自己消失</b>（十几秒后），
 * 而且不能顺手解掉别人的锁。所以它走 {@link AccessCore#latchAirlock}
 * 那条独立的路 —— 见 {@link AccessCore#REASON_AIRLOCK} 的注释。
 *
 * <h2>★ 什么情况下会把封条放掉</h2>
 * <ul>
 *   <li>循环正常走完（目标侧确实开了）→ 放；</li>
 *   <li>超时中止 → 放（门保持关闭）；</li>
 *   <li>人按了急停 → 放；</li>
 *   <li>{@link #invalidate()}：方块实体被移除（拆机器 / 区块卸载）→ 放。</li>
 * </ul>
 * ★ 最后一条是<b>有意做成 fail-open</b> 的。代价是区块卸载时封条也会掉，
 * 但封条只挡"开门"、门本身还是关着的，所以不会留下缺口；
 * 而收益是"绝不可能留下一扇永远开不了、又没有任何界面能看出原因的门"。
 * 万一连 invalidate 都没赶上（崩溃），还有最后一道网：门在读档时会丢弃气闸位
 * （见 {@code TileBlastDoor.readFromNBT}）—— 持久化下来的气闸封按定义就是过期的。
 */
public class TileAirlockController extends TileEntity implements ITickable {

    /**
     * 找门的搜索半径（格）。量的是<b>到整扇门的包围盒</b>的距离（见
     * {@link TileBlastDoor#distanceSqToNearestCell}），不是到门那一格角点的距离。
     *
     * <h3>★★ 为什么从 8 提到 16</h3>
     * 参考实现里这个值是 8，但那里的语义和这里差一整条街：
     * <ul>
     *   <li>参考实现量的是"到门方块"的距离，而我们一开始量的是
     *       {@code door.getPos()} —— 那是门的<b>左下角</b>。3×3 的门，
     *       从角点量比从最近边量能多出 3 格多，7×7 更多；</li>
     *   <li>玩家真实造出来的气闸比"两扇门贴着控制器"大得多。实测存档里：
     *       控制器在 (287,4,1349)，两扇门在 (279,4,1346) 和 (291,4,1348)。
     *       近的那扇门角点距离 8.54 格 &gt; 8，<b>被直接排除</b> ——
     *       控制器于是"只看得到一扇门"，永远报"配不上对"，
     *       界面上还只写"分不出你从哪一侧进来"（见 {@link #activate}）。</li>
     * </ul>
     * 所以两处一起改：按<b>包围盒</b>量（同一扇门立刻变成 5.52 格），
     * 半径放到 16 给更大的气闸留余量。
     * <p>
     * 半径放大必然带来"把隔壁气闸的门也吸进来"的风险，所以配对规则同时改成
     * <b>优先在控制器两侧各取一扇</b>（见 {@link #rebindDoors}）——
     * 那正是气闸的几何定义，比"取最近的两扇"稳得多。
     */
    public static final int SEARCH_RADIUS = 16;

    public static final int SCAN_INTERVAL = 10;
    /** 每多少刻往客户端同步一次（循环中）。1 秒一次，够界面显示倒计时了 */
    public static final int SYNC_INTERVAL = 20;
    /** 播报的半径（格） */
    public static final int ANNOUNCE_RANGE = 24;

    /**
     * ★★ 定"入口侧"的唯一规则：<b>哪一扇门现在是开着的，那一侧就是入口</b>。
     * <p>
     * 用户明确要的就是这一条（"按下按钮时检测那边门开了，就让另一扇门净化后打开"）：
     * 人从开着的那一侧走进舱室 → 按按钮 → 那一侧关上并封死 → 净化 → <b>另一侧</b>打开。
     * 两扇门都关着时才退回"离谁近"（{@link #nearestSide}）；
     * 连玩家都没有（面板按钮 / 指令）时退回甲侧 —— 那也是唯一还能确定的顺序。
     */

    /** 全部规则都在这里 */
    private final AirlockCore core = new AirlockCore();

    private int channel = TerminalCore.CHANNEL_DEFAULT;
    private boolean announce = true;

    /** 配对结果（只有服务端扫出来的才算数；不持久化，掉线重扫即可） */
    private final BlockPos[] sides = new BlockPos[AirlockCore.SIDES];
    /** 给界面看的配对文字，形如 {@code 3,64,10 → 3,64,14} */
    private String sidesText = "";
    /**
     * 配不上对时"为什么配不上"的一句话（界面上直接显示）。
     * <p>
     * ★ 存在的理由：配不上对是这块功能<b>最常见的故障</b>，而且成因全在别处
     * （门的频道没设、门没建造、门离得太远）。没有这句话，玩家得到的只有一句
     * "分不出你从哪一侧进来"，完全指不到该改哪里 —— 实测就是这么卡住的。
     */
    private String pairHint = "";

    /**
     * 上一次写进日志的配对结论。用来<b>只在结论变化时</b>打日志 ——
     * {@link #rebindDoors} 每 {@link #SCAN_INTERVAL} 刻跑一次，不去重的话
     * 一秒两行，日志会被刷爆、真正有用的那行反而被埋掉。
     */
    private String lastPairLog = "";

    private boolean initialized = false;
    private boolean lastActive = false;
    private int scanCooldown = 0;
    private int syncCooldown = 0;
    private String status = "";

    /**
     * 上一刻内核处在哪个阶段（{@code STATE_*}）。
     * <p>
     * ★ 存在的唯一理由：{@link AirlockCore#abort()} 会把 state 打回 IDLE，
     * 所以等 {@code EVENT_ABORT} 送到 {@link #handleEvent} 时，
     * {@code core.getState()} 已经<b>不再是</b>出错的那一段了。
     * 不记住它的话，所有超时都只能报一句"超时或被打断"——
     * 玩家看到的就是"莫名其妙急停了"，而真正有用的信息（是门关不上、还是门打不开）
     * 已经被抹掉了。这跟 {@link #pairHint} 是同一个思路：故障必须自己说清成因。
     */
    private int phaseAtTick = AirlockCore.STATE_IDLE;

    /**
     * 上一次放行打开的是哪一侧（{@code SIDE_NONE} = 没有），界面显示用。
     * <p>
     * ★ 放行之后那一侧<b>保持开着</b>（人还没走出去）。这同时给了下一次一个明确的
     * "入口侧"：下次按按钮时它就是开着的那一侧 —— 正是 {@link #openSide()} 要的。
     */
    private int openExitSide = AirlockCore.SIDE_NONE;

    // ------------------------------------------------------------------ 读取（界面用）

    public AirlockCore getCore() {
        return core;
    }

    public int getChannel() {
        return channel;
    }

    public boolean isAnnounce() {
        return announce;
    }

    public int getDeconTicks() {
        return core.getDeconTicks();
    }

    public int getLockTimeout() {
        return core.getLockTimeout();
    }

    public String getSidesText() {
        return sidesText == null ? "" : sidesText;
    }

    public String getStatus() {
        return status == null ? "" : status;
    }

    /** 两扇门都配上了吗 */
    public boolean isPaired() {
        return sides[0] != null && sides[1] != null;
    }

    /** 门数（0..2）。内核据此拒绝"配不齐就开闸" */
    public int getDoorCount() {
        return core.getDoorCount();
    }

    /** 某一扇门的方块实体；没配上或区块没加载时返回 null */
    public TileBlastDoor door(int side) {
        if (!AirlockCore.isValidSide(side) || world == null) {
            return null;
        }
        BlockPos p = sides[side];
        if (p == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(p);
        return te instanceof TileBlastDoor ? (TileBlastDoor) te : null;
    }

    // ------------------------------------------------------------------ 配置

    /**
     * 服务端权威入口：整套参数一起覆盖（和防核门 / 门禁终端一样的约定）。
     * <p>
     * ★ 改频道会清掉配对结果 —— 旧那一对门已经不属于这个气闸了，
     * 留着的话下一步就会去动别人家的门。
     */
    public void applySettings(int newChannel, int deconTicks, int lockTimeout, boolean newAnnounce) {
        int ch = TerminalCore.clampChannel(newChannel);
        boolean channelChanged = ch != this.channel;
        this.channel = ch;
        this.announce = newAnnounce;
        core.setDeconTicks(deconTicks);
        core.setLockTimeout(lockTimeout);
        if (channelChanged) {
            sides[0] = null;
            sides[1] = null;
            sidesText = "";
            pairHint = "";
            // 换了频道 → 旧的那两扇门身上的气闸封得放掉，不然它们会一直开不了
            releaseAll();
            markDirty();
        }
        markDirty();
        syncToClient();
    }

    // ------------------------------------------------------------------ 激活

    /**
     * 按了按钮（空手右键控制器 / 界面「启动」/ 远程面板 / 指令 —— 都走这一条路）。
     *
     * <h3>★ 入口侧 = 当前开着的那一侧</h3>
     * 人只能从开着的那一侧走进舱室，所以那一侧就是入口；净化完开的是<b>另一侧</b>。
     * 两侧都关着（没人进出过的初始状态）才退回"离按按钮的人近的那一侧"。
     *
     * @param player 触发的人；可以为 null（面板 / 指令），那时只能用"开着那一侧"来定方向
     */
    public void activate(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return;
        }
        // ★ 按按钮是"任意时刻"发生的，而门闩是上一 tick 报的 —— 先重报一遍。
        //   少了这一步，玩家在一扇刚被锁上的门旁边按按钮还能把气闸跑起来。
        //   （AirlockCore 的类注释里把这条列成了硬约定）
        report();

        // ★★ "配不上对"必须在这里拦下，而且必须报<b>配对</b>那一条。
        //   交给下面 checkStart 的后果是：配不上对时 door(s) 返回 null，
        //   openSide() 和 nearestSide() 都只能给出 SIDE_NONE，于是内核报的是
        //   REFUSE_SIDE（"分不出你从哪一侧进来"）—— 而 AirlockCore 的检查顺序是
        //   "侧号优先于配对"（那条顺序是钉住的接口契约，不能改）。
        //   症状就是：真实成因是"门没配对"，玩家收到的却是"分不出方向"，
        //   完全指不到该去改哪里。实测存档里卡住的就是这一条。
        if (core.getDoorCount() < AirlockCore.SIDES) {
            String text = setupRefusalText();
            setStatus(text);
            notify(player, text);
            RsAudio.warn("[气闸] %s 启动被拒（没配对）：%s", format(pos), text);
            return;
        }

        int from = openSide();
        if (!AirlockCore.isValidSide(from) && player != null) {
            from = nearestSide(player);
        }
        int refuse = core.checkStart(from);
        if (refuse != AirlockCore.REFUSE_NONE) {
            String text = refuseText(refuse);
            setStatus(text);
            notify(player, text);
            // ★ 把两个侧门的实际开合一起写出来：PLAYER 为空时 from 只能是"开着的那一侧"，
            //   两侧都关着就会变成 REFUSE_SIDE。不带这个现场，日志里只看到
            //   "分不出你从哪一侧进来"，根本不知道当时门是什么状态
            RsAudio.warn("[气闸] %s 启动被拒（%s）：甲侧开%s 乙侧开%s",
                    format(pos), text,
                    sideOpenDesc(AirlockCore.SIDE_A), sideOpenDesc(AirlockCore.SIDE_B));
            return;
        }
        startCycle(from);
    }

    /** 某一侧门"开了几行 / 门没了"的一小段描述，日志用 */
    private String sideOpenDesc(int side) {
        TileBlastDoor d = door(side);
        if (d == null) {
            return "?";
        }
        return String.valueOf(d.getProgress());
    }

    /**
     * 配不上对时给玩家看的那句话：通用说明 + <b>具体缺什么</b>（{@link #pairHint}）。
     * <p>
     * 只写"需要两扇同频道的门"是没用的 —— 玩家会以为自己已经有那两扇门了。
     * 带上"频道 N 里有几扇、16 格内找到几扇"才指得到地方。
     */
    public String setupRefusalText() {
        String hint = getPairHint();
        String base = refuseText(AirlockCore.REFUSE_SETUP);
        return hint.isEmpty() ? base : base + "：" + hint;
    }

    private void startCycle(int from) {
        if (!core.requestTransfer(from)) {
            return;
        }
        sealBoth();
        setStatus("气闸循环开始");
        setActive(true);
        syncToClient();
        // ★ 循环是"按一下就自动走完"的黑盒，日志里必须留下起止和方向，
        //   否则玩家报"门乱开"时我们对不上任何时间点
        RsAudio.log("[气闸] %s 开始循环：入口 %s 侧 → 出口 %s 侧，净化 %d 刻",
                format(pos), sideName(from), sideName(core.getDestSide()),
                core.getDeconTicks());
    }

    /**
     * 离玩家近的那一侧（人就是从这一侧进来的，净化完要开另一侧）。
     * <p>
     * ★ 量的是到<b>门中心</b>（{@code anchor()}）的距离，不是到控制器那一格
     * （控制器是门的<b>左下角</b>）：7 格宽的门，左下角和中心差了 3 格，
     * 拿左下角去比会把"站在宽门右半边的人"判成离对面更近 —— 方向就反了。
     */
    public int nearestSide(EntityPlayer player) {
        TileBlastDoor a = door(AirlockCore.SIDE_A);
        TileBlastDoor b = door(AirlockCore.SIDE_B);
        if (a == null || b == null) {
            return AirlockCore.SIDE_NONE;
        }
        double da = dist(player, a.anchor());
        double db = dist(player, b.anchor());
        return da <= db ? AirlockCore.SIDE_A : AirlockCore.SIDE_B;
    }

    private static double dist(EntityPlayer player, BlockPos pos) {
        return player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /**
     * ★ 入口侧 = <b>现在开着的那一侧</b>。
     * <p>
     * 人只能从开着的那一侧走进舱室，所以它就是入口；净化完要开的是另一侧。
     * 用"实际开了几行"（{@code getProgress()}）而不是意图：门还在升起途中时
     * 意图已经是"开"了，但舱室其实还没通 —— 用意图会把方向判反。
     * <p>
     * 两侧都关着时返回 {@link AirlockCore#SIDE_NONE}，由调用方退回"离谁近"。
     */
    private int openSide() {
        for (int s = 0; s < AirlockCore.SIDES; s++) {
            TileBlastDoor d = door(s);
            if (d != null && d.getProgress() > 0) {
                return s;
            }
        }
        return AirlockCore.SIDE_NONE;
    }

    public static String refuseText(int refuse) {
        switch (refuse) {
            case AirlockCore.REFUSE_BUSY:
                return "气闸已经在跑一轮了，等它走完或按急停";
            case AirlockCore.REFUSE_LOCKED:
                return "两侧里有一侧正被封锁，气闸起不来";
            case AirlockCore.REFUSE_SETUP:
                return "还没配对到两扇门：需要 " + SEARCH_RADIUS + " 格内两扇同频道、已建造的门";
            case AirlockCore.REFUSE_SIDE:
                return "分不出你从哪一侧进来";
            default:
                return "";
        }
    }

    /**
     * 中止的<b>具体成因</b>（写进状态栏 + 播报）。
     * <p>
     * ★ 为什么值得单独一个方法：中止的唯一成因是"某一段物理动作超时"，
     * 而两段的含义完全不同 ——
     * <ul>
     *   <li><b>关门段超时</b>：有门没关上。最常见的其实是<b>有人站在门里</b>
     *       （门会 {@code pendingClose} 等它走开），其次是门被拆了 / 区块没加载。</li>
     *   <li><b>开门段超时</b>：目标侧被封条以外的锁挡住，或者门本身坏了。</li>
     * </ul>
     * 只报"超时"的话这两种情况对玩家是同一句话，只能靠猜；
     * 报出阶段再附上"哪一侧还开着"，就能直接照着改。
     */
    private String abortDetail() {
        switch (phaseAtTick) {
            case AirlockCore.STATE_LOCKING:
                return "关门阶段 " + (core.getLockTimeout() / 20) + " 秒内没能把两侧都关上"
                        + openSideDetail();
            case AirlockCore.STATE_UNLOCKING:
                return "开门阶段 " + (core.getLockTimeout() / 20) + " 秒内目标侧没能打开";
            case AirlockCore.STATE_DECON:
                return "净化结束时方向丢失（这个不该发生，请把这句话发出来）";
            default:
                return "被打断";
        }
    }

    /**
     * "哪一侧还开着" —— 拼成 {@code （甲侧还开着、乙侧还开着）}，都关着时返回空串。
     * <p>
     * ★ 读的是内核里那份"上一次报上来的"状态，不是现去查门：中止发生时
     * {@code abort()} 只清 state/timer，<b>不动</b> closed[]，所以这份读数
     * 正是超时那一刻两侧的实际开合 —— 那才是要报给玩家的东西。
     */
    private String openSideDetail() {
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < AirlockCore.SIDES; s++) {
            if (!core.isClosed(s)) {
                sb.append(sb.length() == 0 ? "（" : "、").append(sideName(s)).append("侧还开着");
            }
        }
        return sb.length() == 0 ? "" : sb.append("）").toString();
    }

    /** 急停（界面上那颗按钮 / 指令）。两侧保持关闭，只是把循环作废 */
    public boolean cancel() {
        // ★ 先记下此刻的阶段：core.cancel() 会把它打回 IDLE。
        //   急停是"人按的"，不是超时 —— 报出被作废的是哪一段，玩家才好确认
        //   自己按的到底是不是他以为的那一轮
        int phase = core.getState();
        if (!core.cancel()) {
            return false;
        }
        // ★ 先放封条再关门：顺序反了的话，关门这一步会被"气闸封"当成被封锁挡回去
        releaseAll();
        closeBoth();
        openExitSide = AirlockCore.SIDE_NONE;
        setStatus("气闸循环已中止（" + phaseName(phase) + "阶段），两侧保持关闭");
        setActive(false);
        broadcast("气闸循环已中止");
        RsAudio.warn("[气闸] %s 手动急停（%s阶段），两侧保持关闭",
                format(pos), phaseName(phase));
        markDirty();
        syncToClient();
        return true;
    }

    /** 阶段的中文名（状态栏显示用）。{@link AirlockCore#stateName} 给的是英文枚举名 */
    private static String phaseName(int state) {
        switch (state) {
            case AirlockCore.STATE_LOCKING:
                return "关门";
            case AirlockCore.STATE_DECON:
                return "净化";
            case AirlockCore.STATE_UNLOCKING:
                return "开门";
            default:
                return "待机";
        }
    }

    // ------------------------------------------------------------------ 封 / 放

    /**
     * 两侧关死并封上。
     * <p>
     * ★ 顺序是"先封再关"。反过来（先关后封）中间有一个缝：门已经关上了，
     * 但这一刻玩家还能手动按开 —— 而净化还没开始，所以谁也说不清这算不算已经进舱。
     */
    private void sealBoth() {
        for (int s = 0; s < AirlockCore.SIDES; s++) {
            TileBlastDoor d = door(s);
            if (d == null) {
                continue;
            }
            d.setAirlockLock(true, null);
            d.requestOpen(false, null);
        }
    }

    /** 把两扇门身上的气闸封放掉（只动气闸位，玩家和 FAAS 的锁都不碰） */
    public void releaseAll() {
        for (int s = 0; s < AirlockCore.SIDES; s++) {
            TileBlastDoor d = door(s);
            if (d != null) {
                d.setAirlockLock(false, null);
            }
        }
    }

    /**
     * 两侧都关回去。
     * <p>
     * ★ 中止的定义是<b>保持密闭</b>（见 {@link AirlockCore} 的类注释：
     * "一个走不下去的循环最安全的收场是保持密闭，而不是猜一个方向打开"）。
     * 上一版 {@code EVENT_ABORT} / 急停只放了封条、没有关门，
     * 于是正在升起的那扇门会继续走完 —— 玩家看到的就是"急停之后门反而自己开了"。
     */
    private void closeBoth() {
        for (int s = 0; s < AirlockCore.SIDES; s++) {
            TileBlastDoor d = door(s);
            if (d != null) {
                d.requestOpen(false, null);
            }
        }
    }

    // ------------------------------------------------------------------ 配对 / 报状态

    /**
     * 重新找一遍两扇门。
     * <p>
     * 候选条件：同频道、已建造、<b>整扇门的包围盒</b>在 {@link #SEARCH_RADIUS} 格内。
     *
     * <h3>★★ 配对规则：优先"控制器两侧各一扇"，而不是"最近的两扇"</h3>
     * 气闸的<b>几何定义</b>就是"控制器所在舱室的两边各有一扇门"。而"取最近的两扇"
     * 在半径放大之后并不可靠：同频道挨着造两个气闸时，最近的两扇很可能<b>全在同一侧</b>
     * （隔壁气闸的两扇门都比本气闸另一侧那扇近），控制器就会去开别人家的门。
     * <p>
     * 所以分三步：
     * <ol>
     *   <li>把候选按"在控制器的哪一侧"分组（见 {@link #sideOf}）；</li>
     *   <li>两侧各取<b>最近的一扇</b>，能凑齐就用这一对；</li>
     *   <li>只有一侧有门（控制器被装在舱室外面 / 一端）时，退回"最近的两扇" ——
     *       不然这种情况下气闸就彻底不能用了。</li>
     * </ol>
     *
     * <h3>★ 这里<b>不再</b>要求"开了联锁"</h3>
     * 上一版把 {@code isInterlock()} 当成必要条件，结果玩家只要忘了在两扇门上
     * 各勾一次「联锁」，控制器就永远报"还没配对到两扇门"，而且界面上看不出
     * 缺的是这一项 —— 这正是"气闸认不出我的门"最常见的成因。
     * <p>
     * 不变量本身并没有丢：循环期间两侧都由<b>气闸位</b>封死（{@link #sealBoth}），
     * 净化时不可能被人按开。联锁额外提供的只是"待机时也不会两侧同时开"，
     * 那是玩家的取舍，不该变成"忘了勾就整个用不了"。
     */
    private void rebindDoors() {
        BlockPos old0 = sides[0];
        BlockPos old1 = sides[1];
        sides[0] = null;
        sides[1] = null;
        if (world == null) {
            sidesText = "";
            pairHint = "";
            return;
        }
        if (channel == AccessCore.CHANNEL_NONE) {
            sidesText = "";
            pairHint = "控制器的频道是 0（不参与门禁）—— 先在界面里给它选一个 1..16 的频道，"
                    + "再把两扇门的频道设成同一个";
            notePairing("配不上对 —— " + pairHint);
            return;
        }

        // ---- ① 收集候选，同时统计"整个维度里同频道已建造的门"有几扇（诊断用）
        double limit = (double) SEARCH_RADIUS * SEARCH_RADIUS;
        java.util.List<TileBlastDoor> near = new java.util.ArrayList<TileBlastDoor>();
        java.util.List<Double> nearDist = new java.util.ArrayList<Double>();
        int sameChannelWorld = 0;
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileBlastDoor)) {
                continue;
            }
            TileBlastDoor d = (TileBlastDoor) te;
            if (!d.isBuilt()) {
                continue;
            }
            if (d.getChannel() != channel) {
                continue;
            }
            sameChannelWorld++;
            // ★ 量"到门的包围盒"的距离，不是到门那一格角点的距离 —— 见 SEARCH_RADIUS 的注释
            double dist = d.distanceSqToNearestCell(pos);
            if (dist > limit) {
                continue;
            }
            near.add(d);
            nearDist.add(dist);
        }
        if (near.size() < 2) {
            sidesText = "";
            pairHint = buildPairHint(near.size(), sameChannelWorld);
            notePairing("配不上对 —— " + pairHint);
            return;
        }

        // ---- ② 按"在控制器的哪一侧"分组，两侧各取最近的一扇
        int idxNeg = -1;
        int idxPos = -1;
        double dNeg = Double.MAX_VALUE;
        double dPos = Double.MAX_VALUE;
        for (int i = 0; i < near.size(); i++) {
            int side = sideOf(near.get(i));
            double dist = nearDist.get(i);
            if (side < 0 && dist < dNeg) {
                dNeg = dist;
                idxNeg = i;
            } else if (side > 0 && dist < dPos) {
                dPos = dist;
                idxPos = i;
            }
        }
        TileBlastDoor first;
        TileBlastDoor second;
        if (idxNeg >= 0 && idxPos >= 0) {
            first = near.get(idxNeg);
            second = near.get(idxPos);
        } else {
            // ---- ③ 只有一侧有门：退回"最近的两扇"，否则装在一端的控制器就废了
            TileBlastDoor b1 = null;
            TileBlastDoor b2 = null;
            double e1 = Double.MAX_VALUE;
            double e2 = Double.MAX_VALUE;
            for (int i = 0; i < near.size(); i++) {
                TileBlastDoor d = near.get(i);
                double dist = nearDist.get(i);
                if (dist < e1) {
                    e2 = e1;
                    b2 = b1;
                    e1 = dist;
                    b1 = d;
                } else if (dist < e2) {
                    e2 = dist;
                    b2 = d;
                }
            }
            first = b1;
            second = b2;
        }
        if (first == null || second == null || first == second) {
            sidesText = "";
            pairHint = buildPairHint(near.size(), sameChannelWorld);
            notePairing("配不上对 —— " + pairHint);
            return;
        }

        // ★ 编号按位置全序，保证两次扫描给出同一套编号（否则甲乙会互换）
        TileBlastDoor low = first;
        TileBlastDoor high = second;
        if (comparePos(low.getPos(), high.getPos()) > 0) {
            low = second;
            high = first;
        }
        sides[0] = low.getPos();
        sides[1] = high.getPos();
        sidesText = format(low.getPos()) + " → " + format(high.getPos())
                + "（" + fmtDist(low) + " / " + fmtDist(high) + " 格）";
        pairHint = "";
        notePairing("已配对 甲=" + format(low.getPos()) + " 乙=" + format(high.getPos())
                + "（距控制器 " + fmtDist(low) + " / " + fmtDist(high) + " 格）");
        // ★ 配对变了而循环正在跑 —— 说明有人在半路动了门（改了频道、拆了一扇），
        //   这一轮已经没有意义了。注意要放掉的是<b>旧</b>的那两扇门身上的封条，
        //   不是刚配上的这两扇（对新的门释放等于什么都没做，旧门会一直开不了）
        if (core.isRunning() && (!same(old0, sides[0]) || !same(old1, sides[1]))) {
            core.cancel();
            releaseAt(old0);
            releaseAt(old1);
            setStatus("配对的门变了，气闸循环作废");
            setActive(false);
        }
    }

    /**
     * ★ 配对结论写进游戏日志，<b>只在结论变化时</b>写。
     * <p>
     * 存在的理由：这块功能以前<b>一行日志都不打</b>。于是"气闸认不出我的门"
     * 在 {@code latest.log} 里查不到任何线索（日志干净得像是没问题），
     * 只能靠读存档 NBT 去取证 —— 而这正是它反复卡住的原因。
     * <p>
     * 带上控制器自己的坐标，是因为一个世界里可以有好几个气闸，
     * 不带坐标的日志等于没说。
     * <p>
     * ★ 去重（{@link #lastPairLog}）是必须的：这个方法每 {@link #SCAN_INTERVAL}
     * 刻被调一次，不去重就是每秒两行，会把自己想看的结论刷没。
     */
    private void notePairing(String conclusion) {
        if (world == null || world.isRemote || conclusion == null) {
            return;
        }
        String line = "气闸 " + format(pos) + " 频道 " + channel + "：" + conclusion;
        if (line.equals(lastPairLog)) {
            return;
        }
        lastPairLog = line;
        if (pairHint == null || pairHint.isEmpty()) {
            RsAudio.log("[气闸] %s", line);
        } else {
            // 配不上对是"需要玩家去改点什么"的状态，用 warn 更显眼
            RsAudio.warn("[气闸] %s", line);
        }
    }

    /** 按坐标放掉某一扇门上的气闸封（配对关系已经变了的时候用） */
    private void releaseAt(BlockPos p) {
        if (p == null || world == null) {
            return;
        }
        TileEntity te = world.getTileEntity(p);
        if (te instanceof TileBlastDoor) {
            ((TileBlastDoor) te).setAirlockLock(false, null);
        }
    }

    private static boolean same(BlockPos a, BlockPos b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int comparePos(BlockPos a, BlockPos b) {
        if (a.getX() != b.getX()) {
            return a.getX() < b.getX() ? -1 : 1;
        }
        if (a.getY() != b.getY()) {
            return a.getY() < b.getY() ? -1 : 1;
        }
        if (a.getZ() != b.getZ()) {
            return a.getZ() < b.getZ() ? -1 : 1;
        }
        return 0;
    }

    private static String format(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    /**
     * 门在控制器的哪一侧：{@code -1} / {@code +1}。
     * <p>
     * 取门中心相对控制器中心的偏移里<b>较大的那个水平轴</b>来定侧，忽略 Y ——
     * 气闸一定是水平布置的，而门可以比控制器高好几格，把 Y 算进去会把侧向判歪。
     * 水平中心完全重合时（理论上不该发生）算 {@code +1}，至少不会自相矛盾。
     */
    private int sideOf(TileBlastDoor door) {
        BlockPos a = door.anchor();
        double dx = (a.getX() + 0.5D) - (pos.getX() + 0.5D);
        double dz = (a.getZ() + 0.5D) - (pos.getZ() + 0.5D);
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx < 0.0D ? -1 : 1;
        }
        return dz < 0.0D ? -1 : 1;
    }

    /** 门到控制器有几格（界面显示用，一位小数） */
    private String fmtDist(TileBlastDoor door) {
        return String.format(java.util.Locale.ROOT, "%.1f",
                Math.sqrt(door.distanceSqToNearestCell(pos)));
    }

    /**
     * 配不上对时，把"到底缺什么"写成一句<b>能照着改</b>的话。
     * <p>
     * 三种成因分开说，因为它们要去改的地方完全不同：频道没设 / 门没建造
     * （合成一句：频道里根本没有已建造的门）、门太远、只找到一扇。
     */
    private String buildPairHint(int found, int sameChannelWorld) {
        if (sameChannelWorld == 0) {
            return "频道 " + channel + " 里没有已建造的门 —— 门的「频道」要在门的设置界面里设成 "
                    + channel + "（默认是 0 = 不参与门禁），而且门要点过「建造」";
        }
        if (found == 0) {
            return "频道 " + channel + " 里有 " + sameChannelWorld + " 扇已建造的门，但都不在 "
                    + SEARCH_RADIUS + " 格内 —— 把控制器挪到两扇门中间，或者把门挪近";
        }
        if (found == 1) {
            return "频道 " + channel + " 里有 " + sameChannelWorld + " 扇已建造的门，"
                    + SEARCH_RADIUS + " 格内只找到 1 扇 —— 气闸要两扇（另一扇离得太远，"
                    + "或者它是隔壁气闸的门 / 还没建造）";
        }
        return "频道 " + channel + " 里找到 " + found + " 扇门，但凑不成一对 —— 检查它们的频道和建造状态";
    }

    /** 配不上对的原因（界面显示用）；配对正常时是空串 */
    public String getPairHint() {
        return pairHint == null ? "" : pairHint;
    }

    /**
     * 把两侧的实际情况报进内核。
     * <p>
     * ★ "关着"看的是<b>实际开了几行</b>（{@code getProgress()}），不是意图。
     * 重型门走好几刻，用意图的话会在门还没落到底时就以为关好了 ——
     * 那一刻舱室其实是敞的，净化却已经开始计时。
     * <p>
     * ★ "锁着"报的是<b>除气闸位以外</b>还有没有锁。把气闸自己封的那一把算进去的话，
     * 净化一结束就会判定"目标侧被封锁"，然后永久憋住。
     */
    private void report() {
        int count = 0;
        for (int s = 0; s < AirlockCore.SIDES; s++) {
            TileBlastDoor d = door(s);
            if (d == null) {
                // 门不见了 / 区块没加载：当它"没关着"。
                // 后果是 LOCKING 会一直等到超时然后中止 —— 这是安全的收场
                core.setSideClosed(s, false);
                core.setSideLatched(s, false);
                continue;
            }
            count++;
            core.setSideClosed(s, d.getProgress() <= 0);
            core.setSideLatched(s, d.hasManualLatch() || d.hasRemoteLatch());
        }
        core.setDoorCount(count);
    }

    // ------------------------------------------------------------------ 事件

    private void handleEvent(int event) {
        // ★ 声音先发，且不问聊天栏那条广播发没发出去 —— 某个语言键缺失时该响的也得响
        //   （和 TileDmrController.handleEvents 同一个约定）。
        //   表在 MachineAudio 里：气闸声 / 净化完成 / 放行 / 憋住 / 急停各一个候选链。
        MachineAudio.airlockEvent(world, pos, event);

        switch (event) {
            case AirlockCore.EVENT_DECON_START:
                setStatus("两侧已密封，净化 " + core.getDeconTicks() + " 刻");
                broadcast("气闸密闭，净化中");
                break;
            case AirlockCore.EVENT_DECON_DONE:
                setStatus("净化完成，准备放行");
                break;
            case AirlockCore.EVENT_OPEN_SIDE: {
                int dest = core.getDestSide();
                TileBlastDoor d = door(dest);
                if (d != null) {
                    // ★ 先清掉门自己的循环倒计时：门的"另一侧刚关上"逻辑在本次循环里
                    //   也触发过，残留的倒计时会把这次放行挡回去，白白晚几秒。
                    //   时序现在归控制器管，门自己那段倒计时没有意义了
                    d.clearCycle();
                    d.setAirlockLock(false, null);
                    d.requestOpen(true, null);
                }
                // ★ 记住这一侧是"开着的出口"：人还没走出去，而且下一次按按钮时
                //   它就是"开着的那一侧" —— 也就是下一轮的入口侧
                openExitSide = dest;
                setStatus("放行 " + sideName(dest));
                broadcast("气闸放行 " + sideName(dest));
                break;
            }
            case AirlockCore.EVENT_HOLD:
                setStatus("净化完成，但目标侧被封锁 —— 保持密闭");
                broadcast("目标侧被封锁，气闸保持密闭");
                RsAudio.warn("[气闸] %s 目标侧被外部封锁，保持密闭不放大",
                        format(pos));
                break;
            case AirlockCore.EVENT_ABORT:
                // ★ 中止 = 保持密闭：放封条之后必须把两侧都关回去。
                //   只放封条不关门的话，正在升起的那扇会继续走完 ——
                //   玩家看到的就是"急停之后门反而自己开了"。
                //   ★ 同时把"在哪一段、因为什么"说出来，见 abortDetail()
                String why = abortDetail();
                releaseAll();
                closeBoth();
                openExitSide = AirlockCore.SIDE_NONE;
                setStatus("气闸循环中止：" + why + "，两侧保持关闭");
                broadcast("气闸循环中止：" + why);
                setActive(false);
                RsAudio.warn("[气闸] %s 循环中止：%s", format(pos), why);
                break;
            case AirlockCore.EVENT_DONE:
                // ★ 出口门<b>保持开着</b>：人还没走出去呢。
                //   它同时就是下一轮的"入口侧"（见 {@link #openSide()}），
                //   所以这里刻意<b>不</b>把它关回去。
                releaseAll();
                setStatus("气闸放行完成");
                setActive(false);
                RsAudio.log("[气闸] %s 放行完成（出口 %s 侧保持开着）",
                        format(pos), sideName(core.getDestSide()));
                break;
            default:
                break;
        }
    }

    public static String sideName(int side) {
        if (side == AirlockCore.SIDE_A) {
            return "甲";
        }
        return side == AirlockCore.SIDE_B ? "乙" : "?";
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        boolean dirty = false;

        if (!initialized) {
            // ★ 第一个 tick 无条件把指示灯对齐到存档里的状态。
            //   存档是"循环中"而方块状态是灭的（或反过来）时，不主动对齐就会一直错着，
            //   玩家看到的面板灯和实际行为对不上
            initialized = true;
            lastActive = core.isRunning();
            setActive(lastActive);
            dirty = true;
        }

        if (--scanCooldown <= 0) {
            scanCooldown = SCAN_INTERVAL;
            rebindDoors();
            // ★ 待机且配不上对时，把"为什么配不上"直接顶到状态栏。
            //   玩家潜行右键打开界面（或者看控制器说明）时就能看到成因，
            //   而不是按下去才收到一句"分不出你从哪一侧进来"。
            //   只在没在跑循环时做 —— 循环中的状态文案不能被这句话盖掉。
            if (!core.isRunning() && !isPaired()) {
                String text = setupRefusalText();
                if (!text.equals(status)) {
                    setStatus(text);
                    dirty = true;
                }
            }
        }
        report();

        // ★ 在推内核之前记下这一刻的阶段：abort() 会把 state 打回 IDLE，
        //   等 EVENT_ABORT 到 handleEvent 时就读不到"是哪一段出的错"了
        phaseAtTick = core.getState();
        int event = core.tick();
        if (event != AirlockCore.EVENT_NONE) {
            handleEvent(event);
            dirty = true;
        }


        boolean active = core.isRunning();
        if (active != lastActive) {
            lastActive = active;
            setActive(active);
            dirty = true;
        }

        if (dirty) {
            markDirty();
            syncToClient();
        } else if (active && --syncCooldown <= 0) {
            // 循环中每秒同步一次，界面上的倒计时才会走
            syncCooldown = SYNC_INTERVAL;
            markDirty();
            syncToClient();
        }
    }

    private void setActive(boolean active) {
        if (world != null && !world.isRemote) {
            BlockAirlockController.setActiveIndicator(world, pos, active);
        }
    }

    /**
     * ★ 方块实体被移除时（拆机器 / 方块被替换 / 区块卸载）把气闸封放掉。
     * <p>
     * 为什么挂在 {@code invalidate()} 而不是 {@code breakBlock}：这个钩子是
     * {@code World.removeTileEntity} 调用的，<b>必定</b>会发生；而 {@code breakBlock}
     * 里还能不能 {@code getTileEntity} 到方块实体，取决于 {@code World.setBlockState}
     * 内部先移除实体还是先回调方块，靠不住。
     * <p>
     * 代价是区块卸载时也会放掉（fail-open）。这是有意的：封条只挡"开门"，
     * 门本身仍然关着，所以不会留下缺口；而"永远开不了的门"没有任何界面能看出原因，
     * 那种故障要严重得多。
     */
    @Override
    public void invalidate() {
        if (world != null && !world.isRemote) {
            releaseAll();
        }
        super.invalidate();
    }

    /** 给玩家回一条状态提示；player 为 null（红石/自动化触发）时什么都不做 */
    private void notify(EntityPlayer player, String text) {
        if (player != null && text != null && !text.isEmpty()) {
            player.sendStatusMessage(new TextComponentString(text), true);
        }
    }

    /** 向附近的玩家播报（开界面里的「播报」关掉就不喊） */
    private void broadcast(String text) {
        if (!announce || world == null || text == null || text.isEmpty()) {
            return;
        }
        double limit = (double) ANNOUNCE_RANGE * ANNOUNCE_RANGE;
        for (EntityPlayer p : world.playerEntities) {
            if (p.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= limit) {
                p.sendStatusMessage(new TextComponentString(text), true);
            }
        }
    }

    private void setStatus(String text) {
        this.status = text == null ? "" : text;
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ------------------------------------------------------------------ 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.channel = TerminalCore.clampChannel(compound.hasKey("Channel")
                ? compound.getInteger("Channel") : TerminalCore.CHANNEL_DEFAULT);
        this.announce = !compound.hasKey("Announce") || compound.getBoolean("Announce");
        this.status = compound.getString("Status");
        core.loadState(compound.hasKey("Airlock") ? compound.getIntArray("Airlock") : null);
        // ★ 配对结果不持久化：它是"附近有哪几扇门"的投影，
        //   存下来只会在门被拆掉之后留下一堆死引用。下一次扫描（最多 0.5 秒）就重建好了
        sides[0] = null;
        sides[1] = null;
        sidesText = "";
        pairHint = "";
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Channel", channel);
        compound.setBoolean("Announce", announce);
        compound.setString("Status", status == null ? "" : status);
        compound.setIntArray("Airlock", core.saveState());
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Channel", channel);
        tag.setBoolean("Announce", announce);
        tag.setString("Status", status == null ? "" : status);
        tag.setIntArray("Airlock", core.saveState());
        tag.setString("Sides", sidesText == null ? "" : sidesText);
        tag.setString("Hint", pairHint == null ? "" : pairHint);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.channel = TerminalCore.clampChannel(tag.hasKey("Channel")
                ? tag.getInteger("Channel") : TerminalCore.CHANNEL_DEFAULT);
        this.announce = !tag.hasKey("Announce") || tag.getBoolean("Announce");
        this.status = tag.getString("Status");
        this.sidesText = tag.getString("Sides");
        this.pairHint = tag.getString("Hint");
        core.loadState(tag.hasKey("Airlock") ? tag.getIntArray("Airlock") : null);
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
}

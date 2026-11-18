package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockF9Casing;
import com.rsaudio.block.BlockF9Controller;
import com.rsaudio.block.BlockF9Core;
import com.rsaudio.f9.F9Core;
import com.rsaudio.f9.F9Settings;
import com.rsaudio.f9.F9Spec;
import com.rsaudio.network.F9Snapshot;
import com.rsaudio.network.PacketF9Action;
import com.rsaudio.network.PacketF9Sync;
import com.rsaudio.network.PacketHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F9 控制柜的方块实体 —— 整套反应堆在 MC 世界里的<b>唯一权威</b>。
 * <p>
 * 它和 {@link TileDmrController} 是同一套路子的两个实例：状态全在
 * {@link F9Core}（零 MC 依赖内核）里，这里只负责四件事 ——
 * <b>扫结构</b>、<b>推进内核</b>、<b>把事件变成聊天栏消息</b>、<b>把读数推给客户端</b>。
 * 内核自己一行 MC 代码都没有，所以 {@code tools/check_f9_core.py} 能拿纯 javac 跑它。
 *
 * <h3>和 DMR 控制柜的三处实质差异</h3>
 * <ol>
 *   <li>F9 没有"点火序列"：{@code start()} 只是把冷停堆推到运行，唯一前置是
 *       <b>安全系统必须开着</b>。所以这里没有 DMR 那套"子系统给齐 + 双钥匙 + 燃料单元"
 *       的启动前置，{@code canStartNow()} 也只有一条判据。</li>
 *   <li>F9 的操作是<b>连续量</b>为主的：拉杆按住就一直走。所以同步频率要更勤
 *       （玩家盯着指针调压力时，5 tick 一次的读数才跟得上手），见 {@link #SYNC_INTERVAL_BUSY}。</li>
 *   <li>爆堆 / 熔毁是<b>两台不同的终态</b>，但都走同一条"爆炸 + 等刷新"的路，
 *       区别只在倒计时长度和播报文案。</li>
 * </ol>
 */
public class TileF9Controller extends TileEntity implements ITickable {

    // ------------------------------------------------------------------ 调参

    /** 结构重扫间隔（tick）。20 = 每秒一次，够快也够省 */
    private static final int STRUCTURE_CHECK_INTERVAL = 20;
    /**
     * 结构结论"变了"要连续几轮才认（去抖）。
     * <p>
     * ★ 为什么必须去抖：玩家手抖放错一格又马上拿回来，会让结构判定一闪而过；
     * 不去抖就是一对聊天栏消息（失效 + 成立）。DMR 那边实测过一局六分钟刷八次。
     */
    private static final int STRUCT_CONFIRM_ROUNDS = 2;
    /**
     * 运行中的同步间隔。比 DMR 的 5 tick 更快是<b>故意</b>的：
     * F9 的核心玩法是"盯着同步仪指针，用泄压阀把功率调到 39000–41000"，
     * 玩家手上一直在动，读数推慢了指针就会跳。
     */
    private static final int SYNC_INTERVAL_BUSY = 3;
    /** 静止时的同步间隔（冷停堆 / 终态）。没必要一秒二十次 */
    private static final int SYNC_INTERVAL_IDLE = 20;
    /** 读数推送半径（格） */
    private static final double SYNC_RANGE = 96.0D;
    /** 一般事件的播报半径 */
    private static final double EVENT_RANGE = 64.0D;
    /** 事故级事件的播报半径（整座设施都该听到） */
    private static final double EVENT_RANGE_MAJOR = 256.0D;
    /** 同一句话的静默窗口（tick） */
    private static final int BROADCAST_THROTTLE_TICKS = 60;
    /** 闸门表的上限，防止带参数的消息把表撑爆 */
    private static final int THROTTLE_TABLE_MAX = 128;

    private static final String PREFIX = "chat.rsaudio.f9.";
    /**
     * 内核事件的语言键前缀。★ 和结构类消息（{@link #PREFIX}）<b>分开一个段</b>——
     * 和 DMR 那边一样：事件有二十来条、结构只有几条，
     * 混在同一个命名空间里，lang 文件里就看不出"哪些是内核排出来的"。
     */
    private static final String PREFIX_EVENT = "chat.rsaudio.f9.event.";

    /** 内核事件键 -> 语言键。见 {@link #staticInit()} */
    private static final Map<String, String> EVENT_TEXT = new HashMap<String, String>();
    /** 事故级事件：播报范围拉到整座设施 */
    private static final Set<String> MAJOR_EVENTS = new HashSet<String>();
    /**
     * 不设闸的事件：<b>重复出现即确又发生了一次</b>。
     * <p>
     * 比如"卡棒"—— 一根棒卡住只会 emit 一次，但玩家反复打到 SCRAM 去清卡棒时，
     * 每清掉一根就再来一根，每一根都该说出来。设了闸反而会让后面几根"闷掉"。
     */
    private static final Set<String> REPEATABLE_EVENTS = new HashSet<String>();
    /**
     * 只进界面 / 只发声、<b>不往聊天栏推</b>的状态类事件。
     * <p>
     * 判据：玩家看到它<b>不需要动一下</b>（状态就在界面读数里），或者它必然跟着另一条
     * 更需要玩家反应的消息一起出现（说两遍等于没说）。
     * <ul>
     *   <li>{@code rod_moving} —— 拉杆每重新挂一次就可能发，聊天栏会被刷屏；</li>
     *   <li>{@code psi_safe_again} —— 压力回到安全区，是"警报解除"而不是"要你做事"，
     *       界面上压力条变绿就是全部信息。</li>
     * </ul>
     */
    private static final Set<String> QUIET_EVENTS = new HashSet<String>();

    static {
        // ---- 事件 -> 语言键。键名和 F9Spec 里的常量一一对应，别改着改着少一条：
        //      少一条的后果是"这件事发生了但玩家永远不知道"，而且完全不报错（见下面的 continue）。
        EVENT_TEXT.put(F9Spec.EV_ROD_MOVING, PREFIX_EVENT + "rod_moving");
        EVENT_TEXT.put(F9Spec.EV_ROD_JAMMED, PREFIX_EVENT + "rod_jammed");
        EVENT_TEXT.put(F9Spec.EV_ROD_CLEARED, PREFIX_EVENT + "rod_cleared");
        EVENT_TEXT.put(F9Spec.EV_SCRAM, PREFIX_EVENT + "scram");
        EVENT_TEXT.put(F9Spec.EV_TEMP_ALARM, PREFIX_EVENT + "temp_alarm");
        EVENT_TEXT.put(F9Spec.EV_PSI_DANGER, PREFIX_EVENT + "psi_danger");
        EVENT_TEXT.put(F9Spec.EV_PSI_SAFE_AGAIN, PREFIX_EVENT + "psi_safe_again");
        EVENT_TEXT.put(F9Spec.EV_SAFETY_DISABLED, PREFIX_EVENT + "safety_disabled");
        EVENT_TEXT.put(F9Spec.EV_SAFETY_ENABLED, PREFIX_EVENT + "safety_enabled");
        EVENT_TEXT.put(F9Spec.EV_SYNC_OK, PREFIX_EVENT + "sync_ok");
        EVENT_TEXT.put(F9Spec.EV_SYNC_FAIL_NEEDLE, PREFIX_EVENT + "sync_fail_needle");
        EVENT_TEXT.put(F9Spec.EV_SYNC_FAIL_KW, PREFIX_EVENT + "sync_fail_kw");
        EVENT_TEXT.put(F9Spec.EV_SYNC_FAIL_RODS, PREFIX_EVENT + "sync_fail_rods");
        EVENT_TEXT.put(F9Spec.EV_SYNC_FAIL_VALVES, PREFIX_EVENT + "sync_fail_valves");
        EVENT_TEXT.put(F9Spec.EV_DESYNC, PREFIX_EVENT + "desync");
        EVENT_TEXT.put(F9Spec.EV_BLOWOUT, PREFIX_EVENT + "blowout");
        EVENT_TEXT.put(F9Spec.EV_MELTDOWN, PREFIX_EVENT + "meltdown");
        EVENT_TEXT.put(F9Spec.EV_REGEN, PREFIX_EVENT + "regen");
        EVENT_TEXT.put(F9Spec.EV_SHUTDOWN_DENIED_POWER, PREFIX_EVENT + "shutdown_denied_power");
        EVENT_TEXT.put(F9Spec.EV_SHUTDOWN_DENIED_TEMP, PREFIX_EVENT + "shutdown_denied_temp");
        EVENT_TEXT.put(F9Spec.EV_SHUTDOWN_OK, PREFIX_EVENT + "shutdown_ok");
        EVENT_TEXT.put(F9Spec.EV_THERMAL_LOCKED, PREFIX_EVENT + "thermal_locked");
        EVENT_TEXT.put(F9Spec.EV_FLUX_HIGH, PREFIX_EVENT + "flux_high");

        MAJOR_EVENTS.add(F9Spec.EV_SCRAM);
        MAJOR_EVENTS.add(F9Spec.EV_BLOWOUT);
        MAJOR_EVENTS.add(F9Spec.EV_MELTDOWN);
        MAJOR_EVENTS.add(F9Spec.EV_REGEN);

        // 重大事件天然不设闸
        REPEATABLE_EVENTS.addAll(MAJOR_EVENTS);
        REPEATABLE_EVENTS.add(F9Spec.EV_ROD_JAMMED);
        REPEATABLE_EVENTS.add(F9Spec.EV_ROD_CLEARED);
        REPEATABLE_EVENTS.add(F9Spec.EV_SYNC_FAIL_VALVES);

        QUIET_EVENTS.add(F9Spec.EV_ROD_MOVING);
        QUIET_EVENTS.add(F9Spec.EV_PSI_SAFE_AGAIN);
    }

    // ------------------------------------------------------------------ 状态

    /** 零依赖内核。服务端才有；客户端保持 null（读数从快照来） */
    private F9Core core;
    private final F9Settings settings = new F9Settings();

    private boolean structureOk;
    /**
     * 堆芯坐标。<b>一旦认出来就不再清空</b>（"粘性"，和 DMR 同一条纪律）。
     * <p>
     * 外壳被拆掉时结构判定会失败；如果这时把坐标清空，<b>爆炸会静默地什么都不做</b> ——
     * 事故流程照走、播报照发，就是不炸。而"拆两块砖让失控的反应堆停下来"
     * 恰恰是设计上明确不允许的事。所以判"还能不能操作"看 {@link #structureOk}，
     * 判"堆芯在哪"看这个字段，两者分开。
     */
    private BlockPos corePos;

    private int structureTimer;
    /** 结构结论"变了"已经连续几轮 */
    private int structConfirm;
    private int syncTimer;
    /** 结构丢失时"再试一次停堆"的冷却（tick） */
    private int structureLostTimer;
    /** 上一次推快照时的状态，用来在状态跳变时立刻补推一次 */
    private int lastSyncedState = Integer.MIN_VALUE;

    /** 上次播报某句话的时刻（键 = 语言键 + 参数）。用世界时间而不是自己的计数器 */
    private final Map<String, Long> lastBroadcastAt = new HashMap<String, Long>();
    /** 上次处理某个内核事件的时刻（键 = 事件键） */
    private final Map<String, Long> lastEventAt = new HashMap<String, Long>();

    // ------------------------------------------------------------------ 读取

    /** 只给测试/调试用；客户端为 null */
    public F9Core getCore() {
        return core;
    }

    public boolean isStructureOk() {
        return structureOk;
    }

    public BlockPos getCorePos() {
        return corePos;
    }

    private F9Core core() {
        if (core == null) {
            core = new F9Core(settings);
        }
        return core;
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        F9Core c = core();

        if (++structureTimer >= STRUCTURE_CHECK_INTERVAL) {
            structureTimer = 0;
            validateStructure();
        }

        // ★ 事件永远先排空：结构不成立时玩家点按钮同样会产生事件（比如"启动被拒"），
        //   那些事件必须播出来，否则玩家只会看到按钮没反应、完全不知道为什么。
        handleEvents(c);

        if (structureOk || F9Spec.isTerminal(c.getState())) {
            // 终态之后即使外壳被拆也要继续跑完 —— 那是台已经失控的机器，
            // 拆两块砖并不能让它停下来，这个"来不及了"的手感是设计的一部分。
            structureLostTimer = 0;
            c.tick();
            handleEvents(c);
            if (c.consumeExplosionPending()) {
                detonate();
                return;
            }
        } else if (F9Spec.isHot(c.getState())) {
            // 结构被拆掉时堆还在热着：就地安全停机，别留下一个没人管的反应堆。
            // ★「结构失效」这条播报归 validateStructure() 管 —— 那边有"成立→不成立"的
            //   边沿判据，说一次就够。这里跟着每 tick 播会把聊天栏刷爆（DMR 踩过）。
            //   这里只让动作真的发生，不推聊天栏。
            if (structureLostTimer <= 0) {
                structureLostTimer = BROADCAST_THROTTLE_TICKS;
                c.shutdown();
                handleEvents(c, false);
            } else {
                structureLostTimer--;
            }
        }

        syncLit(c);
        pushSync(c);
    }

    // ------------------------------------------------------------------ 结构判定

    /**
     * ★★ 结构判定的<b>唯一实现</b>：沿六个轴向各走 2 格找压力容器，返回 {@link #DENY_*}。
     * <p>
     * <b>只读</b>：不碰 {@code structureOk}、不播报。找到的容器写进 {@code outCore[0]}，
     * 顺手刷新 {@code shellMiss} 这个诊断字段。
     * <p>
     * ★★★ 为什么必须提成一份：{@link #validateStructure()} 是<b>去抖</b>的，
     * 于是存在一个窗口 —— 最新一轮已经扫到完好结构，但 {@code structureOk} 还停在 false。
     * 玩家正好在这个窗口里按「启动」，读缓存的旧结论就会报出
     * 「还没连成多方块结构」—— 结构明明好好的，玩家照着 tooltip 重砌一遍也没用。
     * <p>
     * ★ 通式：<b>失败提示必须是"此刻现场重探"的结果，不能是上一轮的缓存结论。</b>
     *
     * @param outCore 长度 ≥1 的数组；找到容器时写入 outCore[0]，否则写 null
     */
    private int scanStructure(BlockPos[] outCore) {
        if (world == null) {
            return DENY_UNLOADED;
        }
        boolean sawCore = false;
        // 每轮从干净的诊断现场开始（否则上一轮的结论会顶掉这一轮的）
        shellMiss = -1;
        for (net.minecraft.util.EnumFacing dir : net.minecraft.util.EnumFacing.values()) {
            BlockPos candidate = pos.offset(dir, 2);
            // 邻块还没加载完（区块边界上会瞬时发生）时这一轮不下结论：
            // getBlockState 对未加载区块返回空气，会被当成"外壳被拆了"。
            if (!shellReady(candidate)) {
                return DENY_UNLOADED;
            }
            if (!(world.getBlockState(candidate).getBlock() instanceof BlockF9Core)) {
                continue;
            }
            sawCore = true;
            int miss = shellMissing(candidate);
            if (miss > 0) {
                // 记住"外壳缺几格"（取缺得最少的那个候选点，那才是最接近摆对的方向）
                if (shellMiss < 0 || miss < shellMiss) {
                    shellMiss = miss;
                }
                continue;
            }
            if (outCore != null) {
                outCore[0] = candidate;
            }
            return DENY_NONE;
        }
        if (sawCore) {
            return DENY_SHELL;
        }
        return DENY_CORE;
    }

    /**
     * 3×3×3 外壳、正中间一块压力容器，控制柜贴在外壳某面的<b>正中心外侧</b>。
     * <p>
     * 于是"容器在哪"和"控制柜在哪"之间只有六种可能：控制柜沿六个轴向各走 2 格。
     * 只查这 6 个候选点就够了。<b>上下两个方向也要认</b> —— 机器立着砌还是躺着砌
     * 应该随玩家，不该被朝向卡住。
     */
    private void validateStructure() {
        // 世界都没了就别下结论，原样保留上一轮的结论。
        if (world == null) {
            return;
        }
        BlockPos[] out = new BlockPos[1];
        int deny = scanStructure(out);
        structDeny = deny;
        // ★ 未加载完不下结论：这一步必须在比较 structureOk 之前返回，
        //   否则"区块没加载"会被当成一次"结构失效"，去抖几轮后真把结论翻成 false。
        if (deny == DENY_UNLOADED) {
            return;
        }
        boolean now = (deny == DENY_NONE);
        BlockPos found = out[0];
        if (now) {
            shellMiss = -1;
        }

        if (now == structureOk) {
            structConfirm = 0;
            if (now) {
                corePos = found;
            }
            return;
        }
        if (++structConfirm < STRUCT_CONFIRM_ROUNDS) {
            return;
        }
        structConfirm = 0;

        boolean was = structureOk;
        structureOk = now;
        if (now) {
            corePos = found;
        }
        if (was && !now) {
            // 结构失效必须在日志里留痕（带坐标）——出了事才有得查
            RsAudio.log("[F9] 结构失效：控制柜 %s，原因 %s", pos, structDenyText());
            broadcast(PREFIX + "structure_lost", EVENT_RANGE);
        } else if (now && canStartNow()) {
            // ★ "可以启动了"只在真的能启动的时候说。
            //   判据只有一份，在内核 {@link F9Core#canStart()} 里 ——
            //   这里自己写一份近似判断的话，播报一定会说谎（DMR 踩过两次）。
            RsAudio.log("[F9] 结构成立且启动前置就绪：控制柜 %s", pos);
            broadcast(PREFIX + "structure_ok", EVENT_RANGE);
        } else if (now) {
            RsAudio.log("[F9] 结构成立但还不能启动：控制柜 %s，安全系统没开", pos);
        }
    }

    /** 结构判定的失败原因（决定对玩家说哪一句话） */
    private static final int DENY_NONE = 0;
    private static final int DENY_CORE = 1;
    private static final int DENY_SHELL = 2;
    private static final int DENY_UNLOADED = 3;

    /** 上一次结构判定没通过的原因，见 {@link #DENY_*} */
    private int structDeny = DENY_NONE;
    /** {@link #DENY_SHELL} 时外壳缺几格 */
    private int shellMiss = -1;

    /**
     * 结构不成立时该对玩家说的那一句 —— <b>自报成因</b>。
     * <p>
     * "没找到压力容器"和"外壳缺几格"是两件完全不同的事，同一句话把两种成因盖住，
     * 玩家照着 tooltip 重砌一遍也没用。
     */
    private String structDenyKey() {
        switch (structDeny) {
            case DENY_CORE:
                return PREFIX + "no_structure_core";
            case DENY_SHELL:
                return PREFIX + "no_structure_shell";
            case DENY_UNLOADED:
                return PREFIX + "no_structure_unloaded";
            default:
                // ★ 兜底不能是"还没连成多方块结构"：走到这里说明结构其实是好的、
                //   只是结论还没被确认。说"没连成结构"就是在说谎。
                return PREFIX + "struct_pending";
        }
    }

    /** {@link #structDenyKey()} 那条提示带的参数（只有"外壳缺几格"要参数） */
    private String structDenyArg() {
        return structDeny == DENY_SHELL ? String.valueOf(Math.max(0, shellMiss)) : "";
    }

    /** 同一个原因，写成日志里能读的一句话 */
    private String structDenyText() {
        switch (structDeny) {
            case DENY_CORE:
                return "六个方向 2 格外都没有 F9 压力容器";
            case DENY_SHELL:
                return "找到压力容器了，但 3×3×3 外壳缺 " + Math.max(0, shellMiss) + " 格";
            case DENY_UNLOADED:
                return "邻近区块还没加载完，读不到外壳";
            default:
                return "未知";
        }
    }

    /**
     * 现在按启动按钮真的会启动吗。
     * <p>
     * ★ 判据只有一份，在内核 {@link F9Core#canStart()} 里 —— 这里<b>不</b>自己判断。
     */
    private boolean canStartNow() {
        return core().canStart();
    }

    /** 结构判定要读的那 3×3×3 是否已经全部加载（没加载完就不该下结论） */
    private boolean shellReady(BlockPos center) {
        for (BlockPos p : BlockPos.getAllInBoxMutable(center.add(-1, -1, -1), center.add(1, 1, 1))) {
            if (!world.isBlockLoaded(p)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 以 center 为中心那 3×3×3 里，除中心外 26 格<b>缺几格外壳</b>（0 = 全砌满）。
     * <p>
     * ★ 返回"缺口数"而不是"够不够"，是为了让提示能自报成因 ——
     * 玩家听到"还缺 3 格"才知道该去补几块。
     */
    private int shellMissing(BlockPos center) {
        int miss = 0;
        for (BlockPos p : BlockPos.getAllInBoxMutable(center.add(-1, -1, -1), center.add(1, 1, 1))) {
            if (p.equals(center)) {
                continue;
            }
            if (!(world.getBlockState(p).getBlock() instanceof BlockF9Casing)) {
                miss++;
            }
        }
        return miss;
    }

    // ------------------------------------------------------------------ 事件

    /** 把内核排出来的事件键变成聊天栏消息 */
    private void handleEvents(F9Core c) {
        handleEvents(c, true);
    }

    /**
     * @param announce 是否往聊天栏播报。传 {@code false} 时什么都不说 ——
     *                 用在"外壳已经被拆掉"那条路径上：那时候机器已经废了，
     *                 内核因为停堆前置条件不满足而排出来的"停机被拒"在聊天栏里看着像是有救。
     */
    private void handleEvents(F9Core c, boolean announce) {
        List<String> events = c.drainEvents();
        if (events.isEmpty()) {
            return;
        }
        for (String key : events) {
            // ★ 同一件事 3 秒内只说一次：连点按钮会把同一个失败原因 emit 很多遍。
            //   但"重复出现即确又发生了一次"的事件不设闸 —— 见 REPEATABLE_EVENTS。
            if (!REPEATABLE_EVENTS.contains(key) && cutDuplicate(lastEventAt, key)) {
                continue;
            }
            // ★ 状态类事件到此为止：不往聊天栏推（见 QUIET_EVENTS）。
            //   聊天栏只留给"玩家需要动一下"的事件 —— 状态类的现场在界面的读数里。
            if (QUIET_EVENTS.contains(key)) {
                continue;
            }
            String langKey = EVENT_TEXT.get(key);
            if (langKey == null) {
                // ★★ 这条 continue 是静默的：事件发生了但玩家永远不知道。
                //   所以留一行日志 —— 缺语言键是"改了内核忘了改这里"，必须能查出来。
                RsAudio.warn("[F9] 未登记的内核事件键「%s」：控制柜 %s —— 玩家看不到这条提示", key, pos);
                continue;
            }
            if (F9Spec.EV_SCRAM.equals(key)) {
                if (announce) {
                    // SCRAM 要自报成因（高压 / 高通量 / 高温 / 超功率），
                    // 不然玩家只知道"停堆了"，不知道自己做错了什么。
                    broadcast(langKey, EVENT_RANGE_MAJOR, scramReasonText(c));
                }
                continue;
            }
            if (announce) {
                broadcast(langKey, MAJOR_EVENTS.contains(key) ? EVENT_RANGE_MAJOR : EVENT_RANGE);
            }
        }
        markDirty();
    }

    /** 把内核给的停机原因代码变成玩家能读的一句话（键缺失时兜底显示原始代码） */
    private String scramReasonText(F9Core c) {
        String reason = c.getScramReason();
        if (reason == null || reason.isEmpty()) {
            return "未知";
        }
        // 内核里这些原因本身就是英文短代码（见 F9Spec.SCRAM_*），这里不翻译 ——
        // 它们是给玩家对照界面上的红色标出来的那一条用的，保持原样最不容易出错。
        return reason;
    }

    private void broadcast(String langKey, double range, Object... args) {
        if (world == null || world.isRemote) {
            return;
        }
        if (cutDuplicate(lastBroadcastAt, dedupeKey(langKey, args))) {
            return;
        }
        double limit = range * range;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.isDead) {
                continue;
            }
            if (player.getDistanceSq(x, y, z) <= limit) {
                player.sendStatusMessage(new TextComponentTranslation(langKey, args), false);
            }
        }
    }

    /**
     * 给"玩家自己点出来的"反馈（点了按钮但被拒）。
     * <p>
     * 和 {@link #broadcast} 用同一张闸门表，但按玩家分开计时 —— 不能让 A 连点把 B 的提示压掉。
     */
    private void tell(EntityPlayer player, String langKey, Object... args) {
        if (world == null || world.isRemote || player == null) {
            return;
        }
        if (cutDuplicate(lastBroadcastAt, dedupeKey(langKey + "@" + player.getEntityId(), args))) {
            return;
        }
        player.sendStatusMessage(new TextComponentTranslation(langKey, args), false);
    }

    /** 把"这句话 + 这组参数"拼成一个去重键 */
    private static String dedupeKey(String langKey, Object[] args) {
        if (args == null || args.length == 0) {
            return langKey;
        }
        StringBuilder sb = new StringBuilder(langKey);
        for (Object arg : args) {
            sb.append('|').append(arg);
        }
        return sb.toString();
    }

    /**
     * 这句话现在该不该说：说过就记下时刻，静默窗口里的重复返回 true（= 掐掉）。
     */
    private boolean cutDuplicate(Map<String, Long> table, String key) {
        long now = world.getTotalWorldTime();
        Long prev = table.get(key);
        // ★ 必须带 now >= prev：换维度/读档之后世界时间有可能比记下的小，
        //   差值变成负数就会永远小于窗口 —— 那会把这台机器的播报<b>永久静音</b>。
        if (prev != null && now >= prev && now - prev < BROADCAST_THROTTLE_TICKS) {
            return true;
        }
        if (table.size() >= THROTTLE_TABLE_MAX) {
            table.clear();
        }
        table.put(key, now);
        return false;
    }

    // ------------------------------------------------------------------ 后果

    /** 爆堆 / 熔毁：真炸一个坑。标记在内核里，只会被取到一次 */
    private void detonate() {
        if (corePos == null || world == null) {
            return;
        }
        RsAudio.log("[F9] 反应堆在 %s 炸了（状态 %s）", corePos, core().getStateName());
        if (settings.explosionEnabled) {
            world.newExplosion((net.minecraft.entity.Entity) null,
                    corePos.getX() + 0.5D, corePos.getY() + 0.5D, corePos.getZ() + 0.5D,
                    settings.explosionRadius, true, true);
        }
    }

    // ------------------------------------------------------------------ 玩家操作

    /**
     * 服务端权威入口：处理界面发来的一个动作。
     * <p>
     * 参数一律当不可信（动作号越界、棒索引越界、子系统位不在掩码里），
     * 全部在这里挡掉 —— 客户端那段代码是玩家可以改的。
     */
    public void handleAction(EntityPlayer player, int action, int arg) {
        if (world == null || world.isRemote) {
            return;
        }
        F9Core c = core();
        switch (action) {
            case PacketF9Action.ACT_START:
                if (!structureOk) {
                    // ★★★ 自报成因，而且必须是<b>此刻现场重探</b>的成因，不能读缓存的 structDeny。
                    BlockPos[] out = new BlockPos[1];
                    int deny = scanStructure(out);
                    if (deny == DENY_NONE) {
                        // 结构其实是好的，只是"结论"还没落地 —— 这一按按早了。
                        RsAudio.log("[F9] 启动被拒（结论未落定）：控制柜 %s，现场重探结构完好，"
                                + "等去抖确认后再按", pos);
                        tell(player, PREFIX + "struct_pending");
                        return;
                    }
                    structDeny = deny;
                    RsAudio.log("[F9] 启动被拒（结构不成立）：控制柜 %s，%s", pos, structDenyText());
                    tell(player, structDenyKey(), structDenyArg());
                    return;
                }
                if (!c.canStart()) {
                    // 走到这里只剩一种可能：安全系统被关了。
                    // 内核的 start() 只有这一条前置，所以这里可以把话说死。
                    RsAudio.log("[F9] 启动被拒（安全系统关闭）：控制柜 %s", pos);
                    tell(player, PREFIX + "start_need_safety");
                    return;
                }
                c.start();
                break;
            case PacketF9Action.ACT_MOVE_ROD:
                // ★ 每一 tick 的重复调用 = "按住"。内核的语义是"这一 tick 之后还接着走一秒"，
                //   所以按住就是每 tick 再挂一次，而不是需要一个新的"开始移动"动作。
                if (arg != 1 && arg != -1) {
                    return;
                }
                c.moveRods(arg);
                break;
            case PacketF9Action.ACT_RELEASE_ROD:
                c.releaseRods();
                break;
            case PacketF9Action.ACT_STOP_ROD:
                c.stopRods();
                break;
            case PacketF9Action.ACT_TOGGLE_SUB:
                // 只认那 4 个开关位。低位是转换器/泄压阀的位段，
                // 它们是靠 ACT_TOGGLE_CONVERTER / ACT_TOGGLE_VALVE 单调的 ——
                // 这里放行的话，客户端报一个 0x10 就能绕过"逐个接活塞"的操作手感。
                if ((arg & F9Spec.SUB_ALL) == 0 || (arg & ~F9Spec.SUB_ALL) != 0) {
                    return;
                }
                c.toggleSubsystem(arg);
                break;
            case PacketF9Action.ACT_TOGGLE_CONVERTER:
                if (arg < 0 || arg >= F9Spec.CONVERTER_COUNT) {
                    return;
                }
                c.toggleConverter(arg);
                break;
            case PacketF9Action.ACT_TOGGLE_VALVE:
                if (arg < 0 || arg >= F9Spec.VALVE_COUNT) {
                    return;
                }
                c.toggleValve(arg);
                break;
            case PacketF9Action.ACT_SELECT_ROD:
                if (arg < F9Core.ROD_SELECT_ALL || arg >= F9Spec.ROD_COUNT) {
                    return;
                }
                c.selectRod(arg);
                break;
            case PacketF9Action.ACT_SELECT_SECTOR:
                if (arg < 0 || arg >= F9Spec.SECTOR_COUNT) {
                    return;
                }
                c.selectSector(arg);
                break;
            case PacketF9Action.ACT_SYNC:
                c.synchronize();
                break;
            case PacketF9Action.ACT_DESYNC:
                c.desynchronize();
                break;
            case PacketF9Action.ACT_SHUTDOWN:
                c.shutdown();
                break;
            default:
                return;
        }
        handleEvents(c);
        markDirty();
        // 立刻回推一次：玩家点了按钮不用等下一个同步周期才看到变化
        sendStateTo(player);
    }

    /** 控制柜被拆掉：热着的堆就地安全停机 */
    public void onControllerBroken() {
        if (core == null || world == null || world.isRemote) {
            return;
        }
        if (F9Spec.isHot(core.getState())) {
            core.shutdown();
        }
    }

    // ------------------------------------------------------------------ 同步

    private void syncLit(F9Core c) {
        int state = c.getState();
        boolean lit = state != F9Spec.ST_COLD && !F9Spec.isTerminal(state);
        BlockF9Controller.setLit(world, pos, lit);

        // ★ 顺带把压力容器的自发光也同步过去。
        //   corePos 是 validateStructure() 找到的；结构还没成立时它是 null，直接跳过。
        if (corePos != null && world.isBlockLoaded(corePos)) {
            BlockF9Core.setLit(world, corePos, lit);
        }
    }

    private void pushSync(F9Core c) {
        syncTimer++;
        int state = c.getState();
        boolean busy = state != F9Spec.ST_COLD && !F9Spec.isTerminal(state);
        int interval = busy ? SYNC_INTERVAL_BUSY : SYNC_INTERVAL_IDLE;
        boolean stateChanged = state != lastSyncedState;
        if (!stateChanged && syncTimer < interval) {
            return;
        }
        syncTimer = 0;
        lastSyncedState = state;

        F9Snapshot snapshot = F9Snapshot.capture(c, structureOk);
        double limit = SYNC_RANGE * SYNC_RANGE;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.isDead) {
                continue;
            }
            try {
                if (player.getDistanceSq(x, y, z) <= limit) {
                    PacketHandler.INSTANCE.sendTo(new PacketF9Sync(pos, snapshot), (EntityPlayerMP) player);
                }
            } catch (Throwable t) {
                // 单个玩家掉线不该影响其他人
            }
        }
    }

    /** 单独给某个玩家推一次（开界面 / 点了按钮时用） */
    public void sendStateTo(EntityPlayer player) {
        if (world == null || world.isRemote || !(player instanceof EntityPlayerMP) || core == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendTo(
                    new PacketF9Sync(pos, F9Snapshot.capture(core, structureOk)), (EntityPlayerMP) player);
        } catch (Throwable t) {
            // 同上
        }
    }

    // ------------------------------------------------------------------ 存档

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (core == null) {
            core = new F9Core(settings);
        }
        // 长度不对时 loadState 会整份丢弃 —— 半份状态比一台全新的机器更危险
        core.loadState(compound.getIntArray("F9"));
        structureOk = compound.getBoolean("StructOk");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (core != null) {
            compound.setIntArray("F9", core.saveState());
        }
        compound.setBoolean("StructOk", structureOk);
        return compound;
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, net.minecraft.block.state.IBlockState oldState,
                                 net.minecraft.block.state.IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

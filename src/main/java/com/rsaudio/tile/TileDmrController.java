package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockDmrCasing;
import com.rsaudio.block.BlockDmrController;
import com.rsaudio.block.BlockDmrCore;
import com.rsaudio.dmr.DmrCore;
import com.rsaudio.dmr.DmrSettings;
import com.rsaudio.dmr.DmrSpec;
import com.rsaudio.network.DmrSnapshot;
import com.rsaudio.network.PacketDmrAction;
import com.rsaudio.network.PacketDmrSync;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.util.DmrAudio;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * DMR 控制柜的方块实体：整套反应堆在 MC 世界里的<b>唯一权威</b>。
 *
 * <h3>职责边界</h3>
 * 物理与状态机全在 {@link DmrCore}（零 MC 依赖，能被纯 javac 单测）。这个类只干四件事：
 * <ol>
 *   <li><b>每 tick 喂一次</b>内核（{@code core.tick()}）—— 只发生在服务端；</li>
 *   <li><b>把事件键翻译成世界里的后果</b>：聊天播报、烤死腔体里的人、内爆真爆炸；</li>
 *   <li><b>结构判定</b>：每 {@value #STRUCTURE_CHECK_INTERVAL} tick 扫一次多方块；</li>
 *   <li><b>持久化与同步</b>：状态存成 {@code int[]} 进 NBT，读数按需推给附近客户端。</li>
 * </ol>
 * 它<b>不做任何数值计算</b> —— 一旦在这里改温度或判定熔毁，就等于把内核复制了一份，
 * 两份逻辑迟早会分叉，而且分叉出来的那份没有单测。
 *
 * <h3>为什么结构检查不做在 neighborChanged</h3>
 * 和广播器抓红石沿是同一个道理：邻居变化的顺序在复杂场景下不保证，一次
 * {@code setBlock} 可能只触发部分邻居。定时全量扫一遍逻辑上多跑一点，但<b>不会漏</b>。
 * 一次扫描是 6 个候选中心 × 27 格 = 162 次查询，20 tick 一次，可以忽略。
 *
 * <h3>为什么快照走自定义包而不是 getUpdateTag</h3>
 * 读数每 tick 都在变，而 {@code getUpdateTag} 是"整块方块的 NBT 广播"。
 * 用那条路等于每 tick 往所有附近玩家推一次 NBT。这里改成服务端按自己节奏推。
 */
public class TileDmrController extends TileEntity implements ITickable {

    /** 结构全量重扫的间隔（tick） */
    private static final int STRUCTURE_CHECK_INTERVAL = 20;

    /**
     * 结构结论要连续几轮一致才认（每轮 {@link #STRUCTURE_CHECK_INTERVAL} tick）。
     * <p>
     * 一次"结论变了"会往聊天栏发一条消息，所以一闪而过的扰动（玩家放错一格又马上改回来、
     * 区块边缘瞬时未加载）不该被当成真的结构变化。两轮 = 最多 2 秒的确认延迟，
     * 对"可以点火了"这种提示完全无感，但足以滤掉手抖。
     */
    private static final int STRUCT_CONFIRM_ROUNDS = 2;
    /** 反应堆"动起来了"时的读数推送间隔（tick） */
    private static final int SYNC_INTERVAL_BUSY = 5;
    /** 停机待命时的推送间隔 —— 这时候读数基本不动，没必要每 5 tick 推一次 */
    private static final int SYNC_INTERVAL_IDLE = 20;
    /** 读数推给多远以内的玩家 */
    private static final double SYNC_RANGE = 96.0D;
    /** 一般事件播报半径（"你身边这台机器"） */
    private static final double EVENT_RANGE = 64.0D;
    /** 重大事件播报半径（熔毁/内爆/Code Black 要全设施都听见） */
    private static final double EVENT_RANGE_MAJOR = 256.0D;

    /**
     * 同一句话的最短重复间隔（tick，3 秒）。
     *
     * <h3>为什么必须有这道闸</h3>
     * 失败类播报是<b>被玩家反复操作反复触发</b>的：连点六次「硬重启激光」，内核就 emit 六次
     * 同一个键，聊天栏跟着刷六条一模一样的红字（实测两秒内七条 —— 就是这个把聊天栏淹掉的）。
     * 播报的职责是"告诉玩家卡在哪"，第二遍没有任何新信息，只会把真正重要的那几条（熔毁、
     * 停机成功）挤出屏幕。
     * <p>
     * 另外还有一条<b>没有玩家参与也会刷</b>的路：结构被拆、堆还在 ONLINE、而停堆前置条件
     * （温度 &lt; 3500K）又不满足时，{@code c.beginCooldown()} 每个 tick 都被拒一次、每次都
     * emit 一条"停堆被拒"。3 秒的窗口把这条也一并压住。
     */
    private static final int BROADCAST_THROTTLE_TICKS = 60;

    /** 闸门表的条数上限，到顶就整体清空（见 {@link #cutDuplicate}） */
    private static final int THROTTLE_TABLE_MAX = 128;

    /** 找"散落便签"时最多试几个随机点 */
    private static final int NOTE_SEARCH_TRIES = 60;
    /** 便签散落的最大水平半径（格） */
    private static final int NOTE_RADIUS = 24;
    /** 玩家要离堆芯多近才够得着燃料单元（格，平方比较用） */
    private static final double CHAMBER_REACH_SQ = 16.0D;

    private static final String PREFIX = "chat.rsaudio.dmr.";

    /** 事件键 -> 语言文件键。内核只产键，措辞是这边的事 */
    private static final Map<String, String> EVENT_TEXT = new HashMap<String, String>();

    /** 需要全设施广播的重大事件 */
    private static final java.util.Set<String> MAJOR_EVENTS = new java.util.HashSet<String>();

    /**
     * 不设节流闸的事件：它们<b>重复出现本身就是信息</b>，不是噪音。
     * 判别标准只有一条 —— "同一秒里出现两次，是不是真的发生了两次？"
     * <ul>
     *   <li>{@link #MAJOR_EVENTS}（熔毁 / Phase2 / Code Black / 内爆）：真发生第二次就得说；</li>
     *   <li>{@code EV_CELL_PULLED}：三个人各拔一块是本设计里的一条正式通道，
     *       两个玩家挨着操作时可能落在同一个 3 秒窗口里；</li>
     *   <li>{@code EV_LASER_LOST}：一根接一根烧掉是坏消息的<b>增量</b>，吞掉就等于少报一根；</li>
     *   <li>{@code EV_SHUTDOWN_OK_*}：停机成功的收尾播报，只此一次。</li>
     * </ul>
     * 反过来，"温度没压下来 / 有功率激光没在工作"这类是<b>状态</b>不是<b>事件</b> ——
     * 说一遍和说十遍，玩家看到的是同一件事。
     */
    private static final java.util.Set<String> REPEATABLE_EVENTS = new java.util.HashSet<String>();

    /**
     * <b>不进聊天栏</b>的事件：仍然发声（{@code DmrAudio}）、仍然进 GUI 的读数与告警灯，
     * 只是不再往玩家屏幕上推一条文字。
     *
     * <h3>判据</h3>
     * "这条消息说的是一件<b>玩家已经能看见</b>的事吗？" —— DMR 的 GUI 上有温度、完整性、
     * 燃料、EFSS 水位四个实时读数，还有一块告警灯。凡是那四个数字直接体现出来的，
     * 再加一条聊天栏消息就是重复表达同一件事，而聊天栏是共享资源：它被状态刷满之后，
     * 真正的<b>事件</b>（激光烧毁、停机被拒、熔毁倒计时）就淹在里面了。
     *
     * <h3>为什么必须存在这个集合</h3>
     * 这些事件是内核按"越界即播报"产生的，而内核并不知道玩家能看见什么。
     * 最典型的是 {@code EV_SUSTAIN_LOSS}：热管理系统一开，空载堆芯 11.2 秒就会被压过
     * 230K，于是<b>每次点火</b>都必然弹一条"反应可持续性正在丧失（堆芯过冷）" ——
     * 玩家什么都没做错，却收到一条听起来像事故的警报。它是状态，不是事件。
     */
    private static final java.util.Set<String> QUIET_EVENTS = new java.util.HashSet<String>();

    static {
        String e = PREFIX + "event.";
        EVENT_TEXT.put(DmrSpec.EV_IGNITION_START, e + "ignition_start");
        EVENT_TEXT.put(DmrSpec.EV_IGNITION_DENIED_SUBSYS, e + "denied_subsys");
        EVENT_TEXT.put(DmrSpec.EV_IGNITION_DENIED_FUEL, e + "denied_fuel");
        EVENT_TEXT.put(DmrSpec.EV_ONLINE, e + "online");
        EVENT_TEXT.put(DmrSpec.EV_COOLDOWN_DONE, e + "cooldown");
        EVENT_TEXT.put(DmrSpec.EV_COOLDOWN_FAILED_TEMP, e + "cooldown_need_thermal");
        EVENT_TEXT.put(DmrSpec.EV_COOLDOWN_FAILED_EFSS, e + "cooldown_need_efss");
        EVENT_TEXT.put(DmrSpec.EV_SAFE_TEMP, e + "temp_safe");
        EVENT_TEXT.put(DmrSpec.EV_WARN_TEMP, e + "temp_warn");
        EVENT_TEXT.put(DmrSpec.EV_INTEGRITY_DROP, e + "integrity_drop");
        EVENT_TEXT.put(DmrSpec.EV_FUEL_LOW, e + "fuel_low");
        EVENT_TEXT.put(DmrSpec.EV_FUEL_MONITOR_FAIL, e + "fuel_monitor_fail");
        EVENT_TEXT.put(DmrSpec.EV_SUSTAIN_LOSS, e + "sustain_loss");
        EVENT_TEXT.put(DmrSpec.EV_STALL, e + "stall");
        EVENT_TEXT.put(DmrSpec.EV_MAINTENANCE_IN, e + "maintenance_in");
        EVENT_TEXT.put(DmrSpec.EV_MAINTENANCE_OUT, e + "maintenance_out");
        EVENT_TEXT.put(DmrSpec.EV_MAINTENANCE_NEED_CELLS, e + "need_cells");
        EVENT_TEXT.put(DmrSpec.EV_MELTDOWN, e + "meltdown");
        EVENT_TEXT.put(DmrSpec.EV_PHASE2, e + "phase2");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_OK_P1, e + "shutdown_p1");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_OK_P2, e + "shutdown_p2");
        EVENT_TEXT.put(DmrSpec.EV_CODE_REJECTED, e + "code_rejected");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_FAILED_THERMAL, e + "need_thermal");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_FAILED_EFSS, e + "need_efss");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_FAILED_LASER, e + "need_laser");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_FAILED_KEYS, e + "need_keys");
        EVENT_TEXT.put(DmrSpec.EV_SHUTDOWN_FAILED_CELLS, e + "need_cells_shutdown");
        EVENT_TEXT.put(DmrSpec.EV_LASER_LOST, e + "laser_lost");
        EVENT_TEXT.put(DmrSpec.EV_LASER_REPAIRED, e + "laser_repaired");
        EVENT_TEXT.put(DmrSpec.EV_EFSS_LOW, e + "efss_low");
        EVENT_TEXT.put(DmrSpec.EV_CELL_PULLED, e + "cell_pulled");
        EVENT_TEXT.put(DmrSpec.EV_CELL_TRAP, e + "cell_trap");
        EVENT_TEXT.put(DmrSpec.EV_CODE_LOCATED, e + "code_located");
        EVENT_TEXT.put(DmrSpec.EV_CODE_BLACK, e + "code_black");
        EVENT_TEXT.put(DmrSpec.EV_IMPLODE, e + "implode");
        EVENT_TEXT.put(DmrSpec.EV_ORDER_NEW, e + "order_new");
        EVENT_TEXT.put(DmrSpec.EV_ORDER_DONE, e + "order_done");
        EVENT_TEXT.put(DmrSpec.EV_ORDER_FAIL, e + "order_fail");

        MAJOR_EVENTS.add(DmrSpec.EV_MELTDOWN);
        MAJOR_EVENTS.add(DmrSpec.EV_PHASE2);
        MAJOR_EVENTS.add(DmrSpec.EV_CODE_BLACK);
        MAJOR_EVENTS.add(DmrSpec.EV_IMPLODE);

        // 不设闸的那些：见 REPEATABLE_EVENTS 的注释（重大事件天然在里面）
        REPEATABLE_EVENTS.addAll(MAJOR_EVENTS);
        REPEATABLE_EVENTS.add(DmrSpec.EV_CELL_PULLED);
        REPEATABLE_EVENTS.add(DmrSpec.EV_LASER_LOST);
        REPEATABLE_EVENTS.add(DmrSpec.EV_SHUTDOWN_OK_P1);
        REPEATABLE_EVENTS.add(DmrSpec.EV_SHUTDOWN_OK_P2);

        // 只进 GUI / 只发声的状态类事件，见 QUIET_EVENTS 的注释。
        // 一条条说清楚为什么是"状态"：
        //   sustain_loss      堆芯被热管理压过 230K（点火后必现，见上）
        //   efss_low          消防水位越过 75% 预警线 —— GUI 有水位条
        //   temp_safe         堆芯越过 1120K 安全线 —— GUI 有温度读数
        //   integrity_drop    完整性开始下降 —— 它总是和"超温警告"同一 tick 出现，
        //                     与其连发两条，不如让真正的那条（越过 2300K）说话
        //   fuel_monitor_fail 燃料读数不可信 —— 此时 GUI 上那个数字本身就会标注失效
        QUIET_EVENTS.add(DmrSpec.EV_SUSTAIN_LOSS);
        QUIET_EVENTS.add(DmrSpec.EV_EFSS_LOW);
        QUIET_EVENTS.add(DmrSpec.EV_SAFE_TEMP);
        QUIET_EVENTS.add(DmrSpec.EV_INTEGRITY_DROP);
        QUIET_EVENTS.add(DmrSpec.EV_FUEL_MONITOR_FAIL);
    }

    // ------------------------------------------------------------------ 状态

    /** 零依赖内核。服务端才有；客户端保持 null（读数从快照来） */
    private DmrCore core;
    private final DmrSettings settings = new DmrSettings();

    private boolean structureOk;
    /**
     * 堆芯坐标。<b>一旦认出来就不再清空</b>（"粘性"）。
     * <p>
     * ★ 这一点很关键，而且是踩过才知道的：外壳被拆掉时结构判定会失败，
     * 如果这时把坐标清空，<b>内爆会静默地什么也不做</b> —— 熔毁流程照走、播报照发，
     * 就是不炸。而"拆两块砖让失控的反应堆停下来"恰恰是设计上明确不允许的事。
     * 所以判"还能不能操作"要看 {@link #structureOk}，判"堆芯在哪"看这个字段，两者分开。
     */
    private BlockPos corePos;

    private int structureTimer;
    /** 结构结论"变了"已经连续几轮（见 {@link #STRUCT_CONFIRM_ROUNDS}） */
    private int structConfirm;
    private int syncTimer;
    /**
     * 结构丢失时"再试一次停堆"的冷却（tick）。见 {@link #update} 里那个分支：
     * 停堆前置条件不满足时 {@code beginCooldown()} 会被无限拒绝，不能每 tick 都问一遍。
     */
    private int structureLostTimer;
    /** 上一次推快照时的状态机状态，用来在"状态跳变"时立刻补推一次 */
    private int lastSyncedState = Integer.MIN_VALUE;

    /**
     * 上次播报某句话的时刻。键 = 语言键（+ 参数串，或 + "@玩家id"），
     * 见 {@link #BROADCAST_THROTTLE_TICKS}。用世界时间而不是自己的计数器：
     * 计时源只该有一个，读档后也不会因为字段没存而错乱。
     */
    private final Map<String, Long> lastBroadcastAt = new HashMap<String, Long>();
    /** 上次处理某个内核事件的时刻（键 = 事件键）。声音和聊天共用这道闸，见 {@link #cutDuplicate} */
    private final Map<String, Long> lastEventAt = new HashMap<String, Long>();

    // ------------------------------------------------------------------ 读取

    /** 只给测试/调试用；客户端为 null */
    public DmrCore getCore() {
        return core;
    }

    public boolean isStructureOk() {
        return structureOk;
    }

    public BlockPos getCorePos() {
        return corePos;
    }

    private DmrCore core() {
        if (core == null) {
            core = new DmrCore(settings);
        }
        return core;
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        DmrCore c = core();

        if (++structureTimer >= STRUCTURE_CHECK_INTERVAL) {
            structureTimer = 0;
            validateStructure();
        }

        // ★ 事件永远先排空：结构不成立时玩家点按钮同样会产生事件（比如"点火被拒"），
        //   那些事件必须播出来，否则玩家只会看到按钮没反应、完全不知道为什么。
        handleEvents(c);

        if (structureOk || DmrSpec.isMeltdown(c.getState())) {
            // 熔毁之后即使外壳被拆也要继续跑完 —— 那是台已经失控的机器，
            // 拆两块砖并不能让它停下来，这个"来不及了"的手感是设计的一部分。
            structureLostTimer = 0;
            c.tick();
            handleEvents(c);
            if (c.isChamberLethal()) {
                killChamberOccupants();
            }
            if (c.consumeExplosionPending()) {
                detonate();
                return;
            }
        } else if (c.getState() == DmrSpec.ST_ONLINE || c.getState() == DmrSpec.ST_STARTUP) {
            // 结构被拆掉时堆还在跑：就地安全停机，别留下一个没人管的反应堆。
            // ★「结构失效」这条播报归 validateStructure() 管 —— 那边有"成立→不成立"的边沿
            //   判据，说一次就够。这里<b>曾经</b>也跟着每 tick 播一遍，而停堆前置条件
            //   （温度 &lt; 3500K）不满足时 beginCooldown() 会一直返回 false、状态一直留在
            //   ONLINE，于是这个分支每 tick 都进来 —— 聊天栏每秒二十条，全是同一句。
            //   现在停堆尝试本身也拉到 3 秒一次：内核不用每 tick 空转着 emit 一条"停堆被拒"。
            //   最后一条：这里只让声音响，<b>不</b>往聊天栏推。外壳都已经拆了，
            //   再刷一条"停堆被拒：温度还在 3500K 以上，先去开热管理系统"是纯粹的误导 ——
            //   那个热管理系统正是被拆掉的那个多方块的一部分。玩家听得到机器在挣扎就够了。
            if (structureLostTimer <= 0) {
                structureLostTimer = BROADCAST_THROTTLE_TICKS;
                c.beginCooldown();
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
     * ★★ 结构判定的<b>唯一实现</b>：沿六个轴向各走 2 格找堆芯腔体，返回 {@link #DENY_*}。
     * <p>
     * <b>只读</b>：不碰 {@code structureOk}、不播报。找到的堆芯写进 {@code outCore[0]}
     * （没找到就是 null），顺手刷新 {@code shellMiss} 这个诊断字段。
     * <p>
     * ★★★ 为什么必须提成一份：{@link #validateStructure()} 是<b>去抖</b>的
     * （连续 {@code STRUCT_CONFIRM_ROUNDS} 轮结论一致才认），于是存在一个窗口 ——
     * 最新一轮已经扫到完好结构（{@code structDeny = DENY_NONE}），
     * 但 {@code structureOk} 还停在 false。玩家正好在这个窗口里按「点火」，
     * 旧写法会去读那个缓存的 {@code structDeny} 的兜底分支，报出
     * 「控制柜还没连成多方块结构，先按 tooltip 里的摆法砌好」——
     * 结构明明好好的，玩家照着 tooltip 重砌一遍也没用。
     * <p>
     * ★ 通式：<b>失败提示必须是"此刻现场重探"的结果，不能是上一轮的缓存结论。</b>
     * 缓存只适合用来决定"要不要播报"，不适合用来解释"为什么这一下没成"。
     *
     * @param outCore 长度 ≥1 的数组；找到堆芯时写入 outCore[0]，否则写 null
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
            if (!(world.getBlockState(candidate).getBlock() instanceof BlockDmrCore)) {
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
        // 六个方向都没有堆芯腔体
        return DENY_CORE;
    }

    /**
     * 3×3×3 外壳、正中间一块堆芯腔体，控制柜贴在外壳某面的<b>正中心外侧</b>。
     * <p>
     * 于是"堆芯在哪"和"控制柜在哪"之间只有六种可能：控制柜沿六个轴向各走 2 格。
     * 只查这 6 个候选点就够了，不需要在 5×5×5 里搜。
     * <p>
     * ★ 上下两个方向也要认：机器是立着砌还是躺着砌应该随玩家，不该被朝向卡住。
     */
    private void validateStructure() {
        // ★ 世界都没了就别下结论。曾经这里先 structureOk = false 再判 world，
        //   于是"区块卸载 → 结论被清成失效（但没播报）→ 区块加载回来 → 播一条结构已成立"
        //   白送一对假事件。现在世界不可用就原样保留上一轮的结论。
        if (world == null) {
            return;
        }
        BlockPos[] out = new BlockPos[1];
        int deny = scanStructure(out);
        structDeny = deny;
        // ★ 未加载完不下结论：这一步必须<b>在</b>比较 structureOk 之前返回，
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
        // ★ 连续 STRUCT_CONFIRM_ROUNDS 轮结论一致才认。结构判定会被"玩家手抖放错一格又马上拿回来"
        //   这类一闪而过的扰动带偏 —— 一被带偏就是一对聊天栏消息（失效 + 成立）。
        //   实测一局里六分钟内"失效/成立"来回刷了八次，大半是这么来的。
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
            // ★ 结构失效必须在日志里留痕（带坐标）。实测过一次"聊天栏收到控制柜说结构没成立、
            //   但整份日志里找不到任何结构性线索"—— 只能靠猜。现在至少能查。
            RsAudio.log("[DMR] 结构失效：控制柜 %s，原因 %s", pos, structDenyText());
            broadcast(PREFIX + "structure_lost", EVENT_RANGE);
        } else if (now && canIgniteNow()) {
            // ★ "可以点火了"这句话只在<b>真的能点火</b>的时候说。
            //   曾经无条件播报，于是熔毁倒计时里聊天栏还在喊"多方块结构已成立，可以点火了"——
            //   那时候按点火按钮根本不会有反应，这话纯属误导。
            RsAudio.log("[DMR] 结构成立且点火前置就绪：控制柜 %s", pos);
            broadcast(PREFIX + "structure_ok", EVENT_RANGE);
        } else if (now) {
            // ★ 结构成立、但点火前置没满足 → 不播报（不能说"可以点火了"），
            //   但必须在日志里写清楚"为什么没说"。
            RsAudio.log("[DMR] 结构成立但还不能点火：控制柜 %s，原因 %s", pos,
                    core().ignitionBlockReason());
        }
    }

    /** 结构判定的三种失败原因（决定对玩家说哪一句话） */
    private static final int DENY_NONE = 0;
    private static final int DENY_CORE = 1;
    private static final int DENY_SHELL = 2;
    private static final int DENY_UNLOADED = 3;

    /** 上一次结构判定没通过的原因，见 {@link #DENY_*} */
    private int structDeny = DENY_NONE;
    /** {@link #DENY_SHELL} 时外壳缺几格（取所有候选方向里缺得最少的那个） */
    private int shellMiss = -1;

    /**
     * 结构不成立时该对玩家说的那一句 —— <b>自报成因</b>。
     * <p>
     * ★ 原来永远是同一句"控制柜还没连成多方块结构，先按 tooltip 里的摆法砌好"。
     * 实测过一次：反应堆正在运行、外壳 26 格都在，玩家却收到这句话 ——
     * "没找到堆芯"和"外壳缺几格"是两件完全不同的事，同一句话把两种成因盖住了，
     * 玩家照着 tooltip 重砌一遍也没用（2026-09-21 22:01:44）。
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
                // ★ 兜底<b>不能</b>是"还没连成多方块结构"：走到这里说明 structDeny 是 DENY_NONE，
                //   也就是"结构其实是好的、只是结论还没被确认"。说"没连成结构"就是在说谎。
                //   （真正的四种成因在上面三个 case 里，这一支只在"按早了"时命中。）
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
                return "六个方向 2 格外都没有 DMR 堆芯腔体";
            case DENY_SHELL:
                return "找到堆芯腔体了，但 3×3×3 外壳缺 " + Math.max(0, shellMiss) + " 格";
            case DENY_UNLOADED:
                return "邻近区块还没加载完，读不到外壳";
            default:
                return "未知";
        }
    }

    /**
     * 现在按点火按钮真的会点着吗。
     * <p>
     * ★★★ 判据<b>只有一份</b>，在内核 {@link DmrCore#canIgnite()} 里。
     * 这里<b>曾经</b>写的是自己的一句近似判断（只看"内核处于未点火"），
     * 于是子系统没开齐时，聊天栏刚喊完"多方块结构已成立，可以点火了"，
     * 玩家一点就是"点火被拒：燃料单元、功率激光给水泵、辅助电网、点火钥匙必须全部就位"。
     * 两次实测：21:52:02 喊完 → 21:52:06 被拒；22:00:16 喊完 → 22:00:17 被拒。
     * <p>
     * ★ 通式：<b>两处各写一份"同一件事的前置条件"，只要写的是近似值，播报就一定会说谎。</b>
     */
    private boolean canIgniteNow() {
        return core().canIgnite();
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
     * 玩家听到"还缺 3 格"才知道该去补几块，听到"结构不成立"只能干瞪眼。
     */
    private int shellMissing(BlockPos center) {
        BlockPos min = center.add(-1, -1, -1);
        BlockPos max = center.add(1, 1, 1);
        int miss = 0;
        for (BlockPos p : BlockPos.getAllInBoxMutable(min, max)) {
            if (p.getX() == center.getX() && p.getY() == center.getY() && p.getZ() == center.getZ()) {
                continue;
            }
            if (!(world.getBlockState(p).getBlock() instanceof BlockDmrCasing)) {
                miss++;
            }
        }
        return miss;
    }

    // ------------------------------------------------------------------ 事件

    /** 把内核排出来的事件键变成聊天栏消息 + 声音 */
    private void handleEvents(DmrCore c) {
        handleEvents(c, true);
    }

    /**
     * @param announce 是否往聊天栏播报。传 {@code false} 时只发声音。
     *                 用在"外壳已经被拆掉"那条路径上：那时候机器已经废了，
     *                 内核因为停堆前置条件不满足而排出来的"停堆被拒 / 温度没压下来"
     *                 在聊天栏里看着像是有救 —— 而它提示你去开的那个热管理系统
     *                 本身也已经跟着外壳一起没了。
     */
    private void handleEvents(DmrCore c, boolean announce) {
        List<String> events = c.drainEvents();
        if (events.isEmpty()) {
            return;
        }
        for (String key : events) {
            // ★ 同一件事 3 秒内只说一次：连点按钮会把同一个失败原因 emit 很多遍
            //   （两秒七条就是这么来的），声音也会跟着叠成一片。
            //   但"重复出现即确又发生了一次"的事件不设闸 —— 见 REPEATABLE_EVENTS。
            if (!REPEATABLE_EVENTS.contains(key) && cutDuplicate(lastEventAt, key)) {
                continue;
            }
            // 声音先发：即使某个语言键缺失，该响的也得响
            DmrAudio.onEvent(world, pos, key);

            // ★ 状态类事件到此为止：该响的已经响了，但不往聊天栏推（见 QUIET_EVENTS）。
            //   聊天栏只留给"玩家需要动一下"的事件 —— 状态类的现场在 GUI 的读数里。
            if (QUIET_EVENTS.contains(key)) {
                continue;
            }

            if (DmrSpec.EV_MELTDOWN.equals(key)) {
                // ★ 这里<b>曾经</b>是把停机码直接念到聊天栏里的（"玩家没别的地方能看到它"）。
                //   那正是"反应堆太容易停机"的头号原因：玩家什么都不用做，码自己送上门。
                //   现在按 QSERF 的做法：码写在一张纸上、散落到设施里，聊天栏只提示"去把它找回来"。
                //   谁去找、找了多久、路上会不会被防爆门关在里面 —— 那才是这场事故的玩法。
                spawnCodeNote(c.getShutdownCodeText());
                if (announce) {
                    broadcast(EVENT_TEXT.get(key), EVENT_RANGE_MAJOR);
                    broadcast(PREFIX + "code_note", EVENT_RANGE_MAJOR);
                }
                markDirty();
                continue;
            }

            String langKey = EVENT_TEXT.get(key);
            if (langKey == null) {
                continue;
            }
            if (DmrSpec.EV_ORDER_NEW.equals(key)) {
                if (announce) {
                    broadcast(langKey, EVENT_RANGE, c.getOrderTargetText());
                }
            } else if (DmrSpec.EV_PHASE2.equals(key)) {
                if (announce) {
                    broadcast(langKey, EVENT_RANGE_MAJOR, Integer.valueOf(c.getPhaseTimer() / 20));
                }
            } else if (DmrSpec.EV_CELL_PULLED.equals(key)) {
                if (announce) {
                    broadcast(langKey, EVENT_RANGE_MAJOR,
                            Integer.valueOf(c.getCellPulled()), Integer.valueOf(DmrSpec.FUEL_CELL_COUNT));
                }
            } else if (DmrSpec.EV_SHUTDOWN_OK_P1.equals(key) || DmrSpec.EV_SHUTDOWN_OK_P2.equals(key)
                    || DmrSpec.EV_IMPLODE.equals(key)) {
                // 这三件事之后不该再有音乐接着放（停机成功了还在放融毁曲很奇怪）
                DmrAudio.stopMusic(world, pos);
                if (announce) {
                    broadcast(langKey, EVENT_RANGE_MAJOR);
                }
            } else if (announce) {
                broadcast(langKey, MAJOR_EVENTS.contains(key) ? EVENT_RANGE_MAJOR : EVENT_RANGE);
            }
        }
        markDirty();
    }

    // -------------------------------------------------------------- 停机码便签

    /**
     * 把六位停机码写成一张"散落在设施里的便签"。
     *
     * <h3>为什么用原版纸 + 显示名，而不是新做一个方块</h3>
     * 目的是"让玩家非得跑一趟、还得看得见它"。一张 {@code minecraft:paper} 带上自定义显示名，
     * 就已经具备三件事：掉在地上能看见、捡起来进背包随时能翻、不需要新贴图/新模型。
     * 新做一个"便签方块"要多三份资源，收益却一样。
     * <p>
     * 两个细节是必须的：
     * <ul>
     *   <li>{@code setNoDespawn()}：原版掉落物 5 分钟就没了，而熔毁窗口正好是 5~10 分钟 ——
     *       不设的话会出现"纸刚生成就消失，于是这一局必定救不回来"。</li>
     *   <li>{@code setNoPickupDelay()}：捡起延迟本来是为了防止丢东西立刻捡回来，
     *       这里没这个顾虑，早一秒捡到就早一秒能念码。</li>
     * </ul>
     */
    private void spawnCodeNote(String codeText) {
        if (world == null || world.isRemote) {
            return;
        }
        BlockPos spot = findNoteSpot();
        if (spot == null) {
            RsAudio.warn("DMR 熔毁：找不到能放停机码便签的位置，这一局没有便签");
            return;
        }
        ItemStack stack = new ItemStack(Items.PAPER);
        // 显示名直接用中文字面量：这是服务端路径，走 I18n 在专用服务器上只会拿到 key 本身，
        // 反而更难认。整个模组的界面语言本来就是中文优先。
        stack.setStackDisplayName(TextFormatting.GOLD + "停机码 " + codeText);
        EntityItem item = new EntityItem(world, spot.getX() + 0.5D, spot.getY() + 0.5D,
                spot.getZ() + 0.5D, stack);
        item.setNoDespawn();
        item.setNoPickupDelay();
        world.spawnEntity(item);
        RsAudio.log("DMR 停机码便签已散落到 %s", spot);
    }

    /**
     * 在设施里随便挑一个"站得住、够得着"的位置。
     * <p>
     * 判据故意设得保守（脚下是实心、上面两格是空气），因为要塞进随机地形里 ——
     * 找一个会卡在墙里的位置，玩家就永远看不见那张纸了。
     * 六十次都挑不到就退回控制柜顶上：宁可"就在原地"，也不要这一局根本没有便签。
     */
    private BlockPos findNoteSpot() {
        BlockPos base = corePos != null ? corePos : pos;
        Random rng = world.rand;
        for (int i = 0; i < NOTE_SEARCH_TRIES; i++) {
            int dx = rng.nextInt(NOTE_RADIUS * 2 + 1) - NOTE_RADIUS;
            int dz = rng.nextInt(NOTE_RADIUS * 2 + 1) - NOTE_RADIUS;
            int dy = rng.nextInt(9) - 4;
            BlockPos p = base.add(dx, dy, dz);
            if (!world.isBlockLoaded(p)) {
                continue;
            }
            if (p.getY() < 1 || p.getY() >= world.getActualHeight() - 2) {
                continue;
            }
            if (!world.isAirBlock(p) || !world.isAirBlock(p.up())) {
                continue;
            }
            if (!world.isSideSolid(p.down(), EnumFacing.UP)) {
                continue;
            }
            return p;
        }
        BlockPos fallback = pos.up(2);
        return world.isBlockLoaded(fallback) ? fallback : null;
    }

    /** 玩家是不是站在堆芯腔体里（拔燃料单元必须人真的下去，这是 QSERF 的原设定） */
    private boolean playerAtCore(EntityPlayer player) {
        if (corePos == null || player == null) {
            return false;
        }
        double dx = player.posX - (corePos.getX() + 0.5D);
        double dy = player.posY - (corePos.getY() + 0.5D);
        double dz = player.posZ - (corePos.getZ() + 0.5D);
        return dx * dx + dy * dy + dz * dz <= CHAMBER_REACH_SQ;
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
     * 给"玩家自己点出来的"反馈（点了按钮但被拒，比如结构不成立 / 离堆芯太远）。
     * <p>
     * 和 {@link #broadcast} 用同一张闸门表，但按玩家分开计时 —— 不能让 A 连点把 B 的提示压掉。
     */
    private void tell(EntityPlayer player, String langKey, Object... args) {
        if (world == null || world.isRemote || player == null) {
            return;
        }
        // ★ 去重键要带上玩家 id（+参数）：不能让 A 连点把 B 的提示压掉，
        //   也不能把"外壳缺 3 格"和"外壳缺 5 格"当成同一句话掐掉。
        if (cutDuplicate(lastBroadcastAt, dedupeKey(langKey + "@" + player.getEntityId(), args))) {
            return;
        }
        player.sendStatusMessage(new TextComponentTranslation(langKey, args), false);
    }

    /** 把"这句话 + 这组参数"拼成一个去重键（订单带百分比、拔料带 N/M，都不是同一句话） */
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
     *
     * @param table 闸门表（广播内容表或内核事件表）
     * @param key   去重键
     */
    private boolean cutDuplicate(Map<String, Long> table, String key) {
        long now = world.getTotalWorldTime();
        Long prev = table.get(key);
        // ★ 必须带 now >= prev：换维度/读档之后世界时间有可能比记下的小，
        //   差值变成负数就会永远小于窗口 —— 那会把这台机器的播报<b>永久静音</b>。
        if (prev != null && now >= prev && now - prev < BROADCAST_THROTTLE_TICKS) {
            return true;
        }
        // 带参数的键（订单百分比、N/M 进度）组合数是有限的，但还是设个上限：
        // 闸门短暂失效一次，远好过一张无限长大的表。
        if (table.size() >= THROTTLE_TABLE_MAX) {
            table.clear();
        }
        table.put(key, now);
        return false;
    }

    // ------------------------------------------------------------------ 后果

    /**
     * 点火序列期间腔体内部是致命的。
     * <p>
     * hurtResistantTime 要清零：不给的话一 tick 只能打一次，而实体本身有无敌帧，
     * 玩家有可能穿着好装备硬撑过整个序列 —— 那不叫"微波炉"。
     */
    private void killChamberOccupants() {
        if (corePos == null) {
            return;
        }
        BlockPos min = corePos.add(-1, -1, -1);
        BlockPos max = corePos.add(2, 2, 2);
        AxisAlignedBB box = new AxisAlignedBB(min.getX(), min.getY(), min.getZ(),
                max.getX(), max.getY(), max.getZ());
        List<EntityLivingBase> victims = world.getEntitiesWithinAABB(EntityLivingBase.class, box);
        for (EntityLivingBase victim : victims) {
            victim.hurtResistantTime = 0;
            victim.attackEntityFrom(DamageSource.IN_FIRE, 32767.0F);
        }
    }

    /** 内爆：真炸一个坑。标记在内核里，只会被取到一次 */
    private void detonate() {
        if (corePos == null || world == null) {
            return;
        }
        RsAudio.log("DMR 内爆于 %s", corePos);
        if (settings.explosionEnabled) {
            world.newExplosion((Entity) null,
                    corePos.getX() + 0.5D, corePos.getY() + 0.5D, corePos.getZ() + 0.5D,
                    settings.explosionRadius, true, true);
        }
    }

    // ------------------------------------------------------------------ 玩家操作

    /**
     * 服务端权威入口：处理界面发来的一个动作。
     * <p>
     * 参数一律当不可信（动作号越界、停机码长度不对、子系统位不在掩码里），
     * 全部在这里挡掉 —— 客户端那段代码是玩家可以改的。
     */
    public void handleAction(EntityPlayer player, int action, int arg) {
        if (world == null || world.isRemote) {
            return;
        }
        DmrCore c = core();
        switch (action) {
            case PacketDmrAction.ACT_TOGGLE_SUB:
                if ((arg & DmrSpec.SUB_ALL) == 0) {
                    return;
                }
                c.toggleSubsystem(arg);
                break;
            case PacketDmrAction.ACT_SET_LOAD:
                c.setLoadPct(arg);
                break;
            case PacketDmrAction.ACT_IGNITE:
                if (!structureOk) {
                    // ★★★ 自报成因，而且必须是<b>此刻现场重探</b>的成因。
                    //   不能读缓存的 structDeny：validateStructure 是去抖的，存在一个窗口 ——
                    //   最新一轮已经扫到完好结构（structDeny = DENY_NONE），但 structureOk 还停在
                    //   false。玩家正好在这个窗口里按「点火」，旧写法就会报
                    //   「控制柜还没连成多方块结构，先按 tooltip 里的摆法砌好」——
                    //   结构明明好好的，这句话纯属误导（实测 2026-09-21 22:01:44，
                    //   当时反应堆正在运行、功率订单已经到 84%）。
                    BlockPos[] out = new BlockPos[1];
                    int deny = scanStructure(out);
                    if (deny == DENY_NONE) {
                        // 结构其实是好的，只是"结论"还没落地 —— 这一按按早了。
                        // 说清楚"再按一次"，绝不谎报结构没连成。
                        RsAudio.log("[DMR] 点火被拒（结论未落定）：控制柜 %s，现场重探结构完好，"
                                + "等去抖确认后再按", pos);
                        tell(player, PREFIX + "struct_pending");
                        return;
                    }
                    structDeny = deny;
                    // 用 tell（带闸门）：连点「点火」不该把这条刷成一长串。
                    RsAudio.log("[DMR] 点火被拒（结构不成立）：控制柜 %s，%s", pos, structDenyText());
                    tell(player, structDenyKey(), structDenyArg());
                    return;
                }
                // 失败原因由内核发事件说明（子系统没齐 / 没燃料）
                c.pressIgnition();
                break;
            case PacketDmrAction.ACT_COOLDOWN:
                c.beginCooldown();
                break;
            case PacketDmrAction.ACT_ENTER_MAINT:
                c.enterMaintenance();
                break;
            case PacketDmrAction.ACT_INSTALL_CELL:
                c.installFuelCell();
                break;
            case PacketDmrAction.ACT_EXIT_MAINT:
                c.exitMaintenance();
                break;
            case PacketDmrAction.ACT_SUBMIT_CODE:
                if (arg < DmrSpec.SHUTDOWN_CODE_MIN || arg > DmrSpec.SHUTDOWN_CODE_MAX) {
                    return;
                }
                c.submitShutdownCode(arg);
                break;
            case PacketDmrAction.ACT_REPAIR_LASERS:
                c.repairLasers();
                break;
            case PacketDmrAction.ACT_PULL_CELL:
                // ★ 拔燃料单元必须人真的站在堆芯腔体里 —— 这是 QSERF 的原始设定
                //   （"you must go to DMR... remove three fuel cells"），也是这条
                //   抢救通道唯一的代价：控制室里按按钮是拔不下来的。
                if (!playerAtCore(player)) {
                    tell(player, PREFIX + "pull_too_far");
                    return;
                }
                c.pullFuelCell(player.getEntityId());
                break;
            default:
                return;
        }
        handleEvents(c);
        markDirty();
        // 立刻回推一次：玩家点了按钮不用等下一个同步周期才看到变化
        sendStateTo(player);
    }

    /** 控制柜被拆掉：运行中的堆就地安全停机 */
    public void onControllerBroken() {
        if (core == null || world == null || world.isRemote) {
            return;
        }
        int state = core.getState();
        if (state == DmrSpec.ST_ONLINE || state == DmrSpec.ST_STARTUP) {
            core.beginCooldown();
        }
    }

    // ------------------------------------------------------------------ 同步

    private void syncLit(DmrCore c) {
        int state = c.getState();
        boolean lit = state != DmrSpec.ST_OFFLINE && state != DmrSpec.ST_SAFE
                && state != DmrSpec.ST_IMPLODED;
        BlockDmrController.setLit(world, pos, lit);

        // ★ 顺带把堆芯的视觉状态也同步过去 —— 等离子体的亮度和"熔毁时抖动"
        //   都靠那两个方块属性（lit / meltdown）驱动。
        //   corePos 是 validateStructure() 找到的；结构还没成立时它是 null，
        //   这时什么都没得同步，直接跳过。
        if (corePos != null && world.isBlockLoaded(corePos)) {
            boolean meltdown = DmrSpec.isMeltdown(state);
            BlockDmrCore.setState(world, corePos, lit, meltdown);
        }
    }

    private void pushSync(DmrCore c) {
        syncTimer++;
        int state = c.getState();
        boolean busy = state != DmrSpec.ST_OFFLINE && state != DmrSpec.ST_SAFE
                && state != DmrSpec.ST_IMPLODED;
        int interval = busy ? SYNC_INTERVAL_BUSY : SYNC_INTERVAL_IDLE;
        boolean stateChanged = state != lastSyncedState;
        if (!stateChanged && syncTimer < interval) {
            return;
        }
        syncTimer = 0;
        lastSyncedState = state;

        DmrSnapshot snapshot = DmrSnapshot.capture(c, structureOk);
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
                    PacketHandler.INSTANCE.sendTo(new PacketDmrSync(pos, snapshot), (EntityPlayerMP) player);
                }
            } catch (Throwable t) {
                // 单个玩家掉线不该影响其他人
            }
        }
    }

    /** 单独给某个玩家推一次（开界面/点了按钮时用） */
    public void sendStateTo(EntityPlayer player) {
        if (world == null || world.isRemote || !(player instanceof EntityPlayerMP) || core == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendTo(
                    new PacketDmrSync(pos, DmrSnapshot.capture(core, structureOk)), (EntityPlayerMP) player);
        } catch (Throwable t) {
            // 同上
        }
    }

    // ------------------------------------------------------------------ 存档

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (core == null) {
            core = new DmrCore(settings);
        }
        // 长度不对时 loadState 会整份丢弃 —— 半份状态比一台全新的机器更危险
        core.loadState(compound.getIntArray("Dmr"));
        structureOk = compound.getBoolean("StructOk");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        if (core != null) {
            compound.setIntArray("Dmr", core.saveState());
        }
        compound.setBoolean("StructOk", structureOk);
        return compound;
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

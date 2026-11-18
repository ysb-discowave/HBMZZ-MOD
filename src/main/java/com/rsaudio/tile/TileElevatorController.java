package com.rsaudio.tile;

import com.rsaudio.ModBlocks;
import com.rsaudio.block.BlockElevatorController;
import com.rsaudio.block.BlockElevatorDoor;
import com.rsaudio.elevator.ElevatorCore;
import com.rsaudio.elevator.ElevatorSettings;
import com.rsaudio.elevator.ElevatorSpec;
import com.rsaudio.network.ElevatorSnapshot;
import com.rsaudio.network.PacketElevatorSync;
import com.rsaudio.network.PacketHandler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 电梯控制柜的方块实体：<b>整台电梯唯一的服务端权威</b>。
 * <p>
 * 它同时管三件事，而且这三件事的顺序不能换：
 * <ol>
 *   <li><b>认路</b>：定期扫井道（{@link #scanShaft()}），把"有哪些层"灌给内核
 *       （{@link ElevatorCore#setShaft}）。</li>
 *   <li><b>推进</b>：{@link ElevatorCore#tick()}，然后 {@link #handleEvents()} 把事件念出去。</li>
 *   <li><b>落世界</b>：{@link #applyCells()} 按内核的整数索引把轿厢方块搬到新位置、
 *       顺路把乘客一起搬；{@link #applyDoors()} 开关层门。</li>
 * </ol>
 *
 * <h3>★★★ 轿厢 = 一组真实方块（不是实体）</h3>
 * 见 {@link ElevatorSpec} 的类注释。这里只补一条实现上的要点：
 * {@link #applyCells()} 里<b>先把归属表换成新位置，再动世界</b>。
 * 反过来的话，收回旧格子时 {@link com.rsaudio.block.BlockElevatorCar#breakBlock}
 * 会问"这一格属于轿厢吗"，而那时归属表还指着旧位置 → 答"是" → 被当成玩家拆轿厢
 * → 触发 {@link #onCarBlockBroken}。表现是<b>电梯每动一格就自己报一次"轿厢被拆"</b>。
 * 和防爆门是同一条教训（"消失侧第一步必须现场重探，而且用同一把尺子"）。
 *
 * <h3>★★ 乘客是"整格搬"</h3>
 * 轿厢方块只能落在整数索引上，所以乘客也跟着整格搬（见 {@link #moveRiders}）。
 * 不这么做的话乘客会半程悬在轿厢上方的空气里，被重力每刻往下拽、下一 tick 又被抬起，
 * 表现是<b>人在轿厢里持续抖动</b>，而且看代码完全看不出问题。
 *
 * <h3>★ 输出分级（本项目的老规矩）</h3>
 * 聊天栏只放"需要玩家动一下"的：到了哪层、出现什么故障、呼叫被拒。
 * "出发/门开/门关"这类只进界面与动作栏（见 {@link #QUIET_EVENTS}）。
 * 降噪只做在这一层 —— 内核只管"发生了什么"。
 */
public class TileElevatorController extends TileEntity implements ITickable {

    // ------------------------------------------------------------------ 节奏
    /** 多久复检一次井道（ticks）。★ 井道是玩家砌的，塌了不会通知我们 */
    private static final int SHAFT_CHECK_INTERVAL = ElevatorSettings.SHAFT_CHECK_INTERVAL;
    /** 井道结论去抖：连续几轮一致才认（复用 DMR/F9 的约定） */
    private static final int SHAFT_CONFIRM_ROUNDS = ElevatorSettings.SHAFT_CONFIRM_ROUNDS;
    /** 有呼叫 / 有故障时推快照的间隔 */
    private static final int SYNC_INTERVAL_BUSY = 3;
    /** 静止待机时推快照的间隔 */
    private static final int SYNC_INTERVAL_IDLE = 20;
    /** 推快照的半径 */
    private static final double SYNC_RANGE = 96.0D;
    /** 念事件的半径 */
    private static final double EVENT_RANGE = 48.0D;
    /** 同一句话的最短重复间隔（ticks） */
    private static final int BROADCAST_THROTTLE_TICKS = 60;

    /** 结构类文案前缀（"井道成立了"这种，不由内核 emit） */
    private static final String PREFIX = "chat.rsaudio.elevator.";
    /** 内核事件文案前缀 */
    private static final String PREFIX_EVENT = "chat.rsaudio.elevator.event.";

    /**
     * 事件号 → 文案键。<b>由 {@link ElevatorSpec#eventName} 生成</b>，不手写。
     * ★ 手写过一次的下场（DMR/F9 都栽过）：清单停在旧条数，新增的事件全在清单外 →
     * 校验放行 → 客户端聊天栏里直接露出 {@code chat.rsaudio.elevator.event.xxx} 这种原始键名。
     * 所以这里保证"事件号集合和 lang 键集合永远一一对应"，
     * 并且 {@code verify_jar.py} 的 [2d] 段会拿源码和 lang 做双向比对。
     */
    private static final Map<Integer, String> EVENT_TEXT = new HashMap<Integer, String>();

    static {
        for (int e = 0; e < ElevatorSpec.EV_COUNT; e++) {
            EVENT_TEXT.put(e, PREFIX_EVENT + ElevatorSpec.eventName(e));
        }
    }

    /**
     * 静音事件：只进 GUI / 只发声，<b>不进聊天栏</b>。
     * ★ "出发"和"门开合"在连续运行时每几秒一次，全念出来就是刷屏。
     *   它们对玩家没有"需要动一下"的含义，所以按老规矩降级。
     */
    private static final Set<Integer> QUIET_EVENTS = new HashSet<Integer>();

    static {
        QUIET_EVENTS.add(ElevatorSpec.EV_DEPART);
        QUIET_EVENTS.add(ElevatorSpec.EV_DOOR_OPENING);
        QUIET_EVENTS.add(ElevatorSpec.EV_DOOR_CLOSING);
        QUIET_EVENTS.add(ElevatorSpec.EV_CALL);
    }

    // ------------------------------------------------------------------ 状态
    private int type;

    private ElevatorCore core;

    /** 控制柜的 Y 就是井道最低层：路径索引 0 = {@code pos.getY()} */
    private int shaftY0;
    /** 井道中心（X/Z）；Y 字段无意义 */
    private BlockPos shaftCenter = BlockPos.ORIGIN;
    /** 上一次扫出来的层数（用于"层数变了"的文案） */
    private int lastFloorCount;

    /** 世界此刻反映的是哪个整数索引的轿厢位置。{@code Integer.MIN_VALUE} = 还没摆过 */
    private int appliedIndex = Integer.MIN_VALUE;
    /** 当前属于轿厢的格子（{@link #ownsCell} 的唯一依据） */
    private final List<BlockPos> carCells = new ArrayList<BlockPos>();

    /** 当前开着门的层下标；-1 = 门都关着 */
    private int openedSlot = -1;

    private int scanTimer;
    private int syncTimer;
    private int confirmRounds;
    private boolean lastStructureOk;

    /** 去重播报用：键 → 最近一次播报的刻 */
    private final Map<String, Long> lastBroadcast = new HashMap<String, Long>();

    /** 自检诊断（只打一次，避免刷日志） */
    private final Set<String> diagnosed = new HashSet<String>();

    // ================================================================== 构造 / 类型

    public TileElevatorController() {
        this.type = ElevatorSpec.TYPE_PASSENGER;
    }

    /**
     * 由方块在创建方块实体时灌入。
     * ★ 这是"我是哪一种"的权威来源；读档时 NBT 里的 type 也走这里。
     */
    public void setType(int type) {
        int t = (type < 0 || type >= ElevatorSpec.TYPE_COUNT) ? ElevatorSpec.TYPE_PASSENGER : type;
        if (this.type != t || this.core == null) {
            this.type = t;
            this.core = new ElevatorCore(t);
            this.appliedIndex = Integer.MIN_VALUE;
        }
    }

    public int getTypeIndex() {
        return type;
    }

    /**
     * 从世界复核"我是哪一种"。
     * <p>
     * ★★ 为什么必须有这一句：方块实体的 {@code createNewTileEntity(world, meta)} 那个老签名
     * <b>拿不到方块状态</b>，只能给一个默认类型。Forge 1.12.2 正常走的是
     * {@code createTileEntity(world, state)}（能认出三种控制柜），但存档路径 / 其他模组
     * 的放置路径可能落到老签名上 —— 那时电梯会以"客运"的身份醒来，
     * 而它其实是货运（轿厢尺寸差一格）。<b>症状是轿厢只占 3×3 却报 5×5 的层门</b>，
     * 非常难查。所以每 tick 花一次方块查询把它钉死。
     */
    private void ensureTypeFromWorld() {
        if (world == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockElevatorController) {
            int t = ((BlockElevatorController) state.getBlock()).getTypeIndex();
            if (t != type || core == null) {
                setType(t);
            }
        }
    }

    private ElevatorCore core() {
        if (core == null) {
            core = new ElevatorCore(type);
        }
        return core;
    }

    // ================================================================== 主循环

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        ensureTypeFromWorld();
        ElevatorCore c = core();

        if (scanTimer-- <= 0) {
            scanTimer = SHAFT_CHECK_INTERVAL;
            scanShaft();
        }

        // ★ 事件由"外部动作"产生（玩家按呼叫钮 → handleAction → call()），
        //   而 tick() 故意不清事件队列（见 ElevatorCore.tick 的注释）。
        //   所以这里的顺序是：先 tick，再取事件，最后 clearEvents。
        c.tick();
        handleEvents();

        applyCells();
        applyDoors();

        BlockElevatorController.setLit(world, pos,
                lastStructureOk && c.fault() == ElevatorSpec.FAULT_NONE);

        if (syncTimer-- <= 0) {
            boolean busy = c.callCount() > 0 || c.isMoving() || c.fault() != ElevatorSpec.FAULT_NONE
                    || c.state() != ElevatorSpec.ST_IDLE;
            syncTimer = busy ? SYNC_INTERVAL_BUSY : SYNC_INTERVAL_IDLE;
            pushSync();
        }
    }

    // ================================================================== 认路

    private EnumFacing facing() {
        if (world == null) {
            return EnumFacing.NORTH;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockElevatorController) {
            return state.getValue(BlockElevatorController.FACING);
        }
        return EnumFacing.NORTH;
    }

    /**
     * 扫井道并把结论灌给内核。
     * <p>
     * ★★★ 井道中心 = 控制柜沿<b>正面</b>方向再走 {@code 轿厢半径 + 1} 格。
     * 那个 "+1" 不是凑数：轿厢占据的是以中心为原点的 N×N 方形，控制柜必须站在那个方形
     * <b>外面</b>，否则货运电梯（5×5）的控制柜会正好落在轿厢要占的格子里 ——
     * 表现是"轿厢一开动就把控制柜吃掉了"。
     */
    private void scanShaft() {
        ElevatorCore c = core();
        shaftY0 = pos.getY();
        EnumFacing f = facing();
        int r = ElevatorSettings.carRadius(type);
        // ★★ 1.12.2 里 EnumFacing 的偏移取值方法叫 getFrontOffsetX()/getFrontOffsetZ()。
        //    写成 1.16+ 的 getXOffset()/getZOffset() 是**编译期**就报错的 ——
        //    这是好事（它不会静默），但很容易在跨版本抄代码时带进来。
        int cx = pos.getX() + f.getFrontOffsetX() * (r + 1);
        int cz = pos.getZ() + f.getFrontOffsetZ() * (r + 1);
        shaftCenter = new BlockPos(cx, shaftY0, cz);

        // ---- 1) 沿台阶/竖列往上找井道顶
        int top = -1;
        for (int k = 0; k <= ElevatorSpec.MAX_SHAFT_SCAN; k++) {
            if (!columnOk(cx, cz, k)) {
                break;
            }
            top = k;
        }

        if (top < 0) {
            // 连自己这一层都不是井道 —— 这是"找不到井道"，和"井道里没有层门"是两回事，
            // 必须分开报（玩家要修的地方完全不同）。
            c.setShaftFound(false);
            c.setShaft(new int[0], 0, 0, 0);
            onStructureChanged(false, 0);
            return;
        }

        // ---- 2) 收层：某层的"井道外围一圈"里有层门，就算一层
        List<Integer> idxList = new ArrayList<Integer>();
        List<List<BlockPos>> doorList = new ArrayList<List<BlockPos>>();
        int missing = 0;
        for (int k = 0; k <= top; k++) {
            List<BlockPos> doors = findDoors(cx, cz, k, r);
            if (!doors.isEmpty()) {
                idxList.add(k);
                doorList.add(doors);
            }
            // 这一层的井道格被别的东西占了（玩家往井道里砌了机器）→ 轿厢到不了 → 算"缺"
            if (!footprintOk(cx, cz, k, r)) {
                missing++;
            }
        }
        // 井道顶<b>上面</b>还有层门 → 那几层够不着，也算"缺"
        for (int k = top + 1; k <= top + 4; k++) {
            if (!findDoors(cx, cz, k, r).isEmpty()) {
                missing++;
            }
        }

        // ---- 3) 去抖：结论连续 SHAFT_CONFIRM_ROUNDS 轮一致才认
        //   ★ 玩家正在一格一格砌井道时，中间态一定不成立。不去抖就会疯狂刷
        //     "故障 / 恢复"两条消息（本项目在 DMR 的结构判定上实锤过）。
        int[] floors = new int[idxList.size()];
        for (int i = 0; i < floors.length; i++) {
            floors[i] = idxList.get(i);
        }
        boolean same = (floors.length == c.floorCount());
        if (same) {
            for (int i = 0; i < floors.length; i++) {
                if (c.floorIndex(i) != floors[i]) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            confirmRounds++;
            if (confirmRounds < SHAFT_CONFIRM_ROUNDS) {
                return;                       // 等下一轮再确认，这一轮不下结论
            }
        }
        confirmRounds = 0;

        c.setShaftFound(true);
        boolean changed = c.setShaft(floors, 0, top, missing);
        if (changed) {
            doorCache.clear();
            doorCache.putAll(buildDoorCache(doorList));
        }
        onStructureChanged(true, floors.length);
    }

    /** 层下标 → 该层的层门坐标。{@link #applyDoors()} 与 {@link #callAtDoor} 都读它 */
    private final Map<Integer, List<BlockPos>> doorCache = new HashMap<Integer, List<BlockPos>>();

    private Map<Integer, List<BlockPos>> buildDoorCache(List<List<BlockPos>> doorList) {
        Map<Integer, List<BlockPos>> out = new HashMap<Integer, List<BlockPos>>();
        ElevatorCore c = core();
        for (int i = 0; i < doorList.size() && i < c.floorCount(); i++) {
            // 层的下标 i 对应层表第 i 项；层表就是按 idxList 顺序建的，所以下标一致
            out.put(i, doorList.get(i));
        }
        return out;
    }

    /** 这一格（在路径索引 k 处）是不是井道的一部分：井道方块或轿厢方块都算 */
    private boolean columnOk(int cx, int cz, int k) {
        int dx = (int) Math.round(ElevatorSpec.axisStep(type, 0, k));
        int dz = (int) Math.round(ElevatorSpec.axisStep(type, 2, k));
        IBlockState s = world.getBlockState(new BlockPos(cx + dx, shaftY0 + k, cz + dz));
        return s.getBlock() == ModBlocks.ELEVATOR_SHAFT || s.getBlock() == ModBlocks.ELEVATOR_CAR;
    }

    /** 第 k 层的整个 N×N 井道格都是井道/轿厢（有一格被占住就说明轿厢停不进去） */
    private boolean footprintOk(int cx, int cz, int k, int r) {
        int dx = (int) Math.round(ElevatorSpec.axisStep(type, 0, k));
        int dz = (int) Math.round(ElevatorSpec.axisStep(type, 2, k));
        int y = shaftY0 + k;
        for (int ax = -r; ax <= r; ax++) {
            for (int az = -r; az <= r; az++) {
                IBlockState s = world.getBlockState(new BlockPos(cx + dx + ax, y, cz + dz + az));
                if (s.getBlock() != ModBlocks.ELEVATOR_SHAFT && s.getBlock() != ModBlocks.ELEVATOR_CAR) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 第 k 层"井道外围一圈"里的层门。★ 只认外围：摆在井道格里会把井道堵掉 */
    private List<BlockPos> findDoors(int cx, int cz, int k, int r) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        int dx = (int) Math.round(ElevatorSpec.axisStep(type, 0, k));
        int dz = (int) Math.round(ElevatorSpec.axisStep(type, 2, k));
        int y = shaftY0 + k;
        for (int ax = -r - 1; ax <= r + 1; ax++) {
            for (int az = -r - 1; az <= r + 1; az++) {
                boolean onRing = (Math.abs(ax) > r) || (Math.abs(az) > r);
                if (!onRing) {
                    continue;
                }
                BlockPos p = new BlockPos(cx + dx + ax, y, cz + dz + az);
                if (world.getBlockState(p).getBlock() instanceof BlockElevatorDoor) {
                    out.add(p);
                    if (out.size() >= ElevatorSpec.MAX_DOORS_PER_FLOOR) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    private void onStructureChanged(boolean ok, int floorCount) {
        if (ok == lastStructureOk && floorCount == lastFloorCount) {
            return;
        }
        lastStructureOk = ok;
        if (ok && floorCount > 0) {
            broadcast(PREFIX + "shaft_ok", "");
        } else if (!ok) {
            broadcast(PREFIX + "shaft_lost", "");
        }
        lastFloorCount = floorCount;
    }

    // ================================================================== 落世界：轿厢

    /**
     * 把内核的整数索引落成方块，并顺路把乘客搬走。
     * <p>
     * ★★ 顺序是刻意的：<b>先换归属表，再动世界</b>（见类注释）。
     */
    private void applyCells() {
        ElevatorCore c = core();
        if (!lastStructureOk && appliedIndex == Integer.MIN_VALUE) {
            return;
        }
        int want = c.cellIndex();
        int old = appliedIndex;
        if (want == old) {
            return;
        }

        List<BlockPos> oldCells = new ArrayList<BlockPos>(carCells);
        List<BlockPos> newCells = cellsOf(want);

        // ★★★ 先换归属：这一步之后，收回旧格子时 breakBlock 问 ownsCell 会得到"否"，
        //   于是它知道自己只是电梯在搬迁，不会误报"轿厢被拆"。
        appliedIndex = want;
        carCells.clear();
        carCells.addAll(newCells);

        // 先搬乘客（用旧位置的体积去找人），再动方块 —— 反过来的话
        // "站在轿厢上的人"会因为轿厢已经搬走而扫不到。
        if (old != Integer.MIN_VALUE) {
            moveRiders(old, want);
        }

        Set<Long> newSet = keySet(newCells);
        Set<Long> oldSet = keySet(oldCells);
        for (BlockPos p : oldCells) {
            if (!newSet.contains(encode(p))) {
                restoreCell(p);
            }
        }
        for (BlockPos p : newCells) {
            if (!oldSet.contains(encode(p))) {
                placeCarCell(p);
            }
        }
        if (world != null) {
            world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 3);
        }
    }

    /** 第 index 层时，轿厢占据的全部格子（平台 + 四角立柱） */
    public List<BlockPos> cellsOf(int index) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        int r = ElevatorSettings.carRadius(type);
        int h = ElevatorSettings.carHeight(type);
        int dx = (int) Math.round(ElevatorSpec.axisStep(type, 0, index));
        int dz = (int) Math.round(ElevatorSpec.axisStep(type, 2, index));
        int cx = shaftCenter.getX() + dx;
        int cz = shaftCenter.getZ() + dz;
        int cy = shaftY0 + index;
        for (int ax = -r; ax <= r; ax++) {
            for (int az = -r; az <= r; az++) {
                out.add(new BlockPos(cx + ax, cy, cz + az));
            }
        }
        for (int k = 1; k < h; k++) {
            out.add(new BlockPos(cx - r, cy + k, cz - r));
            out.add(new BlockPos(cx + r, cy + k, cz - r));
            out.add(new BlockPos(cx - r, cy + k, cz + r));
            out.add(new BlockPos(cx + r, cy + k, cz + r));
        }
        return out;
    }

    private void placeCarCell(BlockPos p) {
        IBlockState s = world.getBlockState(p);
        if (s.getBlock() == ModBlocks.ELEVATOR_CAR) {
            return;
        }
        // ★ 只覆盖井道和空气：轿厢撞上别的东西（机器、水）时宁可留着那东西，
        //   让它去"井道被占住"那条故障里报出来 —— 直接覆盖会静默删掉玩家的东西。
        if (s.getBlock() == ModBlocks.ELEVATOR_SHAFT || s.getBlock().isAir(s, world, p)) {
            world.setBlockState(p, ModBlocks.ELEVATOR_CAR.getDefaultState(), 3);
        }
    }

    private void restoreCell(BlockPos p) {
        if (world.getBlockState(p).getBlock() == ModBlocks.ELEVATOR_CAR) {
            world.setBlockState(p, ModBlocks.ELEVATOR_SHAFT.getDefaultState(), 3);
        }
    }

    /**
     * 把站在轿厢上的实体连同轿厢一起搬一格。
     * <p>
     * ★★ 只搬"没骑在别人身上的"根实体，乘客跟着走由原版 {@code updateRidden} 负责 ——
     * 全都自己搬的话，马上的骑手会被搬两次（飞出去）。
     * <p>
     * ★★ 顺手清 {@code fallDistance} 与 {@code motionY}：不清的话电梯下行会被算成
     * "自由落体了几十格"，玩家一到站就掉血。
     */
    private void moveRiders(int oldIndex, int newIndex) {
        double dx = ElevatorSpec.axisStep(type, 0, newIndex) - ElevatorSpec.axisStep(type, 0, oldIndex);
        double dy = ElevatorSpec.axisStep(type, 1, newIndex) - ElevatorSpec.axisStep(type, 1, oldIndex);
        double dz = ElevatorSpec.axisStep(type, 2, newIndex) - ElevatorSpec.axisStep(type, 2, oldIndex);
        if (dx == 0.0D && dy == 0.0D && dz == 0.0D) {
            return;
        }
        AxisAlignedBB box = carVolume(oldIndex);
        List<Entity> found = world.getEntitiesWithinAABB(Entity.class, box);
        for (Entity e : found) {
            if (e.isRiding()) {
                continue;                      // 骑手由它的坐骑带着走，别搬两次
            }
            e.setPosition(e.posX + dx, e.posY + dy, e.posZ + dz);
            e.motionY = 0.0D;
            if (ElevatorSettings.RIDE_CLEAR_FALL_DISTANCE) {
                e.fallDistance = 0.0F;
            }
            for (Entity p : e.getRecursivePassengers()) {
                p.setPosition(p.posX + dx, p.posY + dy, p.posZ + dz);
                p.motionY = 0.0D;
                if (ElevatorSettings.RIDE_CLEAR_FALL_DISTANCE) {
                    p.fallDistance = 0.0F;
                }
            }
        }
    }

    /** 第 index 层时"轿厢那一块空间"（用来找人）。★ 判定盒与方块层共用 {@link #cellsOf} 的换算 */
    private AxisAlignedBB carVolume(int index) {
        int r = ElevatorSettings.carRadius(type);
        int h = ElevatorSettings.carHeight(type);
        int dx = (int) Math.round(ElevatorSpec.axisStep(type, 0, index));
        int dz = (int) Math.round(ElevatorSpec.axisStep(type, 2, index));
        int cx = shaftCenter.getX() + dx;
        int cz = shaftCenter.getZ() + dz;
        int cy = shaftY0 + index;
        return new AxisAlignedBB(
                cx - r, cy - ElevatorSettings.RIDE_FOOT_TOLERANCE, cz - r,
                cx + r + 1.0D, cy + h + ElevatorSettings.RIDE_HEAD_MARGIN, cz + r + 1.0D);
    }

    /**
     * 这一格此刻是不是轿厢的一部分。
     * <p>
     * ★★★ 这是 {@code BlockElevatorCar#breakBlock} 的<b>唯一判据</b>，
     * 也就是"这一格本来就该消失吗"那把尺子。它必须和 {@link #applyCells()} 用同一份表
     * （{@link #carCells}），否则电梯会自己拆自己（见类注释）。
     */
    public boolean ownsCell(BlockPos p) {
        if (p == null) {
            return false;
        }
        // 先比整数索引，绝大多数查询在不命中时一次比较就出结果
        if (appliedIndex != Integer.MIN_VALUE && p.getY() != shaftY0 + appliedIndex
                && p.getY() != shaftY0 + appliedIndex + 1) {
            // 立柱在 appliedIndex+1..+h-1，所以只快筛掉明显不可能的 Y
            int h = ElevatorSettings.carHeight(type);
            if (p.getY() < shaftY0 + appliedIndex || p.getY() >= shaftY0 + appliedIndex + h) {
                return false;
            }
        }
        return keySet(carCells).contains(encode(p));
    }

    /**
     * 轿厢格被拆（玩家挖 / 爆炸）。<b>必须留痕</b> —— 不留痕的话"我的轿厢少了一块"
     * 在日志里一个字都没有，只能靠猜。留痕之后自我修复：下一 tick 会按当前位置补回。
     */
    public void onCarBlockBroken(BlockPos p) {
        if (world == null || world.isRemote) {
            return;
        }
        diag("car-broken:" + p, "[电梯] 轿厢格 " + p.getX() + "," + p.getY() + "," + p.getZ()
                + " 被拆（类型=" + ElevatorSpec.typeName(type) + "），已按当前位置补回");
        appliedIndex = Integer.MIN_VALUE;      // 强制下一 tick 重新落一遍格子
        carCells.clear();
    }

    // ================================================================== 落世界：门

    private void applyDoors() {
        ElevatorCore c = core();
        int slot = c.currentSlot();
        boolean want = (c.state() == ElevatorSpec.ST_DOOR_OPENING
                || c.state() == ElevatorSpec.ST_DOOR_OPEN) && slot >= 0;
        if (want && slot != openedSlot) {
            if (openedSlot >= 0) {
                setDoorsOpen(openedSlot, false);
            }
            setDoorsOpen(slot, true);
            openedSlot = slot;
        } else if (!want && openedSlot >= 0) {
            setDoorsOpen(openedSlot, false);
            openedSlot = -1;
        }
    }

    private void setDoorsOpen(int slot, boolean open) {
        List<BlockPos> doors = doorCache.get(slot);
        if (doors == null) {
            return;
        }
        for (BlockPos p : doors) {
            BlockElevatorDoor.setOpen(world, p, open);
        }
    }

    private void closeAllDoors() {
        for (Map.Entry<Integer, List<BlockPos>> e : doorCache.entrySet()) {
            for (BlockPos p : e.getValue()) {
                BlockElevatorDoor.setOpen(world, p, false);
            }
        }
        openedSlot = -1;
    }

    // ================================================================== 广播

    /** 只打一次的诊断出口。★ 不是"每 tick 打一次"——那会把日志刷爆 */
    private void diag(String key, String line) {
        if (diagnosed.add(key)) {
            System.out.println(line);
        }
    }

    /**
     * 念一句话（带去重）。
     * <p>
     * ★★ 去重必须写成 {@code now >= prev && now - prev < WINDOW}：
     * 只写 {@code now - prev < WINDOW} 的话，一旦时间回退（换世界 / 服务器重启），
     * 差值变负 → 恒小于窗口 → <b>永久静音</b>。本项目踩过这个坑。
     */
    private void broadcast(String langKey, String arg) {
        long now = world.getTotalWorldTime();
        Long prev = lastBroadcast.get(langKey);
        if (prev != null && now >= prev && now - prev < BROADCAST_THROTTLE_TICKS) {
            return;
        }
        lastBroadcast.put(langKey, now);
        TextComponentTranslation msg = (arg == null || arg.isEmpty())
                ? new TextComponentTranslation(langKey)
                : new TextComponentTranslation(langKey, arg);
        for (EntityPlayer p : world.getEntitiesWithinAABB(EntityPlayer.class,
                new AxisAlignedBB(pos).grow(EVENT_RANGE))) {
            p.sendStatusMessage(msg, false);
        }
    }

    private void broadcastActionBar(EntityPlayer player, String langKey, String arg) {
        if (player == null) {
            return;
        }
        TextComponentTranslation msg = (arg == null || arg.isEmpty())
                ? new TextComponentTranslation(langKey)
                : new TextComponentTranslation(langKey, arg);
        player.sendStatusMessage(msg, true);
    }

    private void handleEvents() {
        ElevatorCore c = core();
        int n = c.eventCount();
        for (int i = 0; i < n; i++) {
            int code = c.eventCode(i);
            int arg = c.eventArg(i);
            if (code == ElevatorSpec.EV_FAULT) {
                // ★ 故障必须自报成因：直接念"是哪种故障"，不是一句笼统的"出故障了"。
                broadcast(PREFIX + "fault." + ElevatorSpec.faultName(arg), "");
                continue;
            }
            if (code == ElevatorSpec.EV_REPAIRED) {
                broadcast(PREFIX + "repaired", "");
                continue;
            }
            if (QUIET_EVENTS.contains(code)) {
                continue;                      // 只进界面，不进聊天栏
            }
            String key = EVENT_TEXT.get(code);
            if (key == null) {
                continue;
            }
            broadcast(key, floorLabel(arg));
        }
        c.clearEvents();
    }

    /** 事件参数是"路径索引"，念给玩家听要换算成"第几层"。 */
    private String floorLabel(int index) {
        ElevatorCore c = core();
        for (int i = 0; i < c.floorCount(); i++) {
            if (c.floorIndex(i) == index) {
                return String.valueOf(i + 1);
            }
        }
        return String.valueOf(index + 1);
    }

    // ================================================================== 同步

    private void pushSync() {
        ElevatorSnapshot snap = ElevatorSnapshot.capture(core(), lastStructureOk);
        PacketElevatorSync pkt = new PacketElevatorSync(pos, snap);
        for (EntityPlayer p : world.getEntitiesWithinAABB(EntityPlayer.class,
                new AxisAlignedBB(pos).grow(SYNC_RANGE))) {
            if (p instanceof EntityPlayerMP) {
                PacketHandler.INSTANCE.sendTo(pkt, (EntityPlayerMP) p);
            }
        }
    }

    /** 玩家开界面时立刻推一次，免得界面先显示"正在等服务端" */
    public void sendStateTo(EntityPlayer player) {
        if (world == null || world.isRemote || !(player instanceof EntityPlayerMP)) {
            return;
        }
        PacketHandler.INSTANCE.sendTo(
                new PacketElevatorSync(pos, ElevatorSnapshot.capture(core(), lastStructureOk)),
                (EntityPlayerMP) player);
    }

    // ================================================================== 客户端发包入口

    /**
     * 处理一条来自界面的操作。
     * <p>
     * ★ 每一个参数都要在这里<b>再校验一次</b>：动作号、层下标、方向。
     * 客户端只是"把玩家点了什么报上来"，它说的话一律不可信
     * （一个改过的客户端能发任何东西，而 {@code PacketElevatorAction} 只挡了距离和方块类型）。
     */
    public void handleAction(EntityPlayer player, int action, int arg) {
        if (world == null || world.isRemote) {
            return;
        }
        ElevatorCore c = core();
        switch (action) {
            case 0: {   // ACT_CALL
                if (arg < 0 || arg >= c.floorCount()) {
                    return;
                }
                if (!c.call(arg)) {
                    broadcastActionBar(player, PREFIX + "call_denied", "");
                }
                break;
            }
            case 1:     // ACT_HOLD_DOOR
                c.holdDoor();
                break;
            case 2:     // ACT_CLOSE_DOOR
                c.nudgeClose();
                break;
            case 3:     // ACT_CLEAR_CALLS
                c.clearCalls();
                break;
            case 4:     // ACT_RESCAN
                scanTimer = 0;
                break;
            default:
                break;
        }
    }

    /**
     * 玩家右键层门：呼叫轿厢到<b>这一扇门所在的那一层</b>。
     * <p>
     * 层下标是从门自己的 Y 反推的，<b>不接受调用方传下标</b> ——
     * 让调用方算下标就有两个地方会算错（门在斜面井道里不是一个简单的 Y 差）。
     */
    public void callAtDoor(BlockPos doorPos, EntityPlayer player) {
        if (world == null || world.isRemote || doorPos == null) {
            return;
        }
        ElevatorCore c = core();
        int index = doorPos.getY() - shaftY0;
        for (int i = 0; i < c.floorCount(); i++) {
            if (c.floorIndex(i) == index) {
                if (!c.call(i)) {
                    broadcastActionBar(player, PREFIX + "call_denied", "");
                } else {
                    broadcastActionBar(player, PREFIX_EVENT + "call", String.valueOf(i + 1));
                }
                return;
            }
        }
        // 找得到控制器但这一层不在层表里 → 明说"扇门没接上井道"，而不是静默什么都不做
        broadcastActionBar(player, PREFIX + "door_orphan", "");
    }

    /** 控制柜被拆：把门都关上、轿厢留着（方块本身会被原版一起收掉） */
    public void onControllerBroken() {
        if (world == null || world.isRemote) {
            return;
        }
        closeAllDoors();
        diag("ctrl-broken", "[电梯] 控制柜 " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                + " 被拆（类型=" + ElevatorSpec.typeName(type) + "），已关闭所有层门");
    }

    // ================================================================== 工具

    private static long encode(BlockPos p) {
        return p.toLong();
    }

    private static Set<Long> keySet(List<BlockPos> list) {
        Set<Long> s = new HashSet<Long>(Math.max(16, list.size() * 2));
        for (BlockPos p : list) {
            s.add(encode(p));
        }
        return s;
    }

    // ================================================================== 存档

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        int t = compound.hasKey("Type") ? compound.getInteger("Type") : ElevatorSpec.TYPE_PASSENGER;
        setType(t);
        if (compound.hasKey("Elev")) {
            core().loadState(compound.getIntArray("Elev"));
        }
        appliedIndex = compound.hasKey("Applied") ? compound.getInteger("Applied") : Integer.MIN_VALUE;
        // ★ 读档后强制重落一遍格子：存档里可能有过半截状态（上次存档正好卡在搬迁中间），
        //   重落一次就把轿厢补回完整的样子。这也是"轿厢自己少了块"的自愈路径。
        carCells.clear();
        appliedIndex = Integer.MIN_VALUE;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Type", type);
        compound.setIntArray("Elev", core().saveState());
        // ★ 存"世界此刻反映的是哪一格"。存了它才能在下一次开机时判断
        //   "存档里的轿厢方块是不是已经和新位置对上了"（虽然现在读档一律重落，留着当诊断用）。
        compound.setInteger("Applied", appliedIndex == Integer.MIN_VALUE ? -99999 : appliedIndex);
        return compound;
    }
}

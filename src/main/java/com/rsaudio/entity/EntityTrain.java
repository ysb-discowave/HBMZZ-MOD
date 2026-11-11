package com.rsaudio.entity;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockTrainRail;
import com.rsaudio.block.BlockTrainStation;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketTrainSync;
import com.rsaudio.train.TrainCore;
import com.rsaudio.train.TrainSettings;
import com.rsaudio.train.TrainSpec;
import com.rsaudio.tile.TileTrainStation;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;
import io.netty.buffer.ByteBuf;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 列车实体 —— 本模组的第一个自定义实体。
 *
 * <h3>★ 分工：实体只管"世界 <-> 内核"这一层胶水</h3>
 * 所有物理与状态机都在零依赖内核 {@link TrainCore} 里（那是唯一能拿真 javac 单测的部分）。
 * 这个类<b>不重复实现任何物理</b>，它只做四件事：
 * <ol>
 *   <li><b>扫世界填内核</b>：定期把周围的轨道格和站台读成 {@code addRail} / {@code addStation}；</li>
 *   <li><b>喂 tick</b>：每 tick 调 {@code core.tick()}，然后把内核算出的世界坐标搬到自己的
 *       {@code posX/posY/posZ}；</li>
 *   <li><b>记日志</b>：把内核 {@code drainEvents()} 吐出来的事件转成玩家看得懂的中文提示；</li>
 *   <li><b>存读档</b>：把 {@code core.saveState()} 那份 int[] 原样塞进 NBT。</li>
 * </ol>
 *
 * <h3>★ 为什么"扫世界"是<b>整份重扫</b>而不是增量</h3>
 * 玩家是在列车跑着的时候铺轨道、拆站台的。增量同步要求"每一个会改动轨道的操作
 * 都记得回来通知列车" —— 而操作入口有：放置、破坏、爆炸、活塞、指令、结构加载器……
 * 漏一个就是"轨道明明铺好了车却说没轨道"。
 * <p>
 * 整份重扫的代价只是一次几十到几百格的遍历，每 {@code routeCheckTicks} 刻一次，
 * 完全可忽略。<b>一致性 > 微优化</b>，和内核里 {@code rebuildLinks} 的取舍一致。
 *
 * <h3>★ 扫多大范围：洪泛，不是盒扫</h3>
 * 从车头脚下那一格轨道开始，沿六邻域把<b>整条线路</b>收进来（见 {@link #collectRails}）。
 * 图的范围由轨道自己的连通性决定，与车头在哪、轨道有多长都无关。
 * <p>
 * ★ 这里原来写的是"以车头为中心 32 格的立方体"，理由是"一个园区不会有几公里长的轨道"。
 * 那个前提是错的：玩家实测的环全长 50 格（z 从 -82 到 -33），而框只有 65 格宽、
 * 还跟着车头跑 —— 车头到环的一端，另一端就掉出框外，环被切成一条断头链，
 * 玩家得到的结论就是"<b>轨道长了就不能用</b>"。
 * 而且框里"别人的轨道"会被一起并进来，两条互不相干的线路永远在同一张图里报断裂
 * —— 那就是"<b>一张地图只能放一条线路</b>"。
 * <p>
 * ★ 只走<b>已加载</b>的区块：未加载区块上读方块会强制加载，那是很重的操作，
 * 绝不能在实体 tick 里做。读不到的位置就当没有轨道（内核会报脱轨）。
 */
public class EntityTrain extends Entity implements IEntityAdditionalSpawnData {

    /**
     * 种子搜索半径（格）：从车头这一格往外找"我脚下那格轨道"，找到就从它开始洪泛。
     * <p>
     * ★ 这个值决定"同一张地图上两条线路会不会被并成一张图"：种子只取车头附近这几格，
     * 所以几格开外的平行线路不会被收进来 —— 一台车只服务它自己那条线。
     * 不能再小：读到的车头坐标是<b>浮点插值</b>的结果，向下取整未必正好落在轨道格上。
     * 2（即 5×5×5）足以覆盖任何 y 偏移约定。
     */
    public static final int SEED_RADIUS = 2;

    /**
     * 兜底种子半径（格）：{@link #SEED_RADIUS} 里一格轨道都没找到时，放大到这么大再找。
     * <p>
     * 用途只有一个 —— 车被挪到半空、或者脚下的轨道被拆了之后，给它一条
     * "找回最近那条轨道"的路。正常行驶时永远用不到（第一级就命中）。
     */
    public static final int FALLBACK_SEED_RADIUS = 32;

    /**
     * 洪泛用的邻居偏移。<b>必须与 {@code TrainCore.NEIGHBOUR_OFFSETS} 逐字一致</b>。
     * <p>
     * ★ 少收一种邻居的后果很隐蔽：那条边就只在一端存在，内核会把它算成"断头"，
     * 日志里看到的是"玩家的轨道铺错了"，完全看不出是扫描漏了一种邻接。
     * 六邻域正交 + {@code |dy| <= 1}（竖直那一对天然满足），与内核同。
     */
    private static final int[][] TRACK_NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0},
    };

    /**
     * 从"一格轨道"出发，站台可能落在哪些相对位置上。
     * <p>
     * ★ 这是内核 {@code TrainCore.rebuildStationBindings} 里那张 {@code cands}
     * 表的<b>逆</b>（每个偏移取反）—— 内核从站台找轨道，这里从轨道找站台。
     * 两张表必须互为镜像，改一张就必须改另一张。
     */
    private static final int[][] STATION_SLOT_OFFSETS = {
            {0, 1, 0}, {0, 0, 0},
            {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1},
            {0, 2, 0},
            {-1, 1, 0}, {1, 1, 0}, {0, 1, -1}, {0, 1, 1},
    };


    /** 内核。服务端权威；客户端那份只用于渲染（不在客户端 tick） */
    private final TrainCore core = new TrainCore(new TrainSettings());

    /** 这一节实体是不是客户端副本。决定了"谁 tick 内核" */
    private boolean spawnInit = false;

    /**
     * 车头<b>为什么</b>消失。{@code null} = 没走任何已知移除路径（本身就是线索）。
     * <p>
     * ★ 只用于日志与播报，不进 NBT —— 车都没了，没有"下一次"。
     */
    private String removalReason = null;

    /** 非玩家伤害只报一次（着火时每 20 刻打一次，不设闸门就是刷屏） */
    private boolean envHitLogged = false;

    /**
     * 一格轨道在扫描缓存里的记录。
     * <p>
     * ★★ 为什么<b>存坐标本身</b>而不是"把坐标打包成 long 再解回来"：
     * 第一版我图省事用了 {@code core.key(x,y,z)} 打包、再按 {@code k>>42} 解回来 ——
     * 那是错的双重错误：打包格式其实是 {@code x<<38 | y<<26 | z}，
     * 而且位运算解包在<b>负坐标</b>上会静默给出垃圾值。
     * 直接把坐标留着最省事，也没有解包这一步可以写错。
     * （打包成 long 只允许当 Map 键用，见 {@code TrainSpec.cellKey}。）
     */
    private static final class RailRec {
        final int x;
        final int y;
        final int z;
        final int code;      // dir | (slope ? 0x100 : 0)

        RailRec(int x, int y, int z, int code) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.code = code;
        }
    }

    /**
     * 上一次扫描时各轨道格的走向码缓存（键 = {@link #cellKey}）。
     * <p>
     * ★ 用来做"轨道变了没有"的快速判断：全量重扫虽然便宜，但<b>重建整张图会让
     * 内核里的 prev/next 全部重算</b>。如果每 40 刻都无脑重建，任何"正在进行的行进"
     * 都会被打断。所以只有真的变了才重建。
     */
    private final Map<Long, RailRec> railStamp = new HashMap<Long, RailRec>();

    /**
     * "线路端点差一格"这条提示上次说出去的时刻。
     * <p>
     * ★ 它是在<b>每一次扫描</b>里算出来的（默认几秒一次），不定时说的话
     * 会变成每几秒一条 —— 那正是玩家最烦的刷屏。
     */
    private long gapHintAt = Long.MIN_VALUE;

    /** 同一条提示的最小间隔（tick）。10 秒 */
    private static final int GAP_HINT_THROTTLE_TICKS = 200;

    /** 站台签名，同上：名字和停站时间变了才需要重灌（值 = 名字 + \u0001 + 停站刻数） */
    private final Map<Long, String> stationStamp = new HashMap<Long, String>();

    /**
     * 扫描缓存的键。<b>只用于 Map 查找</b>，绝不解包还原坐标 ——
     * 要坐标就直接带着 {@link RailRec} 走。
     */
    private static long cellKey(int x, int y, int z) {
        // 用质数混合，避免低位/高位在相邻坐标上撞车
        long h = 1125899906842597L;
        h = h * 31 + x;
        h = h * 31 + y;
        h = h * 31 + z;
        return h;
    }

    /** 这次扫描有没有发现任何变化 */
    private boolean scanDirty = true;

    public EntityTrain(World worldIn) {
        super(worldIn);
        // 碰撞箱跟车厢几何对齐（渲染是 CAR_W 宽、CAR_Y0+CAR_H 高）——
        // 差太多的话会出现"人站在车厢里却穿过去"或者"离车还有半格就被推开"。
        setSize((float) (TrainSpec.CAR_W * 0.9D), (float) (TrainSpec.CAR_Y0 + TrainSpec.CAR_H));
        // ★ 这里<b>故意不</b>写 isImmuneToFire：免疫要写在"谁有权拆车"那一个判据里
        //   （{@link #attackEntityFrom}），而不是散成一堆免疫标志位 ——
        //   散开的后果是"环境伤害能删掉车头"这条路径谁也看不见（见那边的长注释）。
        this.stepHeight = 0.0F;
        this.noClip = false;
    }

    // ================================================================ 尺寸

    @Override
    protected void entityInit() {
        // 没有需要网络同步的 DataWatcher 字段：位置/朝向走实体自己的同步，
        // 其余状态（速度、相位、乘客数）走 PacketTrainSync 定期推。
        // ★ 为什么不放进 DataWatcher：那些值每 tick 都变，而 DataWatcher 每个字段
        //   都有独立的 dirty 标记 + 每 tick 的同步包；用它就等于每 tick 发好几个包。
        //   走自己的包可以按需节流（见 SYNC_INTERVAL）。
    }

    // ================================================================ tick

    @Override
    public void onUpdate() {
        super.onUpdate();

        if (world.isRemote) {
            // 客户端不跑内核：位置由服务端通过实体的 posX/posY/posZ 同步过来。
            // ★ 客户端如果也跑一份内核，两边会因为"扫描到的世界不一样"而逐渐分叉，
            //   表现是车在屏幕上抽搐。所以客户端<b>只做渲染</b>。
            this.prevPosX = this.posX;
            this.prevPosY = this.posY;
            this.prevPosZ = this.posZ;
            return;
        }

        // ---- 服务端：扫世界 -> 喂内核 -> 搬坐标
        if (!spawnInit) {
            spawnInit = true;
            scanWorld();
            // 第一次进世界：如果内核已经因为读档恢复了位置，就按坐标吸附；
            // 否则找一个站台发车。
            if (!core.rebase((int) Math.floor(posX), (int) Math.floor(posY),
                    (int) Math.floor(posZ))) {
                core.startAtStation(0);
            }
        }

        tickScanTimer();
        core.tick();
        applyCorePosition();
        flushEvents();
        syncToClients();
    }

    /** 定期重扫世界（见类注释里"为什么整份重扫"） */
    private void tickScanTimer() {
        if (scanTimer-- > 0) {
            return;
        }
        scanTimer = Math.max(1, core.settings().routeCheckTicks);
        scanWorld();
    }

    private int scanTimer = 0;

    /**
     * 把周围的轨道和站台读进内核。
     * <p>
     * ★ 只在真的变了的时候才 {@code rebuildLinks()} —— 见 {@link #railStamp} 的注释。
     */
    private void scanWorld() {
        try {
            doScanWorld();
        } catch (Throwable t) {
            // 扫描不能把整个世界 tick 掉。出意外就报出来、保持上一次的图
            RsAudio.warn("[列车] 扫描轨道异常: %s", t);
        }
    }

    private void doScanWorld() {
        int cx = (int) Math.floor(posX);
        int cy = (int) Math.floor(posY);
        int cz = (int) Math.floor(posZ);

        // ---- 轨道：从车头脚下洪泛出整条线路（不是以车头为中心的盒扫，见 collectRails）
        Map<Long, RailRec> seenRails = collectRails(cx, cy, cz);

        // ---- 端点"差一格"提示
        // ★ 这一格是玩家最需要的一句话。轨道只差一格没接上时，内核给的判据是
        //   "有 N 个格没接上"（N 因为单遍定向会虚高成一个巨大且无意义的数），
        //   玩家拿着它根本不知道该去动哪一格 —— 于是结论就是"轨道长了也不能用"。
        //   这里替他把"补哪一格"算出来。
        reportTerminusGaps(seenRails);

        boolean railsChanged = railsDiffer(seenRails);
        if (railsChanged) {
            // ★ 整份重灌：clearRails + 逐个 addRail，再 rebuildLinks。
            //   不做增量删除 —— 内核没有 removeRail 的批量版本，
            //   而且"多删了一格"的后果（车开进空气）比"多重建一次"严重得多。
            core.clearRails();
            for (RailRec r : seenRails.values()) {
                core.addRail(r.x, r.y, r.z, r.code & 0xFF, (r.code & 0x100) != 0);
            }
            railStamp.clear();
            railStamp.putAll(seenRails);
            core.rebuildLinks();
        }

        // ---- 站台：从扫到的轨道格出发找（站台只在轨道旁边才有意义）
        Map<Long, StationRec> seenStations = new HashMap<Long, StationRec>();
        if (scanStations(seenRails, seenStations)) {
            collectStations(seenStations);
            return;
        }
        if (railsChanged) {
            // 轨道图重建后站台的 rail 下标会失效，必须重挂一遍（图没变就不用重挂）
            collectStations(rebuiltStations);
        }
    }

    /**
     * 这一轮的轨道图与上一轮有没有区别（有一格不同就要整份重灌）。
     * <p>
     * ★ 判据必须是"两边格数相同、且<b>每一格的走向码</b>都相同"。只比格数会漏掉
     * "拆了一格又在别处补了一格"——总数不变、图的形状却变了。
     * （反过来说，格数相同 + 每个键都在两边 = 两个集合相等，不必再扫一遍旧表找删格。）
     */
    private boolean railsDiffer(Map<Long, RailRec> seenRails) {
        if (seenRails.size() != railStamp.size()) {
            return true;
        }
        for (Map.Entry<Long, RailRec> e : seenRails.entrySet()) {
            RailRec old = railStamp.get(e.getKey());
            if (old == null || old.code != e.getValue().code) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把<b>整条线路</b>的轨道读进来 —— 从车头脚下那格开始，沿六邻域洪泛。
     *
     * <h3>★★★ 为什么不能"以车头为中心扫一个立方体"</h3>
     * 这里原来是三层的 {@code for dx,dy,dz in -32..32} 盒扫，它有两个致命问题：
     * <ol>
     *   <li><b>长轨道会被框切断</b>：框跟着车头走。玩家实测的环全长 50 格，
     *       车头跑到环的一端时另一端就掉出框外 → {@code isLoop()} 变 false →
     *       内核报"轨道断裂" → 车只能认眼前那一段。这就是"轨道长了就不能用"；</li>
     *   <li><b>框里"别人的轨道"被并进来</b>：同一个园区里两条互不相干的线路，
     *       只要相距不到 65 格就永远在同一张图里 → 图有多个连通分量 → 照样报断裂。
     *       这就是"一张地图只能放一条线路"。</li>
     * </ol>
     * 洪泛把这两个问题一起解决：图的边界 = 轨道自己的连通性（与车头位置无关），
     * 种子只取车头附近（所以隔壁那条线路不会被并进来）。
     * <p>
     * ★★ 顺带一个好处：盒扫是 <b>65³ = 27.4 万</b>次读方块，洪泛只要
     * "轨道格数 × 6"。104 格的线路 = 624 次，快了两个数量级。
     * <p>
     * ★ 上限 {@link TrainSpec#MAX_TRACK_CELLS} 只是防呆（玩家真把轨道绕世界一圈），
     * 洪泛本身对长度没有限制。
     */
    private Map<Long, RailRec> collectRails(int cx, int cy, int cz) {
        Map<Long, RailRec> out = new HashMap<Long, RailRec>();
        ArrayDeque<int[]> queue = new ArrayDeque<int[]>();
        Set<Long> queued = new HashSet<Long>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        // ---- 种子：车头自己那一格。一个都没有就放大一圈再找（车被挪到半空时的兜底）。
        for (int r = SEED_RADIUS; queue.isEmpty() && r <= FALLBACK_SEED_RADIUS; r *= 2) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        int x = cx + dx;
                        int y = cy + dy;
                        int z = cz + dz;
                        if (!railHere(p, x, y, z)) {
                            continue;
                        }
                        long k = cellKey(x, y, z);
                        if (queued.add(Long.valueOf(k))) {
                            queue.add(new int[] { x, y, z });
                        }
                    }
                }
            }
        }

        // ---- 洪泛
        int cap = TrainSpec.MAX_TRACK_CELLS;
        while (!queue.isEmpty() && out.size() < cap) {
            int[] c = queue.poll();
            Long boxed = Long.valueOf(cellKey(c[0], c[1], c[2]));
            if (out.containsKey(boxed)) {
                continue;
            }
            // ★ 出队时再读一次：入队到现在可能已经被拆了。
            RailRec rec = railRecAt(p, c[0], c[1], c[2]);
            if (rec == null) {
                continue;
            }
            out.put(boxed, rec);
            for (int[] o : TRACK_NEIGHBOURS) {
                int nx = c[0] + o[0];
                int ny = c[1] + o[1];
                int nz = c[2] + o[2];
                // 与内核 buildAdjacency 的 |dy| <= MAX_STEP 同一条判据
                if (Math.abs(ny - c[1]) > 1) {
                    continue;
                }
                Long nk = Long.valueOf(cellKey(nx, ny, nz));
                if (queued.contains(nk) || out.containsKey(nk)) {
                    continue;
                }
                if (!railHere(p, nx, ny, nz)) {
                    continue;
                }
                queued.add(nk);
                queue.add(new int[] { nx, ny, nz });
            }
        }
        if (out.size() >= cap) {
            RsAudio.warn("[列车] 轨道超过 %d 格，本次扫描在 (%d,%d,%d) 处截断", cap, cx, cy, cz);
        }
        return out;
    }

    /** 这一格是不是轨道（区块没加载/不是轨道都算 false） */
    private boolean railHere(BlockPos.MutableBlockPos p, int x, int y, int z) {
        p.setPos(x, y, z);
        // 区块没加载就不读 —— getBlockState 在未加载区块上会强加载，
        // 那是很重的操作，绝不能在实体 tick 里做。
        if (!world.isBlockLoaded(p)) {
            return false;
        }
        return world.getBlockState(p).getBlock() instanceof BlockTrainRail;
    }

    /** 读一格轨道的走向码；不是轨道返回 null */
    private RailRec railRecAt(BlockPos.MutableBlockPos p, int x, int y, int z) {
        p.setPos(x, y, z);
        if (!world.isBlockLoaded(p)) {
            return null;
        }
        IBlockState st = world.getBlockState(p);
        if (!(st.getBlock() instanceof BlockTrainRail)) {
            return null;
        }
        // ★★ 必须用 getActualState <b>现算</b>的形状，不能用世界里存的那个 meta。
        //   轨道方块把形状设计成"由邻居现算"（见 BlockTrainRail 的类注释），
        //   世界里存的 SHAPE 永远停在 getDefaultState() 的那个 0 ——
        //   放置时写进去，之后没有任何代码去更新它。
        //   直接 dirOf(st) 的后果：内核收到的 dir 恒为 DIR_NONE，
        //   于是 TrainCore.speedLimitHere() 里那条 r.dir == DIR_CURVE
        //   <b>从来没成立过</b> —— 弯道限速形同虚设，
        //   表现就是列车全速冲过直角弯（玩家说的"一点也不自然"）。
        int dir = TrainSpec.dirFromShape(BlockTrainRail.shapeOf(
                st.getBlock().getActualState(st, world, p)));
        boolean slope = isSlope(x, y, z);
        return new RailRec(x, y, z, (dir & 0xFF) | (slope ? 0x100 : 0));
    }

    /** 上一次扫到的站台（轨道图重建时要拿它重挂一次） */
    private final Map<Long, StationRec> rebuiltStations = new HashMap<Long, StationRec>();

    /** 一座站台：锚点坐标 + 名字 + 停站时间 */
    private static final class StationRec {
        final int x;
        final int y;
        final int z;
        final String name;
        final int dwell;

        StationRec(int x, int y, int z, String name, int dwell) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.name = name;
            this.dwell = dwell;
        }
    }

    /** 把扫到的站台灌进内核 */
    private void collectStations(Map<Long, StationRec> seen) {
        core.clearStations();
        for (StationRec s : seen.values()) {
            core.addStation(s.x, s.y, s.z, s.name, s.dwell);
        }
        if (!seen.isEmpty()) {
            core.rebuildLinks();
        }
        // ★ 签名必须在这里更新 —— 它记录的是"已经灌进内核的那一份"。
        //   放在扫描函数里更新的话，"轨道变了导致重挂站台"这条路径就不会刷新签名，
        //   下一轮扫描会误判成"站台变了"、再重挂一次，白重建。
        stationStamp.clear();
        for (Map.Entry<Long, StationRec> e : seen.entrySet()) {
            StationRec s = e.getValue();
            stationStamp.put(e.getKey(), s.name + "\u0001" + s.dwell);
        }
    }

    /**
     * 扫站台。
     * <p>
     * 站的权威格是<b>身份证格</b>（{@link BlockTrainStation#identityOf}：
     * 一片连通的站台方块里最西北的那一格），所以一片站台只会产出<b>一条</b>记录 ——
     * 否则内核里会出现好几座同名站台，车会在同一个地方停好几次。
     * <p>
     * ★★ <b>不能"只认某一格"</b>。早先这里要求"锚点那一格必须也是站台方块"，
     * 而锚点是"3 格网格的西北角" —— 玩家的站台铺在 x=304 时锚点在 x=303，
     * 那一格是空气，于是整座站在扫描里凭空消失，列车报"线路上一个站点都没有"。
     * 现在改成跟着世界里的方块走：铺一格是一站、铺一条月台也是一站。
     * <p>
     * ★★★ <b>站台的搜索范围 = 已扫到的轨道格的邻域，不是"以车头为中心的盒"。</b>
     * 原来是拿 40 格的盒扫去找站台，于是车头跑远之后，线路另一端那几座站台
     * 就掉出框外 —— 玩家说的"<b>只能识别一个站点</b>"。
     * 而站台只在"轨道旁边"才有意义：内核 {@code rebuildStationBindings} 只从站台
     * 往正下/正上/四周找 ≤2 格的轨道，再远就是"贴着空气的站台"，列车永远不会停在那里。
     * 所以从<b>完整的轨道格集合</b>出发找站台，既不会漏，也不会收进没用的孤立站台。
     * <p>
     * ★ {@link #STATION_SLOT_OFFSETS} 是内核那张 {@code cands} 表的逆。
     * 改一张必须改另一张，否则会出现"内核愿意绑、扫描却没把它送进来"的站台。
     * <p>
     * ★ 同一片站台会被很多条轨道格看到（站台夹在两格轨道中间时），
     * 所以按身份证格去重；{@code padCache} 保证一片 N 格站台只跑一次连通搜索。
     *
     * @return 站台集合有没有变化
     */
    private boolean scanStations(Map<Long, RailRec> rails, Map<Long, StationRec> out) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        Map<Long, BlockPos> padCache = new HashMap<Long, BlockPos>();
        for (RailRec r : rails.values()) {
            for (int[] c : STATION_SLOT_OFFSETS) {
                int x = r.x + c[0];
                int y = r.y + c[1];
                int z = r.z + c[2];
                p.setPos(x, y, z);
                if (!world.isBlockLoaded(p)) {
                    continue;
                }
                if (!BlockTrainStation.isStation(world, p)) {
                    continue;
                }
                BlockPos identity = BlockTrainStation.identityOf(world, p, padCache);
                long k = cellKey(identity.getX(), identity.getY(), identity.getZ());
                Long boxed = Long.valueOf(k);
                if (out.containsKey(boxed)) {
                    continue;
                }
                TileTrainStation tile = BlockTrainStation.getTile(world, identity);
                if (tile == null) {
                    continue;
                }
                out.put(boxed, new StationRec(identity.getX(), identity.getY(),
                        identity.getZ(), tile.getName(), tile.getDwellTicks()));
            }
        }
        rebuiltStations.clear();
        rebuiltStations.putAll(out);

        // 比对变化
        if (out.size() != stationStamp.size()) {
            return true;
        }
        for (Map.Entry<Long, StationRec> e : out.entrySet()) {
            StationRec cur = e.getValue();
            String old = stationStamp.get(e.getKey());
            if (old == null || !old.equals(cur.name + "\u0001" + cur.dwell)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这条线的端点上，"再差一格就能接上另一段轨道"的格子是哪几格。
     *
     * <h3>★★★ 为什么必须报"哪一格"</h3>
     * 玩家实际遇到的是：轨道铺成一条长线（或两个环），中间<b>只差一格</b>没接上。
     * 内核只会说"有 72 个格没接上"，因为它的定向是单遍不回溯的 —— 图一断，
     * 后面整段都拿不到 prev/next，全被数成断头。玩家拿这个数字无从下手，
     * 最后得到的结论就是"轨道长了就不能用"。
     * <p>
     * 这里替玩家把那格算出来：从<b>真实端点</b>（这条线里只有一个邻居的格）出发，
     * 往没有邻居的那几个方向看两格 —— 中间那格是空的、隔一格是轨道，那中间那格
     * 就是"补上就能通"的位置。
     *
     * <h3>★ 为什么只认"隔一格有轨道、而且它不在这条线里"</h3>
     * <ul>
     *   <li>U 形线的两条臂相距两格，端点朝外看第二格是<b>自己这条线</b>的格子；
     *       不排除掉的话，一条好端端的 U 形线每次扫描都会收到一条假提示；</li>
     *   <li>直接相邻（隔零格）就不是"端点"了 —— 那已经被收进这条线里；</li>
     *   <li>隔两格以上（缺 2 格以上）不报：这时"该补哪一格"本来就有歧义，
     *       报了反而误导。</li>
     * </ul>
     */
    private void reportTerminusGaps(Map<Long, RailRec> rails) {
        if (rails.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        List<String> gaps = new ArrayList<String>();
        for (RailRec r : rails.values()) {
            if (degreeOf(rails, r) != 1) {
                continue;   // 不是端点
            }
            for (int[] o : TRACK_NEIGHBOURS) {
                int mx = r.x + o[0];
                int my = r.y + o[1];
                int mz = r.z + o[2];
                if (rails.containsKey(Long.valueOf(cellKey(mx, my, mz)))) {
                    continue;   // 这个方向有邻居，不是端点的外侧
                }
                int fx = r.x + o[0] * 2;
                int fy = r.y + o[1] * 2;
                int fz = r.z + o[2] * 2;
                if (rails.containsKey(Long.valueOf(cellKey(fx, fy, fz)))) {
                    continue;   // 隔一格是这条线自己（U 形线的另一条臂）
                }
                if (railHere(p, fx, fy, fz)) {
                    gaps.add("(" + mx + "," + my + "," + mz + ")");
                }
            }
        }
        if (gaps.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gaps.size() && i < 4; i++) {
            if (i > 0) {
                sb.append("、");
            }
            sb.append(gaps.get(i));
        }
        String list = sb.toString();
        // 日志每次扫描都打（结论没变就重复，方便对照）；玩家提示上闸门
        RsAudio.log("[列车] 线路端点差一格就能接上：在 %s 补一格轨道（本线 %d 格）",
                list, rails.size());
        long now = world.getTotalWorldTime();
        if (gapHintAt != Long.MIN_VALUE && now >= gapHintAt
                && now - gapHintAt < GAP_HINT_THROTTLE_TICKS) {
            return;
        }
        gapHintAt = now;
        notifyNearby(I18n.translateToLocalFormatted("chat.rsaudio.train.gap_hint", list));
    }

    /** 这一格在<b>本次扫描到的</b>轨道图里有几个邻居（只看正交六邻域） */
    private int degreeOf(Map<Long, RailRec> rails, RailRec r) {
        int n = 0;
        for (int[] o : TRACK_NEIGHBOURS) {
            if (rails.containsKey(Long.valueOf(cellKey(r.x + o[0], r.y + o[1], r.z + o[2])))) {
                n++;
            }
        }
        return n;
    }

    private boolean isSlope(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return isRailAt(pos.up()) || isRailAt(pos.down());
    }

    private boolean isRailAt(BlockPos p) {
        if (!world.isBlockLoaded(p)) {
            return false;
        }
        return world.getBlockState(p).getBlock() instanceof BlockTrainRail;
    }

    /**
     * 把内核算出的世界坐标搬到自己身上。
     * <p>
     * ★ 用 {@code setPosition} 而不是 {@code move}：内核已经把插值做完了
     * （{@code worldX/Y/Z} 就是"轨格中心 + progress 插值"的最终结果）。
     * 再走一次 {@code move} 就等于插了两次值，表现是车在坡道上抖。
     */
    private void applyCorePosition() {
        if (core.getPhase() == TrainSpec.PH_STALLED && core.getFail() != TrainSpec.FAIL_NONE
                && TrainCore.isFatal(core.getFail())) {
            // 致命故障：停在原地不搬坐标（内核不知道自己在哪了就没法搬）
            return;
        }
        double nx = core.worldX();
        double ny = core.worldY();
        double nz = core.worldZ();
        if (Double.isNaN(nx) || Double.isNaN(ny) || Double.isNaN(nz)) {
            // 内核保证不放 NaN 出来，这里是最后一道防线
            return;
        }
        this.prevPosX = this.posX;
        this.prevPosY = this.posY;
        this.prevPosZ = this.posZ;
        setPosition(nx, ny, nz);
        this.rotationYaw = core.yaw();
        this.prevRotationYaw = this.rotationYaw;
        // 里程表：让服务端知道"这个实体移动过"，否则位置更新不会发给客户端
        this.velocityChanged = true;
    }

    /** 把内核事件翻成玩家提示 */
    private void flushEvents() {
        List<String> evs = core.drainEvents();
        if (evs.isEmpty()) {
            return;
        }
        int fail = core.getFail();
        for (String ev : evs) {
            // ★ 故障类事件要连着"具体原因"一起报 —— 这是本项目的硬约定：
            //   每句失败提示都要能指到该去改哪个方块、改哪个参数。
            //   只报 "train_stalled" 对玩家毫无用处。
            String detail = core.getFailDetail();
            // ★ 日志必带坐标（项目约定）。车头万一后面消失了，"它最后在干什么、在哪"
            //   如果能从同一行读到，就不需要再去翻别的日志。
            RsAudio.log("[列车] %s @ %s%s", ev, where(), detail == null || detail.isEmpty()
                    ? "" : "：" + detail);
            if (TrainCore.failEventKey(fail).equals(ev)) {
                notifyNearby(I18n.translateToLocalFormatted("chat.rsaudio.train.fail",
                        I18n.translateToLocal(failKey(fail)), detail == null ? "" : detail));
            }
        }
    }

    /** 故障原因的本地化键 */
    public static String failKey(int reason) {
        switch (reason) {
            case TrainSpec.FAIL_NO_RAIL:
                return "train.fail.no_rail";
            case TrainSpec.FAIL_DEAD_END:
                return "train.fail.dead_end";
            case TrainSpec.FAIL_BLOCKED:
                return "train.fail.blocked";
            case TrainSpec.FAIL_STATION_GONE:
                return "train.fail.station_gone";
            case TrainSpec.FAIL_NO_LOOP:
                return "train.fail.no_loop";
            case TrainSpec.FAIL_NO_STATION:
                return "train.fail.no_station";
            case TrainSpec.FAIL_DOOR_JAMMED:
                return "train.fail.door_jammed";
            case TrainSpec.FAIL_NO_CAR:
                return "train.fail.no_car";
            default:
                return "train.fail.none";
        }
    }

    /** 给附近（64 格内）的玩家发一条提示 */
    private void notifyNearby(String text) {
        List<EntityPlayer> players = world.playerEntities;
        for (EntityPlayer pl : players) {
            if (pl.getDistanceSq(this) <= 64.0D * 64.0D) {
                pl.sendStatusMessage(new TextComponentString(text), true);
            }
        }
    }

    // ================================================================ 同步

    /** 同步间隔（tick）。20 tick = 1 秒推一次读数（速度/相位/乘客数） */
    private static final int SYNC_INTERVAL = 20;
    private int syncTimer = 0;

    /**
     * 把内核读数推给看得见的客户端。
     * <p>
     * ★ 位置/朝向<b>不走这个包</b> —— 那是实体自带的同步负责的。
     * 这个包只带"实体同步不带的东西"：速度、相位、乘客数、故障。
     * 客户端渲染器要用它们画转向架灯、车厢门。
     */
    private void syncToClients() {
        if (syncTimer-- > 0) {
            return;
        }
        syncTimer = SYNC_INTERVAL;
        int[] st = core.saveState();
        PacketHandler.INSTANCE.sendToAllAround(new PacketTrainSync(getEntityId(), st),
                new net.minecraftforge.fml.common.network.NetworkRegistry.TargetPoint(
                        world.provider.getDimension(), posX, posY, posZ, 96.0D));
    }

    // ================================================================ 交互

    /**
     * 右键 = 上车 / 下车。
     * <p>
     * ★ 前提是内核认为"门是开的"（{@code core.doorOpen()}）。这不是刁难玩家 ——
     * 它就是列车这个玩法的核心约束：你得等车停稳、门开了才能上。
     * 门关着的时候右键会明确告诉玩家"现在上不去"，而不是默默失败。
     */
    @Override
    public boolean processInitialInteract(EntityPlayer player, EnumHand hand) {
        if (world.isRemote) {
            return true;
        }
        if (player.isSneaking()) {
            // 潜行右键 = 看车况
            player.sendStatusMessage(new TextComponentString(describe()), true);
            return true;
        }
        if (player.getRidingEntity() == this) {
            // 已经在车上：下车
            player.dismountRidingEntity();
            return true;
        }
        if (!core.doorOpen()) {
            player.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocalFormatted("chat.rsaudio.train.door_closed",
                            I18n.translateToLocal(phaseKey(core.getPhase())))), true);
            return true;
        }
        if (core.passengers() >= core.capacity()) {
            player.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocal("chat.rsaudio.train.full")), true);
            return true;
        }
        if (!core.isLoop() && core.getFail() != TrainSpec.FAIL_NONE
                && TrainCore.isFatal(core.getFail())) {
            player.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocalFormatted("chat.rsaudio.train.fail",
                            I18n.translateToLocal(failKey(core.getFail())),
                            core.getFailDetail())), true);
            return true;
        }
        // 上车
        player.startRiding(this);
        core.setWaiting(core.getWaiting() + 1);
        return true;
    }

    /** 相位名的本地化键（界面/提示要用） */
    public static String phaseKey(int phase) {
        switch (phase) {
            case TrainSpec.PH_RUN:
                return "train.phase.run";
            case TrainSpec.PH_BRAKING:
                return "train.phase.braking";
            case TrainSpec.PH_DOOR_OPENING:
                return "train.phase.door_opening";
            case TrainSpec.PH_DWELL:
                return "train.phase.dwell";
            case TrainSpec.PH_DOOR_CLOSING:
                return "train.phase.door_closing";
            case TrainSpec.PH_ACCEL:
                return "train.phase.accel";
            case TrainSpec.PH_STALLED:
                return "train.phase.stalled";
            default:
                return "train.phase.run";
        }
    }

    /** 一句话车况（潜行右键） */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(I18n.translateToLocalFormatted("chat.rsaudio.train.status",
                core.cars(), core.passengers(), core.capacity()));
        sb.append(" · ").append(I18n.translateToLocal(phaseKey(core.getPhase())));
        sb.append(String.format(java.util.Locale.ROOT, " · %.2f 格/刻", core.getSpeed()));
        if (core.getFail() != TrainSpec.FAIL_NONE) {
            sb.append("\n").append(I18n.translateToLocalFormatted("chat.rsaudio.train.fail",
                    I18n.translateToLocal(failKey(core.getFail())), core.getFailDetail()));
        }
        return sb.toString();
    }

    // ================================================================ 实体契约

    /**
     * 重力/摩擦一律交给内核 —— 实体自己什么都不算。
     * <p>
     * ★ 这是"两套物理打架"的解法：如果这里调 {@code super.move(MoverType.SELF, ...)}，
     * 原版的重力会把车往下拽，而内核每 tick 又把坐标硬设回轨道高度 ——
     * 表现就是车在轨道上方高频抖动。
     * 所以这个实体是"被内核牵着走"的：{@code onUpdate} 里直接
     * {@code setPosition}，从不调用 {@code move}。
     */
    @Override
    public void move(MoverType type, double x, double y, double z) {
        // 什么都不做：位置完全由内核决定
    }

    /** 不会被推走、不会被水流冲 —— 它是一条轨道上的车 */
    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    /**
     * 乘客能不能<b>操纵</b>这台车（原版里是给猪/船/矿车那类"骑上去能开"的实体用的）。
     * <p>
     * ★ 这个方法的名字有误导性：它**不是**"能不能骑上来"。
     * "能不能上车"由 {@link #processInitialInteract} 判定（门开着才能上）；
     * 这个方法只回答"上车之后能不能自己掌握方向"。
     * <p>
     * 列车<b>不</b>让乘客操纵 —— 它跑的是时刻表，方向盘在 {@code TrainCore} 手里。
     * 返回 true 的话原版会把玩家的视线方向当成输入去转动实体朝向，
     * 表现就是"车被乘客扭歪了，但下一 tick 内核又把它拧回来"的高频抖动。
     */
    @Override
    public boolean canPassengerSteer() {
        return false;
    }

    /**
     * 乘客坐哪：按"第几个乘客"排到车厢里的座位上。
     * <p>
     * ★ 座位偏移全部来自 {@link TrainSpec#seatLocalX}/{@link TrainSpec#seatLocalZ}，
     * 和渲染器画车厢用的是<b>同一组常量</b> —— 两边各写一套数字的话，
     * 乘客会飘在车厢外面，而那是没有任何报错的静默故障。
     *
     * <h3>★★ 局部坐标 -> 世界坐标必须和渲染器的 {@code rotate(-yaw)} 对齐</h3>
     * 渲染器把车体几何按"车头朝 +Z"写，再绕 Y 转 {@code -yaw}
     * （MC 的朝向是 {@code (-sin yaw, cos yaw)}，所以要取负号）。
     * 于是局部 {@code (lx, lz)} 到世界偏移是：
     * <pre>
     *   wx = lx·cos - lz·sin
     *   wz = lx·sin + lz·cos
     * </pre>
     * ★ 这里原来是 {@code dx = -sin·back, dz = +cos·back}，也就是把乘客摆到了
     * <b>车头正前方</b> back 格的地方 —— 车厢画在车后面，人却飘在车前面，
     * 而且沿 X 的线路上还会横向偏出去。现在统一走上面这个矩阵。
     */
    @Override
    public void updatePassenger(Entity passenger) {
        if (!isPassenger(passenger)) {
            return;
        }
        // 车头没有座位（HEAD_CAPACITY = 0），所以第 0 个乘客落在第 1 节车厢
        int idx = this.getPassengers().indexOf(passenger);
        if (idx < 0) {
            idx = 0;
        }
        int perCar = Math.max(1, TrainSpec.CAPACITY_PER_CAR);
        int car = 1 + (idx / perCar);
        if (car > core.cars()) {
            car = Math.max(1, core.cars());
        }
        int seat = idx % perCar;
        // 车厢中心在局部 z = -car·CELLS_PER_CAR（往车尾退，和渲染器同一把尺子）
        double lx = TrainSpec.seatLocalX(seat);
        double lz = -car * TrainSpec.CELLS_PER_CAR + TrainSpec.seatLocalZ(seat);
        double yawRad = Math.toRadians(this.rotationYaw);
        double wx = lx * Math.cos(yawRad) - lz * Math.sin(yawRad);
        double wz = lx * Math.sin(yawRad) + lz * Math.cos(yawRad);
        // 脚底站在车厢地板上（和车底一样高，所以上下车不用跨台阶）
        passenger.setPosition(posX + wx, posY + TrainSpec.CAR_FLOOR_Y, posZ + wz);
    }

    /** 不写这个的话玩家会坐在方块坐标系原点上 */
    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
    }

    @Override
    public double getMountedYOffset() {
        // 和 updatePassenger 里那个高度同一把尺子（原版默认路径也会用到它）
        return TrainSpec.CAR_FLOOR_Y;
    }

    /**
     * 被攻击 —— <b>只有玩家的攻击</b>能拆掉这台车（连同里面的乘客一起下车）。
     * <p>
     * ★ 不写 {@code setDead} 之外的清理：轨道和站台是玩家的东西，不打车一起拆。
     *
     * <h3>★★★ 为什么必须把"非玩家伤害"挡掉（2026-09-22 实锤）</h3>
     * 原来的实现是"任何人打我，我就 {@code setDead}"。而原版 {@code Entity} 自己就会打你：
     * <ul>
     *   <li>{@code Entity.onEntityUpdate}：{@code fire > 0} 时<b>每 20 刻</b>调一次
     *       {@code attackEntityFrom(DamageSource.ON_FIRE, 1.0F)}；</li>
     *   <li>同处：{@code isInLava()} 命中 → {@code setOnFireFromLava()} →
     *       {@code attackEntityFrom(DamageSource.LAVA, 4.0F)}；</li>
     *   <li>{@code isImmuneToExplosions()} 默认 false → 附近任何爆炸都会打它。</li>
     * </ul>
     * 于是<b>一台停在站台上、玩家一根手指都没碰的车头，会在几秒内静默消失</b>：
     * 存档里搜不到（区块实体数是 0）、游戏日志里一条都没有（既没有异常也没有 WARN）、
     * 玩家看到的就只有"车头没了"。事后从日志完全无法判断是岩浆、着火还是爆炸 ——
     * 因为那条路径<b>根本没有日志出口</b>。
     * <p>
     * ★ 通式：一个"世界里的机器"不该把"我可以被伤害"当默认。谁有权拆它，
     * 必须在一个判据里显式写出来；其余一律拒绝<b>并留痕</b>，否则下一次还是同样的静默故障。
     * <p>
     * ★ 这里刻意<b>不</b>靠 {@code isImmuneToFire = true} 之类的标志位来免疫：
     * 免疫写在 {@code attackEntityFrom} 一处，才能保证"有没有漏掉某条伤害源"
     * 是看得见的；标志位一散开就没人能回答这个问题了。
     */
    @Override
    public boolean attackEntityFrom(net.minecraft.util.DamageSource source, float amount) {
        if (world.isRemote || isEntityInvulnerable(source)) {
            return false;
        }
        EntityPlayer attacker = null;
        Entity trueSrc = source.getTrueSource();
        if (trueSrc instanceof EntityPlayer) {
            attacker = (EntityPlayer) trueSrc;
        } else if (trueSrc == null && source.getImmediateSource() instanceof EntityPlayer) {
            attacker = (EntityPlayer) source.getImmediateSource();
        }
        if (attacker == null) {
            // 环境伤害（着火 / 岩浆 / 爆炸 / 挤压…）：拒绝，但第一次一定要说话。
            // ★ 这条日志就是"车头为什么没了"的答案本身，所以必须带伤害类型和坐标。
            if (!envHitLogged) {
                envHitLogged = true;
                RsAudio.log("[列车] 车头吃到非玩家伤害（%s，%.1f 点）@ %s —— 已拒绝"
                        + "（只有玩家能拆车，机器不吃环境伤害）",
                        source.getDamageType(), Float.valueOf(amount), where());
            }
            return false;
        }
        removalReason = "被玩家 " + attacker.getName() + " 拆除";
        RsAudio.log("[列车] 车头被玩家 %s 拆除 @ %s（伤害源 %s，已跑 %d 刻）",
                attacker.getName(), where(), source.getDamageType(),
                Integer.valueOf(ticksExisted));
        notifyNearby(I18n.translateToLocal("chat.rsaudio.train.removed"));
        setDead();
        return true;
    }

    @Override
    public void setDead() {
        if (!world.isRemote && !isDead) {
            // ★★ 车头消失必须留痕，而且必须能直接指到成因。
            //   这条日志存在的唯一目的：下一次"车头没了"不需要再花一晚上做取证。
            //   removalReason == null 意味着走了一条<b>预期之外</b>的移除路径 ——
            //   那句话本身就是线索（区块卸载、别的模组、/kill、World.removeEntity…）。
            RsAudio.log("[列车] 车头消失 @ %s（%s；已跑 %d 刻）", where(),
                    removalReason == null ? "★ 没走任何已知移除路径（可疑）" : removalReason,
                    Integer.valueOf(ticksExisted));
            if (removalReason == null) {
                // 玩家拆除那条路径已经当面播报过了；这条只在"预期之外"时才说 ——
                // 两者都播会变成一次拆除收到两条提示。
                notifyNearby(I18n.translateToLocal("chat.rsaudio.train.gone"));
            }
        }
        if (!world.isRemote) {
            // ★ 用原版的 removePassengers（它是 public 的，且会正确处理
            //   ridingEntity 双向解绑）。自己写一份的后果是乘客那头还记着"我在车上"，
            //   于是人卡在空气里动不了 —— 所以这里刻意不重复实现。
            removePassengers();
        }
        super.setDead();
    }

    /**
     * 掉出世界（原版 {@code onEntityUpdate} 在 {@code posY < -64} 时调它，默认 {@code setDead}）。
     * <p>
     * ★ 对本类来说这是<b>不可能发生</b>的事：车头的 y 由内核按轨道格的 y 算出来
     * （{@code rail.y + RAIL_RIDE_HEIGHT}），而轨道都在玩家建的地方。真发生了说明
     * 内核给的坐标坏了 —— 那就更不能按原版静默删掉：删掉之后连"坏坐标是多少"都没了。
     * 这里把它拉回 y≥0，下一 tick {@code applyCorePosition()} 会按轨道重设。
     */
    @Override
    protected void outOfWorld() {
        RsAudio.log("[列车] 车头掉出世界（坐标 %.3f,%.3f,%.3f）—— 已拦下，"
                + "下一 tick 会按轨道重设（正常永远不该发生，请查这台车的内核状态）",
                Double.valueOf(posX), Double.valueOf(posY), Double.valueOf(posZ));
        setPosition(posX, 0.0D, posZ);
    }

    /** 日志用的坐标串。★ 项目约定：日志必带坐标 */
    private String where() {
        return String.format("%.1f,%.1f,%.1f", Double.valueOf(posX), Double.valueOf(posY),
                Double.valueOf(posZ));
    }

    // ================================================================ 渲染包围盒

    /**
     * ★★★ <b>必须罩住整列编组，不能只用"车头那一格"的包围盒。</b>
     *
     * <p>原版 {@code Entity.getRenderBoundingBox()} 返回 {@code getEntityBoundingBox()}，
     * 而本实体的碰撞箱只有车头那么大（{@code setSize(CAR_W*0.9, CAR_Y0+CAR_H)}）——
     * 车厢是<b>纯渲染</b>出来的、没有实体，它们落在车头后面最远
     * {@link TrainSpec#maxConsistLength()} 格处。
     *
     * <p>★★ 这个盒子不是"给影子用的"那种无关紧要的量：{@code RenderGlobal.renderEntities}
     * 拿它做<b>视锥剔除</b>——
     * <pre>
     *   boolean flag = renderManager.shouldRender(e, camera, ...) || e.isRidingOrBeingRiddenBy(player);
     *   Render.shouldRender: return e.isInRangeToRender3d(...)
     *       &amp;&amp; (e.ignoreFrustumCheck || camera.isBoundingBoxInFrustum(getRenderBoundingBox().grow(0.5)));
     * </pre>
     * 剔除是<b>整实体一起</b>的（没有"逐节车厢"这回事）。所以盒子只罩车头时会出现：
     * <ul>
     *   <li>看着车头 —— 车厢在你背后，本来就看不见；</li>
     *   <li>转头去看车厢 —— 车头出了视锥 → <b>整列车被剔除</b>，眼前什么都没有。</li>
     * </ul>
     * 玩家能给出的描述只有一句：<b>"根本没有车厢"</b>（2026-09-23 实锤）。
     *
     * <p>★ 长度按 {@link TrainSpec#maxConsistLength()}（上限）算，理由见那边。
     * 方向按当前朝向摆：车头朝向 = {@code (-sin yaw, 0, cos yaw)}，车厢挂在<b>反</b>方向 ——
     * 同一套符号约定在 {@code TrainRenderer} 里推过一遍（那里用 {@code -yaw} 做 GL 旋转）。
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        AxisAlignedBB head = super.getRenderBoundingBox();
        double back = TrainSpec.maxConsistLength();
        double rad = Math.toRadians(this.rotationYaw);
        double tailX = Math.sin(rad) * back;        // = -(-sin yaw) * back
        double tailZ = -Math.cos(rad) * back;
        // 头盒 ∪ 尾盒：这样横向仍然只用 ±半车宽（不会像"以车头为中心的正方体"那样
        // 把旁边十几格全圈进来），纵向一直罩到最后一节车厢。
        return head.union(head.offset(tailX, 0.0D, tailZ)).grow(1.0D);
    }

    // ================================================================ NBT

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        if (compound.hasKey("TrainState")) {
            int[] st = compound.getIntArray("TrainState");
            core.loadState(st);
            spawnInit = true;       // 读档之后不要再"找站台发车"
        }
        // 轨道图和站台不在 NBT 里 —— 它们是**世界的**状态，不是列车的状态。
        // 每次上线重扫一遍就有（scanWorld），存进去反而会出现"存档里的轨道
        // 和世界里的轨道不一致"这种没法排查的问题。
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setIntArray("TrainState", core.saveState());
    }

    // ================================================================ 生成数据

    /**
     * 实体生成时要多发一份数据。
     * <p>
     * ★ 这里发的是<b>长度 0</b> 的占位：列车没有"生成参数"
     * （它的一切都从内核状态恢复，而内核状态走 {@link PacketTrainSync}）。
     * 之所以还要实现 {@code IEntityAdditionalSpawnData}，是因为不实现它
     * Forge 会用另一条生成路径，而两条路径下实体的首次位置同步时机不同 ——
     * 实现它可以让服务端在实体出现在客户端之前就把位置定好，
     * 否则新车会先闪现在 (0,0,0) 再跳过来。
     */
    @Override
    public void writeSpawnData(ByteBuf buffer) {
        int[] st = core.saveState();
        buffer.writeInt(st.length);
        for (int i = 0; i < st.length; i++) {
            buffer.writeInt(st[i]);
        }
    }

    @Override
    public void readSpawnData(ByteBuf additionalData) {
        int len = additionalData.readInt();
        if (len < 0 || len > TrainSpec.STATE_SLOTS * 4) {
            return;
        }
        int[] st = new int[len];
        for (int i = 0; i < len; i++) {
            st[i] = additionalData.readInt();
        }
        core.loadState(st);
    }

    // ================================================================ 给外部用

    public TrainCore getCore() {
        return core;
    }

    /** 服务端把内核状态灌回来（读档 / 生成数据都用） */
    public void applyState(int[] st) {
        core.loadState(st);
    }

    /**
     * 立刻重扫一次世界。
     * <p>
     * 给"刚放下列车 / 刚改完线路"这种希望马上生效的场合用；
     * 平时靠 {@code scanTimer} 定期扫就够了。
     */
    public void rescanNow() {
        scanWorld();
        scanTimer = Math.max(1, core.settings().routeCheckTicks);
    }
}

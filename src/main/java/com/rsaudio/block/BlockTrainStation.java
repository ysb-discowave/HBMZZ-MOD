package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.train.TrainSpec;
import com.rsaudio.tile.TileTrainStation;
import com.rsaudio.util.DelayText;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 列车站台。铺在轨道<b>旁边</b>或<b>上方</b>，列车会自动找到它并停靠。
 *
 * <h3>★ 一座站台 = 一片连通的站台方块</h3>
 * 玩家右键其中任意一格都该操作<b>同一个</b>站台 —— 所以每次右键都要先把
 * "被点的那一格"归一化成"这座站台的身份证格"（见 {@link #identityOf}）：
 * <b>正交相邻（含上下）的站台方块算同一座，身份证格取其中最西北的那一格</b>
 * （x 最小 → z 最小 → y 最小，判据在零依赖的 {@link TrainSpec#betterIdentity}）。
 * <p>
 * 这样铺一格是一站、铺一条长月台也是一站，而且身份证格<b>由世界唯一决定</b>，
 * 不需要"站台有自己的主人格"这种隐藏状态 —— 那种状态一旦和实际方块不同步
 * （拆了一格、复制粘贴了一段），就会留下"永远找不到主人"的孤儿站台。
 * <p>
 * ★ 旧算法是"取所在 3 格网格的西北角"，它有个致命前提：<b>那一格必须真的铺了站台</b>。
 * 站台铺在 x=304 时锚点是 x=303，那一格是空气 → 整座站在扫描时凭空消失，
 * 列车报"线路上一个站点都没有"（2026-09-21 实测：玩家铺了三座单格站台，一座都没被认出来）。
 * 要求玩家把站台对齐到 3 的倍数是根本不成立的约定。
 *
 * <h3>站台自己判"能不能绑上轨道"吗</h3>
 * <b>不判。</b>站台只声明自己在哪；"下面有没有轨道"由列车扫描时实地去看
 * （{@code TrainCore.rebuildStationBindings}）。理由和 {@link TileTrainStation}
 * 上写的一样：唯一权威是每次的全量扫描。
 * 在这里缓存"我绑上了"的结论，玩家拆掉轨道之后它就会一直说"我绑着"。
 */
public class BlockTrainStation extends Block implements ITileEntityProvider {

    /** 站台顶面高度：比一格方块低 1/16，坐上去有"月台"的感觉 */
    private static final AxisAlignedBB PAD_BB =
            new AxisAlignedBB(0.0, 0.0, 0.0, 1.0, 0.9375, 1.0);

    public BlockTrainStation() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "train_station");
        setUnlocalizedName(RsAudio.MODID + ".train_station");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.0F);
        setResistance(30.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
    }

    // ------------------------------------------------------------------ 身份与分组

    /** 这一格是不是站台方块 */
    public static boolean isStation(IBlockAccess world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() instanceof BlockTrainStation;
    }

    public static TileTrainStation getTile(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileTrainStation ? (TileTrainStation) te : null;
    }

    /**
     * 把"一格站台方块"归一化成它所属那座站台的<b>身份证格</b>。
     * <p>
     * 规则：正交相邻（含竖直上下）的站台方块连成一座站台，身份证格 = 其中
     * x 最小、再 z 最小、最后 y 最小的那一格（{@link TrainSpec#betterIdentity}）。
     * <p>
     * ★ 这是本类<b>唯一</b>的身份判据，也是 {@code EntityTrain.scanStations} 用的同一个 ——
     * 扫描与右键必须同源，否则玩家改的那座站和列车读的那座站不是一个，
     * 表现是"站名改了但车上还是旧名"，没有任何报错。
     *
     * @param cache 跨多次调用的缓存（一次全量扫描里会反复问同一片站台）。键是格，值是身份证格。
     */
    public static BlockPos identityOf(World world, BlockPos start, Map<Long, BlockPos> cache) {
        if (!isStation(world, start)) {
            return start;
        }
        Long startKey = Long.valueOf(TrainSpec.cellKey(start.getX(), start.getY(), start.getZ()));
        BlockPos hit = cache.get(startKey);
        if (hit != null) {
            return hit;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
        Map<Long, BlockPos> seen = new HashMap<Long, BlockPos>();
        BlockPos best = start;
        seen.put(startKey, start);
        queue.add(start);
        // ★ 必须在**已加载**的区块里走。未加载区块上 getBlockState 会强制加载，
        //   那是很重的操作 —— 这个函数在实体 tick 里也会被调用。
        while (!queue.isEmpty() && seen.size() <= TrainSpec.PAD_MAX_CELLS) {
            BlockPos cur = queue.poll();
            if (TrainSpec.betterIdentity(cur.getX(), cur.getY(), cur.getZ(),
                    best.getX(), best.getY(), best.getZ())) {
                best = cur;
            }
            for (EnumFacing f : EnumFacing.values()) {
                BlockPos n = cur.offset(f);
                if (!world.isBlockLoaded(n)) {
                    continue;
                }
                long k = TrainSpec.cellKey(n.getX(), n.getY(), n.getZ());
                if (seen.containsKey(Long.valueOf(k)) || !isStation(world, n)) {
                    continue;
                }
                seen.put(Long.valueOf(k), n);
                queue.add(n);
            }
        }
        for (Long k : seen.keySet()) {
            cache.put(k, best);
        }
        return best;
    }

    /** 同上，自带一个空缓存（右键这种一次性调用用） */
    public static BlockPos identityOf(World world, BlockPos start) {
        return identityOf(world, start, new HashMap<Long, BlockPos>());
    }

    /**
     * 从任意一格站台取到它所属站台的方块实体 —— 先归一到身份证格，再取那格的实体。
     * <p>
     * ★ 站名与停站时间<b>只认身份证格那一份</b>。落在同一座站台里的其它格即使
     * 自带方块实体也不参与读取，所以不存在"两格各存一个名字、读到哪个看运气"的问题。
     */
    public static TileTrainStation getTileFor(World world, BlockPos pos) {
        return getTile(world, identityOf(world, pos));
    }

    /**
     * 这一格周围（26 邻域）最靠西北的那块<b>已有</b>站台方块的方块实体。
     * <p>
     * 只给 {@link #onBlockAdded} 的"扩站台"用：新放的一格要么不是身份证格
     * （从身份证格继承），要么自己就是新的身份证格（那就得从邻居里随便找一格继承，
     * 否则站名会留在旧格子上、新格子上是默认名，看起来像"站名丢了"）。
     * <p>
     * ★ 刻意用 26 邻域而不是全量连通搜索：这里只找一个"有数据的邻居"，
     * 而新格子必然是贴着已有站台放的（否则也没什么可继承）。
     */
    private static TileTrainStation sourceTileNear(World world, BlockPos pos) {
        TileTrainStation best = null;
        BlockPos bestPos = null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos p = pos.add(dx, dy, dz);
                    if (!world.isBlockLoaded(p) || !isStation(world, p)) {
                        continue;
                    }
                    TileTrainStation t = getTile(world, p);
                    if (t == null) {
                        continue;
                    }
                    if (bestPos == null || TrainSpec.betterIdentity(p.getX(), p.getY(), p.getZ(),
                            bestPos.getX(), bestPos.getY(), bestPos.getZ())) {
                        bestPos = p;
                        best = t;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileTrainStation();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileTrainStation();
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 站台是实心台阶：可以走上去站着等车。
     * <p>
     * ★ 返回的是<b>绝对坐标</b>，见 {@code BlockBlastDoor} 里记过的那次事故。
     */
    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return PAD_BB.offset(pos);
    }

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return PAD_BB;
    }

    /**
     * 不是完整方块（顶面矮 1/16），但仍是不透明实心 —— 所以这里
     * {@code isOpaqueCube} 给 true：站台不该透视，它是块实心月台。
     * <p>
     * ★ 只把 {@code isFullCube} 给 false 就够了。两个都给 false 的话
     * 站台会变成"半透明方块"，会去走 {@code getRenderType} 的透明渲染路径，
     * 边缘出现一圈诡异的接缝。
     */
    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.MODEL;
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 空手右键 = 看一眼这个站台（报站名 + 停站时间 + 是否绑上轨道）。
     * 手持木斧潜行右键 = 打开设置界面（走魔杖那条路，这里返回 false 让给它）。
     * <p>
     * ★ 这里<b>只做只读的提示</b>，不做"点一下改时间"这种隐式修改：
     * 站台的参数要在界面里改，因为改错一个值的后果（车到站不停 / 停半天不走）
     * 在游戏里看不出来，必须有一个能同时看到当前值和范围的界面。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        ItemStack held = playerIn.getHeldItemMainhand();
        if (!held.isEmpty() && held.getItem() == Items.WOODEN_AXE) {
            // 交给魔杖（打开设置界面）
            return false;
        }
        if (!worldIn.isRemote) {
            // ★ 先归一到身份证格再取数据 —— 一座站台的任意一格都该报同一份信息
            BlockPos identity = identityOf(worldIn, pos);
            TileTrainStation tile = getTile(worldIn, identity);
            if (tile == null) {
                playerIn.sendStatusMessage(new TextComponentString(
                        I18n.translateToLocal("chat.rsaudio.train.station_no_tile")), true);
                return true;
            }
            // ★ 走 I18n，不写死中文。写死的话英文玩家看到中文，而且这条文案
            //   在 verify_jar 的 REQUIRED_LANG 检查里是隐形的（它只查键存在与否，
            //   查不到"有一句话根本没走键"）—— 那种缺陷只能靠人读代码发现，
            //   所以这里刻意和项目其他提示保持一致的做法。
            String info;
            if (identity.equals(pos)) {
                info = I18n.translateToLocalFormatted("chat.rsaudio.train.station_info",
                        tile.getName(), DelayText.formatDuration(tile.getDwellTicks()));
            } else {
                info = I18n.translateToLocalFormatted("chat.rsaudio.train.station_info_anchor",
                        tile.getName(), DelayText.formatDuration(tile.getDwellTicks()),
                        identity.getX(), identity.getY(), identity.getZ());
            }
            playerIn.sendStatusMessage(new TextComponentString(info), true);
        }
        return true;
    }

    /**
     * 拆站台：只拆这一格。
     * <p>
     * ★ 刻意<b>不</b>连带拆掉整个 3×3。这和塔塔鲁斯/防爆门"拆一格拆整扇"的约定不同，
     * 因为站台是<b>玩家一格一格铺出来的</b>，不是一个由控制器生成的整体。
     * 玩家想把 3×3 改成 2×3 是合理需求；连带拆除会让他必须全部重铺。
     * <p>
     * 列车那边不需要通知：下一次全量扫描自然发现这里没站台了，
     * 报 {@code FAIL_STATION_GONE}。这正是"站台不 tick、只被扫"的好处。
     */
    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        super.breakBlock(worldIn, pos, state);
    }

    /**
     * 放下时<b>继承</b>旁边那座站台的名字和停站时间。
     * <p>
     * ★ 为什么：玩家常见的操作是"把一座站台往旁边扩一圈"。
     * 如果新放的那几格不继承，扩完之后站名就会在身份证格之间飘 ——
     * 右键扩出来的格子看到的是默认名，看起来像"站名丢了"。
     * <p>
     * ★★ <b>为什么必须推迟一刻（这里是本次踩到的死代码坑）</b>：
     * {@code onBlockAdded} 被调用时本站台的方块实体<b>还不存在</b>。
     * 证据在 {@code Chunk.setBlockState} 的字节码里（javap -c 反汇编）：
     * <pre>
     *   507: invokevirtual Block.onBlockAdded(...)      ← 这里
     *   510: hasTileEntity
     *   541: invokevirtual Block.createTileEntity(...)  ← 方块实体到这儿才创建
     *   553: invokevirtual World.setTileEntity(...)
     * </pre>
     * 507 &lt; 541 &lt; 553，一条直线，没有任何分支能把顺序反过来。
     * 所以直接在 {@code onBlockAdded} 里 {@code getTile(worldIn, pos)} 拿到的是
     * <b>null</b>，老代码会 "if (self == null) return;" 直接走掉 ——
     * 语法检查、编译、进游戏都毫无异常，继承却<b>从来没生效过</b>。
     * <p>
     * 现在的做法：{@code onBlockAdded} 只负责<b>预约一次方块更新</b>，
     * 真正的继承在下一 tick 的 {@link #updateTick} 里做 —— 那时方块实体必然已建好。
     * ★ 这条路径覆盖<b>所有</b>放置方式（玩家放置 / {@code /setblock} / 结构方块），
     * 不需要再挂一个 {@code onBlockPlacedBy} 走第二条分支
     * （两条分支的时序差一刻，晚跑的那条会把玩家刚改的名字覆盖回去）。
     */
    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        super.onBlockAdded(worldIn, pos, state);
        if (worldIn.isRemote) {
            return;
        }
        // 只预约，不落笔 —— 这一刻本站台还没有方块实体（见上面字节码）。
        worldIn.scheduleBlockUpdate(pos, this, 1, 0);
    }

    /**
     * 只由 {@link #onBlockAdded} 预约的那一次更新驱动（本方块<b>不是</b>
     * {@code setTickRandomly(true)}，所以不会被随机 tick 反复调用）。
     */
    @Override
    public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand) {
        inheritFromNeighbour(worldIn, pos);
    }

    /** 把周围最靠西北的那座已有站台的名字/停站时间抄到自己身上。幂等。 */
    private static void inheritFromNeighbour(World worldIn, BlockPos pos) {
        TileTrainStation self = getTile(worldIn, pos);
        if (self == null) {
            // 理论上到不了这里（预约发生在方块实体创建之后）。
            // 真到了说明"方块实体没建起来"，那是个更严重的注册问题，直接放弃。
            return;
        }
        TileTrainStation src = sourceTileNear(worldIn, pos);
        if (src == null || src == self) {
            return;
        }
        self.setName(src.getName());
        self.setDwellTicks(src.getDwellTicks());
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState();
    }
}

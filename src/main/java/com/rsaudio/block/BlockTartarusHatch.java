package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.tile.TileTartarus;
import net.minecraft.block.Block;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

/**
 * 塔塔鲁斯的<b>圆形舱盖格</b>：由 {@link BlockTartarus} 控制器在建造时生成，玩家拿不到它的物品。
 * <p>
 * 它<b>不画任何模型</b>（渲染类型 {@code INVISIBLE}）—— 真正可见的圆形金属舱盖盘和中心枢纽，
 * 全部由控制器那一个方块实体上的 {@code TartarusRenderer} 画出来。这一格只负责"挡不挡路"：
 * 关着时是实心（走上去是地板），开了就可通过（沉进竖井）。
 * <p>
 * 拆掉任意一格舱盖 = 拆掉整扇舱盖（含控制器），避免留下拆不掉的残骸。
 */
public class BlockTartarusHatch extends Block {

    /** 是否已开（开了就可通过） */
    public static final PropertyBool OPEN = PropertyBool.create("open");

    public BlockTartarusHatch() {
        super(net.minecraft.block.material.Material.IRON);
        setRegistryName(RsAudio.MODID, "tartarus_hatch");
        setUnlocalizedName(RsAudio.MODID + ".tartarus_hatch");
        setHardness(50.0F);
        setResistance(6000.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(OPEN, Boolean.FALSE));
    }

    public static IBlockState stateFor(boolean open) {
        return com.rsaudio.ModBlocks.TARTARUS_HATCH.getDefaultState().withProperty(OPEN, open);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, OPEN);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(OPEN, (meta & 1) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(OPEN) ? 1 : 0;
    }

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return state.getValue(OPEN) ? NULL_AABB : FULL_BLOCK_AABB;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, net.minecraft.entity.player.EntityPlayer player) {
        return false;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            BlockPos controller = findController(worldIn, pos);
            if (controller != null) {
                TileTartarus tile = BlockTartarus.getTile(worldIn, controller);
                if (tile != null) {
                    tile.removeHatch(true);
                }
            } else {
                // ★★★ 无主舱盖：控制器已经不在了（被拆、存档残留、结构被改坏），
                //   这一格舱盖<b>没有任何东西能驱动它消失</b> ——
                //   原来的代码走到这里就直接 super.breakBlock，只清掉玩家挖的那一格。
                //   实测存档里躺着 <b>1870 格</b>这样的残骸：玩家得手挖 1870 下才清得干净，
                //   而舱盖方块玩家又拿不到它的物品（挖了也不掉东西）—— 纯垃圾。
                //   所以这里做一次<b>有界</b>的连带清理：把与这一格连通的那一整片一起清掉。
                int n = removeOrphanCluster(worldIn, pos);
                if (n > 0) {
                    RsAudio.log("塔塔鲁斯：清理无主舱盖 %d 格，起点 (%d,%d,%d)",
                            n, pos.getX(), pos.getY(), pos.getZ());
                    notifyNearby(worldIn, pos, n);
                } else if (n < 0) {
                    // ★ 不清理时必须说清楚为什么 —— 玩家挖了半天没反应是最糟的体验。
                    RsAudio.log("塔塔鲁斯：(%d,%d,%d) 有无主舱盖，但本轮不清理（%s）",
                            pos.getX(), pos.getY(), pos.getZ(),
                            n == CLEAN_UNLOADED ? "区块没加载完" : "那一片太大");
                    notifyBlocked(worldIn, pos, n);
                }
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    /** 一片太大（超过这个格数）就不敢一次清掉，先让玩家走近确认 */
    public static final int CLEAN_MAX_CELLS = 4096;
    /** 返回码：区块没加载完，这一轮不下结论 */
    public static final int CLEAN_UNLOADED = -1;
    /** 返回码：那一片太大 */
    public static final int CLEAN_TOO_BIG = -2;

    /**
     * 清掉与 from 连通的<b>一整片无主舱盖</b>。
     *
     * <h3>★ 为什么必须"整片一起清"</h3>
     * 控制器不在了之后，舱盖格之间没有任何主从关系 —— 一格一格挖是唯一的出路，
     * 而实际的一片有上千格。所以由玩家挖的那一格当引子，洪泛出整片。
     *
     * <h3>★★ 两道保护，缺一不可</h3>
     * <ol>
     *   <li><b>只清连通的</b>：洪泛走六邻域正交，只认舱盖方块。同一片圆盘是连通的，
     *       而"几层楼叠起来的两片"如果不贴在一起就分别清理，不会误伤；</li>
     *   <li><b>整片都在已加载的区块里才敢动</b>：控制器有可能只是落在未加载的区块里
     *       （{@link #findController} 读不到未加载区块）—— 那种情况下这片舱盖是<b>好的</b>，
     *       清掉就是删玩家建筑。所以先确认包围盒（外扩 1 格）全部加载完。</li>
     * </ol>
     *
     * @return 清掉了几格；0 = 什么也没清（都失败或被挡）；负数见 {@link #CLEAN_UNLOADED} /
     *         {@link #CLEAN_TOO_BIG}
     */
    public static int removeOrphanCluster(World world, BlockPos from) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
        Map<Long, BlockPos> seen = new HashMap<Long, BlockPos>();
        seen.put(Long.valueOf(clusterKey(from)), from);
        queue.add(from);
        int minX = from.getX();
        int maxX = from.getX();
        int minY = from.getY();
        int maxY = from.getY();
        int minZ = from.getZ();
        int maxZ = from.getZ();
        while (!queue.isEmpty()) {
            BlockPos cur = queue.poll();
            if (cur.getX() < minX) {
                minX = cur.getX();
            }
            if (cur.getX() > maxX) {
                maxX = cur.getX();
            }
            if (cur.getY() < minY) {
                minY = cur.getY();
            }
            if (cur.getY() > maxY) {
                maxY = cur.getY();
            }
            if (cur.getZ() < minZ) {
                minZ = cur.getZ();
            }
            if (cur.getZ() > maxZ) {
                maxZ = cur.getZ();
            }
            if (seen.size() > CLEAN_MAX_CELLS) {
                return CLEAN_TOO_BIG;
            }
            for (int[] o : CLUSTER_NEIGHBOURS) {
                BlockPos n = cur.add(o[0], o[1], o[2]);
                Long k = Long.valueOf(clusterKey(n));
                if (seen.containsKey(k)) {
                    continue;
                }
                // 未加载区块上读方块会强加载 —— 那是很重的操作，玩家挖方块时更不能做
                if (!world.isBlockLoaded(n)) {
                    continue;
                }
                if (!(world.getBlockState(n).getBlock() instanceof BlockTartarusHatch)) {
                    continue;
                }
                seen.put(k, n);
                queue.add(n);
            }
        }
        // ---- 保护 2：整片（外扩 1 格）都在已加载的区块里才敢清
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 1; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    if (!world.isBlockLoaded(new BlockPos(x, y, z))) {
                        return CLEAN_UNLOADED;
                    }
                }
            }
        }
        int n = 0;
        for (BlockPos p : seen.values()) {
            if (world.getBlockState(p).getBlock() instanceof BlockTartarusHatch) {
                // ★ 刻意不掉落物品：这是一片已经被遗弃的残骸（控制器都没了），
                //   一千多格一起掉会直接把世界卡住，而且玩家本来就拿不到它的物品。
                world.setBlockToAir(p);
                n++;
            }
        }
        return n;
    }

    /** 洪泛用的邻居：六邻域正交（与"一整片圆盘"的直觉一致，也和内核的尺子一样） */
    private static final int[][] CLUSTER_NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 0, 1}, {0, 0, -1},
            {0, 1, 0}, {0, -1, 0},
    };

    /** 只用于 Map 查找的键，绝不解包还原坐标 */
    private static long clusterKey(BlockPos p) {
        long h = 1125899906842597L;
        h = h * 31 + p.getX();
        h = h * 31 + p.getY();
        h = h * 31 + p.getZ();
        return h;
    }

    /** 告诉附近玩家"清掉了多少格" —— 不然世界突然少一大片方块会很吓人 */
    private static void notifyNearby(World world, BlockPos pos, int n) {
        for (net.minecraft.entity.player.EntityPlayer pl : world.playerEntities) {
            if (pl.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                    <= 64.0D * 64.0D) {
                pl.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(
                        "chat.rsaudio.tartarus.orphan_cleared", n), true);
            }
        }
    }

    /** 没清成也要说清楚原因（区块没加载完 / 那片太大） */
    private static void notifyBlocked(World world, BlockPos pos, int code) {
        String key = code == CLEAN_UNLOADED
                ? "chat.rsaudio.tartarus.orphan_unloaded"
                : "chat.rsaudio.tartarus.orphan_too_big";
        for (net.minecraft.entity.player.EntityPlayer pl : world.playerEntities) {
            if (pl.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                    <= 64.0D * 64.0D) {
                pl.sendStatusMessage(new net.minecraft.util.text.TextComponentTranslation(key), true);
            }
        }
    }

    /**
     * 找"覆盖了某个位置"的控制器：在附近有限范围里找一次。
     * 舱盖格不存自己的主人，所以拆舱盖格时反查（见 {@link BlockTartarus#getTile}）。
     *
     * <h3>★ 为什么不是"整个方形扫一遍"</h3>
     * 最直观的写法是 {@code for dx in -r..r for dz in -r..r}，r 取
     * {@code MAX_RADIUS + 2}。在半径上限是 8 的时候那是 21×21×3 ≈ 1300 次查询，
     * 感觉无所谓；但上限提到 <b>32</b> 之后它变成 <b>67×67×3 ≈ 13500 次</b>
     * {@code getBlockState}，而这是**玩家每挖掉一格舱盖就付一次**的代价 ——
     * 挖一下卡半秒，拆一整扇 3000 格的舱盖会卡到不能用。
     *
     * <p>所以改成<b>按半径由小到大一圈圈找，命中就立刻返回</b>：
     * <ul>
     *   <li>控制器就在被拆的那格附近时（绝大多数情况），第一两圈就命中，
     *       实际查询量只有几十次；</li>
     *   <li>只有"这块舱盖格离控制器很远"（大半径盘的边缘）才会真的扫到外圈，
     *       但那种情况本来也躲不掉。</li>
     * </ul>
     * 用的是切比雪夫距离（正方形环），因为判定 {@code contains()} 是圆形的 ——
     * 控制器一定在 {@code dx²+dz² <= r²} 内，所以扫到第 r 圈必然覆盖到它，
     * 不会漏。
     *
     * <p>★ 注意不能改成"只扫第一圈就算"：那是靠"控制器一般在中心附近"的假设，
     * 而拆边缘格时控制器可能在 30 格外，会**静默**找不到主人
     * （表现为挖掉一格舱盖、整扇舱盖没跟着消失，留下拆不掉的残骸）。
     */
    public static BlockPos findController(World world, BlockPos from) {
        int rMax = TileTartarus.MAX_RADIUS + 2;
        for (int ring = 0; ring <= rMax; ring++) {
            for (int dy = 0; dy <= 2; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        // 只走"这一圈的外框"，里面的格子上一轮已经查过
                        if (ring > 0
                                && Math.abs(dx) != ring && Math.abs(dz) != ring) {
                            continue;
                        }
                        BlockPos candidate = from.add(dx, -dy, dz);
                        if (!world.isBlockLoaded(candidate)) {
                            continue;
                        }
                        if (!(world.getBlockState(candidate).getBlock() instanceof BlockTartarus)) {
                            continue;
                        }
                        TileTartarus tile = BlockTartarus.getTile(world, candidate);
                        if (tile != null && tile.contains(from)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }
}

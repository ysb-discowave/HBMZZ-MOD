package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.entity.EntityTrain;
import com.rsaudio.train.TrainSpec;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 列车轨道。玩家一节一节铺，铺成环线之后列车就能跑。
 *
 * <h3>形状由邻居决定，不由玩家决定</h3>
 * 方块只有一个<b>只读的显示属性</b> {@link #SHAPE}（4 个 bit = 四个水平方向接没接上），
 * 由 {@link #getActualState} 每次渲染时<b>现算</b> —— 和 {@code BlockRedstoneDelayer}
 * 的刻度条同一个路子。
 * <p>
 * ★ <b>为什么不把形状写进 meta：</b>邻居是会变的。玩家在它旁边再接一节轨、
 * 或者把旁边那节拆了，这一格的形状就必须跟着变。写进 meta 就意味着"每个会改变邻居的
 * 操作都要记得回来改这一格"，漏一个就是"轨道看着断了/看着连着但实际不通"。
 * 现算则天然永远跟世界一致 —— 代价是渲染时多几次 {@code getBlockState}，可以接受。
 *
 * <h3>★ 判"接上"的尺子和内核必须一致</h3>
 * 这里判邻居用的高差上限是 {@link TrainSpec#MAX_RAIL_STEP}，和
 * {@code TrainCore.buildAdjacency} 用的是同一个常量。
 * 两边不一致的后果非常隐蔽：方块说"接上了"（画出连通的模型），
 * 内核说"太远不算"（收不进邻接表）—— 玩家看到的是一列停在断口上的车，
 * 而轨道在视觉上明明是连着的。
 *
 * <h3>可通行</h3>
 * 轨道不是实心方块：车要走在上面，玩家也要能站上去。
 * 碰撞盒是<b>贴着地面的一块薄板</b>（{@code 1/8} 格高），走到轨道上等于走在铁轨上，
 * 不会像走方砖那样突然抬高半格。
 */
public class BlockTrainRail extends Block {

    /**
     * 四个水平方向接没接上，位掩码见 {@link TrainSpec#SHAPE_NORTH} 等。
     * <p>
     * 取值范围 {@code 0..15}，所以用 {@code PropertyInteger} 而不是 4 个
     * {@code PropertyBool} —— 后者会让方块状态组合数从 16 涨到 16 的等效
     * （实际也还是 16，但会让 blockstate json 需要写 16 个变体而不是 16 个，
     * 而且读代码时要同时看 4 个属性才知道形状）。
     */
    public static final PropertyInteger SHAPE =
            PropertyInteger.create("shape", 0, TrainSpec.SHAPE_MAX);

    /** 轨面高度：一格方块的底部往上 1/8 格（和原版铁轨一样的高度感） */
    public static final float RAIL_HEIGHT = 0.125F;
    private static final AxisAlignedBB RAIL_BB =
            new AxisAlignedBB(0.0, 0.0, 0.0, 1.0, RAIL_HEIGHT, 1.0);

    public BlockTrainRail() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "train_rail");
        setUnlocalizedName(RsAudio.MODID + ".train_rail");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(2.0F);
        setResistance(30.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(SHAPE, 0));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, SHAPE);
    }

    /** meta 只是形状的缓存，真正的值每次渲染由 getActualState 现算 */
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(SHAPE, meta & TrainSpec.SHAPE_MAX);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(SHAPE) & TrainSpec.SHAPE_MAX;
    }

    /**
     * 现算这一格连着哪几个方向。
     * <p>
     * ★ 这个方法在区块重建渲染时被<b>高频</b>调用，绝对不能抛异常（会直接崩渲染），
     * 所以每一步都做过防御。和 {@code BlockRedstoneDelayer#getActualState} 同样的纪律。
     */
    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        int shape = 0;
        try {
            for (EnumFacing f : HORIZONTALS) {
                if (connectsTo(worldIn, pos, f)) {
                    shape |= bitFor(f);
                }
            }
        } catch (Throwable ignored) {
            // 读邻居出了意外就退回"孤立轨"，反正只是个显示。
            // 宁可画成孤立，也不能让渲染线程崩掉。
            shape = 0;
        }
        return state.withProperty(SHAPE, shape);
    }

    private static final EnumFacing[] HORIZONTALS = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };

    /** 方向 -> 形状位 */
    public static int bitFor(EnumFacing f) {
        switch (f) {
            case NORTH:
                return TrainSpec.SHAPE_NORTH;
            case SOUTH:
                return TrainSpec.SHAPE_SOUTH;
            case WEST:
                return TrainSpec.SHAPE_WEST;
            case EAST:
                return TrainSpec.SHAPE_EAST;
            default:
                return 0;
        }
    }

    /**
     * 某一方向上那格算不算"接上了"（形状位只描述<b>水平</b>连接）。
     * <p>
     * ★★ 这里的判据必须和内核 {@code TrainCore.buildAdjacency} <b>逐字对应</b>。
     * 内核收邻居用的是六邻域（正交的 6 个方向：水平 4 个 + 竖直 2 个），
     * 也就是说：
     * <ul>
     *   <li><b>同级水平邻居</b>算接上（这是形状位主要描述的）；</li>
     *   <li><b>竖直邻居（同一 x/z、y ± 1）</b>算接上 —— 这就是坡道；</li>
     *   <li><b>对角（水平 + 竖直同时差 1）不算接上</b>，内核收不到它。</li>
     * </ul>
     * ★ 我第一版写的是"水平那一格的上/下也算坡道"（即对角），
     * 那和内核的六邻域不一致 —— 后果正是本项目记过的那类隐蔽 bug：
     * <b>方块画成连通的，列车却收不到那个邻居，车停在断口上而轨道看起来是连的</b>。
     * 所以这里只认"同一层"。
     * <p>
     * 竖直邻居不占形状位：一格轨道的形状是"它在自己这一层往四个水平方向接了谁"，
     * 竖直那部分由渲染层另外表现（坡道不需要额外的形状位。
     * 格子本身已经能看出它上下有东西）。
     */
    private static boolean connectsTo(IBlockAccess world, BlockPos pos, EnumFacing f) {
        return isRail(world, pos.offset(f));
    }

    private static boolean isRail(IBlockAccess world, BlockPos p) {
        return world.getBlockState(p).getBlock() instanceof BlockTrainRail;
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 碰撞盒是贴地薄板。
     * <p>
     * ★ 原版 {@code Block#getCollisionBoundingBox} 的约定是返回<b>绝对坐标</b>
     * （默认实现是 {@code getBoundingBox(...).offset(pos)}，见 {@code BlockBlastDoor}
     * 里记过的那次事故：返回 {@code 0..1} 的局部盒会让整扇门的碰撞全落在世界原点那一格）。
     * 这里返回的是 {@code pos} 偏移过的绝对值。
     */
    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return RAIL_BB.offset(pos);
    }

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return RAIL_BB;
    }

    /** 不是完整方块：不遮挡、能透光、能看见下面的东西 */
    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isPassable(IBlockAccess worldIn, BlockPos pos) {
        // 车要走在上面、玩家要能站上去，所以不算"可穿过"
        return false;
    }

    /**
     * 轨道靠邻居决定形状，所以任何邻居变动都要让这一格重新渲染。
     * <p>
     * ★ 不写这个方法的话，玩家在旁边接一节轨，新轨和旧轨的接缝处永远画不出来，
     * 直到区块被卸载重载 —— 看起来就是"铺了轨但没连上"。
     */
    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        super.onBlockAdded(worldIn, pos, state);
        refreshNeighbours(worldIn, pos);
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        super.neighborChanged(state, worldIn, pos, blockIn, fromPos);
        // 邻居变了，形状可能变；把这一格和它四个邻居都标脏重新渲染
        refreshNeighbours(worldIn, pos);
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        // 拆之前先让邻居知道，否则它们会保持"画着接缝"的样子
        refreshNeighbours(worldIn, pos);
        super.breakBlock(worldIn, pos, state);
    }

    private static void refreshNeighbours(World world, BlockPos pos) {
        if (world.isRemote) {
            return;
        }
        // 这一格 + 四个水平邻居（含上下层的坡道邻居）都要重画。
        // 用 notifyBlockUpdate 而不是 markBlockRangeForRenderUpdate：
        // 后者会把整片区域重画，铺一整条线时会明显掉帧。
        world.notifyBlockUpdate(pos, world.getBlockState(pos), world.getBlockState(pos), 2);
        for (EnumFacing f : HORIZONTALS) {
            BlockPos n = pos.offset(f);
            for (int dy = -1; dy <= 1; dy++) {
                BlockPos q = n.up(dy);
                if (world.getBlockState(q).getBlock() instanceof BlockTrainRail) {
                    world.notifyBlockUpdate(q, world.getBlockState(q), world.getBlockState(q), 2);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 放置

    /**
     * 放下时不要求朝向 —— 形状由邻居现算。
     * <p>
     * ★ 但这里刻意把玩家朝向相关的处理留了注释：将来如果要加"手动转向"，
     * 入口就在这里（把朝向写进一个 meta 位当<b>偏好</b>，仍由 getActualState
     * 结合邻居裁剪）。现在不做，是因为"玩家手动转向却因为没邻居而看不出效果"
     * 是比"形状自动算"更让人困惑的交互。
     */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState();
    }

    // ------------------------------------------------------------------ 放车

    /**
     * 潜行 + 右键轨道 = 在脚下这段轨道上放一台列车。
     * <p>
     * ★ 为什么用"潜行右键轨道"而不是做一个"列车生成器"物品：
     * <ol>
     *   <li>本模组至今只有方块、没有自定义 {@code Item} 类，加一个物品要多接 5 处注册
     *       （见项目笔记里"有物品的方块必须接线 5 处"），为了一个一次性动作不值得；</li>
     *   <li>"车放哪儿"这个问题只有轨道能回答 —— 让玩家<b>指着轨道</b>放车，
     *       意图比"拿着生成器对着空气右键"明确得多；</li>
     *   <li>潜行是必要的限定词：不潜行的话右键轨道会被玩家用来表达"我想看看/操作这条轨道"，
     *       而放车是个有副作用的动作。</li>
     * </ol>
     * ★ 同一段轨道上<b>只允许一台车</b>：两台车重叠会一起抢占同一格轨道，
     * 表现是它们互相穿插（内核各算各的，谁也不知道对方存在）。
     * 这里做一个 4 格内的查重，把"已经有车了"明确告诉玩家。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!playerIn.isSneaking()) {
            return false;               // 交给别的逻辑（比如手持木斧的魔杖）
        }
        if (worldIn.isRemote) {
            return true;                // 客户端只负责不再往下传
        }
        if (!(worldIn.getBlockState(pos).getBlock() instanceof BlockTrainRail)) {
            return false;
        }
        // 查重：附近 4 格内已经有车就别再造
        List<EntityTrain> near = worldIn.getEntitiesWithinAABB(EntityTrain.class,
                new AxisAlignedBB(pos).grow(4.0D));
        if (!near.isEmpty()) {
            RsAudio.log("[列车] 放车被拒（4 格内已有车 %d 台）：%s @ %d,%d,%d",
                    Integer.valueOf(near.size()), playerIn.getName(),
                    Integer.valueOf(pos.getX()), Integer.valueOf(pos.getY()), Integer.valueOf(pos.getZ()));
            playerIn.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocal("chat.rsaudio.train.already_here")), true);
            return true;
        }
        EntityTrain train = new EntityTrain(worldIn);
        // 放在这一格轨道的中心偏上一点（轨道只有 1/8 格高）
        train.setPosition(pos.getX() + 0.5D, pos.getY() + TrainSpec.RAIL_RIDE_HEIGHT,
                pos.getZ() + 0.5D);
        worldIn.spawnEntity(train);
        // ★ 内核要先把世界读进去才知道自己踩在哪条线上，所以造出来立刻重扫一次。
        //   不然第一 tick 它会以为四周没有轨道，直接报"脱轨"。
        train.rescanNow();
        // ★ 必须有一条"车确实生成了"的日志。这台机器的整条链路（放置 -> 首次 rebase ->
        //   找站台）全在实体内部，静默失败时玩家只会说"列车没有"，而日志里空无一物。
        //   2026-09-20 那次排查就是靠"debug.log 里一条 [列车] 都没有"才断定
        //   放车动作<b>从未触发</b>（而不是触发后失败）。
        RsAudio.log("[列车] 放车：%s @ %d,%d,%d -> 实体 #%d（轨道方块在 %d,%d,%d）",
                playerIn.getName(), Integer.valueOf(pos.getX()), Integer.valueOf(pos.getY()),
                Integer.valueOf(pos.getZ()), Integer.valueOf(train.getEntityId()),
                Integer.valueOf(pos.getX()), Integer.valueOf(pos.getY()), Integer.valueOf(pos.getZ()));
        playerIn.sendStatusMessage(new TextComponentString(
                I18n.translateToLocal("chat.rsaudio.train.spawned")), true);
        return true;
    }

    // ------------------------------------------------------------------ 查询接口

    /** 这一格当前的连接形状（读方块状态，不是现算 —— 调用方多半已经有了状态对象） */
    public static int shapeOf(IBlockState state) {
        Integer v = state.getValue(SHAPE);
        return v == null ? 0 : v;
    }

    /** 由形状位直接得到内核要的走向码 */
    public static int dirOf(IBlockState state) {
        return TrainSpec.dirFromShape(shapeOf(state));
    }
}

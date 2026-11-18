package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 巨大管道。设施里那种一人高的冷风管 / 工艺管，靠<b>邻居自动接出走向</b>。
 *
 * <h3>形状现算，不写 meta</h3>
 * 四个水平方向各是一个 {@link PropertyBool}（{@code east/north/south/west}），
 * 由 {@link #getActualState} 每次渲染现算 —— 和 {@link BlockTrainRail} 同一个路子，
 * 理由也一样：邻居变了形状就必须跟着变，写进 meta 就意味着"每个会改变邻居的操作
 * 都得记得回来改这一格"，漏一个就是"看着连上了、实际是个断头"。
 *
 * <h3>★★ 为什么只剩 4 个属性（16 态）</h3>
 * 本方块最初是六个方向各一个 {@link PropertyBool}（{@code down/east/north/south/up/west}），
 * 64 种组合，{@link #getMetaFromState} 返回 0..63。<b>1.12.2 的 meta 只有 4 位</b>：
 * 方块状态是按 {@code blockId << 4 | meta} 编号的，超过 15 就越界，
 * 注册时 {@code ForgeRegistry.register} 直接抛
 * {@code ArrayIndexOutOfBoundsException: 63}，整个 mod 在主菜单之前就崩。
 * <b>16 态是硬上限，没有绕开的余地</b>，所以竖直方向不再拥有独立状态：
 * <ul>
 *   <li>状态容器只有水平四位（16 种）；</li>
 *   <li>完全没有水平接口时按<b>竖直段</b>渲染（mask 17 = down|up），
 *       模型和碰撞盒都取这一个值，两者永远一致；</li>
 *   <li>有水平接口时只画水平臂，竖直臂画不出来（见下方"行为变化"）。</li>
 * </ul>
 *
 * <h3>★ 位掩码只在"资源侧"有意义</h3>
 * Java 侧<b>不需要</b>掩码：MC 自己把属性表序列化成查询串。
 * 掩码只是资源侧给模型命名的（{@code giant_pipe_m<mask>}），
 * 位序沿用旧版 6 位：{@code east=2, north=4, south=8, west=32}（{@code down=1, up=16} 保留位序），
 * 所以 64 个模型文件<b>一个都不用动</b>，只是取其中 16 个。
 * 两边唯一的耦合是 {@link #FACINGS} 的<b>顺序</b>必须和那边的循环顺序一致 ——
 * 一旦不一致，"哪一种走向用哪个模型"就错位了（视觉上像管道接到了空气里）。
 *
 * <h3>碰撞盒 = 核心 + 有管臂的方向</h3>
 * 管道只占一格中间的 8/16，按 {@code getBoundingBox} 只给核心盒的话，
 * 玩家能从管壁里穿过去（看着像穿模）。所以碰撞盒按<b>现算出来的形状</b>逐方向外扩：
 * 核心 + 每个接了管的方向一路铺到格子边缘。
 * ★ 必须自己调 {@code state.getActualState(world, pos)}：MC 传进来的 {@code state}
 * 是<b>存进存档的那个</b>（掩码通常是 0），只有现算出来的才是真正画出来的形状。
 * 原版栅栏也是这么做的。
 *
 * <h3>不发光的取舍</h3>
 * {@code setLightOpacity(0)}：它是非满块，按满块挡光会在管壁周围压出一圈黑影，
 * 看着像贴图错了。栅栏、轨道这些非满块同样是 0。
 */
public class BlockGiantPipe extends Block {

    /**
     * 六个连接方向。★ 顺序 = 旧版 6 位掩码的位序，资源侧按同一顺序枚举掩码位。
     * 这个数组仍然是<b>六个方向</b>，只是其中两个竖直方向不再拥有状态。
     */
    public static final EnumFacing[] FACINGS = {
            EnumFacing.DOWN, EnumFacing.EAST, EnumFacing.NORTH,
            EnumFacing.SOUTH, EnumFacing.UP, EnumFacing.WEST,
    };

    /**
     * 竖直方向的两个属性。<b>已移出状态容器</b>（16 态是 1.12.2 meta 的硬上限），
     * 保留字段只是为了二进制兼容：外部代码若能编译过它们的引用，链接不至于断。
     * ★ 绝不能再把状态传给它们或把它们塞回状态容器里：
     * {@code withProperty(DOWN, ...)} 会因"属性不在容器里"抛异常。
     */
    @Deprecated
    public static final PropertyBool DOWN = PropertyBool.create("down");
    public static final PropertyBool EAST = PropertyBool.create("east");
    public static final PropertyBool NORTH = PropertyBool.create("north");
    public static final PropertyBool SOUTH = PropertyBool.create("south");
    @Deprecated
    public static final PropertyBool UP = PropertyBool.create("up");
    public static final PropertyBool WEST = PropertyBool.create("west");

    /** 真正参与状态容器的四个水平属性（16 种组合）。顺序同时决定 meta 位序。 */
    private static final IProperty<Boolean>[] PROPS = new IProperty[]{
            EAST, NORTH, SOUTH, WEST,
    };

    /** {@link #PROPS} 每一项对应的旧版 6 位掩码位（{@code east=2, north=4, south=8, west=32}）。 */
    private static final int[] PROPS_BITS = new int[]{2, 4, 8, 32};

    /** 旧版掩码的最大值（6 位）；形状计算仍按这个口径。 */
    public static final int MASK_MAX = 63;

    /**
     * "纯竖直"的模型索引 = down|up = 17。
     * <p>
     * ★ 这不是"备用分支"，而是<b>唯一正确</b>的取值：这种状态下四个水平位全 false，
     * 资源侧必须给它一个竖直段模型，否则玩家放下第一节管看到的是一个空格子。
     * 碰撞盒也走这个值，于是"看到的形状 = 碰撞的形状"。
     */
    public static final int VERTICAL_MASK = 17;

    /** 管壁：核心的半径（以格为单位）。4/16 的芯，"一人高"的观感 */
    private static final double CORE_LO = 4.0D / 16.0D;
    private static final double CORE_HI = 12.0D / 16.0D;

    public BlockGiantPipe() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "giant_pipe");
        setUnlocalizedName(RsAudio.MODID + ".giant_pipe");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.0F);
        setResistance(25.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        // 非满块：不按满块挡光，否则管壁周围会压出一圈黑影
        setLightOpacity(0);
        setDefaultState(applyMask(this.blockState.getBaseState(), 0));
    }

    // ------------------------------------------------------------------ 状态
    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, EAST, NORTH, SOUTH, WEST);
    }

    /**
     * 掩码 -> 状态。{@code bit(i)} 对应 {@link #FACINGS}[i]，即旧版 6 位掩码。
     * <p>
     * ★ 竖直两位（{@code bit0} down、bit4 up）<b>必须忽略</b>：它们不再在容器里，
     * 直接 {@code withProperty(DOWN, ...)} 会抛 {@code IllegalArgumentException}。
     * 现在这两位的唯一用处是让旧的<b>数据</b>（meta、旧存档）能被安全地读成
     * "水平四位"，而不是报错。
     */
    public static IBlockState applyMask(IBlockState state, int mask) {
        IBlockState s = state;
        for (int i = 0; i < PROPS.length; i++) {
            s = s.withProperty(PROPS[i], Boolean.valueOf((mask & PROPS_BITS[i]) != 0));
        }
        return s;
    }

    /**
     * 状态 -> 旧版 6 位掩码。缺属性的状态（理论上不会出现）当 0 处理。
     * <p>
     * ★ 水平位全 false 时返回 {@link #VERTICAL_MASK}（17），<b>不是 0</b>：
     * 这样模型与碰撞盒都取"竖直段"，和资源侧 blockstate 里 0 态的映射完全一致。
     */
    public static int maskOf(IBlockState state) {
        int horizontal = 0;
        for (int i = 0; i < PROPS.length; i++) {
            Boolean v = state.getValue(PROPS[i]);
            if (v != null && v.booleanValue()) {
                horizontal |= PROPS_BITS[i];
            }
        }
        return horizontal == 0 ? VERTICAL_MASK : horizontal;
    }

    /**
     * meta -> 状态。<b>4 位打包</b>：meta 的第 i 位对应 {@link #PROPS}[i]，所以 meta 只会是 0..15。
     * <p>
     * ★ 这里<b>不能</b>直接把 meta 当旧掩码用：旧掩码的 east 是 bit1、west 是 bit5，
     * 二进制位置和"4 位 meta"完全不同，混用会让存档里一半的管子变成另一个方向。
     */
    @Override
    public IBlockState getStateFromMeta(int meta) {
        int legacy = 0;
        for (int i = 0; i < PROPS.length; i++) {
            if ((meta & (1 << i)) != 0) {
                legacy |= PROPS_BITS[i];
            }
        }
        return applyMask(getDefaultState(), legacy);
    }

    /** 状态 -> meta，4 位打包（meta 永远 0..15）。和 {@link #getStateFromMeta} 互为逆。 */
    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = 0;
        for (int i = 0; i < PROPS.length; i++) {
            if (Boolean.TRUE.equals(state.getValue(PROPS[i]))) {
                meta |= 1 << i;
            }
        }
        return meta & 0xF;
    }

    /**
     * 现算这一格往哪几个方向伸出了管臂。
     * <p>
     * ★ 这个方法在区块重建渲染时被<b>高频</b>调用，绝不能抛异常（会直接穿渲染线程），
     * 所以整段包了 try/catch。和 {@code BlockTrainRail#getActualState} 同样的纪律。
     * <p>
     * ★ 只统计四个<b>水平</b>方向（{@link EnumFacing.Axis#Y} 直接跳过）：竖直方向不再是属性，
     * 统计了也没地方放。
     */
    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        int mask = 0;
        try {
            for (int i = 0; i < FACINGS.length; i++) {
                EnumFacing facing = FACINGS[i];
                if (facing.getAxis() == EnumFacing.Axis.Y) {
                    continue;
                }
                if (connectsTo(worldIn, pos, facing)) {
                    mask |= 1 << i;
                }
            }
        } catch (Throwable ignored) {
            // 读邻居出意外就退回"孤立一截芯"，反正只是个显示：宁可画成孤立，
            // 也不能让渲染线程崩溃。
            mask = 0;
        }
        return applyMask(state, mask);
    }

    /**
     * 某一方向上那格算不算"接上了"。
     * <p>
     * 判据是"那边也是管道"，外加<b>排风扇</b>：管道接风扇是最自然的用法
     * （风管接到排风口上），而风扇自己的朝向与管道无关 —— 玩家不需要为了对齐
     * 而调整管道方向，接上就是接上。
     */
    private static boolean connectsTo(IBlockAccess world, BlockPos pos, EnumFacing f) {
        Block b = world.getBlockState(pos.offset(f)).getBlock();
        return b instanceof BlockGiantPipe || b instanceof BlockExhaustFan;
    }

    // ------------------------------------------------------------------ 几何

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return boxOf(actualMask(state, source, pos));
    }

    /**
     * 碰撞盒 = 核心 + 每个"接了管"的方向外扩到格子边。
     * <p>
     * ★ 原版约定 {@code getCollisionBoundingBox} 返回<b>绝对坐标</b>
     * （{@code BlockBlastDoor} 上记过那次事故：返回局部盒会让碰撞全落在世界原点）。
     */
    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return boxOf(actualMask(state, worldIn, pos)).offset(pos);
    }

    /**
     * 现算掩码，任何意外都退回 0（核心盒）。
     * <p>
     * ★ 直接用 {@code state} 的属性是<b>错的</b>：MC 传进来的是存档里那个状态，
     * 掩码通常停在放置时的 0，于是"画着管臂、碰撞却没有"（能从管壁穿过去）。
     */
    private static int actualMask(IBlockState state, IBlockAccess world, BlockPos pos) {
        try {
            return maskOf(state.getActualState(world, pos));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 掩码 -> 包围盒（局部坐标，未偏移） */
    public static AxisAlignedBB boxOf(int mask) {
        double x0 = CORE_LO;
        double x1 = CORE_HI;
        double y0 = CORE_LO;
        double y1 = CORE_HI;
        double z0 = CORE_LO;
        double z1 = CORE_HI;
        if ((mask & 1) != 0) {
            y0 = 0.0D;                                  // down
        }
        if ((mask & 2) != 0) {
            x1 = 1.0D;                                  // east
        }
        if ((mask & 4) != 0) {
            z0 = 0.0D;                                  // north
        }
        if ((mask & 8) != 0) {
            z1 = 1.0D;                                  // south
        }
        if ((mask & 16) != 0) {
            y1 = 1.0D;                                  // up
        }
        if ((mask & 32) != 0) {
            x0 = 0.0D;                                  // west
        }
        return new AxisAlignedBB(x0, y0, z0, x1, y1, z1);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    /**
     * ★★ 窒息豁免：<b>显式</b>写出来。
     * <p>
     * 按现在的实现，{@link #isFullCube} 恒为 false，所以"什么都不写"也是安全的。
     * 之所以仍然显式覆写，是因为本模组已经在 {@code Block.causesSuffocation} 上
     * 栽过三次（防爆门、F9 门、电梯层间）：它的默认实现问的是
     * {@code getDefaultState().isFullCube()}，采样点是<b>眼睛那一格</b>；
     * 一旦有人把 {@code isFullCube} 改成"看状态"（管道完全可能被改成
     * "没管臂时算满块"），这里就会变成"站在管道里被憋死"，而且看不出和碰撞盒有关。
     * 留着这一行 + {@code verify_jar:EXPECT_NO_SUFFOCATION} 的登记，
     * 就是为了让那次改动<b>不会静默生效</b>。
     */
    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    // ------------------------------------------------------------------ 邻居变动

    /**
     * 管道靠邻居决定形状，所以邻居一变动就要让这一格和它的邻居重新渲染。
     * <p>
     * ★ 不写的话，玩家在旁边再接一节管，接缝处会一直画不出来
     * （直到区块卸载重载）—— 看起来就是"接上了但没连上"。
     * 用 {@code notifyBlockUpdate} 而不是 {@code markBlockRangeForRenderUpdate}：
     * 后者会把整片区域重画，铺长管道时明显掉帧。
     */
    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        super.onBlockAdded(worldIn, pos, state);
        refreshNeighbours(worldIn, pos);
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        super.neighborChanged(state, worldIn, pos, blockIn, fromPos);
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
        IBlockState here = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, here, here, 2);
        for (EnumFacing f : FACINGS) {
            BlockPos n = pos.offset(f);
            Block b = world.getBlockState(n).getBlock();
            if (b instanceof BlockGiantPipe) {
                IBlockState ns = world.getBlockState(n);
                world.notifyBlockUpdate(n, ns, ns, 2);
            }
        }
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_pipe.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_pipe.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_pipe.3"));
    }
}

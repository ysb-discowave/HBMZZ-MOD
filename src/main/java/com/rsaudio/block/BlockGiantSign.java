package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileGiantSign;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 巨大标志牌。设施里那种"整个墙面一块大牌子"的标识板，
 * 文字由 {@code GiantSignRenderer} 画（不占贴图，所以玩家想写什么就写什么）。
 *
 * <h3>能改的东西</h3>
 * <ul>
 *   <li><b>三行文字</b>（每行最多 {@link TileGiantSign#MAX_LINE_LENGTH} 字）——
 *       存在方块实体里，走 {@code getUpdateTag} 同步；</li>
 *   <li><b>字色</b>（{@link TileGiantSign#COLOR_COUNT} 种预设）—— 同样在方块实体里；</li>
 *   <li><b>照明</b>（{@link #LIT}）—— 在<b>方块状态</b>里，因为自发光
 *       （{@link #getLightValue}）只能读方块状态，而且板面亮/暗要换模型。</li>
 * </ul>
 * 右键打开界面改这些；{@code PacketGiantSignSet} 把改动送回服务端。
 *
 * <h3>★ 为什么照明在方块状态、文字在方块实体</h3>
 * 这两条不是随手分的：
 * <ul>
 *   <li>{@code getLightValue(IBlockState, ...)} 的入参<b>只有方块状态</b>。
 *       照明要是存在方块实体里，就得写一个"每 tick 重新设 lightLevel"的补丁，
 *       而那会在大范围内触发光照重算（{@code BlockF9Core} 那边解释过为什么不做）。</li>
 *   <li>文字是自由文本、还要能换模型之外的东西，塞进方块状态会让属性组合爆掉
 *       （3 行 × 24 字符 × 8 色）—— 那是 1.12.2 方块状态绝对不能碰的量级。</li>
 * </ul>
 * 两边各管一半，就都不会有"两份谁说了算"的问题。
 *
 * <h3>朝向</h3>
 * {@link #FACING} = <b>字面朝向</b>（玩家站在哪一侧能读到）。放置时取
 * {@code placer.getHorizontalFacing().getOpposite()}：玩家面朝北对着墙放，
 * 牌子就该朝南（朝着玩家）。
 * <p>
 * 反面贴着墙，所以板面整体贴在与 {@code FACING} <b>相反</b>的那一侧 ——
 * 和 {@code BlockMonitorCamera} 的 {@code BOXES} 表同一套约定。
 */
public class BlockGiantSign extends Block implements ITileEntityProvider {

    /** 字面朝向（水平四向） */
    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create(
            "facing", EnumFacing.class,
            EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST);

    /** 板面照明：亮着的牌子会发光，模型也换成亮的那张 */
    public static final PropertyBool LIT = PropertyBool.create("lit");

    /** 板厚（格）。2/16 —— 比防爆门门板厚一点，看得见"这是一块板" */
    private static final double THICK = 2.0D / 16.0D;

    /**
     * 四个朝向的包围盒，下标用 {@link EnumFacing#ordinal()}（声明顺序 DOWN,UP,NORTH,SOUTH,WEST,EAST）。
     * 盒子总是贴着"字面的反面"那一侧，整面铺满。
     */
    private static final AxisAlignedBB[] BOXES = new AxisAlignedBB[6];

    static {
        double near = 0.0D;
        double far = THICK;
        double back = 1.0D - THICK;
        // NORTH：字朝北(-Z) → 板贴 +Z 那面墙
        BOXES[EnumFacing.NORTH.ordinal()] = new AxisAlignedBB(0.0D, 0.0D, back, 1.0D, 1.0D, 1.0D);
        // SOUTH：字朝南(+Z) → 板贴 -Z 那面墙
        BOXES[EnumFacing.SOUTH.ordinal()] = new AxisAlignedBB(0.0D, 0.0D, near, 1.0D, 1.0D, far);
        // WEST：字朝西(-X) → 板贴 +X 那面墙
        BOXES[EnumFacing.WEST.ordinal()] = new AxisAlignedBB(back, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
        // EAST：字朝东(+X) → 板贴 -X 那面墙
        BOXES[EnumFacing.EAST.ordinal()] = new AxisAlignedBB(near, 0.0D, 0.0D, far, 1.0D, 1.0D);
        // UP / DOWN 不会被放置逻辑选到，但枚举里存在 —— 给个安全值，
        // 免得将来有人放宽放置条件时读到 null 直接崩。
        BOXES[EnumFacing.UP.ordinal()] = new AxisAlignedBB(0.0D, back, 0.0D, 1.0D, 1.0D, 1.0D);
        BOXES[EnumFacing.DOWN.ordinal()] = new AxisAlignedBB(0.0D, near, 0.0D, 1.0D, far, 1.0D);
    }

    public BlockGiantSign() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "giant_sign");
        setUnlocalizedName(RsAudio.MODID + ".giant_sign");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(2.0F);
        setResistance(15.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setLightLevel(0.0F);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(LIT, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, LIT);
    }

    /** meta = facing 的 index；照明放在第 3 位（FACING 只用了 0..5） */
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, horizontal(EnumFacing.getFront(meta & 7)))
                .withProperty(LIT, Boolean.valueOf((meta & 8) != 0));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return (state.getValue(FACING).getIndex() & 7)
                | (state.getValue(LIT).booleanValue() ? 8 : 0);
    }

    /** 竖直朝向一律折回 NORTH：本方块只支持水平四向 */
    private static EnumFacing horizontal(EnumFacing f) {
        return f == null || !f.getAxis().isHorizontal() ? EnumFacing.NORTH : f;
    }

    /**
     * 放置时字面朝向<b>玩家</b>。
     * <p>
     * ★ 和摄像头用"被右击的那一面"不同。摄像头是"镜头要朝外看"，所以被击中的面
     * 天然就是答案；牌子的语义是"我要能读到它"，答案是<b>玩家的方向</b>。
     * 对着同一面墙，两人从相反方向放，得到的朝向就该不同 —— 这点和告示牌一致。
     */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        EnumFacing face = placer == null ? EnumFacing.SOUTH
                : horizontal(placer.getHorizontalFacing().getOpposite());
        return getDefaultState().withProperty(FACING, face);
    }

    /** 亮着的牌子会照亮周围 */
    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.getValue(LIT).booleanValue() ? 13 : 0;
    }

    // ------------------------------------------------------------------ 几何

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return BOXES[state.getValue(FACING).ordinal()];
    }

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return BOXES[state.getValue(FACING).ordinal()].offset(pos);
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
     * ★★★ 窒息豁免 —— 这一条是<b>真的需要</b>，不是防御性重复。
     * <p>
     * 板面只有 2/16 厚，玩家可以站在牌子里那一格；而
     * {@code Block.causesSuffocation} 的默认实现问的是
     * {@code getDefaultState().isFullCube()}（<b>不是</b>传进来的状态），
     * 采样点是<b>眼睛那一格</b>。本模组已经在防爆门、F9 门、电梯层门上
     * 各栽过一次"门开着也把人憋死"，这里是第四处。
     * {@code verify_jar:EXPECT_NO_SUFFOCATION} 里登记着，删了就会被拦下来。
     */
    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileGiantSign();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileGiantSign();
    }

    public static TileGiantSign getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileGiantSign ? (TileGiantSign) te : null;
    }

    // ------------------------------------------------------------------ 右键

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            RsAudio.proxy.openGiantSignGui(pos);
        } else {
            // 服务端顺手把最新内容推一次：玩家开界面时不必等下一个同步周期
            // （玩家刚放下牌子就右键时，客户端那份方块实体可能还没收到过数据）
            TileGiantSign tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.pushToClients();
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_sign.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_sign.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_sign.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.giant_sign.4"));
    }
}

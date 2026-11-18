package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.elevator.ElevatorSettings;
import com.rsaudio.elevator.ElevatorSpec;
import com.rsaudio.tile.TileElevatorController;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * 电梯控制柜：一台电梯的<b>唯一服务端权威</b>。右键开界面，其余一切都在界面里做。
 * <p>
 * 三种电梯（客运 / 货运 / 斜面）<b>共用这一个类</b>，靠构造参数分型 ——
 * 和六色警报灯（{@code BlockAlarmLight(i)}）是同一个做法。
 * 三者真正的差异只有五处（轿厢多大、跑多快、门开多久、额定载重、井道是不是台阶），
 * 那些全在 {@link ElevatorSettings} 的预设里；方块这一层只负责"我是哪一种"。
 *
 * <h3>摆放（tooltip 里也要写清楚）</h3>
 * <pre>
 *     井道（一格宽的柱子，垂直那一列每格都是 [井道]）
 *        ┃
 *        ┃
 *      [控制柜]   ← 贴着井道某一面放，控制柜的<b>正面朝井道</b>
 * </pre>
 * 控制柜自己的那一格 Y 就是井道的<b>最低层</b>（路径索引 0），往上每格索引 +1。
 * 斜面的井道不是竖直的，而是每上一格就往 {@link ElevatorSpec#inclinedAxis()} 的方向挪一格
 * （台阶）—— 认路逻辑在方块实体里，这里不参与。
 *
 * <h3>★ 为什么正面朝向要做成方块状态</h3>
 * 朝向决定"井道在哪一边"（结构扫描的基准方向）。装反了电梯认不到井道，
 * 只会报"找不到井道" —— 而玩家看见的是一台看起来完全正常的机器。
 * 所以放置时自动朝向玩家，且 tooltip 第一条就讲这个。
 */
public class BlockElevatorController extends Block implements ITileEntityProvider {

    /** 三台电梯的注册名后缀，下标与 {@link ElevatorSpec#TYPE_*} 一一对应 */
    public static final String[] TYPE_NAMES = { "passenger", "freight", "inclined" };
    /** 类型个数（给 ModBlocks 造数组用） */
    public static final int TYPE_COUNT = ElevatorSpec.TYPE_COUNT;

    /** 正面朝向（放置时朝向玩家），同时是井道扫描的基准方向 */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 运行灯：井道认出来了 + 没故障 = 亮 */
    public static final PropertyBool LIT = PropertyBool.create("lit");

    private final int type;

    public BlockElevatorController(int type) {
        super(Material.IRON);
        this.type = (type < 0 || type >= TYPE_COUNT) ? ElevatorSpec.TYPE_PASSENGER : type;
        String name = TYPE_NAMES[this.type];
        setRegistryName(RsAudio.MODID, "elevator_controller_" + name);
        setUnlocalizedName(RsAudio.MODID + ".elevator_controller_" + name);
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(6.0F);
        setResistance(60.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(LIT, Boolean.FALSE));
    }

    /** 这台控制柜管的是哪一种电梯 */
    public int getTypeIndex() {
        return type;
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, LIT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(LIT, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(LIT)) {
            meta |= 4;
        }
        return meta;
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    /**
     * 运行灯。★ 只在真的变了才写世界 —— 这个方法每 tick 都会被调，
     * 无条件 {@code setBlockState} 会造成每 tick 一次方块更新风暴（光照与渲染被刷爆）。
     */
    public static void setLit(World world, BlockPos pos, boolean lit) {
        if (world == null || pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockElevatorController) || state.getValue(LIT) == lit) {
            return;
        }
        world.setBlockState(pos, state.withProperty(LIT, lit), 3);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    /**
     * ★★★ 这里必须把"我是哪一种"灌进方块实体。
     * 实现 {@code ITileEntityProvider} 的方块，Forge 1.12.2 优先走 {@code createTileEntity}
     * （它拿得到 {@code state}，所以认得出是哪个方块）；只有老路径才会退回
     * {@code createNewTileEntity(world, meta)}，而那个签名里<b>没有方块状态</b>，
     * 认不出三种控制柜。
     * ⇒ 两个都实现，且方块实体自己还会在第一次 tick 时从世界复核一遍（见
     * {@code TileElevatorController#ensureTypeFromWorld}），三层保险。
     */
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        TileElevatorController te = new TileElevatorController();
        te.setType(type);
        return te;
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        // 这条路径拿不到方块状态：给一个默认类型，方块实体第一次 tick 会自己纠正。
        TileElevatorController te = new TileElevatorController();
        te.setType(type);
        return te;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            RsAudio.proxy.openElevatorGui(pos);
        } else {
            // 顺手把最新状态推过去，玩家开界面时不用等下一个同步周期
            TileEntity te = worldIn.getTileEntity(pos);
            if (te instanceof TileElevatorController) {
                ((TileElevatorController) te).sendStateTo(playerIn);
            }
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TileElevatorController && !worldIn.isRemote) {
            ((TileElevatorController) te).onControllerBroken();
        }
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        String p = "tooltip.rsaudio.elevator_controller_" + TYPE_NAMES[type] + ".";
        tooltip.add(I18n.translateToLocal(p + "1"));
        tooltip.add(I18n.translateToLocal(p + "2"));
        tooltip.add(I18n.translateToLocal(p + "3"));
        tooltip.add(I18n.translateToLocal(p + "4"));
    }
}

package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileAirlockController;
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
 * <b>气闸控制器</b>：按一下，走完整套流程 —— 两侧关上、净化一段时间、对面那侧打开。
 *
 * <h3>用法</h3>
 * <ol>
 *   <li>面对面放两扇防核门，把它们设成<b>同一个频道</b>、并且都打开「联锁」。</li>
 *   <li>在两扇门中间（8 格内）放下控制器，设成同一个频道。</li>
 *   <li>走到舱室里，<b>空手右键控制器</b>：身后的门关上 → 净化 → 对面的门打开。
 *       从哪一侧进都行 —— 方向由<b>人站的位置</b>决定，不是由控制器朝向决定。</li>
 * </ol>
 *
 * <h3>★ 不潜行右键 = 走流程，潜行右键 = 设置界面</h3>
 * 和门禁终端正好相反。终端最常用的动作是"开界面调参数"，
 * 所以它把"不用开界面的那一下"（紧急封锁）放在潜行上；
 * 气闸最常用的动作是<b>走流程</b>，所以它把"不用开界面的那一下"放在普通右键上 ——
 * 被怪追着进门时少一步操作。设置界面还有木斧那条路（见 {@code WandHandler}）。
 * <p>
 * ★ 用户明确<b>不要</b>红石 / 按钮控制气闸（"不要按钮红石控制"）——
 * 所以这里<b>不读红石信号</b>，触发方式只有"人右键控制器"和界面上的「启动」两条。
 *
 * <h3>控制器的朝向只是外观</h3>
 * 哪一扇门算"甲"、哪一扇算"乙"是按<b>位置</b>定的全序（见 {@code TileAirlockController}），
 * 不看 {@code FACING}。所以玩家把控制器装反了也不会把"进"和"出"弄颠倒 ——
 * 真正决定方向的是<b>人站在哪一侧</b>：人靠哪边近，就算从哪边进来，最后开另一侧。
 */
public class BlockAirlockController extends Block implements ITileEntityProvider {

    /** 正面朝向（外观用，不参与几何判定） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 正在跑一轮气闸循环（正面板上的指示灯亮） */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockAirlockController() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "airlock_controller");
        setUnlocalizedName(RsAudio.MODID + ".airlock_controller");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.5F);
        setResistance(30.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(ACTIVE, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, ACTIVE);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(ACTIVE, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(ACTIVE)) {
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
     * 供方块实体同步"循环中"的指示灯。
     * <p>
     * 和 {@code BlockAnnouncer.setActive} / {@code BlockAccessTerminal.setLockedIndicator}
     * 一样刻意做成静态方法：方块实体是双端共用的类，它<b>不能</b> import 客户端渲染类，
     * 但访问自己的方块状态是没问题的。
     */
    public static void setActiveIndicator(World world, BlockPos pos, boolean active) {
        if (world == null || pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockAirlockController) || state.getValue(ACTIVE) == active) {
            return;
        }
        world.setBlockState(pos, state.withProperty(ACTIVE, active), 3);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAirlockController();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileAirlockController();
    }

    static TileAirlockController getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileAirlockController ? (TileAirlockController) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.isSneaking()) {
            // 潜行右键 = 设置界面
            if (worldIn.isRemote) {
                RsAudio.proxy.openAirlockGui(pos);
            }
            return true;
        }
        // 普通右键 = 按一下，走一次完整的循环
        if (!worldIn.isRemote) {
            TileAirlockController tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.activate(playerIn);
            }
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        // 真正"把气闸封放掉"的地方是 TileAirlockController#invalidate()，不是这里 ——
        // invalidate 在方块实体被移除时必定调用，而 breakBlock 里还能不能拿到方块实体
        // 要看 World.setBlockState 的内部顺序，靠不住。详见那边的注释。
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.airlock.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.airlock.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.airlock.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.airlock.4"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.airlock.5"));
    }
}

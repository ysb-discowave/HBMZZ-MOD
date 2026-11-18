package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileF9Controller;
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
 * F9 控制柜：整套反应堆的<b>唯一入口</b>。右键开界面，其余一切都在界面里做。
 * <p>
 * 和 {@link BlockDmrController} 完全同构 —— 理由也一样：反应堆的状态只存在于
 * 控制柜的方块实体里，方块本身只是一张"门"。
 *
 * <h3>多方块结构</h3>
 * <pre>
 *        外壳 外壳 外壳
 *        外壳 堆芯 外壳        ← 3×3×3 的 {@link BlockF9Casing}，
 *        外壳 外壳 外壳           正中间是 {@link BlockF9Core}
 *
 *            [控制柜]          ← 必须正对某一面的<b>中心</b>、紧贴外壳放置
 * </pre>
 * 于是控制柜的位置和堆芯之间有确定关系：<b>堆芯 = 控制柜沿正面方向再往前 2 格</b>。
 * 判定只查 6 个方向各一个候选点，156 次方块查询就够，不需要全空间搜索。
 * <p>
 * <h3>为什么面板要一格朝向属性</h3>
 * 朝向决定"往前 2 格是哪儿"，也就是结构判定的基准方向。玩家装反了就开不了机，
 * 所以放置时自动朝向玩家（和熔炉的约定一致），并在 tooltip 里写清该怎么摆。
 */
public class BlockF9Controller extends Block implements ITileEntityProvider {

    /** 正面朝向（放置时朝向玩家），同时是结构判定的基准方向 */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 正在运行（面板上的绿色运行灯） */
    public static final PropertyBool LIT = PropertyBool.create("lit");

    public BlockF9Controller() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "f9_controller");
        setUnlocalizedName(RsAudio.MODID + ".f9_controller");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(6.0F);
        setResistance(60.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(LIT, Boolean.FALSE));
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
     * 供方块实体同步"正在运行"的运行灯。
     * <p>
     * ★ 只在真的变了才写世界 —— 这个方法会被每 tick 调用，无条件 setBlockState 会造成
     * 每 tick 一次方块更新风暴（客户端光照/渲染全被刷爆）。
     */
    public static void setLit(World world, BlockPos pos, boolean lit) {
        if (world == null || pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockF9Controller) || state.getValue(LIT) == lit) {
            return;
        }
        world.setBlockState(pos, state.withProperty(LIT, lit), 3);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileF9Controller();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileF9Controller();
    }

    static TileF9Controller getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileF9Controller ? (TileF9Controller) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            RsAudio.proxy.openF9Gui(pos);
        } else {
            // 服务端顺手把最新状态推过去：玩家开界面时不用等下一个同步周期
            TileF9Controller tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.sendStateTo(playerIn);
            }
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        // 拆掉控制柜等于放弃这台堆；运行中的话让它安全停机，别留下一个"没人管的熔毁"
        TileF9Controller tile = getTile(worldIn, pos);
        if (tile != null && !worldIn.isRemote) {
            tile.onControllerBroken();
        }
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_controller.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_controller.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_controller.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_controller.4"));
    }
}

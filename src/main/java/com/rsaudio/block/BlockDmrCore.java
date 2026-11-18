package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileDmrCore;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.List;

/**
 * DMR 堆芯腔体：多方块结构的<b>中心那一格</b>。
 * <p>
 * 结构判定要的是"3×3×3 的 {@link BlockDmrCasing} 里正中间放着一块这个"。
 * 也就是说它是整台机器的定位点 —— 控制柜靠它反推外壳范围，所以
 * <b>一块世界里只能有一处这个方块</b>（多了会让"哪块是中心"变得不确定）。
 * <p>
 * ★ 点火序列期间站在腔体里会被烤死 —— 但那一刀由控制柜执行（它每 tick 检查腔体内实体），
 * 这里不做任何事。原因：致死判定依赖反应堆<b>状态</b>，而状态只存在于控制柜的方块实体里。
 *
 * <h3>★ 为什么这个方块要改成"不可见 + 方块实体 + TESR"</h3>
 * 它以前是普通的 cube_all，也就是"一个会发光的立方体"。问题是堆芯在设定里
 * 是<b>惯性约束聚变靶室</b> —— 玩家期待看到的是一团悬在腔体中央、缓慢自转的等离子体，
 * 而不是一块贴了张亮图的砖。而且普通方块模型<b>无法做动画</b>：
 * 模型是烘死在区块网格里的，没有任何逐帧的钩子。
 * <p>
 * 所以改成和塔塔鲁斯 / 防爆门一样的三件套：{@code INVISIBLE} 渲染类型 +
 * {@link TileDmrCore} + {@code DmrCoreRenderer}。方块本体不再可见，
 * 所有视觉都由 TESR 画 —— 于是转、脉冲、随状态变色这些事才做得到。
 */
public class BlockDmrCore extends Block implements ITileEntityProvider {

    /** 堆在运行（点火中 / 在线）。驱动等离子体的亮度与转速 */
    public static final PropertyBool LIT = PropertyBool.create("lit");
    /** 熔毁中（P1/P2）。等离子体转为失控色并剧烈抖动 */
    public static final PropertyBool MELTDOWN = PropertyBool.create("meltdown");

    public BlockDmrCore() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "dmr_core");
        setUnlocalizedName(RsAudio.MODID + ".dmr_core");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(8.0F);
        setResistance(200.0F);
        setSoundType(SoundType.METAL);
        // ★ 方块本体不可见 —— 全部交给 TESR。写了 setLightLevel 也没用，
        //   因为方块根本不被烘焙；"发光"这件事改由渲染器自己画（外加下面的 getLightValue）。
        setDefaultState(this.blockState.getBaseState()
                .withProperty(LIT, Boolean.FALSE)
                .withProperty(MELTDOWN, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, LIT, MELTDOWN);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(LIT, (meta & 1) != 0)
                .withProperty(MELTDOWN, (meta & 2) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = 0;
        if (state.getValue(LIT)) {
            meta |= 1;
        }
        if (state.getValue(MELTDOWN)) {
            meta |= 2;
        }
        return meta;
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    /**
     * ★ 不可见方块在 1.12.2 里必须把 {@code isOpaqueCube} 关掉，
     * 否则相邻面会被错误剔除 —— 表现为"贴着堆芯的那几块外壳的内侧面消失"。
     */
    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    /**
     * 让堆芯像火把一样给周围照亮。这里的值随状态变：
     * 熔毁时最亮（失控的等离子体），运行时次之，未点火时只留一点余辉。
     * <p>
     * 注意这走的是原版光照系统，和 TESR 画出来的亮度是两回事 ——
     * 前者决定"周围方块被照亮成什么样"，后者决定"堆芯本身看起来多亮"。
     * 两个都要给，观感才自洽。
     */
    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        if (state.getValue(MELTDOWN)) {
            return 15;
        }
        return state.getValue(LIT) ? 12 : 4;
    }

    /** 碰撞箱留空：腔体中心是可站人的（点火期间才由控制柜判定致死） */
    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess world, BlockPos pos) {
        return NULL_AABB;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Nullable
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileDmrCore();
    }

    /**
     * 供控制柜同步堆芯的视觉状态（运行 / 熔毁）。
     * <p>
     * 只在真的变了才写世界 —— 这个方法每 tick 会被调用，无条件 setBlockState 会
     * 造成每 tick 一次方块更新风暴（客户端光照与渲染全被刷爆）。
     *
     * @return 是否真的写了世界（调用方可据此决定要不要标脏）
     */
    public static boolean setState(World world, BlockPos pos, boolean lit, boolean meltdown) {
        if (world == null || pos == null) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockDmrCore)) {
            return false;
        }
        if (state.getValue(LIT) == lit && state.getValue(MELTDOWN) == meltdown) {
            return false;
        }
        world.setBlockState(pos, state.withProperty(LIT, lit).withProperty(MELTDOWN, meltdown), 3);
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.dmr_core.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.dmr_core.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.dmr_core.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.dmr_core.4"));
    }
}

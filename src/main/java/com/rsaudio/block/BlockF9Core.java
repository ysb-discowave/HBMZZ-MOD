package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * F9 反应堆压力容器：多方块结构的<b>中心那一格</b>。
 * <p>
 * 结构判定要的是"3×3×3 的 {@link BlockF9Casing} 里正中间放着一块这个"。
 * 也就是说它是整台机器的定位点 —— 控制柜靠它反推外壳范围，所以
 * <b>一块世界里只能有一处这个方块</b>（多了会让"哪块是中心"变得不确定）。
 *
 * <h3>★ 为什么它<b>不</b>做成 DMR 那种"不可见 + 方块实体 + TESR"</h3>
 * {@link BlockDmrCore} 走的是那条路（腔体里画一团自转的等离子体），因为它要的是
 * "悬在靶室中央的聚变等离子体"，普通方块模型做不到那种动画。
 * <p>
 * F9 不一样：它是一台<b>压水堆 + 斯特林转换器</b>，玩家期待看到的是"一个会发亮的压力容器"，
 * 而不是一团等离子体 —— 压力容器本来就有实体感。所以这里就是一个带 {@link #LIT} 属性的
 * 普通方块，运行时把自发光抬上去（{@link #getLightValue}），
 * 省掉一整个方块实体 + 一个 TESR + 一套图集缝合清单，而观感反而更贴设定。
 * <p>
 * ★ 代价说清楚：这个方块<b>没有逐帧动画</b>。以后如果要给容器加"水面翻腾"之类的效果，
 * 那才是该上 TESR 的时候 —— 那时记得三件套一起加：方块实体注册（漏了会一进世界就崩）、
 * 渲染器在 {@code ClientProxy.preInit} 注册（写 init 静默失效）、
 * 贴图进 {@code onTextureStitch} 的缝合清单（漏了是紫黑块）。
 */
public class BlockF9Core extends Block {

    /** 反应堆在运行 / 在线 / 已 SCRAM（还有余热）。驱动自发光 */
    public static final PropertyBool LIT = PropertyBool.create("lit");

    public BlockF9Core() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "f9_core");
        setUnlocalizedName(RsAudio.MODID + ".f9_core");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(8.0F);
        setResistance(200.0F);
        setSoundType(SoundType.METAL);
        setLightLevel(0.0F);
        setDefaultState(this.blockState.getBaseState().withProperty(LIT, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, LIT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(LIT, (meta & 1) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(LIT) ? 1 : 0;
    }

    /**
     * 运行时给周围照亮。走的是原版光照系统 —— 它决定"周围方块被照亮成什么样"。
     * <p>
     * 没做"温度越高越亮"：光照值是烘焙来的一份查找表（每 16 级一档），
     * 每 tick 跟着温度跳档会造成大片区块光照重算，得不偿失。
     * 温度要看得准该去看界面上的读数。
     */
    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.getValue(LIT) ? 13 : 0;
    }

    /**
     * 供控制柜同步"在不在运行"的自发光状态。
     * <p>
     * ★ 只在真的变了才写世界 —— 这个方法每 tick 会被调用，
     * 无条件 {@code setBlockState} 会造成每 tick 一次方块更新风暴（客户端光照与渲染全被刷爆）。
     * 和 {@code BlockDmrCore.setState} 是同一条纪律。
     *
     * @return 是否真的写了世界
     */
    public static boolean setLit(World world, BlockPos pos, boolean lit) {
        if (world == null || pos == null) {
            return false;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockF9Core)) {
            return false;
        }
        if (state.getValue(LIT) == lit) {
            return false;
        }
        world.setBlockState(pos, state.withProperty(LIT, lit), 3);
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_core.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_core.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.f9_core.3"));
    }
}

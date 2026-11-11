package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 电梯井道：轿厢跑在里面的框。
 * <p>
 * <b>可以走进去</b> —— 这一点不是装饰需要，是结构需要：轿厢本身是<b>真实方块</b>
 * （见 {@link BlockElevatorCar}），而它就待在井道的格子里。
 * 如果井道是实心的，玩家永远进不了轿厢（碰撞盒挡住）。
 * 所以井道的每一格都必须 {@code isPassable} + 空碰撞盒 + 不挡住光。
 * <p>
 * 那玩家会不会掉进井道？会 —— 但轿厢的平台就在那儿接着（{@link BlockElevatorCar} 是实心可站立的）。
 * 轿厢不在的那几层确实是空的，这是电梯本来的样子。
 *
 * <h3>★ 为什么这一格不需要方块状态属性</h3>
 * 井道的外形只有一种（一个框），朝向没有意义，所以 blockstate 是无属性的
 * （变体 key = {@code "normal"}，同时要登记进 {@code verify_jar.py} 的
 * {@code PROPERTYLESS_STATES}，否则自检会报"一条属性都没有"）。
 */
public class BlockElevatorShaft extends Block {

    /** 选择框用完整立方体：玩家瞄的是"这一格"，不是那个细框 */
    private static final AxisAlignedBB FULL_BB = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);

    public BlockElevatorShaft() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "elevator_shaft");
        setUnlocalizedName(RsAudio.MODID + ".elevator_shaft");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(5.0F);
        setResistance(40.0F);
        setSoundType(SoundType.METAL);
        setLightOpacity(0);
    }

    // ------------------------------------------------------------------ 可穿过
    // ★★★ 这三对方法必须一起改，少一个的症状各不相同：
    //   · 漏 isFullCube → 玩家站在井道边被"看不见的墙"挡住（碰撞盒是实心的）
    //   · 漏 getCollisionBoundingBox → 井道变成实心柱子，轿厢根本进不去
    //   · 漏 isOpaqueCube → 井道内侧一片漆黑（原版按"不透明"去做面剔除和光照）

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
        return true;
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return NULL_AABB;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return FULL_BB;
    }

    @Override
    public boolean isSideSolid(IBlockState base_state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return false;
    }

    /**
     * 井道要能看透（否则轿厢在里面完全看不见，等于白做）。
     * ★ 用 {@code CUTOUT} 而不是 {@code TRANSLUCENT}：井道贴图是全透明/全不透明的像素，
     * 没有半透明过渡；用 TRANSLUCENT 会让它进"半透明排序队列"，
     * 在井道里叠加时会出现那种"看它一眼就消失"的经典排序 bug。
     */
    @Override
    public BlockRenderLayer getBlockLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_shaft.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_shaft.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_shaft.3"));
    }
}

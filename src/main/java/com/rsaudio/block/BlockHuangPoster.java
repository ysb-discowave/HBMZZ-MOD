package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileHuangPoster;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
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
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 纪念挂画 —— 挂在墙上的画框，右键翻页；多块相邻且朝向、页码相同时会拼成一张大图。
 *
 * <h3>页码为什么放方块实体</h3>
 * 画面从 4 张加到 11 张。1.12.2 的 meta 只有 4 位（16 种状态）：
 * 11 页 × 4 朝向 = 44 种，塞进 blockstate 会在启动时 ArrayIndexOutOfBounds。
 * 所以 blockstate 只留 FACING（4 种），页码在 TileHuangPoster 的 NBT 里。
 *
 * <h3>画面怎么画</h3>
 * 单块时用方块模型贴图（poster_pageN，静态、已验证可靠）；
 * 多块可拼时由 HuangPosterWorldRenderer 用高分辨率原图（posterN）
 * 每块画自己那一格的切片，拼起来是一整张放大画面。
 * 拼不拼由渲染器按"整片宽高比是否接近原图比例"决定 —— 比例失调就各画各的单图。
 */
public class BlockHuangPoster extends Block implements ITileEntityProvider {

    /** 画面总张数（4 张老图 + 7 张新梗图） */
    public static final int PAGES = 11;

    /** 画面在 textures/poster/ 下的文件名，下标 = 页码 */
    public static final String[] VARIANTS_NAMES = {
            "poster0", "poster1", "poster2", "poster3", "poster4", "poster5",
            "poster6", "poster7", "poster8", "poster9", "poster10"
    };

    /**
     * 每张原图的宽高比（宽/高）。渲染器用它判断拼出来的矩形会不会把图拉变形：
     * 矩形宽高比与原图相差太大（相差 45% 以上）就退回单块显示。
     */
    public static final float[] PAGE_ASPECTS = {
            1.0F, 1.0F, 1.0F, 1.0F,
            1.3196F, 1.0369F, 0.9343F, 1.4866F, 0.9558F, 0.7513F, 2.0581F
    };

    /** 画面朝向（画朝着玩家） */
    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create(
            "facing", EnumFacing.class,
            EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST);

    /** meta 里存朝向的那两位用的顺序表。★ 不能用 EnumFacing.byIndex：0/1 是 DOWN/UP */
    private static final EnumFacing[] HORIZONTAL = {
            EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST
    };

    /** 板厚（格）。★ 必须和 HuangPosterWorldRenderer.THICK 一致 */
    private static final double THICK = 2.0D / 16.0D;

    /** 四个朝向的包围盒，下标是 {@link #HORIZONTAL} 的下标 */
    private static final AxisAlignedBB[] BOXES = new AxisAlignedBB[4];

    /**
     * 选择箱做成贴着墙的薄画框，和原版展示框/画一样：只占画面那一薄层，
     * 不再用填满整格的实心盒（那样玩家会觉得"方块太大、挡手"）。
     * 薄板位于 facing 法线的反方向那侧（和模型板、画面同一面）：
     *   NORTH(nz=-1) 板在 +Z 侧 [0.875,1]；EAST(nx=+1) 板在 -X 侧 [0,0.125]
     *   SOUTH(nz=+1) 板在 -Z 侧 [0,0.125]； WEST(nx=-1) 板在 +X 侧 [0.875,1]
     * 厚度取 0.25 格（比显示的 0.125 板略厚一点），保证准星从正面容易点中，
     * 同时明显小于整格；碰撞仍然返回 null（不挡路）。
     */
    static {
        double t = 0.25D;
        // NORTH：画朝 -Z，板贴 +Z 侧
        BOXES[0] = new AxisAlignedBB(0.0D, 0.0D, 1.0D - t, 1.0D, 1.0D, 1.0D);
        // EAST ：画朝 +X，板贴 -X 侧
        BOXES[1] = new AxisAlignedBB(0.0D, 0.0D, 0.0D, t, 1.0D, 1.0D);
        // SOUTH：画朝 +Z，板贴 -Z 侧
        BOXES[2] = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, t);
        // WEST ：画朝 -X，板贴 +X 侧
        BOXES[3] = new AxisAlignedBB(1.0D - t, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    }

    public BlockHuangPoster() {
        super(Material.WOOD);
        setRegistryName(RsAudio.MODID, "huang_poster");
        setUnlocalizedName(RsAudio.MODID + ".huang_poster");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(1.0F);
        setResistance(6.0F);
        setSoundType(net.minecraft.block.SoundType.WOOD);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, HORIZONTAL[meta & 3]);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return indexOf(state.getValue(FACING));
    }

    public static int indexOf(EnumFacing facing) {
        for (int i = 0; i < HORIZONTAL.length; i++) {
            if (HORIZONTAL[i] == facing) {
                return i;
            }
        }
        return 0;
    }

    /** 竖直朝向一律折回 NORTH：本方块只支持水平四向 */
    private static EnumFacing horizontal(EnumFacing f) {
        return f == null || !f.getAxis().isHorizontal() ? EnumFacing.NORTH : f;
    }

    /** 画朝着放置者 —— 和告示牌 / 巨大标志牌同一套语义 */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        EnumFacing face = placer == null ? EnumFacing.SOUTH
                : horizontal(placer.getHorizontalFacing().getOpposite());
        return getDefaultState().withProperty(FACING, face);
    }

    // ------------------------------------------------------------------ 几何

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return BOXES[indexOf(state.getValue(FACING))];
    }

    @Override
    @SuppressWarnings("deprecation")
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        // 和原版的画 / 展示框一样：不产生实体碰撞，玩家能贴着墙走过去，不会被一整格挡住。
        // 准星选中用的是 getBoundingBox（近满格，保证右键点得中），和这个是两回事。
        return null;
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
        return new TileHuangPoster();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileHuangPoster();
    }

    // ------------------------------------------------------------------ 右键

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 副手不重复触发，否则一次点击翻两页 / 放两遍歌
        if (hand != EnumHand.MAIN_HAND) {
            return true;
        }
        if (worldIn.isRemote) {
            // 歌由客户端自己播（见 HuangChantController），画面等服务端同步页码
            if (playerIn.isSneaking()) {
                com.rsaudio.client.HuangChantController.play(pos);
            }
            return true;
        }
        TileEntity te = worldIn.getTileEntity(pos);
        if (!(te instanceof TileHuangPoster)) {
            RsAudio.warn("[纪念挂画] 右键命中方块但拿不到方块实体（TE=%s）——画面对不上就查这里",
                    te == null ? "null" : te.getClass().getName());
            return true;
        }
        TileHuangPoster poster = (TileHuangPoster) te;
        if (playerIn.isSneaking()) {
            return true;    // 歌在客户端那边放完了
        }
        // 翻页：改方块实体的页码，跟着 NBT 同步给所有客户端
        int newPage = poster.nextPage();
        RsAudio.log("[纪念挂画] 服务端收到右键，翻到第 %d 页 pos=%s", newPage, pos);
        poster.syncToClient();
        worldIn.playSound(null, pos, this.getSoundType(state, worldIn, pos, playerIn).getPlaceSound(),
                SoundCategory.BLOCKS, 0.5F, 1.7F);
        return true;
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.huang_poster.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.huang_poster.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.huang_poster.3"));
    }
}

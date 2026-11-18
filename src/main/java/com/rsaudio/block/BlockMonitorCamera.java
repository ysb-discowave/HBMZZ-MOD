package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileMonitorCamera;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
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
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 监控摄像头。
 *
 * <h3>安装方向</h3>
 * 六个面都能装：{@link #FACING} 是<b>镜头看出去的方向</b>，
 * 外壳则贴着反方向的那一面（也就是"墙"在哪边）。
 * 挖掉它再在别处放，就是换位置；想转镜头方向不用拆 —— 界面里改不了朝向，
 * 但界面上会显示当前朝向，拆了重放即可（一个便宜方块，不值得做旋转逻辑）。
 *
 * <h3>碰撞箱</h3>
 * 只占贴着墙的那 3/16 格厚的一小块，所以是"挂在墙上"的观感，
 * 六个方向各有一个预置盒（{@link #BOXES}），按 {@code FACING} 取。
 *
 * <h3>画面</h3>
 * 方块本身不存画面。右键打开设置界面选<b>频道</b>，
 * 监控控制台按频道去取"离自己最近的那个摄像头"的画面。
 */
public class BlockMonitorCamera extends Block implements ITileEntityProvider {

    /** 镜头朝向（六个面，不只是水平） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class);

    /**
     * 六个朝向的碰撞箱，下标用 {@link EnumFacing#ordinal()}：
     * DOWN, UP, NORTH, SOUTH, WEST, EAST —— 正是 EnumFacing 的声明顺序。
     * 盒子总是贴着"镜头反面"那一侧，厚度 5/16 格。
     */
    private static final AxisAlignedBB[] BOXES = new AxisAlignedBB[6];

    static {
        double lo = 3.0D / 16.0D;
        double hi = 13.0D / 16.0D;
        double near = 0.0D;
        double far = 5.0D / 16.0D;
        // DOWN：镜头朝下 → 贴在天花板上 → 盒子在上半部
        BOXES[EnumFacing.DOWN.ordinal()] = new AxisAlignedBB(lo, 1.0D - far, lo, hi, 1.0D, hi);
        // UP：镜头朝上 → 贴在地面上 → 盒子在下半部
        BOXES[EnumFacing.UP.ordinal()] = new AxisAlignedBB(lo, near, lo, hi, far, hi);
        // NORTH：镜头朝北(-Z) → 贴在 +Z 那面墙上
        BOXES[EnumFacing.NORTH.ordinal()] = new AxisAlignedBB(lo, lo, 1.0D - far, hi, hi, 1.0D);
        // SOUTH：镜头朝南(+Z) → 贴在 -Z 那面墙上
        BOXES[EnumFacing.SOUTH.ordinal()] = new AxisAlignedBB(lo, lo, near, hi, hi, far);
        // WEST：镜头朝西(-X) → 贴在 +X 那面墙上
        BOXES[EnumFacing.WEST.ordinal()] = new AxisAlignedBB(1.0D - far, lo, lo, 1.0D, hi, hi);
        // EAST：镜头朝东(+X) → 贴在 -X 那面墙上
        BOXES[EnumFacing.EAST.ordinal()] = new AxisAlignedBB(near, lo, lo, far, hi, hi);
    }

    public BlockMonitorCamera() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "monitor_camera");
        setUnlocalizedName(RsAudio.MODID + ".monitor_camera");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(2.0F);
        setResistance(10.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    public static IBlockState stateFor(EnumFacing facing) {
        return com.rsaudio.ModBlocks.MONITOR_CAMERA.getDefaultState()
                .withProperty(FACING, facing == null ? EnumFacing.NORTH : facing);
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getFront(meta & 7));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getIndex();
    }

    /**
     * 放置时镜头朝<b>外</b>（离开所贴的那一面），六个朝向统一处理。
     * <p>
     * ★ 这里用的是"被右击的那一面" {@code facing}，不是玩家的视线方向。
     * 理由（值得写下来，因为很容易想反）：
     * <ol>
     *   <li>{@code facing} = 被右击那块方块<b>被击中的那一面</b>，它天然朝外指着放置位置；
     *       新方块恰好落在 {@code pos.offset(facing)}。</li>
     *   <li>所以"被贴住的那个面"在新方块的 {@code facing.getOpposite()} 侧 ——
     *       于是 {@code facing} 正好是<b>离开墙面朝外</b>的方向，
     *       既等于"镜头该看的方向"，也正好是 {@link #BOXES} 里"外壳贴对面"的那一侧。</li>
     *   <li>贴墙 / 吸顶 / 落地三种情形全部覆盖，不用分情况讨论 ——
     *       对着地板右键得到 {@code UP}（镜头朝天、外壳贴地板），
     *       对着天花板右键得到 {@code DOWN}，都对。</li>
     * </ol>
     * 对照：{@code EnumFacing.getDirectionFromEntityLiving} 返回的是
     * {@code placer.getHorizontalFacing().getOpposite()}（反汇编核对过，见
     * {@code tools/api_probe.py}），只有"贴墙"这一种情形恰好对；
     * 站在地上往脚下放时它会退化成水平朝向，"落地"就不成立了。
     */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        EnumFacing look = facing == null ? EnumFacing.NORTH : facing;
        return getDefaultState().withProperty(FACING, look);
    }

    // ------------------------------------------------------------------ 形状

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return BOXES[state.getValue(FACING).ordinal()];
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        return BOXES[state.getValue(FACING).ordinal()];
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileMonitorCamera();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileMonitorCamera();
    }

    public static TileMonitorCamera getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileMonitorCamera ? (TileMonitorCamera) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // 手持木斧时让给"结构魔杖"，否则玩家想选角会变成开界面
        if (playerIn.getHeldItemMainhand().getItem() == net.minecraft.init.Items.WOODEN_AXE) {
            return false;
        }
        if (worldIn.isRemote) {
            // 界面只有客户端有；服务端只需要等着收 PacketSetCamera
            RsAudio.proxy.openMonitorCameraGui(pos);
        } else {
            TileMonitorCamera tile = getTile(worldIn, pos);
            if (tile != null && tile.getOwner().isEmpty() && playerIn.getName() != null) {
                tile.setOwner(playerIn.getName());
            }
        }
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.camera.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.camera.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.camera.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.camera.4"));
    }
}

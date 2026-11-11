package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileElevatorController;
import net.minecraft.block.Block;
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
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 电梯层门：贴着井道某一面放，每层放几扇（宽度按轿厢来，3 格轿厢就摆 3 扇）。
 * <p>
 * <b>开关由控制器统一驱动</b> —— 层门自己不留方块实体、不做判定。
 * 控制器在"轿厢到站"那一刻把这一层的所有层门一起写成 {@code open=true}，
 * 离开时再一起关上。这样"门开的时候轿厢一定在"这条不变式只由一处保证。
 *
 * <h3>★★★ 窒息豁免：这一条不写会出人命</h3>
 * 本方块 {@code isFullCube(state) = !OPEN} —— <b>默认状态（关着）是满立方体</b>。
 * 而原版 {@code Block.causesSuffocation(state)} 的默认实现问的是
 * <b>{@code getDefaultState().isFullCube()}</b>（不是传进来的那个 state！），
 * 采样点是玩家的<b>眼睛那一格</b>。
 * <p>
 * ⇒ 不覆写的话，<b>门开着也一样窒息</b>：玩家站在门洞里会被
 * {@code EntityLivingBase.onLivingUpdate → isEntityInsideOpaqueBlock → attackEntityFrom(inWall)}
 * 每 tick 扣血。改碰撞盒<b>救不了</b>（那是两个互不相干的判定）。
 * 本项目在防爆门上实锤过这一条（见 {@code verify_jar.py} §[12]）。
 */
public class BlockElevatorDoor extends Block {

    /** 层的朝向（门朝哪边）。只取水平四向 */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 门是否打开。★ 由控制器写，玩家改不动 */
    public static final PropertyBool OPEN = PropertyBool.create("open");

    private static final AxisAlignedBB FULL_BB = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
    /**
     * 关着的门那一片"门扇"：占满整格，但只让碰撞盒薄薄一层 ——
     * 这样玩家能贴着门站，而不是被一格厚的门推到一格以外。
     */
    private static final AxisAlignedBB SLAB_N = new AxisAlignedBB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 0.15D);

    public BlockElevatorDoor() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "elevator_door");
        setUnlocalizedName(RsAudio.MODID + ".elevator_door");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(5.0F);
        setResistance(40.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(OPEN, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, OPEN);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(OPEN, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(OPEN)) {
            meta |= 4;
        }
        return meta;
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        // 门朝玩家 = 玩家站在哪边，门就往哪边开
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    /** 打开这一层的门（只由 {@link TileElevatorController} 调；和它写世界的时机成对） */
    public static void setOpen(World world, BlockPos pos, boolean open) {
        if (world == null || pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockElevatorDoor) || state.getValue(OPEN) == open) {
            return;
        }
        world.setBlockState(pos, state.withProperty(OPEN, open), 3);
    }

    // ------------------------------------------------------------------ 形状

    @Override
    public boolean isFullCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    /**
     * ★★★ 见类注释：不覆写的话门开着也窒息。
     * 这是"凡是 {@code isFullCube} 看状态的方块都必须覆写"这条规矩的第三个实例
     * （前两个是防爆门控制器格与门板格）。
     */
    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        if (state.getValue(OPEN)) {
            return FULL_BB;                    // 开着的时候只有选择框，没有碰撞
        }
        switch (state.getValue(FACING)) {
            case SOUTH:
                return new AxisAlignedBB(0.0D, 0.0D, 0.85D, 1.0D, 1.0D, 1.0D);
            case WEST:
                return new AxisAlignedBB(0.0D, 0.0D, 0.0D, 0.15D, 1.0D, 1.0D);
            case EAST:
                return new AxisAlignedBB(0.85D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D);
            case NORTH:
            default:
                return SLAB_N;
        }
    }

    /**
     * 碰撞盒：★ 必须 {@code getBoundingBox(...).offset(pos)} —— 原版约定是<b>绝对坐标</b>。
     * 直接返回局部坐标的话，全世界所有门的碰撞盒会堆在原点那一格（防爆门踩过这个坑）。
     */
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        if (state.getValue(OPEN)) {
            return NULL_AABB;
        }
        return getBoundingBox(state, worldIn, pos).offset(pos);
    }

    @Override
    public BlockRenderLayer getBlockLayer() {
        return BlockRenderLayer.CUTOUT;
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 右键层门 = 呼叫轿厢到这一层。
     * <p>
     * ★ 这是**本方块唯一的右键语义**，而且它必须留在这里而不是搬到魔杖那边 ——
     * 本项目反复踩的坑就是"一个方块的右键语义被拆到两处"，修好一半必然弄坏另一半。
     * 魔杖那边对电梯方块是整块早退（见 {@code WandHandler}）。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            return true;                       // 结果由服务端回话，客户端不猜
        }
        TileElevatorController ctrl = BlockElevatorCar.findController(worldIn, pos);
        if (ctrl == null) {
            // ★ 失败提示必须"此刻现场重探"并<b>自报成因</b>：这里就是"门旁边没有控制柜"。
            playerIn.sendStatusMessage(
                    new net.minecraft.util.text.TextComponentTranslation("chat.rsaudio.elevator.no_controller"),
                    true);
            return true;
        }
        ctrl.callAtDoor(pos, playerIn);
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_door.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_door.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_door.3"));
    }
}

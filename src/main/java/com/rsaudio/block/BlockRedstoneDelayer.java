package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileRedstoneDelayer;
import com.rsaudio.util.Broadcaster;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRedstoneWire;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;

/**
 * 红石延迟器。
 * <p>
 * 行为对齐原版红石中继器：
 * <ul>
 *     <li>{@link #FACING} 指向<b>输入面</b>（放置时输入面朝向玩家），输出面是它的背面，
 *     这一点和原版 {@code BlockRedstoneDiode} 的约定一致。</li>
 *     <li>输入面读到信号后延迟若干刻，从输出面输出强度 15；信号消失时立刻关闭
 *     （只有上升沿有延迟）。</li>
 *     <li>只向输出面供电（二极管特性）。</li>
 *     <li>右键打开界面，直接输入延迟时间（支持 2 / 1.5s / 500ms / 10t / 1m）。</li>
 * </ul>
 * 延迟值存在 {@link TileRedstoneDelayer} 的 NBT 里，范围 {@link #MIN_DELAY_TICKS}~{@link #MAX_DELAY_TICKS} 刻。
 * 方块状态里只有一个<b>只读的显示属性</b>{@link #LEVEL}（1~8 档刻度条），由 {@link #getActualState} 现算，
 * 不占 meta，也不会因为改延迟而需要新模型。
 */
public class BlockRedstoneDelayer extends Block implements ITileEntityProvider {

    /** 最短 1 刻 = 0.05 秒（和原版中继器下限一致） */
    public static final int MIN_DELAY_TICKS = 1;
    /** 最长 12000 刻 = 600 秒 = 10 分钟 */
    public static final int MAX_DELAY_TICKS = 12000;
    /** 默认 2 刻 = 0.1 秒 */
    public static final int DEFAULT_DELAY_TICKS = 2;

    /** 输入面方向（输出面 = FACING.getOpposite()） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    public static final PropertyBool POWERED = PropertyBool.create("powered");
    /**
     * 顶面刻度条的档位，只用于渲染。
     * <p>
     * 它<b>不参与</b> meta（{@link #getMetaFromState} 不写它），真实数值在方块实体里，
     * 渲染时由 {@link #getActualState} 填进来。
     */
    public static final PropertyInteger LEVEL = PropertyInteger.create("level", 1, 8);

    public BlockRedstoneDelayer() {
        super(Material.CIRCUITS);
        setRegistryName(RsAudio.MODID, "redstone_delayer");
        setUnlocalizedName(RsAudio.MODID + ".redstone_delayer");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(0.0F);
        setSoundType(SoundType.STONE);
        setLightOpacity(0);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(POWERED, Boolean.FALSE)
                .withProperty(LEVEL, 1));
    }

    // ------------------------------------------------------------------ 数值工具

    public static int clampDelay(int ticks) {
        if (ticks < MIN_DELAY_TICKS) {
            return MIN_DELAY_TICKS;
        }
        return ticks > MAX_DELAY_TICKS ? MAX_DELAY_TICKS : ticks;
    }

    /**
     * 把真实延迟换算成顶面 8 格刻度条的档位。
     * 前 1 秒内线性分 4 档（红石常用的精细区），再往上按数量级分档。
     */
    public static int delayToLevel(int ticks) {
        if (ticks <= 20) {
            // 1..4 刻 -> 1 档, 6..10 -> 2 档, 11..15 -> 3 档, 16..20 -> 4 档
            return Math.max(1, Math.min(4, (ticks + 4) / 5));
        }
        if (ticks <= 200) {        // 10 秒内
            return 5;
        }
        if (ticks <= 1200) {       // 1 分钟内
            return 6;
        }
        if (ticks <= 3600) {       // 3 分钟内
            return 7;
        }
        return 8;
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, POWERED, LEVEL);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        // LEVEL 不进 meta，这里保持默认值，渲染时由 getActualState 填
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(POWERED, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(POWERED)) {
            meta |= 4;
        }
        return meta;
    }

    @Override
    public IBlockState getActualState(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        int level = 1;
        try {
            TileEntity te = worldIn.getTileEntity(pos);
            if (te instanceof TileRedstoneDelayer) {
                level = delayToLevel(((TileRedstoneDelayer) te).getDelayTicks());
            }
        } catch (Throwable ignored) {
            // 这个方法在区块重建渲染时会被高频调用，绝对不能抛异常（会直接崩渲染）。
            // 万一读方块实体出了意外，退回到最小刻度就行，反正只是个显示。
        }
        return state.withProperty(LEVEL, level);
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        // 和原版中继器一致：输入面朝向玩家，输出面朝向玩家看向的方向
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    public static EnumFacing getOutputSide(IBlockState state) {
        return state.getValue(FACING).getOpposite();
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileRedstoneDelayer();
    }

    /** 兼容旧入口（有些原版/Forge 代码路径走的是 ITileEntityProvider） */
    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileRedstoneDelayer();
    }

    private static TileRedstoneDelayer getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileRedstoneDelayer ? (TileRedstoneDelayer) te : null;
    }

    // ------------------------------------------------------------------ 红石

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return side != null && side.getAxis() != EnumFacing.Axis.Y;
    }

    /**
     * 只在输出面供电。
     * <p>
     * 1.12.2 的约定：{@code side} 是"请求方所在的方向"的反方向，即请求方块位于
     * {@code pos.offset(side.getOpposite())}。所以 {@code side == FACING} 时请求方在输出面。
     */
    @Override
    public int getWeakPower(IBlockState state, IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        return state.getValue(POWERED) && side == state.getValue(FACING) ? 15 : 0;
    }

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        return getWeakPower(state, worldIn, pos, side);
    }

    @Override
    public void onBlockAdded(World worldIn, BlockPos pos, IBlockState state) {
        super.onBlockAdded(worldIn, pos, state);
        notifyNeighbors(worldIn, pos, state);
        if (!worldIn.isRemote) {
            checkInput(worldIn, pos, state);
        }
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        if (worldIn.isRemote) {
            return;
        }
        checkInput(worldIn, pos, state);
    }

    /** 检查输入：上升沿排定延迟，下降沿立刻关闭 */
    private void checkInput(World world, BlockPos pos, IBlockState state) {
        TileRedstoneDelayer tile = getTile(world, pos);
        int delay = tile == null ? DEFAULT_DELAY_TICKS : tile.getDelayTicks();
        boolean hasInput = calculateInputStrength(world, pos, state) > 0;

        if (!hasInput) {
            if (tile != null) {
                tile.setPending(false);
            }
            if (state.getValue(POWERED)) {
                setPowered(world, pos, false);
            }
            return;
        }

        if (state.getValue(POWERED)) {
            return;                                  // 已经在输出了
        }
        if (tile != null && tile.isPending()) {
            return;                                  // 已经在倒计时，别重复排程
        }
        if (tile != null) {
            tile.setPending(true);
            tile.setRiseTime(world.getTotalWorldTime());
        }
        world.scheduleUpdate(pos, this, delay);
    }

    @Override
    public void updateTick(World worldIn, BlockPos pos, IBlockState state, Random rand) {
        if (worldIn.isRemote || state.getValue(POWERED)) {
            return;
        }
        TileRedstoneDelayer tile = getTile(worldIn, pos);
        int delay = tile == null ? DEFAULT_DELAY_TICKS : tile.getDelayTicks();

        if (tile != null) {
            long elapsed = worldIn.getTotalWorldTime() - tile.getRiseTime();
            if (elapsed < delay) {
                // 玩家在倒计时途中改过时间（或者旧的排程提前触发了），按剩余时间重排
                worldIn.scheduleUpdate(pos, this, (int) Math.max(1L, delay - elapsed));
                return;
            }
            tile.setPending(false);
        }

        if (calculateInputStrength(worldIn, pos, state) > 0) {
            setPowered(worldIn, pos, true);
        }
    }

    /**
     * 延迟时间被改动后调用：如果正在倒计时，按新时间重排一次，避免"改短了还要等旧的时长"。
     */
    public void rescheduleIfWaiting(World world, BlockPos pos, IBlockState state) {
        if (world.isRemote) {
            return;
        }
        TileRedstoneDelayer tile = getTile(world, pos);
        if (tile == null || state.getValue(POWERED) || !tile.isPending()) {
            return;
        }
        long elapsed = world.getTotalWorldTime() - tile.getRiseTime();
        long remain = tile.getDelayTicks() - elapsed;
        world.scheduleUpdate(pos, this, (int) Math.max(1L, remain));
    }

    private void setPowered(World world, BlockPos pos, boolean powered) {
        IBlockState state = world.getBlockState(pos);
        if (state.getValue(POWERED) == powered) {
            return;
        }
        // 3 = 通知周围方块 + 同步给客户端
        IBlockState newState = state.withProperty(POWERED, powered);
        world.setBlockState(pos, newState, 3);
        if (powered) {
            notifyNeighbors(world, pos, newState);
            // 输出生效 -> 播报到聊天栏（没设文字就什么都不做）
            TileRedstoneDelayer tile = getTile(world, pos);
            if (tile != null) {
                Broadcaster.send(world, pos, tile.getBroadcastText(), tile.getBroadcastColor());
            }
        }
    }

    /** 与原版中继器一致：通知输出面方向的方块重新查询红石强度 */
    private void notifyNeighbors(World world, BlockPos pos, IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        BlockPos outputPos = pos.offset(facing.getOpposite());
        world.neighborChanged(outputPos, this, pos);
        world.notifyNeighborsOfStateExcept(outputPos, this, facing);
    }

    /**
     * 读取输入面方向方块的供电强度（等价于原版 {@code BlockRedstoneDiode#calculateInputStrength}）。
     */
    private int calculateInputStrength(World world, BlockPos pos, IBlockState state) {
        EnumFacing facing = state.getValue(FACING);
        BlockPos inputPos = pos.offset(facing);
        int power = world.getRedstonePower(inputPos, facing);
        if (power >= 15) {
            return power;
        }
        IBlockState inputState = world.getBlockState(inputPos);
        if (inputState.getBlock() == Blocks.REDSTONE_WIRE) {
            power = Math.max(power, inputState.getValue(BlockRedstoneWire.POWER));
        }
        return power;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            // 延迟输入界面是纯客户端界面，改值通过 PacketSetDelay 发回服务端
            RsAudio.proxy.openRedstoneDelayerGui(pos);
        }
        return true;
    }

    // ------------------------------------------------------------------ 其它

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.delayer.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.delayer.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.delayer.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.delayer.4"));
    }
}

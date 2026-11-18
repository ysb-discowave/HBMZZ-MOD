package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileRedstoneComputer;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 自动化红石信号输出计算机：跑一段"极简汇编"程序来控制六个面的红石输出。
 *
 * <h3>六个面各自独立</h3>
 * 每一个面都可以输出 0~15 的任意强度（不是只有开/关），也可以读入对面来的强度 ——
 * 所以它可以当"复杂的定时信号源"用：闪烁、序列脉冲、按输入条件输出不同强度、发报机……
 *
 * <h3>{@link #getWeakPower} 的 side 语义（很容易写反，记一下）</h3>
 * 1.12.2 里 {@code side} 指的是<b>从请求方指向本方块</b>的方向，
 * 也就是请求方位于 {@code pos.offset(side.getOpposite())}。
 * 原版中继器就是这么写的（{@code FACING == side} 时输出），
 * 所以这里要取 {@code side.getOpposite()} 那一面的输出。
 */
public class BlockRedstoneComputer extends Block implements ITileEntityProvider {

    /** 程序正在运行（面板上的指示灯） */
    public static final PropertyBool RUNNING = PropertyBool.create("running");

    public BlockRedstoneComputer() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "redstone_computer");
        setUnlocalizedName(RsAudio.MODID + ".redstone_computer");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.0F);
        setResistance(15.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(RUNNING, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, RUNNING);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(RUNNING, (meta & 1) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(RUNNING) ? 1 : 0;
    }

    /** 运行的时候面板会发光，远处也能一眼看出它在工作 */
    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.getValue(RUNNING) ? 7 : 0;
    }

    // ------------------------------------------------------------------ 红石输出

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    @Override
    public boolean canConnectRedstone(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing side) {
        return true;                     // 六个面都接
    }

    @Override
    public int getWeakPower(IBlockState state, IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        TileRedstoneComputer tile = getTile(worldIn, pos);
        if (tile == null || side == null) {
            return 0;
        }
        return tile.getOutputFor(side.getOpposite().ordinal());
    }

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        return getWeakPower(state, worldIn, pos, side);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileRedstoneComputer();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileRedstoneComputer();
    }

    public static TileRedstoneComputer getTile(IBlockAccess world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileRedstoneComputer ? (TileRedstoneComputer) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.isSneaking()) {
            // 潜行右键 = 快捷 运行/停止
            if (!worldIn.isRemote) {
                TileRedstoneComputer tile = getTile(worldIn, pos);
                if (tile != null) {
                    boolean running = tile.toggleRunning();
                    playerIn.sendStatusMessage(new TextComponentTranslation(
                            running ? "chat.rsaudio.computer.started" : "chat.rsaudio.computer.stopped"), true);
                }
            }
            return true;
        }
        if (worldIn.isRemote) {
            RsAudio.proxy.openComputerGui(pos);
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            TileRedstoneComputer tile = getTile(worldIn, pos);
            if (tile != null) {
                // 拆掉时先松掉所有输出，不然旁边的机器会一直以为有信号
                tile.releaseOutputs();
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.computer.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.computer.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.computer.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.computer.4"));
    }
}

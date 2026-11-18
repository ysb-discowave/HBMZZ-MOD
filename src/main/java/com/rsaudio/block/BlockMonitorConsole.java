package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileMonitorConsole;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * 监控控制台。
 *
 * <h3>它是"显示器 + 操作台"</h3>
 * 屏幕开在 {@link #FACING} 那一面（放下去时正面朝玩家）。
 * 屏幕上会实时显示它所在频道的摄像头画面；右键打开界面则是同一张画面的大图版，
 * 外加频道切换和摄像头清单。
 *
 * <h3>为什么是整方块</h3>
 * 屏幕要占满一个面才好看，而且玩家会拿它当"操作台"靠着摆一排，
 * 做成小方块反而不好对齐。
 */
public class BlockMonitorConsole extends Block implements ITileEntityProvider {

    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);

    public BlockMonitorConsole() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "monitor_console");
        setUnlocalizedName(RsAudio.MODID + ".monitor_console");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.0F);
        setResistance(15.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    public static IBlockState stateFor(EnumFacing facing) {
        return com.rsaudio.ModBlocks.MONITOR_CONSOLE.getDefaultState()
                .withProperty(FACING, facing == null ? EnumFacing.NORTH : facing);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(FACING, EnumFacing.getHorizontal(meta & 3));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).getHorizontalIndex();
    }

    /** 屏幕朝玩家 */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return true;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return true;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileMonitorConsole();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileMonitorConsole();
    }

    public static TileMonitorConsole getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileMonitorConsole ? (TileMonitorConsole) te : null;
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.getHeldItemMainhand().getItem() == net.minecraft.init.Items.WOODEN_AXE) {
            return false;
        }
        if (worldIn.isRemote) {
            RsAudio.proxy.openMonitorConsoleGui(pos);
        }
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.console.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.console.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.console.3"));
    }
}

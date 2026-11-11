package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileStructureLoader;
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
 * 结构加载器：把 {@code config/rsaudio/structures} 里的结构贴到世界上。
 * <p>
 * <ul>
 *     <li>右键：打开界面（选结构、调偏移、加载）。</li>
 *     <li>潜行右键：直接用当前设置加载一次。</li>
 *     <li>收到红石信号（上升沿）：加载一次 —— 于是可以做成"红石触发的自动建筑机"。</li>
 * </ul>
 * 粘贴会把目标长方体<b>先清成空气再放方块</b>，所以结果和保存时一致；
 * 方块实体（箱子内容、机器设置）也会一起复制。大结构由方块实体分 tick 贴，不会一 tick 卡死。
 */
public class BlockStructureLoader extends Block implements ITileEntityProvider {

    /** 正面朝向（放置时朝向玩家） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 正在粘贴中（亮起指示灯） */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockStructureLoader() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "structure_loader");
        setUnlocalizedName(RsAudio.MODID + ".structure_loader");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.5F);
        setResistance(20.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(ACTIVE, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, ACTIVE);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(ACTIVE, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(ACTIVE)) {
            meta |= 4;
        }
        return meta;
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        // 正面朝向玩家（和熔炉的约定一致）
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileStructureLoader();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileStructureLoader();
    }

    static TileStructureLoader getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileStructureLoader ? (TileStructureLoader) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.isSneaking()) {
            // 潜行右键 = 立刻加载一次（不用开界面）
            if (!worldIn.isRemote) {
                TileStructureLoader tile = getTile(worldIn, pos);
                if (tile != null) {
                    tile.loadNow(playerIn);
                }
            }
            return true;
        }
        if (worldIn.isRemote) {
            RsAudio.proxy.openStructureLoaderGui(pos);
        }
        return true;
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        // 红石上升沿由方块实体的 update() 判断（它每 tick 都在跑），这里不用做事
    }

    /** 供方块实体同步"正在加载"指示灯 */
    public static void setActive(World world, BlockPos pos, boolean active) {
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockStructureLoader) || state.getValue(ACTIVE) == active) {
            return;
        }
        world.setBlockState(pos, state.withProperty(ACTIVE, active), 3);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.loader.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.loader.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.loader.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.loader.4"));
    }
}

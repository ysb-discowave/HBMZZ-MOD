package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileAnnouncer;
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
 * 广播器：向范围内的玩家<b>弹右下角提示框</b>，并用 TTS 把文字念出来。
 * <p>
 * <ul>
 *     <li><b>右键</b>：打开界面，填「弹窗标题 / 弹窗内容 / 朗读文本」，
 *         再调范围和停留时长，点「广播」立刻发一次。</li>
 *     <li><b>潜行右键</b>：不用开界面，直接用当前设置广播一次。</li>
 *     <li><b>红石上升沿</b>：自动广播一次 —— 于是"反应堆温度超限"这类事件
 *         可以直接接上红石信号，不需要玩家去点。</li>
 * </ul>
 *
 * <h3>为什么弹窗和语音是两条独立的通道</h3>
 * 语音需要服务器装一个几十 MB 的 TTS 引擎，弹窗不需要。两者绑死的话，
 * 没装引擎的服务器就整个功能不可用；拆开之后<b>没引擎也能弹窗</b>，
 * 只是没有声音。所以服务端侧是"能发声就发声，不能就静默降级"。
 */
public class BlockAnnouncer extends Block implements ITileEntityProvider {

    /** 正面朝向（放置时朝向玩家） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 刚刚广播过（指示灯亮一下） */
    public static final PropertyBool ACTIVE = PropertyBool.create("active");

    public BlockAnnouncer() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "announcer");
        setUnlocalizedName(RsAudio.MODID + ".announcer");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.5F);
        setResistance(20.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(ACTIVE, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

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
        // 喇叭口朝向玩家（和熔炉的约定一致）
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    /** 供方块实体同步"刚广播过"的指示灯 */
    public static void setActive(World world, BlockPos pos, boolean active) {
        if (world == null || pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockAnnouncer) || state.getValue(ACTIVE) == active) {
            return;
        }
        world.setBlockState(pos, state.withProperty(ACTIVE, active), 3);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAnnouncer();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileAnnouncer();
    }

    static TileAnnouncer getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileAnnouncer ? (TileAnnouncer) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.isSneaking()) {
            // 潜行右键 = 直接按当前设置广播一次，不用开界面
            if (!worldIn.isRemote) {
                TileAnnouncer tile = getTile(worldIn, pos);
                if (tile != null) {
                    tile.broadcast(playerIn);
                }
            }
            return true;
        }
        if (worldIn.isRemote) {
            RsAudio.proxy.openAnnouncerGui(pos);
        }
        return true;
    }

    @Override
    public void neighborChanged(IBlockState state, World worldIn, BlockPos pos, Block blockIn, BlockPos fromPos) {
        // 红石上升沿交给方块实体的 update() 判断（它每 tick 都在跑），这里不用做事
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.announcer.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.announcer.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.announcer.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.announcer.4"));
    }
}

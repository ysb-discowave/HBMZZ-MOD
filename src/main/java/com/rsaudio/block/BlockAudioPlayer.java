package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileAudioPlayer;
import net.minecraft.block.Block;
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
 * 音频播放器方块。
 * <p>
 * 接受红石信号，按方块实体的播放模式播放 / 停止音乐；右键打开选歌界面（含模式设置），
 * 潜行右键清除选择。
 * <p>
 * 开了"播放完输出红石信号"之后，整首播完时会短暂对外供电（{@link #PULSE}），
 * 可以拿去触发下一级电路。
 */
public class BlockAudioPlayer extends Block {

    public static final PropertyBool ACTIVE = PropertyBool.create("active");
    /** 播完输出的红石脉冲（只持续几刻，由方块实体倒计时清掉） */
    public static final PropertyBool PULSE = PropertyBool.create("pulse");

    public BlockAudioPlayer() {
        super(Material.WOOD);
        setRegistryName(RsAudio.MODID, "audio_player");
        setUnlocalizedName(RsAudio.MODID + ".audio_player");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(2.0F);
        setResistance(10.0F);
        setSoundType(SoundType.WOOD);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(ACTIVE, Boolean.FALSE)
                .withProperty(PULSE, Boolean.FALSE));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, ACTIVE, PULSE);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(ACTIVE, (meta & 1) != 0)
                .withProperty(PULSE, (meta & 2) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = 0;
        if (state.getValue(ACTIVE)) {
            meta |= 1;
        }
        if (state.getValue(PULSE)) {
            meta |= 2;
        }
        return meta;
    }

    // ------------------------------------------------------------------ 红石输出

    @Override
    public boolean canProvidePower(IBlockState state) {
        return true;
    }

    /** 播完的那几刻向所有方向供电（和红石块一样是"源"，不区分方向） */
    @Override
    public int getWeakPower(IBlockState state, IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        return state.getValue(PULSE) ? 15 : 0;
    }

    @Override
    public int getStrongPower(IBlockState state, IBlockAccess worldIn, BlockPos pos, EnumFacing side) {
        return getWeakPower(state, worldIn, pos, side);
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAudioPlayer();
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.isSneaking()) {
            // 潜行右键 = 清除已选音乐
            if (!worldIn.isRemote) {
                TileEntity te = worldIn.getTileEntity(pos);
                if (te instanceof TileAudioPlayer) {
                    TileAudioPlayer player = (TileAudioPlayer) te;
                    if (player.hasSound()) {
                        player.setSoundName("");
                        playerIn.sendStatusMessage(new TextComponentTranslation("chat.rsaudio.cleared"), true);
                    } else {
                        playerIn.sendStatusMessage(new TextComponentTranslation("chat.rsaudio.empty"), true);
                    }
                }
            }
            return true;
        }

        if (worldIn.isRemote) {
            // 打开选歌界面（纯客户端界面）
            RsAudio.proxy.openAudioPlayerGui(pos);
        }
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.player.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.player.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.player.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.player.4"));
    }
}

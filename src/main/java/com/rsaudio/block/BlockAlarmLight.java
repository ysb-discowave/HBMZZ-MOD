package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileAlarmLight;
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
 * 警报灯：收到红石信号就点亮，可以设置"爆闪周期"和"蜂鸣"。
 * <p>
 * 六种颜色是<b>六个独立方块</b>（alarm_red / alarm_orange / ... / alarm_purple），
 * 而不是一个方块 + 颜色属性：一个颜色的物品要单独有一套贴图和物品模型，
 * 拆成六个方块反而更简单，玩家在创造模式里也一眼能挑到想要的颜色。
 * <p>
 * 闪烁由方块实体在服务端统一驱动（写 {@link #LIT} 方块状态），
 * 所有客户端看到的是同一份节奏 —— 不会出现"每个人看到的闪法不一样"。
 */
public class BlockAlarmLight extends Block implements ITileEntityProvider {

    /** 亮/灭。放在方块状态里（而不是只存在方块实体里）是为了让光照能跟着变 */
    public static final PropertyBool LIT = PropertyBool.create("lit");

    /**
     * 六种颜色（顺序即创造模式物品栏顺序）。
     * ★ 这个数组和 {@code tools/gen_resources.py} 里的颜色表必须一致（贴图/方块状态按它生成）。
     */
    public static final String[] COLOR_NAMES = {"red", "orange", "yellow", "green", "blue", "purple"};
    /** 六种颜色各自的中英文字面量的后缀（语言文件键 rsaudio.alarm.<name>） */
    public static final int COLOR_COUNT = COLOR_NAMES.length;

    private final int colorIndex;

    public BlockAlarmLight(int colorIndex) {
        super(Material.REDSTONE_LIGHT);
        this.colorIndex = Math.max(0, Math.min(COLOR_COUNT - 1, colorIndex));
        String color = COLOR_NAMES[this.colorIndex];
        setRegistryName(RsAudio.MODID, "alarm_" + color);
        setUnlocalizedName(RsAudio.MODID + ".alarm." + color);
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(0.6F);
        setResistance(6.0F);
        setSoundType(SoundType.GLASS);
        setDefaultState(this.blockState.getBaseState().withProperty(LIT, Boolean.FALSE));
    }

    public int getColorIndex() {
        return colorIndex;
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, LIT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(LIT, (meta & 1) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(LIT) ? 1 : 0;
    }

    /** 亮起来真的会发光（点亮时 12 级，足够照亮一小片） */
    @Override
    public int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        return state.getValue(LIT) ? 12 : 0;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAlarmLight();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileAlarmLight();
    }

    static TileAlarmLight getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileAlarmLight ? (TileAlarmLight) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (!worldIn.isRemote) {
            TileAlarmLight tile = getTile(worldIn, pos);
            if (tile == null) {
                return true;
            }
            if (playerIn.isSneaking()) {
                // 潜行右键 = 快速开关蜂鸣（不用开界面）
                boolean beep = tile.toggleBeep();
                playerIn.sendStatusMessage(new TextComponentTranslation(
                        beep ? "chat.rsaudio.alarm.beep_on" : "chat.rsaudio.alarm.beep_off"), true);
                return true;
            }
        } else if (!playerIn.isSneaking()) {
            RsAudio.proxy.openAlarmGui(pos);
        }
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.alarm.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.alarm.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.alarm.3"));
    }
}

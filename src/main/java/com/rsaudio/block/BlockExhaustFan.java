package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileExhaustFan;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
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
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * 排风扇 / 排风口。装在墙上、天花板上或地上的大型轴流风扇，
 * 扇叶由 {@code ExhaustFanRenderer} 画（方块本身只是一个方壳）。
 *
 * <h3>朝向与"往哪吹"</h3>
 * {@link #FACING} = <b>扇叶那一面朝向</b>（也就是风吹出去的方向），
 * 取的是放置时右击的那一面 —— 和 {@code BlockMonitorCamera} 逐字相同的理由：
 * 被右击的面天然朝外指着放置位置，于是它同时就是"叶片该朝哪边"和
 * "外壳贴哪面墙"，贴墙 / 吸顶 / 落地三种情形不用分情况讨论。
 *
 * <h3>★ 方块模型靠 blockstate 的 x/y 旋转复用，不画 12 个模型</h3>
 * 一个 {@code exhaust_fan} 模型，正面（模型的 north 面）是进风格栅贴图，
 * 其余五面是外壳钢板。十二种状态（facing 6 × running 2）全部引用这<b>同一个</b>模型，
 * 只用变体里的 {@code x}/{@code y} 把它转到对应方向。
 * <p>
 * ★ 旋转角度不是"看着像"就行的，{@code gen_resources.py} 里那张表的依据是
 * 原版 {@code observer} 的 blockstate（{@code facing=north → 不转}、
 * {@code facing=down → x=90}、{@code facing=up → x=270}、
 * {@code east/west/south → y=90/270/180}）。
 * 写反的症状是"风扇装在东墙上、叶片却朝西"。
 * <p>
 * ★★ 但 {@code ExhaustFanRenderer} <b>完全不依赖</b>这张表：它像
 * {@code MonitorConsoleRenderer} 那样直接从 {@link #FACING} 算世界方向的基向量，
 * 一次 {@code GlStateManager.rotate} 都不用。所以"模型转对了、叶片转错了"
 * 这种两边约定不一致的错法在这台机器上<b>不可能发生</b>。
 *
 * <h3>开关</h3>
 * 右键切换运行 / 停转（存在方块状态里，会随方块同步给所有客户端，也会存档）。
 * 刻意<b>不接红石</b>：接上红石就意味着"右键手动切"和"被红石强制"两种来源
 * 会互相打架（谁说了算？），而排风扇在设施里本来就是常年开着的装饰件。
 */
public class BlockExhaustFan extends Block implements ITileEntityProvider {

    /** 扇叶朝哪一边（= 风吹出去的方向）。六个面都能装。 */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class);

    /** 转不转。由右键切换，存档保存。 */
    public static final PropertyBool RUNNING = PropertyBool.create("running");

    public BlockExhaustFan() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "exhaust_fan");
        setUnlocalizedName(RsAudio.MODID + ".exhaust_fan");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.0F);
        setResistance(20.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(RUNNING, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, RUNNING);
    }

    /** meta 低位 = facing 的 index（0..5），第 4 位 = running */
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getFront(meta & 7))
                .withProperty(RUNNING, Boolean.valueOf((meta & 8) != 0));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return (state.getValue(FACING).getIndex() & 7)
                | (state.getValue(RUNNING).booleanValue() ? 8 : 0);
    }

    /** 见类注释：被右击的那一面就是"朝外"，六个朝向统一处理 */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        EnumFacing look = facing == null ? EnumFacing.NORTH : facing;
        // 放下就转起来：排风扇的默认状态是"在工作"，玩家不满意再右键停掉。
        // 反过来（默认停着）会让人第一眼以为"这方块坏了"。
        return getDefaultState().withProperty(FACING, look).withProperty(RUNNING, Boolean.TRUE);
    }

    // ------------------------------------------------------------------ 右键

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            return true;                    // 客户端只负责不再往下传（服务端会处理）
        }
        // ★ 重新读一次状态：客户端预测和服务端实际可能差一步，用传进来的 state 会写回旧值
        IBlockState current = worldIn.getBlockState(pos);
        if (!(current.getBlock() instanceof BlockExhaustFan)) {
            return false;
        }
        boolean running = !current.getValue(RUNNING).booleanValue();
        worldIn.setBlockState(pos, current.withProperty(RUNNING, Boolean.valueOf(running)), 3);
        // 开关要有"咔"的一声，否则玩家分不清自己是按到了还是没按到
        worldIn.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_IRON_TRAPDOOR_CLOSE,
                SoundCategory.BLOCKS, 0.5F, running ? 1.4F : 0.8F);
        playerIn.sendStatusMessage(new TextComponentString(I18n.translateToLocal(
                running ? "chat.rsaudio.exhaust_fan.on" : "chat.rsaudio.exhaust_fan.off")), true);
        RsAudio.log("[排风扇] %s 把 %d,%d,%d 切到 %s",
                playerIn.getName(), Integer.valueOf(pos.getX()), Integer.valueOf(pos.getY()),
                Integer.valueOf(pos.getZ()), running ? "运行" : "停转");
        return true;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileExhaustFan();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileExhaustFan();
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.exhaust_fan.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.exhaust_fan.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.exhaust_fan.3"));
    }
}

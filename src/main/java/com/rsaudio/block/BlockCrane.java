package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileCrane;
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
 * 门式起重机。一块"底座机箱"方块，真正的龙门架 / 回转臂 / 吊钩由
 * {@code CraneRenderer}（TESR）画在方块上方（顶点色，不占贴图）。
 *
 * <h3>朝向</h3>
 * {@link #FACING} = <b>回转臂停着时指向的方向</b>（水平四向），
 * 取的是 {@code placer.getHorizontalFacing().getOpposite()}（和巨大标志牌同一套约定：
 * 对着它摆的人，一眼就能看到"这台车臂朝哪边"）。
 * 吸顶 / 落地装没有意义（吊钩要往下吊东西），所以只有四个朝向 ——
 * 与标志牌同理，而不是摄像头 / 排风扇那种六向。
 *
 * <h3>开关</h3>
 * 右键切换运行 / 停臂（存在方块状态里，随方块同步 + 存档）。
 * 和排风扇一样刻意<b>不接红石</b>：设施里的起重机是"常年待命"的机器件，
 * 右键手动切就够了。
 *
 * <h3>★ 方块模型只是"底座机箱"</h3>
 * 八个状态（facing 4 × running 2）全部引用<b>同一个</b> cube 模型，
 * 只用变体里的 y 旋转 —— 和排风扇的 twelve-variants-one-model 同一套路。
 * 上面那台会转的龙门架不靠模型：方块模型烘死在区块网格里没有逐帧动画，
 * 必须走 TESR（漏绑渲染器的症状见 {@code ClientProxy.init} 里排风扇那条注释）。
 */
public class BlockCrane extends Block implements ITileEntityProvider {

    /** 回转臂朝向（水平四向） */
    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create(
            "facing", EnumFacing.class,
            EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST);

    /** 臂转不转。右键切换，存档保存。 */
    public static final PropertyBool RUNNING = PropertyBool.create("running");

    public BlockCrane() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "crane");
        setUnlocalizedName(RsAudio.MODID + ".crane");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.0F);
        setResistance(20.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(RUNNING, Boolean.TRUE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, RUNNING);
    }

    /**
     * meta 低位 = 朝向，第 4 位 = running。
     * <p>
     * ★ 这里和 {@code BlockGiantSign} 有一个<b>刻意的差别</b>：写回 meta 用的是
     * {@code FACING 值的 ordinal()}（NORTH=2, SOUTH=3, WEST=4, EAST=5），
     * 读回再走 {@code EnumFacing.getFront(meta & 7)} —— 两趟是<b>对称</b>的。
     * 标志牌那边用的是 Property 的 index（NORTH=0, EAST=1, ...），与
     * {@code getFront} 的枚举序不对称（meta=1 读回来会变成 NORTH）。
     * 1.12.2 的区块存档按属性名序列化、不走这条 meta 往返，所以那边没出事；
     * 但既然重写，就没有理由把一个已知的不对称再抄一遍。
     */
    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, horizontal(EnumFacing.getFront(meta & 7)))
                .withProperty(RUNNING, Boolean.valueOf((meta & 8) != 0));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return (state.getValue(FACING).ordinal() & 7)
                | (state.getValue(RUNNING).booleanValue() ? 8 : 0);
    }

    /** 竖直朝向一律折回 NORTH：本方块只支持水平四向 */
    private static EnumFacing horizontal(EnumFacing f) {
        return f == null || !f.getAxis().isHorizontal() ? EnumFacing.NORTH : f;
    }

    /** 放置时回转臂<b>朝放置者</b>（和标志牌的字面同一套语义：摆的人能看到它） */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        EnumFacing face = placer == null ? EnumFacing.SOUTH
                : horizontal(placer.getHorizontalFacing().getOpposite());
        return getDefaultState().withProperty(FACING, face);
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
        if (!(current.getBlock() instanceof BlockCrane)) {
            return false;
        }
        boolean running = !current.getValue(RUNNING).booleanValue();
        worldIn.setBlockState(pos, current.withProperty(RUNNING, Boolean.valueOf(running)), 3);
        // 开关要有"嗡"的一声，否则玩家分不清自己是按到了还是没按到
        // ★ 本环境 MCP 快照里活塞音叫 BLOCK_PISTON_EXTEND / BLOCK_PISTON_CONTRACT
        //   （不是 1.12 常见的 _OUT/_IN），别写错名字。
        worldIn.playSound(null, pos, net.minecraft.init.SoundEvents.BLOCK_PISTON_EXTEND,
                SoundCategory.BLOCKS, 0.45F, running ? 1.2F : 0.7F);
        playerIn.sendStatusMessage(new TextComponentString(I18n.translateToLocal(
                running ? "chat.rsaudio.crane.on" : "chat.rsaudio.crane.off")), true);
        RsAudio.log("[起重机] %s 把 %d,%d,%d 切到 %s",
                playerIn.getName(), Integer.valueOf(pos.getX()), Integer.valueOf(pos.getY()),
                Integer.valueOf(pos.getZ()), running ? "运行" : "停臂");
        return true;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileCrane();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileCrane();
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.crane.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.crane.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.crane.3"));
    }
}

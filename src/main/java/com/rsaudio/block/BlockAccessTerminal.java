package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.access.TerminalCore;
import com.rsaudio.tile.TileAccessTerminal;
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
 * FAAS <b>门禁终端</b>：把一片区域（一个门禁频道）整体锁死或放行。
 *
 * <h3>用法</h3>
 * <ol>
 *   <li>放下终端，设好<b>频道</b>（1..16）。</li>
 *   <li>把这个频道里的门在各自的设置界面里也设成同一个频道 —— 门不需要打开「联锁」，
 *       联锁是"门与门互斥"（气闸用），终端是"区域规定"，两者独立。</li>
 *   <li>点「封锁全部」：频道里所有门的<b>远程门闩</b>点亮，本地怎么按都开不了。</li>
 *   <li>点「解除封锁」：只熄灭终端点亮的那一把。玩家自己在门上拉的封锁<b>原样留着</b>。</li>
 * </ol>
 *
 * <h3>★ 它是"区域规定"，不是"遥控器"</h3>
 * 封锁之后才造的门同样会被锁上 —— 终端会定期把区域状态重新落到频道里的每一扇门上
 * （见 {@link TerminalCore} 的类注释）。做成遥控器的话，玩家在封锁区里补一扇门就是个缺口。
 *
 * <h3>红石</h3>
 * <b>默认：来信号 = 封锁</b>。这样"反应堆出事 → 给终端一个信号 → 整片区域封死"可以直接接线，
 * 不需要玩家去点。打开「反向」之后变成"来信号 = 解除封锁"。
 * <p>
 * 只在电平<b>变化</b>时响应（边沿触发）。不过终端每个兜底校准周期本来就会扫一遍，
 * 所以即使漏了某次边沿，状态也会在 1 秒内自己纠回来。
 *
 * <h3>为什么不用 {@link com.rsaudio.block.BlockBlastDoor} 那种"木斧 + 潜行"开门方式</h3>
 * 门把"右键"占了两个动作（木斧不潜行 = 开关门、木斧潜行 = 设置界面），所以必须借木斧区分。
 * 终端没有这个负担：它本身不是门，右键就是开界面最顺手。
 * 潜行右键则做成"立刻翻转区域封锁"，不用开界面 —— 紧急封锁时少一步操作。
 */
public class BlockAccessTerminal extends Block implements ITileEntityProvider {

    /** 正面朝向（界面那一面朝玩家） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 区域封锁中（正面板上的锁定指示灯亮） */
    public static final PropertyBool LOCKED = PropertyBool.create("locked");

    public BlockAccessTerminal() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "access_terminal");
        setUnlocalizedName(RsAudio.MODID + ".access_terminal");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.5F);
        setResistance(30.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(LOCKED, Boolean.FALSE));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, LOCKED);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(LOCKED, (meta & 4) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(LOCKED)) {
            meta |= 4;
        }
        return meta;
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        // 界面那一面朝玩家
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    /**
     * 供方块实体同步"区域封锁中"的指示灯。
     * <p>
     * ★ 和 {@code BlockAnnouncer.setActive} 一样刻意做成静态方法：方块实体是双端共用的类，
     * 它<b>不能</b> import 客户端渲染类，但访问自己的方块状态是没问题的。
     */
    public static void setLockedIndicator(World world, BlockPos pos, boolean locked) {
        if (world == null || pos == null) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockAccessTerminal) || state.getValue(LOCKED) == locked) {
            return;
        }
        world.setBlockState(pos, state.withProperty(LOCKED, locked), 3);
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileAccessTerminal();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileAccessTerminal();
    }

    static TileAccessTerminal getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileAccessTerminal ? (TileAccessTerminal) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (playerIn.isSneaking() && !worldIn.isRemote) {
            // 潜行右键 = 不用开界面，直接翻转区域封锁（紧急封锁快一步）
            TileAccessTerminal tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.toggleLocked(playerIn);
            }
            return true;
        }
        if (worldIn.isRemote) {
            RsAudio.proxy.openAccessTerminalGui(pos);
        }
        return true;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        // ★ 刻意<b>不</b>在拆终端时解除封锁：区域规定不因为"发号施令的机器被拆了"就失效。
        //   要放行就明确放行一次 —— 否则挖掉终端就能解开封锁，封锁就太容易绕过了。
        //   （和防核门拆门板时保留门闩是同一个道理）
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.access.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.access.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.access.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.access.4"));
    }
}

package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.panel.PanelCore;
import com.rsaudio.tile.TileRemotePanel;
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
 * <b>远程控制面板</b>：正面排着 6 个按钮，右键直接按，不是打开菜单。
 *
 * <h3>两种用途，一个方块</h3>
 * <ul>
 *   <li>{@link PanelCore#MODE_DMR}：控制就近一台 DMR 控制柜（点火 / 停机 / 维护 / 装料 /
 *       修激光 / 子系统）。等效于跑过去开控制柜的界面，但人可以待在控制室里。</li>
 *   <li>{@link PanelCore#MODE_GATE}：控制同频道就近一扇防核门（开 / 关 / 封锁 / 解除 /
 *       联锁 / 重型）。塔塔鲁斯那种重型大门就靠它从远处逐行升降。</li>
 * </ul>
 * 模式与频道在<b>潜行右键打开的设置界面</b>里改（和门禁终端、气闸控制器一个路子）。
 *
 * <h3>★★ 普通右键 = 按那一下，潜行右键 = 开界面</h3>
 * 和气闸控制器同向、和门禁终端反向。理由是这里的常用动作显然是"按按钮"：
 * 面板装在墙上，人在紧急情况下伸手就是一下，多一步潜行是多余的。
 *
 * <h3>★ 命中判定为什么放在这里，而"这次按下算哪个动作"放在方块实体里</h3>
 * 这一层只干<b>几何</b>：把原版递进来的 {@code hitX/hitY/hitZ} 加上朝向，换算成归一化面坐标
 * {@code (u,v)}，然后交给方块实体。方块实体那边才知道"现在是哪种模式、6 个格子各是什么动作"。
 * 分开的好处是几何和逻辑各自只有一处：
 * <ul>
 *   <li>几何 → {@link PanelCore#faceU} / {@link PanelCore#faceV} / {@link PanelCore#hitButton}，
 *       和渲染器<b>共用同一个 {@code PanelCore}</b>，不存在"看到的按钮 ≠ 按到的按钮"；</li>
 *   <li>逻辑 → {@link TileRemotePanel#pressFace}，动作表和模式/频道一起，只此一份。</li>
 * </ul>
 *
 * <h3>为什么背面不算</h3>
 * 按钮只画在 {@link #FACING} 那一面，所以只有从那一面点才作数：点到背面/侧面时
 * 按的是"一块铁板的背面"，那里没有按钮。这时候返回 false 让原版继续走别的流程
 * （比如手上拿着方块时正常放置），比"从背面把反应堆停了"合理得多。
 */
public class BlockRemotePanel extends Block implements ITileEntityProvider {

    /** 正面朝向（有按钮的那一面） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);

    public BlockRemotePanel() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "remote_panel");
        setUnlocalizedName(RsAudio.MODID + ".remote_panel");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(3.5F);
        setResistance(30.0F);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState().withProperty(FACING, EnumFacing.NORTH));
    }

    // ------------------------------------------------------------------ 状态

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

    /** 面板正面对着放下它的玩家 —— 按钮要朝着人 */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                           float hitZ, int meta, EntityLivingBase placer) {
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    public static IBlockState stateFor(EnumFacing facing) {
        return com.rsaudio.ModBlocks.REMOTE_PANEL.getDefaultState()
                .withProperty(FACING, facing == null ? EnumFacing.NORTH : facing);
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
        return new TileRemotePanel();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileRemotePanel();
    }

    public static TileRemotePanel getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileRemotePanel ? (TileRemotePanel) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        // ★ 这里刻意<b>不</b>检查"手上是不是木斧"（虽然监控控制台那样做过）。
        //   木斧的非潜行右键会被 {@code WandHandler} 提前吃掉（用来选结构角），
        //   根本轮不到这里；而潜行那一路必须归面板自己 —— 见下面。
        //   要是像监控控制台那样先 return false 把木斧让出去，
        //   潜行右键就会被 {@code WandHandler} 当成"在结构方块上开界面"，
        //   结果是玩家拿着木斧<b>永远打不开面板的设置界面</b>（只能先把斧子收起来，很难查）。
        if (playerIn.isSneaking()) {
            // 潜行右键 = 设置界面（模式 / 频道）
            if (worldIn.isRemote) {
                RsAudio.proxy.openRemotePanelGui(pos);
            }
            return true;
        }
        EnumFacing front = state.getValue(FACING);
        if (facing != front) {
            // 背面 / 侧面没有按钮 —— 让原版接着走它自己的流程
            return false;
        }
        // ★ 这三个已经是"方块内 0..1"，不要再减 pos（见 PanelCore#faceU 的注释）
        double ux = PanelCore.faceRightX(front.getFrontOffsetX(), front.getFrontOffsetZ());
        double uz = PanelCore.faceRightZ(front.getFrontOffsetX(), front.getFrontOffsetZ());
        double u = PanelCore.faceU(hitX, hitZ, ux, uz);
        double v = PanelCore.faceV(hitY);

        TileRemotePanel tile = getTile(worldIn, pos);
        if (tile == null) {
            return true;
        }
        if (!worldIn.isRemote) {
            // 只有服务端真的动手：那边才知道目标是哪台机器、它现在什么状态
            tile.pressFace(playerIn, u, v);
        }
        return true;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.panel.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.panel.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.panel.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.panel.4"));
    }
}

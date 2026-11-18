package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileBlastDoor;
import com.rsaudio.util.WandHandler;
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
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

import java.util.List;

/**
 * 防核门的<b>控制器</b>（同时是门的左下角那一格）。
 *
 * <h3>用法</h3>
 * <ol>
 *     <li>放下方块。</li>
 *     <li>手持木斧<b>潜行 + 右键</b>打开设置界面：宽/高/朝向/收进方向/反向信号。</li>
 *     <li>点「建造」—— 控制器右侧和上方会生成整片门板（{@link BlockBlastDoorPanel}）。</li>
 *     <li><b>手持木斧右键 = 开 / 关门</b>（门板也算门面，见下）；红石信号同样能开门（可反向），
 *         关门时如果门里有实体就先等一等，不夹人。</li>
 * </ol>
 *
 * <h3>★★ 本地开关门只能用木斧（2026-09-25 用户要求）</h3>
 * 以前是"空手右键 = 手动开关"。现在改成<b>必须手持木斧</b>：
 * 木斧右键 = 开 / 关，木斧潜行右键 = 设置界面。空手右键不再动门，只提示一句。
 * <p>
 * ★ 拦截点刻意只有<b>一个</b>：{@code WandHandler.isWand(player)}（"手里是木斧吗"的唯一判据）。
 * 红石、气闸控制器、远程面板、设置界面都是合法入口，不归它管 ——
 * 尤其"远程控制面板"那块墙上的按钮，就是要能远程开门，别顺手也加上木斧要求。
 *
 * <h3>抗爆</h3>
 * 控制器和门板的爆炸抗性都是 {@code 6000}（黑曜石是 1200、基岩是不可破坏）——
 * 大到足以扛住核爆，但仍然可以被正常挖掉/拆掉，不会留下拆不掉的残骸。
 * <p>
 * 门板没有对应的物品：它只能由控制器生成，拆掉任意一格门板会连带拆掉整扇门，
 * 所以不会留下"孤儿门板"。
 */
public class BlockBlastDoor extends Block implements ITileEntityProvider {

    /** 门面朝向（= 门的正面/外侧法线） */
    public static final PropertyEnum<EnumFacing> FACING =
            PropertyEnum.create("facing", EnumFacing.class, EnumFacing.Plane.HORIZONTAL);
    /** 是否已开门（开门时整扇门变成可通行，模型收进门框） */
    public static final PropertyBool OPEN = PropertyBool.create("open");
    /** 收进方向：true = 向上收（收到门楣里），false = 向下收（沉到地板里） */
    public static final PropertyBool RETRACT = PropertyBool.create("retract");

    public static final float BLAST_RESISTANCE = 6000.0F;

    public BlockBlastDoor() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "blast_door");
        setUnlocalizedName(RsAudio.MODID + ".blast_door");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(50.0F);
        setResistance(BLAST_RESISTANCE);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH)
                .withProperty(OPEN, Boolean.FALSE)
                .withProperty(RETRACT, Boolean.FALSE));
    }

    public static IBlockState stateFor(EnumFacing facing, boolean open, boolean retractUp) {
        return com.rsaudio.ModBlocks.BLAST_DOOR.getDefaultState()
                .withProperty(FACING, facing == null ? EnumFacing.NORTH : facing)
                .withProperty(OPEN, open)
                .withProperty(RETRACT, retractUp);
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING, OPEN, RETRACT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, EnumFacing.getHorizontal(meta & 3))
                .withProperty(OPEN, (meta & 4) != 0)
                .withProperty(RETRACT, (meta & 8) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = state.getValue(FACING).getHorizontalIndex();
        if (state.getValue(OPEN)) {
            meta |= 4;
        }
        if (state.getValue(RETRACT)) {
            meta |= 8;
        }
        return meta;
    }

    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        // 门的正面朝玩家
        return getDefaultState().withProperty(FACING, placer.getHorizontalFacing().getOpposite());
    }

    // ------------------------------------------------------------------ 碰撞（开门后可通行）

    /**
     * ★ 连续碰撞：门体滑到哪儿，就只挡到哪儿。
     * <p>
     * 以前是"整格开 / 整格关"—— 一行要么挡死要么凭空消失，于是看着有动画、
     * 能不能过去却还是<b>瞬间翻</b>的。现在按门体的真实位置求交，返回一个<b>部分高度</b>的盒子。
     * 控制器这一格就是门的第 0 行（门永远从控制器往上长），所以 {@code dy = 0}。
     *
     * <h3>★★ 这个盒子必须是<b>世界坐标</b>，不是方块局部坐标</h3>
     * 原版 {@code Block#getCollisionBoundingBox} 的约定是
     * {@code getBoundingBox(...).offset(pos)} —— 也就是<b>绝对坐标</b>。
     * 上一版这里直接返回了 {@code 0..1} 的局部盒子，后果是整扇门的碰撞盒全部落在
     * <b>世界原点</b>那一格：门看着挡在面前、实际能穿过去，站在远离原点的地方
     * 又会莫名其妙被"空气墙"卡住 —— 玩家报的"碰撞箱是乱的"就是这一条。
     * 所以这里必须加上 {@code pos} 的偏移（连兜底的 {@code FULL_BLOCK_AABB} 也要）。
     */
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        if (worldIn instanceof World) {
            TileBlastDoor te = getTile((World) worldIn, pos);
            if (te != null && te.isBuilt()) {
                float[] r = TileBlastDoor.coveredRange(0, te.getHeight(), te.getSlide(), te.isRetractUp());
                return r == null ? NULL_AABB
                        : new AxisAlignedBB(pos.getX(), pos.getY() + r[0], pos.getZ(),
                                            pos.getX() + 1, pos.getY() + r[1], pos.getZ() + 1);
            }
        }
        // 兜底：拿不到方块实体（没建好 / 别的线程）时退回原来的整格判定
        return state.getValue(OPEN) ? NULL_AABB : FULL_BLOCK_AABB.offset(pos);
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return !state.getValue(OPEN);
    }

    /**
     * ★★★ 同 {@link BlockBlastDoorPanel#causesSuffocation} —— 控制器这一格也必须关掉窒息。
     * <p>
     * 原因一模一样：{@code Block.causesSuffocation()} 的默认实现问的是
     * <b>{@code getDefaultState().isFullCube()}</b>，而控制器的默认状态是
     * {@code open=false}（满立方体），于是恒定返回 true。
     * <p>
     * 控制器的默认朝向是门宽方向的<b>第 0 格</b>，也就是门框靠边的第一列 ——
     * 生物从这个位置穿门是很常见的（尤其是 1 格宽的门，整扇门就这一格）。
     * 不关掉的话，那一格单独在那夹人。
     */
    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    /**
     * ★ 门体改由 {@code BlastDoorRenderer}（TESR）<b>连续平移</b>地画。
     * <p>
     * 方块模型只能"一帧换一张贴图"，那样根本没有开合可言 —— 用户要的就是门真的动起来。
     * 所以这里必须让原版别再画它：否则 TESR 画一份、方块模型再画一份，会看到重影。
     * <p>
     * 碰撞 <b>不</b>受影响，仍由 {@code OPEN} 决定（见 {@link #getCollisionBoundingBox}）。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    // ------------------------------------------------------------------ 方块实体

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return true;
    }

    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        return new TileBlastDoor();
    }

    @Override
    public TileEntity createNewTileEntity(World worldIn, int meta) {
        return new TileBlastDoor();
    }

    public static TileBlastDoor getTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileBlastDoor ? (TileBlastDoor) te : null;
    }

    // ------------------------------------------------------------------ 交互

    /**
     * 手持木斧右键 = 开 / 关门；手持木斧潜行右键 = 设置界面。
     *
     * <h3>★★ 为什么"潜行开界面"也搬到了这里</h3>
     * 原来潜行右键是 {@code WandHandler}（结构魔杖）替门开界面的。但魔杖的<b>非潜行</b>
     * 右键是"选第二个角"，而门现在要的就是"木斧右键 = 开关门" —— 两条规则撞在同一格上，
     * 结果只能是"拿着木斧永远开不了门"。所以门这一整块交互（含门板，见
     * {@link BlockBlastDoorPanel#onBlockActivated}）统一定在这里，魔杖那边对门不再插手。
     * <p>
     * ★★★ <b>但"搬进来"必须配一条放行</b>：潜行 + 手持木斧时，原版
     * {@code PlayerControllerMP.processRightClickBlock} 根本不会调用方块的
     * {@code onBlockActivated}（木斧 {@code doesSneakBypassUse} = false），
     * 所以 {@code WandHandler.onRightClick} 在潜行时补了一句
     * {@code event.setUseBlock(ALLOW)}。<b>这两处是一对，缺一不可</b> ——
     * 少了那条放行，木斧潜行右键就是"什么都没发生"（2026-09-25 现场）。
     * <p>
     * ★ 门板和控制器共用同一个说法（{@link #notifyNeedsAxe}）：门面看着是一整扇，
     * 玩家不可能知道哪一格是控制器，两边的提示文案必须一模一样。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (WandHandler.isWand(playerIn)) {
            if (playerIn.isSneaking()) {
                // 潜行 = 设置界面（界面只有客户端有，服务端这份调用是空实现）
                // ★★★ 这个分支能被执行，靠的是 WandHandler.onRightClick 里那条
                //   {@code setUseBlock(ALLOW)} —— 潜行 + 手持木斧时方块本来收不到
                //   onBlockActivated（木斧的 doesSneakBypassUse = false）。
                //   别把那行放行删掉，也别把这段逻辑搬回事件层：一删，症状就是
                //   "拿着木斧潜行右键门，什么都没发生"。
                if (worldIn.isRemote) {
                    RsAudio.proxy.openBlastDoorGui(pos);
                }
                return true;
            }
            if (!worldIn.isRemote) {
                TileBlastDoor tile = getTile(worldIn, pos);
                if (tile != null) {
                    tile.toggleDoor(playerIn);
                }
            }
            return true;
        }
        if (playerIn.getHeldItemMainhand().isEmpty()) {
            // 空手右键过去是"手动开关门"，现在这个动作没了 —— 必须自报成因，
            // 否则玩家只会觉得"门坏了"（这是他最可能做的第一个动作）
            if (!worldIn.isRemote) {
                notifyNeedsAxe(playerIn);
            }
        }
        // ★ 一律返回 false：不拦住手上的物品。拿方块右键贴着门放一块、拿桶右键接水
        //   这些正常建造动作都还要能用（空手那条也只提示、不吞掉事件）。
        return false;
    }

    /**
     * 「本地开关门只能用木斧」这条规则给玩家看的说法。
     * <p>
     * ★ 门板和控制器共用它，文案只有一处；语言键见 {@code chat.rsaudio.door.need_axe}。
     */
    public static void notifyNeedsAxe(EntityPlayer player) {
        if (player != null) {
            player.sendStatusMessage(
                    new net.minecraft.util.text.TextComponentTranslation("chat.rsaudio.door.need_axe"), true);
        }
    }

    // ------------------------------------------------------------------ 被拆掉

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            TileBlastDoor tile = getTile(worldIn, pos);
            if (tile != null) {
                tile.removeDoor(false, "控制器方块被拆（玩家或爆炸）");
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    /**
     * 找"覆盖了某个位置"的控制器。
     * <p>
     * 门板不存自己的主人（那要给每块门板都塞一个方块实体，太浪费），
     * 所以拆门板时就在附近有限范围里找一次。范围就是门的最大尺寸（7×7），
     * 最多也就几千次检查，是拆方块这种低频操作能接受的代价。
     */
    public static BlockPos findController(World world, BlockPos from) {
        for (int dy = 0; dy <= TileBlastDoor.MAX_SIZE; dy++) {
            for (int dx = -TileBlastDoor.MAX_SIZE; dx <= TileBlastDoor.MAX_SIZE; dx++) {
                for (int dz = -TileBlastDoor.MAX_SIZE; dz <= TileBlastDoor.MAX_SIZE; dz++) {
                    BlockPos candidate = from.add(dx, -dy, dz);
                    if (!world.isBlockLoaded(candidate)) {
                        continue;
                    }
                    if (!(world.getBlockState(candidate).getBlock() instanceof BlockBlastDoor)) {
                        continue;
                    }
                    TileBlastDoor tile = getTile(world, candidate);
                    if (tile != null && tile.contains(from)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.3"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.4"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.5"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.6"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.door.7"));
    }
}

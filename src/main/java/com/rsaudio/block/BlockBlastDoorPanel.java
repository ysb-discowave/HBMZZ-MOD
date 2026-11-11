package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.tile.TileBlastDoor;
import com.rsaudio.util.WandHandler;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

/**
 * 防核门的<b>门板</b>：由 {@link BlockBlastDoor} 控制器生成，玩家拿不到它的物品。
 * <p>
 * 没有方块实体（一扇门最多 49 格，每格都塞一个方块实体太浪费），
 * 所以"我属于哪扇门"这件事由控制器负责反查（见 {@link BlockBlastDoor#findController}）。
 * <p>
 * 拆掉任意一格门板 = 拆掉整扇门，避免留下拆不掉的孤儿门板。
 * <p>
 * ★★★ 但"门板消失"有<b>两条完全不同的来源</b>，必须分清：
 * <ol>
 *   <li><b>玩家/爆炸把门板拆了</b> → 连带拆掉整扇门（本类的 {@link #breakBlock}）；</li>
 *   <li><b>门自己开门时把这一格收成空气</b>（{@code TileBlastDoor#applyCells()}）→
 *       <b>什么都不能做</b>，否则门一开就自杀。</li>
 * </ol>
 * 1.12.2 里这两件事走的是同一个 {@code breakBlock} 回调，区别只能靠
 * {@code TileBlastDoor#cellIsVacated()} 现场重探。详见那里的长注释（含 2026-09-25 的日志现场）。
 * <p>
 * 门板的右键也是"木斧开关门"（见 {@link #onBlockActivated}）—— 门面是一整扇，
 * 玩家分不出哪一格是控制器。
 */
public class BlockBlastDoorPanel extends Block {

    /**
     * 是否已开门（开门后不挡路，模型收进门框）。
     * <p>
     * ★★ <b>现在它只是个历史遗留的兜底字段，恒为 {@code false}。</b>
     * "门板在不在"已经改由<b>方块本身的有无</b>表示（见
     * {@code TileBlastDoor#applyCells()}：门开到哪儿，那一格就真的变成空气）。
     * 之所以还留着不删，是因为它参与了方块状态 / blockstate JSON / 元数据编码的契约，
     * 拆掉要连着资源一起改，而它现在已经不参与任何判定 —
     * 唯一还可能被读到的地方是 {@link #getCollisionBoundingBox} 里"查不到控制器"时的兜底分支。
     * <p>
     * 换句话说：<b>不要再往这个属性上挂新逻辑</b>。挂上去也不会生效，
     * 因为门板存在 ⟺ 这一格此刻是关着的，不存在 ⟺ 开着。
     */
    public static final PropertyBool OPEN = PropertyBool.create("open");
    /** 收进方向：true = 向上收，false = 向下收 */
    public static final PropertyBool RETRACT = PropertyBool.create("retract");

    public BlockBlastDoorPanel() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "blast_door_panel");
        setUnlocalizedName(RsAudio.MODID + ".blast_door_panel");
        // ★ 不加进创造模式物品栏：门板只能由控制器生成
        setHardness(50.0F);
        setResistance(BlockBlastDoor.BLAST_RESISTANCE);
        setSoundType(SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(OPEN, Boolean.FALSE)
                .withProperty(RETRACT, Boolean.FALSE));
    }

    public static IBlockState stateFor(boolean open, boolean retractUp) {
        return com.rsaudio.ModBlocks.BLAST_DOOR_PANEL.getDefaultState()
                .withProperty(OPEN, open)
                .withProperty(RETRACT, retractUp);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, OPEN, RETRACT);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(OPEN, (meta & 1) != 0)
                .withProperty(RETRACT, (meta & 2) != 0);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        int meta = 0;
        if (state.getValue(OPEN)) {
            meta |= 1;
        }
        if (state.getValue(RETRACT)) {
            meta |= 2;
        }
        return meta;
    }

    // ------------------------------------------------------------------ 碰撞

    /**
     * ★ 和控制器 {@link BlockBlastDoor} 一样走<b>连续碰撞</b>：按门体真实位置求交，
     * 返回部分高度的盒子，而不是"整格开 / 整格关"。
     * <p>
     * 门板自己没有方块实体，所以先查归属表拿到控制器（{@code TileBlastDoor#ownerOf}），
     * 再用"我比控制器高几格"算出自己是第几行。查不到就退回原来的整格判定（兜底，不会更差）。
     *
     * <h3>★★ 和控制器一样：返回的是<b>世界坐标</b>，不是 0..1 的局部坐标</h3>
     * 原版约定是 {@code getBoundingBox(...).offset(pos)}。写成局部坐标的话，
     * 整扇门的碰撞盒会全部堆在世界原点那一格（见 {@link BlockBlastDoor#getCollisionBoundingBox}）。
     */
    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState state, IBlockAccess worldIn, BlockPos pos) {
        if (worldIn instanceof World) {
            World world = (World) worldIn;
            BlockPos controller = TileBlastDoor.ownerOf(world, pos);
            if (controller != null) {
                TileBlastDoor te = BlockBlastDoor.getTile(world, controller);
                if (te != null && te.isBuilt()) {
                    int dy = pos.getY() - controller.getY();
                    float[] r = TileBlastDoor.coveredRange(dy, te.getHeight(), te.getSlide(), te.isRetractUp());
                    return r == null ? NULL_AABB
                            : new AxisAlignedBB(pos.getX(), pos.getY() + r[0], pos.getZ(),
                                                pos.getX() + 1, pos.getY() + r[1], pos.getZ() + 1);
                }
            }
        }
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
     * ★★★ <b>"门开着也会把生物夹死"的根因就是这里。</b>
     *
     * <h3>窒息判定根本不看 {@code OPEN}，也不看碰撞盒</h3>
     * 1.12.2 的链条是（已用 {@code api_probe.py -c} 反汇编逐环确认）：
     * <pre>
     *   EntityLivingBase.isEntityInsideOpaqueBlock()   ← 每 tick 调，近了就 attackEntityFrom(inWall)
     *     → Entity.isEntityInsideOpaqueBlock()         ← 采样点是 <b>眼睛高度那一格</b>（posY + getEyeHeight ± 0.05）
     *        → IBlockState.causesSuffocation()          ← 注意：问的是"方块",不是"碰撞盒"
     *           → BlockStateBase 实现 → Block.causesSuffocation(state)
     * </pre>
     * 而 {@link Block#causesSuffocation} 的默认实现（同样是反汇编读出来的）是：
     * <pre>
     *   return this.blockMaterial.blocksMovement()
     *       &amp;&amp; this.<b>getDefaultState()</b>.isFullCube();   // ★ 不是传进来的 state！
     * </pre>
     * 它问的是 <b>{@code getDefaultState()}</b>。门板的默认状态永远是
     * {@code open=false} —— 也就是"满立方体"<b>恒为真</b>。
     * <p>
     * 于是：门板不管 {@code OPEN} 是 true 还是 false、门开着还是关着、
     * 连续碰撞算得多准，<b>它都恒定窒息</b>。玩家/生物走进敞开的门框就掉血。
     * 这就是"门开着也会被夹死"，而且它和碰撞完全无关 ——
     * 所以之前所有往碰撞盒上做的修补都不可能修掉它。
     *
     * <h3>修法</h3>
     * 直接把这个谓词关掉：门就是门，不该因为"材质是金属方块"而窒息。
     * 另一条并行的修法在 {@code TileBlastDoor#applyCells()} —— 开门时门板会<b>真的变成空气</b>，
     * 开到底时门框里一格方块都没有。两条互为兜底：
     * 万一哪天门板还在（存档中途、区块刚加载），也不会夹死人。
     */
    @Override
    public boolean causesSuffocation(IBlockState state) {
        return false;
    }

    /**
     * ★ 和控制器 {@link BlockBlastDoor} 一样：门板也不让原版画。
     * 整扇门（含所有门板）由 {@code BlastDoorRenderer} 一次画完，这样门才能<b>连续平移</b>，
     * 而不是"每一格各自瞬间换成开着的贴图"。碰撞照旧由 {@code OPEN} 决定。
     */
    @Override
    public EnumBlockRenderType getRenderType(IBlockState state) {
        return EnumBlockRenderType.INVISIBLE;
    }

    /** 门板不可被普通方式获取，也就不用掉落物（没有对应的物品，原版会自己跳过掉落） */
    @Override
    public boolean canSilkHarvest(World world, BlockPos pos, IBlockState state, net.minecraft.entity.player.EntityPlayer player) {
        return false;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            // 拆一格门板 = 拆整扇门（含控制器），不然会留下拆不掉的残骸
            BlockPos controller = BlockBlastDoor.findController(worldIn, pos);
            if (controller != null) {
                TileBlastDoor tile = BlockBlastDoor.getTile(worldIn, controller);
                if (tile != null) {
                    // ★★★ 先问一句"这一格是门自己收掉的吗" —— 这是"门一开就把自己拆掉"
                    //   那个 bug 的唯一修复点。理由见 TileBlastDoor#cellIsVacated：
                    //   门是靠"把门板写成空气"来开的，而"变成空气"和"被玩家挖掉"
                    //   在 1.12.2 里走的是同一个 breakBlock 回调，不区分就会自杀。
                    //   （2026-09-25 现场：气闸每放行一次，频道里的门就少一扇。）
                    if (!tile.cellIsVacated(pos)) {
                        // 从上往下清，注意别把当前这一格又清一遍（已经要没了）
                        tile.removeDoor(true, "门板被拆：" + pos + " → 连带拆掉整扇门");
                    }
                }
            }
        }
        super.breakBlock(worldIn, pos, state);
    }

    /**
     * 门板也吃"木斧右键 = 开 / 关门、木斧潜行右键 = 设置界面"（和控制器一模一样）。
     *
     * <h3>★★ 为什么门板必须自己也会这一套</h3>
     * 门面看着是<b>一整扇</b>：控制器只有一格（还画在最边上），其余全是门板 ——
     * 而整扇门是 {@code BlastDoorRenderer} 一次性画出来的，玩家<b>根本分不出</b>
     * 哪一格是控制器。只让控制器响应右键的话，玩家对着门中间点半天不会开门，
     * 只会得到"这扇门坏了"的结论。
     *
     * <h3>★ 找控制器在客户端也必须成立</h3>
     * 这里用 {@link BlockBlastDoor#findController}（现场扫）而<b>不是</b>
     * {@code TileBlastDoor.ownerOf}（归属表）：归属表只在服务端登记
     * （{@code TileBlastDoor.update()} 客户端直接 return，{@code registerCells()} 也就不会跑），
     * 客户端查它恒为 null —— 拿它去开界面就是"潜行右键没反应"这种静默故障。
     */
    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (WandHandler.isWand(playerIn)) {
            BlockPos controller = BlockBlastDoor.findController(worldIn, pos);
            if (controller == null) {
                return false;                 // 找不到主人（门正在拆？）—— 当普通方块处理
            }
            if (playerIn.isSneaking()) {
                // ★★★ 与控制器同一条原因：潜行时"方块收得到 onBlockActivated"
                //   是靠 WandHandler.onRightClick 那条 setUseBlock(ALLOW) 换来的
                if (worldIn.isRemote) {
                    RsAudio.proxy.openBlastDoorGui(controller);
                }
                return true;
            }
            if (!worldIn.isRemote) {
                TileBlastDoor tile = BlockBlastDoor.getTile(worldIn, controller);
                if (tile != null) {
                    tile.toggleDoor(playerIn);
                }
            }
            return true;
        }
        if (playerIn.getHeldItemMainhand().isEmpty() && !worldIn.isRemote) {
            BlockBlastDoor.notifyNeedsAxe(playerIn);
        }
        return false;
    }
}

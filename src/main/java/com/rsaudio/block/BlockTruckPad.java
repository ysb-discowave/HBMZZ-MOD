package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.entity.EntityFreightTruck;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * 货运卡车车位：一块"停机坪"方块，<b>潜行 + 右键</b>在它的顶面放出一台
 * {@link EntityFreightTruck}（和"潜行右键轨道放列车"是同一套玩法）。
 *
 * <h3>★ FACING = 放出来的车朝哪边</h3>
 * 只有水平四向（车要在地面上开，吸顶没有意义），
 * 放置时取 {@code placer.getHorizontalFacing().getOpposite()} —— 和起重机 / 巨大标志牌
 * 同一套约定：对着它摆的人，一眼看到车会往自己这边来。
 * 顶面贴图里画了一个箭头（指向模型的 north），四个朝向变体靠 y 旋转把它转到对应方向。
 *
 * <h3>★ 放车前的两道检查（都带聊天回执，不静默）</h3>
 * <ol>
 *   <li><b>查重</b>：4 格内已经有一台卡车就拒绝 —— 和 {@code BlockTrainRail}
 *       放车前的查重同一条纪律。没有它，玩家连点两下就得到两台叠在一起的车。</li>
 *   <li><b>净空</b>：顶上那一格必须不是实心方块（车身高 1.55 格，要占头顶那格）。
 *       顶着一堵墙放车的结果是"车生成在墙里、被自己的碰撞箱卡住"。</li>
 * </ol>
 *
 * <h3>★ 没有方块实体</h3>
 * 车位是纯几何方块（贴图 + FACING 状态），没有任何逐帧要画的东西，
 * 所以<b>不实现</b> {@code ITileEntityProvider} —— 别照起重机那条链抄。
 * 它需要"潜行右键"语义，所以必须进 {@code WandHandler.isOurMachine}
 * （漏了的后果是手持木斧潜行右键被"选第二个角"吃掉，放不出车）。
 */
public class BlockTruckPad extends Block {

    /** 放出来的车朝向（水平四向） */
    public static final PropertyEnum<EnumFacing> FACING = PropertyEnum.create(
            "facing", EnumFacing.class,
            EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST);

    /** 放车前的查重半径（格）。和 BlockTrainRail 放车的查重是同一把尺子 */
    public static final int SPAWN_CHECK_RADIUS = 4;

    public BlockTruckPad() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "truck_pad");
        setUnlocalizedName(RsAudio.MODID + ".truck_pad");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(2.0F);
        setResistance(6.0F);
        setSoundType(net.minecraft.block.SoundType.METAL);
        setDefaultState(this.blockState.getBaseState()
                .withProperty(FACING, EnumFacing.NORTH));
    }

    // ------------------------------------------------------------------ 状态

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, FACING);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState()
                .withProperty(FACING, horizontal(EnumFacing.getFront(meta & 7)));
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(FACING).ordinal() & 7;
    }

    /** 竖直朝向一律折回 NORTH：本方块只支持水平四向 */
    private static EnumFacing horizontal(EnumFacing f) {
        return f == null || !f.getAxis().isHorizontal() ? EnumFacing.NORTH : f;
    }

    /** 放置时车<b>朝放置者</b>（和起重机 / 标志牌同一套语义） */
    @Override
    public IBlockState getStateForPlacement(World worldIn, BlockPos pos, EnumFacing facing, float hitX, float hitY,
                                            float hitZ, int meta, EntityLivingBase placer) {
        EnumFacing face = placer == null ? EnumFacing.SOUTH
                : horizontal(placer.getHorizontalFacing().getOpposite());
        return getDefaultState().withProperty(FACING, face);
    }

    // ------------------------------------------------------------------ 潜行右键放车

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn,
                                    EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote) {
            // 客户端只负责"这件事算我处理了"（TruckPlaceHandler 把 useBlock 放成了 ALLOW），
            // 真正放车在服务端那一侧
            return true;
        }
        if (!playerIn.isSneaking()) {
            // 非潜行右键没有语义（潜行才是"放车"）—— 返回 false 让引擎照常走
            return false;
        }
        // ★ 重新读一次状态（和 BlockCrane 同一条纪律：传进来的 state 可能差一步）
        IBlockState current = worldIn.getBlockState(pos);
        if (!(current.getBlock() instanceof BlockTruckPad)) {
            return false;
        }

        // ① 查重：附近已经有一台车就不放
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - SPAWN_CHECK_RADIUS, pos.getY() - SPAWN_CHECK_RADIUS,
                pos.getZ() - SPAWN_CHECK_RADIUS,
                pos.getX() + 1.0D + SPAWN_CHECK_RADIUS, pos.getY() + 1.0D + SPAWN_CHECK_RADIUS,
                pos.getZ() + 1.0D + SPAWN_CHECK_RADIUS);
        for (Entity e : worldIn.getEntitiesWithinAABB(EntityFreightTruck.class, box)) {
            if (e instanceof EntityFreightTruck) {
                playerIn.sendStatusMessage(new TextComponentString(
                        I18n.translateToLocal("chat.rsaudio.truck.duplicate")), true);
                return true;
            }
        }
        // ② 净空：车顶那一格不能是实心方块
        BlockPos above = pos.up();
        if (worldIn.isBlockNormalCube(above, false)) {
            playerIn.sendStatusMessage(new TextComponentString(
                    I18n.translateToLocal("chat.rsaudio.truck.no_space")), true);
            return true;
        }

        EntityFreightTruck truck = new EntityFreightTruck(worldIn);
        truck.setPosition(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D);
        truck.getCore().setDirection(dirOf(current.getValue(FACING)));
        worldIn.spawnEntity(truck);
        // ★ 本环境 MCP 快照里活塞音叫 BLOCK_PISTON_EXTEND（不是 1.12 常见的 _OUT）
        worldIn.playSound(null, pos, SoundEvents.BLOCK_PISTON_EXTEND,
                SoundCategory.BLOCKS, 0.5F, 0.9F);
        playerIn.sendStatusMessage(new TextComponentString(
                I18n.translateToLocal("chat.rsaudio.truck.spawn")), true);
        RsAudio.log("[卡车] %s 在 %d,%d,%d 的卡位上放出一台货运卡车（朝 %s）",
                playerIn.getName(), Integer.valueOf(pos.getX()), Integer.valueOf(pos.getY()),
                Integer.valueOf(pos.getZ()), facingName(current.getValue(FACING)));
        return true;
    }

    /** blockstate 朝向 → 内核方向码（0=N 1=E 2=S 3=W）。★ 不是 ordinal()：那是 2,3,4,5 */
    private static int dirOf(EnumFacing f) {
        if (f == EnumFacing.NORTH) {
            return 0;
        }
        if (f == EnumFacing.EAST) {
            return 1;
        }
        if (f == EnumFacing.SOUTH) {
            return 2;
        }
        return 3;   // WEST
    }

    private static String facingName(EnumFacing f) {
        if (f == EnumFacing.NORTH) {
            return "北";
        }
        if (f == EnumFacing.EAST) {
            return "东";
        }
        if (f == EnumFacing.SOUTH) {
            return "南";
        }
        return "西";
    }

    // ------------------------------------------------------------------ 提示

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.truck_pad.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.truck_pad.2"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.truck_pad.3"));
    }
}

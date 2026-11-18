package com.rsaudio.block;

import com.rsaudio.RsAudio;
import com.rsaudio.RsAudioTab;
import com.rsaudio.tile.TileElevatorController;
import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.translation.I18n;
import net.minecraft.world.World;

import java.util.List;

/**
 * 电梯轿厢格：平台 + 四角立柱。
 * <p>
 * ★★★ <b>这个方块没有 ItemBlock（玩家拿不到）。</b>它只能由
 * {@link TileElevatorController} 生成与移除 —— 和防爆门门板、塔塔鲁斯舱盖格同一个约定：
 * 让玩家能手动摆轿厢，等于让他能把轿厢摆到井道外面去，然后就有一堆"为什么我的电梯不见了"
 * 要查。{@code verify_jar.py} 的 {@code FORBIDDEN_ITEM_MODELS} 里盯着这一条。
 *
 * <h3>★★★ 为什么需要 {@link #breakBlock} 这一段（本项目踩过两次的坑）</h3>
 * 1.12.2 里「方块被写成空气」和「玩家把它挖了」走的是<b>同一条回调</b>
 * （{@code Chunk.setBlockState}：{@code if (block1 != block) block1.breakBlock(...)}）。
 * <p>
 * 而轿厢格天生就是"会自己变成空气"的方块 —— 轿厢每上一格，脚下那一层的格子就被收回。
 * 如果 {@code breakBlock} 里写"轿厢没了就把整台电梯停掉"，那么这个动作会被
 * <b>电梯自己</b>触发：电梯一动就自己把自己停掉，全程静默。
 * <p>
 * ⇒ 规矩是：<b>消失之前先问一句"这一格本来就该消失吗"</b>，而且必须和写世界那一侧
 * 用<b>同一把尺子</b>（这里是 {@code TileElevatorController#ownsCell(BlockPos)}）。
 * 不用"正在搬迁"这种旗标 —— 旗标漏复原一处就是反向故障（玩家真拆的时候反而不报）。
 */
public class BlockElevatorCar extends Block {

    public BlockElevatorCar() {
        super(Material.IRON);
        setRegistryName(RsAudio.MODID, "elevator_car");
        setUnlocalizedName(RsAudio.MODID + ".elevator_car");
        setCreativeTab(RsAudioTab.INSTANCE);
        setHardness(5.0F);
        setResistance(40.0F);
        setSoundType(SoundType.METAL);
        // 轿厢是实心可站立的平台 —— 碰撞盒、isFullCube、isOpaqueCube 全部走原版默认。
        // ★ 这里刻意**不**像门那样关掉窒息：站在轿厢"里面"是不应该发生的事，
        //   真发生了说明轿厢和玩家重叠了，那时窒息反而是对的（会把人挤出来）。
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        if (!worldIn.isRemote) {
            TileElevatorController ctrl = findController(worldIn, pos);
            if (ctrl != null && ctrl.ownsCell(pos)) {
                // 这一格此刻真的属于轿厢 → 玩家（或者爆炸）把它拆了，不是电梯自己在收格子。
                ctrl.onCarBlockBroken(pos);
            }
            // 反之（ownsCell == false）说明是本控制器刚刚把格子收走造成的，什么都不做。
            // ★ 这里**不能**反过来写成"找不到控制器就当中性方块"—— 找得到但 ownsCell 为假，
            //   和"找不到控制器"是两件事，混在一起就会把搬迁也当成破坏。
        }
        super.breakBlock(worldIn, pos, state);
    }

    /**
     * 现场扫出"这一格属于哪台电梯"。
     * <p>
     * ★ 和防爆门同一条教训：<b>归属表只在服务端登记</b>（而且只在控制器那一格里），
     * 所以这里必须现场扫，不能查什么静态表 —— 客户端也要跑这段逻辑（它也会收到
     * 方块变化），查表恒为 null，表现是"客户端上轿厢被拆不触发任何处理"。
     * <p>
     * 扫描半径用控制器往上最多能扫的格数 —— 轿厢可能在高处，而它只认识自己那台控制器。
     */
    static TileElevatorController findController(World world, BlockPos pos) {
        // 先看这一格的紧邻（轿厢绝大多数时候就在控制器旁边几格内），再放大范围。
        int r = 6;
        for (int dy = -r; dy <= r; dy++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    BlockPos p = pos.add(dx, dy, dz);
                    TileEntity te = world.getTileEntity(p);
                    if (te instanceof TileElevatorController) {
                        return (TileElevatorController) te;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public void addInformation(ItemStack stack, World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_car.1"));
        tooltip.add(I18n.translateToLocal("tooltip.rsaudio.elevator_car.2"));
    }
}

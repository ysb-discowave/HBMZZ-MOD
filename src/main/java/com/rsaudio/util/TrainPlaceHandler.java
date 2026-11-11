package com.rsaudio.util;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockTrainRail;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 放车：<b>潜行 + 右键轨道</b>。
 *
 * <h3>为什么必须有这个类，而不是把逻辑全写在 {@link BlockTrainRail} 里</h3>
 * 1.12.2 的右键流程里有一道很容易踩空的门（客户端 {@code PlayerControllerMP} 与服务端
 * {@code PlayerInteractionManager} 各有一份，逻辑一致）：
 * <pre>
 *   if (玩家潜行 &amp;&amp; !物品.doesSneakBypassUse(...) &amp;&amp; useBlock != ALLOW) {
 *       直接跳过方块自己的 onBlockActivated
 *   }
 * </pre>
 * 而 {@code ItemStack.doesSneakBypassUse} 的实现是 {@code isEmpty() || item.doesSneakBypassUse(...)}，
 * {@code Item} 那一层默认返回 {@code false} —— <b>也就是说只有空手才为真</b>（原版里只有少数
 * "潜行也能用"的物品，比如刷子类工具，会覆写它）。
 * <p>
 * 后果：潜行右键轨道时，只要手里拿着任何东西，
 * {@link BlockTrainRail#onBlockActivated} <b>根本不会被调用</b>，引擎直接进
 * "放置手上方块 / 使用物品"那一段。最常见的场景恰恰最致命 ——
 * 玩家铺完最后一段轨道，<b>手上还握着轨道方块</b>，照 tooltip 说的潜行右键，
 * 得到的只是又铺了一格轨道，看上去就是"什么都放不出来"。
 * <p>
 * 这道门本身是为"玩家潜行时想往机器上叠方块"准备的，而"潜行 + 右键轨道"在我们的语义里
 * 只有一种解释：放车。所以这里显式放行，把"必须空手"这个隐形要求去掉。
 */
@Mod.EventBusSubscriber(modid = RsAudio.MODID)
public final class TrainPlaceHandler {

    private TrainPlaceHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player == null || !player.isSneaking()) {
            return;
        }
        if (event.getHand() != EnumHand.MAIN_HAND) {
            // 只看主手。事件对主/副手各有一份，两边都放行等于给一次右键开两条生成路径；
            // 虽然服务端那道 4 格查重能兜住，但"靠查重兜住"不该是设计的一部分。
            return;
        }
        Block block = player.world.getBlockState(event.getPos()).getBlock();
        if (!(block instanceof BlockTrainRail)) {
            return;
        }
        // ★ 这一行就是"列车放不出来"的开关。详见类注释。
        event.setUseBlock(Event.Result.ALLOW);
        // 只留一条"这道门确实开了"的痕迹，且只在服务端打（事件两端都会触发）。
        // 有了它，"放不出车"才能被区分成两种完全不同的故障：
        //   日志里<b>没有</b>这一行 = 事件都没到（handler 没注册上 / 不在主事件总线）；
        //   有这一行、但没有随后的"放车：" = 引擎仍然没调用 onBlockActivated。
        // 没有它，两种情况在日志里一模一样，都只是"什么都没有"。
        if (!player.world.isRemote) {
            RsAudio.log("[列车] 放车请求：%s 潜行右键轨道 @ %d,%d,%d",
                    player.getName(), Integer.valueOf(event.getPos().getX()),
                    Integer.valueOf(event.getPos().getY()), Integer.valueOf(event.getPos().getZ()));
        }
    }
}

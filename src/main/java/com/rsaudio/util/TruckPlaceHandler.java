package com.rsaudio.util;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockTruckPad;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 放卡车：<b>潜行 + 右键卡位方块</b>。
 *
 * <h3>为什么必须有这个类 —— 和 {@link TrainPlaceHandler} 是同一道门</h3>
 * 1.12.2 的右键流程里有一道很容易踩空的门（客户端 {@code PlayerControllerMP}
 * 与服务端 {@code PlayerInteractionManager} 各有一份，逻辑一致）：
 * <pre>
 *   if (玩家潜行 &amp;&amp; !物品.doesSneakBypassUse(...) &amp;&amp; useBlock != ALLOW) {
 *       直接跳过方块自己的 onBlockActivated
 *   }
 * </pre>
 * 而 {@code ItemStack.doesSneakBypassUse} 的实现是 {@code isEmpty() || ...} ——
 * <b>只有空手才为真</b>。
 * <p>
 * 后果和放列车时一模一样：玩家手上还握着卡位方块，按 tooltip 潜行右键，
 * 得到的只是又铺了一格卡位，"什么都放不出来"。
 * 所以这里对 {@link BlockTruckPad} 显式 {@code setUseBlock(Event.Result.ALLOW)}，
 * 把"必须空手"这个隐形要求去掉。
 * <p>
 * ★ 只放行主手（事件对主/副手各有一份，两边都放行 = 一次右键开两条生成路径；
 * 服务端那道 4 格查重能兜住，但"靠查重兜住"不该是设计的一部分）。
 */
@Mod.EventBusSubscriber(modid = RsAudio.MODID)
public final class TruckPlaceHandler {

    private TruckPlaceHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (player == null || !player.isSneaking()) {
            return;
        }
        if (event.getHand() != EnumHand.MAIN_HAND) {
            return;
        }
        Block block = player.world.getBlockState(event.getPos()).getBlock();
        if (!(block instanceof BlockTruckPad)) {
            return;
        }
        // ★ 这一行就是"卡车放不出来"的开关。详见类注释。
        event.setUseBlock(Event.Result.ALLOW);
        // 只留一条"这道门确实开了"的痕迹，且只在服务端打（事件两端都会触发）。
        // 有了它，"放不出车"才能被区分成两种完全不同的故障：
        //   日志里<b>没有</b>这一行 = 事件都没到（handler 没注册上 / 不在主事件总线）；
        //   有这一行、但没有随后的"放出卡车" = 引擎仍然没调用 onBlockActivated。
        if (!player.world.isRemote) {
            RsAudio.log("[卡车] 放车请求：%s 潜行右键卡位 @ %d,%d,%d",
                    player.getName(), Integer.valueOf(event.getPos().getX()),
                    Integer.valueOf(event.getPos().getY()),
                    Integer.valueOf(event.getPos().getZ()));
        }
    }
}

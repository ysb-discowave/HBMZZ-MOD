package com.rsaudio.util;

import com.rsaudio.block.BlockBlastCurtain;
import com.rsaudio.block.BlockBlastCurtainPanel;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.EnumHand;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 让<b>原版铁铲潜行右键</b>能打到防爆窗帘上 —— 只做这一件事。
 *
 * <h3>为什么需要这个类（和 {@link WandHandler} 里那条 ALLOW 是同一个坑）</h3>
 * 1.12.2 原版 {@code PlayerControllerMP#processRightClickBlock} 的逻辑是：
 * <pre>
 *   boolean bypass = held.doesSneakBypassUse(world, pos, player);   // 空手 true，工具 false
 *   if (!player.isSneaking() || bypass || event.getUseBlock() == ALLOW)
 *       block.onBlockActivated(...);
 * </pre>
 * 铁铲是 {@code ItemTool}，{@code doesSneakBypassUse} 走 {@code Item} 的默认实现（false）。
 * 所以<b>只要在潜行</b>，方块就收不到 {@code onBlockActivated} —— 界面的入口压根不会被调用。
 * 只有把 {@code useBlock} 显式设成 ALLOW 才能放行。
 *
 * <h3>★ 两个必须记住的写法</h3>
 * <ul>
 *   <li><b>不能 {@code setCanceled(true)}</b>：事件一 cancel，原版当场 return，
 *       连 {@code onBlockActivated} 都不会走到，等于自己把帘子焊死。</li>
 *   <li><b>只处理潜行</b>：不潜行时原版本来就会调用 {@code onBlockActivated}
 *       （"铁铲右键 = 开关"靠的就是那条），这里再插手反而容易把动作吃掉。</li>
 * </ul>
 * <p>
 * 木斧那一套不在这里：{@code WandHandler} 已经对 {@code instanceof BlockBlastDoor}
 * 做过一遍（窗帘继承自它，天然被覆盖）。
 *
 * <h3>★ 不挂 {@code @Mod.EventBusSubscriber}</h3>
 * {@code rsaudio} 是同一枚 jar 里的第二个 {@code @Mod} 容器，注解注入那条链一旦漏掉本类，
 * 失败是<b>完全静默</b>的（见 {@code ModBlocks} 的类注释）。所以由
 * {@code RsAudio#preInit} 显式 {@code MinecraftForge.EVENT_BUS.register(CurtainToolHandler.class)}。
 */
public final class CurtainToolHandler {

    private CurtainToolHandler() {
    }

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != EnumHand.MAIN_HAND) {
            return;
        }
        EntityPlayer player = event.getEntityPlayer();
        if (player == null || !player.isSneaking()) {
            return;
        }
        if (!BlockBlastCurtain.isCurtainTool(player)) {
            return;
        }
        Block block = player.world.getBlockState(event.getPos()).getBlock();
        if (block instanceof BlockBlastCurtain || block instanceof BlockBlastCurtainPanel) {
            // 把这次右键交还给方块自己（它去开尺寸界面），但不 cancel 事件
            event.setUseBlock(Event.Result.ALLOW);
        }
    }
}

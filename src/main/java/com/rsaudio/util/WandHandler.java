package com.rsaudio.util;

import com.rsaudio.ModBlocks;
import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockBlastDoor;
import com.rsaudio.block.BlockTartarus;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketWandSync;
import com.rsaudio.structure.StructureFile;
import com.rsaudio.structure.StructureSelection;
import com.rsaudio.structure.StructureStorage;
import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.Event;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * 结构魔杖：<b>手持木斧</b>就是魔杖。
 *
 * <h3>操作</h3>
 * <pre>
 *   左键点方块          = 选第一个角
 *   右键点方块          = 选第二个角
 *   潜行 + 左键         = 清空选区
 *   潜行 + 右键（普通方块）= 打开魔杖界面（填名字、保存、清空）
 * </pre>
 * ★ 防核门<b>不归魔杖管</b>：它自己的木斧右键是"开 / 关门"、潜行右键是设置界面
 * （见下面第 3 条与 {@code BlockBlastDoor}）。
 *
 * <h3>三个必须注意的点</h3>
 * <ol>
 *     <li>必须把事件的 Result 设成 DENY：不然"左键选角"会变成"把方块挖了"。
 *         DENY 在客户端也要设（否则客户端会先自己把方块挖掉再被服务端纠回来，看着像回弹）。</li>
 *     <li>潜行右键我们自己的机器（加载器/计算机/警报灯/终端/面板/气闸）时<b>不拦截</b>，
 *         让方块自己去开界面 —— 但要<b>显式 {@code setUseBlock(ALLOW)}</b>：
 *         潜行 + 手持木斧时方块收不到 onBlockActivated（木斧的 doesSneakBypassUse = false），
 *         只"什么都不设"等于把这次右键<b>丢掉</b>。</li>
 *     <li><b>防核门（含门板）同样不归魔杖管</b>：不潜行 = 开 / 关门（方块自己收得到，
 *         什么都不设就行），潜行 = 设置界面（同样靠上面那条 ALLOW 放行）。
 *         它的"木斧右键 = 开关门"和魔杖的"右键 = 选第二个角"撞在同一格上，
 *         所以魔杖对门一概不插手（2026-09-25 之前是门开不了）。</li>
 * </ol>
 */
@Mod.EventBusSubscriber(modid = RsAudio.MODID)
public final class WandHandler {

    private WandHandler() {
    }

    /**
     * 手里拿的是木斧吗（主手）。
     *
     * <p>
     * ★★★ <b>全模组唯一一把"手持木斧"的尺子</b>，public 出去专门给防核门用：
     * "开关防核门只能用木斧"那条规则在两个人为入口上校验 —— 方块交互
     * （{@code BlockBlastDoor#onBlockActivated} / {@code BlockBlastDoorPanel#onBlockActivated}）
     * 和界面发回来的 {@code PacketSetDoor}（防"绕过界面直接发包"）。
     * 两处都调这里，不要再各自写一遍 {@code getItem() == Items.WOODEN_AXE} ——
     * 判据一旦有两份，迟早会出现"方块那边认、网络包那边不认"这种只在一半路径上生效的规则。
     */
    public static boolean isWand(EntityPlayer player) {
        if (player == null) {
            return false;
        }
        ItemStack stack = player.getHeldItemMainhand();
        return !stack.isEmpty() && stack.getItem() == Items.WOODEN_AXE;
    }

    /** 这个方块是我们自己的机器吗（除防核门以外：那些方块自带界面） */
    private static boolean isOurMachine(Block block) {
        if (block == ModBlocks.STRUCTURE_LOADER
                || block == ModBlocks.REDSTONE_COMPUTER
                // ★ 门板其实已经在 onRightClick 开头整块早退了，这一条是<b>兜底</b>：
                //   万一以后有人把那条早退改窄了，门板也不会被下面那条 else 当成
                //   "普通方块"去打开结构加载器界面
                || block == ModBlocks.BLAST_DOOR_PANEL
                || block == ModBlocks.AUDIO_PLAYER
                || block == ModBlocks.REDSTONE_DELAYER
                // ★ 门禁终端和气闸控制器也要在这里。漏了它们的症状是：
                //   手持木斧潜行右键终端，会打开**结构加载器**的界面 ——
                //   因为下面那条 else 分支把"不是门"的一律当成结构方块处理了。
                //   终端自己的潜行右键是"紧急封锁"，气闸的是"开设置界面"，
                //   两者都该由方块自己处理，而不是被木斧抢走
                || block == ModBlocks.ACCESS_TERMINAL
                // ★ 远程控制面板同样必须在这里。它的设置界面（模式 / 频道）就是靠
                //   "潜行右键"打开的，漏了的话手拿木斧一站过去就会被打开**结构加载器**界面
                || block == ModBlocks.REMOTE_PANEL
                || block == ModBlocks.AIRLOCK_CONTROLLER
                // ★ F9 控制柜也要在这里。它的界面就是"右键开"，
                //   漏了的话手持木斧潜行右键控制柜会打开**结构加载器**界面 ——
                //   因为下面那条 else 把"不是门"的一律当成结构方块处理了。
                //   （DMR 控制柜目前不在这个清单里，是同一个坑的另一处，见当日笔记。）
                || block == ModBlocks.F9_CONTROLLER
                // ★ 电梯门也要在这里：它自己的右键语义是"呼梯"（现场找控制柜并登记这一层），
                //   漏了的话手持木斧右键电梯门会被下面那条 else 当成"选第二个角"吃掉，
                //   玩家按了门毫无反应（日志也空无一物）
                || block == ModBlocks.ELEVATOR_DOOR
                // ★ 排风扇也要在这里：它的右键语义是"开/停"，漏了的话手持木斧右键
                //   就会被下面那条 else 当成"选第二个角"吃掉（按下去毫无反应）
                || block == ModBlocks.EXHAUST_FAN
                // ★ 巨大标志牌同理：它的右键是"打开编辑界面"
                || block == ModBlocks.GIANT_SIGN
                // ★ 起重机：它的右键是"开/停"（和排风扇同类）；漏了的话手持木斧右键
                //   会被下面那条 else 当成"选第二个角"吃掉
                || block == ModBlocks.CRANE
                // ★ 卡车车位：它的"潜行右键"是放车；漏了的话手持木斧潜行右键
                //   被"选第二个角"吃掉，车放不出来（和排风扇/标志牌同一个坑）
                || block == ModBlocks.TRUCK_PAD) {
            return true;
        }
        // ★ 管道<b>不</b>在这个清单里，这是刻意的：它没有任何右键语义
        //   （纯装饰件，形状由邻居现算）。木斧指着它就该是"选角"，
        //   把它也列进来反而会让"拿木斧点管道选角"这件事失效。
        // ★ 三种电梯控制柜（客运 / 货运 / 斜面）也要在这里。它们的界面就是"右键开"，
        //   漏了的话手持木斧潜行右键会被打开**结构加载器**界面 —— 和上面 F9 控制柜同一个坑
        for (Block ctrl : ModBlocks.ELEVATOR_CONTROLLERS) {
            if (block == ctrl) {
                return true;
            }
        }
        for (Block alarm : ModBlocks.ALARM_LIGHTS) {
            if (block == alarm) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 左键：选第一个角

    @SubscribeEvent
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (!isWand(player)) {
            return;
        }
        // ★ 拦下这次左键，不然就是把方块挖掉了
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);

        if (player.world.isRemote) {
            return;
        }
        BlockPos pos = event.getPos();
        StructureSelection selection = StructureSelection.of(player.getUniqueID());
        if (player.isSneaking()) {
            selection.clearCorners();
            sync(player, "已清空选区");
            return;
        }
        selection.setPos1(pos);
        sync(player, "第一个角：" + format(pos) + " · " + selection.describe());
    }

    // ---------------------------------------------------------------- 右键：选第二个角 / 开界面

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        EntityPlayer player = event.getEntityPlayer();
        if (!isWand(player) || event.getHand() != EnumHand.MAIN_HAND) {
            return;
        }
        BlockPos pos = event.getPos();
        Block block = player.world.getBlockState(pos).getBlock();

        // ★★★ 防核门（控制器 + 门板）的右键语义<b>全在方块里</b>（见 {@code BlockBlastDoor#onBlockActivated}）：
        //   不潜行 = 开 / 关门，潜行 = 设置界面。魔杖两样都不碰
        //   （插手的话，"选第二个角"会把开门动作吃掉，症状是拿着木斧永远开不了门）。
        //   ★★ 但"不碰"分两种，别混为一谈：
        //     <ul>
        //       <li><b>不潜行</b> —— 方块自己收得到 {@code onBlockActivated}，什么都不设就是对的；</li>
        //       <li><b>潜行</b> —— 方块<b>收不到</b>，必须显式 ALLOW 把调用权交还给它。
        //           原版 {@code PlayerControllerMP.processRightClickBlock}：
        //           <pre>boolean bypass = held.doesSneakBypassUse(...);   // 空手 true / 手持木斧 false
        //if (!player.isSneaking() || bypass || event.getUseBlock() == ALLOW)
        //    block.onBlockActivated(...);</pre>
        //           木斧的 {@code doesSneakBypassUse} 是 {@code Item} 的默认实现（false），
        //           所以潜行那一路只有 ALLOW 能救 —— 少了它，木斧潜行右键<b>毫无反应</b>
        //           （2026-09-25 现场：把"潜行 = 设置界面"搬进方块里，界面就再也打不开了）。</li>
        //     </ul>
        //   ★ 更不要 {@code setCanceled(true)}：事件一 cancel，原版当场 return（连
        //     {@code onBlockActivated} 都不会走到），方块同样收不到。
        if (block instanceof BlockBlastDoor || block == ModBlocks.BLAST_DOOR_PANEL) {
            if (player.isSneaking()) {
                event.setUseBlock(Event.Result.ALLOW);
            }
            return;
        }

        if (player.isSneaking()) {
            boolean tartarus = block instanceof BlockTartarus;
            if (!tartarus && isOurMachine(block)) {
                // ★ 同上：潜行 + 手持木斧时方块收不到 onBlockActivated，
                //   要显式 ALLOW 才轮得到它自己开界面（空手时本来就 bypass，这条不影响空手）。
                event.setUseBlock(Event.Result.ALLOW);
                return;                      // 让方块自己开界面
            }
            event.setUseBlock(Event.Result.DENY);
            event.setUseItem(Event.Result.DENY);
            event.setCanceled(true);
            if (player.world.isRemote) {
                if (tartarus) {
                    RsAudio.proxy.openTartarusGui(pos);
                } else {
                    RsAudio.proxy.openStructureWandGui(pos);
                }
            }
            return;
        }

        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
        if (player.world.isRemote) {
            return;
        }
        StructureSelection selection = StructureSelection.of(player.getUniqueID());
        selection.setPos2(pos);
        sync(player, "第二个角：" + format(pos) + " · " + selection.describe());
    }

    // ---------------------------------------------------------------- 断开连接时清掉选区

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player != null) {
            StructureSelection.remove(event.player.getUniqueID());
        }
    }

    // ---------------------------------------------------------------- 给界面/网络包用的操作

    /** 保存选区（界面点"保存"） */
    public static String saveSelection(EntityPlayer player, String name) {
        if (player == null) {
            return "";
        }
        StructureSelection selection = StructureSelection.of(player.getUniqueID());
        if (!selection.isComplete()) {
            sync(player, "选区不完整：左键选第一个角，右键选第二个角");
            return "选区不完整：左键选第一个角，右键选第二个角";
        }
        String safe = StructureStorage.sanitizeName(name);
        if (safe.isEmpty()) {
            sync(player, "结构名不能为空（也不能只有点号）");
            return "结构名不能为空（也不能只有点号）";
        }
        StructureFile.Capture capture = StructureFile.capture(player.world,
                selection.getPos1(), selection.getPos2(), safe, player.getName());
        if (!capture.isOk()) {
            sync(player, capture.error);
            return capture.error;
        }
        String error = StructureStorage.save(capture.file);
        if (error != null) {
            sync(player, error);
            return error;
        }
        selection.setName(safe);
        StringBuilder sb = new StringBuilder();
        sb.append("已保存「").append(safe).append("」").append(capture.file.dimensions())
                .append("，非空气方块 ").append(capture.blocks).append(" 个");
        if (capture.file.tiles.size() > 0) {
            sb.append("，含 ").append(capture.file.tiles.size()).append(" 个方块实体");
        }
        if (capture.skippedTiles > 0) {
            sb.append("（").append(capture.skippedTiles).append(" 个方块实体超出上限没存）");
        }
        String text = sb.toString();
        sync(player, text);
        return text;
    }

    /** 清空选区（界面点"清空"） */
    public static void clearSelection(EntityPlayer player) {
        if (player == null) {
            return;
        }
        StructureSelection.of(player.getUniqueID()).clearCorners();
        sync(player, "已清空选区");
    }

    /** 把选区状态同步给本人；message 为 null 表示不改提示文字 */
    public static void sync(EntityPlayer player, String message) {
        if (!(player instanceof EntityPlayerMP)) {
            return;
        }
        StructureSelection selection = StructureSelection.of(player.getUniqueID());
        PacketHandler.INSTANCE.sendTo(new PacketWandSync(selection, message), (EntityPlayerMP) player);
        if (message != null && !message.isEmpty()) {
            player.sendStatusMessage(new TextComponentString(message), true);
        }
    }

    private static String format(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}

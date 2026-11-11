package com.rsaudio;

import com.rsaudio.block.BlockAccessTerminal;
import com.rsaudio.block.BlockAirlockController;
import com.rsaudio.block.BlockAlarmLight;
import com.rsaudio.block.BlockAnnouncer;
import com.rsaudio.block.BlockAudioPlayer;
import com.rsaudio.block.BlockBlastDoor;
import com.rsaudio.block.BlockBlastDoorPanel;
import com.rsaudio.block.BlockTartarus;
import com.rsaudio.block.BlockTartarusHatch;
import com.rsaudio.block.BlockDmrCasing;
import com.rsaudio.block.BlockDmrController;
import com.rsaudio.block.BlockDmrCore;
import com.rsaudio.block.BlockF9Casing;
import com.rsaudio.block.BlockF9Controller;
import com.rsaudio.block.BlockF9Core;
import com.rsaudio.block.BlockElevatorCar;
import com.rsaudio.block.BlockElevatorController;
import com.rsaudio.block.BlockElevatorDoor;
import com.rsaudio.block.BlockElevatorShaft;
import com.rsaudio.block.BlockExhaustFan;
import com.rsaudio.block.BlockGiantPipe;
import com.rsaudio.block.BlockGiantSign;
import com.rsaudio.block.BlockCrane;
import com.rsaudio.block.BlockTruckPad;
import com.rsaudio.block.BlockMonitorCamera;
import com.rsaudio.block.BlockMonitorConsole;
import com.rsaudio.block.BlockRedstoneComputer;
import com.rsaudio.block.BlockRedstoneDelayer;
import com.rsaudio.block.BlockRemotePanel;
import com.rsaudio.block.BlockStructureLoader;
import com.rsaudio.block.BlockTrainRail;
import com.rsaudio.block.BlockTrainStation;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.registries.IForgeRegistry;

/**
 * 方块与物品注册。
 * <p>
 * ★ 注意 {@link #BLAST_DOOR_PANEL}：它<b>故意没有 ItemBlock</b>。
 * 防核门的门板只能由控制器生成、也只能由控制器（或拆掉任意一格）移除，
 * 没有物品就不会出现在创造模式物品栏里、挖掉也不会掉落，从根上避免"孤儿门板"。
 * <p>
 * ★ DMR 那三个方块（控制柜 / 外壳 / 堆芯腔体）是<b>纯几何</b>：
 * 它们自己不记得任何反应堆状态，状态全在控制柜的方块实体里。拆掉外壳不会动到状态，
 * 只会在下一次结构扫描时被判成"结构不成立"。
 */
@Mod.EventBusSubscriber(modid = RsAudio.MODID)
public class ModBlocks {

    public static final Block AUDIO_PLAYER = new BlockAudioPlayer();
    public static final Block REDSTONE_DELAYER = new BlockRedstoneDelayer();
    public static final Block STRUCTURE_LOADER = new BlockStructureLoader();
    public static final Block REDSTONE_COMPUTER = new BlockRedstoneComputer();
    public static final Block BLAST_DOOR = new BlockBlastDoor();
    /** 广播器：向下发右下角弹窗 + TTS 语音 */
    public static final Block ANNOUNCER = new BlockAnnouncer();
    /** 防核门门板（无物品） */
    public static final Block BLAST_DOOR_PANEL = new BlockBlastDoorPanel();

    /**
     * 塔塔鲁斯舱盖门（圆形、巨型、向下缓慢开合的活板门）：
     * <ul>
     *   <li>控制器 {@code TARTARUS} 玩家可以放置（有物品），它的方块实体记着半径 / 开合状态；</li>
     *   <li>舱盖格 {@code TARTARUS_HATCH} 没有物品，由控制器在"建造"时生成、在"拆除"时移除，避免孤儿舱盖。</li>
     * </ul>
     * 两者都用 {@code INVISIBLE} 渲染，真正可见的圆形金属盘 + 中心枢纽全由 {@code TartarusRenderer} 画。
     */
    public static final Block TARTARUS = new BlockTartarus();
    /** 塔塔鲁斯舱盖格（无物品，由控制器生成 / 移除） */
    public static final Block TARTARUS_HATCH = new BlockTartarusHatch();

    /** DMR 控制柜：整套反应堆的唯一入口，有方块实体 */
    public static final Block DMR_CONTROLLER = new BlockDmrController();
    /** DMR 外壳：只为砌出多方块结构存在 */
    public static final Block DMR_CASING = new BlockDmrCasing();
    /** DMR 堆芯腔体：多方块结构的中心那一格 */
    public static final Block DMR_CORE = new BlockDmrCore();

    /**
     * F9（Foxtrot Mk.9 / Project Helios）反应堆。和 DMR 是<b>两台独立机器</b>，
     * 数值与玩法完全不同（见 {@code f9/F9Spec} 的类注释）。
     * <p>
     * 三个方块的分工与 DMR 一一对应：控制柜是唯一入口、外壳只为砌结构、
     * 压力容器是结构中心。区别只有一处：F9 的压力容器<b>没有方块实体也没有 TESR</b>
     * （它是一台压水堆，要的是"一个会发亮的压力容器"而不是一团等离子体），
     * 所以它的发光靠 {@code BlockF9Core.LIT} + {@code getLightValue}。
     */
    public static final Block F9_CONTROLLER = new BlockF9Controller();
    /** F9 外壳：只为砌出多方块结构存在 */
    public static final Block F9_CASING = new BlockF9Casing();
    /** F9 压力容器：多方块结构的中心那一格 */
    public static final Block F9_CORE = new BlockF9Core();

    /**
     * 电梯三件套（客运 / 货运 / 斜面）—— 和六色警报灯一样用数组装，
     * 下标与 {@link com.rsaudio.elevator.ElevatorSpec#TYPE_*} 一一对应。
     * <p>
     * 三种电梯共用同一个方块类（{@link BlockElevatorController(int)}），
     * 差异只在构造参数与预设数值里。做成三块而不是一块 + 界面切类型，
     * 是为了让"我要装一台货梯"这件事在物品栏里就一眼可见。
     */
    public static final Block[] ELEVATOR_CONTROLLERS = new Block[BlockElevatorController.TYPE_COUNT];

    static {
        for (int i = 0; i < ELEVATOR_CONTROLLERS.length; i++) {
            ELEVATOR_CONTROLLERS[i] = new BlockElevatorController(i);
        }
    }

    /** 电梯井道：轿厢跑在里面的框，可穿过 */
    public static final Block ELEVATOR_SHAFT = new BlockElevatorShaft();
    /**
     * 电梯轿厢格：平台 + 四角立柱。
     * ★★ <b>故意没有 ItemBlock</b>（见 {@link BlockElevatorCar} 的类注释）——
     * 它只能由控制柜生成与移除。让玩家能手动摆轿厢，等于让他能把轿厢摆到井道外面去。
     */
    public static final Block ELEVATOR_CAR = new BlockElevatorCar();
    /** 电梯层门：开关由控制柜统一驱动，右键 = 呼叫轿厢到这一层 */
    public static final Block ELEVATOR_DOOR = new BlockElevatorDoor();

    /** 监控摄像头：六个面都能装，可选频道，画面由客户端自己离屏渲染 */
    public static final Block MONITOR_CAMERA = new BlockMonitorCamera();
    /** 监控控制台：正面上实时显示它所在频道的摄像头画面 */
    public static final Block MONITOR_CONSOLE = new BlockMonitorConsole();

    /**
     * FAAS 门禁终端：把一个门禁频道（一片区域）里的门整体锁死 / 放行。
     * <p>
     * 和防核门是<b>配合</b>关系而不是替代关系：门负责"这一格挡不挡路"，
     * 终端负责"这片区域现在允不允许通行"。门不需要打开「联锁」也能被终端管
     * （联锁是门与门互斥，终端是区域规定，两者独立）。
     */
    public static final Block ACCESS_TERMINAL = new BlockAccessTerminal();

    /**
     * 气闸控制器：按一下走完"两侧关上 → 净化 → 对面打开"。
     * <p>
     * 它和门是<b>主从</b>关系：门自己那套"同频道互斥 + 关上一侧给另一侧起倒计时"
     * 只是手动近似版；接上控制器之后，时序由控制器接管（放行前会清掉门自己的倒计时）。
     * 配对靠"8 格内、同频道、已建造、开了联锁"自动找，不需要玩家手动绑定。
     */
    public static final Block AIRLOCK_CONTROLLER = new BlockAirlockController();

    /**
     * 远程控制面板：正面 6 个按钮，<b>右键直接按</b>。
     * <p>
     * 一个方块两种用途 —— 控制 DMR 反应堆（{@link com.rsaudio.panel.PanelCore#MODE_DMR}）
     * 或控制同频道的大门（{@link com.rsaudio.panel.PanelCore#MODE_GATE}），
     * 在潜行右键打开的设置界面里切。
     * <p>
     * 它<b>自己不实现任何规则</b>：能不能点火、门开不开得动，全由那台目标机器判定
     * （包括"被 FAAS 锁着"这类原因），面板只是把手指伸到了几十格之外。
     */
    public static final Block REMOTE_PANEL = new BlockRemotePanel();

    /**
     * 列车轨道。玩家一节一节铺，铺成环线之后列车就能跑。
     * <p>
     * ★ 形状（接上了哪几个方向）<b>不写进 meta</b>，由 {@code getActualState}
     * 按邻居现算。理由见 {@link BlockTrainRail} 的类注释。
     */
    public static final Block TRAIN_RAIL = new BlockTrainRail();

    /**
     * 列车站台。铺在轨道旁边或上方，列车会自动找到它停靠。
     * <p>
     * ★ 站台自带方块实体（存站名 + 停站时间），所以它必须同时出现在
     * {@link RsAudio#preInit} 的 {@code registerTileEntity} 清单里 ——
     * 漏了那一条的症状是<b>一进世界就崩</b>（存/读档时
     * {@code writeToNBT} 抛 {@code "is missing a mapping!"}）。
     */
    public static final Block TRAIN_STATION = new BlockTrainStation();

    /*
     * 装饰组三件：巨大管道 / 排风扇 / 巨大标志牌。
     *
     * 三者都是"给设施收尾"用的，但对 MC 的要求完全不同 —— 这三块正好覆盖了
     * 本模组三种典型形态：
     *   管道   纯方块，形状由邻居现算（6 个 boolean = 64 种组合），没有方块实体；
     *   排风扇 有方块实体 + TESR（叶片要转），状态在方块状态里；
     *   标志牌 有方块实体 + TESR + 界面 + 上行包（文字是可编辑内容）。
     * 所以它们的接线清单长度不同 —— 加新东西时照最近的那一个抄，别照最简的抄。
     */
    /** 巨大管道：六向自动连接，碰撞盒跟着形状走 */
    public static final Block GIANT_PIPE = new BlockGiantPipe();
    /** 排风扇：右键开关，叶片由 ExhaustFanRenderer 画 */
    public static final Block EXHAUST_FAN = new BlockExhaustFan();
    /** 巨大标志牌：三行可编辑文字 + 八色 + 照明 */
    public static final Block GIANT_SIGN = new BlockGiantSign();
    /** 起重机：右键切换运行/停臂，龙门架/回转臂/吊钩由 CraneRenderer 画 */
    public static final Block CRANE = new BlockCrane();
    /** 货运卡车车位：潜行右键放出一台 {@link com.rsaudio.entity.EntityFreightTruck} */
    public static final Block TRUCK_PAD = new BlockTruckPad();

    /** 六色警报灯，下标和 {@link BlockAlarmLight#COLOR_NAMES} 一一对应 */
    public static final Block[] ALARM_LIGHTS = new Block[BlockAlarmLight.COLOR_COUNT];

    static {
        for (int i = 0; i < ALARM_LIGHTS.length; i++) {
            ALARM_LIGHTS[i] = new BlockAlarmLight(i);
        }
    }

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        IForgeRegistry<Block> registry = event.getRegistry();
        registry.register(AUDIO_PLAYER);
        registry.register(REDSTONE_DELAYER);
        registry.register(STRUCTURE_LOADER);
        registry.register(REDSTONE_COMPUTER);
        registry.register(BLAST_DOOR);
        registry.register(BLAST_DOOR_PANEL);
        registry.register(TARTARUS);
        registry.register(TARTARUS_HATCH);
        registry.register(ANNOUNCER);
        registry.register(DMR_CONTROLLER);
        registry.register(DMR_CASING);
        registry.register(DMR_CORE);
        registry.register(F9_CONTROLLER);
        registry.register(F9_CASING);
        registry.register(F9_CORE);
        // ★ 电梯：三个控制柜 + 井道 + 轿厢 + 层门，六种方块<b>全都要注册方块</b>。
        //   轿厢那一行尤其不能漏 —— 它没有 ItemBlock，但方块本身必须存在，
        //   否则控制柜往世界里写它的时候写的是空气（而方块实体那边一声不响）。
        for (Block ctrl : ELEVATOR_CONTROLLERS) {
            registry.register(ctrl);
        }
        registry.register(ELEVATOR_SHAFT);
        registry.register(ELEVATOR_CAR);
        registry.register(ELEVATOR_DOOR);
        registry.register(MONITOR_CAMERA);
        registry.register(MONITOR_CONSOLE);
        registry.register(ACCESS_TERMINAL);
        registry.register(AIRLOCK_CONTROLLER);
        // ★ 远程控制面板之前漏了<b>方块</b>注册（只有它的 ItemBlock 在 registerItems 里被注册了）。
        //   这是和塔塔鲁斯正好相反的那种漏：物品在、方块不在，放下去会变成空气。
        registry.register(REMOTE_PANEL);
        // ★ 列车：轨道（无方块实体） + 站台（有方块实体）
        registry.register(TRAIN_RAIL);
        registry.register(TRAIN_STATION);
        // ★ 装饰组：三块都要注册方块。管道没有方块实体，另外两块有 ——
        //   但"注册方块"这一条对三者一样，漏了就是"物品在、放下去变空气"。
        registry.register(GIANT_PIPE);
        registry.register(EXHAUST_FAN);
        registry.register(GIANT_SIGN);
        // ★ 起重机 + 卡车车位（起重组）。漏注册的症状和装饰组那三块一样：
        //   物品在、放下去变空气。
        registry.register(CRANE);
        registry.register(TRUCK_PAD);
        for (Block alarm : ALARM_LIGHTS) {
            registry.register(alarm);
        }
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        IForgeRegistry<Item> registry = event.getRegistry();
        registry.register(makeItemBlock(AUDIO_PLAYER));
        registry.register(makeItemBlock(REDSTONE_DELAYER));
        registry.register(makeItemBlock(STRUCTURE_LOADER));
        registry.register(makeItemBlock(REDSTONE_COMPUTER));
        registry.register(makeItemBlock(BLAST_DOOR));
        // ★ 塔塔鲁斯控制器必须有物品。之前只注册了方块、没注册 ItemBlock ——
        //   症状是"东西明明写了，创造模式物品栏里却根本没有、也放不下去"，
        //   玩家看到的就是"塔塔鲁斯被吃掉了"。舱盖格 TARTARUS_HATCH 则故意没有物品
        //   （它只能由控制器生成/移除，见类注释）。
        registry.register(makeItemBlock(TARTARUS));
        registry.register(makeItemBlock(ANNOUNCER));
        registry.register(makeItemBlock(DMR_CONTROLLER));
        registry.register(makeItemBlock(DMR_CASING));
        registry.register(makeItemBlock(DMR_CORE));
        // ★ F9 三个方块都要有物品 —— 它们全是"玩家要能拿在手上摆"的（见上面塔塔鲁斯那条注释：
        //   方块注册了但 ItemBlock 漏了的症状是"写了却根本拿不到"）。
        registry.register(makeItemBlock(F9_CONTROLLER));
        registry.register(makeItemBlock(F9_CASING));
        registry.register(makeItemBlock(F9_CORE));
        // ★ 电梯：控制柜（三种）、井道、层门都要有物品（玩家要能拿在手上摆）。
        //   ★★ 轿厢（ELEVATOR_CAR）<b>故意没有</b> —— 见它的类注释：
        //     它只能由控制柜生成/移除。verify_jar 的 FORBIDDEN_ITEM_MODELS 盯着这一条。
        for (Block ctrl : ELEVATOR_CONTROLLERS) {
            registry.register(makeItemBlock(ctrl));
        }
        registry.register(makeItemBlock(ELEVATOR_SHAFT));
        registry.register(makeItemBlock(ELEVATOR_DOOR));
        registry.register(makeItemBlock(MONITOR_CAMERA));
        registry.register(makeItemBlock(MONITOR_CONSOLE));
        registry.register(makeItemBlock(ACCESS_TERMINAL));
        registry.register(makeItemBlock(AIRLOCK_CONTROLLER));
        registry.register(makeItemBlock(REMOTE_PANEL));
        // ★ 列车轨道与站台都要有物品（玩家要能拿在手上铺）。
        //   轨道是纯几何方块、站台带方块实体，两者都必须走 makeItemBlock —— 见上面塔塔鲁斯那条注释。
        registry.register(makeItemBlock(TRAIN_RAIL));
        registry.register(makeItemBlock(TRAIN_STATION));
        // ★ 装饰组三块全都要有物品：它们都是"玩家拿在手上摆"的装饰件。
        //   （对照：电梯轿厢 / 防爆门门板 / 塔塔鲁斯舱盖格是"只有机器能生成"的，
        //     那三种故意不注册，见各自的类注释。）
        registry.register(makeItemBlock(GIANT_PIPE));
        registry.register(makeItemBlock(EXHAUST_FAN));
        registry.register(makeItemBlock(GIANT_SIGN));
        // ★ 起重机与卡车车位都是"玩家拿在手上摆"的件 → 都要有 ItemBlock
        //   （少注册的症状见上面塔塔鲁斯那条注释："写了却根本拿不到"）。
        registry.register(makeItemBlock(CRANE));
        registry.register(makeItemBlock(TRUCK_PAD));
        for (Block alarm : ALARM_LIGHTS) {
            registry.register(makeItemBlock(alarm));
        }
        // 门板没有物品（见类注释）
    }

    private static Item makeItemBlock(Block block) {
        ItemBlock item = new ItemBlock(block);
        item.setRegistryName(block.getRegistryName());
        item.setCreativeTab(RsAudioTab.INSTANCE);
        return item;
    }
}

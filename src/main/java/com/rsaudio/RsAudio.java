package com.rsaudio;

import com.rsaudio.network.PacketHandler;
import com.rsaudio.proxy.CommonProxy;
import com.rsaudio.entity.EntityFreightTruck;
import com.rsaudio.entity.EntityTrain;
import com.rsaudio.tile.TileAccessTerminal;
import com.rsaudio.tile.TileAirlockController;
import com.rsaudio.tile.TileAlarmLight;
import com.rsaudio.tile.TileAnnouncer;
import com.rsaudio.tile.TileAudioPlayer;
import com.rsaudio.tile.TileBlastDoor;
import com.rsaudio.tile.TileCrane;
import com.rsaudio.tile.TileTartarus;
import com.rsaudio.tile.TileDmrController;
import com.rsaudio.tile.TileDmrCore;
import com.rsaudio.tile.TileF9Controller;
import com.rsaudio.tile.TileElevatorController;
import com.rsaudio.tile.TileExhaustFan;
import com.rsaudio.tile.TileGiantSign;
import com.rsaudio.tile.TileMonitorCamera;
import com.rsaudio.tile.TileMonitorConsole;
import com.rsaudio.tile.TileRedstoneComputer;
import com.rsaudio.tile.TileRedstoneDelayer;
import com.rsaudio.tile.TileRemotePanel;
import com.rsaudio.tile.TileTrainStation;
import com.rsaudio.tile.TileStructureLoader;
import com.rsaudio.util.TtsServer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.registry.EntityRegistry;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.Logger;

/**
 * 红石音频 (Redstone Audio)
 * <p>
 * 方块一览：
 * <ul>
 *     <li><b>音频播放器</b>：收到红石信号播放 config/rsaudio/audio 里的 .ogg 音乐。</li>
 *     <li><b>红石延迟器</b>：类似原版中继器，延迟若干刻后输出，右键输入时间。</li>
 *     <li><b>结构加载器</b>：把 config/rsaudio/structures 里的结构贴到世界上（红石可触发）。</li>
 *     <li><b>自动化红石信号输出计算机</b>：写"极简汇编"程序控制六个面的红石输出强度。</li>
 *     <li><b>警报灯</b>（六色）：通红石信号点亮，可调爆闪频率与蜂鸣。</li>
 *     <li><b>防核门</b>：可设置尺寸的滑门，抗爆，关门时会先确认门里没人。</li>
 *     <li><b>广播器</b>：向范围内玩家发右下角弹窗，并用 TTS 把文字念出来
 *         （语音引擎放在 {@code config/rsaudio/tts}，没装也能只弹窗）。</li>
 *     <li><b>DMR 控制柜 / 外壳 / 堆芯腔体</b>：暗物质反应堆。3×3×3 外壳围住一块堆芯腔体、
 *         控制柜贴在外壳某面正中心，右键控制柜开面板点火。
 *         反应堆的物理与状态机是零 MC 依赖内核 {@code com.rsaudio.dmr}，
 *         方块实体只负责喂 tick、播报和存档。</li>
 * </ul>
 * 另外：手持<b>木斧</b>就是"结构魔杖"（左键选角、右键选角、潜行右键开界面），
 * 也是打开防核门设置界面的钥匙。
 */
@Mod(modid = RsAudio.MODID, name = RsAudio.NAME, version = RsAudio.VERSION,
        acceptedMinecraftVersions = "[1.12,1.13)")
public class RsAudio {

    public static final String MODID = "rsaudio";
    public static final String NAME = "红石音频";
    public static final String VERSION = "1.0.0";

    /** 动态生成的资源包目录名（resourcepacks/<PACK_FOLDER>） */
    public static final String PACK_FOLDER = "rsaudio_dynamic";

    /**
     * 实体 id 表。<b>模组内唯一即可</b>，但一旦发布过就不能改 ——
     * 它和实体名一起构成存档里的身份，改了老存档的车会认不出来。
     */
    public static final int ENTITY_ID_TRAIN = 0;
    public static final int ENTITY_ID_FREIGHT_TRUCK = 1;

    @Mod.Instance(MODID)
    public static RsAudio instance;

    @SidedProxy(clientSide = "com.rsaudio.proxy.ClientProxy", serverSide = "com.rsaudio.proxy.CommonProxy")
    public static CommonProxy proxy;

    public static Logger logger;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        logger = event.getModLog();
        Config.init(event.getModConfigurationDirectory());
        GameRegistry.registerTileEntity(TileAudioPlayer.class, new ResourceLocation(MODID, "audio_player"));
        GameRegistry.registerTileEntity(TileRedstoneDelayer.class, new ResourceLocation(MODID, "redstone_delayer"));
        // ★ 结构加载器 / 红石计算机 / 警报灯 / 防核门 的方块实体
        //   漏注册的症状：方块放下去看着没事，但永远不会 tick、存档读回来是空的
        //   （日志里会有 "Skipping TileEntity with id ..." 之类的报错）。
        GameRegistry.registerTileEntity(TileStructureLoader.class, new ResourceLocation(MODID, "structure_loader"));
        GameRegistry.registerTileEntity(TileRedstoneComputer.class, new ResourceLocation(MODID, "redstone_computer"));
        // 六色警报灯共用同一个方块实体类，所以只需要注册一次
        GameRegistry.registerTileEntity(TileAlarmLight.class, new ResourceLocation(MODID, "alarm_light"));
        GameRegistry.registerTileEntity(TileBlastDoor.class, new ResourceLocation(MODID, "blast_door"));
        // ★ 塔塔鲁斯：圆形舱盖门控制器上的方块实体（画盘 + 开合进度 + 防夹）
        GameRegistry.registerTileEntity(TileTartarus.class, new ResourceLocation(MODID, "tartarus"));
        // ★ 广播器：负责"发弹窗 + 放语音"的方块实体
        GameRegistry.registerTileEntity(TileAnnouncer.class, new ResourceLocation(MODID, "announcer"));
        // ★ DMR 控制柜：整套反应堆在 MC 世界里的唯一权威（外壳是纯几何方块，没有方块实体）
        GameRegistry.registerTileEntity(TileDmrController.class, new ResourceLocation(MODID, "dmr_controller"));
        // ★ DMR 堆芯：它<b>不存任何权威状态</b>（读数都在控制柜那边），存在的唯一理由是
        //   让 TESR 有个逐帧的挂载点 —— 等离子体要自转 / 脉动 / 熔毁时抖动，
        //   而方块模型是烘死在区块网格里的静态几何，做不到。
        //   ★★ 漏注册的症状不是"没动画"，而是**一进世界就崩**：
        //   存取档时 `TileEntity.writeToNBT` 会抛
        //   `class TileDmrCore is missing a mapping! This is a bug!`，
        //   玩家看到的却是"走到堆芯附近就崩"。自检：
        //   `grep -rl "extends TileEntity"` 的清单必须与下面这份注册清单一一对应。
        GameRegistry.registerTileEntity(TileDmrCore.class, new ResourceLocation(MODID, "dmr_core"));
        // ★ F9 控制柜：整套压水堆（PWR + 斯特林转换器）在 MC 世界里的唯一权威。
        //   漏注册的症状不是"界面打不开"，而是**一进世界就崩**
        //   （存/读档时 writeToNBT 抛 `class TileF9Controller is missing a mapping!`）。
        //   ★ F9 只有这一个方块实体 —— 外壳和压力容器都是纯几何方块，
        //   压力容器的"发光"走方块属性（BlockF9Core.LIT），不需要 TESR。
        //   所以这里<b>没有</b> TileF9Core 这一条，别照着 DMR 那份清单抄。
        GameRegistry.registerTileEntity(TileF9Controller.class, new ResourceLocation(MODID, "f9_controller"));
        // ★ 电梯控制柜：整台电梯（井道 / 轿厢 / 层门）在 MC 世界里的唯一权威。
        //   三种电梯（客运 / 货运 / 斜面）共用同一个方块实体类，靠 NBT 里的 Type 区分
        //   —— 所以这里只需要注册<b>一个</b> id。
        //   漏注册的症状同样是一进世界就崩：存/读档时 writeToNBT 抛
        //   `class TileElevatorController is missing a mapping!`。
        //   ★ 井道 / 轿厢 / 层门都<b>没有</b>方块实体：轿厢的移动由控制柜写方块完成，
        //     层门只是被写 open 属性。所以这里只有一条。
        GameRegistry.registerTileEntity(TileElevatorController.class,
                new ResourceLocation(MODID, "elevator_controller"));
        // ★ 监控：摄像头只存"配置"，控制台只存"看哪个频道"；
        //   画面是客户端自己离屏渲染的，所以两边都没有任何需要每 tick 同步的东西
        GameRegistry.registerTileEntity(TileMonitorCamera.class, new ResourceLocation(MODID, "monitor_camera"));
        GameRegistry.registerTileEntity(TileMonitorConsole.class, new ResourceLocation(MODID, "monitor_console"));
        // ★ FAAS 门禁终端：区域封锁的唯一权威。它定期把区域状态校准到频道里的每一扇门上，
        //   所以必须 tick（漏注册的症状是：按了封锁只生效一次，之后新造的门就漏了）
        GameRegistry.registerTileEntity(TileAccessTerminal.class, new ResourceLocation(MODID, "access_terminal"));
        // ★ 气闸控制器：状态机每 tick 推进（等待关门 / 净化倒计时 / 等待开门），
        //   还要定期重扫配对的门。漏注册的症状是：按一下什么都不发生（方块没 tick）
        GameRegistry.registerTileEntity(TileAirlockController.class, new ResourceLocation(MODID, "airlock_controller"));
        // ★★ 远程控制面板：这一条之前<b>漏了</b>，是整个面板"按下去什么都不发生"的根因。
        //   漏注册的方块实体，原版在存/读档时根本认不出它：
        //   放下去的那一刻 {@code createTileEntity} 确实造了一个出来，但
        //   {@code TileEntity#writeToNBT} 要用"类 → id"那张表写 {@code id}，
        //   表里没有这个类就直接抛 {@code "is missing a mapping!"}；
        //   读档时 {@code TileEntity.create} 也查不到 id —— 于是重进世界后面板上
        //   {@code getTileEntity} 返回 null，右键只能拿到"什么都没发生"。
        //   面板还要每秒重解析一次目标（{@code ITickable}），不注册就永远不 tick。
        GameRegistry.registerTileEntity(TileRemotePanel.class, new ResourceLocation(MODID, "remote_panel"));
        // ★ 列车站台：只存"站名 + 停站时间"，不 tick。
        //   漏注册的症状同样是"一进世界就崩"——和 TileRemotePanel / TileDmrCore 是同一类坑。
        //   注意列车<b>轨道</b>没有方块实体（形状由邻居现算），所以这里只注册站台。
        //   自检：`grep -rl "extends TileEntity"` 的清单与这份注册清单必须一一对应。
        GameRegistry.registerTileEntity(TileTrainStation.class, new ResourceLocation(MODID, "train_station"));
        // ★ 排风扇 / 巨大标志牌：两块都是 TESR 的挂载点。
        //   ★ 排风扇的方块实体<b>不存任何东西</b>（朝向和开关都在方块状态里），
        //     它存在的唯一理由是"TESR 只对带方块实体的方块调用"。
        //   ★ 标志牌存三行文字 + 字色，走 getUpdateTag 同步。
        //   漏注册的症状一视同仁：一进世界就崩（writeToNBT 抛 missing a mapping），
        //   玩家看到的却是"走到那台机器附近就崩"。自检同上：`grep -rl "extends TileEntity"`
        //   的清单必须和这份注册清单一一对应。
        GameRegistry.registerTileEntity(TileExhaustFan.class, new ResourceLocation(MODID, "exhaust_fan"));
        GameRegistry.registerTileEntity(TileGiantSign.class, new ResourceLocation(MODID, "giant_sign"));
        // ★★ 起重机 —— 和排风扇同一条取舍：方块实体<b>不存任何东西</b>（朝向和运行/停臂
        //   都在方块状态里），它存在的唯一理由是"TESR 只对带方块实体的方块调用"。
        //   漏注册的症状同上：放/读档一台起重机就 missing a mapping 崩世界。
        GameRegistry.registerTileEntity(TileCrane.class, new ResourceLocation(MODID, "crane"));
        // ★★ 列车实体 —— 本模组的<b>第一个自定义实体</b>。
        //   registerModEntity 的 8 个参数依次是：
        //     ResourceLocation  实体名（存档里的 id）
        //     Class             实体类
        //     String            实体名（老 API，随便给但要唯一）
        //     int               实体 id（模组内唯一，从 0 开始）
        //     Object            mod 实例（这里是 this）
        //     int               追踪范围（格）—— 小于 64 时远处的玩家看不到车
        //     int               更新频率（tick）—— 3 是"看起来连续"的常用值
        //     boolean           是否发送速度更新（列车的位置由内核硬设，需要 true）
        //   ★ 漏了这三行的症状是：{@code new EntityTrain(world)} 能造出来、
        //   {@code world.spawnEntity} 也不报错，但**存档读回来车就没了**
        //   （写档时找不到 id → "Skipping Entity with id ..."），
        //   而且客户端永远收不到位置更新。
        EntityRegistry.registerModEntity(
                new ResourceLocation(MODID, "train"),
                EntityTrain.class,
                MODID + ".train",
                ENTITY_ID_TRAIN,
                this,
                80,      // 追踪范围：比扫描半径大，让人在车还没到跟前时就能看见它进站
                3,       // 每 3 tick 同步一次位置
                true);   // 发送速度更新
        // ★★ 货运卡车 —— 本模组第二个自定义实体。参数含义和上面列车那条一模一样
        //   （8 个参数逐位对应）。★ 实体 id 不能改：它和实体名一起构成存档里的身份。
        EntityRegistry.registerModEntity(
                new ResourceLocation(MODID, "freight_truck"),
                EntityFreightTruck.class,
                MODID + ".freight_truck",
                ENTITY_ID_FREIGHT_TRUCK,
                this,
                80,      // 追踪范围
                3,       // 每 3 tick 同步一次位置
                true);   // 发送速度更新（位置由内核硬设，需要 true）
        PacketHandler.init();
        proxy.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    /**
     * 服务器启动完成：把语音的工作线程拉起来。
     * <p>
     * ★★ 为什么这两个方法必须写在<b>主类上</b>、而且必须是 {@code @Mod.EventHandler}：
     * FML 的生命周期事件实现的是 {@code FMLEvent}，**不是** Forge 事件总线上那种
     * {@code net.minecraftforge.fml.common.eventhandler.Event}。所以它们
     * <b>既不能</b>用 {@code @SubscribeEvent} 接，<b>也不能</b>挂在别的类上 ——
     * 反汇编 {@code FMLModContainer} 可见它只做这一件事：
     * <pre>
     *   Class.forName(主类, false, Loader.getModClassLoader())   // initialize=false
     *   → gatherAnnotations(那个 Class)
     *       → 对 getDeclaredMethods() 里每个方法：注解里有 Mod.EventHandler
     *         且「参数恰好 1 个」且「该参数可赋给 FMLEvent」→ 收进 eventMethods
     * </pre>
     * 两个后果要记住：
     * <ol>
     *   <li>写在别的类里（哪怕用 {@code @Mod.EventHandler}）**永远不会被调用**；</li>
     *   <li>给 FML 事件挂 {@code @SubscribeEvent} 再 register 到 Forge 总线，
     *       {@code EventBus.register} 会直接抛 {@code IllegalArgumentException}
     *       → 模组加载失败 → **游戏都进不去**（本模组踩过，见
     *       {@code CommonProxy} 里那段注释）。</li>
     * </ol>
     * 单人游戏里"退出世界 → 再进一个新世界"会重新走一遍 serverStarted/stopping，
     * 所以语音线程跟着服务器的生命周期起停，而不是只在游戏启动时建一次。
     */
    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        try {
            TtsServer.init();
        } catch (Throwable t) {
            warn("启动语音子系统失败: " + t);
        }
    }

    /** 服务器停止：放掉语音的工作线程 */
    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        try {
            TtsServer.shutdown();
        } catch (Throwable t) {
            warn("关闭语音子系统失败: " + t);
        }
    }

    /** 注意：只有当真的传了参数时才走 String.format，避免日志文本里的 % 触发异常 */
    public static void log(String format, Object... args) {
        if (logger == null) {
            return;
        }
        logger.info(args == null || args.length == 0 ? format : String.format(format, args));
    }

    public static void warn(String format, Object... args) {
        if (logger == null) {
            return;
        }
        logger.warn(args == null || args.length == 0 ? format : String.format(format, args));
    }
}

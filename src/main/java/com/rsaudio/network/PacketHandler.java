package com.rsaudio.network;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketSetTartarus;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * 网络通道注册。
 * <p>
 * ★ 每个包的 id 必须<b>唯一且稳定</b>：新包一律追加在末尾用 {@code nextId++}，
 * 不要插在中间或重排 —— 否则老客户端连新服务端时会"对着不同的包反序列化"，
 * 表现为各种莫名其妙的解码崩溃。
 */
public class PacketHandler {

    public static final SimpleNetworkWrapper INSTANCE = NetworkRegistry.INSTANCE.newSimpleChannel(RsAudio.MODID);

    private static int nextId = 0;

    public static void init() {
        // ---- 客户端 -> 服务端
        INSTANCE.registerMessage(PacketSetSound.Handler.class, PacketSetSound.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSetDelay.Handler.class, PacketSetDelay.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSetMode.Handler.class, PacketSetMode.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSoundFinished.Handler.class, PacketSoundFinished.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSetBroadcast.Handler.class, PacketSetBroadcast.class, nextId++, Side.SERVER);
        // 结构魔杖 / 结构加载器
        INSTANCE.registerMessage(PacketWandAction.Handler.class, PacketWandAction.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketStructureLoad.Handler.class, PacketStructureLoad.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketRequestStructures.Handler.class, PacketRequestStructures.class,
                nextId++, Side.SERVER);
        // 红石计算机 / 警报灯 / 防核门
        INSTANCE.registerMessage(PacketSetProgram.Handler.class, PacketSetProgram.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSetAlarm.Handler.class, PacketSetAlarm.class, nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSetDoor.Handler.class, PacketSetDoor.class, nextId++, Side.SERVER);
        // 塔塔鲁斯：设置界面（半径 / 建造 / 拆除 / 开 / 关）
        INSTANCE.registerMessage(PacketSetTartarus.Handler.class, PacketSetTartarus.class, nextId++, Side.SERVER);
        // 广播器（弹窗标题/内容 + 朗读文本）
        INSTANCE.registerMessage(PacketSetAnnouncer.Handler.class, PacketSetAnnouncer.class,
                nextId++, Side.SERVER);
        // DMR 控制柜：界面上的每一个操作
        INSTANCE.registerMessage(PacketDmrAction.Handler.class, PacketDmrAction.class,
                nextId++, Side.SERVER);
        // 监控：摄像头设置（频道/视野/启用/名字）与控制台设置（频道/字幕条）
        INSTANCE.registerMessage(PacketSetCamera.Handler.class, PacketSetCamera.class,
                nextId++, Side.SERVER);
        INSTANCE.registerMessage(PacketSetMonitor.Handler.class, PacketSetMonitor.class,
                nextId++, Side.SERVER);
        // FAAS 门禁终端：区域封锁 + 播报文案（整体覆盖，见 PacketSetTerminal 的类注释）
        INSTANCE.registerMessage(PacketSetTerminal.Handler.class, PacketSetTerminal.class,
                nextId++, Side.SERVER);
        // 气闸控制器：设置 / 启动 / 急停（三个动作都带整套参数，见 PacketSetAirlock 的类注释）
        INSTANCE.registerMessage(PacketSetAirlock.Handler.class, PacketSetAirlock.class,
                nextId++, Side.SERVER);
        // 远程控制面板：只发"模式 + 频道"这套设置。
        // ★ 面板上的 6 个按钮不走这个包 —— 它们是方块面上的实体按钮，
        //   原版 onBlockActivated 已经把命中坐标送到服务端了（见 PacketSetRemotePanel 的类注释）
        INSTANCE.registerMessage(PacketSetRemotePanel.Handler.class, PacketSetRemotePanel.class,
                nextId++, Side.SERVER);
        // F9 控制柜上的操作（启动 / 推拉控制棒 / 开关子系统 / 同步 / 停机）
        INSTANCE.registerMessage(PacketF9Action.Handler.class, PacketF9Action.class,
                nextId++, Side.SERVER);
        // 电梯控制柜上的操作（呼叫某层 / 开门 / 关门 / 取消呼叫 / 重扫井道）
        INSTANCE.registerMessage(PacketElevatorAction.Handler.class, PacketElevatorAction.class,
                nextId++, Side.SERVER);
        // 巨大标志牌：三行文字 + 字色 + 照明（整体覆盖，见 PacketGiantSignSet 的类注释）
        INSTANCE.registerMessage(PacketGiantSignSet.Handler.class, PacketGiantSignSet.class,
                nextId++, Side.SERVER);

        // ---- 服务端 -> 客户端
        INSTANCE.registerMessage(PacketPlaySound.Handler.class, PacketPlaySound.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketDelaySync.Handler.class, PacketDelaySync.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketModeSync.Handler.class, PacketModeSync.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketBroadcastSync.Handler.class, PacketBroadcastSync.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketWandSync.Handler.class, PacketWandSync.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketStructureList.Handler.class, PacketStructureList.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketProgramSync.Handler.class, PacketProgramSync.class, nextId++, Side.CLIENT);
        // 广播器：右下角弹窗 + 语音分片（Begin/Chunk/End 三件套必须连着注册、id 连续）
        INSTANCE.registerMessage(PacketAnnounce.Handler.class, PacketAnnounce.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketTtsBegin.Handler.class, PacketTtsBegin.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketTtsChunk.Handler.class, PacketTtsChunk.class, nextId++, Side.CLIENT);
        INSTANCE.registerMessage(PacketTtsEnd.Handler.class, PacketTtsEnd.class, nextId++, Side.CLIENT);
        // DMR 控制柜状态快照
        INSTANCE.registerMessage(PacketDmrSync.Handler.class, PacketDmrSync.class, nextId++, Side.CLIENT);
        // F9 控制柜读数快照。★ 比 DMR 推得更勤（3 tick 一次）—— 玩家盯着同步仪
        //   调压力时手上一直在动，推慢了指针就会跳。见 TileF9Controller.SYNC_INTERVAL_BUSY。
        INSTANCE.registerMessage(PacketF9Sync.Handler.class, PacketF9Sync.class, nextId++, Side.CLIENT);
        // 电梯读数快照。★ 推得比 F9 还勤（3 tick 一次，见 TileElevatorController.SYNC_INTERVAL_BUSY）
        //   —— 界面上的门开度和速度要跟得上，推慢了看起来就是"电梯在卡"
        INSTANCE.registerMessage(PacketElevatorSync.Handler.class, PacketElevatorSync.class,
                nextId++, Side.CLIENT);
        // 列车读数快照（速度 / 相位 / 乘客数 / 故障）。
        // ★ 位置和朝向<b>不走这个包</b> —— 那是原版实体同步负责的，
        //   两边都带一份的话车会在两个位置之间来回跳（见 PacketTrainSync 的类注释）
        INSTANCE.registerMessage(PacketTrainSync.Handler.class, PacketTrainSync.class,
                nextId++, Side.CLIENT);
        // 音频播放器：reasim 参数（整组覆盖，见 PacketSetReasim 的类注释）。
        // ★ 必须追加在最后：中间的包 id 一挪，老客户端和新服务端就会
        //   "对着不同的包反序列化"，崩得毫无线索（见类注释）。
        INSTANCE.registerMessage(PacketSetReasim.Handler.class, PacketSetReasim.class,
                nextId++, Side.SERVER);
    }
}

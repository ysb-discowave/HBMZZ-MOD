package com.rsaudio.proxy;

import com.rsaudio.util.TtsServer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

/** 通用（服务端/客户端共有）代理 */
public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        // 语音引擎的探测放这里：只摸几个文件，很便宜，而且能让玩家在日志里
        // 一开机就看到"语音到底装上了没有"，不用等到第一次广播才发现是哑的。
        try {
            TtsServer.init();
        } catch (Throwable t) {
            com.rsaudio.RsAudio.warn("初始化语音子系统失败（弹窗功能不受影响）: " + t);
        }
    }

    public void init(FMLInitializationEvent event) {
    }

    public void postInit(FMLPostInitializationEvent event) {
    }

    /**
     * ★★ 服务器的起停**不在这里**处理 —— 见 {@code RsAudio#onServerStarted} /
     * {@code RsAudio#onServerStopping}。
     * <p>
     * 这里原本写了一个挂着 {@code @SubscribeEvent} 的内部类去接
     * {@code FMLServerStartedEvent} / {@code FMLServerStoppingEvent}，
     * 并在 {@code preInit} 里 {@code MinecraftForge.EVENT_BUS.register(...)}。
     * <b>那会让模组在加载阶段直接崩掉、游戏都进不去</b>：
     * <pre>
     *   java.lang.IllegalArgumentException: Method ... onServerStopping(FMLServerStoppingEvent)
     *   has @SubscribeEvent annotation, but takes a argument that is not an Event class
     *       at EventBus.register(EventBus.java:109)
     * </pre>
     * 原因：FML 的生命周期事件（{@code FMLPreInitializationEvent} /
     * {@code FMLServerStartedEvent} / {@code FMLServerStoppingEvent} …）实现的是
     * {@code FMLEvent}，<b>不是</b> {@code net.minecraftforge.fml.common.eventhandler.Event}
     * 的子类 —— 它们根本不在 Forge 的事件总线上。它们由 FML 直接回调
     * {@code @Mod} 主类上带 {@code @Mod.EventHandler} 的方法
     * （反汇编 {@code FMLModContainer.gatherAnnotations} 可见：它只对
     * {@code Class.forName(主类)} 的结果做 {@code getDeclaredMethods()}，
     * 再挑出「恰好 1 个、类型可赋给 {@code FMLEvent}」的 {@code @Mod.EventHandler} 方法）。
     * <p>
     * 也就是说：FML 事件只能用 {@code @Mod.EventHandler} 写在主类里，
     * 而且必须是**主类自己声明的**方法（{@code getDeclaredMethods}，不认继承、
     * 也不认别的类里的 {@code @SubscribeEvent}）。
     */

    /** 打开选歌界面（只有客户端才有界面） */
    public void openAudioPlayerGui(BlockPos pos) {
    }

    /** 打开红石延迟器的延迟输入界面（只有客户端才有界面） */
    public void openRedstoneDelayerGui(BlockPos pos) {
    }

    /**
     * 打开结构魔杖界面（手持木斧潜行右键普通方块）。
     * <p>
     * 注意这个界面的 {@code pos} 只是"玩家点的那一格"，用来给玩家一个位置感；
     * 选区本身在服务端内存里，界面靠 {@code PacketWandSync} 同步回来。
     */
    public void openStructureWandGui(BlockPos pos) {
    }

    /** 打开结构加载器界面（右键加载器） */
    public void openStructureLoaderGui(BlockPos pos) {
    }

    /** 打开红石计算机界面（右键计算机） */
    public void openComputerGui(BlockPos pos) {
    }

    /** 打开警报灯界面（右键警报灯） */
    public void openAlarmGui(BlockPos pos) {
    }

    /**
     * 打开防核门设置界面。
     * <p>
     * ★ 它由方块自己触发（{@code BlockBlastDoor#onBlockActivated} 的"木斧 + 潜行右键"，
     * 门板那格也走同一条路，只是先把控制器位置找出来）。
     * 结构魔杖对防核门一律早退，不替它开界面 —— 见 {@code WandHandler} 里那条注释。
     */
    public void openBlastDoorGui(BlockPos pos) {
    }

    /** 打开塔塔鲁斯设置界面（只有客户端才有界面，这里服务端是空实现） */
    public void openTartarusGui(BlockPos pos) {
    }

    /**
     * 打开广播器界面（右键广播器）。
     * <p>
     * 界面里那三个输入框是纯客户端的，服务端只需要 {@code PacketSetAnnouncer}。
     */
    public void openAnnouncerGui(BlockPos pos) {
    }

    /**
     * 打开 DMR 控制柜界面（右键控制柜）。
     * <p>
     * 界面里的读数全部来自服务端推来的 {@code PacketDmrSync} 快照，操作走
     * {@code PacketDmrAction}。服务端自己不需要界面，所以这里是空的。
     */
    public void openDmrGui(BlockPos pos) {
    }

    /**
     * 打开 F9 控制柜界面（右键控制柜）。
     * <p>
     * ★ 这里是<b>空实现</b>，真正开界面的是 {@code ClientProxy} 的覆写。
     * 漏覆写的症状是"右键控制柜什么都不发生"、而且不报错不刷日志 ——
     * 项目里已经在 {@code openRemotePanelGui} / {@code openTartarusGui} 上各踩过一次。
     */
    public void openF9Gui(BlockPos pos) {
    }

    /**
     * 打开电梯控制柜界面（右键控制柜）。
     * ★ 这一条必须由 {@code ClientProxy} 覆写 —— 这里是空实现，
     * 不覆写的话右键控制柜就是"什么都不发生"，不报错、不刷日志。
     * 项目里在 {@code openRemotePanelGui} / {@code openTartarusGui} / {@code openF9Gui}
     * 上各踩过一次。
     */
    public void openElevatorGui(BlockPos pos) {
    }

    /**
     * 打开巨大标志牌的编辑界面（右键牌子）。
     * ★ 这一条必须由 {@code ClientProxy} 覆写 —— 这里是空实现，
     * 不覆写的话右键牌子就是"什么都不发生"，不报错、不刷日志。
     * 项目里在 {@code openRemotePanelGui} / {@code openTartarusGui} / {@code openF9Gui} /
     * {@code openElevatorGui} 上各踩过一次，这是第五处。
     */
    public void openGiantSignGui(BlockPos pos) {
    }

    /** 打开监控摄像头设置界面（右键摄像头） */
    public void openMonitorCameraGui(BlockPos pos) {
    }

    /** 打开监控控制台界面（右键控制台）——画面只有客户端才有，服务端什么都不做 */
    public void openMonitorConsoleGui(BlockPos pos) {
    }

    /**
     * 打开 FAAS 门禁终端界面（右键终端）。
     * <p>
     * 界面上的三个输入框是纯客户端的，服务端只需要 {@code PacketSetTerminal}。
     * 潜行右键那条路（直接翻转区域封锁）不走界面，服务端在方块自己那里处理。
     */
    public void openAccessTerminalGui(BlockPos pos) {
    }

    /**
     * 打开气闸控制器界面（潜行右键 / 木斧潜行右键）。
     * <p>
     * 服务端什么都不做：界面上的参数走 {@code PacketSetAirlock}，
     * 而「启动 / 急停」是那个包里的另外两个动作码。
     */
    public void openAirlockGui(BlockPos pos) {
    }

    /**
     * 打开远程控制面板的设置界面（潜行右键面板）。
     * <p>
     * 服务端什么都不做：界面里只有模式和频道，走 {@code PacketSetRemotePanel}。
     * 面板上的 6 个按钮不经过界面 —— 那是方块自己的 {@code onBlockActivated} 处理的。
     */
    public void openRemotePanelGui(BlockPos pos) {
    }

    /**
     * 释放某个坐标上监控画面占用的显卡离屏缓冲。
     * <p>
     * ★ 方块实体（{@code TileMonitorCamera}）是双端共用的类，它<b>不能</b>直接
     * {@code import} 客户端的渲染类 —— 那样专用服务器在链接这个类时会去解析
     * {@code net.minecraft.client.*}，轻则日志刷屏、重则崩服。
     * 走代理就没有这个问题：服务端拿到的是这个空实现，压根不会碰到客户端类。
     */
    public void releaseCameraFeed(BlockPos pos) {
    }

    /** 停掉某个方块位置上的客户端声音（服务端不做任何事） */
    public void stopClientSound(BlockPos pos) {
    }
}

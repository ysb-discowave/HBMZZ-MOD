package com.rsaudio.proxy;

import com.rsaudio.RsAudio;
import com.rsaudio.client.AudioLibrary;
import com.rsaudio.client.ClientNotice;
import com.rsaudio.client.ClientNoticeRenderer;
import com.rsaudio.client.ClientSoundPlayer;
import com.rsaudio.client.ClientDmrState;
import com.rsaudio.client.DynamicSoundPack;
import com.rsaudio.client.GuiAccessTerminal;
import com.rsaudio.client.GuiAirlockController;
import com.rsaudio.client.GuiAlarm;
import com.rsaudio.client.GuiAnnouncer;
import com.rsaudio.client.GuiAudioPlayer;
import com.rsaudio.client.GuiBlastDoor;
import com.rsaudio.client.GuiTartarus;
import com.rsaudio.client.GuiDmrController;
import com.rsaudio.client.GuiF9Controller;
import com.rsaudio.client.GuiElevatorController;
import com.rsaudio.client.GuiRedstoneComputer;
import com.rsaudio.client.GuiRedstoneDelayer;
import com.rsaudio.client.GuiStructureLoader;
import com.rsaudio.client.GuiStructureWand;
import com.rsaudio.client.GuiMonitorCamera;
import com.rsaudio.client.GuiMonitorConsole;
import com.rsaudio.client.GuiRemotePanel;
import com.rsaudio.client.MonitorConsoleRenderer;
import com.rsaudio.client.PanelRenderer;
import com.rsaudio.client.TtsClientPlayer;
import com.rsaudio.client.BlastDoorRenderer;
import com.rsaudio.client.CraneRenderer;
import com.rsaudio.client.DmrCoreRenderer;
import com.rsaudio.client.ExhaustFanRenderer;
import com.rsaudio.client.GiantSignRenderer;
import com.rsaudio.client.GuiGiantSign;
import com.rsaudio.client.TartarusRenderer;
import com.rsaudio.tile.TileBlastDoor;
import com.rsaudio.tile.TileCrane;
import com.rsaudio.tile.TileDmrCore;
import com.rsaudio.tile.TileExhaustFan;
import com.rsaudio.tile.TileGiantSign;
import com.rsaudio.tile.TileTartarus;
import com.rsaudio.tile.TileMonitorConsole;
import com.rsaudio.tile.TileRemotePanel;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.client.registry.RenderingRegistry;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** 客户端代理：动态资源包、界面、声音播放 */
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        MinecraftForge.EVENT_BUS.register(new ClientTickHandler());
        MinecraftForge.EVENT_BUS.register(new WorldUnloadHandler());
        // 右下角弹窗的绘制（挂在 RenderGameOverlayEvent.Post 上）
        MinecraftForge.EVENT_BUS.register(new ClientNoticeRenderer());
        // ★ 监控画面的离屏渲染：挂在 RenderWorldLastEvent 上"世界画完、界面没画"的那一刻
        com.rsaudio.client.CameraFeedRenderer.register();
        // 模型注册由 ClientModelRegistry 上的 @Mod.EventBusSubscriber 自动完成
        // （已确认 Forge 的 ModelRegistryEvent 是 post 到 MinecraftForge.EVENT_BUS 的）
        // ★★★ 列车实体（车头 + 拖着的车厢）的渲染器 —— 只能在 preInit 注册，绝不能写在 init。
        //   Forge 的实体渲染器表是**一次性装载**的：RenderManager 的构造函数最后一行会调
        //   RenderingRegistry.loadEntityRenderers(this, this.entityRenderMap)，从此
        //   entityRenderMap 就是一份**拷贝**，之后往 RenderingRegistry 里再塞什么都不再生效。
        //   而 Minecraft.init() 里的先后顺序是钉死的（反编译源码逐行核对）：
        //     514 行 beginMinecraftLoading(...) → 触发 preInit（= 本方法）
        //     565 行 new RenderManager(...)     → 装载渲染器表，**此后注册一律无效**
        //     582 行 finishMinecraftLoading()   → 触发 init（= ClientProxy.init）
        //   ★ 写在 init 里的后果是**完全静默**的：不报错、不刷日志、什么都不说。
        //   现场表现（已用反编译源码串完链条，不是猜）：
        //     RenderManager.getEntityRenderObject → getEntityClassRenderObject 找不到时
        //     **沿父类一路往上找**，最后落到原版给 Entity.class 兜的 RenderEntity，
        //     而它只画 Render.renderOffsetAABB（disableTexture2D + color(1,1,1,1)
        //     + 纯 POSITION_NORMAL 六面体）—— 于是玩家看到的是**一个纯白无贴图的大盒子**，
        //     尺寸正是 EntityTrain.setSize(CAR_W * 0.9, CAR_Y0 + CAR_H) 那么大；
        //     而且**车厢一节都不会出现**（车厢只有 TrainRenderer 会画）。
        //   ★ 这就是"纯白车头 + 车厢是空气"的全部原因 —— 和贴图、图集缝合、
        //     同步包都没有关系，是渲染器压根没被收下。
        //   ★ 注意对比：上面那批 TESR 用的是 ClientRegistry.bindTileEntitySpecialRenderer，
        //     它只是往 TileEntityRendererDispatcher.renderers 这个 Map 里 put，
        //     查表发生在**渲染那一刻**（源码 84 行的 renderers.get），所以写在 init 没问题。
        //     实体渲染器不是这个机制 —— 这是两件不同的事，别照抄。
        RenderingRegistry.registerEntityRenderingHandler(
                com.rsaudio.entity.EntityTrain.class,
                new net.minecraftforge.fml.client.registry.IRenderFactory<com.rsaudio.entity.EntityTrain>() {
                    @Override
                    public net.minecraft.client.renderer.entity.Render<? super com.rsaudio.entity.EntityTrain>
                            createRenderFor(net.minecraft.client.renderer.entity.RenderManager manager) {
                        return new com.rsaudio.client.TrainRenderer(manager);
                    }
                });
        // ★★★ 货运卡车实体渲染器 —— 和列车那条是<b>同一个纪律</b>：
        //   只能写在 preInit，绝不能写在 init（RenderManager 在两者之间就把
        //   渲染器表一次性拷走了；写错的后果是"完全静默"，玩家看到纯白无贴图的大盒子）。
        //   完整推理在列车那条的长注释里，这里不再重复。
        RenderingRegistry.registerEntityRenderingHandler(
                com.rsaudio.entity.EntityFreightTruck.class,
                new net.minecraftforge.fml.client.registry.IRenderFactory<com.rsaudio.entity.EntityFreightTruck>() {
                    @Override
                    public net.minecraft.client.renderer.entity.Render<? super com.rsaudio.entity.EntityFreightTruck>
                            createRenderFor(net.minecraft.client.renderer.entity.RenderManager manager) {
                        return new com.rsaudio.client.FreightTruckRenderer(manager);
                    }
                });
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // ★ 控制台方块上的实时屏幕。漏了这行不会报错，只是方块正面一片死黑
        ClientRegistry.bindTileEntitySpecialRenderer(TileMonitorConsole.class, new MonitorConsoleRenderer());
        // ★ 远程控制面板正面的 6 个按钮。同样是"漏了不报错、只是面板一片素板"
        ClientRegistry.bindTileEntitySpecialRenderer(TileRemotePanel.class, new PanelRenderer());
        // ★ 防核门上的旋转警报灯。绑在控制器那个唯一的方块实体上
        ClientRegistry.bindTileEntitySpecialRenderer(TileBlastDoor.class, new BlastDoorRenderer());
        // ★ 塔塔鲁斯的圆形舱盖盘 + 中心枢纽。绑在控制器那个唯一的方块实体上
        ClientRegistry.bindTileEntitySpecialRenderer(TileTartarus.class, new TartarusRenderer());
        // ★ DMR 堆芯腔体里的等离子体 + 两道磁约束环。这是"没有动画、不够大"那条反馈的正解：
        //   方块模型做不了逐帧动画，必须挂在方块实体上由 TESR 逐帧画。
        //   ★ 漏了这行不会报错 —— 堆芯方块是 INVISIBLE 渲染类型，于是玩家看到的是
        //   **堆芯凭空消失、机器中央一个洞**（因为方块本体刻意不画，全靠这里补上）。
        ClientRegistry.bindTileEntitySpecialRenderer(TileDmrCore.class, new DmrCoreRenderer());
        // ★ 排风扇的叶片 + 防护罩 + 外圈风筒。
        //   方块本身只是一个方壳（普通 cube 模型），转的东西全靠这里画。
        //   漏了不报错 —— 玩家看到的是"墙上贴了一块不会动的铁板"。
        ClientRegistry.bindTileEntitySpecialRenderer(TileExhaustFan.class, new ExhaustFanRenderer());
        // ★ 巨大标志牌上的三行字。
        //   牌面（深色板 + 边框）是方块模型，字是这里画的 —— 因为字是玩家自己写的，
        //   做不进贴图。漏了不报错 —— 玩家看到的是"一块空白牌子"，
        //   而且会以为是自己没设置上（这类"没报错但功能没了"正是本模组反复记的坑）。
        ClientRegistry.bindTileEntitySpecialRenderer(TileGiantSign.class, new GiantSignRenderer());
        // ★ 起重机的龙门架 / 回转臂 / 吊钩。方块本体只是一个带黄色条纹的基座（cube 模型），
        //   能转的部分全靠这里逐帧画。漏了不报错 —— 玩家看到的是"一块不会动的铁基座"。
        ClientRegistry.bindTileEntitySpecialRenderer(TileCrane.class, new CraneRenderer());
        // ★ 列车实体的渲染器注册**不在这里** —— 它必须写在 preInit（见上面的长注释：
        //   RenderManager 在 preInit 与 init 之间就把渲染器表一次性拷走了）。
        //   这里只做一件事：**当场验一遍它真的绑上了没有**，没绑上就补上并吼出来。
        //   为什么值得单独写一个方法：实体渲染器注册错阶段是**完全静默**的失败
        //   （不报错、不刷日志），玩家看到的只是"一个纯白盒子 + 车厢是空气"。
        verifyTrainRendererBound();
        verifyTruckRendererBound();
    }

    /**
     * 校验列车渲染器真的进了 {@code RenderManager}，没进就当场补上并报出来。
     * <p>
     * ★ 教训（2026-09-21 实锤）：实体渲染器注册错阶段不会报错 ——
     * 它只是安静地失效，玩家看到"纯白车头 + 车厢是空气"，日志里一个字都没有。
     * 所以这里既补上是保命（玩家不该为我们的时序错误买单），
     * 又必须<b>自报成因</b>（下次一眼能定位，而不是又去翻反编译源码）。
     */
    private static void verifyTrainRendererBound() {
        try {
            net.minecraft.client.renderer.entity.RenderManager rm = Minecraft.getMinecraft().getRenderManager();
            if (rm == null) {
                return;
            }
            Object got = rm.getEntityClassRenderObject(com.rsaudio.entity.EntityTrain.class);
            if (got instanceof com.rsaudio.client.TrainRenderer) {
                return;
            }
            // candidate 是 RenderEntity（原版给 Entity.class 兜的白盒子渲染器），
            // 说明沿父类找上去也没找到我们 —— 直接塞进表里兜住这一局。
            rm.entityRenderMap.put(com.rsaudio.entity.EntityTrain.class,
                    new com.rsaudio.client.TrainRenderer(rm));
            RsAudio.warn("[列车] 渲染器没被 RenderManager 收下（拿到的是 %s）—— 已当场补注册。"
                            + "成因基本只有一个：实体渲染器注册写在 init 里，晚于 RenderManager 装载渲染器表；"
                            + "必须写在 preInit（ClientProxy.preInit）",
                    got == null ? "null" : got.getClass().getName());
        } catch (Throwable t) {
            RsAudio.warn("[列车] 渲染器校验本身出错：%s", t.toString());
        }
    }

    /**
     * 校验卡车渲染器真的进了 {@code RenderManager} —— 和 {@link #verifyTrainRendererBound()}
     * 同一个方法、同一课（实体渲染器注册错阶段是**完全静默**的失败，
     * 症状是"纯白无贴图的大盒子"）。两者各查各的：查了列车不查卡车的话，
     * "车放出来是白盒子"这条故障就又回到"靠玩家用眼睛发现"的老路上。
     */
    private static void verifyTruckRendererBound() {
        try {
            net.minecraft.client.renderer.entity.RenderManager rm = Minecraft.getMinecraft().getRenderManager();
            if (rm == null) {
                return;
            }
            Object got = rm.getEntityClassRenderObject(com.rsaudio.entity.EntityFreightTruck.class);
            if (got instanceof com.rsaudio.client.FreightTruckRenderer) {
                return;
            }
            rm.entityRenderMap.put(com.rsaudio.entity.EntityFreightTruck.class,
                    new com.rsaudio.client.FreightTruckRenderer(rm));
            RsAudio.warn("[卡车] 渲染器没被 RenderManager 收下（拿到的是 %s）—— 已当场补注册。"
                            + "成因基本只有一个：实体渲染器注册写在 init 里；必须写在 preInit（ClientProxy.preInit）",
                    got == null ? "null" : got.getClass().getName());
        } catch (Throwable t) {
            RsAudio.warn("[卡车] 渲染器校验本身出错：%s", t.toString());
        }
    }

    @Override
    public void openAudioPlayerGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAudioPlayer(pos));
    }

    @Override
    public void openRedstoneDelayerGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiRedstoneDelayer(pos));
    }

    @Override
    public void openStructureWandGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiStructureWand(pos));
    }

    @Override
    public void openStructureLoaderGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiStructureLoader(pos));
    }

    @Override
    public void openComputerGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiRedstoneComputer(pos));
    }

    @Override
    public void openAlarmGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAlarm(pos));
    }

    @Override
    public void openBlastDoorGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiBlastDoor(pos));
    }

    @Override
    public void openAnnouncerGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAnnouncer(pos));
    }

    @Override
    public void openDmrGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiDmrController(pos));
    }

    /**
     * ★ 这一条必须存在：{@link CommonProxy#openF9Gui} 是空实现，
     * 不覆写的话右键控制柜就是"什么都不发生"，不报错、不刷日志。
     * 项目里在 {@code openRemotePanelGui} / {@code openTartarusGui} 上各踩过一次。
     */
    @Override
    public void openF9Gui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiF9Controller(pos));
    }

    /** 电梯控制柜界面。★ 见 {@link CommonProxy#openElevatorGui}：不覆写 = 右键毫无反应 */
    @Override
    public void openElevatorGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiElevatorController(pos));
    }

    /** 巨大标志牌的编辑界面。★ 见 {@link CommonProxy#openGiantSignGui}：不覆写 = 右键毫无反应 */
    @Override
    public void openGiantSignGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiGiantSign(pos));
    }

    @Override
    public void openMonitorCameraGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMonitorCamera(pos));
    }

    @Override
    public void openMonitorConsoleGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiMonitorConsole(pos));
    }

    @Override
    public void openAccessTerminalGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAccessTerminal(pos));
    }

    @Override
    public void openAirlockGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiAirlockController(pos));
    }

    /**
     * ★★ 这一条之前<b>漏了</b> —— 类顶部 import 了 {@code GuiRemotePanel}，
     * 却没写这个覆写。于是潜行右键面板时走的是 {@link CommonProxy} 里那个<b>空实现</b>，
     * 结果就是"面板的设置界面永远打不开"，而且不报错、不刷日志 ——
     * 玩家只能得出"这个面板不能设置"的结论（模式/频道都改不了，
     * 面板就永远停在默认的 DMR 模式 + 频道 1，看起来也就是"没用"）。
     * <p>
     * 所以这一条和"漏注册方块实体"是同一个面板的两条独立死线：
     * 注册那条让它<b>能存能 tick</b>，这一条让它<b>能被配置</b>。
     */
    @Override
    public void openRemotePanelGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiRemotePanel(pos));
    }

    /**
     * ★ 同一类漏覆写的第二处：{@code WandHandler} 拿着木斧潜行右键塔塔鲁斯时会调它，
     * 而客户端这边没有覆写 → 走 {@link CommonProxy} 的空实现 →
     * 塔塔鲁斯的设置界面同样打不开（半径 / 速度 / 警报灯全改不了）。
     */
    @Override
    public void openTartarusGui(BlockPos pos) {
        Minecraft.getMinecraft().displayGuiScreen(new GuiTartarus(pos));
    }

    @Override
    public void releaseCameraFeed(BlockPos pos) {
        com.rsaudio.client.CameraFeedRenderer.release(pos);
    }

    /**
     * 每 tick 的客户端逻辑：
     * <ul>
     *   <li>第一帧扫描音频目录并生成/启用动态资源包；</li>
     *   <li>之后每 tick 驱动 {@link ClientSoundPlayer#tick()}，负责判断"这首放完了"
     *       （MC 没有播放结束回调，只能自己对着 ogg 时长轮询）以及循环模式的兜底重播。</li>
     * </ul>
     */
    public static class ClientTickHandler {

        private boolean initialized = false;

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            // 弹窗计时和音频分片清扫放在"首帧初始化"之前：这两件事从第一刻就该跑，
            // 而动态资源包的扫描只在第一帧做一次并且会 return
            try {
                ClientNotice.tick();
                TtsClientPlayer.tick();
                // 监控摄像头的"名册"（按频道分组）。每 10 刻才真的重扫一次，
                // 所以放在这儿每 tick 调是免费的
                com.rsaudio.client.ClientMonitorCache.tick();
            } catch (Throwable t) {
                RsAudio.warn("弹窗/语音每 tick 逻辑异常: " + t);
            }
            if (!initialized) {
                initialized = true;
                try {
                    AudioLibrary.rescan();
                    boolean changed = DynamicSoundPack.generate();
                    if (changed || !DynamicSoundPack.isEnabled()) {
                        RsAudio.log("正在启用动态音频资源包 (%d 首音乐)", AudioLibrary.getEntries().size());
                        DynamicSoundPack.enableAndReload();
                    }
                } catch (Throwable t) {
                    RsAudio.warn("初始化音频资源包失败: " + t);
                }
                return;
            }
            try {
                ClientSoundPlayer.tick();
            } catch (Throwable t) {
                RsAudio.warn("音频结束检测异常: " + t);
            }
        }
    }

    /** 世界卸载时清掉正在播放的声音 / 弹窗 / 没拼完的语音分片 */
    public static class WorldUnloadHandler {
        @SubscribeEvent
        public void onWorldUnload(net.minecraftforge.event.world.WorldEvent.Unload event) {
            if (event.getWorld() != null && event.getWorld().isRemote) {
                ClientSoundPlayer.stopAll();
                // 不清的话：回到主界面还在念，而且半截音频会一直占着内存
                TtsClientPlayer.stopAll();
                ClientNotice.clear();
                // DMR 读数表也要清：换存档后旧坐标的读数会串到新世界的同一坐标上
                ClientDmrState.clear();
                // F9 读数表同理。★ 它和 DMR 是两张独立的表，只清一张 = 另一张照样串坐标
                com.rsaudio.client.ClientF9State.clear();
                // 电梯读数表同理，也是独立的一张（按控制柜坐标存快照）。
                // ★ 不清的话换存档后，新世界**同一坐标**的控制柜会显示上一个存档的轿厢位置
                com.rsaudio.client.ClientElevatorState.clear();
                // 监控：摄像头名册 + 所有离屏画布。
                // 画布不还的话，每换一次存档就泄漏一批显存（每个 320×180 的 FBO）
                com.rsaudio.client.ClientMonitorCache.clear();
                com.rsaudio.client.CameraFeedRenderer.releaseAll();
            }
        }
    }
}

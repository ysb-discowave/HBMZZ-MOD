package com.rsaudio.client;

import com.rsaudio.ModBlocks;
import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockAlarmLight;
import com.rsaudio.block.BlockElevatorController;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * 方块/物品模型的注册（客户端）。
 * <p>
 * {@code ModelRegistryEvent} 与 {@code TextureStitchEvent} 都是 Forge 在主事件总线上 post 的，
 * 所以本类只要挂上 {@code MinecraftForge.EVENT_BUS} 就行 —— 由
 * {@code ClientProxy#preInit} 显式 {@code register(ClientModelRegistry.class)} 完成，
 * 和方块/物品那边（{@code RsAudio#preInit} 注册 {@link com.rsaudio.ModBlocks}）同一条纪律：
 * <b>不依赖 {@code @Mod.EventBusSubscriber} 的注解扫描</b>。
 * <p>
 * 理由见 {@code ModBlocks} 的类注释：rsaudio 是同一枚 jar 里的第二个 {@code @Mod} 容器，
 * 注解注入一旦漏掉这个类，失败是完全静默的 —— 方块放得出来，手上和物品栏里是紫黑块，
 * 日志里一个字都没有。显式注册把这条链变成确定的。
 * <p>
 * ★ 每加一个有物品的方块，这里都要补一行 —— 漏了的症状是"方块放得出来，但手上/物品栏里是紫黑块"。
 */
public class ClientModelRegistry {

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        register();
    }

    /**
     * ★★ 把<b>渲染器直接引用</b>的贴图缝进方块图集。
     *
     * <h3>为什么必须要有这一段</h3>
     * 1.12.2 的方块图集只收"<b>被某个模型 JSON 引用过的</b>"贴图 —— 原版在启动时
     * 把所有 blockstate / model 扫一遍，把里面出现的贴图缝进图集，仅此而已。
     * 而 TESR 渲染器里的 {@code getAtlasSprite("rsaudio:blocks/xxx")} 是<b>运行时</b>取贴图，
     * 没有任何模型引用它们 → 原版根本不知道这些贴图的存在 → 图集里没有
     * → 运行时拿到的是 {@code missingno}（紫黑棋盘格）。
     * 玩家看到的就是"塔塔鲁斯舱盖乱码"：只有 {@code tartarus_hatch} 一张
     * （碰巧被 {@code tartarus_block.json} 引用过）是正常的，
     * 底板/危险条/外缘/枢纽全是紫黑 —— 14 张 TESR 贴图里 13 张一直没进过图集
     * （DMR 堆芯 2 张、列车车体 6 张同样中招）。
     *
     * <h3>为什么修在这里</h3>
     * 本类由 {@code ClientProxy#preInit} 显式挂到 {@code MinecraftForge.EVENT_BUS} 上，
     * 而 {@code TextureStitchEvent} 正是在主事件总线上 post 的 —— 不用额外注册。
     * 并且这里是客户端资源注册的枢纽，"要缝哪些贴图"和"要注册哪些模型"放一起最不容易漏。
     *
     * <h3>★ 和渲染器字面量的同步关系（verify_jar §[8] 兜底）</h3>
     * 这张表和各渲染器里写的贴图名必须一字不差：渲染器多写一个（文件没有）是紫黑，
     * 这里少缝一个也是紫黑。§[8] 会把本类的常量池和各渲染器的一起扫出来，
     * 与 EXPECT_TESR_TEXTURES 做差 —— 两边任何一边漂了都会被拦下。
     */
    @SubscribeEvent
    public static void onTextureStitch(TextureStitchEvent.Pre event) {
        // DMR 堆芯（DmrCoreRenderer / DmrSpecTextures）
        event.getMap().registerSprite(new ResourceLocation(DmrSpecTextures.CORE));
        event.getMap().registerSprite(new ResourceLocation(DmrSpecTextures.CHAMBER));
        // 塔塔鲁斯舱盖（TartarusRenderer）
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_hatch"));
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_underside"));
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_hazard"));
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_rim"));
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_hub_top"));
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_hub_side"));
        event.getMap().registerSprite(new ResourceLocation("rsaudio:blocks/tartarus_hub_well"));
        // 列车车体（TrainRenderer / TrainTextures）
        event.getMap().registerSprite(new ResourceLocation(TrainTextures.BODY_SIDE));
        event.getMap().registerSprite(new ResourceLocation(TrainTextures.BODY_ROOF));
        event.getMap().registerSprite(new ResourceLocation(TrainTextures.BODY_NOSE));
        event.getMap().registerSprite(new ResourceLocation(TrainTextures.BODY_END));
        event.getMap().registerSprite(new ResourceLocation(TrainTextures.BOGIE));
        event.getMap().registerSprite(new ResourceLocation(TrainTextures.DOOR));
    }

    public static void register() {
        registerItem(ModBlocks.AUDIO_PLAYER, "audio_player");
        registerItem(ModBlocks.REDSTONE_DELAYER, "redstone_delayer");
        registerItem(ModBlocks.STRUCTURE_LOADER, "structure_loader");
        registerItem(ModBlocks.REDSTONE_COMPUTER, "redstone_computer");
        registerItem(ModBlocks.BLAST_DOOR, "blast_door");
        registerItem(ModBlocks.ANNOUNCER, "announcer");
        for (int i = 0; i < ModBlocks.ALARM_LIGHTS.length; i++) {
            registerItem(ModBlocks.ALARM_LIGHTS[i], "alarm_" + BlockAlarmLight.COLOR_NAMES[i]);
        }
        // DMR 反应堆三件套。外壳和堆芯也走这里 —— 它们的 blockstate 是无属性的 "normal"，
        // 但物品模型仍旧得指到 models/item/<name>.json，两件事互不相干、缺一不可。
        registerItem(ModBlocks.DMR_CONTROLLER, "dmr_controller");
        registerItem(ModBlocks.DMR_CASING, "dmr_casing");
        registerItem(ModBlocks.DMR_CORE, "dmr_core");
        // F9 压水堆三件套。和 DMR 一样各注册一行 ——
        //   ★ 压力容器（f9_core）是 LIT 方块、外壳（f9_casing）是无属性方块，
        //     但"方块长什么样"和"物品栏里那份长什么样"是两条独立的线，缺一不可。
        //     漏了这三行，方块放得出来、手上和物品栏里是紫黑块。
        //   ★ 这里**没有**任何贴图登记：F9 刻意不走 INVISIBLE+TESR，
        //     全部贴图都由 models/*.json 引用 → 图集自动收，不需要 registerSprite。
        registerItem(ModBlocks.F9_CONTROLLER, "f9_controller");
        registerItem(ModBlocks.F9_CASING, "f9_casing");
        registerItem(ModBlocks.F9_CORE, "f9_core");
        // 电梯三件套（井道 / 层门 / 三种控制柜）。
        //   ★★ 这里**没有** ELEVATOR_CAR：轿厢没有 ItemBlock（它只由控制柜摆放和搬动），
        //      给它注册物品模型反而会变成"孤儿模型" —— verify_jar 的 FORBIDDEN_ITEM_MODELS
        //      就是拦这个的。
        //   ★ 井道/轿厢是无属性方块，层门是 FACING+OPEN、控制柜是 FACING+LIT ——
        //     但"方块长什么样"和"物品栏里那份长什么样"是两条独立的线，缺一不可。
        //     漏了这几行，方块放得出来、手上和物品栏里就是紫黑块（本项目已踩三次）。
        for (int i = 0; i < ModBlocks.ELEVATOR_CONTROLLERS.length; i++) {
            registerItem(ModBlocks.ELEVATOR_CONTROLLERS[i],
                    "elevator_controller_" + BlockElevatorController.TYPE_NAMES[i]);
        }
        registerItem(ModBlocks.ELEVATOR_SHAFT, "elevator_shaft");
        registerItem(ModBlocks.ELEVATOR_DOOR, "elevator_door");
        // 监控两件套：摄像头有 6 个朝向变体、控制台有 4 个，但物品栏模型只是一个
        registerItem(ModBlocks.MONITOR_CAMERA, "monitor_camera");
        registerItem(ModBlocks.MONITOR_CONSOLE, "monitor_console");
        // FAAS 门禁终端：Java 是 FACING + LOCKED 两个属性。
        //   ★ 这两个属性在 blockstates 里各有一套变体，LOCKED=true 用另一张正面贴图
        //     （access_terminal_locked）—— "指示灯靠正面贴图"说的就是这个：
        //     灯是画在贴图里的，不是渲染器画的。下面这行只管<b>物品栏</b>模型，
        //     和方块放在世界里长什么样无关（那由 blockstates 决定）。
        registerItem(ModBlocks.ACCESS_TERMINAL, "access_terminal");
        // 气闸控制器：FACING + ACTIVE 两个属性；ACTIVE=true 走 airlock_controller_active
        //   模型（正面换成 airlock_controller_front_active，那颗"循环中"的灯就在贴图上）。
        //   ★ 曾经写成"变体共用同一个模型"，是错的 —— 那样写循环指示灯永远不亮。
        registerItem(ModBlocks.AIRLOCK_CONTROLLER, "airlock_controller");

        // ★ 塔塔鲁斯舱盖门：漏了这行 = 物品模型没注册 = 手上/物品栏里是紫黑块。
        //   （方块本身放世界里是正常的，只有手上那一份是紫黑的 —— 这个坑本项目踩过两次了。）
        registerItem(ModBlocks.TARTARUS, "tartarus");
        registerItem(ModBlocks.REMOTE_PANEL, "remote_panel");

        // ★ 列车两件套。轨道有 16 个 shape 变体、站台是无属性方块 —— 但不管有几个变体，
        //   物品栏里都只有一份，所以各注册一行即可。
        //   漏了这两行和漏了上面任何一行是同一个症状：方块放得出来，手上是紫黑块。
        registerItem(ModBlocks.TRAIN_RAIL, "train_rail");
        registerItem(ModBlocks.TRAIN_STATION, "train_station");
        // 装饰组三件（巨大管道 / 排风扇 / 巨大标志牌）。
        //   ★ 三块都有 ItemBlock，所以三行都要有 —— 少一行就是"方块放得出来、
        //     手上和物品栏里是紫黑块"（本项目已在 F9、电梯上各踩过一次）。
        //   ★ 对照：电梯轿厢没有 ItemBlock，所以上面**没有** elevator_car 那一行。
        //     这里是反过来的一半：管道/风扇/牌子都是玩家要拿在手上摆的装饰件。
        registerItem(ModBlocks.GIANT_PIPE, "giant_pipe");
        registerItem(ModBlocks.EXHAUST_FAN, "exhaust_fan");
        registerItem(ModBlocks.GIANT_SIGN, "giant_sign");
        // 起组团两件（起重机 / 卡车车位）。★ 和上面三行是同一条死线：
        //   漏一行 = "方块放得出来、手上和物品栏里是紫黑块"（本项目已踩多次）。
        registerItem(ModBlocks.CRANE, "crane");
        registerItem(ModBlocks.TRUCK_PAD, "truck_pad");
    }

    private static void registerItem(Block block, String name) {
        Item item = Item.getItemFromBlock(block);
        if (item != null) {
            ModelLoader.setCustomModelResourceLocation(item, 0,
                    new ModelResourceLocation(RsAudio.MODID + ":" + name, "inventory"));
        }
    }
}

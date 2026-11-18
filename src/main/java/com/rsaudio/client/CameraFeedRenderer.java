package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockMonitorCamera;
import com.rsaudio.tile.TileMonitorCamera;
import com.rsaudio.util.Gimbal;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 监控画面的离屏渲染引擎。
 *
 * <h2>原理</h2>
 * 摄像头方块上并没有"图像"这个东西。真正发生的是：客户端把世界
 * <b>从摄像头的位置、朝着摄像头看的方向再渲染一遍</b>，画进一块自己的
 * {@link Framebuffer}（= 显卡上的一张离屏画布），然后把那张画布当成普通贴图
 * 贴到监控控制台的屏幕/界面上。所以画面里的水会流动、怪会走路 —— 这是真·实时画面。
 *
 * <h2>怎么"从摄像头看一遍世界"</h2>
 * 不自己拼投影矩阵，而是<b>借用原版的整条世界渲染链</b>：
 * <pre>
 *   mc.displayWidth/Height     ← 换成离屏画布的大小（原版用它算视口和宽高比）
 *   mc.renderViewEntity        ← 换成一个站在摄像头位置的"假实体"
 *   mc.gameSettings.hideGUI    ← 临时置 true：否则第一人称的"手"会被画进监控画面
 *   mc.gameSettings.fovSetting ← 换成摄像头自己的视野
 *   framebuffer.bindFramebuffer(true)
 *   mc.entityRenderer.renderWorld(partialTicks, System.nanoTime())
 *   framebuffer.unbindFramebuffer()
 *   ……以上全部还原
 * </pre>
 * 好处是：光照贴图、雾、天空、云、雨、透明层排序、实体模型 —— 全部天然正确，
 * 因为走的还是原版那条路，我们只是换了一只"眼睛"。
 * <p>
 * 反汇编核对过 {@code EntityRenderer.renderWorldPass}：它自己会做
 * {@code viewport → setupCameraTransform → ActiveRenderInfo.updateRenderInfo →
 * setupTerrain → renderBlockLayer ×3 → renderEntities}，
 * 所以外部<b>只需要</b>把上面那几个字段摆好，别的都不用管。
 *
 * <h2>★ 三条必须遵守的铁律（都踩过）</h2>
 * <ol>
 *   <li><b>绝不能在 {@code RenderWorldLastEvent} 里递归。</b>
 *       反汇编 {@code renderWorldPass} 可见它在偏移 1325 处调用
 *       {@code ForgeHooksClient.dispatchRenderLast} —— 也就是说
 *       {@code RenderWorldLastEvent} 是在 {@code renderWorld} <b>内部</b>触发的。
 *       离屏渲染自己又调 {@code renderWorld}，就会再触发一次本事件 → 无限递归 → 爆栈崩游戏。
 *       所以 {@link #renderingOffscreen} 这道闸门不是"优化"，是<b>防崩</b>。</li>
 *   <li><b>改了 {@code mc.displayWidth/Height} 必须还原。</b>
 *       原版拿它算视口、宽高比，帧末还要用它把主画布 blit 到屏幕；
 *       忘了还原 → 整个屏幕的画面缩到左上角一小块。</li>
 *   <li><b>渲染完要把 GL 状态摆回"世界渲染尾段"的样子。</b>
 *       本渲染发生在 {@code renderWorldPass} 的最后一段，紧接着原版还要
 *       {@code clear(GL_DEPTH_BUFFER_BIT)} + 画第一人称的手；此时若
 *       {@code depthMask(false)}，深度清除会被静默忽略，玩家的手就会糊在世界上。
 *       这里也<b>不用</b> {@code pushAttrib/popAttrib}：1.12.2 的
 *       {@code GlStateManager.pushAttrib} 只是一句裸的 {@code glPushAttrib(8256)}，
 *       不会同步它自己缓存的状态位，反而会把缓存和真实 GL 状态搞不一致。</li>
 * </ol>
 *
 * <h2>按需渲染</h2>
 * 只有在"有人在看"的时候才渲染 —— 有人开着监控界面，或者附近有控制台方块在放画面。
 * 没人看的时候一颗 GPU 周期都不花。请求由界面/方块渲染器在绘制时登记，
 * 统一在每帧的 {@link #flushWanted} 里落地。
 */
public final class CameraFeedRenderer {

    /** 离屏画布分辨率。代价主要在方块几何、不在像素，所以这个值不用太大 */
    public static final int WIDTH = 320;
    public static final int HEIGHT = 180;

    /**
     * 每几刻渲染一帧（1 = 20 FPS，2 = 10 FPS）。
     * <p>
     * 一次离屏渲染 ≈ 多画一遍世界的地形绘制调用，是这整套功能里最贵的一步。
     * 10 FPS 对监控画面完全够"活着"的感觉，把开销砍掉一半。想更顺滑就改成 1。
     */
    public static final int INTERVAL_TICKS = 2;

    /** 单帧最多渲染几个画面。一屏挂十几个监控时，防止帧率被吃光 */
    public static final int MAX_RENDERS_PER_FRAME = 1;

    /** 画面里"谁被拍到了"的探测半径（格） */
    public static final double DETECT_RANGE = 40.0D;
    /** 探测列表最多列几个实体 */
    public static final int DETECT_MAX = 8;

    // ------------------------------------------------------------------ 内部状态

    /** 重入闸门：离屏渲染进行中为 true。见类注释铁律第 1 条 —— 这是防崩，不是优化 */
    private static boolean renderingOffscreen = false;

    /** 本帧被请求渲染的摄像头（存 BlockPos.toLong()，省得反复装箱） */
    private static final Set<Long> WANTED = new LinkedHashSet<Long>();

    private static final Map<Long, Feed> FEEDS = new HashMap<Long, Feed>();

    /** 轮转指针：请求数超过每帧预算时大家轮流上，而不是永远饿死后面的 */
    private static int roundRobin = 0;

    /** 站在摄像头位置的"假实体"。复用一个就够，不需要每帧 new */
    private static CameraProbe probe = null;

    private CameraFeedRenderer() {
    }

    /** 离屏渲染进行中（方块渲染器据此"别把监控屏幕画进监控画面里"） */
    public static boolean isRenderingOffscreen() {
        return renderingOffscreen;
    }

    // ------------------------------------------------------------------ 对外接口

    /**
     * 登记"这一帧我需要这个摄像头的画面"。
     * 界面和方块渲染器在绘制时调用；真正的渲染统一在 {@link #flushWanted} 里做。
     */
    public static void request(BlockPos cameraPos) {
        if (cameraPos != null) {
            WANTED.add(Long.valueOf(cameraPos.toLong()));
        }
    }

    /** 画面是否已经出过至少一帧（没有就显示"信号建立中"） */
    public static boolean isReady(BlockPos cameraPos) {
        Feed feed = feedOrNull(cameraPos);
        return feed != null && feed.everRendered;
    }

    /** 画面贴图 id（离屏画布的彩色纹理），未就绪返回 0 */
    public static int getTexture(BlockPos cameraPos) {
        Feed feed = feedOrNull(cameraPos);
        if (feed == null || feed.buffer == null || !feed.everRendered) {
            return 0;
        }
        return feed.buffer.framebufferTexture;
    }

    /** 这块摄像头上一次渲染时"画面里都有谁"（已格式化成可显示的文字） */
    public static List<String> getSeen(BlockPos cameraPos) {
        Feed feed = feedOrNull(cameraPos);
        return feed == null ? Collections.<String>emptyList() : feed.seen;
    }

    /** 画面的宽高比（供界面按比例布局） */
    public static float getAspect() {
        return (float) WIDTH / (float) HEIGHT;
    }

    /** 画面尺寸的展示文本 */
    public static String resolutionText() {
        return String.format(Locale.ROOT, "%d×%d", WIDTH, HEIGHT);
    }

    /** 释放一个位置占用的离屏画布（方块被拆 / 区块卸载时） */
    public static void release(BlockPos pos) {
        if (pos == null) {
            return;
        }
        Long key = Long.valueOf(pos.toLong());
        Feed feed = FEEDS.remove(key);
        if (feed != null) {
            feed.dispose();
        }
        WANTED.remove(key);
    }

    /** 世界卸载时把所有离屏画布还给显卡（不还的话换存档会一路泄漏显存） */
    public static void releaseAll() {
        for (Feed feed : FEEDS.values()) {
            feed.dispose();
        }
        FEEDS.clear();
        WANTED.clear();
        probe = null;
        roundRobin = 0;
        renderingOffscreen = false;
    }

    // ------------------------------------------------------------------ 每帧落地

    /**
     * 把这一帧登记过的画面都渲染一遍。
     * <p>
     * 挂在 {@link RenderWorldLastEvent} 上：这是"世界刚画完、界面还没开始画"的时刻，
     * 位置最合适 —— 画面能在<b>同一帧</b>的方块屏幕上、以及下一帧的界面里用上。
     */
    public static void flushWanted(float partialTicks) {
        if (renderingOffscreen) {
            // 见类注释铁律第 1 条
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null || WANTED.isEmpty()) {
            WANTED.clear();
            return;
        }
        long tick = mc.world.getTotalWorldTime();
        List<Long> keys = new ArrayList<Long>(WANTED);
        int count = keys.size();
        int done = 0;
        for (int i = 0; i < count && done < MAX_RENDERS_PER_FRAME; i++) {
            Long key = keys.get((roundRobin + i) % count);
            Feed feed = FEEDS.get(key);
            if (feed == null) {
                feed = new Feed();
                FEEDS.put(key, feed);
            }
            // ★ 用 everRendered 做首帧判断，不能写成 `tick - lastTick < N`：
            //   lastTick 初值若是 Long.MIN_VALUE，tick - lastTick 会溢出成负数，
            //   于是"永远还没到时候"，画面一辈子出不来
            if (feed.everRendered && tick - feed.lastTick < INTERVAL_TICKS) {
                continue;
            }
            TileEntity te = mc.world.getTileEntity(BlockPos.fromLong(key.longValue()));
            if (!(te instanceof TileMonitorCamera)) {
                continue;
            }
            if (renderOffscreen((TileMonitorCamera) te, feed, partialTicks)) {
                done++;
            }
        }
        roundRobin = count == 0 ? 0 : (roundRobin + MAX_RENDERS_PER_FRAME) % count;
        WANTED.clear();
    }

    // ------------------------------------------------------------------ 真正的离屏渲染

    private static boolean renderOffscreen(TileMonitorCamera camera, Feed feed, float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || mc.player == null || mc.gameSettings == null) {
            return false;
        }
        if (!world.isBlockLoaded(camera.getPos())) {
            // 摄像头那一块还没加载：没有地形可画，画出来只会是一片虚空
            feed.lastTick = world.getTotalWorldTime();
            return false;
        }

        if (feed.buffer == null) {
            try {
                feed.buffer = new Framebuffer(WIDTH, HEIGHT, true);
                // 线性过滤：画面缩放显示时才不会是马赛克
                feed.buffer.setFramebufferFilter(GL11.GL_LINEAR);
            } catch (Throwable t) {
                RsAudio.warn("创建监控离屏画布失败（本帧跳过）: " + t);
                feed.buffer = null;
                feed.lastTick = world.getTotalWorldTime();
                return false;
            }
        }

        Entity oldViewEntity = mc.getRenderViewEntity();
        int oldDisplayWidth = mc.displayWidth;
        int oldDisplayHeight = mc.displayHeight;
        boolean oldHideGui = mc.gameSettings.hideGUI;
        int oldThirdPerson = mc.gameSettings.thirdPersonView;
        float oldFov = mc.gameSettings.fovSetting;
        boolean oldProfiling = mc.mcProfiler.profilingEnabled;
        // ★ 这两个也要存：renderWorld 里的 getMouseOver 会按"当前视点实体"重算它们，
        //   不还原的话，玩家下一帧的准星 / 挖方块判定会短暂指到摄像头看的地方去
        RayTraceResult oldMouseOver = mc.objectMouseOver;
        Entity oldPointed = mc.pointedEntity;

        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();

        renderingOffscreen = true;
        boolean ok = false;
        try {
            mc.displayWidth = WIDTH;
            mc.displayHeight = HEIGHT;
            mc.gameSettings.hideGUI = true;
            mc.gameSettings.thirdPersonView = 0;
            mc.gameSettings.fovSetting = camera.getFov();
            // 关掉原版 profiler：renderWorldPass 里的 startSection/endStartSection 是配对的，
            // 但那是"从帧首建立"的配对，我们插在中间会把它原有的嵌套结构打断
            mc.mcProfiler.profilingEnabled = false;
            mc.setRenderViewEntity(probeEntity(world, camera));

            feed.buffer.bindFramebuffer(true);
            GlStateManager.viewport(0, 0, WIDTH, HEIGHT);
            mc.entityRenderer.renderWorld(partialTicks, System.nanoTime());
            feed.buffer.unbindFramebuffer();
            ok = true;
        } catch (Throwable t) {
            RsAudio.warn("监控画面渲染失败（已跳过这一帧）: " + t);
        } finally {
            renderingOffscreen = false;
            mc.setRenderViewEntity(oldViewEntity);
            mc.displayWidth = oldDisplayWidth;
            mc.displayHeight = oldDisplayHeight;
            mc.gameSettings.hideGUI = oldHideGui;
            mc.gameSettings.thirdPersonView = oldThirdPerson;
            mc.gameSettings.fovSetting = oldFov;
            mc.mcProfiler.profilingEnabled = oldProfiling;
            mc.objectMouseOver = oldMouseOver;
            mc.pointedEntity = oldPointed;

            // 绘制目标还给主画面（bindFramebuffer(true) 同时会把视口改回窗口大小）
            Framebuffer main = mc.getFramebuffer();
            if (main != null) {
                main.bindFramebuffer(true);
            }
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            restoreWorldPassTailState();
        }

        feed.lastTick = world.getTotalWorldTime();
        if (ok) {
            feed.everRendered = true;
            feed.seen = detectEntities(camera);
        }
        return ok;
    }

    /**
     * 把 GL 状态摆回 {@code renderWorldPass} 在 {@code dispatchRenderLast} 之后紧接着要用的样子。
     * <p>
     * 对照原版反汇编：{@code dispatchRenderLast} 之前原版已经设过
     * {@code depthMask(true) → enableCull() → disableBlend() → disableFog()}，
     * 之后是 {@code clear(GL_DEPTH_BUFFER_BIT)} + {@code renderHand()}。
     * 这里把被离屏渲染改坏的那几项逐条摆回去 —— 用 GlStateManager 的 setter 而不是
     * {@code glPopAttrib}，因为 setter 会同时更新 GlStateManager 自己的缓存，
     * 不会出现"缓存说已经开了、其实并没有开"的错位。
     */
    private static void restoreWorldPassTailState() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableCull();
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.disableFog();
        GlStateManager.disableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.bindTexture(0);
    }

    // ------------------------------------------------------------------ 假实体

    /**
     * 站在摄像头位置的"假实体"。
     * <p>
     * 为什么不直接把 {@code Minecraft.player} 挪过去？因为那是真的把玩家瞬移出去 ——
     * 服务端会看到玩家超距移动，而且挪完还得挪回来，风险太大。
     * 这里造一个<b>从未进过世界</b>的实体：只用来给原版当"眼睛"，
     * 它不在任何实体列表里，也不会被渲染（{@link #shouldRenderInPass} 恒 false）。
     * <p>
     * {@code getEyeHeight()} 返回 0 —— 原版 {@code orientCamera} 算的是
     * {@code 相机Y = posY + getEyeHeight()}，返回 0 意味着我们给 {@code posY} 什么、
     * 相机就在什么高度，不用再心算"这是脚底还是眼睛"。
     */
    private static final class CameraProbe extends Entity {

        CameraProbe(World world) {
            super(world);
            this.noClip = true;
            this.preventEntitySpawning = false;
            this.setSize(0.2F, 0.2F);
        }

        @Override
        public float getEyeHeight() {
            return 0.0F;
        }

        @Override
        public boolean shouldRenderInPass(int pass) {
            return false;
        }

        @Override
        public boolean canBeCollidedWith() {
            return false;
        }

        @Override
        public boolean canBePushed() {
            return false;
        }

        /**
         * {@link Entity} 一共留了三个抽象方法：{@code entityInit} /
         * {@code writeEntityToNBT} / {@code readEntityFromNBT}（用
         * {@code tools/api_run.py} 反射列出 {@code Entity.class} 的 abstract 方法可复现）。
         * <p>
         * ★ 全部留空，<b>不要 throw</b>：这个假实体从不进世界、也永远不会被存档，
         * 正常路径下根本不会有人调它们。但万一原版某条兜底路径顺手调了一下，
         * 抛异常会把整趟离屏渲染打断（画面直接黑掉），而"什么都不做"是安全的。
         */
        @Override
        protected void entityInit() {
        }

        @Override
        public void writeEntityToNBT(NBTTagCompound compound) {
        }

        @Override
        public void readEntityFromNBT(NBTTagCompound compound) {
        }
    }

    /** 把假实体摆到摄像头的"眼睛"位置和朝向上 */
    private static Entity probeEntity(World world, TileMonitorCamera camera) {
        if (probe == null || probe.world != world) {
            probe = new CameraProbe(world);
        }
        BlockPos pos = camera.getPos();
        EnumFacing facing = facingOf(world, pos);

        // 眼睛位置：从方块中心往镜头方向推 0.45 格，正好在小盒子（厚 5/16 格）外侧一点。
        // 不推出去的话，视点落在方块内部，画面第一眼看过去是自己的内壁
        double ex = pos.getX() + 0.5D + facing.getFrontOffsetX() * 0.45D;
        double ey = pos.getY() + 0.5D + facing.getFrontOffsetY() * 0.45D;
        double ez = pos.getZ() + 0.5D + facing.getFrontOffsetZ() * 0.45D;

        // ★ yaw 的方向约定（从原版 Entity.getVectorForRotation 反推）：
        //     yaw=0 → 朝 +Z(南)，90 → 朝 -X(西)，180 → 朝 -Z(北)，270 → 朝 +X(东)
        //   而 EnumFacing.getHorizontalAngle() 返回的正是这套值
        //   （SOUTH=0 / WEST=90 / NORTH=180 / EAST=270），所以直接用，不用自己凑三角函数。
        //   pitch：原版约定正数 = 往下看。
        //   最后再叠上玩家设的"云台偏移"，见 rotationOf()。
        float[] rot = rotationOf(camera, facing);
        float yaw = rot[0];
        float pitch = rot[1];

        // ★ prevPos / prevRotation / lastTickPos 必须和当前值一致：
        //   原版按 partialTicks 在 prev 与当前值之间插值，
        //   不一致的话画面会带着"上一帧的位置"抖动、拖影。
        probe.setLocationAndAngles(ex, ey, ez, yaw, pitch);
        probe.prevPosX = ex;
        probe.prevPosY = ey;
        probe.prevPosZ = ez;
        probe.lastTickPosX = ex;
        probe.lastTickPosY = ey;
        probe.lastTickPosZ = ez;
        probe.prevRotationYaw = yaw;
        probe.prevRotationPitch = pitch;
        return probe;
    }

    private static EnumFacing facingOf(World world, BlockPos pos) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockMonitorCamera) {
            return state.getValue(BlockMonitorCamera.FACING);
        }
        return EnumFacing.NORTH;
    }

    // ------------------------------------------------------------------ 云台角度

    /**
     * 基础朝向（不含云台偏移）换算成的 {yaw, pitch}，单位是度。
     * <p>
     * {@code EnumFacing.getHorizontalAngle()} 返回的正好是原版那套 yaw 约定
     * （SOUTH=0 / WEST=90 / NORTH=180 / EAST=270），所以直接用，不用自己凑三角函数。
     * UP / DOWN 那两个退化情形给 (0, ±90) —— yaw 在这里随便取，反正竖直视线与 yaw 无关。
     */
    private static float[] baseRotation(EnumFacing facing) {
        if (facing == EnumFacing.UP) {
            return new float[] { 0.0F, -90.0F };
        }
        if (facing == EnumFacing.DOWN) {
            return new float[] { 0.0F, 90.0F };
        }
        return new float[] { facing.getHorizontalAngle(), 0.0F };
    }

    /**
     * 摄像头<b>实际</b>看向的 {yaw, pitch} = 基础朝向 + 玩家设的云台偏移。
     * <p>
     * 叠加和夹取的规则在 {@link Gimbal} 里（那份是能被
     * {@code tools/check_gimbal_core.py} 真实跑起来验证的），这里只负责把
     * "方块朝向"翻译成基础角度。
     */
    private static float[] rotationOf(TileMonitorCamera camera, EnumFacing facing) {
        float[] base = baseRotation(facing);
        return Gimbal.rotation(base[0], base[1], camera.getYawOffset(), camera.getPitchOffset());
    }

    /**
     * 摄像头实际视线方向的单位向量，包成 {@link Vec3d} 方便做点积。
     * <p>
     * ★ 公式来自 {@link Gimbal#lookVector(float, float)}，和原版
     * {@code Entity.getVectorForRotation} 逐条一致（已反汇编核对）。必须和渲染
     * 走同一个公式，否则"画面里明明站着个人，控制台却说没人"。
     */
    private static Vec3d lookVector(TileMonitorCamera camera, EnumFacing facing) {
        float[] rot = rotationOf(camera, facing);
        double[] v = Gimbal.lookVector(rot[1], rot[0]);
        return new Vec3d(v[0], v[1], v[2]);
    }

    // ------------------------------------------------------------------ 画面里有谁

    /**
     * 找出摄像头视野里的实体。
     * <p>
     * 这一步是"监控"和"照相机"的区别：控制台上直接能看到"3 号频道拍到 2 个人"，
     * 不用自己盯着小屏幕数。纯客户端计算 —— 客户端本来就有实体列表，
     * 而且服务端算了也没用（画面本来就是各端自己渲染的）。
     * <p>
     * ★ 判定用的是"真正的视锥"而不是"视线夹角 &lt; fov/2"那个圆锥近似，
     * 原因见 {@link Gimbal#insideFrustum}。那段几何在 {@code Gimbal} 里，
     * 所以能被 {@code tools/check_gimbal_core.py} 真跑起来验证。
     */
    private static List<String> detectEntities(TileMonitorCamera camera) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        List<String> result = new ArrayList<String>();
        if (world == null) {
            return result;
        }
        BlockPos pos = camera.getPos();
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - DETECT_RANGE, pos.getY() - DETECT_RANGE, pos.getZ() - DETECT_RANGE,
                pos.getX() + DETECT_RANGE + 1.0D, pos.getY() + DETECT_RANGE + 1.0D,
                pos.getZ() + DETECT_RANGE + 1.0D);
        Vec3d eye = new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        Vec3d lookVec = lookVector(camera, facingOf(world, pos));
        double[] look = new double[] { lookVec.x, lookVec.y, lookVec.z };

        // 半纵角 = fov 的一半；半横角由宽高比放大，和原版 fovSetting → 投影矩阵的口径一致
        double tanVertical = Gimbal.tanHalfVertical(camera.getFov());
        double tanHorizontal = Gimbal.tanHalfHorizontal(camera.getFov(), WIDTH, HEIGHT);

        List<Hit> hits = new ArrayList<Hit>();
        for (Entity entity : world.getEntitiesWithinAABB(Entity.class, box)) {
            if (entity == null || entity.isInvisible()) {
                continue;
            }
            // 死掉的只有掉落物还值得看一眼，其它尸体不报
            if (!entity.isEntityAlive() && !(entity instanceof EntityItem)) {
                continue;
            }
            Vec3d target = new Vec3d(entity.posX, entity.posY + entity.getEyeHeight(), entity.posZ);
            Vec3d delta = target.subtract(eye);
            double distance = delta.lengthVector();
            if (distance < 0.5D || distance > DETECT_RANGE) {
                continue;
            }
            if (!Gimbal.insideFrustum(look, new double[] { delta.x, delta.y, delta.z },
                    tanHorizontal, tanVertical)) {
                continue;
            }
            hits.add(new Hit(entity, distance));
        }
        // 按离摄像头远近排序，近的排前面（监控最关心的是"谁凑过来了"）
        Collections.sort(hits, new Comparator<Hit>() {
            @Override
            public int compare(Hit a, Hit b) {
                return Double.compare(a.distance, b.distance);
            }
        });
        int limit = Math.min(hits.size(), DETECT_MAX);
        for (int i = 0; i < limit; i++) {
            Hit hit = hits.get(i);
            result.add(entityLabel(hit.entity) + " · " + Math.round(hit.distance) + "m");
        }
        return result;
    }

    /** 探测结果：实体 + 它离摄像头的距离 */
    private static final class Hit {
        final Entity entity;
        final double distance;

        Hit(Entity entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }
    }

    private static String entityLabel(Entity entity) {
        if (entity instanceof EntityPlayer) {
            String name = ((EntityPlayer) entity).getName();
            return name == null ? "?" : name;
        }
        String name = entity.getName();
        return name == null || name.isEmpty() ? entity.getClass().getSimpleName() : name;
    }

    // ------------------------------------------------------------------ 缓冲

    /** 一个摄像头对应一块离屏画布 */
    private static final class Feed {

        Framebuffer buffer;
        /** 上一次渲染的世界时刻，用来限流（同一刻不重复渲染） */
        long lastTick = 0L;
        boolean everRendered = false;
        List<String> seen = Collections.emptyList();

        void dispose() {
            if (buffer != null) {
                try {
                    buffer.deleteFramebuffer();
                } catch (Throwable t) {
                    RsAudio.warn("释放监控离屏画布失败: " + t);
                }
                buffer = null;
            }
            everRendered = false;
            seen = Collections.emptyList();
        }
    }

    private static Feed feedOrNull(BlockPos pos) {
        return pos == null ? null : FEEDS.get(Long.valueOf(pos.toLong()));
    }

    /** 当前有多少块画布占着显存（调试用） */
    public static int activeFeedCount() {
        int count = 0;
        for (Feed feed : FEEDS.values()) {
            if (feed.buffer != null) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 事件挂载

    /** 事件订阅器：由客户端代理在 preInit 时注册 */
    public static final class Hook {

        @SubscribeEvent
        public void onRenderWorldLast(RenderWorldLastEvent event) {
            flushWanted(event.getPartialTicks());
        }
    }

    /** 便捷注册（免得客户端代理里还要 import 一堆东西） */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new Hook());
    }
}

package com.rsaudio.client;

import com.rsaudio.block.BlockDmrCore;
import com.rsaudio.tile.TileDmrCore;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

/**
 * DMR 堆芯的渲染器：腔体中央那团<b>缓慢自转的等离子体</b>，外加一圈约束环。
 *
 * <h3>它解决的是什么问题</h3>
 * 之前 DMR 就是三个静态方块（外壳 / 控制柜 / 堆芯），堆芯是一块会发光的砖。
 * 玩家的评价是"<i>根本不像广播那样，没有动画，也不够大</i>" —— 两条都对：
 * <ul>
 *   <li><b>没有动画</b>：方块模型烘死在区块网格里，没有任何逐帧钩子。
 *       本渲染器每帧按真实 dt 推进相位，球体自转 + 呼吸脉动 + 约束环反向旋转；</li>
 *   <li><b>不够大</b>：原来堆芯只有 1 格。现在等离子体本体直径约 1.9 格，
 *       再加上一圈半径 2.6 格的约束环，占满整个 3×3×3 外壳的内部空间。</li>
 * </ul>
 *
 * <h3>四种状态，四种观感</h3>
 * <ol>
 *   <li><b>未点火</b>：暗红色的小球，慢慢转，像冷却中的余烬；</li>
 *   <li><b>运行中</b>：青白色明亮等离子体，自转加快，脉动清晰，约束环亮起；</li>
 *   <li><b>熔毁 P1/P2</b>：转为刺目的橙红→白热，球体剧烈抖动，约束环失稳（半径抖动）；</li>
 *   <li><b>内爆后</b>：方块已被替换，渲染器自然不再被调用。</li>
 * </ol>
 *
 * <h3>★ 两条踩过的坑（写新 TESR 时照抄）</h3>
 * <ol>
 *   <li><b>贴图名必须带 {@code blocks/}</b>。{@code getAtlasSprite("rsaudio:xxx")}
 *       会<b>静默</b>返回 {@code missingno}（紫黑块），不报错、不刷日志。
 *       正确写法是 {@code "rsaudio:blocks/xxx"}。</li>
 *   <li><b>离屏摄像渲染那一趟也要挡掉</b>。监控摄像机的画面是"把世界重画一遍"，
 *       如果不挡，堆芯会在监控画面里画第二次，出现重影/闪烁。
 *       见 {@link CameraFeedRenderer#isRenderingOffscreen()}。</li>
 * </ol>
 */
public class DmrCoreRenderer extends TileEntitySpecialRenderer<TileDmrCore> {

    /** 基础可视距离。等离子体不大，96 格足够；再远本来也看不清细节 */
    private static final double VIEW_RANGE = 96.0D;

    // ---- 等离子体本体
    /** 等离子体半径（格）。★ 这就是"够不够大"的主开关 —— 直径 = 2 × 这个数 */
    private static final double PLASMA_R = 0.95D;
    /** 球体的纬线分段数（越多越圆，代价是顶点数） */
    private static final int SPHERE_LAT = 20;
    /** 球体的经线分段数 */
    private static final int SPHERE_LON = 32;

    // ---- 约束环（环绕等离子体的两道磁约束线圈）
    /** 约束环半径（格）—— 比等离子体大不少，是"占满腔体"的主要贡献者 */
    private static final double RING_R = 2.55D;
    /** 约束环的管径（格） */
    private static final double RING_TUBE = 0.085D;
    /** 每道环的周向分段 */
    private static final int RING_SEG = 72;

    // ---- 颜色（未点火 / 运行 / 熔毁）
    private static final float[] COLD_IN = {0.55F, 0.14F, 0.10F};
    private static final float[] COLD_OUT = {0.18F, 0.04F, 0.03F};
    private static final float[] RUN_IN = {0.90F, 0.99F, 1.00F};
    private static final float[] RUN_OUT = {0.18F, 0.62F, 0.92F};
    private static final float[] MELT_IN = {1.00F, 0.97F, 0.72F};
    private static final float[] MELT_OUT = {0.95F, 0.28F, 0.04F};

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(TileDmrCore te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        // ★ 离屏（监控摄像机）那一趟挡掉，否则监控画面里会重影
        if (CameraFeedRenderer.isRenderingOffscreen()) {
            return;
        }
        if (destroyStage >= 0 || te == null || te.getWorld() == null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        BlockPos pos = te.getPos();
        if (mc.player.getDistanceSqToCenter(pos) > VIEW_RANGE * VIEW_RANGE) {
            return;
        }

        IBlockState state = te.getWorld().getBlockState(pos);
        if (!(state.getBlock() instanceof BlockDmrCore)) {
            return;
        }
        boolean melting = state.getValue(BlockDmrCore.MELTDOWN);
        boolean lit = state.getValue(BlockDmrCore.LIT);

        // ★ 客户端的强度平滑（见 TileDmrCore.tickClient）：渐亮而不是跳变
        float intensity = te.clientIntensity;
        if (intensity <= 0.0F) {
            intensity = 0.25F;
        }

        // ★ 相位按"真实帧间隔"推进，而不是按 tick ——
        //   否则高帧率下等离子体转速会跟着帧率一起变，看起来像卡顿。
        float phase = te.clientPhase;
        float pulse = te.clientPulse;
        float shake = te.clientShake;

        // 熔毁抖动：给整个球体一个高频的小位移
        double jx = 0.0D, jy = 0.0D, jz = 0.0D;
        if (melting) {
            double amp = 0.035D * Math.max(0.0F, intensity);
            jx = Math.sin(shake * 3.1D) * amp;
            jy = Math.sin(shake * 4.7D + 1.3D) * amp;
            jz = Math.cos(shake * 2.3D + 0.7D) * amp;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + 0.5D + jx, y + 0.5D + jy, z + 0.5D + jz);

        // 关掉光照与剔除：等离子体是自发光的，跟着环境光变暗就完全不对了；
        // 剔除必须关，因为球体是从内向外看的，背面也得画才不漏。
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        TextureAtlasSprite tex = mc.getTextureMapBlocks().getAtlasSprite(DmrSpecTextures.CORE);
        TextureAtlasSprite wall = mc.getTextureMapBlocks().getAtlasSprite(DmrSpecTextures.CHAMBER);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // ---- ⓪ 腔体内壁：一圈很暗的圆筒，给等离子体当背景。
        //        没有它的话，等离子体就是"悬在空气里的一团光"，看不出这是个腔体。
        //        先画它，后面的辉光/本体再压上来 —— 层次才对。
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        drawShell(buf, wall, RING_R * 1.02D, 2.05D, 64);
        tess.draw();

        // ---- ① 外辉光：一个比等离子体略大的半透明球壳（先画，让本体压在它上面）
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        double glowR = PLASMA_R * (1.30D + 0.06D * Math.sin(pulse));
        drawSphere(buf, tex, glowR, phase * 0.6D,
                pick(lit, melting, COLD_OUT, RUN_OUT, MELT_OUT), 0.22F * intensity);
        tess.draw();

        // ---- ② 等离子体本体：不透明的实心球，随时间自转
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        double bodyR = PLASMA_R * (1.0D + 0.045D * Math.sin(pulse + 1.1F));
        drawSphere(buf, tex, bodyR, phase,
                pick(lit, melting, COLD_IN, RUN_IN, MELT_IN), 1.0F);
        tess.draw();

        // ---- ③ 约束环：两道倾斜的磁约束线圈，反向旋转
        GlStateManager.disableBlend();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        // 熔毁时环半径会抖 —— "约束正在失效"的视觉
        double ringR = RING_R * (1.0D + (melting ? 0.05D * Math.sin(shake * 2.0D) : 0.0D));
        // 第一道：绕 X 轴倾斜约 30°
        drawRing(buf, tex, ringR, RING_TUBE, RING_SEG,
                phase, (float) Math.toRadians(30.0D), 0.0F, lit, melting);
        // 第二道：反向旋转 + 绕 Z 轴倾斜
        drawRing(buf, tex, ringR, RING_TUBE, RING_SEG,
                -phase * 0.75D, 0.0F, (float) Math.toRadians(-35.0D), lit, melting);
        tess.draw();

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 画一圈圆筒（腔体内壁）。
     * <p>
     * ★ UV 必须<b>按弧长推进</b>（{@code u ∝ r * angle}），不能直接用角度。
     * 用角度的后果：整圈只占一张贴图的一个周期 → 大圆筒上的纹理被横向拉成几根粗道子。
     * 这条规律在塔塔鲁斯的环形铺贴上也踩过，见那里的类注释。
     * 这里贴图本身是无方向噪声，拉伸的伤害比对格纹小，但仍然会明显变糊 —— 所以照规矩来。
     */
    private static void drawShell(BufferBuilder buf, TextureAtlasSprite tex,
                                  double r, double halfH, int seg) {
        double uMin = tex.getMinU(), uMax = tex.getMaxU();
        double vMin = tex.getMinV(), vMax = tex.getMaxV();
        // 绕一整圈的 UV 重复次数 ≈ 周长 / 想象中"每格 1 遍"
        double repeat = Math.max(1.0D, r * 2.0D);
        float[] c = {0.55F, 0.58F, 0.64F};   // 压暗，别抢等离子体的视觉重心
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2.0D * i / seg;
            double a1 = Math.PI * 2.0D * (i + 1) / seg;
            double x0 = Math.cos(a0) * r, z0 = Math.sin(a0) * r;
            double x1 = Math.cos(a1) * r, z1 = Math.sin(a1) * r;
            double u0 = uMin + (uMax - uMin) * frac((double) i / seg * repeat);
            double u1 = uMin + (uMax - uMin) * frac((double) (i + 1) / seg * repeat);
            // 内侧面朝向圆心，绕序让它朝里可见
            vert(buf, new double[]{x0, halfH, z0}, u0, vMin, c, 1.0F);
            vert(buf, new double[]{x1, halfH, z1}, u1, vMin, c, 1.0F);
            vert(buf, new double[]{x1, -halfH, z1}, u1, vMax, c, 1.0F);
            vert(buf, new double[]{x0, -halfH, z0}, u0, vMax, c, 1.0F);
        }
    }

    /**
     * 画一个球（其实是经纬网格拼出来的球面）。
     * <p>
     * UV 用经典的"按经纬铺贴图"：{@code u} 沿经线走、{@code v} 沿纬线走。
     * 因为我们的贴图是"单格自洽"的噪声纹理（不是画好的球），
     * 铺开后看起来就是自然的表面颗粒 —— 而不是"贴了张世界地图"。
     *
     * @param spin  绕 Y 轴的额外旋转（弧度），制造自转
     * @param rgb   颜色
     * @param alpha 透明度
     */
    private static void drawSphere(BufferBuilder buf, TextureAtlasSprite tex, double r,
                                   double spin, float[] rgb, float alpha) {
        double uMin = tex.getMinU();
        double uMax = tex.getMaxU();
        double vMin = tex.getMinV();
        double vMax = tex.getMaxV();
        // 贴图重复次数：球面较大，重复几次让颗粒看起来更细
        double uRepeat = 3.0D;
        double vRepeat = 2.0D;

        for (int lat = 0; lat < SPHERE_LAT; lat++) {
            double phi0 = Math.PI * lat / SPHERE_LAT;
            double phi1 = Math.PI * (lat + 1) / SPHERE_LAT;
            for (int lon = 0; lon < SPHERE_LON; lon++) {
                double th0 = Math.PI * 2.0D * lon / SPHERE_LON + spin;
                double th1 = Math.PI * 2.0D * (lon + 1) / SPHERE_LON + spin;

                // 四个角在球面上的位置
                double[] p00 = sph(r, phi0, th0);
                double[] p01 = sph(r, phi0, th1);
                double[] p11 = sph(r, phi1, th1);
                double[] p10 = sph(r, phi1, th0);

                // UV：按经纬线性映射 + 重复
                double v0 = vMin + (vMax - vMin) * frac((double) lat / SPHERE_LAT * vRepeat);
                double v1 = vMin + (vMax - vMin) * frac((double) (lat + 1) / SPHERE_LAT * vRepeat);
                double u0 = uMin + (uMax - uMin) * frac((double) lon / SPHERE_LON * uRepeat);
                double u1 = uMin + (uMax - uMin) * frac((double) (lon + 1) / SPHERE_LON * uRepeat);

                vert(buf, p00, u0, v0, rgb, alpha);
                vert(buf, p01, u1, v0, rgb, alpha);
                vert(buf, p11, u1, v1, rgb, alpha);
                vert(buf, p10, u0, v1, rgb, alpha);
            }
        }
    }

    /**
     * 画一道圆环（绕 Y 轴的环面，可再绕 X/Z 倾斜）。
     * <p>
     * 环面用"圆截面扫一圈"的方式画：对每个周向位置，画一个矩形管段。
     * 管段只画朝外的两个面就够 —— 从外面看是一个环，看不到管子的内侧。
     *
     * @param spinY  绕 Y 轴的旋转（自转）
     * @param tiltX  绕 X 轴的倾斜
     * @param tiltZ  绕 Z 轴的倾斜
     */
    private static void drawRing(BufferBuilder buf, TextureAtlasSprite tex,
                                 double ringR, double tube, int seg,
                                 double spinY, float tiltX, float tiltZ,
                                 boolean lit, boolean melting) {
        double uMin = tex.getMinU();
        double uMax = tex.getMaxU();
        double vMin = tex.getMinV();
        double vMax = tex.getMaxV();

        // 环的颜色：运行时是金属蓝的亮环，未点火是暗环，熔毁是烧红的环
        float[] c;
        if (melting) {
            c = new float[]{0.95F, 0.42F, 0.10F};
        } else if (lit) {
            c = new float[]{0.42F, 0.72F, 0.95F};
        } else {
            c = new float[]{0.26F, 0.29F, 0.33F};
        }
        float alpha = 1.0F;

        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2.0D * i / seg + spinY;
            double a1 = Math.PI * 2.0D * (i + 1) / seg + spinY;

            // 环上两点（在 XZ 平面上），再由圆截面给出上下沿
            double[] p0 = ringPoint(ringR, tube, a0, tiltX, tiltZ);
            double[] p1 = ringPoint(ringR, tube, a1, tiltX, tiltZ);
            // 上沿 = 半径方向略外扩 + 抬高 tube
            double[] p0t = ringPoint(ringR, tube, a0, tiltX, tiltZ, +1);
            double[] p1t = ringPoint(ringR, tube, a1, tiltX, tiltZ, +1);
            double[] p0b = ringPoint(ringR, tube, a0, tiltX, tiltZ, -1);
            double[] p1b = ringPoint(ringR, tube, a1, tiltX, tiltZ, -1);

            double u0 = uMin + (uMax - uMin) * ((double) i / seg * 8.0D % 1.0D);
            double u1 = uMin + (uMax - uMin) * ((double) (i + 1) / seg * 8.0D % 1.0D);

            // 外侧面
            vert(buf, p0t, u0, vMin, c, alpha);
            vert(buf, p1t, u1, vMin, c, alpha);
            vert(buf, p1b, u1, vMax, c, alpha);
            vert(buf, p0b, u0, vMax, c, alpha);
            // 内侧面（反向绕，避免和外侧同向导致法线面被剔除）
            vert(buf, p0b, u0, vMax, c, alpha);
            vert(buf, p1b, u1, vMax, c, alpha);
            vert(buf, p1t, u1, vMin, c, alpha);
            vert(buf, p0t, u0, vMin, c, alpha);
        }
    }

    /**
     * 环上一个点的位置，并施加倾斜。
     *
     * @param side 0 = 管子中心，+1 = 管子上沿，-1 = 管子下沿
     */
    private static double[] ringPoint(double ringR, double tube, double angle,
                                      float tiltX, float tiltZ, int side) {
        double x = Math.cos(angle) * ringR;
        double z = Math.sin(angle) * ringR;
        double y = side * tube;
        // 先绕 X 转，再绕 Z 转
        double cy = Math.cos(tiltX), sy = Math.sin(tiltX);
        double y1 = y * cy - z * sy;
        double z1 = y * sy + z * cy;
        double cz = Math.cos(tiltZ), sz = Math.sin(tiltZ);
        double x2 = x * cz - y1 * sz;
        double y2 = x * sz + y1 * cz;
        return new double[]{x2, y2, z1};
    }

    private static double[] ringPoint(double ringR, double tube, double angle,
                                      float tiltX, float tiltZ) {
        return ringPoint(ringR, tube, angle, tiltX, tiltZ, 0);
    }

    /** 球坐标 → 直角坐标 */
    private static double[] sph(double r, double phi, double theta) {
        double sp = Math.sin(phi);
        return new double[]{
                r * sp * Math.cos(theta),
                r * Math.cos(phi),
                r * sp * Math.sin(theta)
        };
    }

    private static void vert(BufferBuilder buf, double[] p, double u, double v,
                             float[] rgb, float alpha) {
        buf.pos(p[0], p[1], p[2])
                .tex(u, v)
                .color(rgb[0], rgb[1], rgb[2], alpha)
                .endVertex();
    }

    /** 取小数部分，用于让 UV 在重复时无缝接上 */
    private static double frac(double v) {
        return v - Math.floor(v);
    }

    /** 三选一：未点火 / 运行 / 熔毁 */
    private static float[] pick(boolean lit, boolean melting,
                                float[] cold, float[] run, float[] melt) {
        return melting ? melt : (lit ? run : cold);
    }
}

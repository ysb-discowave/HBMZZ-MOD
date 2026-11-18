package com.rsaudio.client;

import com.rsaudio.block.BlockExhaustFan;
import com.rsaudio.tile.TileExhaustFan;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

/**
 * 排风扇的扇叶 + 防护罩 + 外圈风筒。
 *
 * <h2>★★ 几何全部"自己算"，一次 {@code GlStateManager.rotate} 都不用</h2>
 * 本模组在"渲染朝向"上已经吃过教训（见 {@code MonitorConsoleRenderer} 的类注释）：
 * "模型的正面在哪个面""变体里的 y 角是顺时针还是逆时针"这类约定，看文档和凭记忆
 * 都极容易被别的版本带偏，而错了的表现是镜像 / 转 90° / 上下颠倒 —— 都不报错。
 * <p>
 * 这里的做法和监控控制台逐字相同：
 * <ol>
 *   <li>从 {@link BlockExhaustFan#FACING} 直接算出一组世界基向量
 *       （法线 {@code n} 和面内两条轴 {@code u}/{@code v}），
 *       所有顶点都是 {@code 中心 + u*a + v*b + n*d} 这种向量和。</li>
 *   <li>不设 GL 旋转，所以"方块模型用了 x/y 旋转"和"这里不用旋转"两种做法
 *       之间不存在需要对齐的约定 ——
 *       <b>"模型转对了、叶片却转错了"这种错法在这台机器上不可能发生</b>。</li>
 *   <li>画的时候关掉背面剔除，三角形的绕序也就不重要了。</li>
 * </ol>
 * 剩下唯一的"约定"是叶片自转的方向，那个方向错了完全看不出来（风机电扇本来
 * 就有正反转），所以不构成风险。
 *
 * <h2>★ 几何整体凸出方块表面</h2>
 * 方块自己那一面是"进风格栅"贴图、且是完整的一个面。要是把叶片画在方块<b>内部</b>
 * （{@code d < 0}），它会被方块自己的正面挡住 —— 什么都看不见。
 * 所以整套几何都放在面<b>外</b>（{@code d > 0}），观感就是"墙上挂了一台排风扇"，
 * 顺带也不用去改方块模型挖洞（那要按 6 个朝向各做一个模型）。
 *
 * <h2>★ 自转相位不能直接用 worldTime</h2>
 * {@code getTotalWorldTime()} 会一直涨，长时间的世界里它的 double/float 精度
 * 早就不够表示"度"，表现是风扇突然开始一跳一跳地卡。
 * 所以先对 3600 取模 —— 3600 × 14°/tick = 50400°，正好是 360 的整数倍，
 * 取模处不会跳帧。
 */
public class ExhaustFanRenderer extends TileEntitySpecialRenderer<TileExhaustFan> {

    /** 超过这个距离就不画了（一面墙十几个风扇，远处看不出转不转） */
    private static final double VIEW_RANGE = 48.0D;

    /** 每 tick 转多少度 */
    private static final float SPIN_DEG_PER_TICK = 14.0F;

    /** 相位取模周期（tick）。★ 必须让 周期 × 每 tick 度数 是 360 的整数倍 */
    private static final long SPIN_PERIOD_TICKS = 3600L;

    /** 叶片数 */
    private static final int BLADE_COUNT = 6;
    /** 单片叶片张开的角度（度）。小于 360/叶片数，叶片之间才有缝 */
    private static final double BLADE_ARC = 46.0D;

    /** 各个半径（以格为单位） */
    private static final double R_OPENING = 7.0D / 16.0D;      // 风筒内径（深色盘）
    private static final double R_BLADE_OUT = 6.6D / 16.0D;
    private static final double R_BLADE_IN = 1.6D / 16.0D;
    private static final double R_HUB = 2.2D / 16.0D;
    private static final double R_CAGE_OUT = 6.4D / 16.0D;
    private static final double R_CAGE_MID = 3.6D / 16.0D;
    private static final double R_RIM = 7.2D / 16.0D;

    /** 各个"离表面的深度"（格）。0 = 正好在方块面上 */
    private static final double D_OPENING = 0.006D;
    private static final double D_BLADE = 0.040D;
    private static final double D_HUB = 0.056D;
    private static final double D_CAGE = 0.115D;
    private static final double D_RIM = 0.004D;
    private static final double RIM_DEPTH = 0.118D;

    private static final int SEGMENTS = 24;

    @Override
    public void render(TileExhaustFan te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
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
        if (!(state.getBlock() instanceof BlockExhaustFan)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockExhaustFan.FACING);
        boolean running = state.getValue(BlockExhaustFan.RUNNING).booleanValue();

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 自发光：金属件在昏暗的机房里也该看得清（和本模组其它 TESR 一致）
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        Frame f = new Frame(facing);
        double spin = spinAngle(te, partialTicks, running);

        drawRim(f);
        drawDisc(f, D_OPENING, R_OPENING, 0.055F, 0.058F, 0.070F);
        drawBlades(f, spin);
        drawDisc(f, D_HUB, R_HUB, 0.72F, 0.74F, 0.78F);
        drawCage(f);

        GlStateManager.enableRescaleNormal();
        GlStateManager.enableCull();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ 相位

    /**
     * 当前自转角（度，0..360）。
     * <p>
     * 停转时返回 0 —— <b>不</b>停在"上一次的角度"是刻意的：停下之后再启动，
     * 叶片从 0 开始转，看起来像"重新通电"，比"停在某个随机角度"更像机器。
     */
    private static double spinAngle(TileExhaustFan te, float partialTicks, boolean running) {
        if (!running) {
            return 0.0D;
        }
        long ticks = te.getWorld().getTotalWorldTime() % SPIN_PERIOD_TICKS;
        double angle = (ticks + partialTicks) * SPIN_DEG_PER_TICK;
        // 再取一次 360 的模，让 drawBlades 那边的三角函数吃到的角度总是小的
        return angle % 360.0D;
    }

    // ------------------------------------------------------------------ 面内的基向量

    /**
     * 由 {@code FACING} 直接算出的世界基向量。
     * <p>
     * {@code n} 是朝外的法线；{@code u}/{@code v} 是面内两条正交轴，
     * 用来把"半径 + 角度"摊成三维坐标。取法与 {@code MonitorConsoleRenderer.Frame} 相同：
     * <ul>
     *   <li>竖直朝向（上下）时面是水平面：{@code u = +X}、{@code v = +Z}；</li>
     *   <li>其余四个朝向时面是竖立面：{@code v = +Y}（面的"上"永远是世界上的上），
     *       {@code u = (n.z, 0, -n.x)}（站在外面看这块面的"右"）。</li>
     * </ul>
     * 两个都不需要跟原版的任何角度约定对齐，纯粹是向量运算。
     */
    private static final class Frame {
        final double nx;
        final double ny;
        final double nz;
        final double ux;
        final double uy;
        final double uz;
        final double vx;
        final double vy;
        final double vz;

        Frame(EnumFacing facing) {
            // ★ 1.12.2 的名字是 getFrontOffsetX/Y/Z（跨版本抄成 getXOffset 编译不过）
            this.nx = facing.getFrontOffsetX();
            this.ny = facing.getFrontOffsetY();
            this.nz = facing.getFrontOffsetZ();
            if (this.ny != 0) {
                this.ux = 1.0D;
                this.uy = 0.0D;
                this.uz = 0.0D;
                this.vx = 0.0D;
                this.vy = 0.0D;
                this.vz = 1.0D;
            } else {
                this.ux = this.nz;
                this.uy = 0.0D;
                this.uz = -this.nx;
                this.vx = 0.0D;
                this.vy = 1.0D;
                this.vz = 0.0D;
            }
        }

        /** 半径 r、面内角度 angleDeg、离表面深度 depth -> 世界 x */
        double x(double r, double cos, double sin, double depth) {
            return 0.5D + this.ux * (r * cos) + this.vx * (r * sin) + this.nx * (0.5D + depth);
        }

        double y(double r, double cos, double sin, double depth) {
            return 0.5D + this.uy * (r * cos) + this.vy * (r * sin) + this.ny * (0.5D + depth);
        }

        double z(double r, double cos, double sin, double depth) {
            return 0.5D + this.uz * (r * cos) + this.vz * (r * sin) + this.nz * (0.5D + depth);
        }
    }

    // ------------------------------------------------------------------ 各个零件

    /** 外圈风筒的侧壁：让风扇看起来有厚度，而不是一张贴纸 */
    private void drawRim(Frame f) {
        BufferBuilder buf = begin();
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.toRadians(360.0D * i / SEGMENTS);
            double a1 = Math.toRadians(360.0D * (i + 1) / SEGMENTS);
            double c0 = Math.cos(a0);
            double s0 = Math.sin(a0);
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);
            // ★ 四个角一律在 R_RIM 半径上，只是"前(D_RIM) → 后(RIM_DEPTH)"走一圈：
            //   (a0,前) -> (a1,前) -> (a1,后) -> (a0,后)，正好是一段侧壁。
            //   这里曾经把第 2 个角的深度写成 R_RIM（半径写进了深度位），于是整组参数
            //   往后错位、少传了一个 double —— 编译期就报 "quad cannot be applied"。
            quad(buf, f, R_RIM, c0, s0, D_RIM,
                    R_RIM, c1, s1, D_RIM,
                    R_RIM, c1, s1, RIM_DEPTH,
                    R_RIM, c0, s0, RIM_DEPTH,
                    0.34F, 0.35F, 0.39F);
        }
        Tessellator.getInstance().draw();
    }

    /** 一个面向外的实心圆盘（用扇形三角带画） */
    private void drawDisc(Frame f, double depth, double radius, float r, float g, float b) {
        BufferBuilder buf = begin(GL11.GL_TRIANGLE_FAN);
        buf.pos(f.x(0.0D, 1.0D, 0.0D, depth), f.y(0.0D, 1.0D, 0.0D, depth),
                f.z(0.0D, 1.0D, 0.0D, depth)).color(r, g, b, 1.0F).endVertex();
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.toRadians(360.0D * i / SEGMENTS);
            double c = Math.cos(a);
            double s = Math.sin(a);
            buf.pos(f.x(radius, c, s, depth), f.y(radius, c, s, depth),
                    f.z(radius, c, s, depth)).color(r, g, b, 1.0F).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    /**
     * 叶片。每片是一个"内圈到外圈"的梯形，绕中心均布、整体加上相位 {@code spin}。
     * <p>
     * 相邻叶片用两种深浅交替：纯同色的话，转起来只能看到一圈模糊，
     * 交替色才能看清"真的在转"。
     */
    private void drawBlades(Frame f, double spin) {
        BufferBuilder buf = begin();
        double step = 360.0D / BLADE_COUNT;
        for (int i = 0; i < BLADE_COUNT; i++) {
            double a0 = Math.toRadians(spin + step * i);
            double a1 = Math.toRadians(spin + step * i + BLADE_ARC);
            double c0 = Math.cos(a0);
            double s0 = Math.sin(a0);
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);
            boolean even = (i % 2) == 0;
            float cr = even ? 0.66F : 0.46F;
            float cg = even ? 0.69F : 0.49F;
            float cb = even ? 0.74F : 0.54F;
            quad(buf, f, R_BLADE_OUT, c0, s0, D_BLADE, R_BLADE_OUT, c1, s1, D_BLADE,
                    R_BLADE_IN, c1, s1, D_BLADE, R_BLADE_IN, c0, s0, D_BLADE, cr, cg, cb);
            // 叶片前沿压一条深色细边，静止时也能看出叶片是有厚度的板
            quad(buf, f, R_BLADE_OUT, c1, s1, D_BLADE, R_BLADE_OUT, c1, s1, D_BLADE + 0.012D,
                    R_BLADE_IN, c1, s1, D_BLADE + 0.012D, R_BLADE_IN, c1, s1, D_BLADE,
                    0.28F, 0.29F, 0.32F);
        }
        Tessellator.getInstance().draw();
    }

    /** 防护罩：两道同心圆环 + 四根辐条 */
    private void drawCage(Frame f) {
        BufferBuilder buf = begin();
        ring(buf, f, R_CAGE_MID - 0.045D, R_CAGE_MID + 0.045D, 0.62F, 0.64F, 0.68F);
        ring(buf, f, R_CAGE_OUT - 0.045D, R_CAGE_OUT + 0.045D, 0.62F, 0.64F, 0.68F);
        for (int i = 0; i < 4; i++) {
            double a = Math.toRadians(45.0D + 90.0D * i);
            double c = Math.cos(a);
            double s = Math.sin(a);
            double half = 0.055D;
            // 辐条 = 一根从中心到外圈的细长条：用横跨 ±half 的两条边拼
            double px = -s * half;
            double py = c * half;
            double rOut = R_RIM;
            double x0 = f.x(0.0D, 1.0D, 0.0D, D_CAGE);
            double y0 = f.y(0.0D, 1.0D, 0.0D, D_CAGE);
            double z0 = f.z(0.0D, 1.0D, 0.0D, D_CAGE);
            buf.pos(x0 + f.ux * px + f.vx * py, y0 + f.uy * px + f.vy * py,
                    z0 + f.uz * px + f.vz * py).color(0.62F, 0.64F, 0.68F, 1.0F).endVertex();
            buf.pos(x0 - f.ux * px - f.vx * py, y0 - f.uy * px - f.vy * py,
                    z0 - f.uz * px - f.vz * py).color(0.62F, 0.64F, 0.68F, 1.0F).endVertex();
            double xo = f.x(rOut, c, s, D_CAGE);
            double yo = f.y(rOut, c, s, D_CAGE);
            double zo = f.z(rOut, c, s, D_CAGE);
            buf.pos(xo - f.ux * px - f.vx * py, yo - f.uy * px - f.vy * py,
                    zo - f.uz * px - f.vz * py).color(0.62F, 0.64F, 0.68F, 1.0F).endVertex();
            buf.pos(xo + f.ux * px + f.vx * py, yo + f.uy * px + f.vy * py,
                    zo + f.uz * px + f.vz * py).color(0.62F, 0.64F, 0.68F, 1.0F).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    /** 一圈环带（内半径 -> 外半径），用 SEGMENTS 段四边形拼 */
    private static void ring(BufferBuilder buf, Frame f, double rIn, double rOut,
                             float cr, float cg, float cb) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = Math.toRadians(360.0D * i / SEGMENTS);
            double a1 = Math.toRadians(360.0D * (i + 1) / SEGMENTS);
            double c0 = Math.cos(a0);
            double s0 = Math.sin(a0);
            double c1 = Math.cos(a1);
            double s1 = Math.sin(a1);
            quad(buf, f, rOut, c0, s0, D_CAGE, rOut, c1, s1, D_CAGE,
                    rIn, c1, s1, D_CAGE, rIn, c0, s0, D_CAGE, cr, cg, cb);
        }
    }

    // ------------------------------------------------------------------ 顶点工具

    private static BufferBuilder begin() {
        return begin(GL11.GL_QUADS);
    }

    private static BufferBuilder begin(int mode) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.getBuffer();
        buf.begin(mode, DefaultVertexFormats.POSITION_COLOR);
        return buf;
    }

    /**
     * 四个角拼一个四边形。
     * <p>
     * 每个角都是"半径 + 面内角度 + 离表面深度"，前三个参数定半径、中两个定角度、
     * 最后一个定深度 —— 顺序容易看串，所以写成 {@code (r, cos, sin, depth)} 一组。
     */
    private static void quad(BufferBuilder buf, Frame f,
                             double r1, double c1, double s1, double d1,
                             double r2, double c2, double s2, double d2,
                             double r3, double c3, double s3, double d3,
                             double r4, double c4, double s4, double d4,
                             float cr, float cg, float cb) {
        buf.pos(f.x(r1, c1, s1, d1), f.y(r1, c1, s1, d1), f.z(r1, c1, s1, d1))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(r2, c2, s2, d2), f.y(r2, c2, s2, d2), f.z(r2, c2, s2, d2))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(r3, c3, s3, d3), f.y(r3, c3, s3, d3), f.z(r3, c3, s3, d3))
                .color(cr, cg, cb, 1.0F).endVertex();
        buf.pos(f.x(r4, c4, s4, d4), f.y(r4, c4, s4, d4), f.z(r4, c4, s4, d4))
                .color(cr, cg, cb, 1.0F).endVertex();
    }
}

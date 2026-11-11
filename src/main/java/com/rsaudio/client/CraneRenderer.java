package com.rsaudio.client;

import com.rsaudio.block.BlockCrane;
import com.rsaudio.tile.TileCrane;
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
 * 起重机的龙门架 / 回转臂 / 吊钩（顶点色，不占贴图）。
 *
 * <h2>★★ 和排风扇同一套纪律：几何自己算，一次 GL rotate 都不用</h2>
 * 方块那一格（{@code BlockCrane} 的 cube 模型）只是"底座机箱"，
 * 上面这台会转的龙门架全部由这里逐帧画出来：
 * <ul>
 *   <li>固定件：立柱 + 立领 —— 不转，直接按世界轴画；</li>
 *   <li>回转件：驾驶舱 / 前臂 / 后臂 / 配重 / 信标灯 —— 都挂在同一个角 {@code theta} 上，
 *       每个顶点都是 {@code 中心 + dir*r + 法向偏移} 的向量和，
 *       其中 {@code dir = (cos theta, sin theta)} 落在 XZ 平面里；</li>
 *   <li>吊钩：钢缆 + 钩头，跟在前臂尖端那个半径上（也不单独转，直接按尖端位置画）。</li>
 * </ul>
 * 不设 GL 旋转 → 不存在"模型转了、臂没跟着转"这类需要对齐的约定。
 * 画完关掉背面剔除（手写顶点序，绕序不该再成为变量）。
 *
 * <h2>★ 回转角：停着时停在 FACING，运行时慢速扫一圈</h2>
 * {@code theta = baseAngle(FACING) + spin}。
 * <ul>
 *   <li>{@code baseAngle}：NORTH=270° / EAST=0° / SOUTH=90° / WEST=180°
 *       （dir=(cos, sin) 落在 XZ 平面：+X 是 0°，+Z 是 90°，-Z 是 270° ——
 *       MC 里 +Z 是南、-Z 是北，所以朝北 = 270°）。</li>
 *   <li>{@code spin}：运行中 = {@code ((worldTime % 90) + partial) * 4°}，
 *       90 tick × 4° = 360°，一整圈 4.5 秒 —— 起重机是重机械，快转起来反而假。
 *       ★ 和排风扇同一条规矩：取模周期 × 每 tick 度数必须是 360 的整数倍，
 *       否则长世界（worldTime 很大）里相位会跳帧。</li>
 *   <li>停着时 {@code spin = 0}（臂停在 FACING 上）—— 和排风扇"停转回 0°"是同一个
 *       刻意选择：再启动时从固定位重新起转，像"重新通电"，比停在随机角度更像机器。</li>
 * </ul>
 *
 * <h2>★ 几何尺寸与 {@link TileCrane#getRenderBoundingBox} 是同一把尺子</h2>
 * 前臂甩到中心外 1.10 格（包围盒横向 ±1.25 罩得住），
 * 驾驶舱顶到 y≈1.52、信标灯顶到 y≈1.59（包围盒纵向到 +1.6 罩得住）。
 * 改这里任何一个尺寸，就去改那里的包围盒 —— 两边不一致的后果是
 * "区块边缘/视锥边缘处的起重机整块被剔除"（纯渲染附加物撑不破默认包围盒的老坑）。
 */
public class CraneRenderer extends TileEntitySpecialRenderer<TileCrane> {

    /** 超过这个距离不画（远处看不出臂转不转） */
    private static final double VIEW_RANGE = 48.0D;

    /** 每 tick 转多少度（重机械：慢） */
    private static final float SPIN_DEG_PER_TICK = 4.0F;

    /** 相位取模周期（tick）。★ 必须让 周期 × 每 tick 度数 是 360 的整数倍（90×4=360） */
    private static final long SPIN_PERIOD_TICKS = 90L;

    // ---------------------------------------------------------------- 各零件尺寸
    // 全部以"底座方块的下表面"为 y=0。横向以方块中心 (0.5, 0.5) 为原点。

    /** 立柱：从底座顶面 y=1.0 升到 1.36 */
    private static final double TOWER_HALF = 0.13D;
    private static final double TOWER_Y0 = 1.0D;
    private static final double TOWER_Y1 = 1.36D;
    /** 立领（柱脚那一圈，比柱粗一点，把"柱子插进底座"的缝遮掉） */
    private static final double COLLAR_HALF = 0.19D;
    private static final double COLLAR_Y = 1.015D;
    private static final double COLLAR_H = 0.035D;

    /** 驾驶舱（回转件，坐在柱顶）：y 1.36..1.52 */
    private static final double CAB_HALF = 0.17D;
    private static final double CAB_Y = 1.44D;
    private static final double CAB_H = 0.08D;
    /** 驾驶舱挡风玻璃（臂那一侧） */
    private static final double GLASS_R = 0.19D;
    private static final double GLASS_HALF = 0.09D;
    private static final double GLASS_H = 0.045D;
    private static final double GLASS_T = 0.015D;

    /** 信标灯（驾驶舱顶，运行时红、停着暗） */
    private static final double LAMP_HALF = 0.035D;
    private static final double LAMP_Y = 1.56D;

    /** 前臂：r 0.18 → 1.10（★ 1.10 就是 TileCrane 包围盒注释里那个"最远甩到 1.1 格"） */
    private static final double JIB_R0 = 0.18D;
    private static final double JIB_R1 = 1.10D;
    private static final double JIB_W = 0.10D;
    private static final double JIB_H = 0.12D;
    private static final double JIB_Y = 1.44D;
    /** 后臂：r 0.18 → 0.46 */
    private static final double CTR_R0 = 0.18D;
    private static final double CTR_R1 = 0.46D;
    /** 配重（后臂尾端） */
    private static final double CTRW_R = 0.38D;
    private static final double CTRW_HALF_U = 0.10D;
    private static final double CTRW_HALF_W = 0.09D;
    private static final double CTRW_HALF_Y = 0.10D;
    private static final double CTRW_Y = 1.40D;

    /** 吊钩：钢缆（r=1.00 处垂下）+ 钩头 */
    private static final double HOOK_R = 1.00D;
    private static final double CABLE_HALF_Y = 0.28D;
    private static final double CABLE_Y = 1.12D;      // y 0.84..1.40
    private static final double HOOK_HALF = 0.06D;
    private static final double HOOK_Y = 0.78D;

    // ---------------------------------------------------------------- 颜色（工业黄 + 钢灰）
    private static final float[] C_TOWER = {0.42F, 0.44F, 0.50F};
    private static final float[] C_COLLAR = {0.52F, 0.54F, 0.60F};
    private static final float[] C_CAB = {0.55F, 0.58F, 0.64F};
    private static final float[] C_GLASS = {0.16F, 0.20F, 0.28F};
    private static final float[] C_JIB = {0.84F, 0.64F, 0.14F};
    private static final float[] C_CTRW = {0.30F, 0.32F, 0.36F};
    private static final float[] C_CABLE = {0.52F, 0.54F, 0.58F};
    private static final float[] C_HOOK = {0.90F, 0.70F, 0.16F};
    private static final float[] C_LAMP_ON = {1.00F, 0.30F, 0.25F};
    private static final float[] C_LAMP_OFF = {0.35F, 0.18F, 0.16F};

    @Override
    public void render(TileCrane te, double x, double y, double z, float partialTicks,
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
        if (!(state.getBlock() instanceof BlockCrane)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockCrane.FACING);
        boolean running = state.getValue(BlockCrane.RUNNING).booleanValue();

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 自发光：机房里的机器在昏暗处也该看得清（和本模组其它 TESR 一致）
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        double theta = spinAngle(te, partialTicks, running, facing);
        double rad = Math.toRadians(theta);
        double dx = Math.cos(rad);
        double dz = Math.sin(rad);

        BufferBuilder buf = begin();
        // ---- 固定件（世界轴对齐）
        box(buf, 0.5D, (TOWER_Y0 + TOWER_Y1) / 2.0D, 0.5D,
                TOWER_HALF, (TOWER_Y1 - TOWER_Y0) / 2.0D, TOWER_HALF, C_TOWER);
        box(buf, 0.5D, COLLAR_Y, 0.5D, COLLAR_HALF, COLLAR_H, COLLAR_HALF, C_COLLAR);
        // ---- 回转件（全部挂在 theta 上）
        box(buf, 0.5D, CAB_Y, 0.5D, CAB_HALF, CAB_H, CAB_HALF, C_CAB);
        obox(buf, 0.5D + dx * GLASS_R, CAB_Y, 0.5D + dz * GLASS_R,
                dx, 0.0D, dz, GLASS_T,
                0.0D, 1.0D, 0.0D, GLASS_H,
                -dz, 0.0D, dx, GLASS_HALF, C_GLASS);
        beam(buf, (JIB_R0 + JIB_R1) / 2.0D, JIB_Y, dx, dz, JIB_R1 - JIB_R0, JIB_W, JIB_H, C_JIB);
        beam(buf, -(CTR_R0 + CTR_R1) / 2.0D, JIB_Y, dx, dz, CTR_R1 - CTR_R0, JIB_W, JIB_H, C_JIB);
        obox(buf, 0.5D - dx * CTRW_R, CTRW_Y, 0.5D - dz * CTRW_R,
                dx, 0.0D, dz, CTRW_HALF_U,
                0.0D, 1.0D, 0.0D, CTRW_HALF_Y,
                -dz, 0.0D, dx, CTRW_HALF_W, C_CTRW);
        box(buf, 0.5D, LAMP_Y, 0.5D, LAMP_HALF, LAMP_HALF, LAMP_HALF,
                running ? C_LAMP_ON : C_LAMP_OFF);
        // ---- 吊钩（跟在前臂尖端那个半径上）
        box(buf, 0.5D + dx * HOOK_R, CABLE_Y, 0.5D + dz * HOOK_R,
                0.012D, CABLE_HALF_Y, 0.012D, C_CABLE);
        box(buf, 0.5D + dx * HOOK_R, HOOK_Y, 0.5D + dz * HOOK_R,
                HOOK_HALF, HOOK_HALF, HOOK_HALF, C_HOOK);

        Tessellator.getInstance().draw();

        GlStateManager.enableRescaleNormal();
        GlStateManager.enableCull();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    // ---------------------------------------------------------------- 相位

    /**
     * 当前回转角（度，0..360）。
     * <p>
     * 停着时返回 0（臂停在 FACING 上）—— 刻意不停在"上一次的角度"，
     * 理由见类注释（"重新通电"的观感）。
     */
    private static double spinAngle(TileCrane te, float partialTicks, boolean running,
                                    EnumFacing facing) {
        double base = baseAngle(facing);
        if (!running) {
            return base % 360.0D;
        }
        long ticks = te.getWorld().getTotalWorldTime() % SPIN_PERIOD_TICKS;
        double angle = base + (ticks + partialTicks) * SPIN_DEG_PER_TICK;
        return angle % 360.0D;
    }

    /** 各朝向对应的"停着时臂指的方向"（度）。dir=(cos,sin) 落在 XZ 平面。 */
    private static double baseAngle(EnumFacing facing) {
        if (facing == EnumFacing.EAST) {
            return 0.0D;
        }
        if (facing == EnumFacing.SOUTH) {
            return 90.0D;
        }
        if (facing == EnumFacing.WEST) {
            return 180.0D;
        }
        return 270.0D;      // NORTH（默认）
    }

    // ---------------------------------------------------------------- 顶点工具

    private static BufferBuilder begin() {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        return buf;
    }

    /**
     * 任意朝向的盒子：中心 (cx,cy,cz)，三条单位轴 u/v/w 各带半长 hu/hv/hw。
     * <p>
     * 8 个角 = 中心 ± (hu·u + hv·v + hw·w) 的全部组合；6 个面各取其中 4 个角。
     * 背面剔除是关着的，绕序不用管。
     */
    private static void obox(BufferBuilder buf,
                             double cx, double cy, double cz,
                             double ux, double uy, double uz, double hu,
                             double vx, double vy, double vz, double hv,
                             double wx, double wy, double wz, double hw,
                             float[] rgb) {
        double[] px = new double[8];
        double[] py = new double[8];
        double[] pz = new double[8];
        for (int i = 0; i < 8; i++) {
            double su = (i & 1) == 0 ? -hu : hu;
            double sv = (i & 2) == 0 ? -hv : hv;
            double sw = (i & 4) == 0 ? -hw : hw;
            px[i] = cx + su * ux + sv * vx + sw * wx;
            py[i] = cy + su * uy + sv * vy + sw * wy;
            pz[i] = cz + su * uz + sv * vz + sw * wz;
        }
        int[][] faces = {
                {1, 3, 7, 5}, {0, 2, 6, 4},     // u=+ / u=-
                {2, 3, 7, 6}, {0, 1, 5, 4},     // v=+ / v=-
                {4, 5, 7, 6}, {0, 1, 3, 2},     // w=+ / w=-
        };
        for (int[] f : faces) {
            buf.pos(px[f[0]], py[f[0]], pz[f[0]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
            buf.pos(px[f[1]], py[f[1]], pz[f[1]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
            buf.pos(px[f[2]], py[f[2]], pz[f[2]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
            buf.pos(px[f[3]], py[f[3]], pz[f[3]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
        }
    }

    /** 世界轴对齐的盒子（obox 的 u=+X / v=+Y / w=+Z 特例） */
    private static void box(BufferBuilder buf,
                            double cx, double cy, double cz,
                            double hx, double hy, double hz, float[] rgb) {
        obox(buf, cx, cy, cz,
                1.0D, 0.0D, 0.0D, hx,
                0.0D, 1.0D, 0.0D, hy,
                0.0D, 0.0D, 1.0D, hz, rgb);
    }

    /**
     * 水平"梁"：中心在"方块中心沿 dir 出去 r"处，长轴沿 dir（水平单位向量），
     * 总长 len，截面宽 w（水平垂直向）、高 h（竖直）。
     * <p>
     * 用来画前臂 / 后臂 —— 它们的长轴就是回转角指的方向，
     * 用 {@code (dx,0,dz)} 和 {@code (-dz,0,dx)} 两条单位轴拼出来，不碰 GL。
     */
    private static void beam(BufferBuilder buf, double r, double y,
                             double dx, double dz, double len, double w, double h, float[] rgb) {
        obox(buf, 0.5D + dx * r, y, 0.5D + dz * r,
                dx, 0.0D, dz, len / 2.0D,
                0.0D, 1.0D, 0.0D, h / 2.0D,
                -dz, 0.0D, dx, w / 2.0D, rgb);
    }
}

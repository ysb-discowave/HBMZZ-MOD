package com.rsaudio.client;

import com.rsaudio.entity.EntityFreightTruck;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * 货运卡车实体的渲染器（纯顶点色：驾驶室 / 货台 / 轮子 / 顶灯）。
 *
 * <h3>★★ 和 TrainRenderer 同一套纪律，但更简单</h3>
 * <ul>
 *   <li><b>位置/朝向从 prevPos/pos + prevYaw/yaw 用 partialTicks 插值</b>：
 *       原版实体同步每 3 tick 推一次离散快照，不插值车就是"一格一格跳"。
 *       ★ prevPos 脏值判据和列车同一条：插值距离 &gt; 1 格 = 刚生成/刚传送，
 *       直接用 pos（否则新车会从世界原点"飞"过来）。</li>
 *   <li><b>绕 Y 的角必须是 {@code -yaw}</b>：MC 朝向 = (-sin yaw, cos yaw)，
 *       局部 +Z 要指到 yaw 方向，θ 必须取负号。漏负号的坑在 TrainRenderer
 *       的类注释里写死了（南北向碰巧对、东西向整车转 180°）。</li>
 *   <li><b>画之前 disableCull()</b>：手写顶点序，绕序不该再是变量。</li>
 *   <li><b>离屏渲染守卫</b>：监控摄像机的"重画一遍世界"那一趟要挡掉，
 *       否则卡车在监控画面里画第二次（重影/闪烁）。</li>
 * </ul>
 *
 * <h3>★ 贴图：一张都不绑</h3>
 * 车体全部是顶点色盒子（POSITION_COLOR），不采样图集 ——
 * {@link #getEntityTexture} 返回 null（和 TrainRenderer 同义：贴图要绑的话
 * 是"手里自己绑"，这里根本不用绑）。所以它也不进
 * {@code gen_resources.py} 的 RENDERER_TEXTURES 清单（没有 getAtlasSprite
 * 字面量就没有"静默 missingno"这条故障路径）。
 *
 * <h3>★ 顶灯亮不亮读 DataWatcher，不读内核</h3>
 * 客户端那份内核<b>不 tick</b>（服务端权威），它的 speed/state 永远是旧值。
 * 运行状态走实体同步通道里的 DataWatcher 字节（EntityFreightTruck 每 tick
 * setByte，只在变化的那一次真正发包）—— 渲染器只读它。
 *
 * <h3>★ 几何与 EntityFreightTruck.getRenderBoundingBox 是同一把尺子</h3>
 * 车体纵向 -1.15..+1.15（包围盒按 ±1.3 罩）、车高 1.55（碰撞箱 1.55）。
 * 改这里先改那边的包围盒。
 */
public class FreightTruckRenderer extends Render<EntityFreightTruck> {

    /** 超过这个距离不画 */
    private static final double VIEW_RANGE = 96.0D;

    // ---------------------------------------------------------------- 几何尺寸
    // 局部坐标：原点 = 车脚下（地面），车头朝 +Z，车宽沿 X，高沿 Y。

    /** 轮子：四个，中心 (±0.62, 0.30, ±0.85) */
    private static final double WHEEL_X = 0.62D;
    private static final double WHEEL_Z = 0.85D;
    private static final double WHEEL_HX = 0.14D;
    private static final double WHEEL_HY = 0.30D;
    private static final double WHEEL_HZ = 0.20D;
    /** 底盘框架：y 0.52..0.72，z -1.10..+0.80 */
    private static final double CHASSIS_HX = 0.66D;
    private static final double CHASSIS_HY = 0.10D;
    private static final double CHASSIS_Y = 0.62D;
    private static final double CHASSIS_HZ = 0.95D;
    private static final double CHASSIS_ZC = -0.15D;
    /** 货台：y 0.72..0.84，z -1.15..-0.05（★ -1.15 就是车尾） */
    private static final double BED_HX = 0.72D;
    private static final double BED_HY = 0.06D;
    private static final double BED_Y = 0.78D;
    private static final double BED_HZ = 0.55D;
    private static final double BED_ZC = -0.60D;
    /** 货台侧栏 */
    private static final double RAIL_HX = 0.03D;
    private static final double RAIL_HY = 0.07D;
    private static final double RAIL_Y = 0.90D;
    private static final double RAIL_HZ = 0.55D;
    /** 货物：板条箱 + 桶 */
    private static final double CRATE_HX = 0.42D;
    private static final double CRATE_HY = 0.21D;
    private static final double CRATE_Y = 1.05D;
    private static final double CRATE_HZ = 0.35D;
    private static final double CRATE_ZC = -0.75D;
    private static final double BARREL_HX = 0.16D;
    private static final double BARREL_HY = 0.13D;
    private static final double BARREL_Y = 0.97D;
    private static final double BARREL_HZ = 0.16D;
    private static final double BARREL_X = -0.35D;
    private static final double BARREL_ZC = -0.20D;
    /** 驾驶室：y 0.65..1.45，z 0.18..1.06（★ +1.06 就是车头） */
    private static final double CAB_HX = 0.62D;
    private static final double CAB_HY = 0.40D;
    private static final double CAB_Y = 1.05D;
    private static final double CAB_HZ = 0.44D;
    private static final double CAB_ZC = 0.62D;
    /** 挡风玻璃（车头正面浮出一点，避免 z-fighting） */
    private static final double WIN_HX = 0.48D;
    private static final double WIN_HY = 0.13D;
    private static final double WIN_Y = 1.25D;
    private static final double WIN_HZ = 0.02D;
    private static final double WIN_ZC = 1.06D;
    /** 驾驶室侧窗 */
    private static final double SIDEWIN_HX = 0.02D;
    private static final double SIDEWIN_HY = 0.11D;
    private static final double SIDEWIN_Y = 1.22D;
    private static final double SIDEWIN_HZ = 0.30D;
    private static final double SIDEWIN_ZC = 0.62D;
    /** 前保险杠 */
    private static final double BUMP_HX = 0.66D;
    private static final double BUMP_HY = 0.06D;
    private static final double BUMP_Y = 0.75D;
    private static final double BUMP_HZ = 0.04D;
    private static final double BUMP_ZC = 1.08D;
    /** 顶灯（★ 1.45..1.55 = 车高 1.55 的封顶，和包围盒/碰撞箱同一把尺子） */
    private static final double LAMP_HX = 0.10D;
    private static final double LAMP_HY = 0.05D;
    private static final double LAMP_Y = 1.50D;
    private static final double LAMP_HZ = 0.10D;
    private static final double LAMP_ZC = 0.55D;

    // ---------------------------------------------------------------- 颜色
    private static final float[] C_WHEEL = {0.18F, 0.19F, 0.22F};
    private static final float[] C_CHASSIS = {0.50F, 0.52F, 0.58F};
    private static final float[] C_BED = {0.42F, 0.44F, 0.50F};
    private static final float[] C_RAIL = {0.55F, 0.58F, 0.64F};
    private static final float[] C_CRATE = {0.48F, 0.36F, 0.22F};
    private static final float[] C_BARREL = {0.55F, 0.33F, 0.20F};
    private static final float[] C_CAB = {0.60F, 0.63F, 0.70F};
    private static final float[] C_GLASS = {0.16F, 0.20F, 0.28F};
    private static final float[] C_BUMP = {0.30F, 0.32F, 0.36F};
    private static final float[] C_LAMP_ON = {0.95F, 0.75F, 0.15F};
    private static final float[] C_LAMP_OFF = {0.40F, 0.32F, 0.12F};

    public FreightTruckRenderer(RenderManager renderManager) {
        super(renderManager);
        // 影子跟着车宽走
        this.shadowSize = 1.6F;
    }

    /**
     * ★ 返回 null = 框架不预绑贴图。本渲染器纯顶点色，全程不采样图集
     * （和 TrainRenderer 同一条纪律：要绑是自己手里绑，这里不需要）。
     */
    @Override
    protected ResourceLocation getEntityTexture(EntityFreightTruck entity) {
        return null;
    }

    // ================================================================ 主入口

    @Override
    public void doRender(EntityFreightTruck entity, double x, double y, double z,
                         float entityYaw, float partialTicks) {
        if (entity == null || entity.isDead) {
            return;
        }
        // ★ 离屏（监控摄像机的画面）那一趟要挡掉，否则重影/闪烁
        if (CameraFeedRenderer.isRenderingOffscreen()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            return;
        }
        if (this.renderManager == null) {
            return;
        }
        if (this.renderManager.getDistanceToCamera(entity.posX, entity.posY, entity.posZ)
                > VIEW_RANGE) {
            return;
        }

        // ---- 插值出"这一刻"的位置与朝向（prevSane 判据见类注释）
        double dxp = entity.posX - entity.prevPosX;
        double dyp = entity.posY - entity.prevPosY;
        double dzp = entity.posZ - entity.prevPosZ;
        boolean prevSane = (dxp * dxp + dyp * dyp + dzp * dzp) <= 1.0D;
        double ix = prevSane ? entity.prevPosX + dxp * partialTicks : entity.posX;
        double iy = prevSane ? entity.prevPosY + dyp * partialTicks : entity.posY;
        double iz = prevSane ? entity.prevPosZ + dzp * partialTicks : entity.posZ;
        float dyaw = normalizeDeg(entity.rotationYaw - entity.prevRotationYaw);
        float iyaw = prevSane ? entity.prevRotationYaw + dyaw * partialTicks : entity.rotationYaw;

        // 顶灯状态（DataWatcher，只在变化时同步 —— 客户端内核不 tick，不能读它）。
        // ★ 本环境 datasync 是新式 API：getDataManager().get(DataParameter)，不是 getByte(int)。
        boolean running = entity.getDataManager().get(EntityFreightTruck.STATE) == 1;

        // ---- 画
        GlStateManager.pushMatrix();
        GlStateManager.translate(ix, iy, iz);
        // 车体几何按"车头朝 +Z"写；MC 朝向 = (-sin yaw, cos yaw) → 这里必须是 -iyaw
        GlStateManager.rotate(-iyaw, 0.0F, 1.0F, 0.0F);

        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.disableTexture2D();
        // ★★ 关掉背面剔除：手写顶点的绕序写反了那一面就会整块消失
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 自发光：院子里的车在昏暗处也该看得清（和本模组其它顶点色渲染器一致）
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        // ---- 轮子（四个）
        for (int s = 0; s < 2; s++) {
            double wx = (s == 0) ? WHEEL_X : -WHEEL_X;
            for (int t = 0; t < 2; t++) {
                double wz = (t == 0) ? WHEEL_Z : -WHEEL_Z;
                box(buf, wx, WHEEL_HY, wz, WHEEL_HX, WHEEL_HY, WHEEL_HZ, C_WHEEL);
            }
        }
        // ---- 底盘 / 货台 / 侧栏
        box(buf, 0.0D, CHASSIS_Y, CHASSIS_ZC, CHASSIS_HX, CHASSIS_HY, CHASSIS_HZ, C_CHASSIS);
        box(buf, 0.0D, BED_Y, BED_ZC, BED_HX, BED_HY, BED_HZ, C_BED);
        box(buf, BED_HX, RAIL_Y, BED_ZC, RAIL_HX, RAIL_HY, RAIL_HZ, C_RAIL);
        box(buf, -BED_HX, RAIL_Y, BED_ZC, RAIL_HX, RAIL_HY, RAIL_HZ, C_RAIL);
        // ---- 货物
        box(buf, 0.0D, CRATE_Y, CRATE_ZC, CRATE_HX, CRATE_HY, CRATE_HZ, C_CRATE);
        box(buf, BARREL_X, BARREL_Y, BARREL_ZC, BARREL_HX, BARREL_HY, BARREL_HZ, C_BARREL);
        // ---- 驾驶室
        box(buf, 0.0D, CAB_Y, CAB_ZC, CAB_HX, CAB_HY, CAB_HZ, C_CAB);
        box(buf, 0.0D, WIN_Y, WIN_ZC, WIN_HX, WIN_HY, WIN_HZ, C_GLASS);
        box(buf, CAB_HX, SIDEWIN_Y, SIDEWIN_ZC, SIDEWIN_HX, SIDEWIN_HY, SIDEWIN_HZ, C_GLASS);
        box(buf, -CAB_HX, SIDEWIN_Y, SIDEWIN_ZC, SIDEWIN_HX, SIDEWIN_HY, SIDEWIN_HZ, C_GLASS);
        box(buf, 0.0D, BUMP_Y, BUMP_ZC, BUMP_HX, BUMP_HY, BUMP_HZ, C_BUMP);
        // ---- 顶灯（运行时亮）
        box(buf, 0.0D, LAMP_Y, LAMP_ZC, LAMP_HX, LAMP_HY, LAMP_HZ,
                running ? C_LAMP_ON : C_LAMP_OFF);

        tess.draw();

        // ★ 状态复原：进来的时候关掉的必须成对还原
        GlStateManager.enableCull();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.enableRescaleNormal();
        GlStateManager.popMatrix();
    }

    /** 角度差归一到 (-180, 180]，让插值走短弧（转向 180° 时不会"倒着转一圈"） */
    private static float normalizeDeg(float d) {
        while (d > 180.0F) {
            d -= 360.0F;
        }
        while (d < -180.0F) {
            d += 360.0F;
        }
        return d;
    }

    // ================================================================ 顶点工具

    /**
     * 世界轴对齐的盒子（局部坐标，车体几何全用它）。
     * 8 个角 + 6 个面；背面剔除是关着的，绕序不用管。
     */
    private static void box(BufferBuilder buf,
                            double cx, double cy, double cz,
                            double hx, double hy, double hz, float[] rgb) {
        double[] px = new double[8];
        double[] py = new double[8];
        double[] pz = new double[8];
        for (int i = 0; i < 8; i++) {
            double sx = (i & 1) == 0 ? -hx : hx;
            double sy = (i & 2) == 0 ? -hy : hy;
            double sz = (i & 4) == 0 ? -hz : hz;
            px[i] = cx + sx;
            py[i] = cy + sy;
            pz[i] = cz + sz;
        }
        int[][] faces = {
                {1, 3, 7, 5}, {0, 2, 6, 4},     // x=+ / x=-
                {2, 3, 7, 6}, {0, 1, 5, 4},     // y=+ / y=-
                {4, 5, 7, 6}, {0, 1, 3, 2},     // z=+ / z=-
        };
        for (int[] f : faces) {
            buf.pos(px[f[0]], py[f[0]], pz[f[0]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
            buf.pos(px[f[1]], py[f[1]], pz[f[1]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
            buf.pos(px[f[2]], py[f[2]], pz[f[2]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
            buf.pos(px[f[3]], py[f[3]], pz[f[3]]).color(rgb[0], rgb[1], rgb[2], 1.0F).endVertex();
        }
    }
}

package com.rsaudio.client;

import com.rsaudio.block.BlockMonitorConsole;
import com.rsaudio.tile.TileMonitorCamera;
import com.rsaudio.tile.TileMonitorConsole;
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

import java.util.List;

/**
 * 监控控制台方块上的屏幕：把摄像头的实时画面贴到机器正面。
 *
 * <h2>★ 这套几何是"自己算"的，不借原版的任何朝向约定</h2>
 * 一个平面上显示的画面，朝向错一点就会镜像/上下颠倒，而"方块模型的正面到底在哪个面"
 * "变体里的 y 角到底是顺时针还是逆时针"这类约定，看文档和凭记忆都极容易被别的版本带偏。
 * 这里的做法是把不确定性全部消掉：
 * <ol>
 *   <li><b>屏幕的四个角直接用世界坐标算。</b>
 *       站在屏幕外面看的人的"右"方向是 {@code 视线方向 × 上方向}，
 *       而视线方向是 {@code -FACING}，所以"屏幕右"就是 {@code -(FACING × (0,1,0))}。
 *       这纯粹是向量运算，和原版怎么写模型无关。</li>
 *   <li><b>不设 GL 旋转</b>（{@code rotate} 一次都不用），避免把符号搞反。</li>
 *   <li><b>画的时候关掉背面剔除</b>（{@code disableCull}），这样三角形的绕序也不重要了。</li>
 *   <li><b>方块模型的正面贴图是对称的边框</b>（见 {@code gen_resources.py} 里
 *       {@code tex_monitor_console_front} 的画法），所以模型那边的 UV 约定
 *       怎么算都看不出差别 —— 画面区域由本渲染器盖上去。</li>
 * </ol>
 * 剩下唯一的约定是 <b>帧缓冲纹理的 v 方向</b>：离屏画布是 OpenGL 画的，
 * 纹理原点在左下，所以"画面的上边"对应 {@code v = 1}。这一条有原版
 * {@code Framebuffer.framebufferRenderExt} 的顶点数据可以对着看，是确定的。
 *
 * <h2>为什么这里要登记 request</h2>
 * 有人看得见这块屏幕，画面才需要出帧。方块渲染器在绘制时登记一次，
 * 引擎在同一帧的世界渲染收尾处统一渲染 —— 所以屏幕上的画面和界面里的画面
 * 是<b>同一张</b>，不会为了两处显示各渲染一遍。
 */
public class MonitorConsoleRenderer extends TileEntitySpecialRenderer<TileMonitorConsole> {

    /** 超过这个距离就不画屏幕了：远处一排控制台不必都出帧 */
    private static final double VIEW_RANGE = 48.0D;

    /**
     * 屏幕区域（以格为单位，从方块中心算起）。
     * <p>
     * ★ 高度必须是 {@code 宽度 × 9 / 16}：离屏画布是 16:9（320×180），
     * 四边形按别的比例铺开就会把画面压扁/拉长。这两个数还和
     * {@code gen_resources.py} 里 {@code tex_monitor_console_front} 的深色屏范围
     * 一一对应（那边是 px x∈[2,13]、y∈[2,13]），改一个就要改另一个。
     */
    private static final double HALF_W = 0.375D;
    private static final double HALF_H = HALF_W * 9.0D / 16.0D;

    /** 屏幕面比方块表面再往外挪一点，避免和方块自身的面 z-fighting 打架 */
    private static final double SURFACE = 0.5D + 0.003D;

    @Override
    public void render(TileMonitorConsole te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        // ★ 离屏渲染那一趟本身也会走"渲染方块实体"，递归回来就会把监控屏幕
        //   画进监控画面里（无限套娃），而且白吃一遍性能。直接不画。
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

        TileMonitorCamera camera = ClientMonitorCache.nearestOn(te.getChannel(), pos);
        int texture = 0;
        if (camera != null) {
            CameraFeedRenderer.request(camera.getPos());
            texture = CameraFeedRenderer.getTexture(camera.getPos());
        }
        EnumFacing facing = facingOf(te);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableBlend();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 自发光：屏幕不该跟着房间的亮度变暗
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        if (texture != 0) {
            drawScreenQuad(facing, texture);
        } else {
            drawDeadScreen(facing);
        }
        if (te.isOverlay()) {
            drawOverlay(facing, te, camera);
        }

        GlStateManager.enableRescaleNormal();
        GlStateManager.enableCull();
        GlStateManager.enableBlend();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    /** 屏幕面上的四条边：右方向 + 法线 */
    private static final class Frame {
        final double nx;
        final double ny;
        final double nz;
        /** 站在外面看的"右" */
        final double ux;
        final double uz;

        Frame(EnumFacing facing) {
            this.nx = facing.getFrontOffsetX();
            this.ny = facing.getFrontOffsetY();
            this.nz = facing.getFrontOffsetZ();
            // 站在屏幕外面的人的"右" = 视线方向 × 上方向，而视线方向 = -n：
            //     (-n) × (0,1,0) = (n.z, 0, -n.x)
            // 验算 north(n=(0,0,-1))：u = (-1,0,0) = 西 —— 站在北边看南边，
            // 右手边确实是西，对得上。
            this.ux = facing.getFrontOffsetZ();
            this.uz = -facing.getFrontOffsetX();
        }

        double cx() {
            return 0.5D + nx * SURFACE;
        }

        double cy() {
            return 0.5D + ny * SURFACE;
        }

        double cz() {
            return 0.5D + nz * SURFACE;
        }
    }

    private void drawScreenQuad(EnumFacing facing, int texture) {
        Frame f = new Frame(facing);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        vertex(buffer, f, -1, +1, 0.0D, 1.0D);
        vertex(buffer, f, +1, +1, 1.0D, 1.0D);
        vertex(buffer, f, +1, -1, 1.0D, 0.0D);
        vertex(buffer, f, -1, -1, 0.0D, 0.0D);
        tessellator.draw();

        GlStateManager.bindTexture(0);
    }

    /**
     * 一个角。
     *
     * @param su   -1 = 屏幕左边，+1 = 屏幕右边
     * @param sv   -1 = 屏幕下边，+1 = 屏幕上边
     * @param texU 贴图 u（0 = 画面左边）
     * @param texV 贴图 v（1 = 画面上边；帧缓冲纹理原点在左下，所以上边是 1）
     */
    private void vertex(BufferBuilder buffer, Frame f, int su, int sv, double texU, double texV) {
        double px = f.cx() + f.ux * HALF_W * su;
        double py = f.cy() + HALF_H * sv;
        double pz = f.cz() + f.uz * HALF_W * su;
        buffer.pos(px, py, pz).tex(texU, texV).endVertex();
    }

    /** 没信号：一块深灰屏 + 一行字 */
    private void drawDeadScreen(EnumFacing facing) {
        Frame f = new Frame(facing);
        GlStateManager.disableTexture2D();
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        // 整块深灰
        coloredVertex(buffer, f, -1, +1, 0.05F, 0.05F, 0.06F);
        coloredVertex(buffer, f, +1, +1, 0.05F, 0.05F, 0.06F);
        coloredVertex(buffer, f, +1, -1, 0.05F, 0.05F, 0.06F);
        coloredVertex(buffer, f, -1, -1, 0.05F, 0.05F, 0.06F);
        // 中间一条浅色横带，像"没有信号的彩条"
        coloredVertex(buffer, f, -1, 0.20D, 0.16F, 0.16F, 0.20F);
        coloredVertex(buffer, f, +1, 0.20D, 0.16F, 0.16F, 0.20F);
        coloredVertex(buffer, f, +1, -0.20D, 0.16F, 0.16F, 0.20F);
        coloredVertex(buffer, f, -1, -0.20D, 0.16F, 0.16F, 0.20F);
        tessellator.draw();
        GlStateManager.enableTexture2D();
    }

    private void coloredVertex(BufferBuilder buffer, Frame f, double su, double sv,
                               float r, float g, float b) {
        double px = f.cx() + f.ux * HALF_W * su;
        double py = f.cy() + HALF_H * sv;
        double pz = f.cz() + f.uz * HALF_W * su;
        buffer.pos(px, py, pz).color(r, g, b, 1.0F).endVertex();
    }

    /**
     * 屏幕上的字幕条：频道号 + 摄像头名 + 画面里有几个人。
     * <p>
     * 文字用的是原版告示牌渲染器那一套变换（平移到屏幕面 → 转到朝向观看者 →
     * {@code scale(-0.01, -0.01, 0.01)} → drawString）。
     * <b>局部 +Z 要指向 FACING</b> —— 告示牌就是这个约定，四个朝向都对得上，
     * 所以这里照抄而不是重新推。
     */
    private void drawOverlay(EnumFacing facing, TileMonitorConsole te, TileMonitorCamera camera) {
        Frame f = new Frame(facing);
        List<String> seen = camera == null
                ? java.util.Collections.<String>emptyList()
                : CameraFeedRenderer.getSeen(camera.getPos());

        String line1 = "CH" + te.getChannel();
        String line2 = camera == null ? "--" : trim(camera.labelOrPos(), 14);
        String line3 = seen.isEmpty() ? "CLEAR" : ("TARGET " + seen.size());

        double baseX = f.cx() - f.ux * HALF_W;
        double baseY = f.cy() + HALF_H - 0.015D;
        double baseZ = f.cz() - f.uz * HALF_W;

        GlStateManager.pushMatrix();
        GlStateManager.translate(baseX + f.nx * 0.002D, baseY, baseZ + f.nz * 0.002D);
        GlStateManager.rotate(textAngle(facing), 0.0F, 1.0F, 0.0F);
        GlStateManager.scale(-0.011F, -0.011F, 0.011F);
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        getFontRenderer().drawString(line1, 0, 0, 0xFFFFFF);
        getFontRenderer().drawString(line2, 0, 10, seen.isEmpty() ? 0x90FF90 : 0xFFD060);
        getFontRenderer().drawString(line3, 0, 20, seen.isEmpty() ? 0x90FF90 : 0xFF8060);

        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    /**
     * 让文字面朝观看者的 Y 轴旋转角。
     * <p>
     * {@code rotate(θ, 0,1,0)} 把局部 {@code +Z} 转到 {@code (sinθ, 0, cosθ)}，
     * 我们要它等于 {@code FACING}，于是 north=180 / east=90 / south=0 / west=270
     * （正好就是原版告示牌用的 {@code -getHorizontalAngle()}）。
     */
    private static float textAngle(EnumFacing facing) {
        switch (facing) {
            case NORTH:
                return 180.0F;
            case EAST:
                return 90.0F;
            case SOUTH:
                return 0.0F;
            case WEST:
                return 270.0F;
            default:
                return 0.0F;
        }
    }

    private static EnumFacing facingOf(TileMonitorConsole te) {
        net.minecraft.block.state.IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (state.getBlock() instanceof BlockMonitorConsole) {
            return state.getValue(BlockMonitorConsole.FACING);
        }
        return EnumFacing.NORTH;
    }

    private static String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + "…";
    }
}

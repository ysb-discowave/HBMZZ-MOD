package com.rsaudio.client;

import com.rsaudio.block.BlockRemotePanel;
import com.rsaudio.panel.PanelCore;
import com.rsaudio.tile.TileRemotePanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

/**
 * 控制面板正面那 6 个按钮。
 *
 * <h2>★★ 这里的几何不是一个"画法"，它就是命中判定的同一份数据</h2>
 * 按钮的每个矩形都来自 {@link PanelCore#buttonRect}，观看者的"右"来自
 * {@link PanelCore#faceRightX}/{@link PanelCore#faceRightZ} ——
 * 和 {@code BlockRemotePanel#onBlockActivated} 里算 {@code (u,v)} 用的是<b>同一对函数</b>。
 * <p>
 * 这是整个面板功能里最要紧的一条：画和按一旦各写一份公式，症状就是
 * <b>看到的按钮和按下去的按钮不是同一个</b>（按"停机"点着了"点火"），
 * 而界面上完全看不出异常。共享内核之后，"画歪了"和"按错了"在结构上就不可能同时发生。
 * <p>
 * 具体到坐标系：都在面板局部的一个二维坐标 {@code (u,v)} 里画，
 * {@code u} 是"站在外面看时的左→右"，{@code v} 是方块自身的 y（下→上）。
 * 这个二维坐标到方块局部坐标的映射在 {@link PanelCore#facePointX} / {@link PanelCore#facePointZ}
 * 里，本类只负责把它接到底下的 {@link Face}。
 *
 * <h2>★ 为什么这里不画文字</h2>
 * 往方块面上贴文字要挑一套 GL 变换（绕 Y 旋转的角度、{@code scale} 的符号、
 * 文字的基准点），而"这套变换到底对不对"只有在游戏里用眼睛看才知道 ——
 * 这里没法验证。文字一旦镜像/上下颠倒，是那种"能编译、能运行、看着还挺像"的错。
 * <p>
 * 文案完全交给界面 —— {@code GuiRemotePanel} 里有一份"第几格是什么"的对照表，
 * 那是普通 2D 界面，没有朝向问题，写错了当场就能看见。
 * <p>
 * 所以世界里的面板只承担<b>位置 + 颜色 + 灯</b>：6 个按钮各有自己的底色
 * （{@link PanelCore#colorOf}），<b>每一颗都带一颗指示灯</b>
 * （{@link PanelCore#lampBit} / {@link PanelCore#lampOn} / {@link PanelCore#lampColorOn}）——
 * 亮 = 这一下按下去会生效、或这个状态已经成立；灭 = 没这回事。
 *
 * <h2>★★ 正面这个平面的坐标：不许在这里自己算</h2>
 * 面板上所有四边形的 x/z 都走 {@link PanelCore#facePointX} / {@link PanelCore#facePointZ}。
 * 2026-09-21 实锤过一次：这里原先自己写 {@code nx * SURFACE + ux * along}，
 * <b>漏了"方块最小角 → 面中心"的 0.5</b>，于是整块面板沿法线外推半格、沿 u 轴再偏半格 ——
 * 顶在墙上/嵌在凹槽里时四边形全被推进邻居方块、被深度测试挡掉，
 * 玩家看到的正面就只剩那张素板贴图：<b>一个按钮、一颗灯都没有</b>。
 * 交给内核之后，{@code check_panel_core} 能把"四个角必须落在这一格方块内"
 * 当成普通算术用例跑，这种事不会再靠眼睛发现。
 *
 * 代价是面板本体不能"读"出来，只能靠颜色和灯记。所以底色是按角色配的
 * （不可逆的动作用暖色、开关用冷色），并且<b>没接上目标时整块按钮会暗下去、灯全灭</b> ——
 * 这一条比文字更有用：它一眼就告诉你"按了也没用"。
 * <p>
 * 灯灭着的时候是<b>深绿灰的镜片</b>（{@link PanelCore#LAMP_COLOR_OFF}），不是纯黑：
 * 灭着的灯也得让人看得出"那里有一颗灯"，否则整块板子看起来就是"根本没有指示灯"。
 *
 * <h2>亮度</h2>
 * 按钮跟着方块所在位置的亮度走（暗环境里保留 35% 的底光，不然漆黑的机房里面板全黑），
 * 只有<b>指示灯</b>是自发光 —— 指示灯本来就是这个意思。
 */
public class PanelRenderer extends TileEntitySpecialRenderer<TileRemotePanel> {

    /** 超过这个距离就不画了。面板通常成排出现，远处一堵墙全是按钮没必要都算 */
    private static final double VIEW_RANGE = 48.0D;

    /**
     * 正面往外挪一点，避免和方块自身的面 z-fighting 打架。
     * ★ 数只有一个出处：{@link PanelCore#FACE_SURFACE}（监控控制台用的是同一个值）。
     */
    private static final double SURFACE = PanelCore.FACE_SURFACE;

    /** 暗环境里的最低亮度。0 = 全黑（看不见），1 = 完全不受光线影响 */
    private static final float MIN_BRIGHT = 0.35F;

    /** 按钮外框往外扩多少（归一化），做出"嵌在铁板里"的样子 */
    private static final double BEZEL = 0.008D;
    /** 灯（方灯）占按钮宽度的比例。位置：按钮右上角 */
    private static final double LAMP = 0.22D;
    /** 灯座比镜片外扩多少（归一化）：一圈深色边，看出"这颗灯是嵌进去的" */
    private static final double LENS_RIM = 0.007D;

    /** 外框颜色（深灰铁） */
    private static final float[] BEZEL_COLOR = {0.10F, 0.11F, 0.13F};
    /** 灯座（灯窝）颜色，比按钮更深 */
    private static final float[] LENS_SOCKET = {0.05F, 0.06F, 0.07F};
    /** 没接上目标时：整块按钮压暗到多少 */
    private static final float UNLINKED_DIM = 0.35F;

    @Override
    public void render(TileRemotePanel te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        // ★ 监控的离屏渲染那一趟本身也会走"渲染方块实体"。不挡的话，
        //   摄像头画面里会出现面板被画了两遍（而且那趟的光照/矩阵都对不上）。
        if (CameraFeedRenderer.isRenderingOffscreen()) {
            return;
        }
        if (destroyStage >= 0 || te == null || te.getWorld() == null || te.getPos() == null) {
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

        Face face = new Face(facingOf(te));
        int mode = te.getMode();
        int status = te.getStatusBits();
        boolean linked = (status & PanelCore.ST_LINKED) != 0;
        float bright = brightnessOf(te, face);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        drawHeader(buffer, face, mode, linked, bright);
        for (int i = 0; i < PanelCore.BUTTONS; i++) {
            drawButton(buffer, face, mode, i, status, linked, bright);
        }

        tessellator.draw();

        // 灯单独一趟：它们是自发光的，不该乘上面那个环境亮度。
        // ★ 6 颗按钮全都有灯（不再只画开关型那两颗）—— 否则玩家看到的是一块
        //   "根本没有指示灯"的板子，而不是"灯都灭着"的板子。
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int i = 0; i < PanelCore.BUTTONS; i++) {
            if (PanelCore.hasLamp(mode, i)) {
                drawLamp(buffer, face, mode, i, status);
            }
        }
        tessellator.draw();

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ 各个部件

    /**
     * 顶部那条：模式的底色 + 右侧一个"接没接上"的方点。
     * <p>
     * 面板装错地方（比如门模式却没同频道的门）最常见，而 <b>没接上</b> 这件事
     * 必须一眼可见：所以这里用一个饱和的红点，不是把颜色调暗一点点了事。
     */
    private void drawHeader(BufferBuilder buffer, Face face, int mode, boolean linked, float bright) {
        float[] band = mode == PanelCore.MODE_DMR
                ? new float[]{0.32F, 0.26F, 0.10F}     // 琥珀：反应堆
                : new float[]{0.10F, 0.28F, 0.30F};    // 青：大门
        quad(buffer, face, PanelCore.MARGIN_U, PanelCore.HEADER_V0,
                1.0D - PanelCore.MARGIN_U, PanelCore.HEADER_V1,
                band[0] * bright, band[1] * bright, band[2] * bright, 1.0F);

        // 状态点：右端一个小方块
        double s = 0.05D;
        double u1 = 1.0D - PanelCore.MARGIN_U - 0.01D;
        double v0 = (PanelCore.HEADER_V0 + PanelCore.HEADER_V1) * 0.5D - s * 0.5D;
        float[] dot = linked ? new float[]{0.25F, 0.85F, 0.35F} : new float[]{0.85F, 0.20F, 0.20F};
        quad(buffer, face, u1 - s, v0, u1, v0 + s, dot[0], dot[1], dot[2], 1.0F);
    }

    /** 一个按钮：先画深色外框，再画彩色面板 */
    private void drawButton(BufferBuilder buffer, Face face, int mode, int index, int status,
                            boolean linked, float bright) {
        double[] r = PanelCore.buttonRect(index);
        if (r == null) {
            return;
        }
        quad(buffer, face, r[0] - BEZEL, r[1] - BEZEL, r[2] + BEZEL, r[3] + BEZEL,
                BEZEL_COLOR[0] * bright, BEZEL_COLOR[1] * bright, BEZEL_COLOR[2] * bright, 1.0F);

        int rgb = PanelCore.colorOf(mode, index);
        float k = linked ? bright : UNLINKED_DIM;
        quad(buffer, face, r[0], r[1], r[2], r[3],
                ((rgb >> 16) & 0xFF) / 255.0F * k,
                ((rgb >> 8) & 0xFF) / 255.0F * k,
                (rgb & 0xFF) / 255.0F * k,
                1.0F);
    }

    /**
     * 按钮右上角那颗指示灯：先画一圈深色灯座，再画镜片。
     * <p>
     * ★ <b>灭着的灯也必须看得见</b>：镜片用 {@link PanelCore#LAMP_COLOR_OFF}（深绿灰），
     * 不是接近纯黑。理由不是好看 —— 如果灭灯和底色一样黑，整块面板看起来就是
     * "这是块没有指示灯的素板"，玩家会以为功能没做出来（2026-09-21 玩家的原话就是
     * "面板没有指示器"）。灯座那一圈深色边就是为了让"灭"也有轮廓。
     * <p>
     * 亮/灭和颜色都由内核给（{@link PanelCore#lampOn} / {@link PanelCore#lampColorOn}），
     * 这里不参与判断 —— 面板灯亮错了，玩家会照着一个错的状态去操作。
     */
    private void drawLamp(BufferBuilder buffer, Face face, int mode, int index, int status) {
        double[] r = PanelCore.buttonRect(index);
        if (r == null) {
            return;
        }
        double w = (r[2] - r[0]) * LAMP;
        double pad = BEZEL * 1.6D;
        double u1 = r[2] - pad;
        double v1 = r[3] - pad;

        // 灯座：比镜片再外扩一圈
        quad(buffer, face, u1 - w - LENS_RIM, v1 - w - LENS_RIM, u1 + LENS_RIM, v1 + LENS_RIM,
                LENS_SOCKET[0], LENS_SOCKET[1], LENS_SOCKET[2], 1.0F);

        // 镜片：亮 = 内核给的颜色，灭 = 深绿灰（★ 见上面注释，不能是纯黑）
        int rgb = PanelCore.lampOn(mode, index, status)
                ? PanelCore.lampColorOn(mode, index)
                : PanelCore.LAMP_COLOR_OFF;
        quad(buffer, face, u1 - w, v1 - w, u1, v1,
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                1.0F);
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 面板正面这个平面。
     * <p>
     * 只做水平四向，所以这里只有绕 Y 的变化 —— 竖直方向就是方块自身的 y，不参与旋转，
     * 也就不存在上下颠倒的可能。
     * <p>
     * ★ 注意这个类里<b>没有</b>"观看者的右"了：那个概念（和它在世界坐标里的分量）
     * 只在 {@link PanelCore#faceRightX}/{@link PanelCore#faceRightZ} 一处存在，
     * 画和按都从那里取。少一个副本就少一次"两边各演化一次"的机会。
     */
    private static final class Face {
        /** 正面朝向。亮度探测要用它取"面板正前方那一格" */
        final EnumFacing facing;
        final double nx;
        final double nz;

        Face(EnumFacing facing) {
            this.facing = facing;
            this.nx = facing.getFrontOffsetX();
            this.nz = facing.getFrontOffsetZ();
        }

        /**
         * 归一化面坐标 {@code (u,v)} → <b>方块局部坐标</b>（{@code 0..1} 就是这一格方块里）。
         * <p>
         * ★★ 这里只剩转发，一行算术都不留。原先这里自己写
         * {@code nx * SURFACE + ux * along}，漏了"方块最小角 → 面中心"的那 0.5，
         * 整块面板被推到<b>隔壁半格</b>里去了 —— 顶在墙上或嵌在凹槽里时，四边形被
         * 邻居方块挡住（深度测试），正面看起来一个按钮、一颗灯都没有。
         * 现在几何的唯一出处是 {@link PanelCore#facePointX} / {@link PanelCore#facePointZ}，
         * 那边有 {@code check_panel_core} 的"四个角必须落在这一格方块内"用例盯着。
         */
        double[] point(double u, double v) {
            return new double[]{
                    PanelCore.facePointX(nx, nz, u, SURFACE),
                    v,
                    PanelCore.facePointZ(nx, nz, u, SURFACE)};
        }
    }

    /** 画一个矩形（四个角按 (u0,v0)-(u1,v0)-(u1,v1)-(u0,v1) 的顺序） */
    private void quad(BufferBuilder buffer, Face face, double u0, double v0, double u1, double v1,
                      float r, float g, float b, float a) {
        vertex(buffer, face, u0, v0, r, g, b, a);
        vertex(buffer, face, u1, v0, r, g, b, a);
        vertex(buffer, face, u1, v1, r, g, b, a);
        vertex(buffer, face, u0, v1, r, g, b, a);
    }

    private void vertex(BufferBuilder buffer, Face face, double u, double v,
                        float r, float g, float b, float a) {
        double[] p = face.point(u, v);
        buffer.pos(p[0], p[1], p[2]).color(r, g, b, a).endVertex();
    }

    /**
     * 面板所在位置的环境亮度，0..1。
     * <p>
     * 这里用的是 {@code getLightFromNeighbors} 的 0..15 再线性映射，而不是真正的
     * 光照贴图采样（{@code getCombinedLight} 的打包值要按位拆）。对"面板看着别太亮/别全黑"
     * 这个目的来说，线性映射足够，而且是<b>可读的</b> —— 不用去对着位域较劲。
     */
    private static float brightnessOf(TileRemotePanel te, Face face) {
        net.minecraft.world.World world = te.getWorld();
        if (world == null) {
            return 1.0F;
        }
        // 探"面板正前方那一格"和"面板上方那一格"，取较亮的那个：
        // 面板贴着的墙挡住了自己那一格的光照，只看自己会偏暗
        BlockPos probe = te.getPos().offset(face.facing);
        int level = Math.max(world.getLightFromNeighbors(probe), world.getLightFromNeighbors(te.getPos().up()));
        float lit = Math.min(1.0F, Math.max(0.0F, level / 15.0F));
        return MIN_BRIGHT + (1.0F - MIN_BRIGHT) * lit;
    }

    private static EnumFacing facingOf(TileRemotePanel te) {
        net.minecraft.block.state.IBlockState state = te.getWorld().getBlockState(te.getPos());
        if (state.getBlock() instanceof BlockRemotePanel) {
            return state.getValue(BlockRemotePanel.FACING);
        }
        return EnumFacing.NORTH;
    }
}

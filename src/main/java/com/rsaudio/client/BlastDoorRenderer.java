package com.rsaudio.client;

import com.rsaudio.access.AccessCore;
import com.rsaudio.block.BlockAlarmLight;
import com.rsaudio.block.BlockBlastDoor;
import com.rsaudio.tile.TileBlastDoor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

import java.util.Set;
import java.util.TreeSet;

/**
 * 防核门上的<b>旋转警报灯</b>（TESR，挂在控制器方块实体上）。
 *
 * <h3>几何</h3>
 * 控制器永远在门左下角，门沿"朝向的右手方向"展开 {@code width} 格、向上 {@code height} 格。
 * 这里沿门<b>顶边</b>均匀摆几颗旋转警报灯（角上必放，宽了再加中间 / 四分点），
 * 从门面稍微凸出一点。整扇门只有控制器这一个方块实体，
 * 所以所有灯都由这<b>一个</b> TESR 一次画完（不需要给每颗灯单独造方块实体）。
 *
 * <h3>★ "旋转"是怎么来的</h3>
 * 每颗灯 = 一颗自发光的灯体（立方体，加色混合让它亮起来）
 * + 一对互相垂直、绕竖直轴旋转的半透明光刃。光刃转起来就是常见的"警灯扫光"。
 * 门处于<b>警报态</b>（被封锁 / 被联锁挡住 / 气闸循环中）时扫得更快、闪得更急，
 * 平时则是一圈舒缓的旋转光，纯装饰。
 *
 * <h3>★ 坐标约定（和本项目其它 TESR 一致）</h3>
 * Forge 调 {@code render} 时已经把 GL 矩阵平移到"控制器那格的世界坐标（相对相机）"，
 * 所以进来先 {@code translate(x,y,z)}，之后就在<b>方块局部坐标</b>里画
 * （(0,0,0) = 控制器方块中心，+Y = 上，门向右 / 向上按格数偏移）。
 */
public class BlastDoorRenderer extends TileEntitySpecialRenderer<TileBlastDoor> {

    /** 超出这个距离就不画了（门通常不大，远处没必要算） */
    private static final double VIEW_RANGE = 64.0D;
    /** 灯体从门面再往外凸多少（门面已经在方块边界上，凸一点点就露在门外了） */
    private static final double PROTRUDE = 0.06D;

    /** 六色警报灯调色板（顺序 = {@link BlockAlarmLight#COLOR_NAMES}） */
    private static final float[][] ALARM_RGB = {
            {1.00F, 0.10F, 0.10F}, // red
            {1.00F, 0.45F, 0.00F}, // orange
            {0.95F, 0.90F, 0.10F}, // yellow
            {0.20F, 1.00F, 0.25F}, // green
            {0.20F, 0.55F, 1.00F}, // blue
            {0.72F, 0.30F, 1.00F}, // purple
    };

    @Override
    public void render(TileBlastDoor te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        // ★ 监控离屏渲染那一趟也会走"渲染方块实体"，挡掉避免画面里灯画两遍
        if (CameraFeedRenderer.isRenderingOffscreen()) {
            return;
        }
        if (destroyStage >= 0 || te == null || te.getWorld() == null || te.getPos() == null) {
            return;
        }
        // 没建好的门既没有门体、也没有门框可挂灯
        // ★ 注意这里<b>不再</b>因为"警报灯关了"就整扇不画 —— 门体是必须画的，
        //   只有那几颗旋转灯才是可选的（下面单独判断）。
        if (!te.isBuilt()) {
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

        net.minecraft.block.state.IBlockState state = te.getWorld().getBlockState(pos);
        EnumFacing facing;
        if (state.getBlock() instanceof BlockBlastDoor) {
            facing = state.getValue(BlockBlastDoor.FACING);
        } else {
            facing = EnumFacing.NORTH;
        }
        EnumFacing right = facing.rotateY();
        int fx = facing.getFrontOffsetX(), fz = facing.getFrontOffsetZ();
        int rx = right.getFrontOffsetX(), rz = right.getFrontOffsetZ();

        int width = te.getWidth();
        int height = te.getHeight();
        int color = Math.max(0, Math.min(ALARM_RGB.length - 1, te.getAlarmColor()));
        float[] rgb = ALARM_RGB[color];

        // 警报态：被封锁 / 被联锁挡住 / 气闸循环中 —— 这种时候灯要更急更亮
        boolean alarmed = te.isLatched()
                || te.getCycleRemaining() > 0
                || te.getBlockReason() != AccessCore.REASON_NONE;
        float spin = alarmed ? 0.20F : 0.09F;
        float aMul = alarmed ? 1.0F : 0.65F;

        long t = te.getWorld().getTotalWorldTime();
        float angle = (t + partialTicks) * spin;
        // 一闪一闪：爆闪感（平时也轻微呼吸）
        float pulse = 0.65F + 0.35F * (float) Math.sin((t + partialTicks) * 0.25F);

        int[] cols = beaconColumns(width);

        // ★ 客户端缓动 + 滑动量。门体和警报灯<b>必须共用同一个 slide</b>：
        //   灯是按"门顶边"这个固定高度摆的，门滑走了灯不跟着走就会悬在半空。
        te.stepClientAnim();
        float anim = te.getClientAnim();
        float slide = (te.isRetractUp() ? 1.0F : -1.0F) * anim * Math.max(1, height);

        // ★ 门体的水平包围盒（和 renderBody 同一套算法，见那边的几何说明）。
        //   警报灯要挂在门<b>正面</b>上，所以也得知道正面在哪一个坐标面上
        double x0 = Math.min(0.0D, rx * (double) (width - 1));
        double x1 = Math.max(0.0D, rx * (double) (width - 1)) + 1.0D;
        double z0 = Math.min(0.0D, rz * (double) (width - 1));
        double z1 = Math.max(0.0D, rz * (double) (width - 1)) + 1.0D;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);

        // ---------- ① 门体（必画）：沿收进方向连续平移的装甲板 ----------
        renderBody(te, fx, fz, rx, rz, slide, x0, x1, z0, z1);

        // ---------- ② 旋转警报灯（可在设置里关掉）----------
        if (!te.isAlarmLights()) {
            GlStateManager.popMatrix();
            return;
        }

        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE); // 加色发光
        GlStateManager.depthMask(false);

        for (int dx : cols) {
            // ★ 沿门宽那一轴用"这一列的中心"，沿门厚那一轴挂在<b>门正面</b>再往外凸一点
            double bx = (fx != 0) ? frontFaceCoord(fx, x0, x1, PROTRUDE) : columnCenter(rx, dx);
            double bz = (fz != 0) ? frontFaceCoord(fz, z0, z1, PROTRUDE) : columnCenter(rz, dx);
            // ★ 门顶边往下一点 —— 再跟着门一起滑（slide 和门体共用），否则灯会悬空
            double by = slide + height - 0.18D;

            GlStateManager.pushMatrix();
            GlStateManager.translate(bx, by, bz);
            GlStateManager.rotate(angle, 0.0F, 1.0F, 0.0F);

            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

            // 灯体（实心亮立方体）
            addCube(buf, 0, 0, 0, 0.11F, 0.11F, 0.11F, rgb[0], rgb[1], rgb[2], 1.0F);
            // 外圈光晕
            addCube(buf, 0, 0, 0, 0.26F, 0.26F, 0.26F,
                    rgb[0] * 0.5F, rgb[1] * 0.5F, rgb[2] * 0.5F, 0.22F * aMul);
            // 旋转光刃（两片互相垂直的竖立薄面，绕 Y 轴扫动）
            float beamA = 0.30F * aMul * pulse;
            beam(buf, rgb, beamA);

            tess.draw();
            GlStateManager.popMatrix();
        }

        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    /**
     * 画<b>门体本身</b>：一整块沿收进方向连续平移的装甲板。
     *
     * <h3>为什么必须自己画</h3>
     * 方块模型只能"一帧换一张贴图"（{@code open} 从 true 切到 false），
     * 那样门<b>永远不会动</b>，只是"啪"地换张图 —— 用户要的动画只能靠 TESR 平移来做。
     * 所以 {@code BlockBlastDoor} / {@code BlockBlastDoorPanel} 都设成了
     * {@code INVISIBLE}，门体全由这一处画。
     *
     * <h3>★★ 几何（这一段重写过 —— 上一版把整扇门画错了一格）</h3>
     * 进来时已经 {@code translate(x,y,z)} 过，原点在控制器那格的<b>最小角</b>，
     * 控制器自己占 {@code [0,1]³}。于是门体的包围盒是：
     * <ul>
     *   <li><b>沿门厚（朝向那一轴）</b>：只有控制器自己那一格 → {@code [0,1]}。</li>
     *   <li><b>沿门宽（right 那一轴）</b>：{@code width} 格。★ 但 {@code right} 可能是
     *       <b>负方向</b>（朝南的门 right = 西、朝西的门 right = 北），那时门体是在
     *       控制器的<b>负侧</b>展开的 —— 所以边界必须按 {@code min/max} 算，
     *       不能想当然地写 {@code 0..w}。上一版就是这么写的，结果朝南 / 朝西的门
     *       整扇平移了一格（"模型是乱的"）。</li>
     *   <li><b>竖直</b>：{@code [slide, slide + height]}。</li>
     * </ul>
     * 另外"正面"是朝外法线那一侧的那一面：朝北的门正面在 {@code z = 0}、
     * 朝南的在 {@code z = 1}（不是一律 {@code z = 0}）。上一版直接拿
     * {@code facing} 的偏移量当地面坐标，朝北 / 朝西的门整扇被推到门外一格。
     *
     * <h3>贴图</h3>
     * 和 {@code models/block/blast_door_closed.json} 完全一致：正面 {@code door_front}、
     * 背面和两个端面 {@code door_side}、顶 {@code door_top}、底 {@code door_bottom}。
     * 保持和原版方块模型同一套贴图，门看起来才和建出来时一样。
     */
    private void renderBody(TileBlastDoor te, int fx, int fz, int rx, int rz, float slide,
                            double x0, double x1, double z0, double z1) {
        int w = Math.max(1, te.getWidth());
        int h = Math.max(1, te.getHeight());

        double y0 = slide;
        double y1 = slide + h;

        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        TextureAtlasSprite front = sprite("rsaudio:blocks/door_front");
        TextureAtlasSprite side = sprite("rsaudio:blocks/door_side");
        TextureAtlasSprite top = sprite("rsaudio:blocks/door_top");
        TextureAtlasSprite bottom = sprite("rsaudio:blocks/door_bottom");

        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);

        double[] up = { 0.0D, 1.0D, 0.0D };
        // ★★★ 门里其实需要<b>两把</b>尺子，不是同一把：
        //   ① frontToBack：从<b>正面</b>穿到<b>背面</b>，方向 = -facing。
        //      只用在 bo（背面原点）的计算上。
        //   ② endThick：从<b>端面</b>指向<b>门内部</b>，方向 = 跟门宽正交的那一轴上的恒正方向。
        //      用在端面 + 顶 + 底（它们都从端面原点出发，步进 1 格 = 门厚 1 格）。
        //
        //   上版只声明了一把 `thick = {-fx, 0, -fz}`，它正好是 frontToBack 的正确方向，
        //   所以 bo 算对了；但被拿去当 endThick 用就是错的 —— 那是<b>从门内指向门外</b>
        //   的方向，不是"从端面指向门内"。朝南（fz=+1）时它指向 -Z → 端面/顶/底全画到
        //   z=-1 那格（门外），朝东（fx=+1）时同理画到 x=-1。
        //   朝北/朝西凑巧对齐（-facing 恰好就是端面该走的方向），所以 bug 只在另一半朝向
        //   出现，肉眼很容易看成"贴图反了 / 画在门外一格"。
        double[] frontToBack = { -fx, 0.0D, -fz };
        double[] endThick = (rx != 0) ? new double[] { 0.0D, 0.0D, 1.0D }
                                     : new double[] { 1.0D, 0.0D, 0.0D };
        // ★ 门宽方向：<b>每格</b>的步长，恒指向 +X 或 +Z，长度必须是 1。
        //   sheet() 的语义是"沿 du 铺 nU 格"，du 是单格向量；
        //   把整个门宽（w）当 du 传进去的话，门会被铺成 w×w 格 —— 直接炸掉。
        double[] wide = (rx != 0) ? new double[] { 1.0D, 0.0D, 0.0D }
                                  : new double[] { 0.0D, 0.0D, 1.0D };

        // 正面：朝向那一轴上"朝外"的那一面（朝北 = z0、朝南 = z1、朝西 = x0、朝东 = x1）
        double[] fo = (fx != 0) ? p(fx > 0 ? x1 : x0, y0, z0) : p(x0, y0, fz > 0 ? z1 : z0);
        // 背面 = 正面 + frontToBack
        double[] bo = p(fo[0] + frontToBack[0], y0, fo[2] + frontToBack[2]);
        // 门宽方向的两个端面
        double[] e0 = p(x0, y0, z0);
        double[] e1 = (rx != 0) ? p(x1, y0, z0) : p(x0, y0, z1);

        sheet(buf, front, fo, wide, up, w, h);      // 正面（朝外）
        sheet(buf, side, bo, wide, up, w, h);       // 背面
        sheet(buf, side, e0, endThick, up, 1, h);   // 端面 A
        sheet(buf, side, e1, endThick, up, 1, h);   // 端面 B
        sheet(buf, top, p(x0, y1, z0), wide, endThick, w, 1);     // 顶
        sheet(buf, bottom, p(x0, y0, z0), wide, endThick, w, 1);  // 底

        tess.draw();

        // ★ 把状态还原回去：TESR 是共用一个 GL 上下文的，
        //   漏掉还原会让后面画的（警报灯、别的方块实体）跟着变暗或穿模
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
    }

    /**
     * 门体<b>正面</b>在朝向那一轴上的坐标，再加 {@code out} 的向外凸出量 —— 警报灯挂在这里。
     */
    private static double frontFaceCoord(int f, double lo, double hi, double out) {
        if (f == 0) {
            return 0.0D;
        }
        return f > 0 ? hi + out : lo - out;
    }

    /** 门体某一列（dx）在门宽那一轴上的中心坐标 */
    private static double columnCenter(int r, int dx) {
        // 第 dx 格的最小角在 r*dx，格子往 +轴 方向占一格 → 中心是 r*dx + 0.5
        return r * dx + 0.5D;
    }

    private static double[] p(double x, double y, double z) {
        return new double[] { x, y, z };
    }

    /**
     * 铺一片贴图：沿 {@code du} 铺 {@code nU} 格、沿 {@code dv} 铺 {@code nV} 格，
     * <b>每一格一整张贴图</b>。
     *
     * <h3>★ 这一句就是"拉伸"的根因与修法</h3>
     * 上一版是整个面只铺一张贴图：7 格宽 × 7 格高的门，等于把一张 16×16 的图
     * 拉成一面墙 —— 玩家看到的就是"拉伸门"、糊成一团、某些面甚至看不出是贴图。
     * 改成<b>按格子铺</b>之后，每一格和原来方块模型看到的完全一致，
     * 门多大就自然重复多少张，不会拉。
     */
    private static void sheet(BufferBuilder buf, TextureAtlasSprite sp,
                              double[] o, double[] du, double[] dv, int nU, int nV) {
        for (int i = 0; i < nU; i++) {
            for (int j = 0; j < nV; j++) {
                double ax = o[0] + du[0] * i + dv[0] * j;
                double ay = o[1] + du[1] * i + dv[1] * j;
                double az = o[2] + du[2] * i + dv[2] * j;
                double bx = ax + du[0], by = ay + du[1], bz = az + du[2];
                double cx = bx + dv[0], cy = by + dv[1], cz = bz + dv[2];
                double dx = ax + dv[0], dy = ay + dv[1], dz = az + dv[2];

                buf.pos(ax, ay, az).tex(sp.getMinU(), sp.getMaxV()).endVertex();
                buf.pos(bx, by, bz).tex(sp.getMaxU(), sp.getMaxV()).endVertex();
                buf.pos(cx, cy, cz).tex(sp.getMaxU(), sp.getMinV()).endVertex();
                buf.pos(dx, dy, dz).tex(sp.getMinU(), sp.getMinV()).endVertex();
            }
        }
    }

    private static TextureAtlasSprite sprite(String name) {
        return Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(name);
    }

    /**
     * 顶边放灯的列下标：角上必放，宽了再加中间 / 四分点。
     * 这样既不会在 1 格门上摆一堆，也不会在 7 格门上只有一个。
     */
    private static int[] beaconColumns(int width) {
        Set<Integer> set = new TreeSet<Integer>();
        set.add(0);
        set.add(width - 1);
        set.add(width / 2);
        if (width >= 5) {
            set.add(width / 4);
            set.add((width * 3) / 4);
        }
        int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) {
            out[i++] = v;
        }
        return out;
    }

    /** 以 (cx,cy,cz) 为中心、半边长 (hx,hy,hz) 的发光立方体（6 面） */
    private static void addCube(BufferBuilder buf, double cx, double cy, double cz,
                                float hx, float hy, float hz, float r, float g, float b, float a) {
        // +X / -X
        quad(buf, cx + hx, cy - hy, cz - hz, cx + hx, cy - hy, cz + hz, cx + hx, cy + hy, cz + hz, cx + hx, cy + hy, cz - hz, r, g, b, a);
        quad(buf, cx - hx, cy - hy, cz + hz, cx - hx, cy - hy, cz - hz, cx - hx, cy + hy, cz - hz, cx - hx, cy + hy, cz + hz, r, g, b, a);
        // +Y / -Y
        quad(buf, cx - hx, cy + hy, cz - hz, cx + hx, cy + hy, cz - hz, cx + hx, cy + hy, cz + hz, cx - hx, cy + hy, cz + hz, r, g, b, a);
        quad(buf, cx - hx, cy - hy, cz + hz, cx + hx, cy - hy, cz + hz, cx + hx, cy - hy, cz - hz, cx - hx, cy - hy, cz - hz, r, g, b, a);
        // +Z / -Z
        quad(buf, cx - hx, cy - hy, cz + hz, cx + hx, cy - hy, cz + hz, cx + hx, cy + hy, cz + hz, cx - hx, cy + hy, cz + hz, r, g, b, a);
        quad(buf, cx + hx, cy - hy, cz - hz, cx - hx, cy - hy, cz - hz, cx - hx, cy + hy, cz - hz, cx + hx, cy + hy, cz - hz, r, g, b, a);
    }

    private static void quad(BufferBuilder buf,
                              double x0, double y0, double z0,
                              double x1, double y1, double z1,
                              double x2, double y2, double z2,
                              double x3, double y3, double z3,
                              float r, float g, float b, float a) {
        buf.pos(x0, y0, z0).color(r, g, b, a).endVertex();
        buf.pos(x1, y1, z1).color(r, g, b, a).endVertex();
        buf.pos(x2, y2, z2).color(r, g, b, a).endVertex();
        buf.pos(x3, y3, z3).color(r, g, b, a).endVertex();
    }

    /** 一对绕 Y 轴旋转的竖立光刃（半透明薄片）：第 1 片沿 +X，第 2 片沿 +Z，互相垂直，
     *  保证从任意水平角度看都能看到扫光，而不是只有正对时才亮 */
    private static void beam(BufferBuilder buf, float[] rgb, float a) {
        float len = 2.2F;
        float half = 0.5F;
        // 第 1 片：XY 平面（沿 +X 伸出）
        quad(buf, 0, -half, 0, len, -half, 0, len, half, 0, 0, half, 0, rgb[0], rgb[1], rgb[2], a);
        // 第 2 片：ZY 平面（沿 +Z 伸出）
        quad(buf, 0, -half, 0, 0, -half, len, 0, half, len, 0, half, 0, rgb[0], rgb[1], rgb[2], a);
    }
}

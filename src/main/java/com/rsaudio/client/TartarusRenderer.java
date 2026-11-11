package com.rsaudio.client;

import com.rsaudio.tile.TileTartarus;
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
 * 塔塔鲁斯的 TESR：画<b>巨大的圆形金属舱盖门</b>，并按开合进度<b>向下缓慢平移</b>。
 *
 * <h3>坐标约定</h3>
 * Forge 调 {@code render} 时已把 GL 平移到控制器那格的世界坐标（相对相机），
 * 进来先 {@code translate(x,y,z)}，之后在<b>方块局部坐标</b>里画
 * （(0,0,0) = 方块最小角，+Y = 上，圆盘中心在 (0.5, *, 0.5)）。
 *
 * <h3>为什么用 TESR 而不是方块模型</h3>
 * 圆形盘、向下平移、平滑插值这些事，静态方块模型做不到。控制器和舱盖格都是
 * {@code INVISIBLE}，真正的几何全在这里画。盘下沉时舱盖格已变可通过，
 * 于是它"沉进竖井"的样子自然成立。
 *
 * <h3>★★ 这一版重写过：从"彩色小圆盘"改成"巨型金属舱盖门" ★★</h3>
 * 上一版有几个互相叠加的问题，合起来让玩家看到的是"<b>彩色小圆盘</b>"：
 * <ol>
 *   <li><b>无贴图</b>：全程 {@code POSITION_COLOR} + {@code disableTexture2D()}，
 *       几何完全靠纯色填充。侧面和底面还是同一颜色的 {@code *0.7F}/{@code *0.8F} 变暗版，
 *       于是整个盘读起来是"一块塑料片"，而不是钢。上色又是亮灰 + 亮黄，
 *       正是"<b>彩色</b>"那两个字的来源。</li>
 *   <li><b>太薄</b>：盘厚只有 {@code 0.30} 格（不到 1/3 格），
 *       半径却有好几格 —— 一个直径 8 格、厚度 0.3 格的东西，几何上就是个盘片。</li>
 *   <li><b>没有结构</b>：盘面是一整个平环，没有任何分段、加强肋、铆钉、检修口。
 *       "舱盖门"是由一块块钢板拼起来、中间有枢纽、外圈有危险条的机械结构，
 *       平面色块表达不出这个。</li>
 *   <li><b>默认太小</b>：默认半径 4 = 直径 8 格，谈不上"巨型"。</li>
 * </ol>
 *
 * <h4>这一版的做法</h4>
 * <ul>
 *   <li>全程 {@code POSITION_TEX_COLOR}（带贴图），贴图取
 *       {@code tile.rsaudio.tartarus} 那一套（由 {@code tools/gen_resources.py} 生成）。
 *       贴图的 UV <b>按格铺</b>：径向每 {@code TEX_SCALE} 格重复一次、环向按弧长重复 ——
 *       这样大圆盘上是"密密麻麻的铆接钢板"，不会像一整张贴图被拉伸糊掉。</li>
 *   <li>盘体加厚到 {@link #DISC_THICK}（约半格），并<b>分段</b>：
 *       {@link #segmentsFor(int)} 算出来的那组扇形舱盖板，每块顶面都比基准<b>抬起</b>一丁点
 *       （{@link #PANEL_LIFT}）。抬的是顶面、底面不动 —— 于是格与格之间是一道
 *       <b>向下的凹槽</b>，近看是一块一块的板，远看有细密的立体分割。
 *       ★ 块数<b>按半径算</b>而不是固定值：固定 8 块在半径 16 的盘上会变成
 *       八个 45° 的大色块，又退回"色块"观感了。</li>
 *   <li>分段之间插 <b>放射状加强肋</b>（窄的、略低的暗色条）——
 *       舱盖的"骨架"。</li>
 *   <li>盘面中央挖出真正的<b>枢纽井</b>（画深色内壁），中心枢纽从井里凸出来。</li>
 *   <li>外缘是<b>厚侧壁 + 一圈黄黑危险条 + 最外沿一层盖边</b>，把圆盘的轮廓收干净。</li>
 * </ul>
 *
 * <h4>★ 贴图名与 UV 方向这两个坑（都踩过）</h4>
 * ① 取贴图必须用 <b>{@code rsaudio:blocks/xxx}</b>（带 {@code blocks/}），
 *    因为 {@code TextureMap} 的图集里，方块的贴图就是注册在这个路径下的。
 *    写成 {@code rsaudio:xxx} 会 {@code getAtlasSprite} 拿到
 *    {@code missingno}（紫黑）—— 而且不报错，只是整块变紫黑。
 * ② 朝上的面（顶面）UV 必须让<b>世界 +Z 对应 v 减小的方向</b>，
 *    否则整张贴图会南北翻转。朝下的面（底面）则相反。
 *    这里用 {@link #sector} 的 {@code down} 参数统一处理，不要在两处各写一遍。
 *
 * <h3>动画</h3>
 * 客户端的 {@link TileTartarus#clientAnim} 自己朝意图插值，保证连贯、且不必每 tick 收网络包。
 */
public class TartarusRenderer extends TileEntitySpecialRenderer<TileTartarus> {

    /** 超出这个距离就不画了（圆盘大，远处没必要算） */
    private static final double VIEW_RANGE = 96.0D;
    /** 圆盘分段数（越大越圆） */
    private static final int SEG = 48;

    /**
     * 舱盖板最少分成几块扇形（= 放射状加强肋的最少条数）。
     *
     * <p>★ 实际块数是<b>按半径算的</b>，不是固定 8 —— 见 {@link #segmentsFor(int)}。
     * 半径 32 的盘上如果还只有 8 块，每块就是 <b>45°</b> 的一个大披萨，
     * 那看起来不是"钢板拼接"而是"八块纯色色块"，就是玩家最初抱怨的那个观感。
     * 所以块数跟着周长长：`周长 / 每块目标宽度`。
     */
    private static final int MIN_SEGMENTS = 8;
    /** 每块舱盖板的目标环向宽度（格）。越小越密，细节越碎 */
    private static final double SEGMENT_WIDTH = 2.2D;
    /**
     * 块数上限。★ 这个数必须跟着 {@code MAX_RADIUS} 一起长，否则它就成了真正的瓶颈：
     * 半径 32 按宽度算需要约 91 块，如果上限还卡在 64，每块就变成 ~3.1 格宽
     * （正常是 ~1.1 格）—— 大半径下"钢板拼接"的细节会明显变粗，等于白放大。
     *
     * <p>128 块的顶点开销很小（每块就是个扇面 + 两条立面），
     * 而它保证了从半径 2 到 32 的<b>每一档</b>观感一致。
     */
    private static final int MAX_SEGMENTS = 128;

    /** 盘体厚度（格）。★ 这个数是"读起来像不像一块厚舱盖"的主开关，别往下调 */
    private static final double DISC_THICK = 0.50D;
    /** 盘顶面基准高度（略高于地面一格，关着时踩上去正好是地板） */
    private static final double TOP_Y = 0.52D;

    /** 每块舱盖板相对基准抬起的量 —— 抬顶面不抬底面，于是格间形成向下的凹槽 */
    private static final double PANEL_LIFT = 0.035D;
    /** 加强肋相对基准低下去的量（肋比板面略沉，读起来是"凹进去的骨架"） */
    private static final double RIB_SINK = 0.045D;

    /** 径向每隔几格重复一次贴图（= 一张 16×16 贴图覆盖几格） */
    private static final double TEX_SCALE = 1.5D;
    /** 一个方块格的高度在 UV 上占多少（贴图是 16×16，一格一个循环） */
    private static final double UV_PER_BLOCK = 1.0D;

    /** 外缘侧壁的厚度（径向） */
    private static final double RIM_W = 0.34D;
    /** 危险条在径向占的宽度 */
    private static final double HAZ_W = 0.22D;
    /** 最外沿盖边向内探出的宽度（把轮廓收干净） */
    private static final double LIP_W = 0.10D;
    /** 盖边比盘顶面高出多少 */
    private static final double LIP_RISE = 0.06D;

    /** 中心枢纽井（盘面中央的孔）的内半径 */
    private static final double WELL_R = 0.62D;
    /** 枢纽本体半径 */
    private static final double HUB_R = 0.54D;
    /** 枢纽从盘顶面再往上凸出多少 */
    private static final double HUB_H = 0.30D;

    // ------------------------------------------------------------------ 渲染

    @Override
    public void render(TileTartarus te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        // ★ 监控离屏渲染那一趟也会走"渲染方块实体"，挡掉避免画面里盘画两遍
        if (CameraFeedRenderer.isRenderingOffscreen()) {
            return;
        }
        if (destroyStage >= 0 || te == null || te.getWorld() == null || te.getPos() == null) {
            return;
        }
        int radius = te.getRadius();
        if (radius < 1) {
            return;
        }

        // 客户端自平滑插值，保证"缓慢开合"的动画连贯
        updateClientAnim(te, partialTicks);
        float amt = te.clientAnim;
        if (amt < 0.0F) {
            amt = 0.0F;
        } else if (amt > 1.0F) {
            amt = 1.0F;
        }
        float descent = amt * TileTartarus.DESCENT;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) {
            return;
        }
        BlockPos pos = te.getPos();
        // ★ 可视距离必须跟着半径长：半径 16 的舱盖直径 32 格，
        //   站在盘边上只能看到自己脚下那一小块，得退到几十格外才看得全。
        //   用固定的 96 会在"人已经退到看不全"的地方刚好把它剔掉，非常割裂。
        double viewRange = VIEW_RANGE + radius * 4.0D;
        if (mc.player.getDistanceSqToCenter(pos) > viewRange * viewRange) {
            return;
        }

        boolean built = te.isBuilt();
        double cx = 0.5D, cz = 0.5D;
        // 盘整体随开合下沉
        double yTop = TOP_Y - descent;
        // ★ 舱盖板块数按半径算（见 segmentsFor 的注释：固定 8 块在大半径上会变成大色块）
        int segments = segmentsFor(radius);
        // 环向分段数也要跟着长，否则 16 格的半径上圆看起来是几十边形
        int ringSeg = Math.max(SEG, Math.min(SEG * 3, segments * 2));

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);

        // ★ 贴图几何：必须绑方块图集（和 BlastDoorRenderer 同一套做法）
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);
        TextureAtlasSprite hatch = sprite("rsaudio:blocks/tartarus_hatch");
        TextureAtlasSprite underside = sprite("rsaudio:blocks/tartarus_underside");
        TextureAtlasSprite hazard = sprite("rsaudio:blocks/tartarus_hazard");
        TextureAtlasSprite rimTex = sprite("rsaudio:blocks/tartarus_rim");
        TextureAtlasSprite hubTop = sprite("rsaudio:blocks/tartarus_hub_top");
        TextureAtlasSprite hubSide = sprite("rsaudio:blocks/tartarus_hub_side");
        TextureAtlasSprite hubWell = sprite("rsaudio:blocks/tartarus_hub_well");

        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);

        double rOut = radius;
        double yBot = yTop - DISC_THICK;

        if (built) {
            // ---- ① 顶板：分 segments 块（块数按半径算），每块顶面单独抬起 → 格间凹槽
            for (int s = 0; s < segments; s++) {
                double a0 = s * (Math.PI * 2.0D) / segments;
                double a1 = (s + 1) * (Math.PI * 2.0D) / segments;
                double pTop = yTop + PANEL_LIFT;
                // 顶面：枢纽井外沿 → 危险条内沿
                sector(buf, hatch, cx, cz, WELL_R, rOut - RIM_W - HAZ_W, pTop,
                        a0, a1, false);
            }
            // ---- ② 底板：整片背板，<b>不抬起</b>，所以不分块、一次画完
            //        （分块是给顶面制造凹槽用的，底面没有这个需求；
            //          而且底面分块还会在背面露出格缝，从竖井里往上看是碎的）
            sector(buf, underside, cx, cz, WELL_R, rOut - RIM_W, yBot,
                    0.0D, Math.PI * 2.0D, true);

            // ---- ③ 放射状加强肋：夹在每两块舱盖板之间，比板面略沉
            double stepAngle = (Math.PI * 2.0D) / segments;
            double ribHalf = stepAngle * 0.055D;
            for (int s = 0; s < segments; s++) {
                double a = s * stepAngle;
                double yRib = yTop + PANEL_LIFT - RIB_SINK;
                double ri = WELL_R, ro = rOut - RIM_W - HAZ_W;
                // 肋的顶面（窄扇形）
                sector(buf, hatch, cx, cz, ri, ro, yRib, a - ribHalf, a + ribHalf, false);
                // 肋的两侧立面：从肋顶到板顶，形成那道凹槽的"墙"
                ribWall(buf, hatch, cx, cz, ri, ro, yRib, yTop + PANEL_LIFT, a - ribHalf, false);
                ribWall(buf, hatch, cx, cz, ri, ro, yRib, yTop + PANEL_LIFT, a + ribHalf, true);
            }

            // ---- ④ 枢纽井内壁：盘面中央那个孔，画成深色内圈（枢纽从这升出来）
            ringWall(buf, hubWell, cx, cz, WELL_R, yTop + PANEL_LIFT, yBot, ringSeg);

            // ---- ⑤ 外缘上的黄黑危险条（贴在侧壁上沿，略外凸一点避免 z 冲突）
            double hzOut = rOut - RIM_W + 0.004D;
            ringWall(buf, hazard, cx, cz, hzOut, yTop + PANEL_LIFT + 0.02D,
                    yTop + PANEL_LIFT - 0.26D, ringSeg);

            // ---- ⑥ 外缘侧壁：整圈最厚的那道边
            ringWall(buf, rimTex, cx, cz, rOut, yTop, yBot, ringSeg);

            // ---- ⑦ 最外沿盖边：从侧壁顶端向内探出的一圈薄檐，把轮廓收干净
            sector(buf, rimTex, cx, cz, rOut - RIM_W + LIP_W, rOut, yTop + LIP_RISE,
                    Math.PI * 2.0D, 0.0D, false);
            ringWall(buf, rimTex, cx, cz, rOut, yTop + LIP_RISE, yTop, ringSeg);
        }

        // ---- ⑧ 控制核（中心枢纽）：<b>无论建造与否都画</b>。
        //         没建造时它就是"可以右键这里"的提示；建造后它正好落在
        //         ④ 挖出的那个枢纽井里。几何完全一致，只画这一处、不要在两处各画一遍
        //         （重叠画两遍会让中心的贴图 z-fighting 闪烁）。
        double coreTop = yTop + PANEL_LIFT + HUB_H;
        ringWall(buf, hubSide, cx, cz, HUB_R, coreTop, yTop - 0.10D, ringSeg);
        sector(buf, hubTop, cx, cz, 0.0D, HUB_R, coreTop + 0.002D, 0.0D, Math.PI * 2.0D, false);

        tess.draw();

        // ★ 把状态还原回去：TESR 共用 GL 上下文，漏还原会让后面画的跟着变色/穿模
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    /** 客户端自插值：朝 wantOpen 平滑逼近，速度与服务端一致（SPEED * 20 每秒） */
    private static void updateClientAnim(TileTartarus te, float partial) {
        long now = System.currentTimeMillis();
        if (te.lastAnimTime == 0L) {
            te.lastAnimTime = now;
        }
        float dt = (now - te.lastAnimTime) / 1000.0F;
        te.lastAnimTime = now;
        if (dt > 0.1F) {
            dt = 0.1F;
        }
        float target = te.wantsOpen() ? 1.0F : 0.0F;
        float speed = TileTartarus.SPEED * 20.0F; // 每秒
        if (te.clientAnim < target) {
            te.clientAnim = Math.min(target, te.clientAnim + speed * dt);
        } else if (te.clientAnim > target) {
            te.clientAnim = Math.max(target, te.clientAnim - speed * dt);
        }
    }

    // ------------------------------------------------------------------ 几何

    /**
     * 一个环状<b>扇面</b>（可有内半径）：{@code innerR} 到 {@code outerR}，
     * 角度 {@code a0}→{@code a1}。{@code innerR <= 0} 时是实心扇形，中心收成一个点。
     *
     * <h3>UV 约定（★★ 顶面 / 底面方向相反，这里统一处理）</h3>
     * <ul>
     *   <li>{@code down=false}（面朝上）：环向 → {@code u}（按弧长，所以大半径处贴图不拉伸）、
     *       径向 → {@code v} 且随半径增大而<b>减小</b>。</li>
     *   <li>{@code down=true}（面朝下）：径向 → {@code v} 且随半径增大而<b>增大</b>
     *       （从下面仰视时坐标轴是反的）。</li>
     * </ul>
     * 两者都在 {@code / TEX_SCALE} 归一，于是每 {@code TEX_SCALE} 格贴图重复一次。
     * 环向的 {@code u} 用 {@code 弧长 / TEX_SCALE} —— 若直接用角度（0..2π）当 u，
     * 外圈会被拉成只剩一圈花纹，只有内圈是正常的，这是最常见的"环形 UV"错误。
     *
     * <p>★ {@code innerR} 与 {@code outerR} 不一定是"盘的内外沿"：画枢纽井内壁、
     * 盖边这些窄环时传的是那一小段的半径。{@code uBaseR} 取两者平均做环向基准，
     * 所以窄环上的贴图宽度也是对的。
     */
    private static void sector(BufferBuilder buf, TextureAtlasSprite sp,
                               double cx, double cz, double innerR, double outerR, double y,
                               double a0, double a1, boolean down) {
        if (outerR <= innerR) {
            return;
        }
        // 环向 UV 的基准半径取内外平均：内外圈各承担一半误差，
        // 不会出现"内圈正、外圈被拉"或反过来的情况
        double uBaseR = innerR <= 0.0D ? outerR * 0.5D : (innerR + outerR) * 0.5D;
        int steps = stepsFor(innerR, outerR, a0, a1);
        for (int i = 0; i < steps; i++) {
            double b0 = a0 + (a1 - a0) * i / steps;
            double b1 = a0 + (a1 - a0) * (i + 1) / steps;
            // 环向 u 按【弧长】推进：外周弧长更长 → u 走得更多 → 贴图不会被拉长
            double u0 = uAt(sp, uBaseR, b0);
            double u1 = uAt(sp, uBaseR, b1);
            double vIn = vAt(sp, innerR, down);
            double vOut = vAt(sp, outerR, down);
            double x0i = cx + Math.cos(b0) * innerR, z0i = cz + Math.sin(b0) * innerR;
            double x1i = cx + Math.cos(b1) * innerR, z1i = cz + Math.sin(b1) * innerR;
            double x0o = cx + Math.cos(b0) * outerR, z0o = cz + Math.sin(b0) * outerR;
            double x1o = cx + Math.cos(b1) * outerR, z1o = cz + Math.sin(b1) * outerR;
            if (innerR <= 0.0D) {
                // 实心扇形：中心收成一个点
                buf.pos(cx, y, cz).tex(u0, vIn).color(1F, 1F, 1F, 1F).endVertex();
                if (down) {
                    buf.pos(x0o, y, z0o).tex(u0, vOut).color(1F, 1F, 1F, 1F).endVertex();
                    buf.pos(x1o, y, z1o).tex(u1, vOut).color(1F, 1F, 1F, 1F).endVertex();
                } else {
                    buf.pos(x1o, y, z1o).tex(u1, vOut).color(1F, 1F, 1F, 1F).endVertex();
                    buf.pos(x0o, y, z0o).tex(u0, vOut).color(1F, 1F, 1F, 1F).endVertex();
                }
            } else if (down) {
                buf.pos(x0i, y, z0i).tex(u0, vIn).color(1F, 1F, 1F, 1F).endVertex();
                buf.pos(x0o, y, z0o).tex(u0, vOut).color(1F, 1F, 1F, 1F).endVertex();
                buf.pos(x1o, y, z1o).tex(u1, vOut).color(1F, 1F, 1F, 1F).endVertex();
                buf.pos(x1i, y, z1i).tex(u1, vIn).color(1F, 1F, 1F, 1F).endVertex();
            } else {
                buf.pos(x0i, y, z0i).tex(u0, vIn).color(1F, 1F, 1F, 1F).endVertex();
                buf.pos(x1i, y, z1i).tex(u1, vIn).color(1F, 1F, 1F, 1F).endVertex();
                buf.pos(x1o, y, z1o).tex(u1, vOut).color(1F, 1F, 1F, 1F).endVertex();
                buf.pos(x0o, y, z0o).tex(u0, vOut).color(1F, 1F, 1F, 1F).endVertex();
            }
        }
    }

    /**
     * 环向 u：半径 {@code r} 处、角度 {@code angle} 对应的贴图 u。
     *
     * <p>★ 用<b>弧长</b>（{@code r * angle}）而不是角度本身：若直接用 0..2π 当 u，
     * 整圈只占一张贴图的一个周期，外圈的分格线会被拉成"两三根粗道子"，
     * 而内圈却密密麻麻 —— 这是环形 UV 最常见的错误。
     */
    private static double uAt(TextureAtlasSprite sp, double r, double angle) {
        double span = sp.getMaxU() - sp.getMinU();
        double phase = (r * angle / TEX_SCALE) % 1.0D;
        if (phase < 0.0D) {
            phase += 1.0D;
        }
        return sp.getMinU() + phase * span;
    }

    /**
     * 径向 v：半径 {@code r} 对应的贴图 v，每 {@code TEX_SCALE} 格一个周期。
     *
     * <p>★ 顶面与底面方向<b>相反</b>：朝上的面随半径增大 v 减小，朝下的面随半径增大
     * v 增大（从下往上看坐标系是翻的）。写反的症状是"盘上的花纹南北镜像"——
     * 因为这张图基本对称，肉眼很难发现，所以这里必须严格按朝向分支。
     */
    private static double vAt(TextureAtlasSprite sp, double r, boolean down) {
        double span = sp.getMaxV() - sp.getMinV();
        double phase = (r / TEX_SCALE) % 1.0D;
        if (phase < 0.0D) {
            phase += 1.0D;
        }
        return down ? sp.getMinV() + phase * span
                    : sp.getMaxV() - phase * span;
    }

    /** 按弧长决定细分段数，保证大半径处也不会有肉眼可见的多边形棱角 */
    private static int stepsFor(double innerR, double outerR, double a0, double a1) {
        // 用内外平均半径估弧长：只用外半径会让窄环被过度细分，只用内半径则外圈不够
        double refR = innerR <= 0.0D ? outerR * 0.5D : (innerR + outerR) * 0.5D;
        double arc = Math.abs(a1 - a0) * Math.max(refR, 0.5D);
        int steps = (int) Math.ceil(arc / 0.20D);
        return Math.max(1, Math.min(SEG * 4, steps));
    }

    /**
     * 舱盖板分成几块 —— <b>按半径算</b>，让每一块的环向宽度大致恒定为
     * {@link #SEGMENT_WIDTH} 格。
     *
     * <h3>★ 为什么不能固定 8 块</h3>
     * 分段的意义是"看起来是一块一块钢板拼的"。固定 8 块时：
     * <ul>
     *   <li>半径 2：每块弧长 ~1.6 格 → 太密，看着像切碎的饼；</li>
     *   <li>半径 16：每块弧长 <b>~12.6 格</b> → 太稀，变成八块大色块，
     *       完全失去"拼接钢板"的观感。</li>
     * </ul>
     * 所以按 {@code 2πr / SEGMENT_WIDTH} 算，再用 {@link #MIN_SEGMENTS} /
     * {@link #MAX_SEGMENTS} 夹住两头。半径 16 时约 45 块。
     *
     * <h3>★ 任何"每块"的几何都必须用它算，不能沿用常量 8</h3>
     * 下面顶面/底面/加强肋三个循环都走这个函数；写死 8 会让肋和板错位
     * （肋画在旧角度上、板已经按新块数铺开 → 肋跑到板中间去）。
     */
    private static int segmentsFor(int radius) {
        int n = (int) Math.round(2.0D * Math.PI * Math.max(radius, 1)
                / SEGMENT_WIDTH);
        if (n < MIN_SEGMENTS) {
            n = MIN_SEGMENTS;
        }
        if (n > MAX_SEGMENTS) {
            n = MAX_SEGMENTS;
        }
        return n;
    }
    /**
     * 扇形的一条<b>径向立面</b>（加强肋的侧面）：从 {@code yLow} 到 {@code yHigh}，
     * 沿半径 {@code ri}→{@code ro} 的一条竖直窄面。
     *
     * <p>{@code negSide=true} 表示这条立面朝向角度的<b>减小</b>方向（法线在 -角度侧），
     * 用来决定缠绕顺序；画错方向会因为 {@code disableCull} 而看不出问题，
     * 但一旦哪天开了背面剔除就会缺面 —— 所以这里严格写对。
     */
    private static void ribWall(BufferBuilder buf, TextureAtlasSprite sp,
                                double cx, double cz, double ri, double ro,
                                double yLow, double yHigh, double a, boolean negSide) {
        double ua = Math.cos(a), va = Math.sin(a);
        double x0 = cx + ua * ri, z0 = cz + va * ri;
        double x1 = cx + ua * ro, z1 = cz + va * ro;
        double u0 = sp.getMinU() + ((ri / TEX_SCALE) % 1.0D) * (sp.getMaxU() - sp.getMinU());
        double u1 = sp.getMinU() + ((ro / TEX_SCALE) % 1.0D) * (sp.getMaxU() - sp.getMinU());
        double v0 = sp.getMinV();
        double v1 = sp.getMinV() + Math.min(1.0D, (yHigh - yLow) * UV_PER_BLOCK)
                * (sp.getMaxV() - sp.getMinV());
        if (negSide) {
            buf.pos(x0, yLow, z0).tex(u0, v0).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x1, yLow, z1).tex(u1, v0).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x1, yHigh, z1).tex(u1, v1).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x0, yHigh, z0).tex(u0, v1).color(1F, 1F, 1F, 1F).endVertex();
        } else {
            buf.pos(x0, yLow, z0).tex(u0, v0).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x0, yHigh, z0).tex(u0, v1).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x1, yHigh, z1).tex(u1, v1).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x1, yLow, z1).tex(u1, v0).color(1F, 1F, 1F, 1F).endVertex();
        }
    }

    /**
     * 一个圆柱<b>侧壁</b>（整圈）：半径 {@code r}，从 {@code yTop} 到 {@code yBot}。
     * 用于枢纽侧壁、危险条、外缘侧壁、井内壁。
     *
     * <p>环向 UV 按弧长推进（原因见 {@link #sector}）；竖直方向每格一遍贴图。
     */
    private static void ringWall(BufferBuilder buf, TextureAtlasSprite sp,
                                 double cx, double cz, double r,
                                 double yTop, double yBot, int seg) {
        if (r <= 0.0D || yTop <= yBot) {
            return;
        }
        double spanU = sp.getMaxU() - sp.getMinU();
        double v0 = sp.getMinV() + 0.0D * (sp.getMaxV() - sp.getMinV());
        double v1 = sp.getMinV() + Math.min(1.0D, (yTop - yBot) * UV_PER_BLOCK)
                * (sp.getMaxV() - sp.getMinV());
        for (int i = 0; i < seg; i++) {
            double a0 = Math.PI * 2.0D * i / seg;
            double a1 = Math.PI * 2.0D * (i + 1) / seg;
            double x0 = cx + Math.cos(a0) * r, z0 = cz + Math.sin(a0) * r;
            double x1 = cx + Math.cos(a1) * r, z1 = cz + Math.sin(a1) * r;
            double u0 = sp.getMinU() + ((a0 / (Math.PI * 2.0D)) * (Math.PI * 2.0D * r / TEX_SCALE)
                    % 1.0D) * spanU;
            double u1 = sp.getMinU() + ((a1 / (Math.PI * 2.0D)) * (Math.PI * 2.0D * r / TEX_SCALE)
                    % 1.0D) * spanU;
            buf.pos(x0, yTop, z0).tex(u0, v0).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x1, yTop, z1).tex(u1, v0).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x1, yBot, z1).tex(u1, v1).color(1F, 1F, 1F, 1F).endVertex();
            buf.pos(x0, yBot, z0).tex(u0, v1).color(1F, 1F, 1F, 1F).endVertex();
        }
    }

    /** 取方块图集里的贴图。★ 名字必须带 {@code blocks/}，否则拿到 missingno（紫黑）。 */
    private static TextureAtlasSprite sprite(String name) {
        return Minecraft.getMinecraft().getTextureMapBlocks().getAtlasSprite(name);
    }
}

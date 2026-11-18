package com.rsaudio.client;

import com.rsaudio.entity.EntityTrain;
import com.rsaudio.train.TrainSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * 列车实体的渲染器：<b>车头 + 后面拖着的一串车厢</b>。
 *
 * <h3>★ 它只画"内核说存在的东西"</h3>
 * 车厢节数不是这里决定的，是读 {@link ClientTrainState#cars(int[])}（内核存档的槽 12）。
 * 玩家在游戏里摘一节车厢，内核的编组数减一，下一次 {@code PacketTrainSync} 到了之后
 * 这里自然就少画一节 —— <b>渲染层不持有任何"列车有多长"的独立状态</b>。
 * 两边各存一份的话，摘车厢之后会出现"车厢还在画、但已经不能坐了"的鬼影。
 *
 * <h3>★ 位置从哪来</h3>
 * <b>只有车头有实体。</b>车厢不是实体，是渲染器按车头的位置、朝向和
 * {@link TrainSpec#CELLS_PER_CAR} 往后推出来的。<b>这条设计是刻意的</b>：
 * <ul>
 *   <li>车厢要是实体，每一节都要自己的同步包、自己的 tick、自己的存档；
 *       6 节车厢的列车 = 7 个实体在跑物理，而物理其实只有车头一处；</li>
 *   <li>更糟的是"车厢是实体"会带来一堆能出 bug 的交互：玩家右键车厢算不算上车、
 *       车厢会不会被推走、车厢和车头不同步时会不会撕成两截。
 *       全部规避 —— 车厢是纯视觉的。</li>
 * </ul>
 * 代价是车厢"穿不过拐角"（它们沿着车头的朝向直线排开）。对园区内一段一段的
 * 通勤线路来说这个近似完全够用，而且车厢的朝向本来就跟着车头转。
 *
 * <h3>★ 插值用 partialTicks，不用内核的 progress</h3>
 * 车头的 {@code posX/Y/Z} 是服务端每 {@code updateFrequency} 刻推一次的离散快照，
 * 两次之间必须靠 {@code partialTicks} 插值，否则车是"一格一格跳"的。
 * 这里对<b>位置</b>做插值（{@code prevPos ↔ pos}），对<b>朝向</b>同样做
 * （{@code prevRotationYaw ↔ rotationYaw}）。
 * <p>
 * 车厢的偏移则用<b>插值后</b>的位置加<b>插值后</b>的 yaw 去算 ——
 * 用未插值的 yaw 算车厢偏移，会让整列车在拐弯时"拧麻花"。
 *
 * <h3>★ 几何一律用"局部坐标 + 一次 translate/rotate"</h3>
 * 不要在每个顶点上自己算世界坐标。这里先把 GL 平移到车头，再绕 Y 转 yaw，
 * 之后所有几何都按"车头朝 +Z、车宽沿 X、车高沿 Y"来写 ——
 * 换朝向不用改任何顶点。这也是 TESR 那边一贯的路子。
 *
 * <h3>★★ 绕 Y 的那个角必须是 {@code -yaw}，不是 {@code yaw}</h3>
 * {@code GlStateManager.rotate(θ, 0,1,0)} 把局部 {@code +Z} 转到 {@code (sinθ, 0, cosθ)}，
 * 而 MC 的 yaw 约定是"朝向 = {@code (-sin yaw, 0, cos yaw)}"（0=+Z 南、90=-X 西）。
 * 两者对不上符号 —— 要局部 +Z 指向 yaw 方向，θ 必须取 {@code -yaw}。
 * 同一件事在 {@code MonitorConsoleRenderer.textAngle} 里也写过一遍
 * （那里是"原版告示牌用的 {@code -getHorizontalAngle()}"）。
 * <p>
 * <b>漏这个负号的症状很有欺骗性</b>：yaw=0（沿 +Z）和 yaw=180（沿 -Z）时
 * 结果碰巧一样，所以<b>南北向的线看起来完全正常</b>；只有沿 X 的线会把整列车
 * 转 180° —— 车头朝后、车厢跑到行进方向的前面。玩家第一反应是"车厢装反了"。
 *
 * <h3>★★ 画之前必须关掉背面剔除</h3>
 * 世界渲染的其余部分（地形、实体）是在 {@code GL_CULL_FACE} <b>开着</b>的状态下画的
 * （{@code CameraFeedRenderer} 离屏渲染结束后专门 {@code enableCull()} 恢复的正是它）。
 * 本渲染器的四边形是手写顶点序的，绕序只要写反一面，那一面就<b>整个消失</b>：
 * 车厢左右两块侧板一旦被剔除，玩家看到的是"一只透空的壳"，
 * 第一反应就是"列车没有贴图"（2026-09-21 实测）。
 * <p>
 * 本项目的其它手写几何渲染器（塔塔鲁斯 / DMR 堆芯 / 防爆门 / 监控屏幕）
 * 无一例外都是先 {@code disableCull()} 再画、画完 {@code enableCull()} ——
 * {@code MonitorConsoleRenderer} 的注释把理由写得很直白：
 * "这样三角形的绕序也不重要了"。本类以前是<b>唯一</b>漏了这一步的。
 */
public class TrainRenderer extends Render<EntityTrain> {

    /** 超出这个距离不画（车厢要画好几节，没必要算太远） */
    private static final double VIEW_RANGE = 128.0D;

    // ---------------------------------------------------------------- 车体尺寸
    // ★ 尺寸全部读 TrainSpec —— 座位偏移（EntityTrain.updatePassenger）用的是同一组数。
    //   这里再写一份数字的话，改一处就会出现"乘客飘在车厢外面"。

    /** 车顶的弧面：顶面比侧面顶边再高出来的量 */
    private static final double ROOF_RISE = TrainSpec.ROOF_RISE;

    /** 转向架：一节车厢两端各一个 */
    private static final double BOGIE_LEN = 1.10D;
    private static final double BOGIE_W = 1.62D;
    /** 转向架底（= 轮子贴着轨面的高度）。轨面在原点上方 1/16，再留 1/128 的空隙 */
    private static final double BOGIE_BOTTOM = 0.070D;
    /** 转向架顶 = 车底下的那条下裙收边 */
    private static final double BOGIE_TOP = 0.46D;
    /** 下裙（车底到转向架顶之间的机器层），比车厢略窄 */
    private static final double SKIRT_INSET = 0.16D;

    /** 车窗带：相对车底的高度 */
    private static final double WIN_BOT = 1.15D;
    private static final double WIN_TOP = 1.90D;

    /** 车门在车体侧面上占的纵向宽度（视觉上门的开合靠这片的显隐） */
    private static final double DOOR_LEN = 1.30D;

    // ---------------------------------------------------------------- 亮度
    /**
     * 各面的亮度档位（顶点色 × 贴图采样）。
     * <p>
     * ★★ <b>一个都不能超过 1.0。</b>
     * {@code BufferBuilder.color(float...)} 是 {@code (int)(v * 255)} 之后
     * <b>截断成 byte</b>（实测字节码：{@code f2i} → {@code i2b}），所以
     * 1.06 会变成 {@code (byte)270 = 14}（= 0.055），1.18 会变成 44（= 0.17）——
     * 想画"更亮"的面，实际画出来是<b>几乎全黑</b>。
     * 车头正面当初写的就是 1.06，于是整个风挡是黑的；窗带写的 1.18，于是车窗是黑的。
     * 要"更亮"只能把别的面压暗（下面的 LIT_SIDE / LIT_BOTTOM 就是干这个的）。
     */
    private static final float LIT_TOP = 1.00F;
    private static final float LIT_SIDE = 0.86F;
    private static final float LIT_FRONT = 1.00F;
    /** 车底压暗 */
    private static final float LIT_BOTTOM = 0.62F;
    /** 窗带：比侧板亮一档，让窗"浮"出来 */
    private static final float LIT_WINDOW = 1.00F;
    /** 下裙 / 转向架（车底的机器件，压得最暗） */
    private static final float LIT_GEAR = 0.66F;

    /** 前照灯 / 尾灯的颜色 */
    private static final float[] LAMP_HEAD = {1.00F, 0.97F, 0.78F};
    private static final float[] LAMP_TAIL = {0.92F, 0.18F, 0.14F};
    /** 车厢内灯（门开着时透出来） */
    private static final float[] LAMP_CABIN = {0.94F, 0.90F, 0.74F};

    public TrainRenderer(RenderManager renderManager) {
        super(renderManager);
        // 影子大小跟着实体包围盒走；这里设一个差不多匹配车头的值
        this.shadowSize = 1.4F;
    }

    /**
     * ★ 返回 null 表示"不绑定贴图"。
     * <p>
     * 这个渲染器<b>全程不绑纹理图集</b>吗？不是 —— 车体确实走
     * {@code POSITION_TEX_COLOR} 并且绑了 {@code LOCATION_BLOCKS_TEXTURE}
     * （因为贴图就是方块图集里的那几张）。但那是<b>手里自己绑</b>的，
     * 不是 {@code Render} 框架事先绑好的，所以这里返回 null 是正确的。
     * <p>
     * 如果这里返回一张真实路径，框架会在 {@code doRender} 之前多绑一次、
     * 而我们进去第一件事又是绑图集 —— 多一次无用切换，且容易看错"到底哪张生效"。
     */
    @Override
    protected ResourceLocation getEntityTexture(EntityTrain entity) {
        return null;
    }

    // ================================================================ 主入口

    @Override
    public void doRender(EntityTrain entity, double x, double y, double z,
                         float entityYaw, float partialTicks) {
        if (entity == null || entity.isDead) {
            return;
        }
        // ★ 离屏（监控摄像机的画面是"把世界重画一遍"）那一趟要挡掉，
        //   否则列车会在监控画面里画第二次，出现重影/闪烁。
        //   这个坑 DMR 堆芯和塔塔鲁斯都踩过，是新渲染器的标配第一行。
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
        // 距离剔除：车厢要画好几节，太远就别算了
        if (this.renderManager.getDistanceToCamera(entity.posX, entity.posY, entity.posZ)
                > VIEW_RANGE) {
            return;
        }

        // ---- 插值出"这一刻"的车头位置与朝向
        // ★ 刚生成的那一帧 prevPosX/Y/Z 可能还是 0（setPosition 只写 pos*，
        //   不写 prevPos*），此时插值会把车从世界原点"飞"过来。
        //   判据：插值结果离实体当前位置超过一整格，就说明 prev 是脏的，直接用 pos。
        //   用"整格"当阈值而不是"很小一个数"：正常移动每 tick 不到 1 格
        //   （SPEED_MAX = 0.72），所以超过 1 格必然是刚生成/刚传送。
        double dxp = entity.posX - entity.prevPosX;
        double dyp = entity.posY - entity.prevPosY;
        double dzp = entity.posZ - entity.prevPosZ;
        boolean prevSane = (dxp * dxp + dyp * dyp + dzp * dzp) <= 1.0D;
        double ix = prevSane ? entity.prevPosX + dxp * partialTicks : entity.posX;
        double iy = prevSane ? entity.prevPosY + dyp * partialTicks : entity.posY;
        double iz = prevSane ? entity.prevPosZ + dzp * partialTicks : entity.posZ;
        float dyaw = normalizeDeg(entity.rotationYaw - entity.prevRotationYaw);
        float iyaw = prevSane ? entity.prevRotationYaw + dyaw * partialTicks : entity.rotationYaw;

        // ---- 读数（客户端可能还没收到包，全部有兜底）
        int[] st = ClientTrainState.get(entity.getEntityId());
        int cars = ClientTrainState.cars(st);
        int phase = ClientTrainState.phase(st);
        // ★★ 读数包还没到的那一小段（服务端最多 20 刻才推一次）：退回用<b>生成数据</b>里的编组数。
        //   实体自带 {@code IEntityAdditionalSpawnData}，服务端在实体出现在客户端之前就把
        //   整份内核存档塞进去了（EntityTrain.writeSpawnData/readSpawnData）——
        //   和读数包是<b>同一个真值来源</b>，所以这不是"猜一个数"，只是换了条已经到货的通道。
        //   ★ 不做这个兜底的话：新放的 / 刚进视距的车，头 1 秒是"只有车头没有车厢"，
        //     而少于 60 刻又够不着 diagnose() 的超时判据 —— 又是一次"静默地画错"。
        //   两条通道都指望不上时才是真的按 0 节画，那种情况 diagnose() ② 会出声。
        if (st == null) {
            cars = entity.getCore().cars();
            phase = entity.getCore().getPhase();
        }
        // 门正在开/关的过程中，给一个 0..1 的开度，让门扇连续滑动而不是突然出现
        float doorAjar = doorAjar(phase);

        // ---- 画
        GlStateManager.pushMatrix();
        // ★ 位置用**插值后**的 ix/iy/iz，不是渲染器拿到的 x/y/z。
        //   实体坐标是服务端每 3 刻推一次的离散快照，直接用 x/y/z 的话车是
        //   "一格一格跳"的；iy 尤其重要 —— 漏掉它车会在坡道上上下抽动。
        GlStateManager.translate(ix, iy, iz);
        // 车体几何按"车头朝 +Z"写，而 MC 的朝向是 (-sin yaw, cos yaw)，
        // 所以这里必须是 -iyaw（详见类注释里那一段）。
        GlStateManager.rotate(-iyaw, 0.0F, 1.0F, 0.0F);

        GlStateManager.enableTexture2D();
        GlStateManager.enableRescaleNormal();
        GlStateManager.disableBlend();
        // ★★ 关掉背面剔除：手写顶点的绕序写反了那一面就会整块消失。
        //   画完在下面成对恢复（世界渲染的其余部分都指望它是开着的）。
        GlStateManager.disableCull();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        mc.getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        TextureAtlasSprite side = sprite(mc, TrainTextures.BODY_SIDE);
        TextureAtlasSprite roof = sprite(mc, TrainTextures.BODY_ROOF);
        TextureAtlasSprite nose = sprite(mc, TrainTextures.BODY_NOSE);
        TextureAtlasSprite end = sprite(mc, TrainTextures.BODY_END);
        TextureAtlasSprite bogie = sprite(mc, TrainTextures.BOGIE);
        TextureAtlasSprite door = sprite(mc, TrainTextures.DOOR);
        // ★ 一次性自检：静默渲染故障（没异常、日志干净、玩家只看到"全白/车厢是空气"）
        //   只能靠把关键读数打出来取证。见 diagnose() 的注释。
        diagnose(entity, st, cars, side, roof, nose, end, bogie, door);

        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();

        // ---- ① 车头：以原点为中心（原点 = 车头所在的那一格轨道的中心）
        //   ★ 早先车头是画在 [0, CAR_LEN]（整体偏在原点前方），于是车头与第一节
        //     车厢之间空出一格多的缝，看起来像"两截不相连的车厢"。
        //     以原点为中心之后，相邻两节的间隙就只剩 CELLS_PER_CAR - CAR_LEN。
        double halfHead = TrainSpec.CAR_LEN * 0.5D;
        drawCar(buf, side, roof, end, nose, door, bogie,
                -halfHead, halfHead, true, doorAjar, phase);

        // ---- ② 车厢：一节一节往后排
        //   ★ 偏移量来自 TrainSpec.CELLS_PER_CAR —— 和乘客座位、车厢几何同一把尺子。
        //     两边各写一份数字的话，乘客会飘在车厢外面。
        for (int i = 1; i <= cars; i++) {
            double center = -i * TrainSpec.CELLS_PER_CAR;
            drawCar(buf, side, roof, end, end, door, bogie,
                    center - halfHead, center + halfHead, false, doorAjar, phase);
        }

        // ★ 状态复原：进来时关掉的剔除与开的 RescaleNormal 都必须成对还原。
        //   不要在这里"顺手 enableBlend" —— 那是下一个渲染器的默认之一，
        //   替别人改状态会让后续的绘制莫名变半透明（本项目踩过一次类似的状态泄漏）。
        GlStateManager.enableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
    }

    // ================================================================ 一节车

    /**
     * 画一节车（从车头到车厢都是这一个函数，靠 {@code isHead} 切换首尾贴图）。
     *
     * @param z0       车体在局部坐标里的起始 z（车尾端）
     * @param z1       车体结束 z（车头端）
     * @param isHead   是不是车头（车头朝前那一面用"风挡"贴图、并且有灯）
     * @param ajar     车门开度 0..1
     * @param phase    相位码（门灯颜色要用）
     */
    private void drawCar(BufferBuilder buf, TextureAtlasSprite side, TextureAtlasSprite roof,
                         TextureAtlasSprite back, TextureAtlasSprite front, TextureAtlasSprite door,
                         TextureAtlasSprite bogie, double z0, double z1,
                         boolean isHead, float ajar, int phase) {
        double hw = TrainSpec.CAR_W * 0.5D;
        double y0 = TrainSpec.CAR_Y0;
        double y1 = TrainSpec.CAR_Y0 + TrainSpec.CAR_H;

        // ---- ① 车身：左右两侧 + 车顶 + 车底 + 前后端面
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        // 右侧（+X）
        faceQuad(buf, side, hw, y0, z1, hw, y1, z1, hw, y1, z0, hw, y0, z0, LIT_SIDE);
        // 左侧（-X）
        faceQuad(buf, side, -hw, y0, z0, -hw, y1, z0, -hw, y1, z1, -hw, y0, z1, LIT_SIDE);
        // 车顶（+Y）
        faceQuad(buf, roof, -hw, y1, z1, hw, y1, z1, hw, y1, z0, -hw, y1, z0, LIT_TOP);
        // 车底（-Y）
        faceQuad(buf, bogie, -hw, y0, z0, hw, y0, z0, hw, y0, z1, -hw, y0, z1, LIT_GEAR);
        // 前端面（+Z）—— 车头是风挡，车厢是端面
        faceQuad(buf, front, -hw, y0, z1, hw, y0, z1, hw, y1, z1, -hw, y1, z1, LIT_FRONT);
        // 后端面（-Z）
        //   ★ 车尾（编组里最后一节）用同一张贴图。曾想给车尾单独画一张"带尾灯"的端面，
        //     但那需要第二张贴图 + 一个"我是不是最后一节"的额外参数传递；
        //     而尾灯本来就在车头正面那一套里画了（停车时转成红色）。
        //     少一张贴图、少一个参数，观感没有实质损失。
        faceQuad(buf, back, -hw, y0, z0, -hw, y1, z0, hw, y1, z0, hw, y0, z0, LIT_SIDE);
        // ---- 车顶弧面：车顶两侧各一块斜板，让顶不是一块平砖
        roofShoulder(buf, roof, hw, y1, z0, z1, +1);
        roofShoulder(buf, roof, hw, y1, z0, z1, -1);
        Tessellator.getInstance().draw();

        // ---- ② 车窗带：两侧各一条提亮的横带（画在侧板之上一点点，制造凹凸）
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        double wy0 = y0 + WIN_BOT;
        double wy1 = y0 + WIN_TOP;
        double inset = 0.22D;          // 窗带两端留出的车头/车尾余量
        double eps = 0.006D;           // 浮出侧板一点点，避免 z-fighting
        // 右窗
        faceQuad(buf, side, hw + eps, wy0, z0 + inset, hw + eps, wy1, z0 + inset,
                hw + eps, wy1, z1 - inset, hw + eps, wy0, z1 - inset, LIT_WINDOW);
        // 左窗
        faceQuad(buf, side, -hw - eps, wy0, z1 - inset, -hw - eps, wy1, z1 - inset,
                -hw - eps, wy1, z0 + inset, -hw - eps, wy0, z0 + inset, LIT_WINDOW);
        Tessellator.getInstance().draw();

        // ---- ③ 车门：两片，按开度沿着纵向滑开（开度 1 = 完全缩进车体看不见）
        if (ajar < 0.999F && !isHead) {
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            drawDoorLeaf(buf, door, hw, y0, z0, z1, ajar, +1);
            drawDoorLeaf(buf, door, -hw, y0, z0, z1, ajar, -1);
            Tessellator.getInstance().draw();
        }

        // ---- ④ 门开时从门洞里透出的车内灯光
        if (ajar > 0.05F && !isHead) {
            drawCabinGlow(buf, door, hw, y0, y1, z0, z1, ajar);
        }

        // ---- ⑤ 走行部：下裙 + 两端转向架（画在最后，免得被车身的面压住）
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        drawRunningGear(buf, bogie, hw, z0, z1);
        Tessellator.getInstance().draw();

        // ---- ⑥ 前照灯 / 尾灯（只有车头有）
        if (isHead) {
            drawLamps(buf, front, hw, y0, z1, phase);
        }
    }

    // ================================================================ 零件

    /**
     * 车顶肩：车顶两侧各一块向内倾斜的窄板，把"平顶砖"变成"弧顶车厢"。
     * <p>
     * 这是性价比很高的一招 —— 只需要两块四边形，车厢的截面立刻从
     * 方形变成梯形，远看就是"火车"而不是"集装箱"。
     */
    private void roofShoulder(BufferBuilder buf, TextureAtlasSprite tex,
                              double hw, double y1, double z0, double z1, int sign) {
        double x0 = sign * hw;
        double x1 = sign * (hw - 0.34D);
        double yTop = y1 + ROOF_RISE;
        // 外侧下沿 → 内侧上沿，形成一个斜切面
        faceQuad(buf, tex, x0, y1, z1, x0, y1, z0, x1, yTop, z0, x1, yTop, z1, 0.94F);
    }

    /**
     * 走行部：车底下面的机器层（下裙）+ 一节车厢两端的转向架。
     * <p>
     * ★ 车底离地 0.875 格（= 和石板月台面齐平），这一段空档必须有东西填上，
     * 否则车厢看起来是<b>浮在轨道上方</b>的。所以这里画两层：
     * 一层是几乎和车一样宽的下裙（把车底和转向架之间的空隙填满），
     * 一层是两端更窄更低的转向架（读起来是"轮子在下面"）。
     */
    private void drawRunningGear(BufferBuilder buf, TextureAtlasSprite tex,
                                 double hw, double z0, double z1) {
        // ---- 下裙：从车底一直垂到转向架顶
        double sw = hw - SKIRT_INSET;
        box(buf, tex, -sw, sw, BOGIE_TOP, TrainSpec.CAR_Y0, z0 + 0.05D, z1 - 0.05D, LIT_GEAR);
        // ---- 两端转向架：更窄、更短、垂到轮面
        double bw = BOGIE_W * 0.5D;
        double half = BOGIE_LEN * 0.5D;
        for (int end = 0; end < 2; end++) {
            double center = (end == 0 ? z0 : z1) + (end == 0 ? half + 0.10D : -half - 0.10D);
            box(buf, tex, -bw, bw, BOGIE_BOTTOM, BOGIE_TOP,
                    center - half, center + half, LIT_GEAR);
        }
    }

    /**
     * 一个轴对齐的方盒（六个面）。
     * <p>
     * ★ 只画四个侧面 + 底面 + 顶面，和 {@code faceQuad} 一样不区分内外 ——
     * 因为本渲染器开着 {@code disableCull}，绕序不影响可见性，
     * 而方盒是封闭的，深度测试会处理遮挡。
     */
    private void box(BufferBuilder buf, TextureAtlasSprite tex,
                     double x0, double x1, double y0, double y1, double z0, double z1, float lit) {
        // 前后（±Z）
        faceQuad(buf, tex, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, lit);
        faceQuad(buf, tex, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, lit);
        // 左右（±X）
        faceQuad(buf, tex, x1, y0, z1, x1, y1, z1, x1, y1, z0, x1, y0, z0, lit);
        faceQuad(buf, tex, x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, lit);
        // 上 / 下
        faceQuad(buf, tex, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, lit);
        faceQuad(buf, tex, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, lit);
    }

    /**
     * 一片车门。关门时两片在中间对拢（{@code ajar=0}），开门时各自向两端滑开。
     * <p>
     * ★ 用"滑动"而不是"透明度渐变"：列车门是塞拉门/滑门，滑动的观感才对；
     * 而且这里也不开混合，少一次状态切换。
     */
    private void drawDoorLeaf(BufferBuilder buf, TextureAtlasSprite tex, double hw,
                              double y0, double z0, double z1, float ajar, int sign) {
        double mid = (z0 + z1) * 0.5D;
        double half = DOOR_LEN * 0.5D;
        // 关：leaf 覆盖 [mid-sign*half*? ...]；开：整体向两端平移 DOOR_LEN
        double shift = ajar * DOOR_LEN;
        double a = mid + sign * shift;
        double b = a + sign * half;
        double lo = Math.min(a, b);
        double hi = Math.max(a, b);
        // 滑出车体范围就不再画（等价于"门缩进了车壁里"）
        if (hi < z0 || lo > z1) {
            return;
        }
        double eps = 0.012D;
        double yb = y0 + 0.05D;
        double yt = y0 + TrainSpec.CAR_H - 0.15D;
        faceQuad(buf, tex, sign * (hw + eps), yb, hi, sign * (hw + eps), yt, hi,
                sign * (hw + eps), yt, lo, sign * (hw + eps), yb, lo, 0.95F);
    }

    /**
     * 门洞里透出的车内暖光（一个贴在侧面的、略内陷的亮面）。
     * <p>
     * ★ 这一批要<b>开混合</b>：它是叠加在侧板上的半透明光，不是实体面。
     * 关着混合画的话它会把后面的车体色"顶掉"，看起来是一块贴上去的黄斑。
     * 画完立刻把混合关回去 —— 后面还有不透明的零件要画。
     * <p>
     * ★★ UV 必须取<b>一个确定的亮处</b>，不能随手写 {@code tex(0.5, 0.5)}：
     * {@code POSITION_TEX_COLOR} 的最终颜色是 {@code 顶点色 × 贴图采样值}，
     * 而 (0.5,0.5) 落在<b>图集里哪张图集块、那块是什么颜色</b>是不确定的
     * （取决于本版都注册了哪些贴图）。踩中一块暗贴图时"车内灯光"会变成一团黑 ——
     * 一个只在特定贴图集合下才出现的诡异现象。
     * 这里显式取门贴图的中心（门是中绿涂装，乘上去偏亮、不会把亮度吃掉）。
     */
    private void drawCabinGlow(BufferBuilder buf, TextureAtlasSprite tex,
                               double hw, double y0, double y1,
                               double z0, double z1, float ajar) {
        double mid = (z0 + z1) * 0.5D;
        double half = DOOR_LEN * 0.5D * Math.max(0.05F, ajar);
        double eps = 0.010D;
        float a = 0.55F * ajar;
        double u = (tex.getMinU() + tex.getMaxU()) * 0.5D;
        double v = (tex.getMinV() + tex.getMaxV()) * 0.5D;
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.depthMask(false);
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int sign = -1; sign <= 1; sign += 2) {
            double x = sign * (hw - eps);
            double lo = mid - half;
            double hi = mid + half;
            // 手动上色（这一段要跟周围环境有明显亮度差，才读得出"里面亮着灯"）
            buf.pos(x, y0 + 0.05D, hi).tex(u, v)
                    .color(LAMP_CABIN[0], LAMP_CABIN[1], LAMP_CABIN[2], a).endVertex();
            buf.pos(x, y1 - 0.15D, hi).tex(u, v)
                    .color(LAMP_CABIN[0], LAMP_CABIN[1], LAMP_CABIN[2], a).endVertex();
            buf.pos(x, y1 - 0.15D, lo).tex(u, v)
                    .color(LAMP_CABIN[0], LAMP_CABIN[1], LAMP_CABIN[2], a).endVertex();
            buf.pos(x, y0 + 0.05D, lo).tex(u, v)
                    .color(LAMP_CABIN[0], LAMP_CABIN[1], LAMP_CABIN[2], a).endVertex();
        }
        Tessellator.getInstance().draw();
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
    }

    /**
     * 车头的前照灯和尾灯。
     * <p>
     * ★ 用相位决定颜色：停在站里（门开着）时前照灯转成尾灯的暗红，
     * 表示"这车现在是停靠状态"。这是个很小的细节，但它让
     * "车停着"和"车在跑"在<b>不用看速度表</b>的情况下就能分辨。
     * <p>
     * ★ 和 {@link #drawCabinGlow} 同一个理由，UV 取车头贴图中心而不是 (0.5,0.5)。
     */
    private void drawLamps(BufferBuilder buf, TextureAtlasSprite tex, double hw,
                           double y0, double z1, int phase) {
        float[] c = (phase == TrainSpec.PH_DWELL || phase == TrainSpec.PH_DOOR_OPENING
                || phase == TrainSpec.PH_DOOR_CLOSING) ? LAMP_TAIL : LAMP_HEAD;
        double y = y0 + 0.55D;
        double eps = 0.012D;
        double u = (tex.getMinU() + tex.getMaxU()) * 0.5D;
        double v = (tex.getMinV() + tex.getMaxV()) * 0.5D;
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        for (int sign = -1; sign <= 1; sign += 2) {
            double cx = sign * (hw * 0.55D);
            double x0 = cx - 0.18D;
            double x1 = cx + 0.18D;
            double ya = y - 0.16D;
            double yb = y + 0.16D;
            double zz = z1 + eps;
            buf.pos(x0, ya, zz).tex(u, v).color(c[0], c[1], c[2], 1.0F).endVertex();
            buf.pos(x1, ya, zz).tex(u, v).color(c[0], c[1], c[2], 1.0F).endVertex();
            buf.pos(x1, yb, zz).tex(u, v).color(c[0], c[1], c[2], 1.0F).endVertex();
            buf.pos(x0, yb, zz).tex(u, v).color(c[0], c[1], c[2], 1.0F).endVertex();
        }
        Tessellator.getInstance().draw();
    }

    // ================================================================ 画一个四边形

    /**
     * 画一个四边形（按给定顺序的四个角），整面用同一档亮度。
     * <p>
     * ★ UV 用 {@code tex} 的整幅（minU..maxU / minV..maxV）——
     * 因为这些贴图是"一块钢板"的自洽图案，铺在整个面上正好；
     * 按格重复的话要在车体这么小的面上平铺好几次，反而会糊成一团噪点。
     * <p>
     * ★ {@code lit} 必须落在 {@code 0..1}（见上面 LIT_* 的注释：超了会被截断成字节）。
     */
    private static void faceQuad(BufferBuilder buf, TextureAtlasSprite tex,
                                 double ax, double ay, double az,
                                 double bx, double by, double bz,
                                 double cx, double cy, double cz,
                                 double dx, double dy, double dz,
                                 float lit) {
        double u0 = tex.getMinU(), u1 = tex.getMaxU();
        double v0 = tex.getMinV(), v1 = tex.getMaxV();
        buf.pos(ax, ay, az).tex(u0, v1).color(lit, lit, lit, 1.0F).endVertex();
        buf.pos(bx, by, bz).tex(u1, v1).color(lit, lit, lit, 1.0F).endVertex();
        buf.pos(cx, cy, cz).tex(u1, v0).color(lit, lit, lit, 1.0F).endVertex();
        buf.pos(dx, dy, dz).tex(u0, v0).color(lit, lit, lit, 1.0F).endVertex();
    }

    // ================================================================ 小工具

    private static TextureAtlasSprite sprite(Minecraft mc, String name) {
        return mc.getTextureMapBlocks().getAtlasSprite(name);
    }

    /**
     * ★★★ 渲染自检：**只在确认出问题时出声**（每个实体 id 只判一次）。
     * <p>
     * 起因（2026-09-21）：玩家报「纯白车头 + 车厢是空气」，而 `latest.log` / `debug.log`
     * 全都干净 —— 这是个<b>静默</b>故障。当时的做法是把渲染器手里的读数全打出来取证，
     * 最后真凶另有其人（渲染器压根没被 RenderManager 收下，见
     * {@code ClientProxy.preInit} 那段时序注释）。这里把那次取证改造成三条
     * <b>长期</b>的静默故障出口，正常时一个字都不打：
     * <ol>
     *   <li><b>贴图没进方块图集</b> —— 拿到的是 {@code MissingTextureAtlasSprite}（不是 null），
     *       画出来是一片没有贴图的平面。修法：加进 {@code ClientModelRegistry.onTextureStitch}
     *       的缝合清单（1.12.2 的图集只收被模型 JSON 引用过的贴图）；</li>
     *   <li><b>读数包一直没到</b> —— {@code ClientTrainState.cars()} 兜底返回 0，
     *       于是<b>车厢一节都不画</b>（"人坐在空气上"），而聊天栏那句是<b>服务端</b>发的、
     *       照样写 2 节，两边不一致正是这条的特征。
     *       判据用 {@code ticksExisted}：读数包最多 20 刻推一次，
     *       车活过 60 刻还没收到就绝不可能是"路上还没到"；</li>
     *   <li><b>包到了、但说编组是 0 节</b> —— 和上一条是两回事（那边是网络、这边是内核状态），
     *       分开报才能一眼看出该查哪一头。</li>
     * </ol>
     * ★★★ <b>三个出口的"已报过"闸门各自独立，而且必须在判到结论的那一刻才记。</b>
     * 这里踩过一次很典型的坑：原先只有<b>一个</b> {@code DIAGNOSED} 集合，进函数先
     * {@code if (!add(id)) return;} —— 于是整套判断只在实体被渲染的<b>第一帧</b>跑。
     * 而第一帧几乎必然还没收到读数包（服务端最多 20 刻才推一次），
     * 那一刻 {@code ticksExisted} 又还没到 60 → <b>② 这条出口永远不可能响</b>，
     * 而它的沉默恰恰会被当成"读数包没问题"的证据。
     * <p>
     * 通式：<b>"判过一次就不再判"与"条件还没成熟就先别报"凑在一起 = 一条死代码。</b>
     * 闸门只能用来防止<b>已经成立</b>的结论重复上报，不能用来代替"时机"。
     */
    /** ① 贴图：图集在资源重载那一刻就定了，按实体判一次就够 */
    private static final java.util.Set<Integer> TEX_DIAGNOSED = new java.util.HashSet<Integer>();
    /** ② 读数包没到：只在真判到"超时仍没到"时才记，收到之后本条就再也不看了 */
    private static final java.util.Set<Integer> NO_PACKET_REPORTED = new java.util.HashSet<Integer>();
    /** ③ 包到了、但内核说编组是 0 节（和 ② 是两回事） */
    private static final java.util.Set<Integer> ZERO_CARS_REPORTED = new java.util.HashSet<Integer>();

    private static void diagnose(EntityTrain entity, int[] st, int cars,
                                 TextureAtlasSprite side, TextureAtlasSprite roof,
                                 TextureAtlasSprite nose, TextureAtlasSprite end,
                                 TextureAtlasSprite bogie, TextureAtlasSprite door) {
        Integer id = Integer.valueOf(entity.getEntityId());

        // ---- ② 读数包还没到：★ 每帧都重判，直到真收到为止。
        //   必须在下面那个"每实体只看一次"的闸门<b>之前</b> —— 见上面那段通式：
        //   实体被渲染的第一帧几乎必然还没收到包（服务端最多 20 刻才推一次），
        //   把这条判断放进一次性闸门里 = 永远判不出来，而它的沉默又会被当成好消息。
        if (st == null) {
            if (entity.ticksExisted > 60 && NO_PACKET_REPORTED.add(id)) {
                com.rsaudio.RsAudio.warn("[列车] 实体 %d 已活 %d tick 仍未收到读数包"
                                + " —— 车厢会按 0 节画（人坐在空气上）。查 RsAudio.preInit 的 registerModEntity"
                                + " 与 PacketHandler 里 PacketTrainSync 的注册",
                        id, Integer.valueOf(entity.ticksExisted));
            }
        } else if (cars == 0 && ZERO_CARS_REPORTED.add(id)) {
            // ---- ③ 包到了，但内核说编组就是 0 节。和 ② 是两回事（一个是网络、一个是内核状态），
            //   分开报才能一眼看出该查哪一头。
            com.rsaudio.RsAudio.warn("[列车] 读数包说实体 %d 的编组是 0 节（相位 %d、乘客 %d）"
                            + " —— 车厢一节都不会画。这不是渲染问题：内核槽 12 就是 0，"
                            + "去查这车是不是被一节节摘光了（USABLE_CARS=%d 才算可运营）",
                    id, Integer.valueOf(ClientTrainState.phase(st)),
                    Integer.valueOf(ClientTrainState.passengers(st)),
                    Integer.valueOf(TrainSpec.USABLE_CARS));
        }

        // ---- ① 贴图：图集在资源重载那一刻就定了，所以按实体判一次就够
        if (!TEX_DIAGNOSED.add(id)) {
            return;
        }
        String[] names = {TrainTextures.BODY_SIDE, TrainTextures.BODY_ROOF, TrainTextures.BODY_NOSE,
                TrainTextures.BODY_END, TrainTextures.BOGIE, TrainTextures.DOOR};
        TextureAtlasSprite[] sprites = {side, roof, nose, end, bogie, door};
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < sprites.length; i++) {
            String how = describe(sprites[i]);
            if (!"OK".equals(how)) {
                if (bad.length() > 0) {
                    bad.append(' ');
                }
                bad.append(names[i]).append('=').append(how);
            }
        }
        if (bad.length() > 0) {
            com.rsaudio.RsAudio.warn("[列车] 贴图没进方块图集：%s"
                    + " —— 把这几张加进 ClientModelRegistry.onTextureStitch 的缝合清单"
                    + "（1.12.2 的图集只收被模型 JSON 引用过的贴图，渲染器直接引用的必须手工缝合）",
                    bad.toString());
        }
    }

    /** 贴图的三种状态：拿到真的 / 没进图集（missingno）/ 连对象都没有 */
    private static String describe(TextureAtlasSprite s) {
        if (s == null) {
            return "★null(查不到这张)";
        }
        String cls = s.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (cls.contains("missing")) {
            return "★missingno(没进图集)";
        }
        return "OK";
    }

    /**
     * 门的开度 0..1。
     * <p>
     * ★ 用相位近似，不追求精确的分段进度：真正逐 tick 的开门动画在
     * {@code TrainCore} 里有（{@code doorTimer}），但那个值<b>不在同步包里</b> ——
     * 为客户端的门动画专门多发一个字段不值得（每 20 刻才推一次，拿来做
     * 12 刻的动画本来也不够用）。
     * 所以这里是"分相位给开度"：开门段 0.5、停站 1.0、关门段 0.5、其它 0。
     * 观感上足够，而且<b>永远不会和内核的真实状态矛盾到"门开着却上不去"</b>，
     * 因为"能不能上车"是服务端判定的，跟这里画什么无关。
     */
    private static float doorAjar(int phase) {
        switch (phase) {
            case TrainSpec.PH_DWELL:
                return 1.0F;
            case TrainSpec.PH_DOOR_OPENING:
            case TrainSpec.PH_DOOR_CLOSING:
                return 0.55F;
            default:
                return 0.0F;
        }
    }

    /** 把角度差归一化到 (-180, 180]，避免 yaw 从 359 → 1 时插值绕一圈 */
    private static float normalizeDeg(float deg) {
        while (deg > 180.0F) {
            deg -= 360.0F;
        }
        while (deg <= -180.0F) {
            deg += 360.0F;
        }
        return deg;
    }
}

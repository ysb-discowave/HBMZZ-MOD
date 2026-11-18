package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockHuangPoster;
import com.rsaudio.tile.TileHuangPoster;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * 纪念挂画的画面渲染（可靠版）。
 *
 * <h2>为什么不再用 TESR</h2>
 * 原来的 HuangPosterRenderer 继承 TileEntitySpecialRenderer，但实测（真实实例日志）
 * 它的 render() 从头到尾没被调用过一次 —— 尽管渲染器已绑定、纹理已预热、右键翻页正常。
 * 而无边地追 RenderGlobal 的 TE 筛选链（getCompiledChunk().getTileEntities /
 * shouldRenderInPass / isBoundingBoxInFrustum）既慢又不可靠。
 * 本模组里的监控画面（CameraFeedRenderer）走 RenderWorldLastEvent 一直是好的，
 * 所以这里照搬同一条路径：世界画完、界面没画那一刻，自己遍历所有挂画方块实体直接画。
 * 这条路由我们完全掌控，不经过引擎那套 TE 筛选。
 *
 * <h2>坐标</h2>
 * RenderWorldLastEvent 触发时，modelview 已经带上了相机的平移旋转，
 * 画世界坐标的东西要减掉相机的插值坐标 —— 引擎把它放在
 * TileEntityRendererDispatcher.staticPlayerX/Y/Z（renderWorldPass 早期就设好了）。
 * 所以传给 drawQuad 的是 (pos - staticPlayer)。
 *
 * <h2>拼大图</h2>
 * 保留原来的 BFS：同朝向、同页码、墙面上相邻的挂画连成一片，比例合适时只由
 * 锚点画一张覆盖整片的大四边形；比例失调就各画各的单块。
 */
public class HuangPosterWorldRenderer {

    private static final double VIEW_RANGE = 48.0D;
    /** 板厚 2/16。板贴在方块"背面"（facing 法线的反方向那侧），画面朝玩家 */
    private static final double THICK = 2.0D / 16.0D;
    private static final double LIFT_FRAME = 0.006D;    // 金框层超出板正面的距离
    private static final double LIFT_PICTURE = 0.012D;  // 画面层（更靠玩家，压住金框中间）
    private static final double FRAME_INSET = 2.5D / 16.0D; // 画面内缩，露出四周金框
    private static final float RATIO_TOL = 1.45F;
    private static final int MAX_CELLS = 96;

    private static final ResourceLocation[] TEX = new ResourceLocation[BlockHuangPoster.PAGES];
    /** 金框贴图（16×16，四周金边、中间深色），用来画画面外圈那一圈金边 */
    private static final ResourceLocation TEX_FRAME =
            new ResourceLocation(RsAudio.MODID, "textures/blocks/poster_frame.png");

    static {
        for (int i = 0; i < TEX.length; i++) {
            TEX[i] = new ResourceLocation(RsAudio.MODID,
                    "textures/poster/" + BlockHuangPoster.VARIANTS_NAMES[i] + ".png");
        }
    }

    /** 注册到事件总线（在 ClientProxy.preInit 调） */
    public static void register() {
        MinecraftForge.EVENT_BUS.register(new HuangPosterWorldRenderer());
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        try {
            renderAll(event.getPartialTicks());
        } catch (Throwable t) {
            if (!crashLogged) {
                crashLogged = true;
                RsAudio.warn("[纪念挂画] 世界渲染绘制出错（只报这一次）：%s", t.toString());
                t.printStackTrace();
            }
        }
    }

    private void renderAll(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null || mc.player == null) {
            return;
        }
        double px = TileEntityRendererDispatcher.staticPlayerX;
        double py = TileEntityRendererDispatcher.staticPlayerY;
        double pz = TileEntityRendererDispatcher.staticPlayerZ;

        // 用索引遍历，避免 ConcurrentModification
        java.util.List<TileEntity> list = world.loadedTileEntityList;
        for (int i = 0; i < list.size(); i++) {
            TileEntity te = list.get(i);
            if (!(te instanceof TileHuangPoster)) {
                continue;
            }
            try {
                renderOne(world, (TileHuangPoster) te, px, py, pz);
            } catch (Throwable t) {
                // 一帧里某块画出错不该拖垮整帧，更不该连累别的画
                if (!crashLogged) {
                    crashLogged = true;
                    RsAudio.warn("[纪念挂画] 单块绘制出错（只报这一次）：%s", t.toString());
                }
            }
        }
    }

    private void renderOne(World world, TileHuangPoster te, double px, double py, double pz) {
        BlockPos pos = te.getPos();
        if (Minecraft.getMinecraft().player.getDistanceSqToCenter(pos) > VIEW_RANGE * VIEW_RANGE) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof BlockHuangPoster)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockHuangPoster.FACING);
        if (facing == null || facing.getAxis().isVertical()) {
            facing = EnumFacing.NORTH;
        }
        int page = te.getPage();
        if (page < 0 || page >= TEX.length) {
            return;
        }

        Layout layout = findLayout(world, pos, facing, page);
        boolean big = false;
        if (layout != null && layout.solidRect && (layout.w > 1 || layout.h > 1)) {
            float rectAspect = (float) layout.w / (float) layout.h;
            float imgAspect = BlockHuangPoster.PAGE_ASPECTS[page];
            float ratio = rectAspect / imgAspect;
            big = ratio >= 1.0F / RATIO_TOL && ratio <= RATIO_TOL;
        }

        double x = pos.getX() - px;
        double y = pos.getY() - py;
        double z = pos.getZ() - pz;

        if (big) {
            if (!pos.equals(layout.anchor)) {
                return;   // 大图只由锚点画，其它块本帧不画
            }
            // 一整块金框铺满整片区域，再把画面贴上，接缝处不会出现一圈圈小金框
            drawPoster(facing, TEX[page], x, y, z, layout.w, layout.h);
            if (!bigDrawLogged) {
                bigDrawLogged = true;
                RsAudio.log("[纪念挂画] 首次拼接绘制：page=%d facing=%s 区域=%dx%d 锚点=%s",
                        page, facing, layout.w, layout.h, pos);
            }
        } else {
            drawPoster(facing, TEX[page], x, y, z, 1, 1);
            if (!firstDrawLogged) {
                firstDrawLogged = true;
                RsAudio.log("[纪念挂画] 首次单块绘制：page=%d facing=%s pos=%s", page, facing, pos);
            }
        }
    }

    /**
     * 画一幅挂画（金框底 + 内缩画面）。
     * anchor 传进来的是整片区域里 u 最小、y 最小的那块方块原点；w×h 是区域尺寸。
     */
    private static void drawPoster(EnumFacing facing, ResourceLocation picture,
                                   double ax, double ay, double az, int w, int h) {
        // 底层：整块金框（inset=0），整片拼图只铺一张，接缝处没有多余边框
        drawQuad(facing, TEX_FRAME, ax, ay, az, w, h, 0.0D, LIFT_FRAME);
        // 上层：真实画面，四周留出金边；比金框更靠玩家一点，避免深度打架
        drawQuad(facing, picture, ax, ay, az, w, h, FRAME_INSET, LIFT_PICTURE);
    }

    /** 沿墙面找同朝向、同页码、相邻的挂画，返回包围盒信息 */
    private static Layout findLayout(World world, BlockPos pos, EnumFacing facing, int page) {
        int ux = facing.getFrontOffsetZ();
        int uz = -facing.getFrontOffsetX();
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        Set<BlockPos> seen = new HashSet<BlockPos>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<BlockPos>();
        queue.add(pos);
        seen.add(pos);

        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        BlockPos anchor = pos;
        int anchorU = Integer.MAX_VALUE;
        int count = 0;

        while (!queue.isEmpty() && count < MAX_CELLS) {
            BlockPos p = queue.poll();
            count++;
            int u = p.getX() * ux + p.getZ() * uz;
            int v = p.getY();
            if (u < minU) minU = u;
            if (u > maxU) maxU = u;
            if (v < minY) minY = v;
            if (v > maxY) maxY = v;
            if (u < anchorU || (u == anchorU && v < anchor.getY())) {
                anchor = p;
                anchorU = u;
            }
            for (int d = 0; d < 4; d++) {
                int du = (d == 0) ? 1 : (d == 1 ? -1 : 0);
                int dv = (d == 2) ? 1 : (d == 3 ? -1 : 0);
                cur.setPos(p.getX() + ux * du, p.getY() + dv, p.getZ() + uz * du);
                BlockPos np = cur.toImmutable();
                if (seen.contains(np)) {
                    continue;
                }
                if (isSamePoster(world, np, facing, page)) {
                    seen.add(np);
                    queue.add(np);
                }
            }
        }
        int w = maxU - minU + 1;
        int h = maxY - minY + 1;
        boolean solid = (count == w * h);
        return new Layout(w, h, solid, anchor);
    }

    private static boolean isSamePoster(World world, BlockPos p, EnumFacing facing, int page) {
        IBlockState st = world.getBlockState(p);
        if (!(st.getBlock() instanceof BlockHuangPoster)) {
            return false;
        }
        if (st.getValue(BlockHuangPoster.FACING) != facing) {
            return false;
        }
        TileEntity te = world.getTileEntity(p);
        return te instanceof TileHuangPoster && ((TileHuangPoster) te).getPage() == page;
    }

    /**
     * 在墙面画一个矩形四边形（局部坐标相对传入的锚点方块原点）。
     *
     * 锚点 (ax,ay,az) 是整片区域里 u 最小、y 最小的那块方块。区域沿墙面水平方向
     * (ux,uz) 展开 w 格、竖直向上展开 h 格。这样 NORTH/SOUTH/EAST/WEST 四个朝向
     * 的拼图都落在同一片方块外表面上，不会再画到区域外侧"浮在旁边"。
     *
     * 关键点：竖向展开方向是 +Y（从锚点所在格的下沿 ay 往上），y 不能像旧代码那样
     * 用 h/2 当中心——那会让 h 格高的图整体往上偏移半格。
     */
    private static void drawQuad(EnumFacing facing, ResourceLocation tex, double ax, double ay, double az,
                                 int w, int h, double inset, double lift) {
        double nx = facing.getFrontOffsetX();   // 法线（画朝玩家的方向）
        double nz = facing.getFrontOffsetZ();
        // 墙面水平轴：朝 u 增大方向。u = x*ux + z*uz，与 findLayout 保持一致
        double ux = nz;
        double uz = -nx;

        // 板贴在方块"背面"：即法线的反方向那侧。板正面距格中心 = 0.5 - 板厚 = 0.375：
        //   NORTH(nz=-1) 板在 +Z 侧，正面 z≈0.875；SOUTH 在 -Z 侧 z≈0.125
        //   EAST(nx=+1)  板在 -X 侧，正面 x≈0.125；WEST  在 +X 侧 x≈0.875
        // 位置 = 格中心 - n*faceDepth，faceDepth 越小越靠玩家。
        // 所以 lift（往玩家挪）要让 faceDepth 变小：faceDepth = 0.375 - lift。
        double faceDepth = (0.5D - THICK) - lift;
        // 区域中心 = 锚点格中心(0.5,0.5,0.5) + 沿 u 展开 (w-1)/2 格 - n*面深
        double cx = 0.5D + ux * ((w - 1) / 2.0D) - nx * faceDepth;
        double cz = 0.5D + uz * ((w - 1) / 2.0D) - nz * faceDepth;
        double cy = 0.5D + (h - 1) / 2.0D;   // 锚点格下沿(0) 沿 +Y 展开

        double halfW = w / 2.0D - inset;
        double halfH = h / 2.0D - inset;
        double x0 = cx - ux * halfW;
        double z0 = cz - uz * halfW;
        double x1 = cx + ux * halfW;
        double z1 = cz + uz * halfW;
        double yTop = cy + halfH;
        double yBot = cy - halfH;

        GlStateManager.pushMatrix();
        try {
            GlStateManager.translate(ax, ay, az);

            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            Minecraft.getMinecraft().getTextureManager().bindTexture(tex);

            GlStateManager.disableLighting();
            GlStateManager.disableCull();
            GlStateManager.disableRescaleNormal();
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

            Tessellator tess = Tessellator.getInstance();
            BufferBuilder buf = tess.getBuffer();
            buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
            buf.pos(x0, yTop, z0).tex(0.0D, 0.0D).endVertex();   // 左上
            buf.pos(x1, yTop, z1).tex(1.0D, 0.0D).endVertex();   // 右上
            buf.pos(x1, yBot, z1).tex(1.0D, 1.0D).endVertex();   // 右下
            buf.pos(x0, yBot, z0).tex(0.0D, 1.0D).endVertex();   // 左下
            tess.draw();
        } finally {
            GlStateManager.popMatrix();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.disableBlend();
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableCull();
            GlStateManager.enableLighting();
        }
    }

    private static final class Layout {
        final int w, h;
        final boolean solidRect;
        final BlockPos anchor;

        Layout(int w, int h, boolean solidRect, BlockPos anchor) {
            this.w = w; this.h = h; this.solidRect = solidRect; this.anchor = anchor;
        }
    }

    private static boolean crashLogged;
    private static boolean firstDrawLogged;
    private static boolean bigDrawLogged;
}

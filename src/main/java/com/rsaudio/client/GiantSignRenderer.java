package com.rsaudio.client;

import com.rsaudio.block.BlockGiantSign;
import com.rsaudio.tile.TileGiantSign;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;

/**
 * 巨大标志牌上的字。
 *
 * <h2>文字怎么贴到方块面上</h2>
 * 变换用的是原版告示牌那一套：平移到文字区域的"左上角" → 绕 Y 转到朝向观看者 →
 * {@code scale(-s,-s,s)} → {@code drawString}。
 * <b>局部 +Z 指向 FACING</b>，所以角度表是
 * {@code north=180 / east=90 / south=0 / west=270}
 * —— 和 {@code MonitorConsoleRenderer.textAngle} 是同一张表，
 * 那次已经用四个朝向逐个核对过，这里照抄而不是重新推。
 * <p>
 * ★ 副作用值得记下来：{@code scale} 的两个负号把局部坐标系翻成了
 * "x 向右、y 向下"，也就是和 {@code drawString} 自己的坐标系一致。
 * 所以下面所有位置计算都可以在<b>像素</b>里想（{@code drawString} 的第二、三个参数
 * 单位就是像素），不用再操心世界坐标的上下左右。
 *
 * <h2>★ 文字面在板子的"外表面"，不是方块的那个面</h2>
 * 牌子是一块 2/16 厚的板，贴在字面的<b>反面</b>。也就是说
 * FACING=north 时板子在 {@code z∈[14/16,1]}，你能看到的那一面在 {@code z=14/16} ——
 * 离方块中心只有 {@code 0.375}，而<b>不是</b> {@code 0.5}。
 * 按 {0.5} 去摆会把字画在方块外面 2/16 处浮着。
 * 这里统一用 {@link #SURFACE} 表达"从中心往字面方向走多远"。
 *
 * <h2>★ 字号按"最宽那一行"自适应，不是一个固定值</h2>
 * 玩家会写 {@code "A"} 也会写 {@code "RESTRICTED AREA"}。
 * 固定字号的结果要么短文本小得可怜，要么长文本直接画到方块外面去。
 * 这里三行<b>共用</b>一个字号（逐行自适应会大小不一，像排版事故），
 * 取三个上限里最小的那个：
 * <ol>
 *   <li>{@link #MAX_SCALE}：上限，防止一个字符撑满整块牌子；</li>
 *   <li>行高上限：三行都塞进 {@link #FH}，且每行还得低于自己那一格的高度；</li>
 *   <li>宽度上限：最长那行必须塞进 {@link #FW}。</li>
 * </ol>
 * 宽度用的是 {@code fontRenderer.getStringWidth()}，<b>不是</b>字符数 × 平均值：
 * 原版字体是变宽的（{@code i} 比 {@code W} 窄一半），估出来的值会让长行溢出。
 */
public class GiantSignRenderer extends net.minecraft.client.renderer.tileentity.TileEntitySpecialRenderer<TileGiantSign> {

    /** 看多远还画字。牌子就是要远远能认出来，所以比别的 TESR 远 */
    private static final double VIEW_RANGE = 64.0D;

    /** 板子的厚度（格）。★ 必须和 {@code BlockGiantSign.THICK} 一致 */
    private static final double THICK = 2.0D / 16.0D;

    /** 从方块中心往字面方向到板子外表面的距离（负值 = 往回走） */
    private static final double SURFACE = THICK - 0.5D;

    /** 字面再往外挪一点，免得和板子的面 z-fighting */
    private static final double TEXT_LIFT = 0.004D;

    /** 文字区域（格）：留出板子边框，四周各让 0.5~1.5/16 */
    private static final double FW = 15.0D / 16.0D;
    private static final double FH = 13.0D / 16.0D;

    /** 字号上限（世界单位 / 像素）。0.027 × 9px ≈ 0.24 格高的字 */
    private static final double MAX_SCALE = 0.027D;

    /** 原版字体一个字符格的高度（含行距） */
    private static final double FONT_CELL = 9.0D;

    /** 未照明时把字色压暗到这个比例 —— 让"没开灯"看得出来 */
    private static final float UNLIT_DIM = 0.55F;

    @Override
    public void render(TileGiantSign te, double x, double y, double z, float partialTicks,
                       int destroyStage, float alpha) {
        if (destroyStage >= 0 || te == null || te.getWorld() == null || !te.hasAnyText()) {
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
        if (!(state.getBlock() instanceof BlockGiantSign)) {
            return;
        }
        EnumFacing facing = state.getValue(BlockGiantSign.FACING);
        boolean lit = state.getValue(BlockGiantSign.LIT).booleanValue();
        if (facing.getAxis().isVertical()) {
            facing = EnumFacing.NORTH;              // 兜底：放置逻辑不会产生竖直朝向
        }

        FontRenderer font = getFontRenderer();
        if (font == null) {
            return;
        }

        // 三行共用字号：先算出最长那行的像素宽
        int maxWidth = 1;
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            String line = te.getLine(i);
            if (line != null && !line.isEmpty()) {
                maxWidth = Math.max(maxWidth, font.getStringWidth(line));
            }
        }
        double slotH = FH / TileGiantSign.LINE_COUNT;
        double scale = Math.min(MAX_SCALE,
                Math.min(slotH * 0.92D / FONT_CELL, FW * 0.98D / (double) maxWidth));
        if (scale <= 0.0D) {
            return;
        }

        int rgb = dim(TileGiantSign.colorRgb(te.getColorIndex()), lit);
        Frame f = new Frame(facing);
        double[] origin = f.topLeft(FW / 2.0D, FH / 2.0D);

        GlStateManager.pushMatrix();
        GlStateManager.translate(x + origin[0], y + origin[1], z + origin[2]);
        GlStateManager.rotate(textAngle(facing), 0.0F, 1.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        // 自发光：牌子在暗处也该读得出来（本模组其它 TESR 同样处理）
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240.0F, 240.0F);

        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            String line = te.getLine(i);
            if (line == null || line.isEmpty()) {
                continue;
            }
            // 这里已经是"像素"坐标系（scale 之后），所以下面两个数直接就是像素偏移
            int width = font.getStringWidth(line);
            int dx = (int) Math.round((FW / scale - width) / 2.0D);
            int dy = (int) Math.round(i * (slotH / scale) + (slotH / scale - FONT_CELL) / 2.0D);
            font.drawStringWithShadow(line, dx, dy, rgb);
        }

        GlStateManager.disableBlend();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    /** 从面外坐标（格）取一个 0xRRGGBB，未照明时压暗 */
    private static int dim(int rgb, boolean lit) {
        if (lit) {
            return rgb;
        }
        int r = (int) (((rgb >> 16) & 0xFF) * UNLIT_DIM);
        int g = (int) (((rgb >> 8) & 0xFF) * UNLIT_DIM);
        int b = (int) ((rgb & 0xFF) * UNLIT_DIM);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * 让文字面朝观看者的 Y 轴旋转角。
     * <p>
     * {@code rotate(θ, 0,1,0)} 把局部 {@code +Z} 转到 {@code (sinθ, 0, cosθ)}；
     * 要它等于 {@code FACING}，于是 north=180 / east=90 / south=0 / west=270
     * —— 正是原版告示牌用的 {@code -getHorizontalAngle()}，和
     * {@code MonitorConsoleRenderer.textAngle} 逐字相同。
     */
    private static float textAngle(EnumFacing facing) {
        switch (facing) {
            case NORTH:
                return 180.0F;
            case EAST:
                return 90.0F;
            case WEST:
                return 270.0F;
            case SOUTH:
            default:
                return 0.0F;
        }
    }

    /**
     * 由 {@code FACING} 直接算出的世界基向量，外加"文字区域左上角"的位置。
     * <p>
     * 取法和 {@code MonitorConsoleRenderer.Frame} 相同：
     * {@code u} = 站在外面看这块面的"右"，{@code v} = 世界上的"上"，
     * {@code n} = 朝外的法线。纯向量运算，不跟原版任何角度约定对齐。
     */
    private static final class Frame {
        final double nx;
        final double ny;
        final double nz;
        final double ux;
        final double uz;

        Frame(EnumFacing facing) {
            // ★ 1.12.2 的名字是 getFrontOffsetX/Z
            this.nx = facing.getFrontOffsetX();
            this.ny = facing.getFrontOffsetY();
            this.nz = facing.getFrontOffsetZ();
            // 站在屏幕外面的人的"右" = 视线方向 × 上方向 = (-n) × (0,1,0) = (n.z, 0, -n.x)
            this.ux = facing.getFrontOffsetZ();
            this.uz = -facing.getFrontOffsetX();
        }

        /**
         * 文字区域左上角的世界坐标（局部的，未加方块 x/y/z）。
         *
         * @param halfW 文字区域半宽（格）
         * @param halfH 文字区域半高（格）
         */
        double[] topLeft(double halfW, double halfH) {
            double cx = 0.5D + this.nx * (SURFACE + TEXT_LIFT);
            double cy = 0.5D + this.ny * (SURFACE + TEXT_LIFT);
            double cz = 0.5D + this.nz * (SURFACE + TEXT_LIFT);
            // 左上角 = 中心 - 右×半宽 + 上×半高
            return new double[]{
                    cx - this.ux * halfW,
                    cy + halfH,
                    cz - this.uz * halfW,
            };
        }
    }
}

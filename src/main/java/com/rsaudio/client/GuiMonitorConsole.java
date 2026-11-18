package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetMonitor;
import com.rsaudio.tile.TileMonitorCamera;
import com.rsaudio.tile.TileMonitorConsole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;

/**
 * 监控控制台界面：左边是实时画面，右边是频道与摄像头清单。
 *
 * <h3>画面从哪来</h3>
 * 不是"服务端拍好发过来"，而是本机渲染的：本界面每帧向
 * {@link CameraFeedRenderer} 登记一次"我需要这个摄像头的画面"，
 * 引擎在该帧的世界渲染收尾时把它渲染进一张离屏贴图，
 * 这里再把那张贴图贴到屏幕的方框里。所以打开界面时画面是活的 ——
 * 有水流、有雪花、有走动的怪。
 *
 * <h3>为什么界面不自己触发渲染</h3>
 * 因为离屏渲染必须在"世界已经画完、界面还没开始画"那个时刻做，
 * 否则会把界面的正交投影状态搞乱。所以界面只负责<b>登记需求</b>，
 * 真正渲染交给 {@code RenderWorldLastEvent}（代价是画面比现实晚一帧，看不出来）。
 */
public class GuiMonitorConsole extends GuiScreen {

    private static final int ID_PREV = 0;
    private static final int ID_NEXT = 1;
    private static final int ID_OVERLAY = 2;
    private static final int ID_DONE = 3;

    /** 面板宽度 */
    private static final int PANEL_W = 122;
    private static final int MARGIN = 8;

    private final BlockPos pos;
    private int channel = TileMonitorConsole.DEFAULT_CHANNEL;
    private boolean overlay = true;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    private boolean loaded;

    /** 上一帧画出来的画面区域，供鼠标点击/调试用 */
    private int feedX;
    private int feedY;
    private int feedW;
    private int feedH;

    public GuiMonitorConsole(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        // 名册按 10 刻一次刷新，界面刚打开时可能还是上一批数据 —— 立刻刷一次
        ClientMonitorCache.refreshNow();
        readFromTile();

        layout();

        int panelX = feedX + feedW + MARGIN;
        // 两个箭头按钮夹着中间的频道号，所以箭头排在字体下面一行
        int rowY = feedY + 12;
        int half = 22;
        this.buttonList.add(new GuiButton(ID_PREV, panelX, rowY, half, 18, "◀"));
        this.buttonList.add(new GuiButton(ID_NEXT, panelX + PANEL_W - half, rowY, half, 18, "▶"));
        this.buttonList.add(new GuiButton(ID_OVERLAY, panelX, rowY + 22, PANEL_W, 18, overlayLabel()));
        int doneW = 90;
        this.buttonList.add(new GuiButton(ID_DONE, this.width - MARGIN - doneW, this.height - 26,
                doneW, 20, I18n.format("gui.rsaudio.monitor.done")));
    }

    /**
     * 按窗口大小算出画面方框（保持 16:9）。
     * <p>
     * 竖直方向的预算是这样分的（这几行数字必须一起看，改一个就要重算一遍）：
     * <pre>
     *   8 .. 17    标题
     *   26 .. feedY + feedH    画面框（外框再往外 2px）
     *   height-36  底部提示 legend
     *   height-20  状态行 status
     *   height-26 .. height-6  右下"完成"按钮
     * </pre>
     * 所以 {@code availH} 要留够 {@code feedY(26) + 提示(10) + 状态(12) + 余量}，
     * 一开始写成 {@code height - 62} 时画面底边正好压在 legend 上（画面高到
     * {@code height-36}，legend 也画在 {@code height-36}）。
     */
    private void layout() {
        int availH = this.height - 84;
        int availW = this.width - MARGIN * 3 - PANEL_W;
        int byHeight = availH * 16 / 9;
        int w = Math.min(availW, byHeight);
        if (w < 96) {
            // 窗口特别小（或 GUI 缩放特别大）时保底，宁可画面小一点也别画到屏幕外
            w = Math.max(64, availW);
        }
        this.feedW = w;
        this.feedH = w * 9 / 16;
        this.feedX = MARGIN;
        this.feedY = 26;
    }

    private String overlayLabel() {
        return I18n.format(overlay ? "gui.rsaudio.monitor.overlay_on" : "gui.rsaudio.monitor.overlay_off");
    }

    private void readFromTile() {
        TileMonitorConsole tile = getTile();
        if (tile != null) {
            this.channel = tile.getChannel();
            this.overlay = tile.isOverlay();
            this.loaded = true;
        }
    }

    private TileMonitorConsole getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileMonitorConsole ? (TileMonitorConsole) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_PREV:
                switchChannel(this.channel - 1);
                break;
            case ID_NEXT:
                switchChannel(this.channel + 1);
                break;
            case ID_OVERLAY:
                send(this.channel, !this.overlay);
                refreshOverlayButton();
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 1..9 直接跳频道，省得按十几次箭头
        if (keyCode >= Keyboard.KEY_1 && keyCode <= Keyboard.KEY_9) {
            switchChannel(keyCode - Keyboard.KEY_1 + 1);
            return;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            switchChannel(this.channel - 1);
            return;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            switchChannel(this.channel + 1);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void switchChannel(int newChannel) {
        int clamped = TileMonitorCamera.clampChannel(newChannel);
        if (clamped == this.channel) {
            return;
        }
        this.channel = clamped;
        send(clamped, this.overlay);
        updateStatus();
    }

    private void send(int newChannel, boolean newOverlay) {
        this.overlay = newOverlay;
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketSetMonitor(this.pos, newChannel, newOverlay));
        } catch (Throwable t) {
            RsAudio.warn("监控控制台设置发送失败（可能已断开连接）: " + t);
        }
    }

    private void refreshOverlayButton() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_OVERLAY) {
                button.displayString = overlayLabel();
                return;
            }
        }
    }

    private void updateStatus() {
        TileMonitorCamera camera = currentCamera();
        if (camera == null) {
            if (ClientMonitorCache.hasAny(this.channel)) {
                this.status = I18n.format("gui.rsaudio.monitor.all_disabled");
                this.statusColor = 0xFFD060;
            } else {
                this.status = I18n.format("gui.rsaudio.monitor.no_camera");
                this.statusColor = 0xFF7070;
            }
            return;
        }
        this.status = I18n.format("gui.rsaudio.monitor.watching", camera.labelOrPos());
        this.statusColor = 0x90FF90;
    }

    /** 当前频道正在看的摄像头（取离控制台最近的启用中那个） */
    private TileMonitorCamera currentCamera() {
        return ClientMonitorCache.nearestOn(this.channel, this.pos);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ClientMonitorCache.tick();
        TileMonitorConsole tile = getTile();
        if (tile != null) {
            // 别人（或别的端）改了频道时跟着变，但要避开"本地刚点过、包还没回来"的瞬间
            if (this.loaded && (tile.getChannel() != this.channel || tile.isOverlay() != this.overlay)) {
                this.channel = tile.getChannel();
                this.overlay = tile.isOverlay();
                refreshOverlayButton();
            }
        }
        updateStatus();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.monitor.title"),
                this.width / 2, 8, 0xFFFFFF);

        TileMonitorCamera camera = currentCamera();
        drawFeedArea(camera);
        drawPanel(camera);
        drawLegend();

        if (!this.status.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(this.status, MARGIN, this.height - 20, this.statusColor);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** 画面方框：黑底 + 外框 + 画面（或"无信号"） */
    private void drawFeedArea(TileMonitorCamera camera) {
        // 机壳
        drawRect(feedX - 2, feedY - 2, feedX + feedW + 2, feedY + feedH + 2, 0xFF202020);
        drawRect(feedX - 1, feedY - 1, feedX + feedW + 1, feedY + feedH + 1, 0xFF6A6A6A);

        if (camera == null) {
            drawRect(feedX, feedY, feedX + feedW, feedY + feedH, 0xFF050505);
            drawStaticNoise();
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.monitor.no_signal"),
                    feedX + feedW / 2, feedY + feedH / 2 - 10, 0x808080);
            drawCenteredString(this.fontRenderer,
                    I18n.format("gui.rsaudio.monitor.channel_tag", this.channel),
                    feedX + feedW / 2, feedY + feedH / 2 + 4, 0x606060);
            return;
        }

        // ★ 向渲染引擎登记"这一帧我要看它"，真正的渲染在 RenderWorldLastEvent 里做
        CameraFeedRenderer.request(camera.getPos());
        int texture = CameraFeedRenderer.getTexture(camera.getPos());

        if (texture == 0) {
            drawRect(feedX, feedY, feedX + feedW, feedY + feedH, 0xFF050505);
            drawStaticNoise();
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.monitor.connecting"),
                    feedX + feedW / 2, feedY + feedH / 2 - 4, 0xC0C0C0);
        } else {
            drawLiveTexture(texture, feedX, feedY, feedW, feedH);
        }

        if (this.overlay) {
            drawOverlayBar(camera);
        }
    }

    /** 把离屏画布当普通贴图贴上去 */
    private void drawLiveTexture(int texture, int x, int y, int w, int h) {
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.bindTexture(texture);

        // ★ 竖直方向要翻过来：帧缓冲的纹理原点在左下，而界面坐标系 y 向下。
        //   所以"界面上边"对应 v=1、"界面下边"对应 v=0
        //   （原版 Framebuffer.framebufferRenderExt 也是这么贴的）。
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, DefaultVertexFormats.POSITION_TEX);
        buffer.pos(x, y + h, this.zLevel).tex(0.0D, 0.0D).endVertex();
        buffer.pos(x + w, y + h, this.zLevel).tex(1.0D, 0.0D).endVertex();
        buffer.pos(x + w, y, this.zLevel).tex(1.0D, 1.0D).endVertex();
        buffer.pos(x, y, this.zLevel).tex(0.0D, 1.0D).endVertex();
        tessellator.draw();

        GlStateManager.bindTexture(0);
        GlStateManager.disableBlend();
    }

    /**
     * "无信号"的雪花噪点。
     * <p>
     * 用 {@code frameCounter} 让噪点每帧变样 —— 静止的雪花看起来像"卡死了"，
     * 动的才像"没信号但在收"。
     */
    private void drawStaticNoise() {
        int lines = Math.max(1, feedH / 3);
        long seed = System.nanoTime() / 40000000L;
        for (int i = 0; i < lines; i++) {
            long value = seed * 6364136223846793005L + i * 1442695040888963407L;
            int rnd = (int) ((value >>> 24) & 0xFF);
            int ly = feedY + (i * feedH) / lines;
            int lh = Math.max(1, feedH / lines - 1);
            int lw = (int) ((long) feedW * (30 + rnd % 70) / 100);
            int lx = feedX + (rnd * 7) % Math.max(1, feedW - lw);
            int gray = 20 + (rnd & 0x1F);
            drawRect(lx, ly, lx + lw, ly + lh, 0xFF000000 | (gray << 16) | (gray << 8) | gray);
        }
    }

    /** 画面上的字幕条：频道号 + 摄像头名字 + 画面里的人 */
    private void drawOverlayBar(TileMonitorCamera camera) {
        int barH = 10;
        int barY = feedY + feedH - barH;
        drawRect(feedX, barY, feedX + feedW, feedY + feedH, 0xA0000000);
        String left = I18n.format("gui.rsaudio.monitor.channel_tag", this.channel)
                + "  " + camera.labelOrPos();
        this.fontRenderer.drawStringWithShadow(left, feedX + 2, barY + 1, 0xE0E0E0);

        List<String> seen = CameraFeedRenderer.getSeen(camera.getPos());
        String right = seen.isEmpty()
                ? I18n.format("gui.rsaudio.monitor.overlay_clear")
                : I18n.format("gui.rsaudio.monitor.overlay_seen", Integer.valueOf(seen.size()));
        int w = this.fontRenderer.getStringWidth(right);
        this.fontRenderer.drawStringWithShadow(right, feedX + feedW - 2 - w, barY + 1,
                seen.isEmpty() ? 0x90FF90 : 0xFFD060);
    }

    /** 右侧面板：频道、开关、摄像头清单、画面内实体 */
    private void drawPanel(TileMonitorCamera camera) {
        int x = feedX + feedW + MARGIN;
        int y = feedY;
        int w = PANEL_W;

        drawRect(x - 2, y - 2, x + w + 2, y + feedH + 2, 0x60000000);

        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.monitor.channel_label"),
                x, y, 0xFFFFFF);
        // 频道号画在两个箭头按钮中间那一格里（按钮在 feedY+12..30，字体 8 像素高）
        String channelText = I18n.format("gui.rsaudio.monitor.channel_value", this.channel);
        int cw = this.fontRenderer.getStringWidth(channelText);
        this.fontRenderer.drawStringWithShadow(channelText, x + (w - cw) / 2, y + 17, 0xFFFF80);

        int listTop = y + 64;

        // 摄像头清单
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.monitor.cameras"), x, listTop, 0xA0C0FF);
        int lineY = listTop + 12;
        List<TileMonitorCamera> cameras = ClientMonitorCache.onChannel(this.channel);
        if (cameras.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.monitor.none"), x, lineY, 0x808080);
            lineY += 10;
        } else {
            for (int i = 0; i < cameras.size() && i < 6; i++) {
                TileMonitorCamera cam = cameras.get(i);
                boolean selected = camera != null && cam.getPos().equals(camera.getPos());
                String prefix = selected ? "▶ " : "  ";
                int color = !cam.isEnabled() ? 0x707070 : (selected ? 0x90FF90 : 0xC0C0C0);
                this.fontRenderer.drawStringWithShadow(prefix + trim(cam.labelOrPos(), 16) + distSuffix(cam),
                        x, lineY, color);
                lineY += 10;
            }
            if (cameras.size() > 6) {
                this.fontRenderer.drawStringWithShadow(
                        I18n.format("gui.rsaudio.monitor.more", Integer.valueOf(cameras.size() - 6)),
                        x, lineY, 0x808080);
                lineY += 10;
            }
        }

        // 画面里的实体
        lineY += 4;
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.monitor.in_frame"), x, lineY, 0xA0C0FF);
        lineY += 12;
        if (camera == null) {
            this.fontRenderer.drawStringWithShadow("—", x, lineY, 0x808080);
        } else {
            List<String> seen = CameraFeedRenderer.getSeen(camera.getPos());
            if (seen.isEmpty()) {
                this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.monitor.nobody"), x, lineY, 0x808080);
            } else {
                for (int i = 0; i < seen.size() && i < 5; i++) {
                    this.fontRenderer.drawStringWithShadow("· " + trim(seen.get(i), 18), x, lineY, 0xE0E0E0);
                    lineY += 10;
                }
            }
        }
    }

    /** 底部提示 */
    private void drawLegend() {
        this.fontRenderer.drawStringWithShadow(
                I18n.format("gui.rsaudio.monitor.legend", CameraFeedRenderer.resolutionText()),
                MARGIN, this.height - 36, 0x909090);
    }

    /** 摄像头旁边的"(x, y, z)"太占地方，列到"距我 N 格"就够用 */
    private String distSuffix(TileMonitorCamera cam) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || cam.getPos() == null) {
            return "";
        }
        double d = Math.sqrt(cam.getPos().distanceSq(this.pos));
        return " " + Math.round(d) + "m";
    }

    private String trim(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, Math.max(1, max - 1)) + "…";
    }

    @Override
    public boolean doesGuiPauseGame() {
        // 单人游戏里这个界面不该暂停 —— 暂停了画面就不动了，"实时"就没意义了
        return false;
    }
}

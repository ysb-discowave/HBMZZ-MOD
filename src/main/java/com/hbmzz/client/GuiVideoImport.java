/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client;

import com.hbmzz.video.VideoFormats;
import com.hbmzz.video.VideoImportQueue;
import com.hbmzz.video.VideoLibrary;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入窗口 —— 把一个随便什么格式的视频变成广播器能放的帧包。
 *
 * <p>左边是目录里等着导入的文件，右边两行是画质档位。这里没有"选择编码器"
 * 之类的选项，因为模组自己不解码：真正的活交给 ffmpeg，它认什么这里就能导什么。
 * 玩家要决定的只有两件事——多快（帧率）和多大（宽度），这两个直接决定产物
 * 的体积，所以放在最显眼的位置，并且用档位而不是输入框，免得有人填 1000 fps。</p>
 *
 * <p><b>转换不挂在这个窗口上。</b>点下"开始"以后活全在
 * {@link VideoImportQueue} 的守护线程里，窗口只是每帧读一次进度。所以关掉窗口
 * 照转，转完压缩包已经在视频目录里，回到广播器按 F7 就能选到。</p>
 */
public class GuiVideoImport extends GuiScreen {

    private static final int MAX_W = 360;
    private static final int H = 258;
    private static final int ROWS = 7;
    private static final int ROW_H = 12;
    private static final int MARGIN = 12;
    private static final int GAP = 6;

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int RED = 0xFFE5484D;
    private static final int MINT = 0xFF43D9A3;

    private final BlockPos pos;
    private final GuiScreen parent;

    /** 面板宽度按窗口现算，窗口窄的时候会缩，不再固定 360。 */
    private int W = MAX_W;
    private int left;
    private int top;
    /** 上一次排版时的视口尺寸，变了就重排。 */
    private int laidOutWidth = -1;
    private int laidOutHeight = -1;
    private int scroll;
    private int selected = -1;
    private int fps = VideoImportQueue.DEFAULT_FPS;
    /** 导出宽度（像素），**不是**视口宽度——同名会遮蔽 {@link GuiScreen#width}。 */
    private int videoWidth = VideoImportQueue.DEFAULT_WIDTH;
    private List<File> files = new ArrayList<>();
    /** Last thing this window said on its own (排队、失败原因…). */
    private String notice = "";
    private boolean noticeIsError;

    public GuiVideoImport(BlockPos pos, GuiScreen parent) {
        this.pos = pos;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        this.files = VideoLibrary.importable();
        clampScroll();
        layout();
    }

    /**
     * 版面按当前视口现算，永远居中。
     *
     * <p>以前这里是写死的 360 宽、按 (width-360)/2 定位。GUI 缩放调大以后可用宽度会缩到
     * 320 甚至更小，面板比屏幕还宽，left 变成负数——左边一截被切在屏幕外，看上去整块
     * 面板歪到右边去了。现在宽度取 min(360, 视口-16)，左边距也夹住，窄窗口里是"变小"，
     * 不是"歪掉"。</p>
     */
    private void layout() {
        // 这里要的是视口尺寸（GuiScreen.width/height）。以前 this.width 指向
        // 上面那个"导出宽度"字段（默认 480），于是面板按 480 居中：1280 宽的
        // 界面上它落在 x=60，左边一截还盖着全屏暗底之外——玩家看到的就是
        // "整块面板斜在一边"。
        this.laidOutWidth = this.width;
        this.laidOutHeight = this.height;
        this.W = Math.max(220, Math.min(MAX_W, this.width - 16));
        this.left = Math.max(4, (this.width - this.W) / 2);
        this.top = Math.max(4, (this.height - H) / 2);
        buildButtons();
    }

    private void buildButtons() {
        this.buttonList.clear();
        int l = this.left;
        int t = this.top;
        int column = Math.max(64, (this.W - 2 * MARGIN - 2 * GAP) / 3);
        int x0 = l + MARGIN;
        int x1 = x0 + column + GAP;
        int x2 = x1 + column + GAP;
        int wide = this.W - 2 * MARGIN;
        this.buttonList.add(new GuiButton(0, x0, t + 166, column, 18, "转换选中"));
        this.buttonList.add(new GuiButton(1, x1, t + 166, column, 18, "全部转换"));
        this.buttonList.add(new GuiButton(2, x2, t + 166, Math.max(56, wide - 2 * (column + GAP)), 18, "关闭"));
        this.buttonList.add(new GuiButton(3, x0, t + 188, column, 18, "帧率 " + this.fps));
        this.buttonList.add(new GuiButton(4, x1, t + 188, column, 18, "宽度 " + this.videoWidth));
        this.buttonList.add(new GuiButton(5, x2, t + 188, Math.max(56, wide - 2 * (column + GAP)), 18, "取消转换"));
        this.buttonList.add(new GuiButton(6, x0, t + 232, wide, 18, ffmpegButtonLabel()));
    }

    @Override
    public void updateScreen() {
        // 窗口被拉动 / 改全屏时 initGui 之外的这条路也要重排，否则按钮留在旧位置。
        if (this.width != this.laidOutWidth || this.height != this.laidOutHeight) {
            layout();
        }
        // 转换结束后文件还在目录里，但对应的 zip 已经出现，标记要跟着变。
        refreshFilesIfChanged();
        syncLabels();
    }

    private void syncLabels() {
        for (GuiButton button : this.buttonList) {
            switch (button.id) {
                case 3:
                    button.displayString = "帧率 " + this.fps;
                    break;
                case 4:
                    button.displayString = "宽度 " + this.videoWidth;
                    break;
                case 6:
                    button.displayString = ffmpegButtonLabel();
                    button.enabled = !ffmpegReady();
                    break;
                default:
                    break;
            }
        }
    }

    private void refreshFilesIfChanged() {
        List<File> latest = VideoLibrary.importable();
        if (latest.size() != this.files.size() || !latest.equals(this.files)) {
            this.files = latest;
            clampScroll();
        }
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xCC000000);

        int l = this.left;
        int t = this.top;
        drawRect(l, t, l + W, t + H, 0xF50A0C0F);
        drawRect(l, t, l + W, t + 1, 0xFF232A33);
        drawRect(l, t + H - 1, l + W, t + H, 0xFF232A33);
        drawRect(l, t, l + 1, t + H, 0xFF232A33);
        drawRect(l + W - 1, t, l + W, t + H, 0xFF232A33);
        drawRect(l + 1, t + 22, l + W - 1, t + 23, 0xFF1A2028);

        this.fontRenderer.drawStringWithShadow("导入视频", l + 12, t + 7, AMBER);
        String header = "mp4 / mkv / mov / avi / webm … → 帧包";
        this.fontRenderer.drawStringWithShadow(header,
                l + W - 12 - this.fontRenderer.getStringWidth(header), t + 8, DIM);

        drawList(mouseX, mouseY);

        this.fontRenderer.drawStringWithShadow("帧率最高 120；帧率与宽度决定体积，转一次之后随便放",
                l + 12, t + 150, 0xFF5A6674);

        drawProgress(l, t);

        String line = this.notice;
        int color = this.noticeIsError ? RED : DIM;
        VideoImportQueue.Progress live = VideoImportQueue.progress();
        if (this.notice.isEmpty() && live.failed()) {
            line = live.detail;
            color = RED;
        }
        if (!line.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(fit(line, W - 24), l + 12, t + 214, color);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawList(int mouseX, int mouseY) {
        int x = this.left + 12;
        int y = this.top + 30;
        int w = W - 24;
        int h = ROWS * ROW_H + 4;

        drawRect(x, y, x + w, y + h, 0xFF10141A);
        drawRect(x, y, x + w, y + 1, 0xFF232A33);
        drawRect(x, y + h - 1, x + w, y + h, 0xFF232A33);
        drawRect(x, y, x + 1, y + h, 0xFF232A33);
        drawRect(x + w - 1, y, x + w, y + h, 0xFF232A33);

        if (this.files.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(
                    "目录里没有待导入的视频 — 把它们丢进 " + VideoLibrary.folderPath(), x + 6, y + 6, 0xFF5A6674);
            return;
        }
        for (int row = 0; row < ROWS; row++) {
            int index = this.scroll + row;
            if (index >= this.files.size()) {
                break;
            }
            File file = this.files.get(index);
            int rowY = y + 2 + row * ROW_H;
            boolean hover = mouseX >= x + 2 && mouseX < x + w - 2
                    && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean active = index == this.selected;
            if (hover || active) {
                drawRect(x + 2, rowY, x + w - 2, rowY + ROW_H, active ? 0x33F5A524 : 0x1AFFFFFF);
            }
            this.fontRenderer.drawStringWithShadow(fit(file.getName(), w - 90), x + 6, rowY + 2,
                    active ? AMBER : 0xFFC8D2DC);

            String tag = tagFor(file);
            int tagColor = tagColorFor(file);
            this.fontRenderer.drawStringWithShadow(tag,
                    x + w - 8 - this.fontRenderer.getStringWidth(tag), rowY + 2, tagColor);
        }
        if (this.files.size() > ROWS) {
            String more = (this.scroll + ROWS) + "/" + this.files.size() + " · 滚轮翻页";
            this.fontRenderer.drawStringWithShadow(more,
                    x + w - 6 - this.fontRenderer.getStringWidth(more), y + h + 2, 0xFF4A5563);
        }
    }

    /** 每行右边那句短话：已经转过的说"已转换"，只有声音的提前说明。 */
    private String tagFor(File file) {
        if (!VideoFormats.isVideo(file)) {
            return "只有声音";
        }
        String name = VideoImportQueue.targetName(file);
        return VideoLibrary.exists(name) ? "已转换" : sizeOf(file);
    }

    private int tagColorFor(File file) {
        if (!VideoFormats.isVideo(file)) {
            return 0xFFD9A343;
        }
        return VideoLibrary.exists(VideoImportQueue.targetName(file)) ? MINT : DIM;
    }

    private String sizeOf(File file) {
        long bytes = file.length();
        if (bytes >= 1048576L) {
            return String.format("%.0f MB", bytes / 1048576.0F);
        }
        return Math.max(1L, bytes / 1024L) + " KB";
    }

    private void drawProgress(int l, int t) {
        VideoImportQueue.Progress p = VideoImportQueue.progress();
        int x = l + 12;
        int y = t + 200;
        int w = W - 24;

        drawRect(x - 1, y - 1, x + w + 1, y + 11, 0xFF000000);
        drawRect(x, y, x + w, y + 10, 0xFF2B2B2B);
        int percent = Math.max(0, Math.min(100, p.percent));
        int fill = w * percent / 100;
        if (fill > 0) {
            drawRect(x, y, x + fill, y + 10, p.failed() ? 0xFF7A2B2B : 0xFF3B8C3B);
        }
        String left = p.phase + (p.running ? " " + percent + "%" : "");
        this.fontRenderer.drawStringWithShadow(left, x + 4, y + 1, 0xFFE6EDF3);
        if (p.running && !p.detail.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(fit(p.detail, w - 80),
                    x + w - 4 - this.fontRenderer.getStringWidth(fit(p.detail, w - 80)), y + 1, DIM);
        }
    }

    private String ffmpegButtonLabel() {
        if (ffmpegReady()) {
            return "ffmpeg 就绪 · 常见容器都能导";
        }
        return "缺少 ffmpeg — 点这里下载（约 190 MB）";
    }

    /**
     * ffmpeg 在不在。
     *
     * <p>只问"本地那份下好了没有"，不调 {@code FfmpegFinder.path()}：那条路在
     * 缺 ffmpeg 时会当场开始下载，而这个方法每帧都被调用。真的没有时，按钮
     * 换成下载入口，点了才走下载页。</p>
     */
    private boolean ffmpegReady() {
        try {
            return com.rsaudio.formats.convert.BundledFfmpeg.isReady(this.mc.mcDataDir);
        } catch (Throwable t) {
            return false;
        }
    }

    private String fit(String text, int maxWidth) {
        if (this.fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsis = this.fontRenderer.getStringWidth("…");
        return this.fontRenderer.trimStringToWidth(text, Math.max(0, maxWidth - ellipsis)) + "…";
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int x = this.left + 12;
        int y = this.top + 30;
        if (mouseButton == 0 && mouseX >= x && mouseX < x + W - 24
                && mouseY >= y && mouseY < y + ROWS * ROW_H + 4) {
            int row = (mouseY - y - 2) / ROW_H;
            int index = this.scroll + row;
            if (row >= 0 && row < ROWS && index < this.files.size()) {
                this.selected = index;
                this.notice = "";
                return;
            }
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int x = this.left + 12;
        int y = this.top + 30;
        if (mouseX >= x && mouseX < x + W - 24 && mouseY >= y && mouseY < y + ROWS * ROW_H + 4) {
            this.scroll += wheel > 0 ? -1 : 1;
            clampScroll();
        }
    }

    private void clampScroll() {
        int max = Math.max(0, this.files.size() - ROWS);
        this.scroll = Math.max(0, Math.min(max, this.scroll));
        if (this.selected >= this.files.size()) {
            this.selected = this.files.size() - 1;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            back();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 0:
                convertSelected();
                break;
            case 1:
                convertAll();
                break;
            case 2:
                back();
                break;
            case 3:
                this.fps = nextStep(VideoImportQueue.FPS_STEPS, this.fps);
                break;
            case 4:
                this.videoWidth = nextStep(VideoImportQueue.WIDTH_STEPS, this.videoWidth);
                break;
            case 5:
                VideoImportQueue.cancel();
                this.notice = "已取消排队中的转换";
                this.noticeIsError = false;
                break;
            case 6:
                openDownload();
                break;
            default:
                back();
                break;
        }
    }

    private void convertSelected() {
        if (this.files.isEmpty()) {
            say("目录里没有可导入的文件", true);
            return;
        }
        if (this.selected < 0) {
            say("先点一行选中文件", true);
            return;
        }
        submit(java.util.Collections.singletonList(this.files.get(this.selected)));
    }

    private void convertAll() {
        if (this.files.isEmpty()) {
            say("目录里没有可导入的文件", true);
            return;
        }
        submit(new ArrayList<>(this.files));
    }

    private void submit(List<File> targets) {
        if (!ffmpegReady()) {
            say("还没有 ffmpeg，先点下面的下载按钮", true);
            return;
        }
        int added = VideoImportQueue.submit(targets, this.fps, this.videoWidth);
        if (added == 0) {
            say("这些文件已经在队列里了", false);
            return;
        }
        say("已排入 " + added + " 个文件 · 可以关掉窗口，转换照常继续", false);
    }

    private void say(String message, boolean error) {
        this.notice = message;
        this.noticeIsError = error;
    }

    private void openDownload() {
        this.mc.displayGuiScreen(new com.rsaudio.client.GuiFfmpegDownload(this, this.mc.mcDataDir, null));
    }

    private void back() {
        this.mc.displayGuiScreen(this.parent != null ? this.parent : new GuiVideoBroadcaster(this.pos));
    }

    private static int nextStep(int[] steps, int current) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) {
                return steps[(i + 1) % steps.length];
            }
        }
        return steps[0];
    }
}

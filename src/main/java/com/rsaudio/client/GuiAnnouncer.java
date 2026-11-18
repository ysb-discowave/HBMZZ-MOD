package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetAnnouncer;
import com.rsaudio.tile.TileAnnouncer;
import com.rsaudio.tts.TtsEngine;
import com.rsaudio.tts.TtsSpec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * 广播器界面：三个输入框 + 参数 + 预览。
 *
 * <h3>三个输入框分别是什么</h3>
 * <ol>
 *   <li><b>弹窗标题</b>：右下角弹窗最上面那一行（加粗位置）。</li>
 *   <li><b>弹窗内容</b>：弹窗正文，太长会自动折行。</li>
 *   <li><b>朗读文本</b>：交给 TTS 念出来的文字。弹窗内容会折行、语音不行，
 *       所以这两件事分成两个框 —— 想"看到的"和"听到的"不一样时直接分开写，
 *       比如标题「冷却液温度」、内容「128°C 已超上限」、朗读「警告，冷却液温度超限」。</li>
 * </ol>
 *
 * <h3>为什么有个「预览」框</h3>
 * 折行是玩家最难预判的东西（中文按字折、英文按词折，数字和标点还会影响）。
 * 与其让玩家发出去再看效果，不如在界面里画一个和真实弹窗<b>用同一套折行代码</b>
 * 的预览 —— 所以预览和实际渲染都走 {@code ClientNoticeRenderer.wrap}。
 *
 * <h3>关界面时会自动保存</h3>
 * 三个输入框不像按钮那样有明确的"应用"时机，如果只在点「广播」时才存，
 * 玩家改完文字直接关掉就会白改。所以关界面时如果检测到改动就补发一次
 * "只保存不广播"的包。
 */
public class GuiAnnouncer extends GuiScreen {

    private static final int ID_RANGE_MINUS = 0;
    private static final int ID_RANGE_PLUS = 1;
    private static final int ID_NOTICE_MINUS = 2;
    private static final int ID_NOTICE_PLUS = 3;
    private static final int ID_SPEED_MINUS = 4;
    private static final int ID_SPEED_PLUS = 5;
    private static final int ID_BROADCAST = 10;
    private static final int ID_PREVIEW = 11;
    private static final int ID_DONE = 12;

    /** 范围按钮每一步多少格 */
    private static final int RANGE_STEP = 8;
    /** 弹窗时长按钮每一步多少秒 */
    private static final int NOTICE_STEP_SECONDS = 1;
    /** 语速按钮每一步 */
    private static final double SPEED_STEP = 0.1D;

    private final BlockPos pos;

    private GuiTextField titleField;
    private GuiTextField contentField;
    private GuiTextField ttsField;

    private int range = TtsSpec.DEFAULT_RANGE;
    private int noticeTicks = TtsSpec.DEFAULT_NOTICE_TICKS;
    private double lengthScale = TtsSpec.DEFAULT_LENGTH_SCALE;

    private String status = "";
    private int statusColor = 0xAAAAAA;
    /** 有未保存的改动（关界面时补一次保存） */
    private boolean dirty;
    /** 已经从方块实体读到过设置 */
    private boolean synced;
    /** 本机引擎状态，只在开界面时探一次 */
    private String engineStatus = "";

    public GuiAnnouncer(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();

        // ★ 调整窗口大小会重新走一遍 initGui。不把玩家已经打进去的字留住的话，
        //   刚写了两行内容手一抖拉了窗口，字就没了。
        boolean resized = this.titleField != null;
        String keepTitle = resized ? this.titleField.getText() : "";
        String keepContent = resized ? this.contentField.getText() : "";
        String keepTts = resized ? this.ttsField.getText() : "";

        int panelWidth = Math.min(300, this.width - 20);
        int left = (this.width - panelWidth) / 2;
        int labelWidth = 66;
        int fieldX = left + labelWidth + 4;
        int fieldWidth = Math.max(60, panelWidth - labelWidth - 10);

        this.titleField = makeField(100, fieldX, 22, fieldWidth, TtsSpec.MAX_TITLE_CHARS);
        this.contentField = makeField(101, fieldX, 40, fieldWidth, TtsSpec.MAX_CONTENT_CHARS);
        this.ttsField = makeField(102, fieldX, 58, fieldWidth, TtsSpec.MAX_TTS_CHARS);
        this.titleField.setFocused(true);

        if (resized) {
            this.titleField.setText(keepTitle);
            this.contentField.setText(keepContent);
            this.ttsField.setText(keepTts);
        }

        // ---- 六个参数按钮，平分成一行
        int gap = 4;
        int paramWidth = Math.max(30, (panelWidth - gap * 5) / 6);
        int paramsX = left;
        int paramsY = 100;
        this.buttonList.add(new GuiButton(ID_RANGE_MINUS, paramsX, paramsY, paramWidth, 20,
                I18n.format("gui.rsaudio.announcer.range_minus")));
        this.buttonList.add(new GuiButton(ID_RANGE_PLUS, paramsX + (paramWidth + gap), paramsY, paramWidth, 20,
                I18n.format("gui.rsaudio.announcer.range_plus")));
        this.buttonList.add(new GuiButton(ID_NOTICE_MINUS, paramsX + (paramWidth + gap) * 2, paramsY, paramWidth, 20,
                I18n.format("gui.rsaudio.announcer.notice_minus")));
        this.buttonList.add(new GuiButton(ID_NOTICE_PLUS, paramsX + (paramWidth + gap) * 3, paramsY, paramWidth, 20,
                I18n.format("gui.rsaudio.announcer.notice_plus")));
        this.buttonList.add(new GuiButton(ID_SPEED_MINUS, paramsX + (paramWidth + gap) * 4, paramsY, paramWidth, 20,
                I18n.format("gui.rsaudio.announcer.speed_minus")));
        this.buttonList.add(new GuiButton(ID_SPEED_PLUS, paramsX + (paramWidth + gap) * 5, paramsY, paramWidth, 20,
                I18n.format("gui.rsaudio.announcer.speed_plus")));

        // ---- 底部三个动作按钮
        int bottomWidth = Math.max(50, (panelWidth - 12) / 3);
        int bottomY = this.height - 36;
        this.buttonList.add(new GuiButton(ID_BROADCAST, left, bottomY, bottomWidth, 20,
                I18n.format("gui.rsaudio.announcer.broadcast")));
        this.buttonList.add(new GuiButton(ID_PREVIEW, left + bottomWidth + 6, bottomY, bottomWidth, 20,
                I18n.format("gui.rsaudio.announcer.preview_local")));
        this.buttonList.add(new GuiButton(ID_DONE, left + (bottomWidth + 6) * 2, bottomY, bottomWidth, 20,
                I18n.format("gui.rsaudio.announcer.done")));

        this.engineStatus = localEngineStatus();
        if (!resized) {
            syncFromTile();
        }
    }

    private GuiTextField makeField(int id, int x, int y, int width, int maxLength) {
        GuiTextField field = new GuiTextField(id, this.fontRenderer, x, y, width, 16);
        field.setMaxStringLength(maxLength);
        // 不把字段背景画成纯黑：和原版其它输入框保持一致的深灰边框
        field.setEnableBackgroundDrawing(true);
        return field;
    }

    /** 探测本机引擎。只在开界面时跑一次 —— 每次探测都要摸硬盘 */
    private String localEngineStatus() {
        TtsEngine.Status status = TtsEngine.discover(com.rsaudio.Config.getTtsDir());
        return I18n.format("gui.rsaudio.announcer.engine",
                status.available ? I18n.format("gui.rsaudio.announcer.engine_ok")
                        : I18n.format("gui.rsaudio.announcer.engine_missing"));
    }

    /** 从（客户端侧的）方块实体读回当前设置 */
    private void syncFromTile() {
        TileAnnouncer tile = getTile();
        if (tile == null) {
            return;
        }
        this.range = tile.getRange();
        this.noticeTicks = tile.getNoticeTicks();
        this.lengthScale = tile.getLengthScale();
        this.titleField.setText(tile.getTitle());
        this.contentField.setText(tile.getContent());
        this.ttsField.setText(tile.getTtsText());
        this.synced = true;
    }

    private TileAnnouncer getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileAnnouncer ? (TileAnnouncer) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_RANGE_MINUS:
                applyRange(this.range - RANGE_STEP);
                break;
            case ID_RANGE_PLUS:
                applyRange(this.range + RANGE_STEP);
                break;
            case ID_NOTICE_MINUS:
                applyNotice(this.noticeTicks - NOTICE_STEP_SECONDS * 20);
                break;
            case ID_NOTICE_PLUS:
                applyNotice(this.noticeTicks + NOTICE_STEP_SECONDS * 20);
                break;
            case ID_SPEED_MINUS:
                applySpeed(this.lengthScale - SPEED_STEP);
                break;
            case ID_SPEED_PLUS:
                applySpeed(this.lengthScale + SPEED_STEP);
                break;
            case ID_BROADCAST:
                send(true);
                break;
            case ID_PREVIEW:
                TtsClientPlayer.previewLocal(this.ttsField.getText(), this.lengthScale);
                this.status = I18n.format("gui.rsaudio.announcer.preview_started");
                this.statusColor = 0x90FF90;
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    /** 参数改动一律先在本地生效（预览会跟着变），点「广播」或关界面时才发给服务端 */
    private void applyRange(int value) {
        this.range = TtsSpec.clampRange(value);
        this.dirty = true;
    }

    private void applyNotice(int ticks) {
        this.noticeTicks = TtsSpec.clampNoticeTicks(ticks);
        this.dirty = true;
    }

    private void applySpeed(double scale) {
        this.lengthScale = TtsSpec.clampLengthScale(scale);
        this.dirty = true;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // ESC 交给原版（关界面）；GuiTextField 会把 ESC 吞掉，只清选区不关界面
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            cycleFocus();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            send(true);
            return;
        }
        for (GuiTextField field : fields()) {
            if (field.isFocused() && field.textboxKeyTyped(typedChar, keyCode)) {
                this.dirty = true;
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    private GuiTextField[] fields() {
        return new GuiTextField[]{this.titleField, this.contentField, this.ttsField};
    }

    private void cycleFocus() {
        GuiTextField[] all = fields();
        int current = -1;
        for (int i = 0; i < all.length; i++) {
            if (all[i] != null && all[i].isFocused()) {
                current = i;
                break;
            }
        }
        int next = (current + 1) % all.length;
        for (int i = 0; i < all.length; i++) {
            all[i].setFocused(i == next);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // 先让原版处理按钮（它会调用 actionPerformed）
        super.mouseClicked(mouseX, mouseY, mouseButton);
        // GuiTextField.mouseClicked 自己会把焦点设对（点到框外就失焦，canLoseFocus 默认 true），
        // 所以这里不要再去手动 setFocused —— 手动设反而会把它算好的结果覆盖掉
        for (GuiTextField field : fields()) {
            if (field != null) {
                field.mouseClicked(mouseX, mouseY, mouseButton);
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField field : fields()) {
            if (field != null) {
                field.updateCursorCounter();
            }
        }
        if (!this.synced) {
            syncFromTile();
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // 改了但没点「广播」的，补发一次"只保存"的包，免得白改
        if (this.dirty) {
            send(false);
        }
    }

    private void send(boolean broadcast) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        String title = TtsSpec.sanitizeTitle(this.titleField.getText());
        String content = TtsSpec.sanitizeContent(this.contentField.getText());
        String tts = TtsSpec.sanitizeTts(this.ttsField.getText());
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketSetAnnouncer(
                    this.pos, title, content, tts, this.range, this.noticeTicks,
                    this.lengthScale, broadcast));
        } catch (Throwable t) {
            RsAudio.warn("广播器设置发送失败（可能已断开连接）: " + t);
            this.status = I18n.format("gui.rsaudio.announcer.send_failed");
            this.statusColor = 0xFF8080;
            return;
        }
        this.dirty = false;
        this.status = I18n.format(broadcast
                ? "gui.rsaudio.announcer.sending" : "gui.rsaudio.announcer.saved");
        this.statusColor = 0x90FF90;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int panelWidth = Math.min(300, this.width - 20);
        int left = (this.width - panelWidth) / 2;
        int labelWidth = 66;

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.announcer.title"),
                this.width / 2, 8, 0xFFFFFF);

        drawFieldRow(this.titleField, left, 22, labelWidth, "gui.rsaudio.announcer.label.title",
                "gui.rsaudio.announcer.hint.title");
        drawFieldRow(this.contentField, left, 40, labelWidth, "gui.rsaudio.announcer.label.content",
                "gui.rsaudio.announcer.hint.content");
        drawFieldRow(this.ttsField, left, 58, labelWidth, "gui.rsaudio.announcer.label.tts",
                "gui.rsaudio.announcer.hint.tts");

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.announcer.params",
                        String.valueOf(this.range),
                        String.format(Locale.ROOT, "%.1f", this.noticeTicks / 20.0D),
                        String.format(Locale.ROOT, "%.1f", this.lengthScale)),
                this.width / 2, 80, 0xFFFF80);
        drawCenteredString(this.fontRenderer, this.engineStatus, this.width / 2, 90, 0xAAAAAA);

        drawPreview(left, panelWidth);

        if (!this.status.isEmpty()) {
            drawCenteredString(this.fontRenderer, this.status, this.width / 2, this.height - 14,
                    this.statusColor);
        } else {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.announcer.tips"),
                    this.width / 2, this.height - 14, 0xAAAAAA);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawFieldRow(GuiTextField field, int left, int y, int labelWidth,
                              String labelKey, String hintKey) {
        if (field == null) {
            return;
        }
        this.fontRenderer.drawStringWithShadow(I18n.format(labelKey), left, y + 4, 0xE0E0E0);
        field.drawTextBox();
        // 空框时在里面画一行灰色提示 —— 1.12.2 的 GuiTextField 没有 placeholder
        if (field.getText().isEmpty() && !field.isFocused()) {
            this.fontRenderer.drawString(I18n.format(hintKey), field.x + 4, field.y + 4, 0x707070);
        }
    }

    /**
     * 右下角弹窗的实时预览。
     * <p>
     * 用和真实渲染<b>同一份折行代码</b>，只是把面板挪到界面中间、固定宽度。
     * 预览不参与淡出（alpha 恒为 1）。
     */
    private void drawPreview(int left, int panelWidth) {
        int top = 138;
        int bottom = this.height - 44;
        int labelY = 126;

        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.announcer.preview_label"),
                left, labelY, 0xE0E0E0);
        this.fontRenderer.drawString(I18n.format("gui.rsaudio.announcer.preview_hint"),
                left + 40, labelY, 0x808080);

        if (bottom - top < 18) {
            // 窗口太矮，画不下预览就别硬画了（会糊成一团）
            return;
        }

        int right = left + panelWidth;
        drawRect(left, top, right, bottom, 0xC0000000);
        drawRect(left, top, left + 3, bottom, 0xFF3AA0FF);
        drawRect(left, top, right, top + 1, 0x40FFFFFF);
        drawRect(left, bottom - 1, right, bottom, 0x40FFFFFF);

        int textX = left + 3 + 4;
        int textWidth = Math.max(20, right - textX - 6);
        int lineHeight = this.fontRenderer.FONT_HEIGHT + 1;
        int room = Math.max(1, (bottom - top - 6) / lineHeight);

        List<String> titleLines = ClientNoticeRenderer.wrap(this.fontRenderer,
                TtsSpec.sanitizeTitle(this.titleField.getText()), textWidth, Math.min(2, room));
        int bodyRoom = Math.max(0, room - titleLines.size()
                - (titleLines.isEmpty() ? 0 : 1));
        List<String> bodyLines = ClientNoticeRenderer.wrap(this.fontRenderer,
                TtsSpec.sanitizeContent(this.contentField.getText()), textWidth, bodyRoom);

        int cursorY = top + 3;
        for (String line : titleLines) {
            this.fontRenderer.drawStringWithShadow(line, textX, cursorY, 0xFFFFFF);
            cursorY += lineHeight;
        }
        if (!titleLines.isEmpty() && !bodyLines.isEmpty()) {
            drawRect(textX, cursorY + 1, right - 4, cursorY + 2, 0x50FFFFFF);
            cursorY += 4;
        }
        for (String line : bodyLines) {
            this.fontRenderer.drawStringWithShadow(line, textX, cursorY, 0xDDDDDD);
            cursorY += lineHeight;
        }
        if (titleLines.isEmpty() && bodyLines.isEmpty()) {
            this.fontRenderer.drawString(I18n.format("gui.rsaudio.announcer.preview_empty"),
                    textX, top + 3, 0x808080);
        }
    }
}

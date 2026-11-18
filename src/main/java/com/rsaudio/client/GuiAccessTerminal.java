package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.access.TerminalCore;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetTerminal;
import com.rsaudio.tile.TileAccessTerminal;
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
import java.util.Locale;

/**
 * FAAS 门禁终端界面：<b>右键终端</b>打开。
 *
 * <h3>界面分三段</h3>
 * <ol>
 *   <li><b>区域状态</b>（最上面那行）：封锁中 / 放行，以及"已锁定 3 / 4 扇门"。
 *       这个分数是<b>服务端扫出来的</b>，不是界面自己数的 —— 界面看不到"哪扇门建好了没有"。</li>
 *   <li><b>播报文案</b>：三个输入框 —— 标题、封锁播报、解除播报。
 *       封锁和解除分开写，因为这两句话的语气完全不同（"封锁启动" vs "警报解除"），
 *       合成一句的话怎么措辞都别扭。</li>
 *   <li><b>参数与操作</b>：频道、红石反向、播报开关、广播范围、弹窗时长；
 *       底下是「封锁全部 / 解除封锁 / 立即播报 / 完成」。</li>
 * </ol>
 *
 * <h3>★ 状态只走一条路</h3>
 * 「封锁全部」和「解除封锁」改的其实是同一个 {@code locked} 字段，
 * 然后整体发给服务端 —— 服务端自己判断"真的翻转了吗"，翻转了才校准门 + 播报。
 * 所以连点两次「封锁全部」不会让门来回抖，也不会响两次喇叭。
 *
 * <h3>改动会自动保存</h3>
 * 输入框不像按钮那样有明确的"应用"时机，所以：改参数/文案 -> 立刻发一次 APPLY；
 * 关界面时如果还有没发出去的改动，再补发一次（和 {@code GuiAnnouncer} 一个约定）。
 */
public class GuiAccessTerminal extends GuiScreen {

    private static final int ID_CH_MINUS = 0;
    private static final int ID_CH_PLUS = 1;
    private static final int ID_INVERT = 2;
    private static final int ID_ANNOUNCE = 3;
    private static final int ID_RANGE_MINUS = 4;
    private static final int ID_RANGE_PLUS = 5;
    private static final int ID_NOTICE_MINUS = 6;
    private static final int ID_NOTICE_PLUS = 7;
    private static final int ID_LOCK_ALL = 10;
    private static final int ID_RELEASE_ALL = 11;
    private static final int ID_BROADCAST_NOW = 12;
    private static final int ID_DONE = 13;

    /** 范围按钮每一步多少格 */
    private static final int RANGE_STEP = 8;
    /** 弹窗时长按钮每一步多少刻（20 刻 = 1 秒） */
    private static final int NOTICE_STEP_TICKS = 20;

    private final BlockPos pos;

    private GuiTextField titleField;
    private GuiTextField lockField;
    private GuiTextField releaseField;

    private int channel = TerminalCore.CHANNEL_DEFAULT;
    private boolean locked;
    private boolean invert;
    private boolean announce = true;
    private int range = TtsSpec.DEFAULT_RANGE;
    private int noticeTicks = TtsSpec.DEFAULT_NOTICE_TICKS;

    private int doorCount;
    private int lockedCount;

    private String status = "";
    private int statusColor = 0xAAAAAA;
    /** 有没发出去的改动（关界面时补一次保存） */
    private boolean dirty;
    private boolean synced;

    // 排版（initGui 里算好，drawScreen 也要用）
    private int fieldLeft;
    private int fieldWidth;
    private int fieldTop;
    private int paramRow1Y;
    private int paramRow2Y;
    private int bottomRowY;

    public GuiAccessTerminal(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();

        // ★ 调整窗口大小会重新走一遍 initGui，把玩家已经打进去的字留住
        boolean resized = this.titleField != null;
        String keepTitle = resized ? this.titleField.getText() : "";
        String keepLock = resized ? this.lockField.getText() : "";
        String keepRelease = resized ? this.releaseField.getText() : "";

        int panelWidth = Math.min(320, this.width - 20);
        this.fieldLeft = (this.width - panelWidth) / 2;
        int labelWidth = 62;
        int textX = this.fieldLeft + labelWidth + 4;
        this.fieldWidth = Math.max(60, panelWidth - labelWidth - 10);

        this.fieldTop = 62;
        this.titleField = makeField(100, textX, this.fieldTop, this.fieldWidth, TtsSpec.MAX_TITLE_CHARS);
        this.lockField = makeField(101, textX, this.fieldTop + 20, this.fieldWidth, TtsSpec.MAX_TTS_CHARS);
        this.releaseField = makeField(102, textX, this.fieldTop + 40, this.fieldWidth, TtsSpec.MAX_TTS_CHARS);
        this.titleField.setFocused(true);

        if (resized) {
            this.titleField.setText(keepTitle);
            this.lockField.setText(keepLock);
            this.releaseField.setText(keepRelease);
        }

        // ---- 行位置：从底下往上排，窗口太矮就压缩行距
        this.bottomRowY = this.height - 30;
        int rowGap = 22;
        this.paramRow2Y = this.bottomRowY - rowGap;
        this.paramRow1Y = this.bottomRowY - rowGap * 2;
        int floor = this.fieldTop + 62;      // 第三个输入框底部再留一点
        if (this.paramRow1Y < floor) {
            rowGap = Math.max(16, (this.bottomRowY - floor) / 2);
            this.paramRow2Y = this.bottomRowY - rowGap;
            this.paramRow1Y = this.bottomRowY - rowGap * 2;
        }

        int gap = 4;
        // ---- 参数第一行：频道 ◀ ▶ / 红石反向 / 播报开关
        int w1 = Math.min(110, Math.max(30, (panelWidth - gap * 3) / 4));
        int x1 = this.width / 2 - (w1 * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_CH_MINUS, x1, this.paramRow1Y, w1, 20,
                I18n.format("gui.rsaudio.access.channel_minus")));
        this.buttonList.add(new GuiButton(ID_CH_PLUS, x1 + (w1 + gap), this.paramRow1Y, w1, 20,
                I18n.format("gui.rsaudio.access.channel_plus")));
        this.buttonList.add(new GuiButton(ID_INVERT, x1 + (w1 + gap) * 2, this.paramRow1Y, w1, 20,
                invertLabel()));
        this.buttonList.add(new GuiButton(ID_ANNOUNCE, x1 + (w1 + gap) * 3, this.paramRow1Y, w1, 20,
                announceLabel()));

        // ---- 参数第二行：广播范围 / 弹窗时长
        int w2 = Math.min(110, Math.max(30, (panelWidth - gap * 3) / 4));
        int x2 = this.width / 2 - (w2 * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_RANGE_MINUS, x2, this.paramRow2Y, w2, 20,
                I18n.format("gui.rsaudio.access.range_minus")));
        this.buttonList.add(new GuiButton(ID_RANGE_PLUS, x2 + (w2 + gap), this.paramRow2Y, w2, 20,
                I18n.format("gui.rsaudio.access.range_plus")));
        this.buttonList.add(new GuiButton(ID_NOTICE_MINUS, x2 + (w2 + gap) * 2, this.paramRow2Y, w2, 20,
                I18n.format("gui.rsaudio.access.notice_minus")));
        this.buttonList.add(new GuiButton(ID_NOTICE_PLUS, x2 + (w2 + gap) * 3, this.paramRow2Y, w2, 20,
                I18n.format("gui.rsaudio.access.notice_plus")));

        // ---- 底部动作行
        int w3 = Math.max(52, (panelWidth - 18) / 4);
        int x3 = this.width / 2 - (w3 * 4 + 6 * 3) / 2;
        this.buttonList.add(new GuiButton(ID_LOCK_ALL, x3, this.bottomRowY, w3, 20,
                I18n.format("gui.rsaudio.access.lock_all")));
        this.buttonList.add(new GuiButton(ID_RELEASE_ALL, x3 + (w3 + 6), this.bottomRowY, w3, 20,
                I18n.format("gui.rsaudio.access.release_all")));
        this.buttonList.add(new GuiButton(ID_BROADCAST_NOW, x3 + (w3 + 6) * 2, this.bottomRowY, w3, 20,
                I18n.format("gui.rsaudio.access.broadcast_now")));
        this.buttonList.add(new GuiButton(ID_DONE, x3 + (w3 + 6) * 3, this.bottomRowY, w3, 20,
                I18n.format("gui.rsaudio.access.done")));

        if (!resized) {
            syncFromTile(true);
        }
    }

    private GuiTextField makeField(int id, int x, int y, int width, int maxLength) {
        GuiTextField field = new GuiTextField(id, this.fontRenderer, x, y, width, 16);
        field.setMaxStringLength(maxLength);
        field.setEnableBackgroundDrawing(true);
        return field;
    }

    /**
     * 从（客户端侧的）方块实体读回当前设置。
     *
     * @param includeText 是否连三个输入框的文字一起覆盖。
     *                    ★ 玩家正在打字时（{@code dirty}）必须传 false —— 服务端每次同步
     *                    都会把输入框刷成它那边的值，正在打的字会被吃掉一半，
     *                    表现为"打字打着打着光标跳走、字没了"
     */
    private void syncFromTile(boolean includeText) {
        TileAccessTerminal tile = getTile();
        if (tile == null) {
            return;
        }
        this.channel = tile.getChannel();
        this.locked = tile.isLocked();
        this.invert = tile.isInvert();
        this.announce = tile.isAnnounce();
        this.range = tile.getRange();
        this.noticeTicks = tile.getNoticeTicks();
        this.doorCount = tile.getDoorCount();
        this.lockedCount = tile.getLockedCount();
        if (includeText && this.titleField != null) {
            this.titleField.setText(tile.getAnnounceTitle());
            this.lockField.setText(tile.getLockText());
            this.releaseField.setText(tile.getReleaseText());
        }
        this.synced = true;
    }

    private TileAccessTerminal getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileAccessTerminal ? (TileAccessTerminal) te : null;
    }

    // ------------------------------------------------------------------ 按钮文字

    private String invertLabel() {
        return I18n.format(invert
                ? "gui.rsaudio.access.invert_on" : "gui.rsaudio.access.invert_off");
    }

    private String announceLabel() {
        return I18n.format(announce
                ? "gui.rsaudio.access.announce_on" : "gui.rsaudio.access.announce_off");
    }

    private void refreshLabels() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_INVERT) {
                button.displayString = invertLabel();
            } else if (button.id == ID_ANNOUNCE) {
                button.displayString = announceLabel();
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_CH_MINUS:
                this.channel = TerminalCore.stepChannel(this.channel, -1);
                this.dirty = true;
                apply();
                break;
            case ID_CH_PLUS:
                this.channel = TerminalCore.stepChannel(this.channel, +1);
                this.dirty = true;
                apply();
                break;
            case ID_INVERT:
                this.invert = !this.invert;
                this.dirty = true;
                apply();
                break;
            case ID_ANNOUNCE:
                this.announce = !this.announce;
                this.dirty = true;
                apply();
                break;
            case ID_RANGE_MINUS:
                this.range = TtsSpec.clampRange(this.range - RANGE_STEP);
                this.dirty = true;
                apply();
                break;
            case ID_RANGE_PLUS:
                this.range = TtsSpec.clampRange(this.range + RANGE_STEP);
                this.dirty = true;
                apply();
                break;
            case ID_NOTICE_MINUS:
                this.noticeTicks = TtsSpec.clampNoticeTicks(this.noticeTicks - NOTICE_STEP_TICKS);
                this.dirty = true;
                apply();
                break;
            case ID_NOTICE_PLUS:
                this.noticeTicks = TtsSpec.clampNoticeTicks(this.noticeTicks + NOTICE_STEP_TICKS);
                this.dirty = true;
                apply();
                break;
            case ID_LOCK_ALL:
                this.locked = true;
                this.dirty = true;
                apply();
                break;
            case ID_RELEASE_ALL:
                this.locked = false;
                this.dirty = true;
                apply();
                break;
            case ID_BROADCAST_NOW:
                send(PacketSetTerminal.BROADCAST_NOW);
                this.dirty = false;
                this.status = I18n.format("gui.rsaudio.access.broadcasting");
                this.statusColor = 0x90FF90;
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
        refreshLabels();
    }

    private void apply() {
        send(PacketSetTerminal.APPLY);
        this.dirty = false;
        this.status = I18n.format(locked
                ? "gui.rsaudio.access.applied_locked" : "gui.rsaudio.access.applied_released");
        this.statusColor = locked ? 0xFF9090 : 0x90FF90;
    }

    private void send(byte action) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        String title = this.titleField == null ? "" : TtsSpec.sanitizeTitle(this.titleField.getText());
        String lockText = this.lockField == null ? "" : TtsSpec.sanitizeTts(this.lockField.getText());
        String releaseText = this.releaseField == null ? "" : TtsSpec.sanitizeTts(this.releaseField.getText());
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketSetTerminal(this.pos, action,
                    this.channel, this.locked, this.invert, this.announce,
                    title, lockText, releaseText, this.range, this.noticeTicks));
        } catch (Throwable t) {
            RsAudio.warn("门禁终端设置发送失败（可能已断开连接）: " + t);
            this.status = I18n.format("gui.rsaudio.access.send_failed");
            this.statusColor = 0xFF8080;
        }
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
            // 回车 = 存下文案并立刻播报一次，方便调措辞
            send(PacketSetTerminal.BROADCAST_NOW);
            this.dirty = false;
            this.status = I18n.format("gui.rsaudio.access.broadcasting");
            this.statusColor = 0x90FF90;
            return;
        }
        for (GuiTextField field : fields()) {
            if (field != null && field.isFocused() && field.textboxKeyTyped(typedChar, keyCode)) {
                this.dirty = true;
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    private GuiTextField[] fields() {
        return new GuiTextField[] { this.titleField, this.lockField, this.releaseField };
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
        // ★ 不要在这里手动 setFocused：GuiTextField.mouseClicked 自己会算好
        //   （点到框外就失焦），手动设反而会把它的结果覆盖掉
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
            syncFromTile(true);
            return;
        }
        // 服务端那边状态变了（别人在别处按了终端、红石把区域锁上了）就跟着更新显示
        TileAccessTerminal tile = getTile();
        if (tile == null) {
            return;
        }
        if (tile.isLocked() != this.locked || tile.getChannel() != this.channel
                || tile.isInvert() != this.invert || tile.isAnnounce() != this.announce
                || tile.getRange() != this.range || tile.getNoticeTicks() != this.noticeTicks
                || tile.getDoorCount() != this.doorCount
                || tile.getLockedCount() != this.lockedCount) {
            // ★ 玩家正在打字（dirty）时不覆盖输入框 —— 否则服务端每次同步都会
            //   把正在打的字刷掉，表现为"打字打着打着光标跳走、字没了"
            syncFromTile(!this.dirty);
        }
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        // 改了但没发出去的，补一次保存，免得白改
        if (this.dirty) {
            send(PacketSetTerminal.APPLY);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.access.title"), cx, 8, 0xFFFFFF);

        // ---- 区域状态（最主要的一行）
        String zone = locked
                ? I18n.format("gui.rsaudio.access.zone_locked", lockedCount, doorCount)
                : I18n.format("gui.rsaudio.access.zone_released", lockedCount, doorCount);
        drawCenteredString(this.fontRenderer, zone, cx, 22, locked ? 0xFF6060 : 0x90FF90);

        // ---- 参数行
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.access.params", channel,
                        I18n.format(invert ? "gui.rsaudio.access.invert_on_short"
                                : "gui.rsaudio.access.invert_off_short"),
                        String.valueOf(range),
                        String.format(Locale.ROOT, "%.1f", this.noticeTicks / 20.0D)),
                cx, 34, 0xC0C0FF);

        // ---- 三个输入框
        drawFieldRow(this.titleField, "gui.rsaudio.access.label.title",
                "gui.rsaudio.access.hint.title");
        drawFieldRow(this.lockField, "gui.rsaudio.access.label.lock",
                "gui.rsaudio.access.hint.lock");
        drawFieldRow(this.releaseField, "gui.rsaudio.access.label.release",
                "gui.rsaudio.access.hint.release");

        // ---- 状态 / 提示
        String line;
        int color;
        if (!this.status.isEmpty()) {
            line = this.status;
            color = this.statusColor;
        } else {
            TileAccessTerminal tile = getTile();
            String tileStatus = tile == null ? "" : tile.getStatus();
            if (tileStatus != null && !tileStatus.isEmpty()) {
                line = tileStatus;
                color = 0xFFFFFF;
            } else {
                line = I18n.format("gui.rsaudio.access.hint");
                color = 0xAAAAAA;
            }
        }
        // 最高只画到参数行上面，别把按钮盖住
        int limitY = this.paramRow1Y - 4;
        int lineY = this.fieldTop + 62;
        for (String piece : this.fontRenderer.listFormattedStringToWidth(line, this.width - 30)) {
            if (lineY + 9 > limitY) {
                break;
            }
            drawCenteredString(this.fontRenderer, piece, cx, lineY, color);
            lineY += 10;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawFieldRow(GuiTextField field, String labelKey, String hintKey) {
        if (field == null) {
            return;
        }
        this.fontRenderer.drawStringWithShadow(I18n.format(labelKey), this.fieldLeft, field.y + 4, 0xE0E0E0);
        field.drawTextBox();
        // 空框时在里面画一行灰色提示 —— 1.12.2 的 GuiTextField 没有 placeholder
        if (field.getText().isEmpty() && !field.isFocused()) {
            this.fontRenderer.drawString(I18n.format(hintKey), field.x + 4, field.y + 4, 0x707070);
        }
    }
}

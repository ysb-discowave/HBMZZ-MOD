package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockRedstoneDelayer;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetBroadcast;
import com.rsaudio.network.PacketSetDelay;
import com.rsaudio.tile.TileRedstoneDelayer;
import com.rsaudio.util.Broadcaster;
import com.rsaudio.util.DelayText;
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

/**
 * 红石延迟器的延迟输入界面。
 * <p>
 * 直接在输入框里写时间就行，不用一档一档地点：
 * <ul>
 *     <li>{@code 2} / {@code 1.5} —— 纯数字按秒算</li>
 *     <li>{@code 500ms} —— 毫秒</li>
 *     <li>{@code 10t} / {@code 10刻} —— 刻</li>
 *     <li>{@code 1m} / {@code 2分} —— 分</li>
 * </ul>
 * 回车 = 应用；下面的快捷按钮按"当前值"加减，改完立刻生效。
 */
public class GuiRedstoneDelayer extends GuiScreen {

    private static final int ID_APPLY = 0;
    private static final int ID_MINUS_TICK = 1;
    private static final int ID_PLUS_TICK = 2;
    private static final int ID_MINUS_SECOND = 3;
    private static final int ID_PLUS_SECOND = 4;
    private static final int ID_DONE = 5;
    private static final int ID_RESET = 6;
    private static final int ID_BROADCAST_COLOR = 7;

    private static final int FIELD_WIDTH = 120;
    private static final int FIELD_HEIGHT = 18;

    /** 播报行：标签 + 输入框 +（点一下换色的）颜色按钮 */
    private static final int BROADCAST_ROW_Y = 124;
    private static final int BROADCAST_LABEL_WIDTH = 44;
    private static final int BROADCAST_FIELD_WIDTH = 180;
    private static final int BROADCAST_COLOR_WIDTH = 90;

    private final BlockPos pos;
    private GuiTextField input;
    private int currentTicks = BlockRedstoneDelayer.DEFAULT_DELAY_TICKS;
    private String status = "";
    private int statusColor = 0xFFFFFF;

    /** 输出时播报到聊天栏的文字与颜色 */
    private String broadcastText = "";
    private int broadcastColor = Broadcaster.DEFAULT_COLOR;
    private GuiTextField broadcastInput;

    public GuiRedstoneDelayer(BlockPos pos) {
        this.pos = pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.currentTicks = readCurrentDelay();

        int cx = this.width / 2;
        // 窗口大小变化会重进 initGui，保留玩家已经敲进去的内容
        String previous = this.input == null ? DelayText.toInputText(currentTicks) : this.input.getText();

        this.input = new GuiTextField(100, this.fontRenderer, cx - FIELD_WIDTH / 2, 56, FIELD_WIDTH, FIELD_HEIGHT);
        this.input.setMaxStringLength(16);
        this.input.setText(previous);
        this.input.setFocused(true);

        // 播报设置（从方块实体里读当前值）
        TileRedstoneDelayer tile = getTile();
        this.broadcastText = tile == null ? "" : tile.getBroadcastText();
        this.broadcastColor = tile == null ? Broadcaster.DEFAULT_COLOR : tile.getBroadcastColor();

        int bcLeft = cx - (BROADCAST_LABEL_WIDTH + BROADCAST_FIELD_WIDTH + 4 + BROADCAST_COLOR_WIDTH) / 2;
        String previousBroadcast = this.broadcastInput == null
                ? this.broadcastText : this.broadcastInput.getText();
        this.broadcastInput = new GuiTextField(101, this.fontRenderer,
                bcLeft + BROADCAST_LABEL_WIDTH, BROADCAST_ROW_Y + 1, BROADCAST_FIELD_WIDTH, 16);
        this.broadcastInput.setMaxStringLength(Broadcaster.MAX_LENGTH);
        this.broadcastInput.setText(previousBroadcast);
        this.broadcastInput.setFocused(false);
        this.buttonList.add(new GuiButton(ID_BROADCAST_COLOR,
                bcLeft + BROADCAST_LABEL_WIDTH + BROADCAST_FIELD_WIDTH + 4, BROADCAST_ROW_Y,
                BROADCAST_COLOR_WIDTH, 18, colorLabel()));

        // 快捷加减
        int presetY = this.height - 96;
        int pw = 68;
        int gap = 6;
        int px = cx - (pw * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_MINUS_TICK, px, presetY, pw, 20,
                I18n.format("gui.rsaudio.delayer.minus_tick")));
        this.buttonList.add(new GuiButton(ID_PLUS_TICK, px + (pw + gap), presetY, pw, 20,
                I18n.format("gui.rsaudio.delayer.plus_tick")));
        this.buttonList.add(new GuiButton(ID_MINUS_SECOND, px + (pw + gap) * 2, presetY, pw, 20,
                I18n.format("gui.rsaudio.delayer.minus_second")));
        this.buttonList.add(new GuiButton(ID_PLUS_SECOND, px + (pw + gap) * 3, presetY, pw, 20,
                I18n.format("gui.rsaudio.delayer.plus_second")));

        // 应用 / 恢复默认 / 完成
        int actionY = this.height - 70;
        int aw = 90;
        int agap = 8;
        int ax = cx - (aw * 3 + agap * 2) / 2;
        this.buttonList.add(new GuiButton(ID_APPLY, ax, actionY, aw, 20,
                I18n.format("gui.rsaudio.delayer.apply")));
        this.buttonList.add(new GuiButton(ID_RESET, ax + aw + agap, actionY, aw, 20,
                I18n.format("gui.rsaudio.delayer.reset")));
        this.buttonList.add(new GuiButton(ID_DONE, ax + (aw + agap) * 2, actionY, aw, 20,
                I18n.format("gui.rsaudio.delayer.done")));
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_APPLY:
                apply(parseInput());
                break;
            case ID_RESET:
                apply(BlockRedstoneDelayer.DEFAULT_DELAY_TICKS);
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            case ID_MINUS_TICK:
                apply(this.currentTicks - 1);
                break;
            case ID_PLUS_TICK:
                apply(this.currentTicks + 1);
                break;
            case ID_MINUS_SECOND:
                apply(this.currentTicks - DelayText.TICKS_PER_SECOND);
                break;
            case ID_PLUS_SECOND:
                apply(this.currentTicks + DelayText.TICKS_PER_SECOND);
                break;
            case ID_BROADCAST_COLOR:
                this.broadcastColor = Broadcaster.nextColor(this.broadcastColor);
                sendBroadcast();
                this.status = I18n.format("gui.rsaudio.broadcast.saved",
                        I18n.format(colorKey(this.broadcastColor)));
                this.statusColor = 0x90FF90;
                refreshColorButton();
                break;
            default:
                break;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        boolean inDelay = this.input != null && this.input.mouseClicked(mouseX, mouseY, mouseButton);
        boolean inBroadcast = this.broadcastInput != null
                && this.broadcastInput.mouseClicked(mouseX, mouseY, mouseButton);
        if (inDelay || inBroadcast) {
            // 两个输入框互斥，键盘输入只会进被点中的那一个
            this.input.setFocused(inDelay);
            this.broadcastInput.setFocused(inBroadcast);
            return;
        }
        // 点到别处（按钮/空白）时焦点留在延迟输入框上，和以前的手感一致
        this.broadcastInput.setFocused(false);
        this.input.setFocused(true);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // ESC 交给原版（关界面），GuiTextField 会把 ESC 吞掉只清选区
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        boolean delayFocused = this.input != null && this.input.isFocused();
        boolean broadcastFocused = this.broadcastInput != null && this.broadcastInput.isFocused();

        // Tab 在两个输入框之间切换
        if (keyCode == Keyboard.KEY_TAB && (delayFocused || broadcastFocused)) {
            if (delayFocused) {
                this.input.setFocused(false);
                this.broadcastInput.setFocused(true);
            } else {
                sendBroadcast();
                this.broadcastInput.setFocused(false);
                this.input.setFocused(true);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (broadcastFocused) {
                sendBroadcast();
                this.status = I18n.format("gui.rsaudio.broadcast.saved",
                        I18n.format(colorKey(this.broadcastColor)));
                this.statusColor = 0x90FF90;
            } else {
                apply(parseInput());
            }
            return;
        }
        if (delayFocused && this.input.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (broadcastFocused && this.broadcastInput.textboxKeyTyped(typedChar, keyCode)) {
            this.broadcastText = Broadcaster.sanitize(this.broadcastInput.getText());
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.input != null) {
            this.input.updateCursorCounter();
        }
        if (this.broadcastInput != null) {
            this.broadcastInput.updateCursorCounter();
        }
    }

    private int parseInput() {
        return DelayText.parseTicks(this.input.getText());
    }

    /** ticks <= 0 表示解析失败 */
    private void apply(int ticks) {
        if (ticks <= 0) {
            this.status = I18n.format("gui.rsaudio.delayer.invalid");
            this.statusColor = 0xFF8080;
            return;
        }
        int value = BlockRedstoneDelayer.clampDelay(ticks);
        PacketHandler.INSTANCE.sendToServer(new PacketSetDelay(this.pos, value));
        this.currentTicks = value;
        this.input.setText(DelayText.toInputText(value));
        this.status = I18n.format("gui.rsaudio.delayer.applied",
                String.valueOf(value), DelayText.formatDuration(value));
        this.statusColor = 0x90FF90;
    }

    private int readCurrentDelay() {
        TileRedstoneDelayer tile = getTile();
        return tile == null ? BlockRedstoneDelayer.DEFAULT_DELAY_TICKS : tile.getDelayTicks();
    }

    private TileRedstoneDelayer getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileRedstoneDelayer ? (TileRedstoneDelayer) te : null;
    }

    // ------------------------------------------------------------------ 聊天栏播报

    private void sendBroadcast() {
        if (this.broadcastInput != null) {
            this.broadcastText = Broadcaster.sanitize(this.broadcastInput.getText());
        }
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(
                    new PacketSetBroadcast(this.pos, this.broadcastText, this.broadcastColor));
        } catch (Throwable t) {
            // 断开连接时 onGuiClosed 也会被调用，发包可能失败；别让它崩客户端
            RsAudio.warn("保存广播设置失败（可能已断开连接）: " + t);
        }
    }

    private String colorLabel() {
        return I18n.format(colorKey(this.broadcastColor));
    }

    private static String colorKey(int ordinal) {
        int value = ordinal < 0 || ordinal >= Broadcaster.COLOR_COUNT ? Broadcaster.DEFAULT_COLOR : ordinal;
        return "gui.rsaudio.color." + value;
    }

    private void refreshColorButton() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_BROADCAST_COLOR) {
                button.displayString = colorLabel();
                return;
            }
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.delayer.title"), cx, 12, 0xFFFFFF);
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.delayer.current",
                        String.valueOf(this.currentTicks), DelayText.formatDuration(this.currentTicks)),
                cx, 28, 0xFFFF80);

        // 标签 + 输入框
        String label = I18n.format("gui.rsaudio.delayer.label");
        int fieldX = cx - FIELD_WIDTH / 2;
        this.fontRenderer.drawStringWithShadow(label,
                fieldX - 8 - this.fontRenderer.getStringWidth(label), 61, 0xE0E0E0);
        this.input.drawTextBox();

        // 实时预览
        int preview = parseInput();
        String previewText;
        int previewColor;
        if (preview <= 0) {
            previewText = I18n.format("gui.rsaudio.delayer.invalid");
            previewColor = 0xFF8080;
        } else {
            int clamped = BlockRedstoneDelayer.clampDelay(preview);
            previewText = I18n.format("gui.rsaudio.delayer.preview",
                    String.valueOf(clamped), DelayText.formatDuration(clamped));
            previewColor = 0xA0FFA0;
        }
        drawCenteredString(this.fontRenderer, previewText, cx, 84, previewColor);

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.delayer.range",
                        String.valueOf(BlockRedstoneDelayer.MIN_DELAY_TICKS),
                        String.valueOf(BlockRedstoneDelayer.MAX_DELAY_TICKS),
                        DelayText.formatSeconds(BlockRedstoneDelayer.MIN_DELAY_TICKS),
                        DelayText.formatSeconds(BlockRedstoneDelayer.MAX_DELAY_TICKS)),
                cx, 104, 0xAAAAAA);

        // 播报行：标签 + 输入框（颜色按钮由 super 一起画）
        int bcLeft = cx - (BROADCAST_LABEL_WIDTH + BROADCAST_FIELD_WIDTH + 4 + BROADCAST_COLOR_WIDTH) / 2;
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.broadcast.label"),
                bcLeft, BROADCAST_ROW_Y + 5, 0xE0E0E0);
        this.broadcastInput.drawTextBox();
        if (this.broadcastInput.getText().isEmpty() && !this.broadcastInput.isFocused()) {
            this.fontRenderer.drawString(I18n.format("gui.rsaudio.broadcast.hint"),
                    bcLeft + BROADCAST_LABEL_WIDTH + 4, BROADCAST_ROW_Y + 5, 0x808080);
        }

        if (!this.status.isEmpty()) {
            drawCenteredString(this.fontRenderer, this.status, cx, this.height - 42, this.statusColor);
        } else {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.delayer.hint"),
                    cx, this.height - 42, 0xAAAAAA);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        // 色块画在按钮之后，否则会被盖住
        for (GuiButton button : this.buttonList) {
            if (button.id != ID_BROADCAST_COLOR) {
                continue;
            }
            int size = 10;
            int swatchX = button.x + button.width - size - 4;
            int swatchY = button.y + (button.height - size) / 2;
            drawRect(swatchX - 1, swatchY - 1, swatchX + size + 1, swatchY + size + 1, 0xFF000000);
            int ordinal = this.broadcastColor < 0 || this.broadcastColor >= Broadcaster.COLOR_COUNT
                    ? Broadcaster.DEFAULT_COLOR : this.broadcastColor;
            drawRect(swatchX, swatchY, swatchX + size, swatchY + size,
                    0xFF000000 | Broadcaster.COLORS[ordinal]);
            break;
        }
    }

    @Override
    public void onGuiClosed() {
        // 关界面时把播报文字落盘，免得敲完直接按 ESC 丢设置
        sendBroadcast();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

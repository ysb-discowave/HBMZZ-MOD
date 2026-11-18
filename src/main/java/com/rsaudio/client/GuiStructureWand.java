package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketWandAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 结构魔杖界面：手持木斧 <b>潜行 + 右键</b> 打开。
 *
 * <h3>这个界面为什么是"纯客户端"的</h3>
 * 选区（两个角）存在服务端内存里，存文件更是只能服务端做。
 * 所以界面上的每个按钮都是往服务端发包，再把服务端算出来的选区状态显示出来 ——
 * 客户端自己算一份"我以为选了多少格"没意义，联机时还会和服务端不一致。
 *
 * <h3>选区怎么来的</h3>
 * <ul>
 *     <li>左键点方块 = 第一个角；右键点方块 = 第二个角（都在世界里做，不用开界面）。</li>
 *     <li>这个界面只负责"看一下选了多少格 + 起个名字存下来"。</li>
 * </ul>
 */
public class GuiStructureWand extends GuiScreen {

    private static final int ID_SAVE = 0;
    private static final int ID_CLEAR = 1;
    private static final int ID_REFRESH = 2;
    private static final int ID_DONE = 3;

    private static final int FIELD_WIDTH = 180;
    private static final int FIELD_HEIGHT = 18;

    private final BlockPos pos;
    private GuiTextField nameField;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    /** 只在第一次 initGui 时向服务端要一次选区（窗口缩放会重进 initGui） */
    private boolean requested;

    public GuiStructureWand(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;

        // 窗口大小变化会重进 initGui，保留玩家已经敲进去的名字
        String previous = this.nameField == null ? ClientWandState.getName() : this.nameField.getText();
        this.nameField = new GuiTextField(100, this.fontRenderer, cx - FIELD_WIDTH / 2, 54,
                FIELD_WIDTH, FIELD_HEIGHT);
        this.nameField.setMaxStringLength(48);
        this.nameField.setText(previous);
        this.nameField.setFocused(true);

        int y = this.height - 54;
        int w = 88;
        int gap = 6;
        int x = cx - (w * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_SAVE, x, y, w, 20, I18n.format("gui.rsaudio.wand.save")));
        this.buttonList.add(new GuiButton(ID_CLEAR, x + (w + gap), y, w, 20,
                I18n.format("gui.rsaudio.wand.clear")));
        this.buttonList.add(new GuiButton(ID_REFRESH, x + (w + gap) * 2, y, w, 20,
                I18n.format("gui.rsaudio.wand.refresh")));
        this.buttonList.add(new GuiButton(ID_DONE, x + (w + gap) * 3, y, w, 20,
                I18n.format("gui.rsaudio.wand.done")));

        if (!this.requested) {
            this.requested = true;
            send(new PacketWandAction(PacketWandAction.REQUEST, ""));
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_SAVE:
                send(new PacketWandAction(PacketWandAction.SAVE, this.nameField.getText()));
                break;
            case ID_CLEAR:
                send(new PacketWandAction(PacketWandAction.CLEAR, ""));
                this.nameField.setText("");
                break;
            case ID_REFRESH:
                send(new PacketWandAction(PacketWandAction.REQUEST, ""));
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
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            send(new PacketWandAction(PacketWandAction.SAVE, this.nameField.getText()));
            return;
        }
        if (this.nameField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.nameField.mouseClicked(mouseX, mouseY, mouseButton)) {
            this.nameField.setFocused(true);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.nameField != null) {
            this.nameField.updateCursorCounter();
        }
    }

    /** 断开连接时发包会失败，别让它崩客户端 */
    private void send(PacketWandAction packet) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(packet);
        } catch (Throwable t) {
            RsAudio.warn("结构魔杖操作发送失败（可能已断开连接）: " + t);
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.wand.title"), cx, 12, 0xFFFFFF);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.wand.selection", ClientWandState.describe()),
                cx, 28, 0xFFFF80);

        String label = I18n.format("gui.rsaudio.wand.name_label");
        int fieldX = cx - FIELD_WIDTH / 2;
        this.fontRenderer.drawStringWithShadow(label, fieldX, 42, 0xE0E0E0);
        this.nameField.drawTextBox();
        if (this.nameField.getText().isEmpty() && !this.nameField.isFocused()) {
            this.fontRenderer.drawString(I18n.format("gui.rsaudio.wand.name_hint"),
                    fieldX + 4, 59, 0x808080);
        }

        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.wand.pos", format(this.pos)), cx, 78, 0xAAAAAA);
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.wand.hint"), cx, 92, 0xAAAAAA);

        String line = !this.status.isEmpty() ? this.status
                : (ClientWandState.isMessageFresh() ? ClientWandState.getMessage() : "");
        if (line == null || line.isEmpty()) {
            line = I18n.format("gui.rsaudio.wand.hint_actions");
        }
        drawCenteredString(this.fontRenderer, line, cx, this.height - 68,
                this.status.isEmpty() ? 0xAAAAAA : this.statusColor);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private static String format(BlockPos p) {
        return "(" + p.getX() + ", " + p.getY() + ", " + p.getZ() + ")";
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

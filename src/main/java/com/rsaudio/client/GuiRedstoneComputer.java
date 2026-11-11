package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetProgram;
import com.rsaudio.redstone.asm.Assembler;
import com.rsaudio.redstone.asm.Cpu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动化红石信号输出计算机的编程界面。
 *
 * <h3>左边写代码，右边看它在干什么</h3>
 * 左边是一个极简的多行编辑器（没有富文本、没有选中区，够写几十行汇编就行），
 * 右边是实时面板：PC、四个寄存器、六个面的输出、已运行刻数、以及错误行。
 * 面板数据由服务端每 5 tick 推一次（见 {@code TileRedstoneComputer} 的 viewers）。
 *
 * <h3>为什么"看着"要告诉服务端</h3>
 * 服务端不会无脑往外推 —— 只有界面开着（发过 VIEWING=true）才推，关掉就停。
 * 否则一个基地里摆几十台计算机、每台都每 5 tick 发包，是不必要的带宽浪费。
 *
 * <h3>编辑器不会被同步覆盖</h3>
 * 只有"第一次收到同步"和"本地没有未保存改动"这两种情况下才会用服务端的文本刷新编辑器。
 * 不然你正打字时来一个同步包，敲进去的内容就没了。
 */
public class GuiRedstoneComputer extends GuiScreen {

    private static final int ID_SAVE = 0;
    private static final int ID_RUN = 1;
    private static final int ID_STOP = 2;
    private static final int ID_RESET = 3;
    private static final int ID_SAMPLE = 4;
    private static final int ID_DONE = 5;

    /** 每行占的像素高（字体本身 9 高，留 1 像素行距） */
    private static final int LINE_H = 10;
    private static final int GUTTER = 30;

    private final BlockPos pos;

    /** 编辑器内容（每行一条，不含换行符） */
    private final List<String> lines = new ArrayList<String>();
    private int caretLine;
    private int caretColumn;
    /** 编辑器顶部显示的是第几行（滚动） */
    private int scroll;
    /** 是否已经从服务端同步里取过源码 */
    private boolean loaded;
    /** 本地有未保存的改动 */
    private boolean dirty;
    private int blink;

    private String status = "";
    private int statusColor = 0xFFFFFF;

    private int editorX0;
    private int editorY0;
    private int editorX1;
    private int editorY1;
    private int panelX0;
    private int panelY0;
    private int panelX1;
    private int panelY1;
    private int textX;

    public GuiRedstoneComputer(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.lines.add("");
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;

        this.editorX0 = 12;
        this.editorY0 = 28;
        this.editorX1 = Math.max(this.editorX0 + 80, cx - 8);
        this.editorY1 = this.height - 60;
        this.textX = this.editorX0 + GUTTER;

        this.panelX0 = cx + 4;
        this.panelY0 = 28;
        this.panelX1 = Math.max(this.panelX0 + 60, this.width - 12);
        this.panelY1 = this.height - 60;

        // 6 个按钮排一行，按窗口宽度自适应，窗口很窄时也不会溢出
        int count = 6;
        int gap = 4;
        int w = Math.min(84, Math.max(30, (this.width - 16 - gap * (count - 1)) / count));
        int total = w * count + gap * (count - 1);
        int x = (this.width - total) / 2;
        int y = this.height - 52;
        this.buttonList.add(new GuiButton(ID_SAVE, x, y, w, 20, I18n.format("gui.rsaudio.computer.save")));
        this.buttonList.add(new GuiButton(ID_RUN, x + (w + gap), y, w, 20,
                I18n.format("gui.rsaudio.computer.run")));
        this.buttonList.add(new GuiButton(ID_STOP, x + (w + gap) * 2, y, w, 20,
                I18n.format("gui.rsaudio.computer.stop")));
        this.buttonList.add(new GuiButton(ID_RESET, x + (w + gap) * 3, y, w, 20,
                I18n.format("gui.rsaudio.computer.reset")));
        this.buttonList.add(new GuiButton(ID_SAMPLE, x + (w + gap) * 4, y, w, 20,
                I18n.format("gui.rsaudio.computer.sample")));
        this.buttonList.add(new GuiButton(ID_DONE, x + (w + gap) * 5, y, w, 20,
                I18n.format("gui.rsaudio.computer.done")));

        // 告诉服务端"我在看着这台机器"，之后它才会推调试数据
        ClientComputerState.expect(this.pos);
        send(PacketSetProgram.viewing(this.pos, true));
    }

    // ------------------------------------------------------------------ 编辑器内容

    private String currentLine() {
        if (this.lines.isEmpty()) {
            this.lines.add("");
        }
        if (this.caretLine < 0) {
            this.caretLine = 0;
        }
        if (this.caretLine >= this.lines.size()) {
            this.caretLine = this.lines.size() - 1;
        }
        String line = this.lines.get(this.caretLine);
        if (this.caretColumn < 0) {
            this.caretColumn = 0;
        }
        if (this.caretColumn > line.length()) {
            this.caretColumn = line.length();
        }
        return line;
    }

    private String toText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < this.lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(this.lines.get(i));
        }
        return sb.toString();
    }

    private void fromText(String text) {
        this.lines.clear();
        String value = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = value.split("\n", -1);
        for (String part : parts) {
            this.lines.add(part);
        }
        if (this.lines.isEmpty()) {
            this.lines.add("");
        }
        // 超长源码（比如有人手动改存档塞了 1 万行）在编辑器里截断显示，
        // 服务端侧本来也有 MAX_SOURCE_CHARS 上限，这里只是别让界面卡住
        int limit = 400;
        if (this.lines.size() > limit) {
            this.lines.subList(limit, this.lines.size()).clear();
        }
        this.caretLine = 0;
        this.caretColumn = 0;
        this.scroll = 0;
    }

    private void markDirty() {
        this.dirty = true;
    }

    // ------------------------------------------------------------------ 编辑操作

    private void insert(String text) {
        String line = currentLine();
        String head = line.substring(0, this.caretColumn);
        String tail = line.substring(this.caretColumn);
        this.lines.set(this.caretLine, head + text + tail);
        this.caretColumn += text.length();
        markDirty();
    }

    private void newLine() {
        String line = currentLine();
        String head = line.substring(0, this.caretColumn);
        String tail = line.substring(this.caretColumn);
        this.lines.set(this.caretLine, head);
        this.lines.add(this.caretLine + 1, tail);
        this.caretLine++;
        this.caretColumn = 0;
        markDirty();
    }

    private void backspace() {
        String line = currentLine();
        if (this.caretColumn > 0) {
            this.lines.set(this.caretLine,
                    line.substring(0, this.caretColumn - 1) + line.substring(this.caretColumn));
            this.caretColumn--;
            markDirty();
            return;
        }
        if (this.caretLine > 0) {
            String previous = this.lines.get(this.caretLine - 1);
            int joinAt = previous.length();
            this.lines.set(this.caretLine - 1, previous + line);
            this.lines.remove(this.caretLine);
            this.caretLine--;
            this.caretColumn = joinAt;
            markDirty();
        }
    }

    private void deleteForward() {
        String line = currentLine();
        if (this.caretColumn < line.length()) {
            this.lines.set(this.caretLine,
                    line.substring(0, this.caretColumn) + line.substring(this.caretColumn + 1));
            markDirty();
            return;
        }
        if (this.caretLine < this.lines.size() - 1) {
            this.lines.set(this.caretLine, line + this.lines.get(this.caretLine + 1));
            this.lines.remove(this.caretLine + 1);
            markDirty();
        }
    }

    private void moveVertical(int delta) {
        int target = this.caretLine + delta;
        if (target < 0 || target >= this.lines.size()) {
            return;
        }
        this.caretLine = target;
        currentLine();                       // 夹一次列号
    }

    private void moveHorizontal(int delta) {
        String line = currentLine();
        int next = this.caretColumn + delta;
        if (next < 0) {
            if (this.caretLine > 0) {
                this.caretLine--;
                this.caretColumn = this.lines.get(this.caretLine).length();
            }
            return;
        }
        if (next > line.length()) {
            if (this.caretLine < this.lines.size() - 1) {
                this.caretLine++;
                this.caretColumn = 0;
            }
            return;
        }
        this.caretColumn = next;
    }

    private void ensureCaretVisible() {
        int rows = visibleRows();
        if (this.caretLine < this.scroll) {
            this.scroll = this.caretLine;
        } else if (this.caretLine >= this.scroll + rows) {
            this.scroll = this.caretLine - rows + 1;
        }
        int maxScroll = Math.max(0, this.lines.size() - rows);
        if (this.scroll > maxScroll) {
            this.scroll = maxScroll;
        }
        if (this.scroll < 0) {
            this.scroll = 0;
        }
    }

    private int visibleRows() {
        return Math.max(1, (this.editorY1 - this.editorY0) / LINE_H);
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_SAVE:
                sendText("gui.rsaudio.computer.saved");
                break;
            case ID_RUN:
                sendText(null);
                send(PacketSetProgram.action(this.pos, PacketSetProgram.RUN));
                this.status = I18n.format("gui.rsaudio.computer.run_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_STOP:
                send(PacketSetProgram.action(this.pos, PacketSetProgram.STOP));
                this.status = I18n.format("gui.rsaudio.computer.stop_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_RESET:
                send(PacketSetProgram.action(this.pos, PacketSetProgram.RESET));
                this.status = I18n.format("gui.rsaudio.computer.reset_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_SAMPLE:
                fromText(Assembler.DEFAULT_SOURCE);
                markDirty();
                this.status = I18n.format("gui.rsaudio.computer.sample_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    /** 把编辑器内容发给服务端；messageKey 非空时顺带写一句状态提示 */
    private void sendText(String messageKey) {
        send(PacketSetProgram.text(this.pos, toText()));
        this.dirty = false;
        if (messageKey != null) {
            this.status = I18n.format(messageKey);
            this.statusColor = 0x90FF90;
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        switch (keyCode) {
            case Keyboard.KEY_UP:
                moveVertical(-1);
                return;
            case Keyboard.KEY_DOWN:
                moveVertical(1);
                return;
            case Keyboard.KEY_LEFT:
                moveHorizontal(-1);
                return;
            case Keyboard.KEY_RIGHT:
                moveHorizontal(1);
                return;
            case Keyboard.KEY_HOME:
                this.caretColumn = 0;
                return;
            case Keyboard.KEY_END:
                this.caretColumn = currentLine().length();
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                newLine();
                return;
            case Keyboard.KEY_BACK:
                backspace();
                return;
            case Keyboard.KEY_DELETE:
                deleteForward();
                return;
            case Keyboard.KEY_TAB:
                // 汇编里没有制表符的概念，插空格更保险
                insert("    ");
                return;
            default:
                break;
        }
        if (isTypeable(typedChar)) {
            insert(String.valueOf(typedChar));
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** 原版的字符过滤是私有的，这里自己写一份：可打印且不是 §（颜色符） */
    private static boolean isTypeable(char c) {
        return c >= ' ' && c != 127 && c != 167;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) {
            return;
        }
        // 按钮在编辑器下方，所以"点击落在编辑器矩形里"就不可能是在点按钮
        if (mouseX < this.editorX0 || mouseX > this.editorX1
                || mouseY < this.editorY0 || mouseY > this.editorY1) {
            return;
        }
        int row = (mouseY - this.editorY0) / LINE_H;
        int index = this.scroll + row;
        if (index < 0 || index >= this.lines.size()) {
            return;
        }
        this.caretLine = index;
        String line = this.lines.get(index);
        this.caretColumn = columnAt(line, mouseX - this.textX);
    }

    /** 相对 x 坐标 -> 最接近的插入位置 */
    private int columnAt(String line, int relativeX) {
        if (relativeX <= 0) {
            return 0;
        }
        int best = 0;
        for (int i = 1; i <= line.length(); i++) {
            if (this.fontRenderer.getStringWidth(line.substring(0, i)) <= relativeX) {
                best = i;
            } else {
                break;
            }
        }
        return best;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.blink++;
        ensureCaretVisible();
        syncFromServer();
    }

    /**
     * 第一次收到同步时把源码装进编辑器；之后只有在"本地没有未保存改动"时才跟随服务端刷新
     * —— 这样既能显示别人改过的程序，又不会在你自己打字时把你的修改顶掉。
     */
    private void syncFromServer() {
        if (!ClientComputerState.isValid()) {
            return;
        }
        String remote = ClientComputerState.getSource();
        if (!this.loaded) {
            this.loaded = true;
            fromText(remote);
            this.dirty = false;
            return;
        }
        if (!this.dirty && !toText().equals(remote)) {
            fromText(remote);
        }
    }

    /** 断开连接时发包会失败，别让它崩客户端 */
    private void send(PacketSetProgram packet) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(packet);
        } catch (Throwable t) {
            RsAudio.warn("红石计算机操作发送失败（可能已断开连接）: " + t);
        }
    }

    @Override
    public void onGuiClosed() {
        // 关界面前把没保存的内容落盘，并让服务端停止推数据
        if (this.dirty) {
            sendText(null);
        }
        send(PacketSetProgram.viewing(this.pos, false));
        super.onGuiClosed();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.computer.title"), cx, 6, 0xFFFFFF);

        drawEditor();
        drawPanel();

        String hint = !this.status.isEmpty() ? this.status : I18n.format("gui.rsaudio.computer.hint");
        drawCenteredString(this.fontRenderer, hint, cx, this.height - 66,
                this.status.isEmpty() ? 0xAAAAAA : this.statusColor);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawEditor() {
        drawRect(this.editorX0 - 1, this.editorY0 - 1, this.editorX1 + 1, this.editorY1 + 1, 0xFF000000);
        drawRect(this.editorX0, this.editorY0, this.editorX1, this.editorY1, 0xFF101018);

        int errorLine = ClientComputerState.getErrorLine();
        int rows = visibleRows();
        for (int i = 0; i < rows; i++) {
            int index = this.scroll + i;
            if (index >= this.lines.size()) {
                break;
            }
            int rowY = this.editorY0 + i * LINE_H;
            boolean bad = errorLine > 0 && errorLine == index + 1;
            if (bad) {
                drawRect(this.editorX0, rowY, this.editorX1, rowY + LINE_H, 0x60A00000);
            }
            String number = String.valueOf(index + 1);
            this.fontRenderer.drawString(number,
                    this.editorX0 + GUTTER - 4 - this.fontRenderer.getStringWidth(number),
                    rowY + 1, bad ? 0xFFFF6060 : 0xFF606080);
            this.fontRenderer.drawString(this.lines.get(index), this.textX, rowY + 1,
                    bad ? 0xFFFF9090 : 0xFFD0D0D0);
        }

        // 光标：编辑器没有焦点概念，它一直是有焦点的（界面里没有别的输入框）
        if (this.blink / 6 % 2 == 0 && this.caretLine >= this.scroll
                && this.caretLine < this.scroll + rows) {
            String line = this.lines.get(this.caretLine);
            int column = Math.min(this.caretColumn, line.length());
            int caretX = this.textX + this.fontRenderer.getStringWidth(line.substring(0, column));
            int caretY = this.editorY0 + (this.caretLine - this.scroll) * LINE_H;
            drawRect(caretX, caretY, caretX + 1, caretY + LINE_H - 1, 0xFFE0E0FF);
        }
    }

    private void drawPanel() {
        drawRect(this.panelX0 - 1, this.panelY0 - 1, this.panelX1 + 1, this.panelY1 + 1, 0xFF000000);
        drawRect(this.panelX0, this.panelY0, this.panelX1, this.panelY1, 0xFF101018);

        int x = this.panelX0 + 4;
        int y = this.panelY0 + 4;
        int width = this.panelX1 - this.panelX0 - 8;

        this.fontRenderer.drawString(I18n.format("gui.rsaudio.computer.panel"), x, y, 0xFFFFD700);
        y += 12;

        if (!ClientComputerState.isValid()) {
            for (String piece : this.fontRenderer.listFormattedStringToWidth(
                    I18n.format("gui.rsaudio.computer.waiting"), width)) {
                this.fontRenderer.drawString(piece, x, y, 0xFF808080);
                y += 10;
            }
            return;
        }

        boolean running = ClientComputerState.isRunning();
        this.fontRenderer.drawString(
                I18n.format(running ? "gui.rsaudio.computer.status_running"
                        : "gui.rsaudio.computer.status_idle"),
                x, y, running ? 0xFF60FF60 : 0xFF909090);
        y += 11;

        this.fontRenderer.drawString(
                I18n.format("gui.rsaudio.computer.pc", ClientComputerState.getPc()), x, y, 0xFFC0C0C0);
        y += 10;
        this.fontRenderer.drawString(
                I18n.format("gui.rsaudio.computer.ticks", ClientComputerState.getTicks()), x, y, 0xFFC0C0C0);
        y += 11;

        // ---- 寄存器
        this.fontRenderer.drawString(I18n.format("gui.rsaudio.computer.regs"), x, y, 0xFF8080FF);
        y += 10;
        for (int i = 0; i < Cpu.REG_COUNT; i++) {
            this.fontRenderer.drawString(registerName(i) + " = " + ClientComputerState.getReg(i),
                    x + 6, y, 0xFFE0E0FF);
            y += 10;
        }
        y += 2;

        // ---- 六个面
        this.fontRenderer.drawString(I18n.format("gui.rsaudio.computer.outputs"), x, y, 0xFFFF8080);
        y += 10;
        for (int face = 0; face < Cpu.FACE_COUNT; face++) {
            int value = ClientComputerState.getOutput(face);
            String label = faceName(face) + " = " + value;
            this.fontRenderer.drawString(label, x + 6, y, value > 0 ? 0xFFFFC060 : 0xFF707070);
            // 强度条：一眼看出哪一面在输出
            int barX = x + 6 + this.fontRenderer.getStringWidth(label) + 4;
            if (value > 0 && barX < this.panelX1 - 6) {
                int barWidth = Math.max(2, Math.min(this.panelX1 - 6 - barX, value * 3));
                drawRect(barX, y + 2, barX + barWidth, y + 7, 0xFFFF6000);
            }
            y += 10;
        }
        y += 2;

        // ---- 错误
        String error = ClientComputerState.getError();
        if (error != null && !error.isEmpty()) {
            String head = I18n.format("gui.rsaudio.computer.error",
                    ClientComputerState.getErrorLine());
            for (String piece : this.fontRenderer.listFormattedStringToWidth(head, width)) {
                this.fontRenderer.drawString(piece, x, y, 0xFFFF6060);
                y += 10;
            }
            for (String piece : this.fontRenderer.listFormattedStringToWidth(error, width)) {
                this.fontRenderer.drawString(piece, x, y, 0xFFE08080);
                y += 10;
            }
        }
    }

    private static String registerName(int index) {
        return String.valueOf((char) ('A' + (index & 3)));
    }

    private static String faceName(int face) {
        String key = face >= 0 && face < Assembler.FACE_NAMES.length
                ? Assembler.FACE_NAMES[face] : "down";
        return I18n.format("gui.rsaudio.computer.face." + key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

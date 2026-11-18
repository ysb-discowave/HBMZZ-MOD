package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketRequestStructures;
import com.rsaudio.network.PacketStructureLoad;
import com.rsaudio.tile.TileStructureLoader;
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
 * 结构加载器界面：选结构、调相对偏移、加载。
 *
 * <h3>两个数据来源</h3>
 * <ul>
 *     <li><b>结构名清单</b>来自服务端（{@link ClientStructureList}）—— 结构文件在服务端的
 *         config/rsaudio/structures 里，联机时更在别人的机器上，客户端看不见。</li>
 *     <li><b>当前设置</b>（选中的结构名、偏移、状态）读本地这一格的方块实体 ——
 *         方块实体自己会用 {@code getUpdateTag} 把这几项同步过来。</li>
 * </ul>
 *
 * <h3>点一下 = 直接设</h3>
 * 点清单里的名字就立刻把结构名发给服务端（而不是"选中 → 再点应用"），少一步操作。
 * 偏移则用输入框 + 「应用偏移」，因为那是要精确敲数字的地方。
 */
public class GuiStructureLoader extends GuiScreen {

    private static final int ID_LOAD = 0;
    private static final int ID_REFRESH = 1;
    private static final int ID_APPLY_OFFSET = 2;
    private static final int ID_DONE = 3;
    private static final int ID_PREV = 4;
    private static final int ID_NEXT = 5;

    private static final int ROW_H = 12;
    private static final int ROWS_PER_PAGE = 8;
    private static final int LIST_WIDTH = 300;
    private static final int LIST_TOP = 44;
    private static final int OFFSET_ROW_Y = 172;

    private final BlockPos pos;
    private GuiTextField fieldX;
    private GuiTextField fieldY;
    private GuiTextField fieldZ;
    private int page;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    /** 只在第一次 initGui 时各要一次清单 */
    private boolean requested;
    /** 上一次从方块实体读到的偏移，用来判断"值真的变了没有" */
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;

    public GuiStructureLoader(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        int cx = this.width / 2;
        int lx = cx - LIST_WIDTH / 2;

        TileStructureLoader tile = getTile();
        this.fieldX = new GuiTextField(200, this.fontRenderer, lx + 62, OFFSET_ROW_Y, 34, 16);
        this.fieldY = new GuiTextField(201, this.fontRenderer, lx + 118, OFFSET_ROW_Y, 34, 16);
        this.fieldZ = new GuiTextField(202, this.fontRenderer, lx + 174, OFFSET_ROW_Y, 34, 16);
        for (GuiTextField field : new GuiTextField[]{this.fieldX, this.fieldY, this.fieldZ}) {
            field.setMaxStringLength(4);
            field.setFocused(false);
        }
        // 窗口缩放会重进 initGui；如果玩家已经改了偏移就别覆盖
        if (this.lastX == Integer.MIN_VALUE || !anyFieldFocused()) {
            fillFields(tile);
        }

        int pagerY = LIST_TOP + ROWS_PER_PAGE * ROW_H + 4;
        this.buttonList.add(new GuiButton(ID_PREV, lx, pagerY, 70, 20,
                I18n.format("gui.rsaudio.loader.prev")));
        this.buttonList.add(new GuiButton(ID_NEXT, lx + LIST_WIDTH - 70, pagerY, 70, 20,
                I18n.format("gui.rsaudio.loader.next")));
        this.buttonList.add(new GuiButton(ID_APPLY_OFFSET, lx + 216, OFFSET_ROW_Y - 2, 84, 20,
                I18n.format("gui.rsaudio.loader.apply_offset")));

        int y = this.height - 54;
        int w = 96;
        int gap = 8;
        int x = cx - (w * 3 + gap * 2) / 2;
        this.buttonList.add(new GuiButton(ID_LOAD, x, y, w, 20, I18n.format("gui.rsaudio.loader.load")));
        this.buttonList.add(new GuiButton(ID_REFRESH, x + w + gap, y, w, 20,
                I18n.format("gui.rsaudio.loader.refresh")));
        this.buttonList.add(new GuiButton(ID_DONE, x + (w + gap) * 2, y, w, 20,
                I18n.format("gui.rsaudio.loader.done")));

        if (!this.requested) {
            this.requested = true;
            sendRequest();
        }
    }

    private void fillFields(TileStructureLoader tile) {
        this.lastX = tile == null ? 0 : tile.getOffsetX();
        this.lastY = tile == null ? 1 : tile.getOffsetY();
        this.lastZ = tile == null ? 0 : tile.getOffsetZ();
        this.fieldX.setText(String.valueOf(this.lastX));
        this.fieldY.setText(String.valueOf(this.lastY));
        this.fieldZ.setText(String.valueOf(this.lastZ));
    }

    private boolean anyFieldFocused() {
        return (this.fieldX != null && this.fieldX.isFocused())
                || (this.fieldY != null && this.fieldY.isFocused())
                || (this.fieldZ != null && this.fieldZ.isFocused());
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_LOAD:
                sendLoad(PacketStructureLoad.load(this.pos));
                this.status = I18n.format("gui.rsaudio.loader.requested");
                this.statusColor = 0x90FF90;
                break;
            case ID_REFRESH:
                sendRequest();
                this.status = I18n.format("gui.rsaudio.loader.refreshed");
                this.statusColor = 0x90FF90;
                break;
            case ID_APPLY_OFFSET:
                applyOffset();
                break;
            case ID_PREV:
                if (this.page > 0) {
                    this.page--;
                }
                break;
            case ID_NEXT:
                if ((this.page + 1) * ROWS_PER_PAGE < ClientStructureList.get().length) {
                    this.page++;
                }
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    private void applyOffset() {
        Integer x = parseInt(this.fieldX.getText());
        Integer y = parseInt(this.fieldY.getText());
        Integer z = parseInt(this.fieldZ.getText());
        if (x == null || y == null || z == null) {
            this.status = I18n.format("gui.rsaudio.loader.offset_invalid",
                    String.valueOf(TileStructureLoader.MAX_OFFSET));
            this.statusColor = 0xFF8080;
            return;
        }
        int cx = TileStructureLoader.clampOffset(x);
        int cy = TileStructureLoader.clampOffset(y);
        int cz = TileStructureLoader.clampOffset(z);
        sendLoad(PacketStructureLoad.offset(this.pos, x, y, z));
        fillFields(null);
        this.lastX = cx;
        this.lastY = cy;
        this.lastZ = cz;
        this.fieldX.setText(String.valueOf(cx));
        this.fieldY.setText(String.valueOf(cy));
        this.fieldZ.setText(String.valueOf(cz));
        this.status = I18n.format("gui.rsaudio.loader.offset_applied", cx, cy, cz);
        this.statusColor = 0x90FF90;
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);

        boolean inX = this.fieldX.mouseClicked(mouseX, mouseY, mouseButton);
        boolean inY = this.fieldY.mouseClicked(mouseX, mouseY, mouseButton);
        boolean inZ = this.fieldZ.mouseClicked(mouseX, mouseY, mouseButton);
        if (inX || inY || inZ) {
            // 三个输入框互斥，键盘只会进被点中的那一个
            this.fieldX.setFocused(inX);
            this.fieldY.setFocused(inY);
            this.fieldZ.setFocused(inZ);
            return;
        }
        this.fieldX.setFocused(false);
        this.fieldY.setFocused(false);
        this.fieldZ.setFocused(false);

        selectRowAt(mouseX, mouseY);
    }

    /** 点在清单上的某一行 → 直接把那个结构名发给服务端 */
    private void selectRowAt(int mouseX, int mouseY) {
        int lx = this.width / 2 - LIST_WIDTH / 2;
        if (mouseX < lx || mouseX > lx + LIST_WIDTH
                || mouseY < LIST_TOP || mouseY >= LIST_TOP + ROWS_PER_PAGE * ROW_H) {
            return;
        }
        int row = (mouseY - LIST_TOP) / ROW_H;
        int index = this.page * ROWS_PER_PAGE + row;
        String[] names = ClientStructureList.get();
        if (index < 0 || index >= names.length) {
            return;
        }
        String name = names[index];
        if (name == null || name.isEmpty()) {
            return;
        }
        sendLoad(PacketStructureLoad.name(this.pos, name));
        this.status = I18n.format("gui.rsaudio.loader.selected", name);
        this.statusColor = 0x90FF90;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (anyFieldFocused()) {
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                applyOffset();
                return;
            }
            if (keyCode == Keyboard.KEY_TAB) {
                cycleFocus();
                return;
            }
            if (this.fieldX.textboxKeyTyped(typedChar, keyCode)
                    || this.fieldY.textboxKeyTyped(typedChar, keyCode)
                    || this.fieldZ.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    /** Tab 在 X → Y → Z → X 之间轮转焦点 */
    private void cycleFocus() {
        if (this.fieldX.isFocused()) {
            this.fieldX.setFocused(false);
            this.fieldY.setFocused(true);
        } else if (this.fieldY.isFocused()) {
            this.fieldY.setFocused(false);
            this.fieldZ.setFocused(true);
        } else {
            this.fieldZ.setFocused(false);
            this.fieldX.setFocused(true);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        this.fieldX.updateCursorCounter();
        this.fieldY.updateCursorCounter();
        this.fieldZ.updateCursorCounter();

        // 服务端改了偏移（例如别人动了）时刷新输入框，但不要打断正在打字的人
        TileStructureLoader tile = getTile();
        if (tile == null || anyFieldFocused()) {
            return;
        }
        if (tile.getOffsetX() != this.lastX || tile.getOffsetY() != this.lastY
                || tile.getOffsetZ() != this.lastZ) {
            fillFields(tile);
        }
    }

    private void sendRequest() {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketRequestStructures());
        } catch (Throwable t) {
            RsAudio.warn("结构清单请求发送失败（可能已断开连接）: " + t);
        }
    }

    private void sendLoad(PacketStructureLoad packet) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(packet);
        } catch (Throwable t) {
            RsAudio.warn("结构加载器操作发送失败（可能已断开连接）: " + t);
        }
    }

    private TileStructureLoader getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileStructureLoader ? (TileStructureLoader) te : null;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        int lx = cx - LIST_WIDTH / 2;
        TileStructureLoader tile = getTile();

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.loader.title"), cx, 8, 0xFFFFFF);

        String current = tile == null ? I18n.format("gui.rsaudio.loader.unknown")
                : (tile.getStructure().isEmpty() ? I18n.format("gui.rsaudio.loader.none_selected")
                : tile.getStructure());
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.loader.current", current),
                cx, 22, tile != null && tile.isLoading() ? 0xFFFF80 : 0xFFFFFF);

        // ---- 清单
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.loader.list_label"), lx, 32, 0xE0E0E0);
        drawRect(lx - 1, LIST_TOP - 1, lx + LIST_WIDTH + 1, LIST_TOP + ROWS_PER_PAGE * ROW_H + 1, 0xFF000000);
        drawRect(lx, LIST_TOP, lx + LIST_WIDTH, LIST_TOP + ROWS_PER_PAGE * ROW_H, 0xFF101018);

        String[] names = ClientStructureList.get();
        int selectedIndex = -1;
        String currentName = tile == null ? "" : tile.getStructure();
        for (int i = 0; i < names.length; i++) {
            if (names[i] != null && names[i].equals(currentName)) {
                selectedIndex = i;
                break;
            }
        }

        if (names.length == 0) {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.loader.empty"),
                    cx, LIST_TOP + 20, 0xFF8080);
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.loader.empty_hint"),
                    cx, LIST_TOP + 34, 0xAAAAAA);
        } else {
            int start = this.page * ROWS_PER_PAGE;
            for (int i = 0; i < ROWS_PER_PAGE; i++) {
                int index = start + i;
                if (index >= names.length) {
                    break;
                }
                int rowY = LIST_TOP + i * ROW_H;
                boolean hover = mouseX >= lx && mouseX <= lx + LIST_WIDTH
                        && mouseY >= rowY && mouseY < rowY + ROW_H;
                boolean isCurrent = index == selectedIndex;
                if (isCurrent) {
                    drawRect(lx, rowY, lx + LIST_WIDTH, rowY + ROW_H, 0xFF2A4A2A);
                } else if (hover) {
                    drawRect(lx, rowY, lx + LIST_WIDTH, rowY + ROW_H, 0xFF2A2A40);
                }
                int color = isCurrent ? 0x90FF90 : (hover ? 0xFFFFFF : 0xC0C0C0);
                this.fontRenderer.drawString(names[index], lx + 4, rowY + 2, color);
            }
        }
        int pages = Math.max(1, (names.length + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.loader.page", this.page + 1, pages, names.length),
                cx, LIST_TOP + ROWS_PER_PAGE * ROW_H + 27, 0xAAAAAA);

        // ---- 偏移
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.loader.offset_label"),
                lx, OFFSET_ROW_Y + 5, 0xE0E0E0);
        this.fontRenderer.drawString("X", lx + 50, OFFSET_ROW_Y + 5, 0xFF8080);
        this.fontRenderer.drawString("Y", lx + 106, OFFSET_ROW_Y + 5, 0x80FF80);
        this.fontRenderer.drawString("Z", lx + 162, OFFSET_ROW_Y + 5, 0x8080FF);
        this.fieldX.drawTextBox();
        this.fieldY.drawTextBox();
        this.fieldZ.drawTextBox();
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.loader.offset_range",
                        -TileStructureLoader.MAX_OFFSET, TileStructureLoader.MAX_OFFSET),
                cx, OFFSET_ROW_Y + 24, 0x888888);

        // ---- 状态（可能很长，折行显示，别跑出屏幕）
        String text;
        int color;
        if (!this.status.isEmpty()) {
            text = this.status;
            color = this.statusColor;
        } else if (tile != null && !tile.getStatus().isEmpty()) {
            text = tile.getStatus();
            color = 0xFFFFFF;
        } else {
            text = I18n.format("gui.rsaudio.loader.hint");
            color = 0xAAAAAA;
        }
        java.util.List<String> lines = this.fontRenderer.listFormattedStringToWidth(
                text, Math.max(60, this.width - 40));
        int lineY = this.height - 70;
        for (String piece : lines) {
            drawCenteredString(this.fontRenderer, piece, cx, lineY, color);
            lineY += 10;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

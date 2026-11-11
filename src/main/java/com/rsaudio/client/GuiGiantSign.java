package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketGiantSignSet;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.tile.TileGiantSign;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 巨大标志牌的编辑界面：三行文字 + 字色 + 照明。
 *
 * <h3>为什么必须有界面</h3>
 * 牌子的全部价值就是"写什么"。做成"右键循环切换若干预设短语"的话，
 * 玩家永远写不出自己基地的名字 —— 那这个方块就只是个装饰贴图了。
 *
 * <h3>★ 宽窗口才画右边那块预览</h3>
 * 字色光看名字（"琥珀"）是选不出来的，所以右边给一块<b>预览区</b>，
 * 用当前字色把三行画出来。
 * <p>
 * 但它不是必需的：窗口被拉窄时（原版 GUI 缩放调大，宽度可能只有 320），
 * {@code cx + 110} 那块就会捅出屏幕右边。所以这里先算一次能不能放下，
 * 放不下就<b>不画</b> —— 宁可少一块预览，也不要在窄窗口上画出屏幕外的东西。
 * 输入框同理，宽度按窗口宽反算（{@code Math.min(200, width - 30)}）。
 *
 * <h3>★ 提交时机</h3>
 * <ul>
 *   <li>字色 / 照明：<b>按下就发</b> —— 它们是离散的、点一下就有反馈，
 *       而且玩家会连点着找颜色，攒到"完成"再发等于没有反馈。</li>
 *   <li>文字：<b>按"应用"或关界面时发</b> —— 自由文本每敲一个字发一个包
 *       会把这台机器的输入延迟顶上去（{@code PacketSetCamera} 那边同理）。</li>
 *   <li>按 Esc 直接关也算提交（{@code onGuiClosed}）：不然玩家敲完名字按 Esc 就白敲了。</li>
 * </ul>
 */
public class GuiGiantSign extends GuiScreen {

    private static final int ID_COLOR_PREV = 100;
    private static final int ID_COLOR_NEXT = 101;
    private static final int ID_LIT = 102;
    private static final int ID_APPLY = 103;
    private static final int ID_DONE = 104;

    private static final int ROW1_Y = 58;
    private static final int ROW_STEP = 22;
    private static final int ROW2_Y = ROW1_Y + ROW_STEP;      // 80
    private static final int ROW3_Y = ROW1_Y + ROW_STEP * 2;  // 102
    private static final int CONTROL_Y = 130;

    private final BlockPos pos;
    private final GuiTextField[] fields = new GuiTextField[TileGiantSign.LINE_COUNT];
    private int colorIndex = TileGiantSign.DEFAULT_COLOR;
    private boolean lit = true;
    /** 已经把服务端那份读过来了没有（方块实体可能还没同步到） */
    private boolean loaded;

    public GuiGiantSign(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();

        int cx = this.width / 2;
        int fw = Math.min(200, this.width - 30);
        int fx = cx - fw / 2;
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            this.fields[i] = new GuiTextField(i, this.fontRenderer, fx, ROW1_Y + ROW_STEP * i, fw, 18);
            this.fields[i].setMaxStringLength(TileGiantSign.MAX_LINE_LENGTH);
            this.fields[i].setText(getTile() == null ? "" : getTile().getLine(i));
            this.fields[i].setFocused(false);
        }

        // 控制行：◀ 色名 ▶  |  照明开关 —— 一行排完，省一行高度
        int cw = Math.min(fw, 200);
        int cxx = cx - cw / 2;
        this.buttonList.add(new GuiButton(ID_COLOR_PREV, cxx, CONTROL_Y, 20, 18, "◀"));
        this.buttonList.add(new GuiButton(ID_COLOR_NEXT, cxx + 24, CONTROL_Y, 20, 18, "▶"));
        this.buttonList.add(new GuiButton(ID_LIT, cxx + 48, CONTROL_Y, cw - 48, 18, litLabel()));

        int botW = Math.min(100, (fw - 8) / 2);
        this.buttonList.add(new GuiButton(ID_APPLY, cx - botW - 4, this.height - 44, botW, 20,
                I18n.format("gui.rsaudio.giant_sign.apply")));
        this.buttonList.add(new GuiButton(ID_DONE, cx + 4, this.height - 44, botW, 20,
                I18n.format("gui.rsaudio.giant_sign.done")));
    }

    private String litLabel() {
        return I18n.format(lit ? "gui.rsaudio.giant_sign.lit_on" : "gui.rsaudio.giant_sign.lit_off");
    }

    private String colorLabel() {
        return I18n.format("tile.rsaudio.giant_sign.color." + TileGiantSign.colorName(colorIndex));
    }

    private void readFromTile() {
        TileGiantSign tile = getTile();
        if (tile != null) {
            this.colorIndex = tile.getColorIndex();
            this.lit = litOf();
            this.loaded = true;
        }
    }

    private TileGiantSign getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileGiantSign ? (TileGiantSign) te : null;
    }

    /** 照明存在方块状态里（自发光只能读方块状态），所以从世界读，不从方块实体读 */
    private boolean litOf() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return true;
        }
        net.minecraft.block.state.IBlockState state = world.getBlockState(this.pos);
        if (state.getBlock() instanceof com.rsaudio.block.BlockGiantSign) {
            return state.getValue(com.rsaudio.block.BlockGiantSign.LIT).booleanValue();
        }
        return true;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_COLOR_PREV:
                this.colorIndex = TileGiantSign.clampColor(this.colorIndex - 1
                        + (this.colorIndex <= 0 ? TileGiantSign.COLOR_COUNT : 0));
                send();
                break;
            case ID_COLOR_NEXT:
                this.colorIndex = TileGiantSign.clampColor(this.colorIndex + 1);
                send();
                break;
            case ID_LIT:
                this.lit = !this.lit;
                button.displayString = litLabel();
                send();
                break;
            case ID_APPLY:
                send();
                break;
            case ID_DONE:
                send();
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    private void send() {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        String[] lines = new String[TileGiantSign.LINE_COUNT];
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            lines[i] = this.fields[i] == null ? "" : this.fields[i].getText();
        }
        try {
            PacketHandler.INSTANCE.sendToServer(
                    new PacketGiantSignSet(this.pos, lines, this.colorIndex, this.lit));
        } catch (Throwable t) {
            RsAudio.warn("巨大标志牌设置发送失败（可能已断开连接）: " + t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        for (GuiTextField field : this.fields) {
            if (field != null && field.isFocused()) {
                // Esc 让输入框先失焦，再按一次才关界面 —— 否则想取消输入就直接退出了
                if (keyCode == 1) {
                    field.setFocused(false);
                    return;
                }
                field.textboxKeyTyped(typedChar, keyCode);
                return;
            }
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (GuiTextField field : this.fields) {
            if (field != null) {
                field.mouseClicked(mouseX, mouseY, mouseButton);
            }
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        for (GuiTextField field : this.fields) {
            if (field != null) {
                field.updateCursorCounter();
            }
        }
        if (!this.loaded) {
            TileGiantSign tile = getTile();
            if (tile != null) {
                readFromTile();
                refreshLitButton();
            }
        }
    }

    private void refreshLitButton() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_LIT) {
                button.displayString = litLabel();
                return;
            }
        }
    }

    @Override
    public void onGuiClosed() {
        // 关界面（含按 Esc 直接关）也把内容存下来
        send();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.giant_sign.title"), cx, 10, 0xFFFFFF);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.giant_sign.where", this.pos.getX(), this.pos.getY(), this.pos.getZ()),
                cx, 22, 0x909090);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.giant_sign.hint", TileGiantSign.MAX_LINE_LENGTH),
                cx, 36, 0xAAAAAA);

        for (GuiTextField field : this.fields) {
            if (field != null) {
                field.drawTextBox();
            }
        }

        // ★ 色名单独占控制行下面那一行：控制行里两个箭头之间只有 4px 缝，
        //   塞不下"琥珀色"这种三个字的标签（挤进去会压到箭头上）。
        drawColorName(cx);

        drawPreview(cx);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawColorName(int cx) {
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.giant_sign.color_label", colorLabel()),
                cx, CONTROL_Y + 22, 0xFFFF80);
    }

    /**
     * 右侧预览区：用当前字色把三行画出来。
     * <p>
     * ★ 放不下就整个不画（见类注释）：窄窗口上宁可少一块预览，
     * 也不能把内容画到屏幕外。
     */
    private void drawPreview(int cx) {
        int pw = 100;
        int ph = 110;
        int px = cx + 110;
        int py = ROW1_Y;
        if (px + pw > this.width - 6 || py + ph > this.height - 50) {
            return;
        }
        int rgb = TileGiantSign.colorRgb(this.colorIndex);
        // 底色故意压得很暗：牌面是深色板，预览也要在那个底上看颜色才对
        drawRect(px, py, px + pw, py + ph, 0xFF20242A);
        drawRect(px, py, px + pw, py + 1, 0xFF6A7078);
        drawRect(px, py + ph - 1, px + pw, py + ph, 0xFF6A7078);
        drawRect(px, py, px + 1, py + ph, 0xFF6A7078);
        drawRect(px + pw - 1, py, px + pw, py + ph, 0xFF6A7078);

        int step = (ph - 16) / TileGiantSign.LINE_COUNT;
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            String text = this.fields[i] == null ? "" : this.fields[i].getText();
            if (text.isEmpty()) {
                continue;
            }
            String shown = text.length() > 14 ? text.substring(0, 13) + "…" : text;
            int ty = py + 8 + step * i;
            drawCenteredString(this.fontRenderer, shown, px + pw / 2, ty, rgb);
        }
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.giant_sign.preview"),
                px + pw / 2, py + ph + 4, 0x808080);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

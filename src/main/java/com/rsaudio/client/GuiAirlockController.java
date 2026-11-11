package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.access.AirlockCore;
import com.rsaudio.access.TerminalCore;
import com.rsaudio.block.BlockAirlockController;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetAirlock;
import com.rsaudio.tile.TileAirlockController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 气闸控制器设置界面：<b>潜行右键</b>（或木斧潜行右键）打开。
 *
 * <h3>参数是"整体覆盖"的</h3>
 * 每次点按钮都把整套参数（频道 / 净化时长 / 超时 / 播报开关）发过去，
 * 而不是"这次改了哪一项"。界面上的值就是玩家的意图。
 * <p>
 * ★ 连「启动」也带着整套参数：玩家可能刚把净化从 180 调到 60 就直接按启动，
 * 只发动作码的话那次调节就丢了，而他会看到"净化还是走了 9 秒"。
 *
 * <h3>★ 这里的按钮不做本地预判</h3>
 * 状态行显示的是<b>服务端同步过来的进度</b>，不是界面自己算的。
 * 界面自己算的话，一旦服务端那边因为超时中止了，界面会继续快乐地倒计时 ——
 * 玩家盯着"还剩 12 刻"却等不到门开，比看到"已中止"难查得多。
 * 所以本地只在很有限的地方乐观更新（那几颗参数加号），
 * 「启动 / 急停」一律等服务端回声。
 */
public class GuiAirlockController extends GuiScreen {

    private static final int ID_CH_MINUS = 0;
    private static final int ID_CH_PLUS = 1;
    private static final int ID_ANNOUNCE = 2;
    private static final int ID_DECON_MINUS = 3;
    private static final int ID_DECON_PLUS = 4;
    private static final int ID_TIMEOUT_MINUS = 5;
    private static final int ID_TIMEOUT_PLUS = 6;
    private static final int ID_START = 7;
    private static final int ID_CANCEL = 8;
    private static final int ID_DONE = 9;

    /** 净化时长按一下调多少刻（20 刻 = 1 秒） */
    private static final int DECON_STEP = 20;
    /** 超时按一下调多少刻 */
    private static final int TIMEOUT_STEP = 10;

    private final BlockPos pos;
    private int channel = TerminalCore.CHANNEL_DEFAULT;
    private int deconTicks = AirlockCore.DECON_DEFAULT;
    private int lockTimeout = AirlockCore.TIMEOUT_DEFAULT;
    private boolean announce = true;

    private int state = AirlockCore.STATE_IDLE;
    private int deconRemaining;
    private int doorCount;
    private String sidesText = "";
    private String status = "";
    private int statusColor = 0xFFFFFF;
    private boolean loaded;

    private int rowTop;
    private int rowGap;
    private int btnW;

    public GuiAirlockController(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();

        int cx = this.width / 2;
        // 最宽的一行是 4 个按钮
        int widest = 4;
        this.btnW = Math.min(96, Math.max(30, (this.width - 16 - 4 * (widest - 1)) / widest));
        int gap = 4;

        int bottomRowY = this.height - 32;
        this.rowGap = 24;
        this.rowTop = bottomRowY - this.rowGap * 3;
        // 窗口不够高时压缩行距（信息行占到 y=80，所以顶部不能早于 92）。
        // 压不动就让它盖住提示行 —— 按钮点不到比少显示一行提示严重得多
        if (this.rowTop < 92) {
            this.rowGap = Math.max(18, (bottomRowY - 92) / 3);
            this.rowTop = bottomRowY - this.rowGap * 3;
        }

        // 第 0 行：频道 / 播报
        int y0 = this.rowTop;
        int x0 = cx - (btnW * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_CH_MINUS, x0, y0, btnW, 20,
                I18n.format("gui.rsaudio.airlock.channel_minus")));
        this.buttonList.add(new GuiButton(ID_CH_PLUS, x0 + (btnW + gap), y0, btnW, 20,
                I18n.format("gui.rsaudio.airlock.channel_plus")));
        this.buttonList.add(new GuiButton(ID_ANNOUNCE, x0 + (btnW + gap) * 2, y0, btnW * 2 + gap, 20,
                announceLabel()));

        // 第 1 行：净化时长
        int y1 = this.rowTop + this.rowGap;
        int x1 = cx - (btnW * 2 + gap) / 2;
        this.buttonList.add(new GuiButton(ID_DECON_MINUS, x1, y1, btnW, 20,
                I18n.format("gui.rsaudio.airlock.decon_minus")));
        this.buttonList.add(new GuiButton(ID_DECON_PLUS, x1 + (btnW + gap), y1, btnW, 20,
                I18n.format("gui.rsaudio.airlock.decon_plus")));

        // 第 2 行：超时
        int y2 = this.rowTop + this.rowGap * 2;
        int x2 = cx - (btnW * 2 + gap) / 2;
        this.buttonList.add(new GuiButton(ID_TIMEOUT_MINUS, x2, y2, btnW, 20,
                I18n.format("gui.rsaudio.airlock.timeout_minus")));
        this.buttonList.add(new GuiButton(ID_TIMEOUT_PLUS, x2 + (btnW + gap), y2, btnW, 20,
                I18n.format("gui.rsaudio.airlock.timeout_plus")));

        // 第 3 行：启动 / 急停 / 完成
        int y3 = this.rowTop + this.rowGap * 3;
        int x3 = cx - (btnW * 3 + gap * 2) / 2;
        this.buttonList.add(new GuiButton(ID_START, x3, y3, btnW, 20,
                I18n.format("gui.rsaudio.airlock.start")));
        this.buttonList.add(new GuiButton(ID_CANCEL, x3 + (btnW + gap), y3, btnW, 20,
                I18n.format("gui.rsaudio.airlock.cancel")));
        this.buttonList.add(new GuiButton(ID_DONE, x3 + (btnW + gap) * 2, y3, btnW, 20,
                I18n.format("gui.rsaudio.airlock.done")));
    }

    private void readFromTile() {
        TileAirlockController tile = getTile();
        if (tile == null) {
            return;
        }
        this.channel = tile.getChannel();
        this.deconTicks = tile.getDeconTicks();
        this.lockTimeout = tile.getLockTimeout();
        this.announce = tile.isAnnounce();
        this.state = tile.getCore().getState();
        this.deconRemaining = tile.getCore().getDeconRemaining();
        this.doorCount = tile.getDoorCount();
        this.sidesText = tile.getSidesText();
        this.loaded = true;
    }

    // ------------------------------------------------------------------ 按钮文字

    private String announceLabel() {
        return I18n.format(announce ? "gui.rsaudio.airlock.announce_on"
                : "gui.rsaudio.airlock.announce_off");
    }

    private void refreshLabels() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_ANNOUNCE) {
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
                apply();
                break;
            case ID_CH_PLUS:
                this.channel = TerminalCore.stepChannel(this.channel, +1);
                apply();
                break;
            case ID_ANNOUNCE:
                this.announce = !this.announce;
                apply();
                break;
            case ID_DECON_MINUS:
                this.deconTicks = AirlockCore.clampDecon(this.deconTicks - DECON_STEP);
                apply();
                break;
            case ID_DECON_PLUS:
                this.deconTicks = AirlockCore.clampDecon(this.deconTicks + DECON_STEP);
                apply();
                break;
            case ID_TIMEOUT_MINUS:
                this.lockTimeout = AirlockCore.clampTimeout(this.lockTimeout - TIMEOUT_STEP);
                apply();
                break;
            case ID_TIMEOUT_PLUS:
                this.lockTimeout = AirlockCore.clampTimeout(this.lockTimeout + TIMEOUT_STEP);
                apply();
                break;
            case ID_START:
                send(PacketSetAirlock.ACTIVATE);
                this.status = I18n.format("gui.rsaudio.airlock.start_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_CANCEL:
                send(PacketSetAirlock.CANCEL);
                this.status = I18n.format("gui.rsaudio.airlock.cancel_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    private void apply() {
        send(PacketSetAirlock.APPLY);
        refreshLabels();
        this.status = I18n.format("gui.rsaudio.airlock.applied", describe());
        this.statusColor = 0x90FF90;
    }

    private void send(byte action) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketSetAirlock(this.pos, action,
                    this.channel, this.deconTicks, this.lockTimeout, this.announce));
        } catch (Throwable t) {
            RsAudio.warn("气闸设置发送失败（可能已断开连接）: " + t);
        }
    }

    private TileAirlockController getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileAirlockController ? (TileAirlockController) te : null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.loaded) {
            readFromTile();
            refreshLabels();
            return;
        }
        TileAirlockController tile = getTile();
        if (tile == null) {
            return;
        }
        if (tile.getChannel() != this.channel || tile.getDeconTicks() != this.deconTicks
                || tile.getLockTimeout() != this.lockTimeout || tile.isAnnounce() != this.announce
                || tile.getCore().getState() != this.state
                || tile.getCore().getDeconRemaining() != this.deconRemaining
                || tile.getDoorCount() != this.doorCount
                || !tile.getSidesText().equals(this.sidesText)) {
            readFromTile();
            refreshLabels();
        }
    }

    // ------------------------------------------------------------------ 绘制

    /** 参数那一行 */
    private String describe() {
        return I18n.format("gui.rsaudio.airlock.params",
                I18n.format("gui.rsaudio.airlock.channel_value", channel),
                I18n.format("gui.rsaudio.airlock.decon_value", deconTicks),
                I18n.format("gui.rsaudio.airlock.timeout_value", lockTimeout));
    }

    /** 状态行的文字 + 配色 */
    private String[] stateLine() {
        switch (state) {
            case AirlockCore.STATE_LOCKING:
                return new String[] { I18n.format("gui.rsaudio.airlock.state_locking"), "0xFFD040" };
            case AirlockCore.STATE_DECON:
                return new String[] { I18n.format("gui.rsaudio.airlock.state_decon", deconRemaining),
                        "0xFFD040" };
            case AirlockCore.STATE_UNLOCKING:
                return new String[] { I18n.format("gui.rsaudio.airlock.state_unlocking"), "0x90FF90" };
            default:
                return new String[] { I18n.format("gui.rsaudio.airlock.state_idle"), "0xAAAAAA" };
        }
    }

    /** 配对那一行：配齐了报两扇门的位置，没配齐报原因 */
    private String pairingLine() {
        if (doorCount >= AirlockCore.SIDES && !sidesText.isEmpty()) {
            return I18n.format("gui.rsaudio.airlock.sides", sidesText);
        }
        return I18n.format("gui.rsaudio.airlock.sides_none", doorCount, AirlockCore.SIDES);
    }

    private static int colorOf(String hex) {
        // 同 GuiBlastDoor.colorOf：颜色字符串带 0x 前缀，用 Integer.decode 解析。
        return Integer.decode(hex);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.airlock.title"), cx, 8, 0xFFFFFF);

        String[] line = stateLine();
        drawCenteredString(this.fontRenderer, line[0], cx, 20, colorOf(line[1]));
        drawCenteredString(this.fontRenderer, pairingLine(), cx, 34,
                doorCount >= AirlockCore.SIDES ? 0xC0C0FF : 0xFF8060);
        drawCenteredString(this.fontRenderer, describe(), cx, 48, 0xE0E0E0);

        String tileStatus = getTile() == null ? "" : getTile().getStatus();
        String hint;
        int color;
        if (!this.status.isEmpty()) {
            hint = this.status;
            color = this.statusColor;
        } else if (!tileStatus.isEmpty()) {
            hint = tileStatus;
            color = 0xFFFFFF;
        } else {
            hint = I18n.format("gui.rsaudio.airlock.hint");
            color = 0xAAAAAA;
        }
        // 最多两行：按钮框从 rowTop 起（标准 240 高窗口下是 104），这两行从 62 起、
        // 每行 10 —— 到 82 结束，留出安全距离
        int lineY = 62;
        int drawn = 0;
        for (String piece : this.fontRenderer.listFormattedStringToWidth(hint, this.width - 30)) {
            if (drawn >= 2) {
                break;
            }
            drawCenteredString(this.fontRenderer, piece, cx, lineY, color);
            lineY += 10;
            drawn++;
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

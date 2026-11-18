package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetRemotePanel;
import com.rsaudio.panel.PanelCore;
import com.rsaudio.tile.TileRemotePanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 远程控制面板的设置界面：<b>潜行右键</b>面板打开。
 *
 * <h3>它只改两样东西，但这两样决定了面板在干什么</h3>
 * <ul>
 *   <li><b>模式</b>：控制 DMR 反应堆，还是控制大门。同一个方块两种用途，
 *       因为两者需要的布局完全一样（6 个按钮），做成两个方块除了多两份资源没有收益；</li>
 *   <li><b>频道</b>：门模式下筛"哪一扇门"。和防核门 / 门禁终端 / 气闸控制器是同一套 1..16。</li>
 * </ul>
 *
 * <h3>★ 这个界面同时承担"图例"的职责</h3>
 * 世界里的面板只画颜色和灯，不画文字（原因见 {@code PanelRenderer} 的类注释：
 * 往方块面上贴文字的那套 GL 变换在这里没法验证，而写错了是"看着挺像"的错）。
 * 所以"第几格是什么"必须有个权威的地方说清楚 —— 就是这里。
 * 面板装好之后潜行右键看一眼，六格的对照关系一眼就记住了。
 *
 * <h3>参数是"整体覆盖"的</h3>
 * 和门禁终端 / 气闸控制器一致：每次改动都把模式 + 频道整套发过去。
 * 界面上的值就是玩家的意图，不存在"只发了变化的那一项"的说法。
 */
public class GuiRemotePanel extends GuiScreen {

    private static final int ID_MODE = 0;
    private static final int ID_CH_MINUS = 1;
    private static final int ID_CH_PLUS = 2;
    private static final int ID_DONE = 3;

    private final BlockPos pos;
    private int mode = PanelCore.MODE_DMR;
    private int channel = PanelCore.CHANNEL_MIN;

    /** 服务端同步过来的"接没接上"，界面只读不猜 */
    private boolean linked;
    private String targetText = "";
    private String status = "";
    private boolean loaded;

    public GuiRemotePanel(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();

        int cx = this.width / 2;
        int btnW = Math.min(96, Math.max(40, (this.width - 16 - 3 * 4) / 4));
        int gap = 4;
        int y = this.height - 32;
        int x = cx - (btnW * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_MODE, x, y, btnW, 20, modeLabel()));
        this.buttonList.add(new GuiButton(ID_CH_MINUS, x + (btnW + gap), y, btnW, 20,
                I18n.format("gui.rsaudio.panel.ch_minus")));
        this.buttonList.add(new GuiButton(ID_CH_PLUS, x + (btnW + gap) * 2, y, btnW, 20,
                I18n.format("gui.rsaudio.panel.ch_plus")));
        this.buttonList.add(new GuiButton(ID_DONE, x + (btnW + gap) * 3, y, btnW, 20,
                I18n.format("gui.rsaudio.panel.done")));
    }

    private void readFromTile() {
        TileRemotePanel tile = getTile();
        if (tile == null) {
            return;
        }
        this.mode = tile.getMode();
        this.channel = tile.getChannel();
        this.linked = tile.isLinked();
        this.targetText = tile.getTargetText();
        this.status = tile.getStatus();
        this.loaded = true;
    }

    // ------------------------------------------------------------------ 文字

    private String modeLabel() {
        return I18n.format("gui.rsaudio.panel.mode_toggle",
                I18n.format(mode == PanelCore.MODE_DMR
                        ? "gui.rsaudio.panel.mode.dmr" : "gui.rsaudio.panel.mode.gate"));
    }

    private void refreshLabels() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_MODE) {
                button.displayString = modeLabel();
            }
        }
    }

    /** 一行图例：把这一排三个按钮的文案连起来 */
    private String legend(int row) {
        int base = row * PanelCore.COLS;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < PanelCore.COLS; i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(I18n.format(PanelCore.labelKey(mode, base + i)));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_MODE:
                this.mode = this.mode == PanelCore.MODE_DMR
                        ? PanelCore.MODE_GATE : PanelCore.MODE_DMR;
                apply();
                break;
            case ID_CH_MINUS:
                this.channel = stepChannel(-1);
                apply();
                break;
            case ID_CH_PLUS:
                this.channel = stepChannel(+1);
                apply();
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    /** 频道在 1..16 之间循环。0 在别处表示"没设频道"，这里不提供 */
    private int stepChannel(int delta) {
        int next = channel + delta;
        if (next < PanelCore.CHANNEL_MIN) {
            next = PanelCore.CHANNEL_MAX;
        } else if (next > PanelCore.CHANNEL_MAX) {
            next = PanelCore.CHANNEL_MIN;
        }
        return PanelCore.clampChannel(next);
    }

    private void apply() {
        refreshLabels();
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(
                    new PacketSetRemotePanel(this.pos, this.mode, this.channel));
        } catch (Throwable t) {
            RsAudio.warn("远程面板设置发送失败（可能已断开连接）: " + t);
        }
    }

    private TileRemotePanel getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileRemotePanel ? (TileRemotePanel) te : null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.loaded) {
            readFromTile();
            refreshLabels();
            return;
        }
        TileRemotePanel tile = getTile();
        if (tile == null) {
            return;
        }
        // 服务端那边可能刚刚重解析出（或者丢掉了）目标 —— 一有变化就整份重读
        if (tile.getMode() != this.mode || tile.getChannel() != this.channel
                || tile.isLinked() != this.linked
                || !tile.getTargetText().equals(this.targetText)
                || !tile.getStatus().equals(this.status)) {
            readFromTile();
            refreshLabels();
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.panel.title"), cx, 6, 0xFFFFFF);
        drawCenteredString(this.fontRenderer, I18n.format(PanelCore.titleKey(mode)), cx, 18, 0xFFD060);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.panel.params",
                        I18n.format(mode == PanelCore.MODE_DMR
                                ? "gui.rsaudio.panel.mode.dmr" : "gui.rsaudio.panel.mode.gate"),
                        channel),
                cx, 32, 0xE0E0E0);

        // 目标行：接上了报坐标，没接上就明说 —— 这是玩家最需要的一行
        if (linked && !targetText.isEmpty()) {
            drawCenteredString(this.fontRenderer,
                    I18n.format("gui.rsaudio.panel.target", targetText), cx, 46, 0x90FF90);
        } else if (mode == PanelCore.MODE_DMR) {
            drawCenteredString(this.fontRenderer,
                    I18n.format("gui.rsaudio.panel.target_none_dmr", PanelCore.SEARCH_RADIUS),
                    cx, 46, 0xFF8060);
        } else {
            drawCenteredString(this.fontRenderer,
                    I18n.format("gui.rsaudio.panel.target_none_gate", channel, PanelCore.SEARCH_RADIUS),
                    cx, 46, 0xFF8060);
        }

        // 图例：面板本体不写字，所以这四行就是"第几格是什么"的权威说明
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.panel.legend"), cx, 66, 0xAAAAAA);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.panel.legend_row", 1, legend(0)), cx, 78, 0xC0C0FF);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.panel.legend_row", 2, legend(1)), cx, 90, 0xC0C0FF);
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.panel.press_hint"), cx, 104, 0x808080);

        if (!status.isEmpty()) {
            drawCenteredString(this.fontRenderer, status, cx, 120, 0xFFD060);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

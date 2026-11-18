package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockAlarmLight;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetAlarm;
import com.rsaudio.tile.TileAlarmLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 警报灯设置界面：爆闪频率 + 蜂鸣开关。
 *
 * <h3>颜色不是设置项</h3>
 * 六种颜色是六个独立方块（alarm_red / alarm_orange / ...），换色就是换方块，
 * 所以这个界面上只显示"你现在开的是哪盏"，不能改。
 *
 * <h3>参数为什么这么少</h3>
 * 只有两个整型/布尔量，服务端方块实体自己会用 {@code getUpdateTag} 把值同步回来，
 * 所以不需要专门的同步包 —— 界面读本地方块实体就行。
 */
public class GuiAlarm extends GuiScreen {

    private static final int ID_ALWAYS = 0;
    private static final int ID_MINUS_10 = 1;
    private static final int ID_MINUS_1 = 2;
    private static final int ID_PLUS_1 = 3;
    private static final int ID_PLUS_10 = 4;
    private static final int ID_BEEP = 5;
    private static final int ID_DONE = 6;

    /** 六种颜色的示意色块（只为界面好看，真值在方块上；顺序同 {@link BlockAlarmLight#COLOR_NAMES}） */
    private static final int[] SWATCH = {
            0xE03030, 0xE08020, 0xE0D020, 0x30C030, 0x3060E0, 0x9040E0,
    };

    private final BlockPos pos;
    private int period = TileAlarmLight.PERIOD_DEFAULT;
    private boolean beep = true;
    private int colorIndex;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    private boolean loaded;

    public GuiAlarm(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();

        int cx = this.width / 2;
        int w = 72;
        int gap = 6;
        int x = cx - (w * 5 + gap * 4) / 2;
        int y = this.height - 84;
        GuiButton minus10 = new GuiButton(ID_MINUS_10, x, y, w, 20,
                I18n.format("gui.rsaudio.alarm.minus10"));
        GuiButton minus1 = new GuiButton(ID_MINUS_1, x + (w + gap), y, w, 20,
                I18n.format("gui.rsaudio.alarm.minus1"));
        GuiButton plus1 = new GuiButton(ID_PLUS_1, x + (w + gap) * 2, y, w, 20,
                I18n.format("gui.rsaudio.alarm.plus1"));
        GuiButton plus10 = new GuiButton(ID_PLUS_10, x + (w + gap) * 3, y, w, 20,
                I18n.format("gui.rsaudio.alarm.plus10"));
        GuiButton always = new GuiButton(ID_ALWAYS, x + (w + gap) * 4, y, w, 20,
                I18n.format("gui.rsaudio.alarm.always"));
        this.buttonList.add(minus10);
        this.buttonList.add(minus1);
        this.buttonList.add(plus1);
        this.buttonList.add(plus10);
        this.buttonList.add(always);

        int w2 = 110;
        int gap2 = 8;
        int x2 = cx - (w2 * 2 + gap2) / 2;
        int y2 = this.height - 58;
        this.buttonList.add(new GuiButton(ID_BEEP, x2, y2, w2, 20, beepLabel()));
        this.buttonList.add(new GuiButton(ID_DONE, x2 + w2 + gap2, y2, w2, 20,
                I18n.format("gui.rsaudio.alarm.done")));
    }

    private String beepLabel() {
        return I18n.format(beep ? "gui.rsaudio.alarm.beep_on" : "gui.rsaudio.alarm.beep_off");
    }

    private void readFromTile() {
        TileAlarmLight tile = getTile();
        if (tile != null) {
            this.period = tile.getPeriod();
            this.beep = tile.isBeep();
            // 客户端还没收到方块实体的同步时不要用默认值覆盖已知状态
            this.loaded = true;
        }
        BlockPos base = this.pos;
        World world = Minecraft.getMinecraft().world;
        if (world != null) {
            net.minecraft.block.Block block = world.getBlockState(base).getBlock();
            if (block instanceof BlockAlarmLight) {
                this.colorIndex = ((BlockAlarmLight) block).getColorIndex();
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_ALWAYS:
                apply(TileAlarmLight.PERIOD_ALWAYS, this.beep);
                break;
            case ID_MINUS_10:
                apply(this.period - 10, this.beep);
                break;
            case ID_MINUS_1:
                apply(this.period - 1, this.beep);
                break;
            case ID_PLUS_1:
                apply(this.period + 1, this.beep);
                break;
            case ID_PLUS_10:
                apply(this.period + 10, this.beep);
                break;
            case ID_BEEP:
                apply(this.period, !this.beep);
                refreshBeepButton();
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    private void apply(int newPeriod, boolean newBeep) {
        int clamped = TileAlarmLight.clampPeriod(newPeriod);
        this.period = clamped;
        this.beep = newBeep;
        send(new PacketSetAlarm(this.pos, clamped, newBeep));
        this.status = I18n.format("gui.rsaudio.alarm.applied", periodText());
        this.statusColor = 0x90FF90;
    }

    private void refreshBeepButton() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_BEEP) {
                button.displayString = beepLabel();
                return;
            }
        }
    }

    private void send(PacketSetAlarm packet) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(packet);
        } catch (Throwable t) {
            RsAudio.warn("警报灯设置发送失败（可能已断开连接）: " + t);
        }
    }

    private TileAlarmLight getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileAlarmLight ? (TileAlarmLight) te : null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 服务端侧被改动（潜行右键快速开关蜂鸣）时跟着刷新，但要避开"本地刚点过、包还没回来"的瞬间
        if (!this.loaded) {
            readFromTile();
            refreshBeepButton();
            return;
        }
        TileAlarmLight tile = getTile();
        if (tile == null) {
            return;
        }
        if (tile.isBeep() != this.beep || tile.getPeriod() != this.period) {
            this.period = tile.getPeriod();
            this.beep = tile.isBeep();
            refreshBeepButton();
        }
    }

    // ------------------------------------------------------------------ 绘制

    private String colorKey() {
        int index = this.colorIndex < 0 || this.colorIndex >= BlockAlarmLight.COLOR_COUNT
                ? 0 : this.colorIndex;
        return "tile.rsaudio.alarm." + BlockAlarmLight.COLOR_NAMES[index] + ".name";
    }

    private String periodText() {
        if (this.period <= TileAlarmLight.PERIOD_ALWAYS) {
            return I18n.format("gui.rsaudio.alarm.always_text");
        }
        return I18n.format("gui.rsaudio.alarm.period_text",
                this.period, String.format(java.util.Locale.ROOT, "%.1f", this.period / 20.0D));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.alarm.title"), cx, 12, 0xFFFFFF);

        // 颜色行：色块 + 名字
        String colorName = I18n.format(colorKey());
        String colorLine = I18n.format("gui.rsaudio.alarm.color", colorName);
        int textWidth = this.fontRenderer.getStringWidth(colorLine);
        int lineX = cx - (textWidth + 14) / 2;
        int swatch = this.colorIndex < 0 || this.colorIndex >= SWATCH.length ? 0 : SWATCH[this.colorIndex];
        drawRect(lineX, 34, lineX + 10, 44, 0xFF000000);
        drawRect(lineX + 1, 35, lineX + 9, 43, 0xFF000000 | swatch);
        this.fontRenderer.drawStringWithShadow(colorLine, lineX + 14, 36, 0xFFFFFF);

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.alarm.current", periodText()),
                cx, 58, 0xFFFF80);

        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.alarm.beep_state",
                        I18n.format(this.beep ? "chat.rsaudio.alarm.beep_on" : "chat.rsaudio.alarm.beep_off")),
                cx, 74, this.beep ? 0x90FF90 : 0x909090);

        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.alarm.range", TileAlarmLight.PERIOD_MIN, TileAlarmLight.PERIOD_MAX),
                cx, 92, 0xAAAAAA);
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.alarm.beep_hint"),
                cx, 106, 0xAAAAAA);

        if (!this.status.isEmpty()) {
            drawCenteredString(this.fontRenderer, this.status, cx, this.height - 30, this.statusColor);
        } else {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.alarm.hint"),
                    cx, this.height - 30, 0xAAAAAA);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

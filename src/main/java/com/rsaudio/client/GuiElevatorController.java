package com.rsaudio.client;

import com.rsaudio.elevator.ElevatorSettings;
import com.rsaudio.elevator.ElevatorSpec;
import com.rsaudio.network.ElevatorSnapshot;
import com.rsaudio.network.PacketElevatorAction;
import com.rsaudio.network.PacketHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.Locale;

/**
 * 电梯控制柜界面：左边读数、右边一整块"呼叫按钮"面板。
 *
 * <h3>数据从哪来</h3>
 * 界面<b>不直接读方块实体</b>，而是读 {@link ClientElevatorState} 里那份服务端推来的快照。
 * 于是"还没收到快照"（刚开界面、区块刚加载）是个必须处理的正常状态 ——
 * 那时候所有按钮置灰 + 一行等待提示，而不是显示一堆 0 让人以为电梯在第一层待机。
 *
 * <h3>★ 为什么点完按钮不立刻改本地读数</h3>
 * 客户端不做乐观更新：读数永远等服务端回推。
 * 电梯是会<b>夹人/坠人</b>的机器，界面上显示的必须是真值；
 * "先显示已受理、0.2 秒后被真实状态打回去"在电梯上会让玩家做错判断。
 *
 * <h3>★ 层数多了怎么办</h3>
 * 层表最长 {@link ElevatorSpec#MAX_FLOORS} 条，这里按
 * {@link #MAX_BUTTON_FLOORS} 条一屏排（4 列 × 8 行）。
 * 超过一屏的部分不打按钮，而是画一行"还有 N 层未显示" ——
 * 宁可明说，也不要默默少画几层让玩家以为电梯只有 12 层。
 */
public class GuiElevatorController extends GuiScreen {

    private static final int W = 300;
    private static final int H = 250;
    /** 一屏最多画多少个楼层按钮（4 列 × 8 行） */
    private static final int MAX_BUTTON_FLOORS = 32;
    private static final int BTN_W = 36;
    private static final int BTN_H = 16;
    private static final int GRID_COLS = 4;

    private static final int ID_HOLD_DOOR = 1;
    private static final int ID_CLOSE_DOOR = 2;
    private static final int ID_CLEAR = 3;
    private static final int ID_RESCAN = 4;
    /** 楼层按钮的 id 段 */
    private static final int ID_FLOOR_BASE = 100;

    private final BlockPos pos;

    private GuiButton btnHold;
    private GuiButton btnClose;
    private GuiButton btnClear;
    private GuiButton btnRescan;
    private final GuiButton[] floorButtons = new GuiButton[MAX_BUTTON_FLOORS];

    private int guiLeft;
    private int guiTop;

    public GuiElevatorController(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    private ElevatorSnapshot snap() {
        return ClientElevatorState.get(pos);
    }

    // ================================================================== 搭建

    @Override
    public void initGui() {
        this.buttonList.clear();
        guiLeft = (this.width - W) / 2;
        guiTop = (this.height - H) / 2;

        int x = guiLeft + 140;
        int y = guiTop + 26;
        for (int i = 0; i < MAX_BUTTON_FLOORS; i++) {
            int col = i % GRID_COLS;
            int row = i / GRID_COLS;
            GuiButton b = new GuiButton(ID_FLOOR_BASE + i,
                    x + col * (BTN_W + 2), y + row * (BTN_H + 2), BTN_W, BTN_H, "-");
            floorButtons[i] = b;
            buttonList.add(b);
        }

        int by = guiTop + 26 + 8 * (BTN_H + 2) + 6;
        btnHold = new GuiButton(ID_HOLD_DOOR, guiLeft + 140, by, 70, 18,
                I18n.format("gui.rsaudio.elevator.hold_door"));
        btnClose = new GuiButton(ID_CLOSE_DOOR, guiLeft + 212, by, 70, 18,
                I18n.format("gui.rsaudio.elevator.close_door"));
        btnClear = new GuiButton(ID_CLEAR, guiLeft + 140, by + 22, 70, 18,
                I18n.format("gui.rsaudio.elevator.clear"));
        btnRescan = new GuiButton(ID_RESCAN, guiLeft + 212, by + 22, 70, 18,
                I18n.format("gui.rsaudio.elevator.rescan"));
        buttonList.add(btnHold);
        buttonList.add(btnClose);
        buttonList.add(btnClear);
        buttonList.add(btnRescan);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        ElevatorSnapshot s = snap();
        boolean ok = s != null;

        for (int i = 0; i < MAX_BUTTON_FLOORS; i++) {
            GuiButton b = floorButtons[i];
            if (b == null) {
                continue;
            }
            boolean has = ok && i < s.floorCount();
            b.visible = has;
            b.enabled = has;
            if (has) {
                // 已按下（在等电梯）与普通层用不同的前缀 —— 一眼能看出"我叫了哪几层"
                String mark = s.hasCall(i) ? "* " : "  ";
                b.displayString = mark + (i + 1) + " (" + s.floorIndex(i) + ")";
            }
        }

        if (btnHold != null) {
            btnHold.enabled = ok && !s.hasFault();
        }
        if (btnClose != null) {
            btnClose.enabled = ok && !s.hasFault();
        }
        if (btnClear != null) {
            btnClear.enabled = ok && s.callCount() > 0;
        }
        // ★ "重扫井道"必须在<b>有故障时也点得动</b>：那正是它存在的意义
        //   （玩家刚把井道补好，不想等 20 刻）。置灰了就等于把这条路堵死。
        if (btnRescan != null) {
            btnRescan.enabled = ok;
        }
    }

    // ================================================================== 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawRect(guiLeft, guiTop, guiLeft + W, guiTop + H, 0xC0101010);
        drawRect(guiLeft, guiTop, guiLeft + W, guiTop + 20, 0xFF2A3550);
        drawRect(guiLeft + 132, guiTop + 22, guiLeft + 133, guiTop + H - 8, 0xFF404A60);

        ElevatorSnapshot s = snap();
        String title = I18n.format("gui.rsaudio.elevator.title");
        if (s != null) {
            title = title + "  -  " + I18n.format("elevator.type." + s.typeKey());
        }
        fontRenderer.drawStringWithShadow(title, guiLeft + 8, guiTop + 6, 0xFFFFFF);

        if (s == null) {
            fontRenderer.drawString(I18n.format("gui.rsaudio.elevator.waiting"),
                    guiLeft + 8, guiTop + 40, 0xFFA0A0A0);
        } else {
            drawReadouts(s);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawReadouts(ElevatorSnapshot s) {
        int x = guiLeft + 8;
        int y = guiTop + 28;
        int step = 12;

        line(x, y, "gui.rsaudio.elevator.type", I18n.format("elevator.type." + s.typeKey()), 0xFFE0E0E0);
        y += step;
        int stateColor = s.hasFault() ? 0xFFFF6060
                : (s.state == ElevatorSpec.ST_IDLE ? 0xFFA0A0A0 : 0xFF60FF60);
        line(x, y, "gui.rsaudio.elevator.state", I18n.format("elevator.state." + s.stateKey()), stateColor);
        y += step;
        line(x, y, "gui.rsaudio.elevator.floor", (s.currentSlot() >= 0
                ? String.valueOf(s.currentSlot() + 1) : "-"), 0xFFE0E0E0);
        y += step;
        // ★ 这里读的是**字段**不是取值方法：ElevatorSnapshot 是个不可变数据包，
        //   字段全是 public final（见它的类注释）。别的一半用字段一半用方法 ——
        //   那正是编译期报 "cannot find symbol: method targetSlot()" 的原因。
        line(x, y, "gui.rsaudio.elevator.target", (s.targetSlot >= 0
                ? String.valueOf(s.targetSlot + 1) : I18n.format("gui.rsaudio.elevator.none")), 0xFFE0E0E0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.index", String.valueOf(s.cellIndex), 0xFFB0B0B0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.speed",
                String.format(Locale.ROOT, "%.2f", s.vel), 0xFFB0B0B0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.door",
                String.format(Locale.ROOT, "%d%%", (int) Math.round(s.doorAmount * 100.0D)), 0xFFB0B0B0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.trips", String.valueOf(s.trips), 0xFFB0B0B0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.floors", String.valueOf(s.floorCount()), 0xFFB0B0B0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.calls", String.valueOf(s.callCount()), 0xFFB0B0B0);
        y += step;
        line(x, y, "gui.rsaudio.elevator.shaft",
                s.structureOk ? I18n.format("gui.rsaudio.elevator.shaft_ok")
                        : I18n.format("gui.rsaudio.elevator.shaft_bad"),
                s.structureOk ? 0xFF60FF60 : 0xFFFF6060);
        y += step;
        if (s.hasFault()) {
            // ★ 故障必须自报成因：这里显示的是"修哪儿"，不是一句"出故障了"
            line(x, y, "gui.rsaudio.elevator.fault",
                    I18n.format("elevator.fault." + s.faultKey()), 0xFFFF6060);
            y += step;
        }
        if (s.shaftMissing > 0) {
            line(x, y, "gui.rsaudio.elevator.missing",
                    String.valueOf(s.shaftMissing), 0xFFFFC060);
            y += step;
        }
        if (s.floorCount() > MAX_BUTTON_FLOORS) {
            fontRenderer.drawString(I18n.format("gui.rsaudio.elevator.more_floors",
                    s.floorCount() - MAX_BUTTON_FLOORS), x, y, 0xFFFFC060);
        }
        // 井道是斜面时明确写一句：玩家会看到轿厢同时往上和往旁边走，不说明会以为有 bug
        if (ElevatorSpec.isInclined(s.type)) {
            fontRenderer.drawString(I18n.format("gui.rsaudio.elevator.inclined_note"),
                    x, guiTop + H - 20, 0xFF8090C0);
        }
    }

    private void line(int x, int y, String key, String value, int color) {
        fontRenderer.drawString(I18n.format(key) + ": " + value, x, y, color);
    }

    // ================================================================== 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) {
            return;
        }
        int id = button.id;
        if (id >= ID_FLOOR_BASE) {
            int slot = id - ID_FLOOR_BASE;
            ElevatorSnapshot s = snap();
            if (s == null || slot < 0 || slot >= s.floorCount()) {
                return;                        // ★ 服务端还会再挡一次，这里只是别发垃圾包
            }
            send(PacketElevatorAction.ACT_CALL, slot);
            return;
        }
        switch (id) {
            case ID_HOLD_DOOR:
                send(PacketElevatorAction.ACT_HOLD_DOOR, 0);
                break;
            case ID_CLOSE_DOOR:
                send(PacketElevatorAction.ACT_CLOSE_DOOR, 0);
                break;
            case ID_CLEAR:
                send(PacketElevatorAction.ACT_CLEAR_CALLS, 0);
                break;
            case ID_RESCAN:
                send(PacketElevatorAction.ACT_RESCAN, 0);
                break;
            default:
                break;
        }
    }

    private void send(int action, int arg) {
        PacketHandler.INSTANCE.sendToServer(new PacketElevatorAction(pos, action, arg));
    }
}

package com.rsaudio.client;

import com.rsaudio.f9.F9Core;
import com.rsaudio.f9.F9Spec;
import com.rsaudio.network.F9Snapshot;
import com.rsaudio.network.PacketF9Action;
import com.rsaudio.network.PacketHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/**
 * F9 控制柜界面：左边一排读数 + 右边一整块操作面板。
 *
 * <h3>数据从哪来</h3>
 * 界面<b>不直接读方块实体</b>，而是读 {@link ClientF9State} 里那份服务端推来的快照。
 * 于是"没收到过快照"（刚开界面、区块刚加载）是个必须处理的正常状态 ——
 * 那种时候所有按钮都置灰，而不是显示一堆 0 让人以为机器坏了。
 *
 * <h3>为什么点完按钮不立刻改本地读数</h3>
 * 客户端不做乐观更新：读数永远等服务端回推。理由是这台机器<b>会把人炸死</b>，
 * 界面上显示的必须是真值；"先显示按成功、0.2 秒后被真实状态打回去"在一个
 * 正在爆堆的面板上会非常致命。
 *
 * <h3>★ 拉杆是"按住"而不是"点一下"</h3>
 * 内核的 {@code moveRods(dir)} 语义是"这一 tick 之后还接着走一秒"，
 * 所以"按住"在 tick 粒度上就是<b>每 tick 都再挂一次</b>。这里用
 * {@link #holdDir} + {@link #holdTimer} 实现：按下时立刻发一次，
 * 之后每 {@link #HOLD_RESEND_TICKS} tick 补发一次，松开（或关界面）时发
 * {@link PacketF9Action#ACT_RELEASE_ROD}。
 * <p>
 * ★ 少了"松手"这一发，开了"连续移动"之后棒会一直走到插入度撞到 0 或 100 才停 ——
 * 玩家会以为按键卡住了。所以 {@link #onGuiClosed()} 里也必须补发。
 */
public class GuiF9Controller extends GuiScreen {

    private static final int W = 310;
    private static final int H = 258;
    /** 按住拉杆时每几 tick 补发一次"继续移动"。太小会刷包，太大会一顿一顿的 */
    private static final int HOLD_RESEND_TICKS = 2;

    private static final int ID_START = 1;
    private static final int ID_SYNC = 2;
    private static final int ID_DESYNC = 3;
    private static final int ID_SHUTDOWN = 4;

    private static final int ID_SUB_BASE = 100;
    /** 转换器 / 泄压阀的按钮 id 段（两者各 4 个） */
    private static final int ID_CONV_BASE = 200;
    private static final int ID_VALVE_BASE = 210;
    /** 单根控制棒的选择按钮 */
    private static final int ID_ROD_BASE = 300;
    /** "全部控制棒"的选择按钮 */
    private static final int ID_ROD_ALL = 320;
    /** 分区选择按钮（0..2） */
    private static final int ID_SECTOR_BASE = 330;
    /** 分区选择里的"全部"（= -1） */
    private static final int ID_SECTOR_ALL = 340;

    private static final int ID_HOLD_UP = 400;
    private static final int ID_HOLD_DOWN = 401;
    private static final int ID_HOLD_STOP = 402;

    /**
     * 界面上要画的子系统开关，下标即按钮 id 的偏移、也是语言键
     * {@code gui.rsaudio.f9.sub.<下标>} 的后缀。
     * <p>
     * ★ 后加的位<b>只能往后排</b>：下标直接当语言键用，往中间插一位会让所有
     * 已有的语言键整体错位一格（表现是"热循环按钮写着'速度 2x'"）。
     */
    private static final int[] SUB_BITS = {
            F9Spec.SUB_SAFETY,
            F9Spec.SUB_CONTINUOUS_TRANSIT,
            F9Spec.SUB_ROD_SPEED_2X,
            F9Spec.SUB_THERMAL_LOOP,
    };

    private static final int C_NORMAL = 0xE0E0E0;
    private static final int C_DIM = 0x9A9A9A;
    private static final int C_OK = 0x55FF55;
    private static final int C_WARN = 0xFFAA00;
    private static final int C_BAD = 0xFF5555;
    private static final int C_SEL = 0x66CCFF;

    private final BlockPos pos;

    private GuiButton btnStart;
    private GuiButton btnSync;
    private GuiButton btnDesync;
    private GuiButton btnShutdown;
    private GuiButton btnHoldUp;
    private GuiButton btnHoldDown;
    private GuiButton btnHoldStop;
    private final GuiButton[] subButtons = new GuiButton[SUB_BITS.length];
    private final GuiButton[] convButtons = new GuiButton[F9Spec.CONVERTER_COUNT];
    private final GuiButton[] valveButtons = new GuiButton[F9Spec.VALVE_COUNT];
    private final GuiButton[] rodButtons = new GuiButton[F9Spec.ROD_COUNT];
    private GuiButton btnRodAll;
    private final GuiButton[] sectorButtons = new GuiButton[F9Spec.SECTOR_COUNT];
    private GuiButton btnSectorAll;

    /** 拉杆当前按住的方向（+1 拔 / -1 插 / 0 = 没按） */
    private int holdDir;
    /** 补发计时（tick） */
    private int holdTimer;

    private int x0;
    private int y0;

    public GuiF9Controller(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ 布局

    @Override
    public void initGui() {
        x0 = (width - W) / 2;
        y0 = (height - H) / 2;
        buttonList.clear();

        int rx = x0 + 158;

        btnStart = new GuiButton(ID_START, rx, y0 + 20, 68, 18, "");
        btnSync = new GuiButton(ID_SYNC, rx + 74, y0 + 20, 68, 18, "");
        btnDesync = new GuiButton(ID_DESYNC, rx, y0 + 42, 68, 18, "");
        btnShutdown = new GuiButton(ID_SHUTDOWN, rx + 74, y0 + 42, 68, 18, "");
        buttonList.add(btnStart);
        buttonList.add(btnSync);
        buttonList.add(btnDesync);
        buttonList.add(btnShutdown);

        for (int i = 0; i < SUB_BITS.length; i++) {
            int col = i % 2;
            int row = i / 2;
            subButtons[i] = new GuiButton(ID_SUB_BASE + i, rx + col * 74, y0 + 68 + row * 22, 68, 18, "");
            buttonList.add(subButtons[i]);
        }

        for (int i = 0; i < convButtons.length; i++) {
            convButtons[i] = new GuiButton(ID_CONV_BASE + i, rx + i * 36, y0 + 116, 34, 18, "");
            buttonList.add(convButtons[i]);
        }
        for (int i = 0; i < valveButtons.length; i++) {
            valveButtons[i] = new GuiButton(ID_VALVE_BASE + i, rx + i * 36, y0 + 138, 34, 18, "");
            buttonList.add(valveButtons[i]);
        }

        // 控制棒选择：第一行 [全部][1][2][3][4]，第二行 [5][6][7][8][9]
        btnRodAll = new GuiButton(ID_ROD_ALL, rx, y0 + 164, 44, 18, "");
        buttonList.add(btnRodAll);
        for (int i = 0; i < rodButtons.length; i++) {
            int col = i % 5;
            int row = i / 5;
            int bx = row == 0 ? rx + 46 + col * 26 : rx + col * 26;
            rodButtons[i] = new GuiButton(ID_ROD_BASE + i, bx, y0 + 164 + row * 22, 24, 18,
                    String.valueOf(i + 1));
            buttonList.add(rodButtons[i]);
        }

        btnSectorAll = new GuiButton(ID_SECTOR_ALL, rx, y0 + 208, 44, 18, "");
        buttonList.add(btnSectorAll);
        for (int i = 0; i < sectorButtons.length; i++) {
            sectorButtons[i] = new GuiButton(ID_SECTOR_BASE + i, rx + 46 + i * 26, y0 + 208, 24, 18,
                    String.valueOf(i + 1));
            buttonList.add(sectorButtons[i]);
        }

        btnHoldUp = new GuiButton(ID_HOLD_UP, rx, y0 + 230, 46, 20, "");
        btnHoldDown = new GuiButton(ID_HOLD_DOWN, rx + 48, y0 + 230, 46, 20, "");
        btnHoldStop = new GuiButton(ID_HOLD_STOP, rx + 96, y0 + 230, 46, 20, "");
        buttonList.add(btnHoldUp);
        buttonList.add(btnHoldDown);
        buttonList.add(btnHoldStop);

        holdDir = 0;
        holdTimer = 0;
        updateButtonLabels();
    }

    private static String t(String key) {
        return I18n.format(key);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // ★ 按住拉杆：这里补发"继续移动"。send 必须在按住期间持续发生，
        //   因为内核那边每一次调用只保证"再走一秒"。
        if (holdDir != 0) {
            if (++holdTimer >= HOLD_RESEND_TICKS) {
                holdTimer = 0;
                send(PacketF9Action.ACT_MOVE_ROD, holdDir);
            }
        }
        updateButtonLabels();
    }

    private void updateButtonLabels() {
        F9Snapshot s = ClientF9State.get(pos);
        boolean ready = s != null;
        boolean hot = ready && F9Spec.isHot(s.state);
        boolean terminal = ready && F9Spec.isTerminal(s.state);

        for (int i = 0; i < subButtons.length; i++) {
            boolean on = ready && s.hasSub(SUB_BITS[i]);
            subButtons[i].displayString = t("gui.rsaudio.f9.sub." + i) + " "
                    + t(on ? "gui.rsaudio.f9.on" : "gui.rsaudio.f9.off");
            // 热循环被安全系统锁着（内核会拒绝并报 thermal_locked）；
            // 这里不提前置灰 —— 玩家点一下、收到那句"安全系统锁住了热循环"比按钮灰着更有信息量。
            subButtons[i].enabled = ready && !terminal;
        }

        for (int i = 0; i < convButtons.length; i++) {
            convButtons[i].enabled = ready && !terminal;
        }
        for (int i = 0; i < valveButtons.length; i++) {
            valveButtons[i].enabled = ready && !terminal;
        }

        btnRodAll.displayString = t("gui.rsaudio.f9.rod_all");
        btnRodAll.enabled = ready;
        for (int i = 0; i < rodButtons.length; i++) {
            rodButtons[i].enabled = ready;
        }
        btnSectorAll.displayString = t("gui.rsaudio.f9.sector_all");
        btnSectorAll.enabled = ready;
        for (GuiButton sectorButton : sectorButtons) {
            sectorButton.enabled = ready;
        }

        btnStart.displayString = t("gui.rsaudio.f9.start");
        btnStart.enabled = ready && s.state == F9Spec.ST_COLD;
        btnSync.displayString = t("gui.rsaudio.f9.sync");
        // 同步只在"运行中"才有意义；到底能不能同步由服务端判（四道关卡各有拒绝原因）
        btnSync.enabled = ready && s.state == F9Spec.ST_RUNNING;
        btnDesync.displayString = t("gui.rsaudio.f9.desync");
        btnDesync.enabled = ready && s.state == F9Spec.ST_SYNCED;
        btnShutdown.displayString = t("gui.rsaudio.f9.shutdown");
        btnShutdown.enabled = ready && (hot || s.state == F9Spec.ST_SYNCED);

        btnHoldUp.displayString = t("gui.rsaudio.f9.hold_up");
        btnHoldDown.displayString = t("gui.rsaudio.f9.hold_down");
        btnHoldStop.displayString = t("gui.rsaudio.f9.hold_stop");
        // 拉杆只在"热着"的时候动得了（内核 moveRods 的前置）
        btnHoldUp.enabled = ready && hot;
        btnHoldDown.enabled = ready && hot;
        btnHoldStop.enabled = ready && hot;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        // 注意 drawRect 的颜色是 ARGB：透明度在高 8 位（0xC0 = 半透明）
        drawRect(x0 - 2, y0 - 2, x0 + W + 2, y0 + H + 2, 0xC0000000);
        drawRect(x0, y0, x0 + W, y0 + H, 0xC0282828);
        // 中间那条竖分割线，把"读数区"和"操作区"分开
        drawRect(x0 + 152, y0 + 16, x0 + 153, y0 + H - 6, 0x60FFFFFF);

        fontRenderer.drawString(t("gui.rsaudio.f9.title"), x0 + 8, y0 + 6, C_NORMAL);

        F9Snapshot s = ClientF9State.get(pos);
        if (s == null) {
            fontRenderer.drawString(t("gui.rsaudio.f9.waiting"), x0 + 8, y0 + 22, C_WARN);
        } else {
            drawReadouts(s);
            drawRods(s);
        }
        // 操作区的小标签（按钮本身只有开关状态，得标一下这一行是什么）
        fontRenderer.drawString(t("gui.rsaudio.f9.converters"), x0 + 158, y0 + 106, C_DIM);
        fontRenderer.drawString(t("gui.rsaudio.f9.valves"), x0 + 158, y0 + 128, C_DIM);
        fontRenderer.drawString(t("gui.rsaudio.f9.rods"), x0 + 158, y0 + 154, C_DIM);
        fontRenderer.drawString(t("gui.rsaudio.f9.sectors"), x0 + 158, y0 + 198, C_DIM);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawReadouts(F9Snapshot s) {
        int x = x0 + 8;
        int y = y0 + 20;
        int step = 10;

        line(x, y, t("gui.rsaudio.f9.state"), t("f9.state." + s.stateName), stateColor(s.state));
        y += step;
        line(x, y, t("gui.rsaudio.f9.temp"), f(s.tempC) + " °C", tempColor(s.tempC));
        y += step;
        line(x, y, t("gui.rsaudio.f9.psi"), f(s.psi) + " PSI", psiColor(s.psi));
        y += step;
        // ★ 中子通量平时恒为 0，只有"正在拔棒"时才涨 —— 界面上把它标红的意义是
        //   "你正在拔棒"，而不是"这台机器出问题了"。
        line(x, y, t("gui.rsaudio.f9.flux"), f(s.flux) + " %",
                s.flux >= F9Spec.FLUX_SCRAM ? C_BAD : (s.flux > 0.0 ? C_WARN : C_OK));
        y += step;
        line(x, y, t("gui.rsaudio.f9.kw"), s.kw + " kW", kwColor(s.kw));
        y += step;
        line(x, y, t("gui.rsaudio.f9.output"), s.outputKw + " kW",
                s.outputKw > 0 ? C_OK : C_DIM);
        y += step;
        line(x, y, t("gui.rsaudio.f9.integrity"), f(s.integrity) + " %",
                s.integrity < 50.0 ? C_BAD : (s.integrity < 85.0 ? C_WARN : C_OK));
        y += step;
        // 同步仪：指针越接近 0（60 Hz）越能同步
        line(x, y, t("gui.rsaudio.f9.needle"), f(s.needle)
                + (s.lastNeedle != 0.0 ? "  (" + f(s.lastNeedle) + ")" : ""), needleColor(s.needle));
        y += step;
        line(x, y, t("gui.rsaudio.f9.sync_cond"),
                s.syncBlockers == 0 ? t("gui.rsaudio.f9.sync_ready") : String.valueOf(s.syncBlockers),
                s.syncBlockers == 0 ? C_OK : C_WARN);
        y += step;
        line(x, y, t("gui.rsaudio.f9.structure"),
                t(s.structureOk ? "gui.rsaudio.f9.struct_ok" : "gui.rsaudio.f9.struct_bad"),
                s.structureOk ? C_OK : C_BAD);
        y += step;

        if (F9Spec.isTerminal(s.state)) {
            line(x, y, t("gui.rsaudio.f9.regen"),
                    Math.max(0, s.regenTimer / 20) + "s  (" + s.regenerations + ")", C_BAD);
            y += step;
        }
        if (s.scramReason != null && !s.scramReason.isEmpty()) {
            line(x, y, t("gui.rsaudio.f9.scram"), s.scramReason, C_BAD);
            y += step;
        }
        // 拉杆方向：按住的时候界面上得有个东西在动，否则玩家不知道动作有没有生效
        if (s.transitDir != 0) {
            line(x, y, t("gui.rsaudio.f9.rod_moving"),
                    s.transitDir > 0 ? t("gui.rsaudio.f9.hold_up") : t("gui.rsaudio.f9.hold_down"),
                    C_SEL);
        }
    }

    /**
     * 9 根控制棒的柱状图。
     * <p>
     * 选中的棒用亮蓝描边、卡住的棒柱体转红 —— 这两件事是玩家在这里最需要一眼看到的。
     */
    private void drawRods(F9Snapshot s) {
        int bx = x0 + 8;
        int by = y0 + 140;
        int barW = 12;
        int barH = 78;
        int gap = 2;
        for (int i = 0; i < F9Spec.ROD_COUNT; i++) {
            int x = bx + i * (barW + gap);
            // 底：画成"插入度条"—— 插入度越高，柱子越高（= 棒越深）
            drawRect(x, by, x + barW, by + barH, 0x80202020);
            double ins = s.rod(i);
            int h = (int) Math.round(barH * Math.max(0.0, Math.min(100.0, ins)) / 100.0);
            int color = s.rodJammed(i) ? C_BAD : (s.selectedRod == i ? C_SEL : C_OK);
            drawRect(x, by + barH - h, x + barW, by + barH, 0xFF000000 | color);
            if (s.selectedRod == F9Core.ROD_SELECT_ALL) {
                // 全局选中：所有棒都用同一圈淡边
                drawRect(x, by, x + barW, by + 1, 0x6066CCFF);
            }
            fontRenderer.drawString(String.valueOf(i + 1), x + 3, by + barH + 2,
                    s.rodJammed(i) ? C_BAD : C_DIM);
        }
        // 右侧把这 9 根的平均值写出来 —— 攻略里的 35% / 40% 说的都是这个数
        fontRenderer.drawString(t("gui.rsaudio.f9.avg_insert") + " " + f(s.avgInsert) + " %",
                bx + 130, by, C_NORMAL);
    }

    /** 左标签、右数值，数值按状态染色 */
    private void line(int x, int y, String label, String value, int color) {
        fontRenderer.drawString(label, x, y, C_DIM);
        fontRenderer.drawString(value, x + 78, y, color);
    }

    private static int stateColor(int state) {
        if (F9Spec.isTerminal(state)) {
            return C_BAD;
        }
        if (state == F9Spec.ST_SCRAMMED) {
            return C_WARN;
        }
        if (state == F9Spec.ST_SYNCED) {
            return C_OK;
        }
        return state == F9Spec.ST_RUNNING ? C_OK : C_DIM;
    }

    private static int tempColor(double c) {
        if (c >= F9Spec.TEMP_SCRAM) {
            return C_BAD;
        }
        return c >= F9Spec.TEMP_ALARM ? C_WARN : C_OK;
    }

    private static int psiColor(double psi) {
        if (psi >= F9Spec.PSI_SCRAM) {
            return C_BAD;
        }
        return psi >= F9Spec.PSI_DANGER ? C_WARN : C_OK;
    }

    private static int kwColor(int kw) {
        return F9Spec.kwInSyncWindow(kw) ? C_OK : C_NORMAL;
    }

    private static int needleColor(double needle) {
        return Math.abs(needle) <= F9Spec.SYNC_NEEDLE_TOLERANCE ? C_OK : C_WARN;
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (!button.enabled) {
            return;
        }
        int id = button.id;
        if (id >= ID_SUB_BASE && id < ID_SUB_BASE + SUB_BITS.length) {
            send(PacketF9Action.ACT_TOGGLE_SUB, SUB_BITS[id - ID_SUB_BASE]);
            return;
        }
        if (id >= ID_CONV_BASE && id < ID_CONV_BASE + convButtons.length) {
            send(PacketF9Action.ACT_TOGGLE_CONVERTER, id - ID_CONV_BASE);
            return;
        }
        if (id >= ID_VALVE_BASE && id < ID_VALVE_BASE + valveButtons.length) {
            send(PacketF9Action.ACT_TOGGLE_VALVE, id - ID_VALVE_BASE);
            return;
        }
        if (id >= ID_ROD_BASE && id < ID_ROD_BASE + rodButtons.length) {
            send(PacketF9Action.ACT_SELECT_ROD, id - ID_ROD_BASE);
            return;
        }
        if (id >= ID_SECTOR_BASE && id < ID_SECTOR_BASE + sectorButtons.length) {
            send(PacketF9Action.ACT_SELECT_SECTOR, id - ID_SECTOR_BASE);
            return;
        }
        switch (id) {
            case ID_START:
                send(PacketF9Action.ACT_START, 0);
                break;
            case ID_SYNC:
                send(PacketF9Action.ACT_SYNC, 0);
                break;
            case ID_DESYNC:
                send(PacketF9Action.ACT_DESYNC, 0);
                break;
            case ID_SHUTDOWN:
                send(PacketF9Action.ACT_SHUTDOWN, 0);
                break;
            case ID_ROD_ALL:
                send(PacketF9Action.ACT_SELECT_ROD, F9Core.ROD_SELECT_ALL);
                break;
            case ID_SECTOR_ALL:
                send(PacketF9Action.ACT_SELECT_SECTOR, -1);
                break;
            case ID_HOLD_UP:
                beginHold(1);
                break;
            case ID_HOLD_DOWN:
                beginHold(-1);
                break;
            case ID_HOLD_STOP:
                endHold();
                send(PacketF9Action.ACT_STOP_ROD, 0);
                break;
            default:
                break;
        }
    }

    /** 按下拉杆：立刻发一次，之后交给 {@link #updateScreen()} 补发 */
    private void beginHold(int dir) {
        holdDir = dir;
        holdTimer = 0;
        send(PacketF9Action.ACT_MOVE_ROD, dir);
    }

    /** 松开拉杆：必须告诉服务端"别走了"，否则连续移动模式下棒会一直走到底 */
    private void endHold() {
        if (holdDir == 0) {
            return;
        }
        holdDir = 0;
        holdTimer = 0;
        send(PacketF9Action.ACT_RELEASE_ROD, 0);
    }

    private void send(int action, int arg) {
        PacketHandler.INSTANCE.sendToServer(new PacketF9Action(pos, action, arg));
    }

    /**
     * ★ 松开鼠标就结束按住。
     * <p>
     * 原版 {@code GuiButton} 只有"按下"这一个回调，没有"按住"也没有"松开"，
     * 所以拉杆的持续移动只能自己接 {@code mouseReleased}。
     * 拖到按钮外面再松手也要算结束 —— 所以这里<b>不判</b>鼠标在不在按钮上。
     */
    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        endHold();
        super.mouseReleased(mouseX, mouseY, state);
    }

    /**
     * ★ 关界面时必须补发"松手"。
     * <p>
     * 玩家按住拉杆直接按 E / ESC 关界面，{@code mouseReleased} 就不会来了 ——
     * 不补这一发，服务端的 {@code leverHeld} 会一直是 true，连续移动模式下
     * 控制棒会在没人的情况下继续跑到撞底。
     */
    @Override
    public void onGuiClosed() {
        endHold();
        super.onGuiClosed();
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // 界面里没有输入框，但 Esc/E 关界面走的就是 super —— 交给它处理
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_E) {
            endHold();
        }
        super.keyTyped(typedChar, keyCode);
    }
}

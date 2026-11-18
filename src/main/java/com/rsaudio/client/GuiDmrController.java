package com.rsaudio.client;

import com.rsaudio.dmr.DmrSpec;
import com.rsaudio.network.DmrSnapshot;
import com.rsaudio.network.PacketDmrAction;
import com.rsaudio.network.PacketHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale;

/**
 * DMR 控制柜界面：一块读数面板 + 所有操作按钮。
 *
 * <h3>数据从哪来</h3>
 * 界面<b>不直接读方块实体</b>，而是读 {@link ClientDmrState} 里那份服务端推来的快照。
 * 于是"没收到过快照"（刚开界面、区块刚加载）是个必须处理的正常状态 ——
 * 那种时候所有按钮都置灰，而不是显示一堆 0.0 让人以为机器坏了。
 *
 * <h3>为什么点完按钮不立刻改本地读数</h3>
 * 客户端不做乐观更新：读数永远等服务端回推。理由是这台机器<b>会把人炸死</b>，
 * 界面上显示的必须是真值；"先显示按成功、0.2 秒后被真实状态打回去"在一个
 * 正在熔毁的面板上会非常致命（玩家会以为自己已经停堆了）。
 *
 * <h3>停机码不在这里</h3>
 * 面板上<b>刻意不显示</b>停机码 —— 它只存在于服务端，玩家得去设施里把那张便签找回来。
 * 见 {@link DmrSnapshot} 里那段关于"为什么连字段都不留"的注释。
 * 于是本界面要做的，是把"还差哪几条前置条件"准确地摊开：
 * 温度 / EFSS 水位 / 功率激光 / 双钥匙 / （Phase 2 的）换料进度。
 */
public class GuiDmrController extends GuiScreen {

    private static final int W = 256;
    private static final int H = 264;

    private static final int ID_LOAD_DOWN = 1;
    private static final int ID_LOAD_UP = 2;
    private static final int ID_IGNITE = 3;
    private static final int ID_COOLDOWN = 4;
    private static final int ID_MAINT_IN = 5;
    private static final int ID_CELL = 6;
    private static final int ID_MAINT_OUT = 7;
    private static final int ID_SUBMIT = 8;
    private static final int ID_REPAIR = 9;
    private static final int ID_PULL = 10;
    private static final int ID_SUB_BASE = 100;

    /**
     * 界面上要画的子系统开关，下标即按钮 id 的偏移、也是语言键
     * {@code gui.rsaudio.dmr.sub.<下标>} 的后缀。
     * <p>
     * ★ 后加的位<b>只能往后排</b>：下标直接当语言键用，往中间插一位会让所有
     * 已有的语言键整体错位一格（表现是"热管理按钮写着'钥匙 2'"）。
     */
    private static final int[] SUB_BITS = {
            DmrSpec.SUB_FUEL_CELLS_LOCKED,
            DmrSpec.SUB_POWER_LASER_PUMPS,
            DmrSpec.SUB_AUX_GRID,
            DmrSpec.SUB_KEY,
            DmrSpec.SUB_THERMAL_SYSTEMS,
            DmrSpec.SUB_KEY2,
    };

    private static final int C_NORMAL = 0xE0E0E0;
    private static final int C_DIM = 0x9A9A9A;
    private static final int C_OK = 0x55FF55;
    private static final int C_WARN = 0xFFAA00;
    private static final int C_BAD = 0xFF5555;

    private final BlockPos pos;

    private GuiButton btnLoadDown;
    private GuiButton btnLoadUp;
    private GuiButton btnIgnite;
    private GuiButton btnCooldown;
    private GuiButton btnMaintIn;
    private GuiButton btnCell;
    private GuiButton btnMaintOut;
    private GuiButton btnSubmit;
    private GuiButton btnRepair;
    private GuiButton btnPull;
    private final GuiButton[] subButtons = new GuiButton[SUB_BITS.length];
    private GuiTextField codeField;

    private int x0;
    private int y0;

    public GuiDmrController(BlockPos pos) {
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

        for (int i = 0; i < SUB_BITS.length; i++) {
            subButtons[i] = new GuiButton(ID_SUB_BASE + i, x0 + 146, y0 + 18 + i * 19, 102, 18, "");
            buttonList.add(subButtons[i]);
        }

        btnLoadDown = new GuiButton(ID_LOAD_DOWN, x0 + 8, y0 + 166, 24, 20, "-");
        btnLoadUp = new GuiButton(ID_LOAD_UP, x0 + 34, y0 + 166, 24, 20, "+");
        btnIgnite = new GuiButton(ID_IGNITE, x0 + 66, y0 + 166, 64, 20, "");
        btnCooldown = new GuiButton(ID_COOLDOWN, x0 + 136, y0 + 166, 64, 20, "");
        buttonList.add(btnLoadDown);
        buttonList.add(btnLoadUp);
        buttonList.add(btnIgnite);
        buttonList.add(btnCooldown);

        btnMaintIn = new GuiButton(ID_MAINT_IN, x0 + 8, y0 + 188, 76, 20, "");
        btnCell = new GuiButton(ID_CELL, x0 + 88, y0 + 188, 76, 20, "");
        btnMaintOut = new GuiButton(ID_MAINT_OUT, x0 + 168, y0 + 188, 80, 20, "");
        buttonList.add(btnMaintIn);
        buttonList.add(btnCell);
        buttonList.add(btnMaintOut);

        btnRepair = new GuiButton(ID_REPAIR, x0 + 8, y0 + 210, 110, 20, "");
        btnPull = new GuiButton(ID_PULL, x0 + 124, y0 + 210, 124, 20, "");
        buttonList.add(btnRepair);
        buttonList.add(btnPull);

        btnSubmit = new GuiButton(ID_SUBMIT, x0 + 90, y0 + 232, 80, 20, "");
        buttonList.add(btnSubmit);

        codeField = new GuiTextField(0, fontRenderer, x0 + 8, y0 + 233, 76, 18);
        codeField.setMaxStringLength(DmrSpec.SHUTDOWN_CODE_DIGITS);
        codeField.setTextColor(0xFFFFFF);
        codeField.setDisabledTextColour(0x707070);

        updateButtonLabels();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (codeField != null) {
            codeField.updateCursorCounter();
        }
        updateButtonLabels();
    }

    private static String t(String key) {
        return I18n.format(key);
    }

    private void updateButtonLabels() {
        DmrSnapshot s = ClientDmrState.get(pos);
        boolean ready = s != null;
        for (int i = 0; i < subButtons.length; i++) {
            if (subButtons[i] == null) {
                continue;
            }
            boolean on = ready && s.hasSub(SUB_BITS[i]);
            subButtons[i].displayString = t("gui.rsaudio.dmr.sub." + i) + ": "
                    + t(on ? "gui.rsaudio.dmr.on" : "gui.rsaudio.dmr.off");
            subButtons[i].enabled = ready;
        }
        if (btnLoadDown != null) {
            // 负载按钮只在在线运行时有意义，但停机时调好再点火是常见操作，所以不锁
            btnLoadDown.enabled = ready;
            btnLoadUp.enabled = ready;
            btnIgnite.displayString = t("gui.rsaudio.dmr.ignite");
            btnIgnite.enabled = ready;
            btnCooldown.displayString = t("gui.rsaudio.dmr.cooldown");
            btnCooldown.enabled = ready;
            btnMaintIn.displayString = t("gui.rsaudio.dmr.maint_in");
            btnMaintIn.enabled = ready;
            btnCell.displayString = t("gui.rsaudio.dmr.install_cell");
            btnCell.enabled = ready;
            btnMaintOut.displayString = t("gui.rsaudio.dmr.maint_out");
            btnMaintOut.enabled = ready;

            boolean melting = ready && DmrSpec.isMeltdown(s.state);
            btnSubmit.displayString = t("gui.rsaudio.dmr.submit_code");
            btnSubmit.enabled = melting;
            codeField.setEnabled(melting);

            // 硬重启只有在"确实缺激光"且"温度降下来了"的时候才有意义 ——
            // 但后一个条件让服务端判更权威，这里只做粗筛，避免玩家反复点着玩
            boolean laserMissing = ready && s.lasers < DmrSpec.LASER_COUNT;
            btnRepair.displayString = t("gui.rsaudio.dmr.repair_laser")
                    + " (" + (ready ? s.lasers : 0) + "/" + DmrSpec.LASER_COUNT + ")";
            btnRepair.enabled = ready && laserMissing;

            // 拔燃料单元：只有熔毁期间、并且这条通道还没锁死才给按
            boolean canPull = ready && DmrSpec.isMeltdown(s.state) && !s.cellChannelLocked
                    && s.cellPulled < DmrSpec.FUEL_CELL_COUNT;
            btnPull.displayString = t("gui.rsaudio.dmr.pull_cell")
                    + " (" + (ready ? s.cellPulled : 0) + "/" + DmrSpec.FUEL_CELL_COUNT + ")";
            btnPull.enabled = canPull;
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        // 注意 drawRect 的颜色是 ARGB：透明度在高 8 位（0xC0 = 半透明）
        drawRect(x0 - 2, y0 - 2, x0 + W + 2, y0 + H + 2, 0xC0000000);
        drawRect(x0, y0, x0 + W, y0 + H, 0xC0282828);

        fontRenderer.drawString(t("gui.rsaudio.dmr.title"), x0 + 8, y0 + 6, C_NORMAL);

        DmrSnapshot s = ClientDmrState.get(pos);
        if (s == null) {
            fontRenderer.drawString(t("gui.rsaudio.dmr.waiting"), x0 + 8, y0 + 22, C_WARN);
        } else {
            drawReadouts(s);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
        if (codeField != null) {
            codeField.drawTextBox();
        }
        if (codeField != null && codeField.getText().isEmpty() && !codeField.isFocused()) {
            fontRenderer.drawString(t("gui.rsaudio.dmr.code_hint"), x0 + 12, y0 + 238, C_DIM);
        }
    }

    private void drawReadouts(DmrSnapshot s) {
        int x = x0 + 8;
        int y = y0 + 20;
        int step = 10;

        line(x, y, t("gui.rsaudio.dmr.state"), t("dmr.state." + s.stateName),
                stateColor(s.state));
        y += step;
        line(x, y, t("gui.rsaudio.dmr.temp"), f(s.tempK) + " K", tempColor(s.tempK));
        y += step;
        line(x, y, t("gui.rsaudio.dmr.integrity"), f(s.integrity) + " %",
                s.integrity <= DmrSpec.INTEGRITY_MELTDOWN + 10.0 ? C_BAD
                        : (s.integrity < 60.0 ? C_WARN : C_OK));
        y += step;
        line(x, y, t("gui.rsaudio.dmr.fuel"), f(s.avgFuel) + " %",
                s.avgFuel <= DmrSpec.FUEL_LOW_WARN ? C_WARN : C_NORMAL);
        y += step;
        // EFSS 水位：停机前置条件之一，平时不掉，一旦掉到 70% 以下就再也停不下来
        line(x, y, t("gui.rsaudio.dmr.efss"), f(s.efss) + " %",
                s.efss < DmrSpec.EFSS_SHUTDOWN_MIN ? C_BAD
                        : (s.efss <= DmrSpec.EFSS_WARN ? C_WARN : C_OK));
        y += step;
        line(x, y, t("gui.rsaudio.dmr.lasers"),
                s.lasers + " / " + DmrSpec.LASER_COUNT,
                s.lasers < DmrSpec.LASER_COUNT ? C_BAD : C_OK);
        y += step;
        line(x, y, t("gui.rsaudio.dmr.power"), s.outputKw + " kW", C_NORMAL);
        y += step;
        line(x, y, t("gui.rsaudio.dmr.eff"), f(s.catalyzerEff), C_NORMAL);
        y += step;
        line(x, y, t("gui.rsaudio.dmr.load"), s.loadPct + " %", C_NORMAL);
        y += step;
        line(x, y, t("gui.rsaudio.dmr.structure"),
                t(s.structureOk ? "gui.rsaudio.dmr.struct_ok" : "gui.rsaudio.dmr.struct_bad"),
                s.structureOk ? C_OK : C_BAD);
        y += step;

        if (DmrSpec.isMeltdown(s.state) || s.phase > 0) {
            String window = t("gui.rsaudio.dmr.phase") + " " + s.phase + " "
                    + t("gui.rsaudio.dmr.countdown") + " " + (s.phaseTimer / 20) + "s";
            line(x, y, t("gui.rsaudio.dmr.meltdown"), window, C_BAD);
            y += step;
            // 停机码不在面板上 —— 只告诉玩家还差几条、以及去哪找码
            String blockers = s.shutdownBlockers == 0
                    ? t("gui.rsaudio.dmr.blockers_ready")
                    : t("gui.rsaudio.dmr.blockers_left") + " " + s.shutdownBlockers;
            line(x, y, t("gui.rsaudio.dmr.shutdown"), blockers,
                    s.shutdownBlockers == 0 ? C_OK : C_WARN);
            y += step;
            line(x, y, t("gui.rsaudio.dmr.code"), t("gui.rsaudio.dmr.code_note"), C_WARN);
            y += step;
        }
        if (s.orderActive) {
            line(x, y, t("gui.rsaudio.dmr.order"),
                    s.orderTargetPct + "% " + (s.orderTimer / 20) + "s", C_NORMAL);
            y += step;
        }
        line(x, y, t("gui.rsaudio.dmr.orders"),
                s.ordersDone + " / " + s.ordersFailed + " / " + s.ordersTotal, C_DIM);
    }

    /** 左标签、右数值，数值按状态染色 */
    private void line(int x, int y, String label, String value, int color) {
        fontRenderer.drawString(label, x, y, C_DIM);
        fontRenderer.drawString(value, x + 78, y, color);
    }

    private static int stateColor(int state) {
        if (DmrSpec.isMeltdown(state) || state == DmrSpec.ST_IMPLODED) {
            return C_BAD;
        }
        if (state == DmrSpec.ST_STALLED || state == DmrSpec.ST_MAINTENANCE) {
            return C_WARN;
        }
        return C_OK;
    }

    private static int tempColor(double k) {
        if (k >= DmrSpec.TEMP_RUNAWAY) {
            return C_BAD;
        }
        if (k >= DmrSpec.TEMP_WARN_LIMIT) {
            return C_WARN;
        }
        return C_OK;
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
            send(PacketDmrAction.ACT_TOGGLE_SUB, SUB_BITS[id - ID_SUB_BASE]);
            return;
        }
        DmrSnapshot s = ClientDmrState.get(pos);
        int load = s == null ? 0 : s.loadPct;
        switch (id) {
            case ID_LOAD_DOWN:
                send(PacketDmrAction.ACT_SET_LOAD, load - 10);
                break;
            case ID_LOAD_UP:
                send(PacketDmrAction.ACT_SET_LOAD, load + 10);
                break;
            case ID_IGNITE:
                send(PacketDmrAction.ACT_IGNITE, 0);
                break;
            case ID_COOLDOWN:
                send(PacketDmrAction.ACT_COOLDOWN, 0);
                break;
            case ID_MAINT_IN:
                send(PacketDmrAction.ACT_ENTER_MAINT, 0);
                break;
            case ID_CELL:
                send(PacketDmrAction.ACT_INSTALL_CELL, 0);
                break;
            case ID_MAINT_OUT:
                send(PacketDmrAction.ACT_EXIT_MAINT, 0);
                break;
            case ID_REPAIR:
                send(PacketDmrAction.ACT_REPAIR_LASERS, 0);
                break;
            case ID_PULL:
                send(PacketDmrAction.ACT_PULL_CELL, 0);
                break;
            case ID_SUBMIT:
                submitCode();
                break;
            default:
                break;
        }
    }

    private void submitCode() {
        DmrSnapshot s = ClientDmrState.get(pos);
        if (s == null || !DmrSpec.isMeltdown(s.state)) {
            return;
        }
        int code;
        try {
            code = Integer.parseInt(codeField.getText().trim());
        } catch (NumberFormatException e) {
            return;
        }
        if (code < DmrSpec.SHUTDOWN_CODE_MIN || code > DmrSpec.SHUTDOWN_CODE_MAX) {
            return;
        }
        send(PacketDmrAction.ACT_SUBMIT_CODE, code);
        codeField.setText("");
    }

    private void send(int action, int arg) {
        PacketHandler.INSTANCE.sendToServer(new PacketDmrAction(pos, action, arg));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (codeField != null && codeField.isFocused() && codeField.textboxKeyTyped(typedChar, keyCode)) {
            stripNonDigits();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN && codeField != null && codeField.isFocused()) {
            submitCode();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    /**
     * 停机码是六位数字，输入框里不该出现别的字符。
     * <p>
     * 用"敲完再过滤"而不是拦 keyTyped：粘贴、输入法、自动重复都会绕过我们的按键判断，
     * 过滤已经落到框里的文本才是真正封死的那一道。
     */
    private void stripNonDigits() {
        String text = codeField.getText();
        StringBuilder sb = new StringBuilder(DmrSpec.SHUTDOWN_CODE_DIGITS);
        for (int i = 0; i < text.length() && sb.length() < DmrSpec.SHUTDOWN_CODE_DIGITS; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        if (!sb.toString().equals(text)) {
            codeField.setText(sb.toString());
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // ★ 先给输入框处理：它自己会按点击位置设焦点，之后不要再手动 setFocused 覆盖它
        if (codeField != null) {
            codeField.mouseClicked(mouseX, mouseY, mouseButton);
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }
}

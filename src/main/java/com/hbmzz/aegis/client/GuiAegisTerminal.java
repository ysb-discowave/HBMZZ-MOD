/*
 * HBMZZ - Aegis DFC telemetry terminal.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.hbmzz.aegis.client;

import com.hbmzz.aegis.AegisConfig;
import com.hbmzz.aegis.AegisTelemetry;
import com.hbmzz.aegis.TileAegisTerminal;
import com.hbmzz.network.AegisRequestPacket;
import com.hbmzz.network.AegisSetupPacket;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 神盾终端的右键配置菜单。普通右键终端打开。
 *
 * <p>所有动作都发到服务端执行（距离 + 目标二次校验），窗口只负责展示与把
 * 「按了哪个按钮」发上去。状态每帧从客户端 tile 读，收到服务端推送后自动刷新；
 * 打开瞬间主动拉一帧，避免停在旧数据。按钮自绘，配色与屏幕一致，并带快捷键
 * 与一次性的操作回执提示。</p>
 *
 * <p>4.1.0 起本窗口还负责墙面安装的几何参数：倾斜角、宽、高。三项都通过
 * SetupPacket 交给服务端 clamp 后写 NBT 并同步，存档里保存，重启不丢。</p>
 */
public final class GuiAegisTerminal extends GuiScreen {

    private static final int W = 372;
    private static final int H = 302;

    private static final int CYAN = 0xFF43E0D8;
    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int FAINT = 0xFF4A6472;
    private static final int GREEN = 0xFF57D97A;
    private static final int RED = 0xFFFF6B6B;
    private static final int LINE = 0xFF232A33;
    private static final int PANEL = 0xF50A0C0F;
    private static final int BTN = 0xFF142029;
    private static final int BTN_HOVER = 0xFF1C3444;
    private static final int BTN_EDGE = 0xFF2A4A5A;

    private final BlockPos pos;
    private int left;
    private int top;
    private boolean killed;
    private String feedback = "";
    private long feedbackAt;
    private long lastPull;

    private static final class Btn {
        final int id;
        final String label;
        final int x, y, w, h;
        Btn(int id, String label, int x, int y, int w, int h) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private final List<Btn> buttons = new ArrayList<Btn>();

    public GuiAegisTerminal(BlockPos pos) {
        this.pos = pos;
    }

    private TileAegisTerminal tile() {
        if (this.mc == null || this.mc.world == null || this.pos == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileAegisTerminal ? (TileAegisTerminal) te : null;
    }

    @Override
    public void initGui() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int colW = (W - 32 - 8) / 2;
        int x0 = this.left + 12;
        int x1 = this.left + 12 + colW + 8;
        int y0 = this.top + 206;
        int y1 = y0 + 26;
        int y2 = y1 + 26;

        this.buttons.clear();
        // geometry: +/- pairs for tilt, width, height
        int gy0 = this.top + 124;
        int step = 22;
        this.buttons.add(new Btn(5, "-", this.left + 196, gy0, 22, 18));
        this.buttons.add(new Btn(6, "+", this.left + 222, gy0, 22, 18));
        this.buttons.add(new Btn(7, "-", this.left + 196, gy0 + step, 22, 18));
        this.buttons.add(new Btn(8, "+", this.left + 222, gy0 + step, 22, 18));
        this.buttons.add(new Btn(9, "-", this.left + 196, gy0 + step * 2, 22, 18));
        this.buttons.add(new Btn(10, "+", this.left + 222, gy0 + step * 2, 22, 18));

        // core actions
        this.buttons.add(new Btn(0, "绑定最近核心", x0, y0, colW, 22));
        this.buttons.add(new Btn(1, "解除绑定", x1, y0, colW, 22));
        this.buttons.add(new Btn(2, "切换页面", x0, y1, colW, 22));
        this.buttons.add(new Btn(3, "电源开关", x1, y1, colW, 22));
        this.buttons.add(new Btn(4, "完成", x0, y2, W - 24, 22));

        pull();
    }

    /** Ask the server for a fresh frame so the window never shows stale data. */
    private void pull() {
        if (this.pos != null) {
            NetworkHandler.sendToServer(new AegisRequestPacket(this.pos));
            this.lastPull = System.currentTimeMillis();
        }
    }

    private void flash(String message) {
        this.feedback = message;
        this.feedbackAt = System.currentTimeMillis();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xCC05080B);
        int l = this.left;
        int t = this.top;

        // 外壳与三道描边
        drawRect(l, t, l + W, t + H, PANEL);
        drawRect(l, t, l + W, t + 1, BTN_EDGE);
        drawRect(l, t + H - 1, l + W, t + H, BTN_EDGE);
        drawRect(l, t, l + 1, t + H, BTN_EDGE);
        drawRect(l + W - 1, t, l + W, t + H, BTN_EDGE);
        // 四角 HUD 角标
        int ck = 10;
        drawRect(l, t, l + ck, t + 2, CYAN); drawRect(l, t, l + 2, t + ck, CYAN);
        drawRect(l + W - ck, t, l + W, t + 2, CYAN); drawRect(l + W - 2, t, l + W, t + ck, CYAN);
        drawRect(l, t + H - 2, l + ck, t + H, CYAN); drawRect(l, t + H - ck, l + 2, t + H, CYAN);
        drawRect(l + W - ck, t + H - 2, l + W, t + H, CYAN); drawRect(l + W - 2, t + H - ck, l + W, t + H, CYAN);

        // 标题栏
        drawRect(l + 1, t + 1, l + W - 1, t + 24, 0xFF0E161C);
        drawRect(l + 1, t + 24, l + W - 1, t + 25, LINE);
        long nowMs = System.currentTimeMillis();
        float pulse = 0.5F + 0.5F * (float) Math.sin(nowMs / 320.0D);
        int lamp = mix(0xFF1A3A44, CYAN, pulse);
        drawRect(l + 12, t + 7, l + 15, t + 18, lamp);
        this.fontRenderer.drawStringWithShadow("神盾 DFC 遥测终端 · 设置", l + 22, t + 8, CYAN);
        int hl = l + W - 16 - (int) ((nowMs / 18L) % 90L);
        drawRect(Math.max(l + W - 106, hl), t + 11, Math.min(l + W - 16, hl + 26), t + 13,
                0x4443E0D8);

        TileAegisTerminal te = tile();
        if (te == null) {
            this.fontRenderer.drawStringWithShadow("目标方块已不存在", l + 12, t + 40, RED);
            drawButtons(mouseX, mouseY);
            return;
        }

        AegisTelemetry frame = te.getLatest();
        boolean hasData = frame != null && frame.worldTick != 0L;

        // 连接状态
        BlockPos core = te.getBoundCore();
        boolean linked = core != null;
        this.fontRenderer.drawStringWithShadow("核心连接", l + 12, t + 36, DIM);
        int linkLamp = linked ? GREEN : AMBER;
        float linkPulse = 0.55F + 0.45F * (float) Math.sin(nowMs / 300.0D);
        drawRect(l + 84, t + 37, l + 88, t + 41, mix(linkLamp, 0xFF000000, 1.0F - linkPulse));
        this.fontRenderer.drawStringWithShadow(linked ? "已绑定" : "自动扫描",
                l + 92, t + 36, linked ? GREEN : AMBER);
        String where = linked
                ? ("坐标 " + core.getX() + ", " + core.getY() + ", " + core.getZ())
                : "未手动绑定 · 搜索 " + AegisConfig.scanRadius + " 格内最近核心";
        this.fontRenderer.drawStringWithShadow(where, l + 92, t + 50, FAINT);

        // 实时读数
        double heat = hasData ? frame.heatFraction() : 0.0D;
        int heatColour = hasData ? AegisPalette.heat(heat) : FAINT;
        String temp = hasData ? String.format(Locale.ROOT, "%,.0f °C", frame.heatC) : "等待数据…";
        String stab = hasData ? String.format(Locale.ROOT, "%.0f%%", frame.stability * 100.0F) : "—";
        String energy = hasData ? formatEnergy(frame.coreEnergy) : "—";

        this.fontRenderer.drawStringWithShadow("堆芯温度", l + 12, t + 68, DIM);
        this.fontRenderer.drawStringWithShadow(temp, l + 92, t + 68, heatColour);
        this.fontRenderer.drawStringWithShadow("稳定", l + 200, t + 68, DIM);
        this.fontRenderer.drawStringWithShadow(stab, l + 240, t + 68, CYAN);
        this.fontRenderer.drawStringWithShadow("能量", l + 296, t + 68, DIM);

        int barX = l + 92, barY = t + 79, barW = 86, barH = 4;
        drawRect(barX, barY, barX + barW, barY + barH, 0xFF0A1116);
        if (hasData) {
            int fw = (int) (barW * heat);
            for (int px = 0; px < fw; px++) {
                drawRect(barX + px, barY, barX + px + 1, barY + barH,
                        mix(CYAN, RED, (float) px / barW));
            }
        }
        drawRect(barX + barW - 1, barY - 1, barX + barW, barY + barH + 1, 0xFFFF5A56);

        this.fontRenderer.drawStringWithShadow("页面", l + 12, t + 88, DIM);
        this.fontRenderer.drawStringWithShadow((te.getPage() + 1) + "/3", l + 48, t + 88, CYAN);
        this.fontRenderer.drawStringWithShadow("电源", l + 92, t + 88, DIM);
        this.fontRenderer.drawStringWithShadow(te.isPowered() ? "开" : "关",
                l + 128, t + 88, te.isPowered() ? GREEN : RED);
        this.fontRenderer.drawStringWithShadow("能量 " + energy, l + 200, t + 88, FAINT);

        // ---- 墙面安装几何 ----
        drawRect(l + 12, t + 106, l + W - 12, t + 107, LINE);
        this.fontRenderer.drawStringWithShadow("墙面安装 · 点击 - / + 调节，实时生效并存档",
                l + 12, t + 110, CYAN);
        int gy0 = t + 124;
        int step = 22;
        drawGeoRow(l + 12, gy0, "倾斜角", String.format(Locale.ROOT, "%+.0f°", te.getTilt()));
        drawGeoRow(l + 12, gy0 + step, "屏幕宽", String.format(Locale.ROOT, "%.2f 格", te.getPanelWidth()));
        drawGeoRow(l + 12, gy0 + step * 2, "屏幕高", String.format(Locale.ROOT, "%.2f 格", te.getPanelHeight()));
        this.fontRenderer.drawStringWithShadow("倾斜 -45~+45 度 · 宽/高 1~32 格，每次 +1/-1 格",
                l + 12, gy0 + step * 3 + 2, FAINT);

        drawButtons(mouseX, mouseY);

        if (!this.feedback.isEmpty() && System.currentTimeMillis() - this.feedbackAt < 2600L) {
            this.fontRenderer.drawStringWithShadow(this.feedback, l + 12, t + H - 14, GREEN);
        }

        if (System.currentTimeMillis() - this.lastPull > 4000L) {
            pull();
        }
    }

    private void drawGeoRow(int x, int y, String label, String value) {
        this.fontRenderer.drawStringWithShadow(label, x, y + 4, DIM);
        int tw = this.fontRenderer.getStringWidth(value);
        this.fontRenderer.drawStringWithShadow(value, x + 172 - tw / 2, y + 4, CYAN);
    }

    private void drawButtons(int mouseX, int mouseY) {
        for (Btn b : this.buttons) {
            boolean hover = mouseX >= b.x && mouseX < b.x + b.w
                    && mouseY >= b.y && mouseY < b.y + b.h;
            drawRect(b.x, b.y, b.x + b.w, b.y + b.h, hover ? BTN_HOVER : BTN);
            drawRect(b.x, b.y, b.x + b.w, b.y + 1, hover ? CYAN : BTN_EDGE);
            drawRect(b.x, b.y + b.h - 1, b.x + b.w, b.y + b.h, BTN_EDGE);
            drawRect(b.x, b.y, b.x + 1, b.y + b.h, BTN_EDGE);
            drawRect(b.x + b.w - 1, b.y, b.x + b.w, b.y + b.h, BTN_EDGE);
            int tw = this.fontRenderer.getStringWidth(b.label);
            this.fontRenderer.drawStringWithShadow(b.label,
                    b.x + (b.w - tw) / 2, b.y + (b.h - 8) / 2, hover ? CYAN : DIM);
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton != 0) {
            return;
        }
        for (Btn b : this.buttons) {
            if (mouseX >= b.x && mouseX < b.x + b.w
                    && mouseY >= b.y && mouseY < b.y + b.h) {
                act(b.id);
                return;
            }
        }
    }

    private void act(int id) {
        TileAegisTerminal te = tile();
        switch (id) {
            case 0:
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.BIND_NEAREST, 0));
                flash("已请求绑定最近核心");
                break;
            case 1:
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.UNBIND, 0));
                flash("已解除绑定 · 恢复自动扫描");
                break;
            case 2:
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.PAGE,
                        te == null ? 0 : (te.getPage() + 1) % AegisTelemetry.PAGE_COUNT));
                flash("已切换页面");
                break;
            case 3:
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.POWER,
                        te == null || !te.isPowered() ? 1 : 0));
                flash("已切换电源");
                break;
            case 4:
                close();
                break;
            // geometry adjustments: read current value, nudge, send fixed-point
            case 5:
                if (te != null) sendTilt(te.getTilt() - 5.0F);
                break;
            case 6:
                if (te != null) sendTilt(te.getTilt() + 5.0F);
                break;
            case 7:
                if (te != null) sendWidth(te.getPanelWidth() - 1.0F);
                break;
            case 8:
                if (te != null) sendWidth(te.getPanelWidth() + 1.0F);
                break;
            case 9:
                if (te != null) sendHeight(te.getPanelHeight() - 1.0F);
                break;
            case 10:
                if (te != null) sendHeight(te.getPanelHeight() + 1.0F);
                break;
            default:
                break;
        }
    }

    /**
     * Live geometry nudge: update the client tile first so the 3D panel and the
     * on-screen readout change on the very next frame, then send the same value
     * to the server for authoritative clamping, NBT persistence and broadcast.
     * Using the identical clamp in {@link TileAegisTerminal#setGeometryPreview}
     * and on the server means the returning sync writes back the same number —
     * the screen never jumps back or waits for the menu to close.
     */
    private void sendTilt(float degrees) {
        int v = Math.round(degrees * 10.0F);
        v = Math.max(Math.round(TileAegisTerminal.TILT_MIN * 10.0F),
                Math.min(Math.round(TileAegisTerminal.TILT_MAX * 10.0F), v));
        float value = v / 10.0F;
        TileAegisTerminal local = tile();
        if (local != null) {
            local.setGeometryPreview(value, local.getPanelWidth(), local.getPanelHeight());
        }
        NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.TILT, v));
        flash("倾斜角 → " + String.format(Locale.ROOT, "%+.0f°", value));
    }

    private void sendWidth(float blocks) {
        int v = Math.round(blocks * 100.0F);
        v = Math.max(Math.round(TileAegisTerminal.WIDTH_MIN * 100.0F),
                Math.min(Math.round(TileAegisTerminal.WIDTH_MAX * 100.0F), v));
        float value = v / 100.0F;
        TileAegisTerminal local = tile();
        if (local != null) {
            local.setGeometryPreview(local.getTilt(), value, local.getPanelHeight());
        }
        NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.WIDTH, v));
        flash("屏幕宽 → " + String.format(Locale.ROOT, "%.2f 格", value));
    }

    private void sendHeight(float blocks) {
        int v = Math.round(blocks * 100.0F);
        v = Math.max(Math.round(TileAegisTerminal.HEIGHT_MIN * 100.0F),
                Math.min(Math.round(TileAegisTerminal.HEIGHT_MAX * 100.0F), v));
        float value = v / 100.0F;
        TileAegisTerminal local = tile();
        if (local != null) {
            local.setGeometryPreview(local.getTilt(), local.getPanelWidth(), value);
        }
        NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.HEIGHT, v));
        flash("屏幕高 → " + String.format(Locale.ROOT, "%.2f 格", value));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1 || keyCode == this.mc.gameSettings.keyBindInventory.getKeyCode()) {
            close();
            return;
        }
        TileAegisTerminal te = tile();
        if (typedChar >= '1' && typedChar <= '4') {
            act(typedChar - '1');
        } else if (typedChar == 'q' || typedChar == 'Q') {
            NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.PAGE,
                    te == null ? 0 : (te.getPage() + AegisTelemetry.PAGE_COUNT - 1) % AegisTelemetry.PAGE_COUNT));
            flash("已切换页面");
        } else if (typedChar == 'e' || typedChar == 'E') {
            act(2);
        } else if (typedChar == 'p' || typedChar == 'P') {
            act(3);
        } else if (typedChar == 'z' || typedChar == 'Z') {
            if (te != null) sendTilt(te.getTilt() - 5.0F);
        } else if (typedChar == 'x' || typedChar == 'X') {
            if (te != null) sendTilt(te.getTilt() + 5.0F);
        } else if (typedChar == '[') {
            if (te != null) sendWidth(te.getPanelWidth() - 1.0F);
        } else if (typedChar == ']') {
            if (te != null) sendWidth(te.getPanelWidth() + 1.0F);
        } else if (typedChar == '-') {
            if (te != null) sendHeight(te.getPanelHeight() - 1.0F);
        } else if (typedChar == '=') {
            if (te != null) sendHeight(te.getPanelHeight() + 1.0F);
        } else {
            super.keyTyped(typedChar, keyCode);
        }
    }

    private void close() {
        if (this.killed) {
            return;
        }
        this.killed = true;
        this.mc.displayGuiScreen(null);
    }

    /** Linear blend of two ARGB colours, t in 0..1. */
    private static int mix(int a, int b, float t) {
        t = Math.max(0.0F, Math.min(1.0F, t));
        int ar = (a >>> 16) & 0xFF, ag = (a >>> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >>> 16) & 0xFF, bg = (b >>> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int g = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    private static String formatEnergy(double value) {
        if (value >= 1.0E9D) {
            return String.format(Locale.ROOT, "%.2fG", value / 1.0E9D);
        }
        if (value >= 1.0E6D) {
            return String.format(Locale.ROOT, "%.2fM", value / 1.0E6D);
        }
        if (value >= 1.0E3D) {
            return String.format(Locale.ROOT, "%.1fk", value / 1.0E3D);
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }
}

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
 */
public final class GuiAegisTerminal extends GuiScreen {

    private static final int W = 372;
    private static final int H = 214;

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
        int y0 = this.top + 110;
        int y1 = y0 + 26;
        int y2 = y1 + 26;

        this.buttons.clear();
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

        // 标题栏
        drawRect(l + 1, t + 1, l + W - 1, t + 24, 0xFF0E161C);
        drawRect(l + 1, t + 24, l + W - 1, t + 25, LINE);
        drawRect(l + 12, t + 7, l + 15, t + 18, CYAN);
        this.fontRenderer.drawStringWithShadow("神盾 DFC 遥测终端 · 设置", l + 22, t + 8, CYAN);

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

        this.fontRenderer.drawStringWithShadow("页面", l + 12, t + 86, DIM);
        this.fontRenderer.drawStringWithShadow((te.getPage() + 1) + "/3", l + 48, t + 86, CYAN);
        this.fontRenderer.drawStringWithShadow("电源", l + 92, t + 86, DIM);
        this.fontRenderer.drawStringWithShadow(te.isPowered() ? "开" : "关",
                l + 128, t + 86, te.isPowered() ? GREEN : RED);
        this.fontRenderer.drawStringWithShadow("能量 " + energy, l + 200, t + 86, FAINT);

        // 按键提示
        this.fontRenderer.drawStringWithShadow("快捷键 1-4 动作 · Q/E 翻页 · P 电源 · Esc 关闭",
                l + 12, t + 98, FAINT);

        drawButtons(mouseX, mouseY);

        // 一次性操作回执
        if (!this.feedback.isEmpty() && System.currentTimeMillis() - this.feedbackAt < 2600L) {
            this.fontRenderer.drawStringWithShadow(this.feedback, l + 12, t + H - 14, GREEN);
        }

        // 窗口开着时每 4 秒补拉一帧，保证长时间停留数据不陈旧
        if (System.currentTimeMillis() - this.lastPull > 4000L) {
            pull();
        }
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
            default:
                break;
        }
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

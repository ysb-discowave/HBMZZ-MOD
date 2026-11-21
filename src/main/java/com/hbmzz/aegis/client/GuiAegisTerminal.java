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

import com.hbmzz.aegis.AegisTelemetry;
import com.hbmzz.aegis.TileAegisTerminal;
import com.hbmzz.network.AegisRequestPacket;
import com.hbmzz.network.AegisSetupPacket;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;

/**
 * 神盾终端的配置窗口。右键终端打开。
 *
 * <p>Four controls, no more: 绑定最近核心 / 解除绑定 / 翻页 / 开关。绑定与状态读取
 * 全部走服务端——窗口只是把「按了哪个按钮」发上去，真正的绑定决策在服务端做，
 * 这样存档和多人游戏里都以服务端为准。</p>
 */
public final class GuiAegisTerminal extends GuiScreen {

    private static final int W = 320;
    private static final int H = 176;
    private static final int CYAN = 0xFF43E0D8;
    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int GREEN = 0xFF57D97A;
    private static final int RED = 0xFFFF6B6B;

    private final BlockPos pos;
    private int left;
    private int top;
    private boolean killed;

    public GuiAegisTerminal(BlockPos pos) {
        this.pos = pos;
    }

    private TileAegisTerminal tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileAegisTerminal ? (TileAegisTerminal) te : null;
    }

    @Override
    public void initGui() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        int bw = 136;
        int gap = 8;
        // 两列两行：绑定 / 解绑 / 翻页 / 开关
        this.buttonList.add(new GuiButton(0, this.left + 12, this.top + 96, bw, 20, "绑定最近核心"));
        this.buttonList.add(new GuiButton(1, this.left + 12 + bw + gap, this.top + 96, bw, 20, "解除绑定"));
        this.buttonList.add(new GuiButton(2, this.left + 12, this.top + 122, bw, 20, "切换页面"));
        this.buttonList.add(new GuiButton(3, this.left + 12 + bw + gap, this.top + 122, bw, 20, "电源开关"));
        this.buttonList.add(new GuiButton(4, this.left + (W - 120) / 2, this.top + H - 26, 120, 20, "完成"));
        // Ask the server for a fresh frame so the window never shows stale data.
        NetworkHandler.sendToServer(new AegisRequestPacket(this.pos));
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xCC05080B);
        int l = this.left;
        int t = this.top;
        drawRect(l, t, l + W, t + H, 0xF50A0C0F);
        drawRect(l, t, l + W, t + 1, 0xFF232A33);
        drawRect(l, t + H - 1, l + W, t + H, 0xFF232A33);
        drawRect(l, t, l + 1, t + H, 0xFF232A33);
        drawRect(l + W - 1, t, l + W, t + H, 0xFF232A33);
        drawRect(l + 1, t + 20, l + W - 1, t + 21, 0xFF1A2028);

        this.fontRenderer.drawStringWithShadow("神盾 DFC 遥测终端 · 设置", l + 12, t + 7, CYAN);

        TileAegisTerminal te = tile();
        if (te == null) {
            this.fontRenderer.drawStringWithShadow("目标方块已不存在", l + 12, t + 40, RED);
            return;
        }

        // 连接状态
        BlockPos core = te.getBoundCore();
        boolean linked = core != null;
        this.fontRenderer.drawStringWithShadow("核心连接", l + 12, t + 34, DIM);
        this.fontRenderer.drawStringWithShadow(linked ? "已绑定" : "自动扫描",
                l + 12, t + 48, linked ? GREEN : AMBER);
        String where = linked ? ("坐标 " + core.getX() + ", " + core.getY() + ", " + core.getZ())
                : "未手动绑定；将自动搜索 16 格内最近的核心";
        this.fontRenderer.drawStringWithShadow(where, l + 12, t + 62, DIM);

        // 实时读数（可能尚未到达，标注等待）
        AegisTelemetry frame = te.getLatest();
        String temp = (frame != null && frame.worldTick != 0L)
                ? String.format(java.util.Locale.ROOT, "%,.0f °C", frame.heatC)
                : "等待数据…";
        this.fontRenderer.drawStringWithShadow("堆芯温度", l + 12, t + 79, DIM);
        this.fontRenderer.drawStringWithShadow(temp, l + 92, t + 79, frame != null && frame.worldTick != 0L ? AMBER : DIM);

        // 页面与电源
        int page = te.getPage() + 1;
        String pageName = page == 1 ? "总览" : (page == 2 ? "部件" : "诊断");
        this.fontRenderer.drawStringWithShadow("当前页面", l + 168, t + 79, DIM);
        this.fontRenderer.drawStringWithShadow(page + "/3", l + 168, t + 90, CYAN);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 0:
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.BIND_NEAREST, 0));
                break;
            case 1:
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.UNBIND, 0));
                break;
            case 2: {
                TileAegisTerminal te = tile();
                int next = te == null ? 0 : (te.getPage() + 1) % AegisTelemetry.PAGE_COUNT;
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.PAGE, next));
                break;
            }
            case 3: {
                TileAegisTerminal te = tile();
                int power = te == null || !te.isPowered() ? 1 : 0;
                NetworkHandler.sendToServer(new AegisSetupPacket(this.pos, AegisSetupPacket.POWER, power));
                break;
            }
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
}

/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
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
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.client;

import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.VideoScreenConfigMessage;
import com.hbmzz.tile.TileEntityVideoScreen;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 大屏配置: width and height.
 *
 * <p>Sneak + right-click any cell of a wall. Nothing else is in here on purpose -
 * not stop, not play, not the clip. Playing is the lever's job (that is what a
 * redstone-driven block is for) and the clip belongs to the player feeding the
 * wall, so offering either here would give the same wall two contradictory
 * inputs.</p>
 *
 * <p>Width and height are separate steppers rather than a slider: a wall is a
 * count of blocks, and a player who wants 6x4 should be able to ask for exactly
 * that instead of nudging a handle until the number happens to land. The value is
 * clamped to 1..32 on both ends of the wire.</p>
 */
public class GuiVideoScreenConfig extends GuiScreen {

    private static final int W = 320;
    private static final int H = 150;

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int MINT = 0xFF43D9A3;

    private static final int ROW_W = 40;
    private static final int ROW_H = 68;

    private final BlockPos pos;

    private int cols = 1;
    private int rows = 1;
    private boolean sent;

    private int left;
    private int top;

    public GuiVideoScreenConfig(BlockPos pos) {
        this.pos = pos;
    }

    private TileEntityVideoScreen tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityVideoScreen ? (TileEntityVideoScreen) te : null;
    }

    @Override
    public void initGui() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        TileEntityVideoScreen te = tile();
        if (te != null) {
            this.cols = te.getCols();
            this.rows = te.getRows();
        }
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.left + 150, this.top + ROW_W - 2, 22, 18, "-"));
        this.buttonList.add(new GuiButton(1, this.left + 244, this.top + ROW_W - 2, 22, 18, "+"));
        this.buttonList.add(new GuiButton(2, this.left + 150, this.top + ROW_H - 2, 22, 18, "-"));
        this.buttonList.add(new GuiButton(3, this.left + 244, this.top + ROW_H - 2, 22, 18, "+"));
        this.buttonList.add(new GuiButton(6, this.left + 24, this.top + H - 30, 132, 20, "应用"));
        this.buttonList.add(new GuiButton(7, this.left + 164, this.top + H - 30, 132, 20, "关闭"));
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xCC000000);
        int l = this.left;
        int t = this.top;
        drawRect(l, t, l + W, t + H, 0xF50A0C0F);
        drawRect(l, t, l + W, t + 1, 0xFF232A33);
        drawRect(l, t + H - 1, l + W, t + H, 0xFF232A33);
        drawRect(l, t, l + 1, t + H, 0xFF232A33);
        drawRect(l + W - 1, t, l + W, t + H, 0xFF232A33);
        drawRect(l + 1, t + 20, l + W - 1, t + 21, 0xFF1A2028);

        this.fontRenderer.drawStringWithShadow("视频大屏 · 配置", l + 12, t + 7, AMBER);
        String hint = "Shift + 右键大屏打开 · 播放仍由拉杆控制";
        this.fontRenderer.drawStringWithShadow(hint,
                l + W - 12 - this.fontRenderer.getStringWidth(hint), t + 8, DIM);

        drawRow("宽", this.cols + " 格", ROW_W, this.cols > 1, this.cols < TileEntityVideoScreen.MAX_SIZE);
        drawRow("高", this.rows + " 格", ROW_H, this.rows > 1, this.rows < TileEntityVideoScreen.MAX_SIZE);

        this.fontRenderer.drawStringWithShadow("上限 " + TileEntityVideoScreen.MAX_SIZE + " × "
                        + TileEntityVideoScreen.MAX_SIZE + " 格",
                l + 12, t + 96, 0xFF5A6674);
        // 方块只会变多或变少，不会让片子变快：速率这一项连字段带消息一起去掉了
        // （倍速时音轨只能扣住，"能加速但不能有声"是个半成品）。
        this.fontRenderer.drawStringWithShadow("改变格子数会就地扩建 / 收回，播放位置不变",
                l + 12, t + 108, 0xFF5A6674);
        String cells = this.cols * this.rows + " 格";
        this.fontRenderer.drawStringWithShadow(cells,
                l + W - 12 - this.fontRenderer.getStringWidth(cells), t + 96,
                this.cols * this.rows > 256 ? AMBER : MINT);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawRow(String label, String value, int y, boolean canDown, boolean canUp) {
        int l = this.left;
        int t = this.top;
        this.fontRenderer.drawStringWithShadow(label, l + 24, t + y + 4, DIM);
        int width = this.fontRenderer.getStringWidth(value);
        this.fontRenderer.drawStringWithShadow(value, l + 208 - width / 2, t + y + 4,
                canDown || canUp ? 0xFFE6ECF3 : DIM);
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            send();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 0:
                this.cols = Math.max(1, this.cols - 1);
                break;
            case 1:
                this.cols = Math.min(TileEntityVideoScreen.MAX_SIZE, this.cols + 1);
                break;
            case 2:
                this.rows = Math.max(1, this.rows - 1);
                break;
            case 3:
                this.rows = Math.min(TileEntityVideoScreen.MAX_SIZE, this.rows + 1);
                break;
            case 6:
                send();
                return;
            default:
                close();
                return;
        }
    }

    private void send() {
        NetworkHandler.sendToServer(new VideoScreenConfigMessage(this.pos, this.cols, this.rows));
    }

    /** Closes, keeping whatever the player dialled in - the window is a remote, not a form. */
    private void close() {
        if (!this.sent) {
            this.sent = true;
            send();
        }
        this.mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        if (!this.sent) {
            this.sent = true;
            send();
        }
    }
}

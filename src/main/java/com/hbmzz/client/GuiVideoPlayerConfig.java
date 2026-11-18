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
import com.hbmzz.network.VideoPlayerConfigMessage;
import com.hbmzz.tile.TileEntityVideoBroadcaster;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 播放器设置: two controls, and nothing else.
 *
 * <p>Sneak + right-click a broadcaster to get here. The two things a broadcast
 * actually needs tuning for are how loud it is and whether the audience may walk
 * out of it, so that is the whole window - no preview, no clip list, no test
 * button. Those live one window over, on the block's plain right-click, where the
 * operator is editing the broadcast rather than its delivery.</p>
 *
 * <p><b>强制限制返回 lives here and nowhere else.</b> It is a setting, not a
 * control: the playback window deliberately has no button, no entry and no
 * toggle for it, so a clip can never be interrupted by a player poking at its
 * own overlay. This window also says whether the lock is currently in force,
 * because a setting whose effect depends on another block's presence has to be
 * readable somewhere - and the reading belongs next to the switch, not in the
 * middle of a film.</p>
 *
 * <p><b>The slider is drawn, not borrowed.</b> {@code GuiSlider} is a mod-defined
 * widget in this project's rsaudio half with its own look, and vanilla has no
 * slider at all. Drawing a 160-pixel track and a 6-pixel knob is less code than
 * making a foreign widget agree with this window's palette, and it keeps the
 * drag handling - the part that actually breaks - in one place.</p>
 */
public class GuiVideoPlayerConfig extends GuiScreen {

    private static final int W = 300;
    private static final int H = 148;

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;

    private static final int TRACK_X = 60;
    private static final int TRACK_W = 190;

    private final BlockPos pos;

    /** 0..200 percent. */
    private int volume = 100;
    private boolean restrictBack = true;
    /** Whether a wall is wired right now; read once per frame, never stored. */
    private boolean screenLinked;
    private boolean dragging;
    private boolean killed;

    private int left;
    private int top;

    public GuiVideoPlayerConfig(BlockPos pos) {
        this.pos = pos;
    }

    private TileEntityVideoBroadcaster tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityVideoBroadcaster ? (TileEntityVideoBroadcaster) te : null;
    }

    @Override
    public void initGui() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;
        TileEntityVideoBroadcaster te = tile();
        if (te != null) {
            this.volume = te.getVolume();
            this.restrictBack = te.isRestrictBack();
            this.screenLinked = te.hasScreen();
        }
        this.buttonList.clear();
        this.buttonList.add(new GuiButton(0, this.left + 24, this.top + H - 30, 120, 20,
                restrictBackLabel()));
        this.buttonList.add(new GuiButton(1, this.left + 156, this.top + H - 30, 120, 20, "完成"));
    }

    private String restrictBackLabel() {
        return this.restrictBack ? "强制限制返回：开" : "强制限制返回：关";
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

        this.fontRenderer.drawStringWithShadow("视频播放器 · 设置", l + 12, t + 7, AMBER);
        String hint = "蹲下右键打开";
        this.fontRenderer.drawStringWithShadow(hint,
                l + W - 12 - this.fontRenderer.getStringWidth(hint), t + 8, DIM);

        // --- volume
        int volumeY = t + 42;
        this.fontRenderer.drawStringWithShadow("音量", l + 12, volumeY + 1, DIM);
        drawTrack(l + TRACK_X, volumeY, TRACK_W, this.volume / 200.0F);
        String value = this.volume + "%";
        this.fontRenderer.drawStringWithShadow(value,
                l + TRACK_X + TRACK_W + 12, volumeY + 1, this.volume == 0 ? DIM : AMBER);

        this.fontRenderer.drawStringWithShadow("100% = 默认音量；200% 更响，0% 静音",
                l + 12, volumeY + 16, 0xFF5A6674);
        this.fontRenderer.drawStringWithShadow("强制限制返回：未接大屏时播放结束前无法退出；接上大屏后自动不生效",
                l + 12, volumeY + 30, 0xFF5A6674);
        String state = this.screenLinked
                ? "当前已接大屏 · 本项不生效，退出由大屏与拉杆决定"
                : "当前未接大屏 · 本项生效中";
        this.fontRenderer.drawStringWithShadow(state, l + 12, volumeY + 46,
                this.screenLinked ? DIM : AMBER);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** A 4-pixel track with an amber fill and a knob at the current value. */
    private void drawTrack(int x, int y, int w, float fraction) {
        drawRect(x, y + 4, x + w, y + 8, 0xFF1A2028);
        drawRect(x, y + 4, x + (int) (w * fraction), y + 8, AMBER);
        int knob = x + (int) (w * fraction) - 2;
        drawRect(knob, y + 1, knob + 4, y + 11, 0xFFE6ECF3);
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && overTrack(mouseX, mouseY)) {
            this.dragging = true;
            setVolumeFrom(mouseX);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.dragging) {
            setVolumeFrom(mouseX);
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = false;
        super.mouseReleased(mouseX, mouseY, state);
    }

    private boolean overTrack(int mouseX, int mouseY) {
        int x = this.left + TRACK_X;
        int y = this.top + 42;
        return mouseX >= x - 4 && mouseX <= x + TRACK_W + 4 && mouseY >= y - 2 && mouseY <= y + 14;
    }

    private void setVolumeFrom(int mouseX) {
        int x = this.left + TRACK_X;
        float fraction = (mouseX - x) / (float) TRACK_W;
        int next = Math.round(Math.max(0.0F, Math.min(1.0F, fraction)) * 200.0F / 5.0F) * 5;
        this.volume = Math.max(0, Math.min(200, next));
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            close();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        if (button.id == 0) {
            this.restrictBack = !this.restrictBack;
            button.displayString = restrictBackLabel();
            return;
        }
        close();
    }

    /** Sends the two settings once, on the way out. */
    private void close() {
        if (this.killed) {
            return;
        }
        this.killed = true;
        NetworkHandler.sendToServer(new VideoPlayerConfigMessage(this.pos, this.volume, this.restrictBack));
        this.mc.displayGuiScreen(null);
    }
}

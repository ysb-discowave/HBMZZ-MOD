/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the
 * Free Software Foundation, version 3.
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
import com.hbmzz.network.VideoSeekMessage;
import com.hbmzz.tile.TileEntityVideoScreen;
import com.hbmzz.video.VideoClip;
import com.hbmzz.video.VideoLibrary;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/**
 * 大屏播放定位: where in the film the wall is, and how to put it somewhere else.
 *
 * <p>Right-click instead of sneak-right-click, because these two questions are
 * asked at different moments: sizing a wall happens once when it is built, while
 * "put it back to the start" happens in the middle of a screening with people
 * waiting. Nothing here changes blocks or settings - it moves a clock.</p>
 *
 * <p><b>Everything is a request to the server.</b> The window does not touch the
 * local decoder at all: it sends a seek / pause / rewind and then keeps drawing
 * whatever the block says. That is why the player who presses the button, the
 * player standing behind them and the player on the other side of a dedicated
 * server all end up looking at the same frame - the position lives in the wall,
 * not in a window.</p>
 *
 * <p>The bar is a direct handle rather than a set of fixed jumps, because "jump
 * to 0:23" is what the request usually is; the +/- buttons exist for the cases
 * where the picture matters more than the number. The length comes from the copy
 * of the clip on this client - the server does not have one - so a client without
 * that file gets an unlabelled bar, and the +/- buttons still work.</p>
 */
public class GuiVideoSeek extends GuiScreen {

    private static final int W = 384;
    private static final int H = 152;

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int MINT = 0xFF43D9A3;
    private static final int TRACK = 0xFF1A2028;
    private static final int EDGE = 0xFF232A33;

    private static final int BAR_Y = 58;
    private static final int BAR_H = 10;
    private static final int BUTTON_Y = 96;

    /** Longest wait between two seeks while the handle is being dragged. */
    private static final long DRAG_INTERVAL_MS = 180L;

    private final BlockPos pos;

    private double durationMs;
    private boolean haveClip;

    private boolean dragging;
    private int dragMs;
    private long lastSentAt;

    private int left;
    private int top;

    private GuiButton pauseButton;
    private GuiButton restartButton;

    public GuiVideoSeek(BlockPos pos) {
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
        measureClip();
        this.buttonList.clear();
        int y = this.top + BUTTON_Y;
        this.buttonList.add(new GuiButton(0, this.left + 20, y, 62, 18, "-30s"));
        this.buttonList.add(new GuiButton(1, this.left + 90, y, 62, 18, "-5s"));
        this.pauseButton = new GuiButton(2, this.left + 160, y, 64, 18, "暂停");
        this.buttonList.add(this.pauseButton);
        this.buttonList.add(new GuiButton(3, this.left + 232, y, 62, 18, "+5s"));
        this.buttonList.add(new GuiButton(4, this.left + 302, y, 62, 18, "+30s"));
        this.restartButton = new GuiButton(5, this.left + 20, y + 24, 132, 20, "回开头（重新播放）");
        this.buttonList.add(this.restartButton);
        this.buttonList.add(new GuiButton(6, this.left + 164, y + 24, 132, 20, "关闭"));
    }

    /**
     * Reads the clip's length once.
     *
     * <p>{@code VideoLibrary.open} parses a zip's index, so this must not happen
     * per frame - but it does have to happen, because the progress bar is
     * meaningless without a length and the server cannot supply one.</p>
     */
    private void measureClip() {
        this.durationMs = 0.0D;
        this.haveClip = false;
        TileEntityVideoScreen te = tile();
        String name = te == null ? "" : te.getVideo();
        if (name.isEmpty()) {
            return;
        }
        VideoClip clip = VideoLibrary.open(name);
        if (clip == null) {
            return;
        }
        try {
            this.durationMs = Math.max(0.0D, clip.durationSeconds() * 1000.0D);
            this.haveClip = this.durationMs > 0.0D;
        } finally {
            clip.close();
        }
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xCC000000);
        int l = this.left;
        int t = this.top;
        drawRect(l, t, l + W, t + H, 0xF50A0C0F);
        drawRect(l, t, l + W, t + 1, EDGE);
        drawRect(l, t + H - 1, l + W, t + H, EDGE);
        drawRect(l, t, l + 1, t + H, EDGE);
        drawRect(l + W - 1, t, l + W, t + H, EDGE);
        drawRect(l + 1, t + 20, l + W - 1, t + 21, EDGE);

        this.fontRenderer.drawStringWithShadow("视频大屏 · 播放定位", l + 12, t + 7, AMBER);
        String hint = "右键大屏打开 · 所有人看到同一帧";
        this.fontRenderer.drawStringWithShadow(hint,
                l + W - 12 - this.fontRenderer.getStringWidth(hint), t + 8, DIM);

        TileEntityVideoScreen te = tile();
        boolean playing = te != null && te.isPlaying();
        boolean paused = te != null && te.isPaused();
        int positionMs = te == null ? 0 : te.positionMs();
        if (this.haveClip) {
            // The wall's clock keeps running past the last frame; the readout
            // stops at the end of the film so it cannot show 3:30 for a 3:13 clip.
            positionMs = (int) Math.min(positionMs, Math.round(this.durationMs));
        }
        int shownMs = this.dragging ? this.dragMs : positionMs;

        // Big readout: position on the left, length on the right.
        this.fontRenderer.drawStringWithShadow(VideoSeekMessage.formatClock(shownMs), l + 20, t + 30, 0xFFE6ECF3);
        if (this.haveClip) {
            String total = "/ " + VideoSeekMessage.formatClock((int) this.durationMs);
            this.fontRenderer.drawStringWithShadow(total,
                    l + 20 + this.fontRenderer.getStringWidth(VideoSeekMessage.formatClock(shownMs)) + 6,
                    t + 30, DIM);
        } else {
            this.fontRenderer.drawStringWithShadow("（本机没有这个视频，长度未知）",
                    l + 20 + this.fontRenderer.getStringWidth(VideoSeekMessage.formatClock(shownMs)) + 6,
                    t + 30, 0xFF6A7480);
        }

        String state = te == null ? "大屏未加载" : !playing ? "拉杆未开" : paused ? "已暂停" : "播放中";
        int stateColor = te == null ? DIM : !playing ? DIM : paused ? AMBER : MINT;
        this.fontRenderer.drawStringWithShadow(state,
                l + W - 20 - this.fontRenderer.getStringWidth(state), t + 30, stateColor);

        drawBar(shownMs);
        drawTicks();

        String foot = this.haveClip
                ? "拖动进度条定位，或按 +/-5s / +/-30s 微调"
                : "没有本机副本时只能按秒调整";
        this.fontRenderer.drawStringWithShadow(foot, l + 20, t + 78, 0xFF5A6674);

        if (this.pauseButton != null) {
            this.pauseButton.displayString = paused ? "继续" : "暂停";
            this.pauseButton.enabled = playing;
        }
        if (this.restartButton != null) {
            this.restartButton.enabled = te != null;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawBar(int shownMs) {
        int l = this.left;
        int t = this.top;
        int x0 = l + 20;
        int x1 = l + W - 20;
        drawRect(x0, t + BAR_Y, x1, t + BAR_Y + BAR_H, TRACK);
        double fraction = fractionOf(shownMs);
        int filled = x0 + (int) Math.round((x1 - x0) * fraction);
        if (filled > x0) {
            drawRect(x0, t + BAR_Y, filled, t + BAR_Y + BAR_H, AMBER);
        }
        // Handle: two pixels wide, so it stays visible against the fill at both
        // ends of the bar.
        drawRect(Math.max(x0, Math.min(x1 - 2, filled - 1)), t + BAR_Y - 2,
                Math.max(x0, Math.min(x1, filled + 1)), t + BAR_Y + BAR_H + 2, 0xFFE6ECF3);
    }

    /** A tick every ten seconds, so a long film can be aimed at without counting. */
    private void drawTicks() {
        if (!this.haveClip || this.durationMs < 20_000.0D) {
            return;
        }
        int l = this.left;
        int t = this.top;
        int x0 = l + 20;
        int x1 = l + W - 20;
        int seconds = (int) Math.ceil(this.durationMs / 1000.0D);
        for (int s = 10; s < seconds; s += 10) {
            int x = x0 + (int) Math.round((x1 - x0) * (s * 1000.0D / this.durationMs));
            drawRect(x, t + BAR_Y + BAR_H, x + 1, t + BAR_Y + BAR_H + 3, EDGE);
        }
    }

    private double fractionOf(int ms) {
        if (!this.haveClip) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, ms / this.durationMs));
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        TileEntityVideoScreen te = tile();
        if (te == null) {
            return;
        }
        int now = te.positionMs();
        switch (button.id) {
            case 0:
                sendSeek(now - 30_000);
                return;
            case 1:
                sendSeek(now - 5_000);
                return;
            case 2:
                NetworkHandler.sendToServer(VideoSeekMessage.togglePause(this.pos));
                return;
            case 3:
                sendSeek(now + 5_000);
                return;
            case 4:
                sendSeek(now + 30_000);
                return;
            case 5:
                NetworkHandler.sendToServer(VideoSeekMessage.restart(this.pos));
                this.dragging = false;
                return;
            default:
                this.mc.displayGuiScreen(null);
                return;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && overBar(mouseX, mouseY)) {
            this.dragging = true;
            this.dragMs = msAt(mouseX);
            sendSeekNow(this.dragMs);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (!this.dragging) {
            super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
            return;
        }
        this.dragMs = msAt(mouseX);
        // Throttled: a drag across a three-minute film is hundreds of pixels, and
        // one packet per pixel would be hundreds of seeks - each one clearing the
        // decoder of every client watching.
        long now = System.currentTimeMillis();
        if (now - this.lastSentAt >= DRAG_INTERVAL_MS) {
            sendSeekNow(this.dragMs);
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (this.dragging) {
            this.dragging = false;
            // Always send the final position, even if the drag ended inside the
            // throttle window: the last place the player pointed at is the one
            // they meant.
            sendSeekNow(msAt(mouseX));
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    private boolean overBar(int mouseX, int mouseY) {
        int l = this.left;
        int t = this.top;
        return mouseX >= l + 20 && mouseX <= l + W - 20
                && mouseY >= t + BAR_Y - 4 && mouseY <= t + BAR_Y + BAR_H + 4;
    }

    /** Bar pixel to film time, clamped into the clip. */
    private int msAt(int mouseX) {
        int l = this.left;
        int x0 = l + 20;
        int x1 = l + W - 20;
        if (x1 <= x0) {
            return 0;
        }
        double fraction = (mouseX - x0) / (double) (x1 - x0);
        fraction = Math.max(0.0D, Math.min(1.0D, fraction));
        double limit = this.haveClip ? this.durationMs : TileEntityVideoScreen.MAX_PROGRESS_MS;
        return (int) Math.round(limit * fraction);
    }

    /**
     * Sends a jump, clamped locally first.
     *
     * <p>The server clamps again - it has to - but a window that sends "-5s from
     * 2s" as a negative number would flash an error at the wall instead of simply
     * stopping at the first frame, and the player would have to work out that the
     * button was refused rather than the wall being broken.</p>
     */
    private void sendSeek(int millis) {
        int clamped = millis;
        if (clamped < 0) {
            clamped = 0;
        }
        if (this.haveClip) {
            clamped = (int) Math.min(clamped, Math.round(this.durationMs));
        }
        sendSeekNow(clamped);
    }

    private void sendSeekNow(int millis) {
        this.lastSentAt = System.currentTimeMillis();
        NetworkHandler.sendToServer(VideoSeekMessage.seek(this.pos, millis));
    }
}

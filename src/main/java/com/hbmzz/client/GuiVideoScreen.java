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

import com.hbmzz.HBMZZConfig;
import com.hbmzz.client.video.VideoPlayer;
import com.hbmzz.video.VideoLibrary;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.IOException;

/**
 * 视频播放窗口: the full-screen takeover.
 *
 * <p>The screen is a single opaque quad over the whole window, painted before
 * anything else, so the HUD, the hotbar, chat and the world are all underneath
 * it. That is the entire trick behind "the game suddenly goes black and a video
 * starts" - no renderer hooks, no overlay layers, just a {@link GuiScreen} that
 * covers everything and refuses to be dismissed.</p>
 *
 * <p><b>Phases.</b> Black until the first frame decodes (typically 50-150 ms,
 * which reads as a display switching inputs, not as a stall), then the video,
 * then out. Sound starts with the picture, never before it.</p>
 *
 * <p><b>Why ESC does not work.</b> A takeover that a player can dismiss with one
 * key is not a takeover, so every key is swallowed for the duration. That is a
 * deliberate, configurable choice: {@code videoAllowEsc=true} in
 * {@code config/hbmzz.cfg} restores normal ESC behaviour, which is what a server
 * that would rather not surprise people should ship.</p>
 *
 * <p><b>And when there is no file</b>, this screen stops being a takeover and
 * says so, with the exact name and path it looked for. A silent black screen
 * would be indistinguishable from a client crash, and the reason is almost
 * always that the video was copied to the server instead of to each client.</p>
 */
public class GuiVideoScreen extends GuiScreen {

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int RED = 0xFFE5484D;

    /** Give up on a clip that has produced no frame at all after this long. */
    private static final double FIRST_FRAME_TIMEOUT = 6.0D;

    private final String videoName;

    private VideoPlayer player;
    private String error;
    private boolean closing;
    private long openedAt;
    private int hintTicks;

    public GuiVideoScreen(String videoName) {
        this.videoName = videoName == null ? "" : videoName;
    }

    public String videoName() {
        return this.videoName;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void initGui() {
        // initGui also runs on resize; a resize must not restart the film.
        if (this.player == null && this.error == null && !this.closing) {
            this.openedAt = System.nanoTime();
            if (!VideoLibrary.isValidName(this.videoName)) {
                this.error = "视频名不合法：" + this.videoName;
                return;
            }
            this.player = VideoPlayer.open(this.videoName);
            if (this.player == null) {
                this.error = "找不到视频文件：" + this.videoName + "\n" + VideoLibrary.describe(this.videoName)
                        + "\n请把视频放到客户端 config/hbmzz/videos/ 目录。";
                return;
            }
            this.player.start();
        }
    }

    @Override
    public void updateScreen() {
        if (this.hintTicks > 0) {
            this.hintTicks--;
        }
        if (this.player == null || this.error != null) {
            return;
        }
        if (!this.player.started()) {
            if (this.player.failure() != null) {
                fail(this.player.failure());
                return;
            }
            if (this.player.showFrame(0)) {
                this.player.beginPlayback();
            } else if ((System.nanoTime() - this.openedAt) / 1.0e9D > FIRST_FRAME_TIMEOUT) {
                fail("视频在 " + (int) FIRST_FRAME_TIMEOUT + " 秒内没有解出第一帧：" + this.videoName);
            }
            return;
        }
        if (this.player.finished()) {
            close();
            return;
        }
        this.player.showFrame(this.player.frameIndex(this.player.elapsedSeconds()));
    }

    private void fail(String message) {
        this.error = message;
        if (this.player != null) {
            this.player.close();
            this.player = null;
        }
    }

    private void close() {
        if (this.closing) {
            return;
        }
        this.closing = true;
        this.mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        if (this.player != null) {
            this.player.close();
            this.player = null;
        }
    }

    /** The world keeps running behind the screen; this is not a pause menu. */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xFF000000);

        if (this.player != null && this.player.started() && this.player.textureId() > 0) {
            drawFilm();
        }
        if (this.error != null) {
            drawError();
        } else {
            drawOverlay();
        }
        if (this.hintTicks > 0) {
            String hint = "广播进行中 · 结束后自动恢复"
                    + (HBMZZConfig.videoAllowEsc ? "" : " · 服务器禁止中途退出");
            this.fontRenderer.drawStringWithShadow(hint,
                    (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                    this.height / 2 + 36, AMBER);
        }
    }

    /** Letterboxed, never stretched: black bars are honest, distortion is not. */
    private void drawFilm() {
        int frameW = this.player.frameWidth();
        int frameH = this.player.frameHeight();
        if (frameW <= 0 || frameH <= 0) {
            return;
        }
        double scale = Math.min((double) this.width / frameW, (double) this.height / frameH);
        int drawW = Math.max(1, (int) Math.round(frameW * scale));
        int drawH = Math.max(1, (int) Math.round(frameH * scale));
        int x = (this.width - drawW) / 2;
        int y = (this.height - drawH) / 2;
        drawQuad(x, y, drawW, drawH, this.player.textureId());
    }

    private void drawOverlay() {
        if (this.player == null || !this.player.started()) {
            return;
        }
        double elapsed = this.player.elapsedSeconds();
        double duration = this.player.durationSeconds();
        String time = timecode(elapsed) + " / " + timecode(duration);
        int barTop = this.height - 14;

        drawRect(0, barTop, this.width, this.height, 0x99000000);
        drawRect(0, barTop, (int) (this.width * this.player.percent()), this.height, AMBER);

        this.fontRenderer.drawStringWithShadow(this.player.title(), 6, barTop + 3, 0xFFFFFFFF);
        this.fontRenderer.drawStringWithShadow(time,
                this.width - 6 - this.fontRenderer.getStringWidth(time), barTop + 3, DIM);
    }

    private void drawError() {
        String[] lines = this.error.split("\n");
        int top = this.height / 2 - lines.length * 6;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int color = i == 0 ? RED : DIM;
            this.fontRenderer.drawStringWithShadow(line,
                    (this.width - this.fontRenderer.getStringWidth(line)) / 2, top + i * 12, color);
        }
        String hint = "按 ESC 关闭";
        this.fontRenderer.drawStringWithShadow(hint,
                (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                top + lines.length * 12 + 14, DIM);
    }

    private void drawQuad(int x, int y, int w, int h, int textureId) {
        GlStateManager.disableLighting();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(textureId);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
        buffer.pos(x, y + h, 0.0D).tex(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x + w, y + h, 0.0D).tex(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x + w, y, 0.0D).tex(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x, y, 0.0D).tex(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        tessellator.draw();
    }

    private static String timecode(double seconds) {
        int total = MathHelper.clamp((int) seconds, 0, 359999);
        return String.format("%02d:%02d", total / 60, total % 60);
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.error != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.mc.displayGuiScreen(null);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (HBMZZConfig.videoAllowEsc) {
                close();
            } else {
                // Not an error, just a reminder that the key is already known.
                this.hintTicks = 40;
            }
        }
        // Everything else is swallowed: no inventory, no chat, no pause.
    }

    /** Clicks must not fall through to the world behind the screen. */
    @Override
    public void handleMouseInput() throws IOException {
        // Intentionally empty.
    }
}

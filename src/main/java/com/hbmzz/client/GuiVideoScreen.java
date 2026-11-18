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
 * 视频播放窗口, in two modes that look nothing alike on purpose.
 *
 * <h3>Takeover (broadcast)</h3>
 * <p>A single opaque quad over the whole window, painted before anything else,
 * so the HUD, the hotbar, chat and the world are all underneath it. That is the
 * entire trick behind "the game suddenly goes black and a video starts" - no
 * renderer hooks, no overlay layers, just a {@link GuiScreen} that covers
 * everything and refuses to be dismissed. This is what a redstone edge on the
 * broadcaster does, and it stays exactly as it was.</p>
 *
 * <h3>Viewer (a screen or a player block you right-clicked)</h3>
 * <p>Not a takeover. The picture sits in a bordered panel anchored to the
 * bottom of the window, and the upper half of the view stays visible: the point
 * of walking up to a screen is to watch <em>and</em> still see the room, so a
 * full-window black rectangle above the panel would be the one thing the player
 * did not ask for. {@code F} expands it to the takeover layout and back; ESC
 * always closes, because a viewer that traps you is a bug rather than a
 * feature.</p>
 *
 * <p><b>Phases.</b> Black until the first frame decodes (typically 50-150 ms,
 * which reads as a display switching inputs, not as a stall), then the video,
 * then out. Sound starts with the picture, never before it.</p>
 *
 * <p><b>And when there is no file</b>, this screen stops playing and says so,
 * with the exact name and path it looked for. A silent black screen would be
 * indistinguishable from a client crash, and the reason is almost always that
 * the video was copied to the server instead of to each client.</p>
 */
public class GuiVideoScreen extends GuiScreen {

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int RED = 0xFFE5484D;

    /** Give up on a clip that has produced no frame at all after this long. */
    private static final double FIRST_FRAME_TIMEOUT = 6.0D;

    private final String videoName;

    /** True for the right-click viewer: starts floating, starts closable. */
    private final boolean viewer;

    private VideoPlayer player;
    private String error;
    private boolean closing;
    private boolean fullscreen;
    private long openedAt;
    private int hintTicks;

    // Floating layout, recomputed every frame; also read by the input handlers.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    /** Broadcast mode: full-window takeover, exactly as before. */
    public GuiVideoScreen(String videoName) {
        this(videoName, false);
    }

    /**
     * @param viewer true for the screen/player right-click window; false for a
     *               redstone broadcast, which has to keep its takeover behaviour
     */
    public GuiVideoScreen(String videoName, boolean viewer) {
        this.videoName = videoName == null ? "" : videoName;
        this.viewer = viewer;
        this.fullscreen = !viewer;
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
            if (this.videoName.isEmpty()) {
                if (this.viewer) {
                    this.error = "这块屏幕还没有接上视频播放器\n"
                            + "拿着连接线缆右键播放器记录，再右键大屏绑定\n"
                            + "或者把播放器配好视频后重新右键";
                } else {
                    this.error = "没有指定视频文件";
                }
                return;
            }
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
            if (this.viewer) {
                // A screen loops, and the window should behave like the screen:
                // reopen rather than dumping the player back into the world.
                this.player.close();
                this.player = VideoPlayer.open(this.videoName);
                if (this.player != null) {
                    this.openedAt = System.nanoTime();
                    this.player.start();
                }
            } else {
                close();
            }
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

    /** True when there is a decoded frame to paint. */
    private boolean hasPicture() {
        return this.player != null && this.player.started() && this.player.textureId() > 0;
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        if (this.fullscreen) {
            drawTakeover();
            return;
        }
        drawViewer();
    }

    /** Broadcast mode: everything is covered, exactly as in 3.7.x. */
    private void drawTakeover() {
        drawRect(0, 0, this.width, this.height, 0xFF000000);

        if (hasPicture()) {
            drawFilm(0, 0, this.width, this.height);
        }
        if (this.error != null) {
            drawError(this.width / 2, this.height / 2);
        } else {
            drawBar(this.height - 14, this.width);
        }
        if (this.hintTicks > 0) {
            String hint = "广播进行中 · 结束后自动恢复"
                    + (HBMZZConfig.videoAllowEsc ? "" : " · 服务器禁止中途退出");
            this.fontRenderer.drawStringWithShadow(hint,
                    (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                    this.height / 2 + 36, AMBER);
        }
    }

    /**
     * Viewer mode: a bordered panel low in the window.
     *
     * <p>Anchored to the bottom and capped at just over half the window height,
     * so the upper part of the view - where the player is looking, and where
     * whatever they are standing in front of is - is never painted over. The
     * world is not dimmed either: the panel is the only thing added.</p>
     */
    private void drawViewer() {
        int pw = Math.min(this.width - 80, 560);
        int ph = Math.max(96, pw * 9 / 16);
        int maxH = (int) (this.height * 0.52D);
        if (ph > maxH) {
            ph = maxH;
            pw = Math.max(160, ph * 16 / 9);
        }
        int px = (this.width - pw) / 2;
        int py = this.height - ph - 40;
        if (py < 20) {
            py = 20;
        }
        this.panelX = px;
        this.panelY = py;
        this.panelW = pw;
        this.panelH = ph;

        // Shadow, bezel, glass.
        drawRect(px - 4, py - 4, px + pw + 4, py + ph + 4, 0x66000000);
        drawRect(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF0A0D11);
        drawRect(px, py, px + pw, py + ph, 0xFF000000);

        if (hasPicture()) {
            drawFilm(px, py, pw, ph);
        }
        if (this.error != null) {
            drawError(px + pw / 2, py + ph / 2);
        } else {
            drawBar(py + ph - 12, pw, px);
        }

        String hint = hasPicture() ? "F 全屏 · ESC 关闭" : "ESC 关闭";
        this.fontRenderer.drawStringWithShadow(hint, px + 1, py + ph + 6, DIM);
    }

    /** Letterboxed, never stretched: black bars are honest, distortion is not. */
    private void drawFilm(int x, int y, int w, int h) {
        int frameW = this.player.frameWidth();
        int frameH = this.player.frameHeight();
        if (frameW <= 0 || frameH <= 0) {
            return;
        }
        double scale = Math.min((double) w / frameW, (double) h / frameH);
        int drawW = Math.max(1, (int) Math.round(frameW * scale));
        int drawH = Math.max(1, (int) Math.round(frameH * scale));
        drawQuad(x + (w - drawW) / 2, y + (h - drawH) / 2, drawW, drawH, this.player.textureId());
    }

    /** Progress bar. Full width in takeover mode, inside the panel in viewer mode. */
    private void drawBar(int top, int fullWidth) {
        drawBar(top, fullWidth, 0);
    }

    private void drawBar(int top, int barWidth, int left) {
        if (this.player == null || !this.player.started()) {
            return;
        }
        double elapsed = this.player.elapsedSeconds();
        double duration = this.player.durationSeconds();
        String time = timecode(elapsed) + " / " + timecode(duration);
        int barTop = top;

        drawRect(left, barTop, left + barWidth, barTop + 12, 0x99000000);
        drawRect(left, barTop, left + (int) (barWidth * this.player.percent()), barTop + 12, AMBER);

        this.fontRenderer.drawStringWithShadow(fit(this.player.title(), barWidth - 70),
                left + 5, barTop + 2, 0xFFFFFFFF);
        this.fontRenderer.drawStringWithShadow(time,
                left + barWidth - 5 - this.fontRenderer.getStringWidth(time), barTop + 2, DIM);
    }

    /** Centred error block; the anchor is the middle of whatever it sits in. */
    private void drawError(int centerX, int centerY) {
        String[] lines = this.error.split("\n");
        int top = centerY - lines.length * 5;
        for (int i = 0; i < lines.length; i++) {
            String line = fit(lines[i], Math.max(120, this.width - 60));
            int color = i == 0 ? RED : DIM;
            this.fontRenderer.drawStringWithShadow(line,
                    centerX - this.fontRenderer.getStringWidth(line) / 2, top + i * 12, color);
        }
    }

    private String fit(String text, int maxWidth) {
        if (this.fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsis = this.fontRenderer.getStringWidth("…");
        return this.fontRenderer.trimStringToWidth(text, Math.max(0, maxWidth - ellipsis)) + "…";
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
        if (this.viewer && keyCode == Keyboard.KEY_F) {
            this.fullscreen = !this.fullscreen;
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (this.viewer || HBMZZConfig.videoAllowEsc) {
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

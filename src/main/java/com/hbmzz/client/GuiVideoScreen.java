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
import com.hbmzz.tile.TileEntityVideoBroadcaster;
import com.hbmzz.tile.TileEntityVideoScreen;
import com.hbmzz.video.VideoLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.IOException;

/**
 * 视频窗口: the full-screen takeover, and the 大屏展示 that mirrors a wall.
 *
 * <p>Two modes, one class, because they share everything that matters: a black
 * quad over the whole window, the letterboxed picture, F for fullscreen, and the
 * rule that nothing behind it can be clicked.</p>
 *
 * <p><b>接管 (takeover).</b> The screen is a single opaque quad over the whole
 * window, painted before anything else, so the HUD, the hotbar, chat and the world
 * are all underneath it. That is the entire trick behind "the game suddenly goes
 * black and a video starts" - no renderer hooks, no overlay layers, just a
 * {@link GuiScreen} that covers everything and refuses to be dismissed.</p>
 *
 * <p><b>展示 (mirror).</b> 站立右键播放器时，如果这台机器接了屏，开的是这个模式：
 * 画面来自那块屏自己的解码器，声音也是那一路。窗口不持有任何播放实例，所以
 * "屏在放、窗口也在放"这种双份声音在这里结构上不可能发生；退出窗口也不会把屏
 * 停掉——屏是世界里的东西，它还在放。</p>
 *
 * <p><b>Phases.</b> Black until the first frame decodes (typically 50-150 ms,
 * which reads as a display switching inputs, not as a stall), then the video,
 * then out. Sound starts with the picture, never before it.</p>
 *
 * <p><b>Why ESC normally does not work.</b> A takeover that a player can dismiss
 * with one key is not a takeover, so every key is swallowed for the duration.
 * That is 强制限制返回, and it is on by default.</p>
 *
 * <p><b>But only while there is no wall.</b> Once this player block is wired to a
 * screen, leaving is the wall's business - the lever decides what plays, and the
 * full-screen path stops being the thing that holds the audience. So the lock is
 * asked for again every time it matters (each key press, each frame that draws
 * the hint), through the block entity's own link state. Nothing is cached at
 * construction: a remote that connects a wall halfway through a clip takes effect
 * on the next key press, and a wall that is broken in the same breath puts the
 * lock back. The window never shows a sentence about a rule that is not in force,
 * which is why an audience cannot tell the two states apart from the picture
 * alone.</p>
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

    /** 展示窗口等大屏开播的宽限：客户端比服务端早一步开窗。 */
    private static final double START_GRACE = 2.5D;

    /** The player block that fired this takeover; null when there is none. */
    private final BlockPos pos;
    private final String videoName;
    /** 0..200 percent, from the broadcaster that fired this takeover. */
    private final int volume;
    /** 强制限制返回 as configured; whether it bites depends on the wall. */
    private final boolean restrictBack;
    /**
     * 大屏展示: this window shows the picture of a wall in the world instead of
     * opening a decoder of its own.
     *
     * <p>Two reasons, and the second one is the important one. A wall already has
     * a decoder, a clock that the server owns and a playhead everybody agrees on;
     * a second decoder in a window would show the same film from a different
     * second, and play the same music a second time. Mirroring means the window
     * cannot disagree with the wall - it is the wall's picture, magnified - and
     * that there is exactly one playback instance whether the player is looking at
     * the block or at the window.</p>
     */
    private final boolean mirror;
    /** The wall being mirrored; null in takeover mode. */
    private final BlockPos wallPos;
    /**
     * Last known link state. The block entity can be out of loaded range mid-clip
     * (the audience may walk far away), and "I cannot see the wall right now" must
     * not read as "the wall was removed" - that would slam the lock back on a
     * player who is simply not standing next to the machine any more.
     */
    private boolean screenLinked;

    /** Fullscreen as it was when the film opened; put back when it closes. */
    private boolean wasFullscreen;
    private boolean fullscreenKnown;

    private VideoPlayer player;
    /** Borrowed from the wall in mirror mode; never closed by this window. */
    private VideoPlayer wallPlayer;
    private String notice = "";
    private String error;
    private boolean closing;
    private long openedAt;
    private int hintTicks;

    /**
     * The window currently on screen, if it is one of these.
     *
     * <p>Static because a soundtrack outliving its window is the one failure this
     * class must never have, and the check has to work from a tick handler that
     * has no reference to it. {@code VideoAudioPolicy} reaps it every tick.</p>
     */
    private static GuiVideoScreen live;

    public GuiVideoScreen(String videoName) {
        this(null, videoName, 100, !HBMZZConfig.videoAllowEsc);
    }

    public GuiVideoScreen(BlockPos pos, String videoName, int volume, boolean restrictBack) {
        this.pos = pos;
        this.videoName = videoName == null ? "" : videoName;
        this.volume = Math.max(0, Math.min(200, volume));
        this.restrictBack = restrictBack;
        this.mirror = false;
        this.wallPos = null;
    }

    /**
     * 大屏展示: the wall's own picture, on the whole window.
     *
     * <p>No player block of its own, no progress bar, no second soundtrack. The
     * window is a magnifying glass over a screen that is already running.</p>
     */
    public static GuiVideoScreen mirror(BlockPos broadcaster, BlockPos wall) {
        return new GuiVideoScreen(broadcaster, wall);
    }

    private GuiVideoScreen(BlockPos broadcaster, BlockPos wall) {
        this.pos = broadcaster;
        this.wallPos = wall == null ? null : wall.toImmutable();
        this.videoName = "";
        this.volume = 100;
        // Entered by hand, so it is never a lock: ESC always closes it.
        this.restrictBack = false;
        this.mirror = true;
    }

    public boolean mirrors() {
        return this.mirror;
    }

    /** The wall this window magnifies, or null when it is not loaded. */
    public TileEntityVideoScreen mirroredWall() {
        return this.mirror ? wallTile() : null;
    }

    private TileEntityVideoScreen wallTile() {
        if (this.wallPos == null || this.mc == null || this.mc.world == null) {
            return null;
        }
        if (!this.mc.world.isBlockLoaded(this.wallPos)) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.wallPos);
        return te instanceof TileEntityVideoScreen ? (TileEntityVideoScreen) te : null;
    }

    /**
     * 现在能不能看到一台接上的大屏。
     *
     * <p>问的是本机这份方块实体，它随方块更新同步，和画面上那条"大屏播放中"
     * 的状态行是同一个来源。方块不在已加载区时返回上一次的结论，而不是 false。</p>
     */
    private boolean screenLinked() {
        if (this.pos != null && this.mc != null && this.mc.world != null
                && this.mc.world.isBlockLoaded(this.pos)) {
            TileEntity te = this.mc.world.getTileEntity(this.pos);
            if (te instanceof TileEntityVideoBroadcaster) {
                this.screenLinked = ((TileEntityVideoBroadcaster) te).hasScreen();
            } else {
                // The player block itself is gone: nothing can be wired to it,
                // so the lock goes back on. Being unloaded is handled above and
                // keeps the previous answer instead.
                this.screenLinked = false;
            }
        }
        return this.screenLinked;
    }

    /** 这条限制此刻是否真的在拦人：开了它，而且没有检测到大屏。 */
    private boolean restricted() {
        // 大屏展示是玩家自己点开的，不是广播接管；它任何时候都能退出。
        return !this.mirror && this.restrictBack && !screenLinked();
    }

    public String videoName() {
        return this.videoName;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void initGui() {
        live = this;
        if (!this.fullscreenKnown) {
            this.fullscreenKnown = true;
            this.wasFullscreen = this.mc.gameSettings.fullScreen;
        }
        if (this.mirror) {
            // initGui also runs on resize. Nothing to open: the decoder belongs to
            // the wall, and updateScreen picks it up again after the resize.
            if (this.openedAt == 0L) {
                this.openedAt = System.nanoTime();
                this.hintTicks = 40;
            }
            return;
        }
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
            // Say it once, at the start, and only when it is true. A takeover
            // that silently eats ESC reads as a broken client; a takeover that
            // announces a lock it does not have is worse.
            this.hintTicks = restricted() ? 40 : 0;
        }
    }

    @Override
    public void updateScreen() {
        if (this.hintTicks > 0) {
            this.hintTicks--;
        }
        if (this.mirror) {
            watchMirror();
            return;
        }
        watchWall();
        if (this.player == null || this.error != null) {
            return;
        }
        if (!this.player.started()) {
            if (this.player.failure() != null) {
                fail(this.player.failure());
                return;
            }
            if (this.player.showFrame(0)) {
                // 0.5 at 100%: the mixer clamps at 1.0, so a louder baseline
                // would make the slider's top half do nothing at all.
                this.player.beginPlayback(0.5F * (this.volume / 100.0F));
            } else if ((System.nanoTime() - this.openedAt) / 1.0e9D > FIRST_FRAME_TIMEOUT) {
                fail("视频在 " + (int) FIRST_FRAME_TIMEOUT + " 秒内没有解出第一帧：" + this.videoName);
            }
            return;
        }
        if (this.player.finished()) {
            close();
            return;
        }
        this.player.showFrame(this.player.frameIndex(this.player.filmSeconds()));
    }

    /**
     * 大屏展示 follows the wall, frame by frame.
     *
     * <p>The decoder is asked for, never kept: the wall may stop, be broken, or
     * have its chunk unloaded while the window is open, and every one of those has
     * to become a sentence rather than a frozen picture. The pointer is released
     * on close without closing the player - the wall still owns it.</p>
     */
    private void watchMirror() {
        double age = (System.nanoTime() - this.openedAt) / 1.0e9D;
        TileEntityVideoScreen wall = wallTile();
        if (wall == null) {
            this.wallPlayer = null;
            this.error = "大屏不在了（被拆掉，或者不在已加载区域）";
            this.notice = "";
            return;
        }
        if (wall.getVideo().isEmpty()) {
            this.wallPlayer = null;
            this.error = "这台播放器还没有设置片源：蹲下右键播放器 → 选一个视频";
            this.notice = "";
            return;
        }
        if (!wall.isPlaying()) {
            this.wallPlayer = null;
            // 右键这一下是客户端先开的窗口、服务端随后才让屏开播，所以头两秒
            // "没在播"是正常的，别把它写成一句故障。
            if (age < START_GRACE) {
                this.error = null;
                this.notice = "正在开始展示…";
            } else {
                this.error = "大屏没有在播放：合上拉杆，或者站起来右键播放器";
                this.notice = "";
            }
            return;
        }
        if (!VideoLibrary.exists(wall.getVideo())) {
            this.wallPlayer = null;
            this.error = "本机没有这个视频：" + wall.getVideo() + "\n请把它放到客户端 config/hbmzz/videos/ 目录";
            this.notice = "";
            return;
        }
        this.error = null;
        this.wallPlayer = com.hbmzz.client.video.VideoScreenPlayback.active(wall);
        this.notice = this.wallPlayer == null
                ? "正在准备画面…（本机解码还没跟上，或者这块屏已到同时解码上限）" : "";
    }

    /**
     * Follows the link state while the clip runs.
     *
     * <p>Connecting a wall mid-clip lifts the lock immediately and withdraws the
     * sentence that explained it; losing the wall puts the lock back and says so.
     * Both directions are visible in the same tick as the block entity update
     * that caused them, because the answer is read, never remembered at open
     * time.</p>
     */
    private void watchWall() {
        boolean before = this.screenLinked;
        boolean now = screenLinked();
        if (now == before) {
            return;
        }
        if (now) {
            this.hintTicks = 0;
        } else if (restricted()) {
            this.hintTicks = 40;
        }
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

    /**
     * Drops this window without touching the screen stack.
     *
     * <p>The safety net for the one thing that must never happen: a soundtrack
     * with no window. If the current screen is no longer this one and
     * {@code onGuiClosed} never ran - a world unload, a packet that replaced the
     * screen, a path that forgot - this closes the decoder anyway. The wall's own
     * decoder is left alone in mirror mode, because it was never ours.</p>
     */
    void abandon() {
        if (this.player != null) {
            this.player.close();
            this.player = null;
        }
        this.wallPlayer = null;
        this.closing = true;
        if (live == this) {
            live = null;
        }
    }

    /** Called once per client tick; see {@code VideoAudioPolicy}. */
    public static void reap(Minecraft mc) {
        GuiVideoScreen screen = live;
        if (screen != null && mc.currentScreen != screen) {
            screen.abandon();
        }
    }

    /** Closes whatever this class still holds; called when a world goes away. */
    public static void abandonLive() {
        GuiVideoScreen screen = live;
        if (screen != null) {
            screen.abandon();
        }
    }

    @Override
    public void onGuiClosed() {
        if (live == this) {
            live = null;
        }
        if (this.player != null) {
            this.player.close();
            this.player = null;
        }
        // The wall's decoder is not closed here: it belongs to the wall and is
        // still on screen in the world. Only the pointer goes.
        this.wallPlayer = null;
        // Closing the film must not leave the desktop in a display mode the
        // player did not choose. Scheduled, because displayGuiScreen() calls
        // this while it is still swapping screens and a resize mid-swap is how
        // you get a black window.
        if (this.fullscreenKnown && this.mc != null
                && this.mc.gameSettings.fullScreen != this.wasFullscreen) {
            final boolean target = this.wasFullscreen;
            this.mc.addScheduledTask(() -> {
                if (this.mc.gameSettings.fullScreen != target) {
                    this.mc.toggleFullscreen();
                }
            });
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

        if (this.mirror) {
            drawMirror();
            return;
        }
        if (this.player != null && this.player.started() && this.player.textureId() > 0) {
            drawFilm(this.player);
        }
        if (this.error != null) {
            drawError();
        } else {
            drawOverlay();
        }
        if (this.hintTicks > 0) {
            String hint = "广播进行中 · 结束后自动恢复"
                    + (restricted() ? " · 播放结束前无法退出" : " · ESC 可退出");
            this.fontRenderer.drawStringWithShadow(hint,
                    (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                    this.height / 2 + 36, AMBER);
        }
    }

    /**
     * 大屏展示: the wall's picture, the wall's sound, no player furniture.
     *
     * <p>Deliberately no progress bar and no timecode. Those belong to a window
     * that owns a clock; this one borrows the wall's, and the wall's own window
     * (right-click the screen) is where the playhead is moved. Showing a second
     * progress bar here would invite the player to drag a second playhead.</p>
     */
    private void drawMirror() {
        // Advance the wall's decoder here as well as in updateScreen: the GUI is
        // repainted every frame but the tick only runs twenty times a second, and
        // the wall behind this window may be outside the camera's view (so its
        // renderer is not called at all). Without this the picture would drop to
        // 20 fps the moment the player looked away from their own wall.
        TileEntityVideoScreen wall = wallTile();
        if (wall != null) {
            VideoPlayer film = com.hbmzz.client.video.VideoScreenPlayback.active(wall);
            if (film != null) {
                this.wallPlayer = film;
            }
        }
        if (this.wallPlayer != null && this.wallPlayer.textureId() > 0) {
            drawFilm(this.wallPlayer);
        }
        if (this.error != null) {
            drawError();
        } else if (this.wallPlayer == null) {
            drawCentre(this.notice.isEmpty() ? "正在准备画面…" : this.notice, DIM);
        }
        if (this.hintTicks > 0) {
            String hint = "大屏展示 · F 全屏 · ESC 退出 · 蹲下右键播放器设置";
            this.fontRenderer.drawStringWithShadow(hint,
                    (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                    this.height - 18, AMBER);
        }
    }

    /** One centred line, used by the mirror window's waiting states. */
    private void drawCentre(String text, int color) {
        this.fontRenderer.drawStringWithShadow(text,
                (this.width - this.fontRenderer.getStringWidth(text)) / 2,
                this.height / 2 - 4, color);
    }

    /** Letterboxed, never stretched: black bars are honest, distortion is not. */
    private void drawFilm(VideoPlayer film) {
        int frameW = film.frameWidth();
        int frameH = film.frameHeight();
        if (frameW <= 0 || frameH <= 0) {
            return;
        }
        double scale = Math.min((double) this.width / frameW, (double) this.height / frameH);
        int drawW = Math.max(1, (int) Math.round(frameW * scale));
        int drawH = Math.max(1, (int) Math.round(frameH * scale));
        int x = (this.width - drawW) / 2;
        int y = (this.height - drawH) / 2;
        drawQuad(x, y, drawW, drawH, film.textureId());
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
        String keys = "F 全屏";
        this.fontRenderer.drawStringWithShadow(keys,
                this.width - 6 - this.fontRenderer.getStringWidth(time)
                        - this.fontRenderer.getStringWidth(keys) - 12, barTop + 3, DIM);
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
        if (keyCode == Keyboard.KEY_F) {
            // 全屏是这台机器的事，不是监听器的事：F 让窗口铺满显示器，
            // 再按一次回到窗口。窗口以"进入前的状态"为准，退出时还回去。
            this.mc.toggleFullscreen();
            this.hintTicks = 40;
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (!restricted()) {
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

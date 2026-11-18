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
     * 这个展示窗口是不是"从大屏那边进来的"。
     *
     * <p>接了大屏以后，全屏这件事归大屏管：右键播放器开出来的展示窗口只是
     * 一块放大镜，不提供全屏入口（提示行会直说"全屏请右键大屏"）。真正能把
     * 画面铺满显示器的是从大屏打开的这一个 —— 右键大屏 →「全屏观看」。</p>
     */
    private final boolean wallOwned;
    /**
     * Last known link state. The block entity can be out of loaded range mid-clip
     * (the audience may walk far away), and "I cannot see the wall right now" must
     * not read as "the wall was removed" - that would slam the lock back on a
     * player who is simply not standing next to the machine any more.
     */
    private boolean screenLinked;

    /**
     * 这个窗口现在是不是"画面铺满"。
     *
     * <p>不看桌面:很多人本来就全屏玩游戏，桌面全屏时"铺满"只是把电视外壳收掉、
     * 画面顶满游戏窗口。这个字段由 F / 右键 / 菜单按钮自己记账，所以"按 F 再按 F"
     * 一定回到电视窗，不会把人从全屏游戏里踢成窗口模式。</p>
     */
    private boolean tvFullscreen;
    /** 打开这个窗口时记下的桌面状态代次，关窗还原时用它认领。 */
    private int displayGeneration;

    private VideoPlayer player;
    /** 正在拖底部那条进度条；拖的时候画面跟着松手才跳。 */
    private boolean barDragging;
    private double barDragSeconds;
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
        this.wallOwned = false;
    }

    /**
     * 大屏电视窗: the wall's own picture, in a set-shaped window in the middle of
     * the screen.
     *
     * <p>No player block of its own, no progress bar, no second soundtrack. The
     * window is a magnifying glass over a screen that is already running - it
     * cannot disagree with the wall, and it cannot play the music twice.</p>
     *
     * <p>Opened by a plain right-click on the wall. It starts as a television, not
     * as fullscreen: F (or a right-click in the window) fills the monitor, F again
     * puts the set back, ESC leaves. The menu's 「全屏观看」 is the same window with
     * that first F already pressed. It is the only window that owns the fullscreen
     * toggle while a wall is connected: the picture is the wall's, so the monitor
     * is the wall's too.</p>
     */
    public static GuiVideoScreen watch(BlockPos broadcaster, BlockPos wall) {
        return new GuiVideoScreen(broadcaster, wall, true);
    }

    private GuiVideoScreen(BlockPos broadcaster, BlockPos wall, boolean wallOwned) {
        this.pos = broadcaster;
        this.wallPos = wall == null ? null : wall.toImmutable();
        this.videoName = "";
        this.volume = 100;
        // Entered by hand, so it is never a lock: ESC always closes it.
        this.restrictBack = false;
        this.mirror = true;
        this.wallOwned = wallOwned;
    }

    /**
     * 这个窗口现在能不能按全屏。
     *
     * <p>规则只有一条：接了屏，全屏归那块屏；没接屏，全屏归播放器。所以本机
     * 播放的窗口要等"确实没有大屏"才给入口，展示窗口只有在从大屏进来时才有。</p>
     *
     * <p>例外是"接了屏，但那块屏不在本机视野里"（另一个维度、人走远了）：那时
     * 大屏那边根本点不到，本机播放又不是重复的声音，所以入口留在这里——否则
     * 玩家会在两边都按不出全屏。</p>
     */
    private boolean canFullscreen() {
        if (this.mirror) {
            return this.wallOwned;
        }
        return !screenLinked() || !wallReachable();
    }

    /** 接上的那块屏此刻在本机能不能点到（区块已加载）。 */
    private boolean wallReachable() {
        if (this.pos == null || this.mc == null || this.mc.world == null
                || !this.mc.world.isBlockLoaded(this.pos)) {
            return false;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        if (!(te instanceof TileEntityVideoBroadcaster)) {
            return false;
        }
        BlockPos wall = ((TileEntityVideoBroadcaster) te).getScreenPos();
        return wall != null && this.mc.world.isBlockLoaded(wall);
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
        // 打开时是普通游戏窗口（小框）：这里不主动改显示模式，只记下"看片之前
        // 桌面是什么样"，退出时原样还回去。全屏是玩家按 F / 右键才发生的事。
        this.displayGeneration = com.hbmzz.client.video.VideoFullscreen.capture();
        // 一进来永远是电视窗（哪怕是全屏游戏）：F 才是"铺满"那一下。
        this.tvFullscreen = false;
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
        // Closing the film must not leave the desktop in a display mode or a
        // window size the player did not choose. Scheduled, because
        // displayGuiScreen() calls this while it is still swapping screens and a
        // resize mid-swap is how you get a black window.
        if (this.mc != null) {
            final net.minecraft.client.Minecraft client = this.mc;
            final int generation = this.displayGeneration;
            client.addScheduledTask(() ->
                    com.hbmzz.client.video.VideoFullscreen.restore(generation, client));
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
        // 大屏窗口自己决定背景：小窗状态要把世界留着（不然一台电视浮在纯黑上，
        // 看着不像"摆在房间里"），全屏才是满屏黑边。
        if (this.mirror) {
            drawMirror();
            return;
        }
        drawRect(0, 0, this.width, this.height, 0xFF000000);

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
                    + (restricted() ? " · 播放结束前无法退出" : " · ESC 可退出")
                    + " · " + fullscreenHint();
            this.fontRenderer.drawStringWithShadow(hint,
                    (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                    this.height / 2 + 36, AMBER);
        }
    }

    /** 提示行里那句"现在全屏还是窗口"，按键前后各说一次。 */
    private String fullscreenHint() {
        if (!canFullscreen()) {
            // 接了大屏：这个窗口不提供全屏，入口在那块屏上。
            return "全屏请右键大屏";
        }
        return this.tvFullscreen ? "已全屏 · F / 右键 还原" : "F / 右键 全屏";
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
        boolean fullscreen = this.tvFullscreen;
        if (fullscreen) {
            drawRect(0, 0, this.width, this.height, 0xFF000000);
            if (this.wallPlayer != null && this.wallPlayer.textureId() > 0) {
                drawFilm(this.wallPlayer);
            }
        } else {
            // 世界压暗但不盖掉：这台电视是摆在房间里的，不是浮在虚空上的。
            drawRect(0, 0, this.width, this.height, 0xB4000000);
            if (this.wallPlayer != null && this.wallPlayer.textureId() > 0) {
                drawTelevision(this.wallPlayer);
            }
        }
        if (this.error != null) {
            drawError();
        } else if (this.wallPlayer == null) {
            drawCentre(this.notice.isEmpty() ? "正在准备画面…" : this.notice, DIM);
        }
        if (this.hintTicks > 0) {
            String hint = (fullscreen ? "全屏 · " : "电视窗 · ")
                    + fullscreenHint() + " · ESC 退出";
            this.fontRenderer.drawStringWithShadow(hint,
                    (this.width - this.fontRenderer.getStringWidth(hint)) / 2,
                    this.height - 18, AMBER);
        }
    }

    /**
     * 一台电视：外壳、边框、屏幕内圈、底座、电源灯，画面在屏幕位置上。
     *
     * <p>比例永远按片子自己的宽高算，所以 4:3 的片子里屏幕就是 4:3 的，不会
     * 被拉成 16:9 再补黑边——外壳是给画面做的，不是给画布做的。屏幕位置先按
     * 视口留出底座和提示行的余量，再夹一个最小尺寸：窗口再小也还是台能看的
     * 电视，而不是一条边框。</p>
     */
    private void drawTelevision(VideoPlayer film) {
        int fw = Math.max(1, film.frameWidth());
        int fh = Math.max(1, film.frameHeight());

        int maxW = Math.max(120, Math.min(this.width - 56, 640));
        int maxH = Math.max(70, this.height - 132);
        double scale = Math.min((double) maxW / fw, (double) maxH / fh);
        int pw = Math.max(48, (int) Math.round(fw * scale));
        int ph = Math.max(27, (int) Math.round(fh * scale));

        int cx = this.width / 2;
        int cy = (this.height - 24) / 2;
        int px = cx - pw / 2;
        int py = cy - ph / 2;

        int bezel = Math.max(7, Math.min(16, (int) Math.round(pw * 0.026D)));
        int shell = bezel + 4;
        int standNeck = Math.max(10, ph / 9);
        int standH = 6;

        // 外壳与底座先画，画面最后压上去：顺序错了边框就会盖住画面边缘。
        int bottom = py + ph + shell;
        int neckW = Math.max(16, pw / 7);
        drawRect(cx - neckW / 2, bottom, cx + neckW / 2, bottom + standNeck, 0xFF10141A);
        drawRect(cx - neckW / 2, bottom, cx - neckW / 2 + 2, bottom + standNeck, 0xFF1D232B);
        int baseW = Math.max(72, pw / 2);
        int baseTop = bottom + standNeck;
        drawRect(cx - baseW / 2, baseTop, cx + baseW / 2, baseTop + standH, 0xFF11151B);
        drawRect(cx - baseW / 2, baseTop, cx + baseW / 2, baseTop + 1, 0xFF2A323C);

        drawRect(px - shell, py - shell, px + pw + shell, py + ph + shell, 0xFF101419);
        drawRect(px - shell, py - shell, px + pw + shell, py - shell + 2, 0xFF2E3742);
        drawRect(px - shell, py + ph + shell - 2, px + pw + shell, py + ph + shell, 0xFF080A0D);
        drawRect(px - shell, py - shell, px - shell + 2, py + ph + shell, 0xFF1A2028);
        drawRect(px + pw + shell - 2, py - shell, px + pw + shell, py + ph + shell, 0xFF1A2028);

        drawRect(px - bezel, py - bezel, px + pw + bezel, py + ph + bezel, 0xFF262E38);
        drawRect(px - bezel, py - bezel, px + pw + bezel, py - bezel + 1, 0xFF3A4551);
        drawRect(px - shell, py + ph + shell - 9, px + pw + shell, py + ph + shell - 2, 0xFF1B222A);

        drawRect(px - 2, py - 2, px + pw + 2, py + ph + 2, 0xFF05070A);
        drawQuad(px, py, pw, ph, film.textureId());

        int ledX = px + pw + bezel - 9;
        int ledY = py + ph + bezel - 7;
        drawRect(ledX, ledY, ledX + 3, ledY + 3, film.paused() ? 0xFFF5A524 : 0xFF43D9A3);
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
        double duration = this.player.durationSeconds();
        double elapsed = this.barDragging ? this.barDragSeconds : this.player.elapsedSeconds();
        double fraction = duration > 0.0D
                ? Math.max(0.0D, Math.min(1.0D, elapsed / duration)) : this.player.percent();
        String time = timecode(elapsed) + " / " + timecode(duration);
        int barTop = this.height - 14;

        drawRect(0, barTop, this.width, this.height, 0x99000000);
        drawRect(0, barTop, (int) (this.width * fraction), this.height, AMBER);
        // 手柄：告诉玩家这条不是装饰，是可以抓的。
        int handleX = (int) (this.width * fraction);
        drawRect(Math.max(0, handleX - 1), barTop, Math.min(this.width, handleX + 1), this.height, 0xFFFFFFFF);

        this.fontRenderer.drawStringWithShadow(this.player.title(), 6, barTop + 3, 0xFFFFFFFF);
        String keys = canFullscreen() ? "F / 右键 全屏" : "全屏请右键大屏";
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
            // 全屏是这台机器的事：F 让画面铺满显示器，再按一次回到原来的窗口
            // （不是原版那个"启动尺寸"，否则窗口会突然变小）。接了大屏以后这个
            // 键不归本窗口管，只提示一句去哪儿按。
            if (canFullscreen()) {
                this.toggleFullscreen();
            } else {
                this.hintTicks = 60;
            }
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

    /**
     * 右键 = 全屏开关（只在这个窗口拥有全屏的时候）；其余点击一律吞掉。
     *
     * <p>哪块画面被放大由打开的是哪个窗口决定。没接屏时这个窗口画的是本机
     * 播放器，右键把它铺满显示器；接了大屏以后，右键播放器开出来的是"展示"
     * 窗口，这里不给全屏入口 —— 全屏要在大屏那边按（右键大屏 →「全屏观看」）。
     * 所以右键在接屏状态下只是被吞掉，不会莫名其妙改桌面模式。</p>
     *
     * <p>不调 {@code super}：进入这个界面的目的就是不让点击落到背后的世界里。</p>
     */
    @Override
    public void handleMouseInput() throws IOException {
        // 每个鼠标事件调一次（原版就是这么调的），只读当前这一个：在里面
        // while (Mouse.next()) 会把后面的移动事件吃掉，拖动就断了。
        if (org.lwjgl.input.Mouse.getEventButton() == 1
                && org.lwjgl.input.Mouse.getEventButtonState()) {
            if (canFullscreen()) {
                this.toggleFullscreen();
            } else {
                this.hintTicks = 60;
            }
            return;
        }
        // 左键交给原版分派：本机播放的窗口里那条进度条可以拖。
        super.handleMouseInput();
    }

    /**
     * 底部那条进度条的范围。
     *
     * <p>只有本机播放的窗口有它：大屏展示没有自己的播放头（进度归右键大屏的
     * 定位窗口），画一条能拖的条只会让人以为拖的是屏。</p>
     */
    private boolean overBar(int mouseX, int mouseY) {
        return !this.mirror && this.player != null && this.player.started()
                && mouseY >= this.height - 16 && mouseY <= this.height;
    }

    private double secondsAt(int mouseX) {
        double duration = this.player == null ? 0.0D : this.player.durationSeconds();
        double fraction = mouseX / (double) Math.max(1, this.width);
        return duration * Math.max(0.0D, Math.min(1.0D, fraction));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        if (mouseButton == 0 && overBar(mouseX, mouseY)) {
            this.barDragging = true;
            this.barDragSeconds = secondsAt(mouseX);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (this.barDragging) {
            this.barDragSeconds = secondsAt(mouseX);
            return;
        }
        super.mouseClickMove(mouseX, mouseY, clickedMouseButton, timeSinceLastClick);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (this.barDragging) {
            this.barDragging = false;
            if (this.player != null) {
                // seekTo 同时挪画面和音轨，所以松手之后声音不会留在原地。
                this.player.seekTo(secondsAt(mouseX));
            }
            return;
        }
        super.mouseReleased(mouseX, mouseY, state);
    }

    /** F / 右键共用的一条路，顺带在提示行里说一句现在是什么状态。 */
    private void toggleFullscreen() {
        this.tvFullscreen = !this.tvFullscreen;
        if (this.tvFullscreen) {
            com.hbmzz.client.video.VideoFullscreen.enterForPicture(this.mc);
        } else {
            com.hbmzz.client.video.VideoFullscreen.leaveForPicture(this.mc);
        }
        this.hintTicks = 60;
    }

    /**
     * 直接进全屏（已经全屏就什么都不做）。
     *
     * <p>给「全屏观看」按钮用：点一下就该是满屏，而不是先开一个窗口再让玩家
     * 自己按一次 F。显示状态的记账仍然由 {@link VideoFullscreen} 做，所以关窗
     * 时还是回到进全屏之前的那个窗口尺寸。</p>
     */
    public void enterFullscreen() {
        if (this.mc == null || !canFullscreen()) {
            return;
        }
        this.tvFullscreen = true;
        com.hbmzz.client.video.VideoFullscreen.enterForPicture(this.mc);
        this.hintTicks = 60;
    }
}

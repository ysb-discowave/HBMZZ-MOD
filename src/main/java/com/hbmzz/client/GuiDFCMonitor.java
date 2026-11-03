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
import com.hbmzz.client.screen.DfcScreen;
import com.hbmzz.client.screen.TerminalCanvas;
import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.network.DFCPageMessage;
import com.hbmzz.dfc.ConsoleRig;
import com.hbmzz.network.DFCScreenMessage;
import com.hbmzz.network.DFCTiltMessage;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.tile.TileEntityDFCMonitor;
import com.hbmzz.tile.TileEntityMonitorCamera;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * The monitor terminal: a resizable control window with a live copy of the
 * panel on the right half.
 *
 * <p>The whole window is drawn by {@link TerminalCanvas} as a single texture,
 * which is why it can carry proper typography, hairlines and rounded-ish
 * furniture that a pile of {@code drawRect} calls cannot. Only the preview is
 * drawn with GL, because it is a texture of its own.</p>
 *
 * <p>Sizing was the loudest complaint about the old terminal: it clamped the
 * window to a hard 760x480 while the player's GUI scale left only ~960x540 to
 * work with, so dragging appeared to do nothing. Here the cap is the actual
 * screen, and the size is written back per monitor.</p>
 */
public class GuiDFCMonitor extends GuiScreen {

    private static final int GRIP = 16;

    private final BlockPos pos;
    private final TerminalCanvas canvas = new TerminalCanvas();
    private final DfcScreen preview = new DfcScreen();

    private int winW;
    private int winH;
    private int left;
    private int top;

    private boolean dragging;
    private int dragStartW;
    private int dragStartH;
    private int dragStartX;
    private int dragStartY;
    private long lastTitleClick;

    public GuiDFCMonitor(BlockPos pos) {
        this.pos = pos;
    }

    private TileEntityDFCMonitor tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityDFCMonitor ? (TileEntityDFCMonitor) te : null;
    }

    /** True when this window is the one showing the monitor at {@code other}. */
    public boolean matches(BlockPos other) {
        return this.pos.equals(other);
    }

    /**
     * Called from the packet thread's scheduled task when a fresh sample lands.
     * The canvas pulls the data itself; this only has to invalidate the window.
     */
    public void onData(DFCSnapshot snapshot) {
        this.canvas.markDirty();
    }

    @Override
    public void initGui() {
        TileEntityDFCMonitor te = tile();
        int wantedW = te != null ? te.getPanelW() : HBMZZConfig.defaultWidth;
        int wantedH = te != null ? te.getPanelH() : HBMZZConfig.defaultHeight;

        // The hard cap is the usable screen: a window larger than that can never
        // be seen, dragged or closed, which is worse than a small one.
        int maxW = Math.max(240, this.width - 16);
        int maxH = Math.max(170, this.height - 16);
        int minW = Math.min(HBMZZConfig.minWidth, maxW);
        int minH = Math.min(HBMZZConfig.minHeight, maxH);

        this.winW = Math.max(minW, Math.min(maxW, wantedW));
        this.winH = Math.max(minH, Math.min(maxH, wantedH));
        this.left = (this.width - this.winW) / 2;
        this.top = (this.height - this.winH) / 2;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawRect(0, 0, this.width, this.height, 0xC8080D12);

        TileEntityDFCMonitor te = tile();
        if (te == null) {
            this.drawCenteredString(this.fontRenderer, "\u76ee\u6807\u5df2\u4e0d\u5b58\u5728",
                    this.width / 2, this.height / 2, 0xFFFF6B6B);
            return;
        }

        int page = te.getPage();
        boolean powered = te.isPowered();
        int cols = te.getScreenCols();
        int rows = te.getScreenRows();
        DFCSnapshot data = te.getClientData();
        boolean linked = data != null && data.coreFound;

        this.canvas.resize(this.winW, this.winH);
        this.canvas.setTilt(te.getTilt());
        this.canvas.render(page, powered, cols, rows, linked, coreLabel(te),
                mouseX - this.left, mouseY - this.top, this.dragging);
        this.canvas.upload();
        drawTexture(this.left, this.top, this.winW, this.winH, this.canvas.textureId());

        drawPreview(te, data, page, powered, cols, rows);

        if (this.dragging) {
            // While dragging, keep the cursor and the readout authoritative even
            // if the window stopped growing at the screen edge.
            this.drawCenteredString(this.fontRenderer, this.winW + " x " + this.winH,
                    this.left + this.winW / 2, this.top + this.winH - 6, 0xFF48D6FF);
        }
    }

    /**
     * Live panel preview.
     *
     * <p>It runs the same {@link DfcScreen} the world does, so what the player
     * sees here is literally the pixels the block will paint - if the panel is
     * mid-boot or showing a fault page, so is the preview.</p>
     */
    private void drawPreview(TileEntityDFCMonitor te, DFCSnapshot data, int page,
                             boolean powered, int cols, int rows) {
        int[] rect = this.canvas.previewRect();
        if (rect[2] < 24 || rect[3] < 18) {
            return;
        }
        long now = System.currentTimeMillis();
        long bootAt = te.getClientBootAt();
        // Fallback: start the clock here if this window is the first thing to
        // look at the panel. Without it a screen opened straight after being
        // placed read bootAt == 0, computed an elapsed time of 0 and stayed
        // frozen on the first frame of the start-up screen.
        if (powered && bootAt == 0L) {
            bootAt = now;
            te.setClientBootAt(now);
        }
        long sinceOn = powered ? Math.max(0L, now - bootAt) : 0L;
        long faultAt = te.getClientFaultAt();
        long faultElapsed = faultAt == 0L ? -1L : Math.max(0L, now - faultAt);

        this.preview.setGeometry(cols, rows);
        boolean alarm = te.isAlarmActive();
        this.preview.setCustom(te.getPanelLayout(), alarm ? Math.max(0, te.getAlarmLevel()) : -1,
                alarm ? te.getAlarmText() : "");
        TileEntityMonitorCamera.CameraView cameraView = null;
        if (te.isCameraEnabled() && te.getCameraPos() != null && this.mc != null && this.mc.world != null) {
            TileEntity cameraTe = this.mc.world.getTileEntity(te.getCameraPos());
            if (cameraTe instanceof TileEntityMonitorCamera) {
                cameraView = ((TileEntityMonitorCamera) cameraTe).capture(this.mc.world);
            }
        }
        this.preview.render(sinceOn, powered, data, page, te.getClientTempRate(), faultElapsed, cameraView);
        this.preview.upload();
        if (!this.preview.hasTexture()) {
            return;
        }

        float aspect = this.preview.textureAspect();
        int boxW = rect[2];
        int boxH = rect[3];
        int drawW = boxW;
        int drawH = Math.round(boxW / aspect);
        if (drawH > boxH) {
            drawH = boxH;
            drawW = Math.round(boxH * aspect);
        }
        int drawX = this.left + rect[0] + (boxW - drawW) / 2;
        int drawY = this.top + rect[1] + (boxH - drawH) / 2;
        drawTexture(drawX, drawY, drawW, drawH, this.preview.textureId());
    }

    private static String coreLabel(TileEntityDFCMonitor te) {
        BlockPos core = te.getCorePos();
        if (core == null) {
            return "\u81ea\u52a8\u626b\u63cf";
        }
        return "(" + core.getX() + ", " + core.getY() + ", " + core.getZ() + ")";
    }

    /**
     * The page index behind a page button.
     *
     * <p>Written out rather than computed as {@code hit - HIT_PAGE_CORE}: the
     * custom page's hit id is not adjacent to the other three, because those
     * were taken when the fourth page was added.</p>
     */
    private static int pageOf(int hit) {
        switch (hit) {
            case TerminalCanvas.HIT_PAGE_PARTS:
                return DfcScreen.PAGE_PARTS;
            case TerminalCanvas.HIT_PAGE_GRAPH:
                return DfcScreen.PAGE_GRAPH;
            case TerminalCanvas.HIT_PAGE_CUSTOM:
                return DfcScreen.PAGE_CUSTOM;
            default:
                return DfcScreen.PAGE_CORE;
        }
    }

    // ------------------------------------------------------------- interaction

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return;
        }
        int localX = mouseX - this.left;
        int localY = mouseY - this.top;
        int hit = this.canvas.hitTest(localX, localY);
        TileEntityDFCMonitor te = tile();

        switch (hit) {
            case TerminalCanvas.HIT_CLOSE:
                this.mc.displayGuiScreen(null);
                return;
            case TerminalCanvas.HIT_GRIP:
                this.dragging = true;
                this.dragStartW = this.winW;
                this.dragStartH = this.winH;
                this.dragStartX = mouseX;
                this.dragStartY = mouseY;
                return;
            case TerminalCanvas.HIT_POWER:
                if (te != null) {
                    boolean on = !te.isPowered();
                    te.setPowered(on);
                    NetworkHandler.sendToServer(new DFCPageMessage(this.pos,
                            DFCPageMessage.ACTION_POWER, on ? 1 : 0));
                    this.canvas.markDirty();
                }
                return;
            case TerminalCanvas.HIT_PAGE_CORE:
            case TerminalCanvas.HIT_PAGE_PARTS:
            case TerminalCanvas.HIT_PAGE_GRAPH:
            case TerminalCanvas.HIT_PAGE_CUSTOM:
                if (te != null) {
                    int page = pageOf(hit);
                    te.setPage(page);
                    NetworkHandler.sendToServer(new DFCPageMessage(this.pos,
                            DFCPageMessage.ACTION_PAGE, page));
                    this.canvas.markDirty();
                }
                return;
            case TerminalCanvas.HIT_STUDIO:
                // The studio is a window of its own rather than a mode inside
                // this one: it needs the whole screen for three columns, and
                // closing it should return to the terminal exactly as it was.
                this.mc.displayGuiScreen(new GuiPanelStudio(this.pos));
                return;
            case TerminalCanvas.HIT_COLS_MINUS:
            case TerminalCanvas.HIT_COLS_PLUS:
                if (te != null) {
                    int delta = hit == TerminalCanvas.HIT_COLS_PLUS ? 1 : -1;
                    applyScreenSize(te, te.getScreenCols() + delta, te.getScreenRows());
                }
                return;
            case TerminalCanvas.HIT_ROWS_MINUS:
            case TerminalCanvas.HIT_ROWS_PLUS:
                if (te != null) {
                    int delta = hit == TerminalCanvas.HIT_ROWS_PLUS ? 1 : -1;
                    applyScreenSize(te, te.getScreenCols(), te.getScreenRows() + delta);
                }
                return;
            case TerminalCanvas.HIT_TILT:
                if (te != null) {
                    // Same shape as the page buttons: move the local copy so the
                    // bar responds on the click, then let the server settle it.
                    int tilt = ConsoleRig.nextNotch(te.getTilt(), te.getScreenRows());
                    te.setTilt(tilt);
                    NetworkHandler.sendToServer(new DFCTiltMessage(this.pos, tilt));
                    this.canvas.markDirty();
                }
                return;
            case TerminalCanvas.HIT_RESET:
                this.winW = Math.min(this.width - 16, HBMZZConfig.defaultWidth);
                this.winH = Math.min(this.height - 16, HBMZZConfig.defaultHeight);
                this.left = (this.width - this.winW) / 2;
                this.top = (this.height - this.winH) / 2;
                storeSize(te);
                this.canvas.markDirty();
                return;
            default:
                // Double click on the header maximises, like any window.
                if (mouseY - this.top < 30 && mouseY >= this.top) {
                    long now = System.currentTimeMillis();
                    if (now - this.lastTitleClick < 350L) {
                        this.winW = Math.max(240, this.width - 16);
                        this.winH = Math.max(170, this.height - 16);
                        this.left = (this.width - this.winW) / 2;
                        this.top = (this.height - this.winH) / 2;
                        storeSize(te);
                        this.canvas.markDirty();
                    }
                    this.lastTitleClick = now;
                }
        }
    }

    private void applyScreenSize(TileEntityDFCMonitor te, int cols, int rows) {
        te.setScreenSize(cols, rows);
        NetworkHandler.sendToServer(new DFCScreenMessage(this.pos, te.getScreenCols(), te.getScreenRows()));
        this.canvas.markDirty();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (!this.dragging) {
            return;
        }
        int maxW = Math.max(240, this.width - 8);
        int maxH = Math.max(170, this.height - 8);
        int minW = Math.min(HBMZZConfig.minWidth, maxW);
        int minH = Math.min(HBMZZConfig.minHeight, maxH);
        this.winW = Math.max(minW, Math.min(maxW, this.dragStartW + (mouseX - this.dragStartX)));
        this.winH = Math.max(minH, Math.min(maxH, this.dragStartH + (mouseY - this.dragStartY)));
        this.left = (this.width - this.winW) / 2;
        this.top = (this.height - this.winH) / 2;
        this.canvas.markDirty();
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (this.dragging) {
            this.dragging = false;
            storeSize(tile());
            this.canvas.markDirty();
        }
    }

    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        // Wheel resize: 16px per notch is coarse enough to feel intentional and
        // fine enough to land on a size you actually want.
        int delta = wheel > 0 ? 16 : -16;
        int maxW = Math.max(240, this.width - 8);
        int maxH = Math.max(170, this.height - 8);
        int minW = Math.min(HBMZZConfig.minWidth, maxW);
        int minH = Math.min(HBMZZConfig.minHeight, maxH);
        this.winW = Math.max(minW, Math.min(maxW, this.winW + delta));
        this.winH = Math.max(minH, Math.min(maxH, this.winH + delta * 3 / 4));
        this.left = (this.width - this.winW) / 2;
        this.top = (this.height - this.winH) / 2;
        storeSize(tile());
        this.canvas.markDirty();
    }

    private void storeSize(TileEntityDFCMonitor te) {
        if (te != null) {
            // Client-side only: the window size is a per-viewer preference, and
            // pushing it through the world save would fight between players.
            te.setPanelSize(this.winW, this.winH);
        }
    }

    @Override
    public void onGuiClosed() {
        storeSize(tile());
        this.canvas.dispose();
        this.preview.dispose();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------------------------------------------------------------- drawing

    /** Full-bright textured quad in GUI space. Kept static so both callers agree. */
    private static void drawTexture(int x, int y, int w, int h, int textureId) {
        if (textureId == 0 || w <= 0 || h <= 0) {
            return;
        }
        // pushAttrib because the GUI pass runs with depth/blend in a known state
        // that later widgets depend on; leaking our setup breaks vanilla screens.
        GlStateManager.pushAttrib();
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
        // Top of the image maps to v = 0: row 0 of the uploaded canvas is the
        // top row, and GL treats row 0 as v = 0.
        buffer.pos(x, y + h, 0.0D).tex(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x + w, y + h, 0.0D).tex(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x + w, y, 0.0D).tex(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x, y, 0.0D).tex(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        tessellator.draw();

        GlStateManager.popAttrib();
    }
}

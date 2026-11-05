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

import com.hbmzz.client.screen.TermCanvas;
import com.hbmzz.ctrl.CtrlLink;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;
import com.hbmzz.ctrl.TerminalShell;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.TerminalInputMessage;
import com.hbmzz.tile.TileEntityCtrlPanel;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The terminal window of one terminal control: scrollback, an input line, and
 * six tabs for the six machines the panel is wired to.
 *
 * <p>Everything typed here goes to the server as text and comes back as text.
 * That is not a shortcut, it is the design: the client owns no authority over
 * which machine a command reaches, so a modified client gains nothing it could
 * not already do by turning a knob on the panel. What the client does own is
 * the <em>editing experience</em> - completion, history, scrolling - all of
 * which it can do locally because the panel's catalogue of keys and events is
 * already synced.</p>
 *
 * <p>The window is a sub-window of the panel: closing it returns to the panel
 * rather than to the world, so "right-click the terminal, type, escape" is one
 * gesture rather than a trip through the world.</p>
 */
public class GuiCtrlTerminal extends GuiScreen {

    private static final int MAX_HISTORY = 64;
    private static final int MAX_LINES = 400;
    /** Millisecond cap on repaints, same as the panel editor. */
    private static final long FRAME_MS = 50L;

    private final BlockPos pos;
    private final int index;

    private final TermCanvas canvas = new TermCanvas();
    private final TermCanvas.View view = new TermCanvas.View();
    private final List<String> history = new ArrayList<String>();
    private int historyAt;

    /** Live completion list and the offset it replaces. */
    private boolean suggesting;
    private int suggestStart;
    private int suggestSel;

    private float scale = 1.0F;
    private int left;
    private int top;
    private int winW;
    private int winH;

    private long lastFrame;
    private boolean frameDue = true;
    private boolean reopenPanel = true;
    private boolean closing;

    public GuiCtrlTerminal(BlockPos pos, int index) {
        this.pos = pos;
        this.index = index;
    }

    private TileEntityCtrlPanel tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityCtrlPanel ? (TileEntityCtrlPanel) te : null;
    }

    private CtrlPanel panel() {
        TileEntityCtrlPanel te = tile();
        return te == null ? null : te.getPanel();
    }

    private CtrlWidget widget() {
        CtrlPanel panel = panel();
        if (panel == null || this.index < 0) {
            return null;
        }
        CtrlWidget widget = panel.get(this.index);
        return widget != null && widget.type == CtrlType.TERMINAL ? widget : null;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void initGui() {
        this.scale = Math.min(Math.min((this.width - 8) / (float) TermCanvas.W,
                (this.height - 8) / (float) TermCanvas.H), 2.0F);
        this.winW = Math.round(TermCanvas.W * this.scale);
        this.winH = Math.round(TermCanvas.H * this.scale);
        this.left = (this.width - this.winW) / 2;
        this.top = (this.height - this.winH) / 2;

        // The banner is written once per window, not per keystroke, and it is
        // the only place the target machine's name is spelled out in full.
        if (this.view.lines.isEmpty()) {
            this.view.lines.add(new TermCanvas.Line("机器终端已连接到面板。", TerminalShell.HINT));
            this.view.lines.add(new TermCanvas.Line("  ls        列出 A–F 上绑定的机器", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  use A     切换目标机器", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  read      读它的全部数据", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  ev / set  发事件、写数值", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  其余命令见 help；Tab 补全。", TerminalShell.HINT));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawRect(0, 0, this.width, this.height, 0xC0080D12);
        refreshView();

        int lx = toLogicalX(mouseX);
        int ly = toLogicalY(mouseY);
        long now = System.currentTimeMillis();
        if (this.frameDue || now - this.lastFrame >= FRAME_MS || this.canvas.textureId() == 0) {
            this.frameDue = false;
            this.lastFrame = now;
            this.canvas.render(this.view, lx, ly);
            this.canvas.upload();
        }
        drawTexture(this.left, this.top, this.winW, this.winH, this.canvas.textureId());
    }

    /**
     * Rebuilds the view from the tile: slot names, current target, prompt.
     *
     * <p>Called every frame instead of kept in a field, because the panel is
     * the truth. Binding a machine in the other window while this one is open
     * should show up here on the next frame, not on the next reopen.</p>
     */
    private void refreshView() {
        CtrlPanel panel = panel();
        CtrlWidget widget = widget();
        if (panel == null || widget == null) {
            // The block or the control went away under us. Closing is better
            // than typing into a window that no longer has a addressee.
            if (!this.closing) {
                this.reopenPanel = false;
                this.mc.displayGuiScreen(null);
            }
            return;
        }
        for (int i = 0; i < CtrlPanel.LINKS; i++) {
            CtrlLink link = panel.links[i];
            this.view.slots[i] = link.isEmpty() ? null : link.label;
        }
        this.view.selected = widget.link;
        this.view.panelName = panel.name;
        this.view.subtitle = widget.link < 0 ? "无目标"
                : CtrlLink.letter(widget.link) + " · " + panel.links[widget.link].label;
        this.view.prompt = (widget.link < 0 ? "-" : CtrlLink.letter(widget.link)) + ">";
        this.view.input = this.input;
        this.view.caret = this.caret;
        this.view.scroll = this.scroll;
        this.view.suggest.clear();
        if (this.suggesting && !this.candidates.isEmpty()) {
            this.view.suggest.addAll(this.candidates);
            this.view.suggestSel = this.suggestSel;
        }
    }

    // ----------------------------------------------------------------- input

    private String input = "";
    private int caret;
    private int scroll;
    private String status = "";

    private final List<String> candidates = new ArrayList<String>();

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        this.frameDue = true;
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                close();
                return;
            case Keyboard.KEY_RETURN:
            case Keyboard.KEY_NUMPADENTER:
                if (this.suggesting) {
                    // Enter with a list open accepts the highlighted candidate
                    // and runs the line - the shell behaviour most people
                    // already have in their fingers.
                    acceptSuggestion();
                }
                submit();
                return;
            case Keyboard.KEY_TAB:
                complete(isShiftKeyDown() ? -1 : 1);
                return;
            case Keyboard.KEY_UP:
                if (this.suggesting) {
                    moveSuggestion(-1);
                } else {
                    recall(-1);
                }
                return;
            case Keyboard.KEY_DOWN:
                if (this.suggesting) {
                    moveSuggestion(1);
                } else {
                    recall(1);
                }
                return;
            case Keyboard.KEY_LEFT:
                this.caret = Math.max(0, this.caret - 1);
                this.suggesting = false;
                return;
            case Keyboard.KEY_RIGHT:
                this.caret = Math.min(this.input.length(), this.caret + 1);
                this.suggesting = false;
                return;
            case Keyboard.KEY_HOME:
                this.caret = 0;
                return;
            case Keyboard.KEY_END:
                this.caret = this.input.length();
                return;
            case Keyboard.KEY_BACK:
                if (this.caret > 0) {
                    this.input = this.input.substring(0, this.caret - 1) + this.input.substring(this.caret);
                    this.caret--;
                }
                this.suggesting = false;
                return;
            case Keyboard.KEY_DELETE:
                if (this.caret < this.input.length()) {
                    this.input = this.input.substring(0, this.caret) + this.input.substring(this.caret + 1);
                }
                this.suggesting = false;
                return;
            case Keyboard.KEY_PRIOR:
                this.scroll = Math.min(this.view.lines.size(), this.scroll + TermCanvas.visibleLines() - 1);
                return;
            case Keyboard.KEY_NEXT:
                this.scroll = Math.max(0, this.scroll - TermCanvas.visibleLines() + 1);
                return;
            case Keyboard.KEY_V:
                if (isCtrlKeyDown()) {
                    paste();
                    return;
                }
                break;
            default:
                break;
        }
        if (typedChar >= 32 && typedChar != 127) {
            insert(String.valueOf(typedChar));
        }
    }

    private void insert(String text) {
        if (this.input.length() + text.length() > TerminalShell.MAX_LINE) {
            this.status = "命令最长 " + TerminalShell.MAX_LINE + " 字符";
            return;
        }
        this.input = this.input.substring(0, this.caret) + text + this.input.substring(this.caret);
        this.caret += text.length();
        this.suggesting = false;
        this.scroll = 0;
    }

    private void paste() {
        String clip = getClipboardString();
        if (clip == null) {
            return;
        }
        String flat = clip.replace('\n', ' ').replace('\r', ' ').trim();
        if (flat.length() > TerminalShell.MAX_LINE) {
            flat = flat.substring(0, TerminalShell.MAX_LINE);
        }
        insert(flat);
    }

    // -------------------------------------------------------------- commands

    private void submit() {
        String line = this.input.trim();
        this.suggesting = false;
        if (line.isEmpty()) {
            return;
        }
        echo(this.view.prompt + " " + line);
        this.history.add(line);
        while (this.history.size() > MAX_HISTORY) {
            this.history.remove(0);
        }
        this.historyAt = this.history.size();
        this.input = "";
        this.caret = 0;
        this.scroll = 0;
        NetworkHandler.sendToServer(new TerminalInputMessage(this.pos, this.index, line));
    }

    private void echo(String text) {
        append(new TermCanvas.Line(text, TerminalShell.ECHO));
    }

    private void append(TermCanvas.Line line) {
        this.view.lines.add(line);
        while (this.view.lines.size() > MAX_LINES) {
            this.view.lines.remove(0);
        }
    }

    private void recall(int direction) {
        if (this.history.isEmpty()) {
            return;
        }
        this.historyAt = Math.max(0, Math.min(this.history.size(), this.historyAt + direction));
        this.input = this.historyAt >= this.history.size() ? "" : this.history.get(this.historyAt);
        this.caret = this.input.length();
    }

    // ------------------------------------------------------------ completion

    /**
     * Tab. First press computes the list and applies its first entry; further
     * presses walk the list. The list is stored, not recomputed, so cycling
     * cannot collapse to one candidate the moment the line is complete.
     */
    private void complete(int direction) {
        if (!this.suggesting) {
            TerminalShell.Completion completion =
                    TerminalShell.complete(panel(), widget(), this.input, this.caret);
            if (completion.candidates.isEmpty()) {
                this.status = "没有可补全的内容";
                return;
            }
            this.candidates.clear();
            this.candidates.addAll(completion.candidates);
            this.suggestStart = completion.start;
            this.suggestSel = direction < 0 ? this.candidates.size() - 1 : 0;
            this.suggesting = true;
            applySuggestion();
            return;
        }
        moveSuggestion(direction);
    }

    private void moveSuggestion(int direction) {
        if (this.candidates.isEmpty()) {
            return;
        }
        this.suggestSel = Math.floorMod(this.suggestSel + direction, this.candidates.size());
        applySuggestion();
    }

    private void applySuggestion() {
        if (this.candidates.isEmpty()) {
            return;
        }
        String candidate = this.candidates.get(Math.max(0, Math.min(this.candidates.size() - 1, this.suggestSel)));
        this.input = this.input.substring(0, this.suggestStart) + candidate + this.input.substring(this.caret);
        this.caret = this.suggestStart + candidate.length();
        // A finished word wants a space after it; a "var=" wants the value.
        boolean word = !candidate.endsWith("=") && this.caret == this.input.length();
        if (word && this.candidates.size() == 1) {
            this.input = this.input + " ";
            this.caret++;
        }
        this.status = this.candidates.size() == 1 ? "已补全" : "Tab 切换 · " + this.candidates.size() + " 项";
    }

    private void acceptSuggestion() {
        this.suggesting = false;
        this.candidates.clear();
    }

    // ----------------------------------------------------------------- reply

    /** Called from the packet bridge on the client thread. */
    public void onReply(BlockPos where, int which, int slot, boolean clear, String kinds, String text) {
        if (!this.pos.equals(where) || which != this.index) {
            return;
        }
        this.frameDue = true;
        if (clear) {
            this.view.lines.clear();
        }
        String[] lines = text.isEmpty() ? new String[0] : text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int kind = i < kinds.length() ? kinds.charAt(i) - '0' : TerminalShell.OUT;
            append(new TermCanvas.Line(lines[i], kind));
        }
        if (slot >= 0) {
            this.status = "目标 " + CtrlLink.letter(slot);
        }
        this.scroll = 0;
    }

    // ----------------------------------------------------------------- mouse

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.frameDue = true;
        int lx = toLogicalX(mouseX);
        int ly = toLogicalY(mouseY);
        if (this.canvas.closeAt(lx, ly)) {
            close();
            return;
        }
        int slot = this.canvas.slotAt(lx, ly);
        if (slot >= 0) {
            // Switching targets goes through the same command the operator
            // would type, so there is one code path and one set of rules.
            this.input = "use " + CtrlLink.letter(slot);
            submit();
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && this.mc.currentScreen == this) {
            int step = wheel > 0 ? 3 : -3;
            this.scroll = Math.max(0, Math.min(this.view.lines.size(), this.scroll + step));
            this.frameDue = true;
        }
    }

    // ----------------------------------------------------------------- close

    private void close() {
        this.closing = true;
        TileEntityCtrlPanel te = tile();
        this.reopenPanel = te != null && widget() != null;
        this.mc.displayGuiScreen(this.reopenPanel ? new GuiCtrlPanel(this.pos) : null);
    }

    @Override
    public void onGuiClosed() {
        // displayGuiScreen replaces the screen without closing it first, so a
        // close from close() must not be counted twice; the flag also keeps the
        // texture from leaking when the window is replaced by another one.
        this.canvas.dispose();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ util

    private int toLogicalX(int mouseX) {
        return Math.round((mouseX - this.left) / this.scale);
    }

    private int toLogicalY(int mouseY) {
        return Math.round((mouseY - this.top) / this.scale);
    }

    /** Full-bright textured quad, same recipe as the panel editor's. */
    private static void drawTexture(int x, int y, int w, int h, int textureId) {
        if (textureId == 0 || w <= 0 || h <= 0) {
            return;
        }
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
        buffer.pos(x, y + h, 0.0D).tex(0.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x + w, y + h, 0.0D).tex(1.0F, 1.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x + w, y, 0.0D).tex(1.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        buffer.pos(x, y, 0.0D).tex(0.0F, 0.0F).color(255, 255, 255, 255).endVertex();
        tessellator.draw();

        GlStateManager.popAttrib();
    }
}

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
import com.hbmzz.cecp.controls.addon.TerminalControl;
import com.hbmzz.cecp.tile.TileEntityCtrlConsole;
import com.hbmzz.ctrl.CtrlLink;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.SnapshotHost;
import com.hbmzz.ctrl.TerminalShell;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.TerminalInputMessage;
import com.hbmzz.network.TerminalReplyMessage;
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
    /** {@link TerminalInputMessage#KIND_PANEL} or {@code KIND_CONSOLE}. */
    private final int kind;

    private final TermCanvas canvas = new TermCanvas();
    private final TermCanvas.View view = new TermCanvas.View();
    /** Slots and vocabulary as the server last described them. */
    private final SnapshotHost snapshot = new SnapshotHost();
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
        this(TerminalInputMessage.KIND_PANEL, pos, index);
    }

    public GuiCtrlTerminal(int kind, BlockPos pos, int index) {
        this.kind = kind;
        this.pos = pos;
        this.index = index;
    }

    private boolean isConsole() {
        return this.kind == TerminalInputMessage.KIND_CONSOLE;
    }

    private TileEntityCtrlPanel tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityCtrlPanel ? (TileEntityCtrlPanel) te : null;
    }

    /** The ported console this window belongs to, or null. */
    private TileEntityCtrlConsole console() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityCtrlConsole ? (TileEntityCtrlConsole) te : null;
    }

    /**
     * Does the thing this window is attached to still exist?
     *
     * <p>Checked per frame, because the block can be broken or the control
     * deleted while the window is open - and typing into a window with no
     * addressee is worse than being kicked out of it.</p>
     */
    private boolean addresseeAlive() {
        if (isConsole()) {
            TileEntityCtrlConsole console = console();
            if (console == null || console.panel == null
                    || this.index < 0 || this.index >= console.panel.controls.size()) {
                return false;
            }
            return console.panel.controls.get(this.index) instanceof TerminalControl;
        }
        CtrlPanel panel = panel();
        return panel != null && widget(panel) != null;
    }

    private CtrlPanel panel() {
        TileEntityCtrlPanel te = tile();
        return te == null ? null : te.getPanel();
    }

    private com.hbmzz.ctrl.CtrlWidget widget(CtrlPanel panel) {
        if (panel == null || this.index < 0) {
            return null;
        }
        com.hbmzz.ctrl.CtrlWidget widget = panel.get(this.index);
        return widget != null && widget.type == com.hbmzz.ctrl.CtrlType.TERMINAL ? widget : null;
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
            this.view.lines.add(new TermCanvas.Line("机器终端已连接。", TerminalShell.HINT));
            this.view.lines.add(new TermCanvas.Line("  ls        列出已绑定的机器", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  use A     切换目标机器", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  read      读它的全部数据", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  ev / set  发事件、写数值", TerminalShell.OUT));
            this.view.lines.add(new TermCanvas.Line("  其余命令见 help；Tab 补全。", TerminalShell.HINT));
            if (this.isConsole()) {
                this.view.lines.add(new TermCanvas.Line(
                        "  这台面板的槽位就是联动器里起的名字，use 后面可以写名字。", TerminalShell.HINT));
            }
        }
        seedSnapshot();
        // Ask the server for the authoritative slot list and vocabulary; the
        // local copy above is only there so the strip is never briefly empty.
        NetworkHandler.sendToServer(new TerminalInputMessage(this.kind, this.pos, this.index, ""));
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
     * Fills the snapshot from the local panel copy, before the server answers.
     *
     * <p>Only so the window does not open with six blank tabs; the reply
     * overwrites every field a moment later.</p>
     */
    private void seedSnapshot() {
        this.snapshot.clear();
        if (this.isConsole()) {
            TileEntityCtrlConsole console = console();
            if (console == null || console.panel == null
                    || this.index < 0 || this.index >= console.panel.controls.size()) {
                return;
            }
            if (!(console.panel.controls.get(this.index) instanceof TerminalControl)) {
                return;
            }
            TerminalControl control = (TerminalControl) console.panel.controls.get(this.index);
            for (String tag : control.tags()) {
                this.snapshot.add(tag, tag, true);
            }
            this.snapshot.target(control.getTarget());
            return;
        }
        CtrlPanel panel = panel();
        com.hbmzz.ctrl.CtrlWidget widget = widget(panel);
        if (panel == null || widget == null) {
            return;
        }
        for (int i = 0; i < CtrlPanel.LINKS; i++) {
            CtrlLink link = panel.links[i];
            boolean bound = !link.isEmpty();
            this.snapshot.add(CtrlLink.letter(i), bound ? link.label : "未绑定", bound);
            if (bound) {
                this.snapshot.vocab(i, link.keys, link.events, link.vars);
            }
        }
        this.snapshot.target(widget.link);
    }

    /**
     * Rebuilds the view from what the server last said.
     *
     * <p>Called every frame instead of kept in a field, because the panel is
     * the truth. Binding a machine in the other window while this one is open
     * should show up here on the next frame, not on the next reopen.</p>
     */
    private void refreshView() {
        if (!addresseeAlive()) {
            // The block or the control went away under us. Closing is better
            // than typing into a window that no longer has a addressee.
            if (!this.closing) {
                this.reopenPanel = false;
                this.mc.displayGuiScreen(null);
            }
            return;
        }
        int target = this.snapshot.target();
        for (int i = 0; i < TermCanvas.LINKS; i++) {
            boolean bound = i < this.snapshot.slots() && this.snapshot.bound(i);
            this.view.slots[i] = bound ? this.snapshot.label(i) : null;
        }
        this.view.selected = target >= 0 && target < TermCanvas.LINKS ? target : -1;
        this.view.panelName = this.isConsole() ? "CE 控制台" : panel() == null ? "" : panel().name;
        String label = target < 0 ? "" : this.snapshot.label(target);
        String name = target < 0 ? "" : this.snapshot.name(target);
        // A group's name already begins with its label ("RBMK 反应堆 · 12 部件"),
        // so repeating the label in front of it would read "RBMK · RBMK ...".
        String detail = name == null || name.isEmpty() ? label : name;
        this.view.subtitle = target < 0 ? "无目标"
                : (detail.startsWith(label) ? detail : label + " · " + detail);
        this.view.prompt = (target < 0 ? "-" : label) + ">";
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
        NetworkHandler.sendToServer(new TerminalInputMessage(this.kind, this.pos, this.index, line));
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
                    TerminalShell.complete(this.snapshot, this.input, this.caret);
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
    public void onReply(int kind, BlockPos where, int which, int slot, boolean clear,
                        String kinds, String text, String labels, String names,
                        String keys, String events, String vars) {
        if (kind != this.kind || !this.pos.equals(where) || which != this.index) {
            return;
        }
        this.frameDue = true;
        if (!labels.isEmpty()) {
            this.snapshot.clear();
            java.util.List<String> labelList = TerminalReplyMessage.split(labels, 32);
            java.util.List<String> nameList = TerminalReplyMessage.split(names, 32);
            for (int i = 0; i < labelList.size(); i++) {
                // The name is what the header shows; before grouping it was the
                // same string as the label, and every older reply still sends
                // an empty list here, so fall back rather than show blanks.
                String name = i < nameList.size() && !nameList.get(i).isEmpty()
                        ? nameList.get(i) : labelList.get(i);
                this.snapshot.add(labelList.get(i), name, true);
            }
        }
        this.snapshot.target(slot);
        if (slot >= 0) {
            this.snapshot.vocab(slot,
                    TerminalReplyMessage.split(keys, 32),
                    TerminalReplyMessage.split(events, 32),
                    pairs(TerminalReplyMessage.split(vars, 16)));
        }
        if (clear) {
            this.view.lines.clear();
        }
        String[] lines = text.isEmpty() ? new String[0] : text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineKind = i < kinds.length() ? kinds.charAt(i) - '0' : TerminalShell.OUT;
            append(new TermCanvas.Line(lines[i], lineKind));
        }
        if (slot >= 0) {
            String label = this.snapshot.label(slot);
            this.status = "目标 " + (label == null || label.isEmpty() ? String.valueOf(slot) : label);
        }
        this.scroll = 0;
    }

    /** "event var" lines back into the map completion uses. */
    private static java.util.Map<String, String> pairs(java.util.List<String> lines) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (String line : lines) {
            int space = line.indexOf(' ');
            if (space > 0) {
                out.put(line.substring(0, space), line.substring(space + 1).trim());
            }
        }
        return out;
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
            if (!this.snapshot.bound(slot)) {
                return;
            }
            // Switching targets goes through the same command the operator
            // would type, so there is one code path and one set of rules.
            String label = this.snapshot.label(slot);
            this.input = "use " + (label == null || label.isEmpty() ? String.valueOf(slot) : label);
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
        // Only this mod's panel has a window to go back to; the ported console
        // is operated from the world, so its terminal closes to the world.
        this.reopenPanel = !this.isConsole() && tile() != null
                && widget(panel()) != null;
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

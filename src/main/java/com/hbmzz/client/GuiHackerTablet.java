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

import com.hbmzz.hack.HackService;
import com.hbmzz.hack.HackState;
import com.hbmzz.network.HackerCommandMessage;
import com.hbmzz.network.HackerReplyMessage;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The tablet's window: a terminal, not a form.
 *
 * <h3>Why a command line</h3>
 * <p>The feature is "type a command and a machine's value changes". A grid of
 * buttons would need one button per field of every machine in the mod, and it
 * would still not cover the fields nobody has thought of yet. A line of text
 * does, and it is also what the fiction asks for: somebody who has broken into
 * the plant's telemetry is past the point of using the interface that was
 * offered to them.</p>
 *
 * <h3>What is drawn here and what is not</h3>
 * <p>The window owns no truth. Everything it paints comes from the last
 * {@link HackerReplyMessage} - the printed lines, the target, and the world's
 * intrusion state. It does not remember which machine it wrote to and it cannot
 * ask for one; the server decides what the typed line means, exactly as it does
 * for the panel terminal. The one thing it does locally is {@code clear}, which
 * only affects this window's own buffer.</p>
 *
 * <h3>The ERROR screen</h3>
 * <p>When the core signal is cut the window stops being a terminal and becomes
 * a red field reading {@code ERROR#！}. The command line survives at the bottom
 * - only {@code restore} is accepted - because a full-screen failure state with
 * no way out is a trap, not a feature. The cut also expires on its own after
 * {@link HackState#cutTicks}.</p>
 */
public class GuiHackerTablet extends GuiScreen {

    private static final int W = 340;
    private static final int H = 210;
    private static final int HEADER_H = 14;
    private static final int FOOTER_H = 20;
    private static final int STATUS_H = 12;
    private static final int ROW = 10;
    private static final int MAX_LOG = 240;
    /** Ticks between silent state polls, so a cut fired elsewhere shows up here. */
    private static final int POLL_TICKS = 20;

    // Palette. Green phosphor for normal text, red for anything the machine
    // refused, amber for the "you just broke something" notice.
    private static final int BG = 0xF0060A07;
    private static final int FRAME = 0xFF00B34A;
    private static final int FRAME_DIM = 0xFF0A3D1C;
    private static final int BAR = 0xFF0A1A0E;
    private static final int GREEN = 0xFF7CFF9B;
    private static final int GREEN_BRIGHT = 0xFF2BFF6A;
    private static final int GREEN_DIM = 0xFF2E7A45;
    private static final int CYAN = 0xFF35F0C8;
    private static final int AMBER = 0xFFFFC24A;
    private static final int RED = 0xFFFF3B3B;
    private static final int RED_DARK = 0xFF5A0D0D;

    private final Deque<HackService.Line> log = new ArrayDeque<HackService.Line>();
    private final List<String> history = new ArrayList<String>();

    private String input = "";
    private int historyIndex = -1;
    private int scroll;

    private int state = HackState.NORMAL;
    private int ticksLeft;
    private boolean hasTarget;
    private int targetX;
    private int targetY;
    private int targetZ;
    private String targetLabel = "";

    private int pollTimer = POLL_TICKS;
    private long openedAt;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void initGui() {
        if (this.openedAt == 0L) {
            this.openedAt = System.currentTimeMillis();
            banner();
            // Opening the window is also the one moment where the server has to
            // be told that somebody is looking, so the state strip is right
            // before the first keypress. An empty line is that request.
            NetworkHandler.sendToServer(new HackerCommandMessage(""));
        }
    }

    private void banner() {
        say(HackService.TONE_HEAD, "BLACKICE // HBMZZ-NET  v1.0");
        say(HackService.TONE_DIM, "uplink established - 127.0.0.1:25565");
        say(HackService.TONE_TEXT, "输入 help 查看指令。目标机器需要先 target 锁定。");
    }

    @Override
    public void updateScreen() {
        if (--this.pollTimer <= 0) {
            this.pollTimer = POLL_TICKS;
            NetworkHandler.sendToServer(new HackerCommandMessage(""));
        }
    }

    /** Called from {@code ClientPacketBridge} on the client tick thread. */
    public void accept(HackerReplyMessage message) {
        this.state = message.state();
        this.ticksLeft = message.ticksLeft();
        this.hasTarget = message.hasTarget();
        this.targetX = message.targetX();
        this.targetY = message.targetY();
        this.targetZ = message.targetZ();
        this.targetLabel = message.targetLabel();
        for (HackService.Line line : message.lines()) {
            say(line.tone, line.text);
        }
        // A reply that pushed new output should put the view back at the
        // bottom: a player who just typed something wants to see its answer,
        // not the place they had scrolled to ten seconds ago.
        if (!message.lines().isEmpty()) {
            this.scroll = 0;
        }
    }

    private void say(int tone, String text) {
        this.log.addLast(new HackService.Line(tone, text == null ? "" : text));
        while (this.log.size() > MAX_LOG) {
            this.log.removeFirst();
        }
    }

    // --------------------------------------------------------------------- input

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // Escape closes the window - that is handled by GuiScreen before this
        // point, and swallowing it here would trap the player.
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            submit();
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!this.input.isEmpty()) {
                this.input = this.input.substring(0, this.input.length() - 1);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_UP) {
            recall(-1);
            return;
        }
        if (keyCode == Keyboard.KEY_DOWN) {
            recall(1);
            return;
        }
        if (keyCode == Keyboard.KEY_PRIOR) {
            this.scroll = Math.min(this.scroll + visibleRows() - 1, Math.max(0, this.log.size() - 1));
            return;
        }
        if (keyCode == Keyboard.KEY_NEXT) {
            this.scroll = Math.max(0, this.scroll - (visibleRows() - 1));
            return;
        }
        if (keyCode == Keyboard.KEY_HOME) {
            this.scroll = Math.max(0, this.log.size() - visibleRows());
            return;
        }
        if (keyCode == Keyboard.KEY_END) {
            this.scroll = 0;
            return;
        }
        // Ctrl+V. Minecraft delivers the paste as U+0016 rather than as a key.
        if (typedChar == 22) {
            String clip = GuiScreen.getClipboardString();
            if (clip != null) {
                String flat = clip.replace('\n', ' ').replace('\r', ' ');
                this.input = clip(this.input + flat, HackService.MAX_LINE);
            }
            return;
        }
        if (typedChar >= ' ' && typedChar != 127 && this.input.length() < HackService.MAX_LINE) {
            this.input += typedChar;
        }
    }

    private void recall(int direction) {
        if (this.history.isEmpty()) {
            return;
        }
        int next = this.historyIndex + direction;
        if (next < -1) {
            next = -1;
        }
        if (next >= this.history.size()) {
            next = this.history.size() - 1;
        }
        this.historyIndex = next;
        this.input = next < 0 ? "" : this.history.get(next);
    }

    private void submit() {
        String line = this.input.trim();
        this.input = "";
        this.historyIndex = -1;
        if (line.isEmpty()) {
            return;
        }
        if ("clear".equalsIgnoreCase(line) || "cls".equalsIgnoreCase(line)) {
            this.log.clear();
            this.scroll = 0;
            return;
        }
        // Echo first: the round trip is at least a tick, and a terminal that
        // shows nothing until the server answers feels like it dropped the line.
        say(HackService.TONE_HEAD, "> " + line);
        remember(line);
        NetworkHandler.sendToServer(new HackerCommandMessage(line));
    }

    private void remember(String line) {
        if (!this.history.isEmpty() && this.history.get(this.history.size() - 1).equals(line)) {
            return;
        }
        this.history.add(line);
        while (this.history.size() > 40) {
            this.history.remove(0);
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int room = Math.max(0, this.log.size() - visibleRows());
        this.scroll += wheel > 0 ? 2 : -2;
        this.scroll = Math.max(0, Math.min(this.scroll, room));
    }

    @Override
    public boolean doesGuiPauseGame() {
        // A screen that freezes the world would stop the very reactors this
        // tablet is meant to be poking at while it is open.
        return false;
    }

    // -------------------------------------------------------------------- drawing

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - H) / 2;

        drawRect(x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, FRAME_DIM);
        drawRect(x0, y0, x0 + W, y0 + H, BG);
        frame(x0, y0);

        if (this.state == HackState.CUT) {
            drawCutScreen(x0, y0);
            return;
        }

        drawHeader(x0, y0);
        drawLog(x0, y0);
        drawStatus(x0, y0);
        drawPrompt(x0, y0);
    }

    private void frame(int x0, int y0) {
        drawRect(x0, y0, x0 + W, y0 + 1, FRAME);
        drawRect(x0, y0 + H - 1, x0 + W, y0 + H, FRAME);
        drawRect(x0, y0, x0 + 1, y0 + H, FRAME);
        drawRect(x0 + W - 1, y0, x0 + W, y0 + H, FRAME);
    }

    private void drawHeader(int x0, int y0) {
        drawRect(x0 + 1, y0 + 1, x0 + W - 1, y0 + HEADER_H, BAR);
        drawRect(x0 + 1, y0 + HEADER_H, x0 + W - 1, y0 + HEADER_H + 1, FRAME_DIM);
        String title = "BLACKICE";
        this.fontRenderer.drawStringWithShadow(title, x0 + 6, y0 + 4, GREEN_BRIGHT);
        String sub = "// remote field writer";
        this.fontRenderer.drawString(sub, x0 + 6 + this.fontRenderer.getStringWidth(title) + 4,
                y0 + 4, GREEN_DIM);

        // Right side: the link state, in the colour of its severity.
        String tag = HackState.name(this.state);
        int color = this.state == HackState.DEGRADED ? AMBER : GREEN;
        int tagW = this.fontRenderer.getStringWidth(tag);
        this.fontRenderer.drawString(tag, x0 + W - 8 - tagW, y0 + 4, color);
        drawRect(x0 + W - 8 - tagW - 6, y0 + 6, x0 + W - 8 - tagW - 4, y0 + 8,
                blink() ? color : GREEN_DIM);
    }

    private void drawLog(int x0, int y0) {
        int top = y0 + HEADER_H + 3;
        int bottom = y0 + H - FOOTER_H - STATUS_H - 2;
        int rows = (bottom - top) / ROW;

        List<HackService.Line> all = new ArrayList<HackService.Line>(this.log);
        int end = all.size() - this.scroll;
        int start = Math.max(0, end - rows);
        end = Math.max(0, Math.min(end, all.size()));

        int y = top;
        for (int i = start; i < end; i++) {
            HackService.Line line = all.get(i);
            this.fontRenderer.drawString(ellipsis(line.text, W - 14), x0 + 7, y, toneColor(line.tone));
            y += ROW;
        }
        // A caret where the next line will land, so an empty log still reads as
        // a live terminal rather than a blank panel.
        if (all.isEmpty()) {
            this.fontRenderer.drawString("_", x0 + 7, top, GREEN_DIM);
        }
        if (this.scroll > 0) {
            String hint = "^ " + this.scroll;
            this.fontRenderer.drawString(hint, x0 + W - 8 - this.fontRenderer.getStringWidth(hint),
                    bottom - ROW, GREEN_DIM);
        }
    }

    private void drawStatus(int x0, int y0) {
        int y = y0 + H - FOOTER_H - STATUS_H;
        drawRect(x0 + 1, y, x0 + W - 1, y + 1, FRAME_DIM);
        String left;
        int color;
        if (this.hasTarget) {
            left = "TARGET " + this.targetX + " " + this.targetY + " " + this.targetZ;
            color = this.state == HackState.DEGRADED ? AMBER : GREEN;
        } else {
            left = "TARGET: NONE";
            color = GREEN_DIM;
        }
        this.fontRenderer.drawString(left, x0 + 7, y + 3, color);
        String right = this.hasTarget ? this.targetLabel : "use target <x> <y> <z>";
        this.fontRenderer.drawString(ellipsis(right, 150),
                x0 + W - 8 - this.fontRenderer.getStringWidth(ellipsis(right, 150)), y + 3,
                GREEN_DIM);
    }

    private void drawPrompt(int x0, int y0) {
        int y = y0 + H - FOOTER_H;
        drawRect(x0 + 1, y, x0 + W - 1, y + 1, FRAME_DIM);
        this.fontRenderer.drawString(">", x0 + 7, y + 5, GREEN_BRIGHT);
        String shown = ellipsis(this.input, W - 34);
        this.fontRenderer.drawString(shown, x0 + 17, y + 5, GREEN);
        // Blinking block after the typed text, which is the one thing that makes
        // a text field look like it is waiting for you.
        if (blink()) {
            int caretX = x0 + 17 + this.fontRenderer.getStringWidth(shown);
            drawRect(caretX, y + 4, caretX + 4, y + 12, GREEN_BRIGHT);
        }
    }

    /**
     * The failure page: everything is ERROR#！, everything is red, and the only
     * thing that still works is the command that undoes it.
     */
    private void drawCutScreen(int x0, int y0) {
        boolean flash = blink();
        drawRect(x0 + 1, y0 + 1, x0 + W - 1, y0 + H - 1, flash ? 0x66200000 : 0x33100000);

        String text = "ERROR#\uFF01";
        int scale = 3;
        int textW = this.fontRenderer.getStringWidth(text) * scale;
        int cx = x0 + W / 2 - textW / 2;
        int cy = y0 + H / 2 - 22;

        GlStateManager.pushMatrix();
        GlStateManager.translate(cx, cy, 0.0F);
        GlStateManager.scale((float) scale, (float) scale, 1.0F);
        this.fontRenderer.drawStringWithShadow(text, 0, 0, flash ? RED : 0xFF8A1414);
        GlStateManager.popMatrix();

        String sub = "CORE SIGNAL SEVERED / \u5806\u82af\u4fe1\u53f7\u5df2\u4e2d\u65ad";
        this.fontRenderer.drawString(sub, x0 + W / 2 - this.fontRenderer.getStringWidth(sub) / 2,
                y0 + H / 2 + 14, flash ? 0xFFFF8080 : 0xFF8A1414);

        String timer = "AUTO-RECOVER IN " + Math.max(0, this.ticksLeft / 20) + "s";
        this.fontRenderer.drawString(timer, x0 + W / 2 - this.fontRenderer.getStringWidth(timer) / 2,
                y0 + H / 2 + 28, 0xFF6E2020);

        // Hazard bands top and bottom, flashing with the rest.
        if (flash) {
            hazard(x0 + 1, y0 + 1, W - 2);
            hazard(x0 + 1, y0 + H - 7, W - 2);
        }

        int y = y0 + H - FOOTER_H;
        drawRect(x0 + 1, y, x0 + W - 1, y + 1, 0xFF601010);
        this.fontRenderer.drawString("> ", x0 + 7, y + 5, RED);
        String shown = ellipsis(this.input, W - 34);
        this.fontRenderer.drawString(shown, x0 + 17, y + 5, 0xFFFF9A9A);
        if (blink()) {
            int caretX = x0 + 17 + this.fontRenderer.getStringWidth(shown);
            drawRect(caretX, y + 4, caretX + 4, y + 12, RED);
        }
        String hint = "only \"restore\" is accepted";
        this.fontRenderer.drawString(hint, x0 + W - 8 - this.fontRenderer.getStringWidth(hint),
                y + 5, 0xFF6E2020);
    }

    private void hazard(int x, int y, int w) {
        drawRect(x, y, x + w, y + 6, 0xFFFF2A2A);
        for (int i = 0; i < w; i += 8) {
            drawRect(x + i, y, x + Math.min(i + 4, w), y + 6, 0xFF3A0505);
        }
    }

    // -------------------------------------------------------------------- helpers

    private int visibleRows() {
        return Math.max(1, (H - HEADER_H - FOOTER_H - STATUS_H - 8) / ROW);
    }

    private static boolean blink() {
        return (System.currentTimeMillis() / 400L) % 2L == 0L;
    }

    private static int toneColor(int tone) {
        switch (tone) {
            case HackService.TONE_OK:
                return GREEN_BRIGHT;
            case HackService.TONE_ERR:
                return RED;
            case HackService.TONE_WARN:
                return AMBER;
            case HackService.TONE_DIM:
                return GREEN_DIM;
            case HackService.TONE_HEAD:
                return CYAN;
            default:
                return GREEN;
        }
    }

    /** Truncates to the pixel width the font actually needs, not to a char count. */
    private String ellipsis(String text, int maxWidth) {
        String value = text == null ? "" : text;
        if (this.fontRenderer.getStringWidth(value) <= maxWidth) {
            return value;
        }
        String cut = value;
        while (cut.length() > 0 && this.fontRenderer.getStringWidth(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    private static String clip(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}

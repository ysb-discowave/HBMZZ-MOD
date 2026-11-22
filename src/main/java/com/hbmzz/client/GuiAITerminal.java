/*
 * HBMZZ - AI control node.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 */
package com.hbmzz.client;

import com.hbmzz.ai.AiTerminalService;
import com.hbmzz.hack.HackService;
import com.hbmzz.network.AiCommandMessage;
import com.hbmzz.network.AiReplyMessage;
import com.hbmzz.network.NetworkHandler;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The AI terminal window: a command line, not a form.
 *
 * <p>The window owns no truth. Every line it paints came back from the server
 * in an {@link AiReplyMessage}; the only local actions are echoing what was
 * typed and scrolling. That keeps a dedicated server and a single-player world
 * on exactly the same code path.</p>
 */
public class GuiAITerminal extends GuiScreen {

    private static final int W = 360;
    private static final int H = 220;
    private static final int HEADER_H = 14;
    private static final int FOOTER_H = 20;
    private static final int ROW = 10;
    private static final int MAX_LOG = 240;

    private static final int BG = 0xF0060A07;
    private static final int FRAME = 0xFF35B0E0;
    private static final int FRAME_DIM = 0xFF0A2D3D;
    private static final int BAR = 0xFF0A141A;
    private static final int TEXT = 0xFFBFE9F7;
    private static final int HEAD = 0xFF66D6FF;
    private static final int DIM = 0xFF3F7C90;
    private static final int OK = 0xFF7CFF9B;
    private static final int ERR = 0xFFFF5A5A;
    private static final int WARN = 0xFFFFC24A;

    private final Deque<HackService.Line> log = new ArrayDeque<HackService.Line>();
    private final List<String> history = new ArrayList<String>();

    private String input = "";
    private int historyIndex = -1;
    private int scroll;
    private boolean opened;

    @Override
    public void initGui() {
        if (!this.opened) {
            this.opened = true;
            say(HackService.TONE_HEAD, "HBMZZ // AI 终端 v1.0");
            say(HackService.TONE_DIM, "输入 help 查看本地命令；其它输入交给 AI 解释。");
            NetworkHandler.sendToServer(new AiCommandMessage(""));
        }
    }

    /** Called from ClientPacketBridge on the client tick thread. */
    public void accept(AiReplyMessage message) {
        for (HackService.Line line : message.lines()) {
            say(line.tone, line.text);
        }
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

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
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
        if (typedChar == 22) {
            String clip = GuiScreen.getClipboardString();
            if (clip != null) {
                this.input = clip(this.input + clip.replace('\n', ' ').replace('\r', ' '),
                        AiTerminalService.MAX_INPUT);
            }
            return;
        }
        if (typedChar >= ' ' && typedChar != 127 && this.input.length() < AiTerminalService.MAX_INPUT) {
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
        if (line.equalsIgnoreCase("clear") || line.equalsIgnoreCase("cls")) {
            this.log.clear();
            this.scroll = 0;
            return;
        }
        say(HackService.TONE_HEAD, "> " + line);
        if (!this.history.isEmpty() && this.history.get(this.history.size() - 1).equals(line)) {
            return;
        }
        this.history.add(line);
        while (this.history.size() > 40) {
            this.history.remove(0);
        }
        NetworkHandler.sendToServer(new AiCommandMessage(line));
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
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int x0 = (this.width - W) / 2;
        int y0 = (this.height - H) / 2;

        drawRect(x0 - 1, y0 - 1, x0 + W + 1, y0 + H + 1, FRAME_DIM);
        drawRect(x0, y0, x0 + W, y0 + H, BG);
        drawRect(x0, y0, x0 + W, y0 + 1, FRAME);
        drawRect(x0, y0 + H - 1, x0 + W, y0 + H, FRAME);
        drawRect(x0, y0, x0 + 1, y0 + H, FRAME);
        drawRect(x0 + W - 1, y0, x0 + W, y0 + H, FRAME);

        drawRect(x0 + 1, y0 + 1, x0 + W - 1, y0 + HEADER_H, BAR);
        this.fontRenderer.drawStringWithShadow("AI TERMINAL", x0 + 6, y0 + 4, HEAD);
        this.fontRenderer.drawString("// DFC control", x0 + 6 + this.fontRenderer.getStringWidth("AI TERMINAL") + 4,
                y0 + 4, DIM);

        int top = y0 + HEADER_H + 3;
        int bottom = y0 + H - FOOTER_H - 2;
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
        if (all.isEmpty()) {
            this.fontRenderer.drawString("_", x0 + 7, top, DIM);
        }

        int fy = y0 + H - FOOTER_H;
        drawRect(x0 + 1, fy, x0 + W - 1, fy + 1, FRAME_DIM);
        this.fontRenderer.drawString(">", x0 + 7, fy + 5, HEAD);
        String shown = ellipsis(this.input, W - 34);
        this.fontRenderer.drawString(shown, x0 + 17, fy + 5, TEXT);
        if (blink()) {
            int caretX = x0 + 17 + this.fontRenderer.getStringWidth(shown);
            drawRect(caretX, fy + 4, caretX + 4, fy + 12, HEAD);
        }
    }

    private int visibleRows() {
        return Math.max(1, (H - HEADER_H - FOOTER_H - 8) / ROW);
    }

    private static boolean blink() {
        return (System.currentTimeMillis() / 400L) % 2L == 0L;
    }

    private static int toneColor(int tone) {
        switch (tone) {
            case HackService.TONE_OK:
                return OK;
            case HackService.TONE_ERR:
                return ERR;
            case HackService.TONE_WARN:
                return WARN;
            case HackService.TONE_DIM:
                return DIM;
            case HackService.TONE_HEAD:
                return HEAD;
            default:
                return TEXT;
        }
    }

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
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}

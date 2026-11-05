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

import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.VideoConfigMessage;
import com.hbmzz.tile.TileEntityVideoBroadcaster;
import com.hbmzz.video.VideoLibrary;
import com.hbmzz.video.VideoTargets;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频广播器配置窗口 - two fields, one shelf of clips, three buttons.
 *
 * <p>The shape is a command block's, on purpose. The operator is being asked the
 * same two questions a command block asks - <em>which thing</em> and <em>who</em> -
 * and the second answer is a selector, so the field looks and behaves like one:
 * {@code @a[r=32]}, {@code @p}, {@code @a[team=red]}. Everything vanilla
 * understands works, because it is vanilla doing the parsing on both ends.</p>
 *
 * <p><b>Where the settings live.</b> Not here. The window edits a working copy
 * of two strings and posts them; the tile entity stores them. Saving keeps the
 * window open and shows the server's own sentence, because the interesting
 * failures (out of range, not an operator, selector matched nobody) can only be
 * discovered on the server.</p>
 *
 * <p><b>The preview is a preview.</b> The line under the fields resolves the
 * selector against the client's own view of the world so a typo is caught before
 * it is saved - but a multiplayer client has no way to evaluate a bare
 * {@code @a}, and says so rather than guessing.</p>
 */
public class GuiVideoBroadcaster extends GuiScreen {

    private static final int W = 320;
    private static final int H = 200;
    private static final int LIST_ROWS = 6;
    private static final int ROW_H = 12;

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int RED = 0xFFE5484D;
    private static final int MINT = 0xFF43D9A3;

    private final BlockPos pos;

    private GuiTextField videoField;
    private GuiTextField selectorField;
    private boolean built;

    private int left;
    private int top;

    private List<String> clips = new ArrayList<>();
    private int scroll;
    private int hoveredRow = -1;

    private String preview = "";
    private int previewColor = DIM;
    private String previewKey = "";

    public GuiVideoBroadcaster(BlockPos pos) {
        this.pos = pos;
    }

    private TileEntityVideoBroadcaster tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityVideoBroadcaster ? (TileEntityVideoBroadcaster) te : null;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void initGui() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        if (!this.built) {
            this.built = true;
            TileEntityVideoBroadcaster te = tile();
            this.videoField = new GuiTextField(0, this.fontRenderer,
                    this.left + 74, this.top + 26, 234, 16);
            this.videoField.setMaxStringLength(64);
            this.selectorField = new GuiTextField(1, this.fontRenderer,
                    this.left + 74, this.top + 128, 234, 16);
            this.selectorField.setMaxStringLength(128);
            if (te != null) {
                this.videoField.setText(te.getVideo());
                this.selectorField.setText(te.getSelector());
            } else {
                this.selectorField.setText(TileEntityVideoBroadcaster.DEFAULT_SELECTOR);
            }
            this.videoField.setFocused(true);
        } else {
            // A resize keeps whatever is half-typed and only moves the boxes.
            this.videoField.x = this.left + 74;
            this.videoField.y = this.top + 26;
            this.selectorField.x = this.left + 74;
            this.selectorField.y = this.top + 128;
        }

        this.clips = VideoLibrary.names();

        this.buttonList.add(new GuiButton(0, this.left + 22, this.top + 176, 96, 18, "保存并应用"));
        this.buttonList.add(new GuiButton(1, this.left + 124, this.top + 176, 96, 18, "测试广播"));
        this.buttonList.add(new GuiButton(2, this.left + 226, this.top + 176, 72, 18, "关闭"));
    }

    @Override
    public void updateScreen() {
        this.videoField.updateCursorCounter();
        this.selectorField.updateCursorCounter();
    }

    // ------------------------------------------------------------------ draw

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, this.width, this.height, 0xCC000000);

        int l = this.left;
        int t = this.top;
        drawRect(l, t, l + W, t + H, 0xF50A0C0F);
        drawRect(l, t, l + W, t + 1, 0xFF232A33);
        drawRect(l, t + H - 1, l + W, t + H, 0xFF232A33);
        drawRect(l, t, l + 1, t + H, 0xFF232A33);
        drawRect(l + W - 1, t, l + W, t + H, 0xFF232A33);
        drawRect(l + 1, t + 22, l + W - 1, t + 23, 0xFF1A2028);

        this.fontRenderer.drawStringWithShadow("视频广播器", l + 12, t + 7, AMBER);
        String header = "红石上升沿 → 全屏占用";
        this.fontRenderer.drawStringWithShadow(header,
                l + W - 12 - this.fontRenderer.getStringWidth(header), t + 8, DIM);

        this.fontRenderer.drawStringWithShadow("视频文件", l + 12, t + 30, DIM);
        this.fontRenderer.drawStringWithShadow("广播目标", l + 12, t + 132, DIM);

        this.videoField.drawTextBox();
        this.selectorField.drawTextBox();
        if (this.videoField.getText().isEmpty() && !this.videoField.isFocused()) {
            this.fontRenderer.drawStringWithShadow("<名字 或 从下面选>",
                    l + 78, t + 30, 0xFF4A5563);
        }
        if (this.selectorField.getText().isEmpty() && !this.selectorField.isFocused()) {
            this.fontRenderer.drawStringWithShadow("<@a[r=32] / @p / 玩家名>",
                    l + 78, t + 132, 0xFF4A5563);
        }

        drawList(mouseX, mouseY);

        updatePreview();
        this.fontRenderer.drawStringWithShadow(fit(this.preview, W - 24), l + 12, t + 150, this.previewColor);

        TileEntityVideoBroadcaster te = tile();
        String status = te == null ? "" : te.getStatus();
        if (!status.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(fit(status, W - 24), l + 12, t + 162, DIM);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawList(int mouseX, int mouseY) {
        int x = this.left + 12;
        int y = this.top + 46;
        int w = 296;
        int h = LIST_ROWS * ROW_H + 4;

        drawRect(x, y, x + w, y + h, 0xFF10141A);
        drawRect(x, y, x + w, y + 1, 0xFF232A33);
        drawRect(x, y + h - 1, x + w, y + h, 0xFF232A33);
        drawRect(x, y, x + 1, y + h, 0xFF232A33);
        drawRect(x + w - 1, y, x + w, y + h, 0xFF232A33);

        this.hoveredRow = -1;
        if (this.clips.isEmpty()) {
            this.fontRenderer.drawStringWithShadow("视频目录是空的 — 见 config/hbmzz/videos/README.txt",
                    x + 6, y + 6, 0xFF5A6674);
            return;
        }
        clampScroll();
        for (int row = 0; row < LIST_ROWS; row++) {
            int index = this.scroll + row;
            if (index >= this.clips.size()) {
                break;
            }
            int rowY = y + 2 + row * ROW_H;
            boolean hover = mouseX >= x + 2 && mouseX < x + w - 2 && mouseY >= rowY && mouseY < rowY + ROW_H;
            boolean selected = this.clips.get(index).equals(this.videoField.getText().trim());
            if (hover) {
                this.hoveredRow = row;
            }
            if (hover || selected) {
                drawRect(x + 2, rowY, x + w - 2, rowY + ROW_H,
                        selected ? 0x33F5A524 : 0x1AFFFFFF);
            }
            this.fontRenderer.drawStringWithShadow(this.clips.get(index), x + 6, rowY + 2,
                    selected ? AMBER : 0xFFC8D2DC);
            if (!VideoLibrary.exists(this.clips.get(index))) {
                String warn = "坏";
                this.fontRenderer.drawStringWithShadow(warn,
                        x + w - 8 - this.fontRenderer.getStringWidth(warn), rowY + 2, RED);
            }
        }
        if (this.clips.size() > LIST_ROWS) {
            String more = (this.scroll + LIST_ROWS) + "/" + this.clips.size() + " · 滚轮翻页";
            this.fontRenderer.drawStringWithShadow(more,
                    x + w - 6 - this.fontRenderer.getStringWidth(more), y + h + 3, 0xFF4A5563);
        }
    }

    private void clampScroll() {
        int max = Math.max(0, this.clips.size() - LIST_ROWS);
        this.scroll = Math.max(0, Math.min(max, this.scroll));
    }

    /** Recomputes the line under the fields, only when an input actually changed. */
    private void updatePreview() {
        String video = this.videoField.getText().trim();
        String selector = this.selectorField.getText().trim();
        String key = video + "\u0000" + selector;
        if (key.equals(this.previewKey)) {
            return;
        }
        this.previewKey = key;

        StringBuilder text = new StringBuilder();
        int color = DIM;
        if (video.isEmpty()) {
            text.append("未选择视频");
        } else if (!VideoLibrary.isValidName(video)) {
            text.append("视频名只能用字母数字和 _ . -");
            color = RED;
        } else if (VideoLibrary.exists(video)) {
            text.append("视频 · 本机已有");
            color = MINT;
        } else {
            text.append("视频 · 本机没有这个文件");
            color = RED;
        }

        text.append("   ·   目标 ");
        if (selector.isEmpty()) {
            text.append("未设置");
            color = RED;
        } else if (this.mc != null && this.mc.world != null) {
            VideoTargets.Result result = VideoTargets.resolve(this.mc.world, this.pos, selector);
            if (result.ok()) {
                text.append("匹配 ").append(result.players.size()).append(" 名玩家");
                if (color != RED) {
                    color = MINT;
                }
            } else {
                text.append(result.error);
                color = RED;
            }
        } else {
            text.append("（无世界）");
        }
        this.preview = text.toString();
        this.previewColor = color;
    }

    /** Truncates by measured pixel width, not by character count: a Chinese
     *  selector error is four times as wide per character as an ASCII one. */
    private String fit(String text, int maxWidth) {
        if (this.fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsis = this.fontRenderer.getStringWidth("…");
        return this.fontRenderer.trimStringToWidth(text, Math.max(0, maxWidth - ellipsis)) + "…";
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        int x = this.left + 12;
        int y = this.top + 46;
        if (mouseButton == 0 && mouseX >= x && mouseX < x + 296 && mouseY >= y && mouseY < y + LIST_ROWS * ROW_H + 4) {
            int row = (mouseY - y - 2) / ROW_H;
            int index = this.scroll + row;
            if (row >= 0 && row < LIST_ROWS && index < this.clips.size()) {
                this.videoField.setText(this.clips.get(index));
                this.selectorField.setFocused(false);
                this.videoField.setFocused(false);
                return;
            }
        }
        this.videoField.mouseClicked(mouseX, mouseY, mouseButton);
        this.selectorField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        int x = this.left + 12;
        int y = this.top + 46;
        if (mouseX >= x && mouseX < x + 296 && mouseY >= y && mouseY < y + LIST_ROWS * ROW_H + 4) {
            this.scroll += wheel > 0 ? -1 : 1;
            clampScroll();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            send(false);
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            boolean videoFocused = this.videoField.isFocused();
            this.videoField.setFocused(!videoFocused);
            this.selectorField.setFocused(videoFocused);
            return;
        }
        if (this.videoField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        if (this.selectorField.textboxKeyTyped(typedChar, keyCode)) {
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 0:
                send(false);
                break;
            case 1:
                send(true);
                break;
            default:
                this.mc.displayGuiScreen(null);
                break;
        }
    }

    private void send(boolean test) {
        NetworkHandler.sendToServer(new VideoConfigMessage(this.pos,
                this.videoField.getText().trim(), this.selectorField.getText().trim(), test));
    }
}

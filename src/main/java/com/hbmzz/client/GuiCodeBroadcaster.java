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

import com.hbmzz.network.CustomCodeConfigMessage;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.tile.TileEntityCodeBroadcaster;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.Locale.ROOT;

/**
 * 代码广播器自定义状态窗口.
 *
 * <p>One broadcaster can shout something that is not one of the eight standard
 * codes: a level (0..7) plus its own word, colour and text. The window edits a
 * working copy and posts it; the tile entity stores it. Saving keeps the window
 * open and shows the server's own one-line answer, the same way the video
 * broadcaster's window does - the window itself has no authority over what a
 * block is allowed to say.</p>
 *
 * <p>The colour swatch is a fixed palette rather than a free RGB wheel on
 * purpose: the eight standard colours plus a handful of common ones are what a
 * facility actually needs, and a swatch that shows its own hex keeps the answer
 * honest when a player copies a code between boxes.</p>
 */
public class GuiCodeBroadcaster extends GuiScreen {

    private static final int W = 320;
    private static final int H = 268;

    private static final int AMBER = 0xFFF5A524;
    private static final int DIM = 0xFF8894A0;
    private static final int MINT = 0xFF43D9A3;
    private static final int RED = 0xFFE5484D;

    /** The swatch cycles through these on click; index 0 is "no custom colour". */
    private static final int[] PALETTE = {
            0xFF6BF29A, 0xFFE066, 0xFFA53C, 0xFF5A5A,
            0xFF4F8C, 0xFF3A2E, 0xFF2D6B, 0xC05BFF,
            0xFF5AD1E0, 0xFF9BE05A, 0xFFD15AB0, 0xFFFFFF
    };

    private final BlockPos pos;

    private GuiTextField labelField;
    private GuiTextField zhTitleField;
    private GuiTextField enTitleField;
    private GuiTextField zhDetailField;
    private GuiTextField enDetailField;
    private boolean built;

    private boolean active;
    private int level;
    private int colorIndex;
    private int left;
    private int top;

    /** Buttons: 0=启用 toggle, 1=等级 -, 2=等级 +, 3=颜色, 4=保存, 5=关闭. */
    private GuiButton toggleButton;
    private GuiButton levelDownButton;
    private GuiButton levelUpButton;
    private GuiButton colorButton;
    private GuiButton saveButton;
    private GuiButton closeButton;

    private String notice = "";

    public GuiCodeBroadcaster(BlockPos pos) {
        this.pos = pos;
    }

    private TileEntityCodeBroadcaster tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityCodeBroadcaster ? (TileEntityCodeBroadcaster) te : null;
    }

    private int color() {
        int index = Math.max(0, Math.min(PALETTE.length - 1, this.colorIndex));
        return PALETTE[index];
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void initGui() {
        this.left = (this.width - W) / 2;
        this.top = (this.height - H) / 2;

        if (!this.built) {
            this.built = true;
            TileEntityCodeBroadcaster te = tile();
            if (te != null) {
                this.active = te.isCustomActive();
                this.level = te.getCustomLevel();
                this.colorIndex = matchPalette(te.getCustomRgb());
            }
            this.labelField = new GuiTextField(0, this.fontRenderer,
                    this.left + 74, this.top + 44, 234, 16);
            this.labelField.setMaxStringLength(24);
            this.zhTitleField = new GuiTextField(1, this.fontRenderer,
                    this.left + 74, this.top + 78, 234, 16);
            this.zhTitleField.setMaxStringLength(32);
            this.enTitleField = new GuiTextField(2, this.fontRenderer,
                    this.left + 74, this.top + 112, 234, 16);
            this.enTitleField.setMaxStringLength(48);
            this.zhDetailField = new GuiTextField(3, this.fontRenderer,
                    this.left + 74, this.top + 146, 234, 16);
            this.zhDetailField.setMaxStringLength(80);
            this.enDetailField = new GuiTextField(4, this.fontRenderer,
                    this.left + 74, this.top + 180, 234, 16);
            this.enDetailField.setMaxStringLength(96);
            if (te != null) {
                this.labelField.setText(te.getCustomLabel());
                this.zhTitleField.setText(te.getCustomZhTitle());
                this.enTitleField.setText(te.getCustomEnTitle());
                this.zhDetailField.setText(te.getCustomZhDetail());
                this.enDetailField.setText(te.getCustomEnDetail());
            }
        } else {
            this.labelField.x = this.left + 74;
            this.labelField.y = this.top + 44;
            this.zhTitleField.x = this.left + 74;
            this.zhTitleField.y = this.top + 78;
            this.enTitleField.x = this.left + 74;
            this.enTitleField.y = this.top + 112;
            this.zhDetailField.x = this.left + 74;
            this.zhDetailField.y = this.top + 146;
            this.enDetailField.x = this.left + 74;
            this.enDetailField.y = this.top + 180;
        }

        this.buttonList.add(this.toggleButton = new GuiButton(0, this.left + 12, this.top + 20, 84, 18,
                this.active ? "停用自定义" : "启用自定义"));
        this.buttonList.add(this.levelDownButton = new GuiButton(1, this.left + 104, this.top + 20,
                22, 18, "-"));
        this.buttonList.add(this.levelUpButton = new GuiButton(2, this.left + 148, this.top + 20,
                22, 18, "+"));
        this.buttonList.add(this.colorButton = new GuiButton(3, this.left + 180, this.top + 20,
                128, 18, "颜色 " + Integer.toHexString(color()).toUpperCase(ROOT)));
        this.buttonList.add(this.saveButton = new GuiButton(4, this.left + 12, this.top + H - 24,
                96, 18, "保存"));
        this.buttonList.add(this.closeButton = new GuiButton(5, this.left + W - 108, this.top + H - 24,
                96, 18, "关闭"));
    }

    @Override
    public void updateScreen() {
        this.labelField.updateCursorCounter();
        this.zhTitleField.updateCursorCounter();
        this.enTitleField.updateCursorCounter();
        this.zhDetailField.updateCursorCounter();
        this.enDetailField.updateCursorCounter();
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
        drawRect(l + 1, t + 40, l + W - 1, t + 41, 0xFF1A2028);

        this.fontRenderer.drawStringWithShadow("代码广播器 · 自定义状态", l + 12, t + 6, AMBER);
        String header = "等级决定警报强度 · 颜色与文字决定样子";
        this.fontRenderer.drawStringWithShadow(header,
                l + W - 12 - this.fontRenderer.getStringWidth(header), t + 7, DIM);

        this.fontRenderer.drawStringWithShadow("等级", l + 12, t + 24, DIM);
        this.fontRenderer.drawStringWithShadow(String.valueOf(this.level),
                l + 134, t + 24, 0xFFC8D2DC);

        this.fontRenderer.drawStringWithShadow("代码", l + 12, t + 48, DIM);
        this.fontRenderer.drawStringWithShadow("中文标题", l + 12, t + 82, DIM);
        this.fontRenderer.drawStringWithShadow("英文标题", l + 12, t + 116, DIM);
        this.fontRenderer.drawStringWithShadow("中文详情", l + 12, t + 150, DIM);
        this.fontRenderer.drawStringWithShadow("英文详情", l + 12, t + 184, DIM);

        this.labelField.drawTextBox();
        this.zhTitleField.drawTextBox();
        this.enTitleField.drawTextBox();
        this.zhDetailField.drawTextBox();
        this.enDetailField.drawTextBox();

        // The swatch: a filled square showing the chosen colour next to the hex.
        int swatchX = l + 186;
        int swatchY = t + 24;
        drawRect(swatchX - 2, swatchY - 2, swatchX + 12, swatchY + 12, 0xFF232A33);
        drawRect(swatchX, swatchY, swatchX + 10, swatchY + 10, 0xFF000000 | color());

        if (!this.notice.isEmpty()) {
            this.fontRenderer.drawStringWithShadow(fit(this.notice, W - 24), l + 12, t + 208, this.noticeColor());
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /** Truncates by measured pixel width: a Chinese detail is wide per character. */
    private String fit(String text, int maxWidth) {
        if (this.fontRenderer.getStringWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsis = this.fontRenderer.getStringWidth("…");
        return this.fontRenderer.trimStringToWidth(text, Math.max(0, maxWidth - ellipsis)) + "…";
    }

    private int noticeColor() {
        return this.notice.startsWith("已保存") ? MINT : RED;
    }

    // ----------------------------------------------------------------- input

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN) {
            send();
            return;
        }
        GuiTextField[] fields = {this.labelField, this.zhTitleField, this.enTitleField,
                this.zhDetailField, this.enDetailField};
        boolean consumed = false;
        for (GuiTextField field : fields) {
            if (field.isFocused() || field.textboxKeyTyped(typedChar, keyCode)) {
                consumed = true;
            }
        }
        if (consumed) {
            return;
        }
        // TAB walks the focus chain so a keyboard-only operator can fill all five.
        if (keyCode == Keyboard.KEY_TAB) {
            focusNext();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void focusNext() {
        GuiTextField[] fields = {this.labelField, this.zhTitleField, this.enTitleField,
                this.zhDetailField, this.enDetailField};
        int next = -1;
        int current = -1;
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].isFocused()) {
                current = i;
            }
        }
        next = (current + 1) % fields.length;
        for (int i = 0; i < fields.length; i++) {
            fields[i].setFocused(i == next);
        }
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case 0:
                this.active = !this.active;
                this.toggleButton.displayString = this.active ? "停用自定义" : "启用自定义";
                break;
            case 1:
                this.level = Math.max(0, this.level - 1);
                break;
            case 2:
                this.level = Math.min(7, this.level + 1);
                break;
            case 3:
                this.colorIndex = (this.colorIndex + 1) % PALETTE.length;
                this.colorButton.displayString = "颜色 " + Integer.toHexString(color()).toUpperCase(ROOT);
                break;
            case 4:
                send();
                break;
            case 5:
            default:
                this.mc.displayGuiScreen(null);
                break;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.labelField.mouseClicked(mouseX, mouseY, mouseButton);
        this.zhTitleField.mouseClicked(mouseX, mouseY, mouseButton);
        this.enTitleField.mouseClicked(mouseX, mouseY, mouseButton);
        this.zhDetailField.mouseClicked(mouseX, mouseY, mouseButton);
        this.enDetailField.mouseClicked(mouseX, mouseY, mouseButton);
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private void send() {
        NetworkHandler.sendToServer(new CustomCodeConfigMessage(this.pos, this.active, this.level,
                this.labelField.getText().trim(), color(),
                this.zhTitleField.getText().trim(), this.enTitleField.getText().trim(),
                this.zhDetailField.getText().trim(), this.enDetailField.getText().trim()));
        this.notice = "已保存";
    }

    private static int matchPalette(int rgb) {
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < PALETTE.length; i++) {
            int distance = distance(PALETTE[i] & 0xFFFFFF, rgb & 0xFFFFFF);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private static int distance(int a, int b) {
        int r = (a >> 16 & 0xFF) - (b >> 16 & 0xFF);
        int g = (a >> 8 & 0xFF) - (b >> 8 & 0xFF);
        int bl = (a & 0xFF) - (b & 0xFF);
        return r * r + g * g + bl * bl;
    }
}

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

import com.hbmzz.client.screen.DfcScreen;
import com.hbmzz.client.screen.StudioCanvas;
import com.hbmzz.dfc.DFCSnapshot;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.network.PanelEditMessage;
import com.hbmzz.panel.Action;
import com.hbmzz.panel.ActionType;
import com.hbmzz.panel.Channel;
import com.hbmzz.panel.Condition;
import com.hbmzz.panel.LogicProgram;
import com.hbmzz.panel.PanelLayout;
import com.hbmzz.panel.Rule;
import com.hbmzz.panel.Widget;
import com.hbmzz.panel.WidgetType;
import com.hbmzz.tile.TileEntityDFCMonitor;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.IOException;

/**
 * 面板工坊: the window where a custom panel is built and programmed.
 *
 * <p>It edits a <em>copy</em>. Nothing reaches the monitor until 保存并应用 is
 * pressed, which is what makes the footer button honest - and what lets an
 * operator experiment with a layout on a screen that is currently driving an
 * alarm without disturbing it.</p>
 *
 * <h3>The three tabs</h3>
 * <ol>
 *   <li><b>控件设计</b> - click a control in the palette to add it, drag it on
 *       the canvas, edit its properties on the right.</li>
 *   <li><b>逻辑编排</b> - pick a rule, then edit its 当 / 若 / 则 block groups.
 *       Selecting a condition or an action turns the right column into that
 *       block's parameter sheet.</li>
 *   <li><b>预设库</b> - named designs on disk, plus copy/paste between screens.</li>
 * </ol>
 *
 * <p>Text is entered through one {@link GuiTextField} that is moved onto
 * whichever box the canvas reports as editable this frame. One field instead of
 * six eliminates a whole class of "which field has focus" bugs, and the canvas
 * already knows where the box is because it drew it.</p>
 */
public class GuiPanelStudio extends GuiScreen {

    private final BlockPos pos;
    private final StudioCanvas canvas = new StudioCanvas();

    /** Working copies. Committed to the server only on 保存. */
    private PanelLayout layout;
    private LogicProgram program;

    private int mode = StudioCanvas.MODE_DESIGN;
    private int selWidget = -1;
    private int selRule;
    private int selCond = -1;
    private int selAction = -1;
    private int selPreset = -1;

    private int winW;
    private int winH;
    private int left;
    private int top;

    private GuiTextField field;
    private int fieldKind = StudioCanvas.FIELD_NONE;
    private String presetName = "";

    private boolean dragging;
    private int dragIndex = -1;
    private int dragCellX;
    private int dragCellY;

    private String status = "";
    private boolean modified;

    public GuiPanelStudio(BlockPos pos) {
        this.pos = pos;
        this.status = "\u5de5\u4f5c\u533a\u5df2\u8f7d\u5165";
    }

    private TileEntityDFCMonitor tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityDFCMonitor ? (TileEntityDFCMonitor) te : null;
    }

    @Override
    public void initGui() {
        TileEntityDFCMonitor te = tile();
        this.layout = te != null ? te.getPanelLayout().copy() : PanelLayout.defaults();
        this.program = te != null ? te.getLogicProgram().copy() : LogicProgram.defaults();
        this.presetName = this.layout.name;
        this.selWidget = this.layout.size() > 0 ? 0 : -1;
        this.selRule = this.program.rules.size() > 0 ? 0 : -1;

        int maxW = Math.max(480, this.width - 16);
        int maxH = Math.max(320, this.height - 16);
        this.winW = Math.min(maxW, 860);
        this.winH = Math.min(maxH, 520);
        this.left = (this.width - this.winW) / 2;
        this.top = (this.height - this.winH) / 2;

        this.field = new GuiTextField(0, this.fontRenderer, 0, 0, 100, 12);
        this.field.setMaxStringLength(24);
        this.field.setEnableBackgroundDrawing(false);
        this.field.setVisible(false);
        this.canvas.resize(this.winW, this.winH);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawRect(0, 0, this.width, this.height, 0xC8080D12);

        TileEntityDFCMonitor te = tile();
        DFCSnapshot live = te != null ? te.getClientData() : null;
        int level = -1;
        String alarm = "";
        if (te != null && te.isAlarmActive()) {
            level = Math.max(0, te.getAlarmLevel());
            alarm = te.getAlarmText();
        }

        this.canvas.setModel(this.layout, this.program);
        this.canvas.setView(this.mode, this.selWidget, this.selRule, this.selCond, this.selAction);
        this.canvas.setLive(live, level, alarm);
        this.canvas.setStatus(statusLine());
        this.canvas.setPresets(PanelPresets.get().names(), this.selPreset);
        this.canvas.setDragging(this.dragging);
        this.canvas.render(mouseX - this.left, mouseY - this.top);
        this.canvas.upload();

        drawTexture(this.left, this.top, this.winW, this.winH, this.canvas.textureId());
        syncField();

        if (this.dragging) {
            this.drawCenteredString(this.fontRenderer, "\u62d6\u52a8\u4e2d",
                    this.left + this.winW / 2, this.top + this.winH + 4, 0xFF48D6FF);
        }
    }

    private String statusLine() {
        String text = this.status;
        if (this.modified) {
            text = text + "   \u00b7 \u672a\u4fdd\u5b58";
        }
        return text;
    }

    // ------------------------------------------------------------ text fields

    private String fieldText(int kind) {
        switch (kind) {
            case StudioCanvas.FIELD_WIDGET_TITLE:
                return currentWidget() == null ? "" : safe(currentWidget().title);
            case StudioCanvas.FIELD_LABEL_TEXT:
                return currentWidget() == null ? "" : safe(currentWidget().text);
            case StudioCanvas.FIELD_RULE_NAME:
                return currentRule() == null ? "" : safe(currentRule().name);
            case StudioCanvas.FIELD_ACTION_TEXT:
                return currentAction() == null ? "" : safe(currentAction().text);
            case StudioCanvas.FIELD_PRESET_NAME:
                return this.presetName;
            default:
                return "";
        }
    }

    private void applyFieldText(int kind, String text) {
        switch (kind) {
            case StudioCanvas.FIELD_WIDGET_TITLE:
                if (currentWidget() != null) {
                    currentWidget().title = text;
                }
                break;
            case StudioCanvas.FIELD_LABEL_TEXT:
                if (currentWidget() != null) {
                    currentWidget().text = text;
                }
                break;
            case StudioCanvas.FIELD_RULE_NAME:
                if (currentRule() != null) {
                    currentRule().name = text;
                }
                break;
            case StudioCanvas.FIELD_ACTION_TEXT:
                if (currentAction() != null) {
                    currentAction().text = text;
                }
                break;
            case StudioCanvas.FIELD_PRESET_NAME:
                this.presetName = text;
                break;
            default:
                break;
        }
    }

    /**
     * Moves the one text field onto the box the canvas drew, and mirrors its
     * contents back into the model on every frame - so a half-typed title is
     * already visible on the canvas behind the cursor.
     */
    private void syncField() {
        int kind = this.canvas.fieldKind();
        int[] rect = this.canvas.fieldRect();
        if (kind == StudioCanvas.FIELD_NONE || rect[2] <= 4) {
            this.field.setVisible(false);
            this.fieldKind = StudioCanvas.FIELD_NONE;
            return;
        }
        boolean changed = kind != this.fieldKind;
        this.fieldKind = kind;
        this.field.x = this.left + rect[0] + 3;
        this.field.y = this.top + rect[1] + 2;
        this.field.width = rect[2] - 6;
        this.field.height = 12;
        this.field.setVisible(true);
        if (changed) {
            this.field.setText(fieldText(kind));
            this.field.setCursorPositionEnd();
            this.field.setFocused(true);
        }
        this.field.drawTextBox();
        applyFieldText(kind, this.field.getText());
    }

    // --------------------------------------------------------------- geometry

    private Widget currentWidget() {
        if (this.selWidget < 0 || this.selWidget >= this.layout.size()) {
            return null;
        }
        return this.layout.widgets.get(this.selWidget);
    }

    private Rule currentRule() {
        if (this.selRule < 0 || this.selRule >= this.program.rules.size()) {
            return null;
        }
        return this.program.rules.get(this.selRule);
    }

    private Condition currentCondition() {
        Rule rule = currentRule();
        if (rule == null || this.selCond < 0 || this.selCond >= rule.conditions.size()) {
            return null;
        }
        return rule.conditions.get(this.selCond);
    }

    private Action currentAction() {
        Rule rule = currentRule();
        if (rule == null || this.selAction < 0 || this.selAction >= rule.actions.size()) {
            return null;
        }
        return rule.actions.get(this.selAction);
    }

    // ------------------------------------------------------------ interaction

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (this.field.getVisible()
                && mouseX >= this.field.x && mouseX < this.field.x + this.field.width
                && mouseY >= this.field.y && mouseY < this.field.y + 12) {
            this.field.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }
        this.field.setFocused(false);

        String id = this.canvas.hitAt(mouseX - this.left, mouseY - this.top);
        if (id.isEmpty()) {
            return;
        }
        if (mouseButton == 1) {
            // Right click is delete, everywhere a control or a rule can be
            // pointed at. One gesture, no menu to open.
            if (id.startsWith("w:")) {
                int index = number(id.substring(2));
                this.layout.remove(index);
                this.selWidget = this.layout.size() - 1;
                this.modified = true;
                this.status = "\u5df2\u5220\u9664\u63a7\u4ef6";
            }
            return;
        }
        handle(id);
    }

    private void handle(String id) {
        if (id.equals("close")) {
            this.mc.displayGuiScreen(new GuiDFCMonitor(this.pos));
            return;
        }
        if (id.startsWith("tab:")) {
            this.mode = number(id.substring(4));
            this.selCond = -1;
            this.selAction = -1;
            return;
        }
        if (handleDesign(id)) {
            return;
        }
        if (handleLogic(id)) {
            return;
        }
        if (handlePreset(id)) {
            return;
        }
        if (id.equals("reset")) {
            TileEntityDFCMonitor te = tile();
            if (te != null) {
                this.layout = te.getPanelLayout().copy();
                this.program = te.getLogicProgram().copy();
                this.selWidget = this.layout.size() > 0 ? 0 : -1;
                this.selRule = this.program.rules.size() > 0 ? 0 : -1;
                this.selCond = -1;
                this.selAction = -1;
                this.modified = false;
                this.status = "\u5df2\u8fd8\u539f\u4e3a\u5c4f\u5e55\u4e0a\u7684\u9762\u677f";
            }
            return;
        }
        if (id.equals("copy")) {
            PanelPresets.setClipboard(this.layout, this.program);
            this.status = "\u5df2\u590d\u5236\u5230\u526a\u8d34\u677f";
            return;
        }
        if (id.equals("paste")) {
            if (PanelPresets.hasClipboard()) {
                this.layout = PanelPresets.clipboardLayout();
                this.program = PanelPresets.clipboardLogic();
                this.selWidget = this.layout.size() > 0 ? 0 : -1;
                this.selRule = this.program.rules.size() > 0 ? 0 : -1;
                this.selCond = -1;
                this.selAction = -1;
                this.modified = true;
                this.status = "\u5df2\u7c98\u8d34\uff0c\u8bb0\u5f97\u4fdd\u5b58";
            } else {
                this.status = "\u526a\u8d34\u677f\u662f\u7a7a\u7684";
            }
            return;
        }
        if (id.equals("save")) {
            NetworkHandler.sendToServer(new PanelEditMessage(this.pos, this.layout, this.program));
            this.modified = false;
            this.status = "\u5df2\u4fdd\u5b58\u5e76\u5e94\u7528\u5230\u76d1\u63a7\u5c4f";
        }
    }

    private boolean handleDesign(String id) {
        if (id.startsWith("add:")) {
            WidgetType type = WidgetType.byName(id.substring(4));
            Widget widget = this.layout.add(type);
            if (widget == null) {
                this.status = "\u63a7\u4ef6\u5df2\u6ee1\uff08" + PanelLayout.MAX_WIDGETS + "\uff09\u6216\u753b\u5e03\u6ca1\u6709\u7a7a\u4f4d";
            } else {
                this.selWidget = this.layout.size() - 1;
                this.modified = true;
                this.status = "\u5df2\u6dfb\u52a0 " + type.label();
            }
            return true;
        }
        Widget widget = currentWidget();
        if (id.startsWith("w:")) {
            String rest = id.substring(2);
            if (rest.equals("del")) {
                this.layout.remove(this.selWidget);
                this.selWidget = this.layout.size() - 1;
                this.modified = true;
                return true;
            }
            int index = number(rest);
            if (index >= 0 && index < this.layout.size()) {
                this.selWidget = index;
                this.selAction = -1;
                this.selCond = -1;
                // Begin a drag; the offset keeps the grabbed corner under the
                // cursor instead of snapping the control's origin to it.
                int[] cell = this.canvas.cellAt(lastMouseX - this.left, lastMouseY - this.top);
                Widget target = this.layout.widgets.get(index);
                this.dragging = true;
                this.dragIndex = index;
                this.dragCellX = cell == null ? 0 : cell[0] - target.gx;
                this.dragCellY = cell == null ? 0 : cell[1] - target.gy;
            }
            return true;
        }
        if (widget == null) {
            return false;
        }
        if (id.equals("ch:-") || id.equals("ch:+")) {
            Channel next = widget.channel == null ? Channel.TEMPERATURE
                    : widget.channel.next(id.endsWith("+") ? 1 : -1);
            widget.channel = next;
            widget.min = next.min();
            widget.max = next.max();
            widget.decimals = next.decimals();
            if (widget.title == null || widget.title.isEmpty() || isChannelName(widget.title)) {
                widget.title = next.label();
            }
            this.modified = true;
            return true;
        }
        if (id.equals("min:-") || id.equals("min:+")) {
            widget.min += (id.endsWith("+") ? 1 : -1) * step(widget.max);
            widget.clamp();
            this.modified = true;
            return true;
        }
        if (id.equals("max:-") || id.equals("max:+")) {
            widget.max += (id.endsWith("+") ? 1 : -1) * step(widget.max);
            widget.clamp();
            this.modified = true;
            return true;
        }
        if (id.equals("dec:-") || id.equals("dec:+")) {
            widget.decimals = Math.max(0, Math.min(3, widget.decimals + (id.endsWith("+") ? 1 : -1)));
            this.modified = true;
            return true;
        }
        if (id.equals("color")) {
            widget.color = (widget.color + 1) % 5;
            this.modified = true;
            return true;
        }
        if (id.equals("gw:-") || id.equals("gw:+")) {
            int next = widget.gw + (id.endsWith("+") ? 1 : -1);
            if (next >= widget.minGw() && !this.layout.blocked(this.selWidget, widget.gx, widget.gy,
                    next, widget.gh)) {
                widget.gw = next;
                widget.clamp();
                this.modified = true;
            }
            return true;
        }
        if (id.equals("gh:-") || id.equals("gh:+")) {
            int next = widget.gh + (id.endsWith("+") ? 1 : -1);
            if (next >= widget.minGh() && !this.layout.blocked(this.selWidget, widget.gx, widget.gy,
                    widget.gw, next)) {
                widget.gh = next;
                widget.clamp();
                this.modified = true;
            }
            return true;
        }
        return false;
    }

    private static boolean isChannelName(String title) {
        for (Channel channel : Channel.values()) {
            if (channel.label().equals(title)) {
                return true;
            }
        }
        return false;
    }

    private boolean handleLogic(String id) {
        Rule rule = currentRule();
        if (id.equals("rule:add")) {
            Rule added = this.program.add();
            if (added == null) {
                this.status = "\u89c4\u5219\u5df2\u6ee1\uff08" + LogicProgram.MAX_RULES + "\uff09";
            } else {
                this.selRule = this.program.rules.size() - 1;
                this.selCond = -1;
                this.selAction = -1;
                this.modified = true;
                this.status = "\u5df2\u65b0\u589e\u89c4\u5219";
            }
            return true;
        }
        if (id.startsWith("rule:")) {
            String rest = id.substring(5);
            if (rest.equals("del")) {
                this.program.remove(this.selRule);
                this.selRule = Math.min(this.selRule, this.program.rules.size() - 1);
                this.selCond = -1;
                this.selAction = -1;
                this.modified = true;
                return true;
            }
            if (rest.equals("enabled")) {
                if (rule != null) {
                    rule.enabled = !rule.enabled;
                    this.modified = true;
                }
                return true;
            }
            int index = number(rest);
            if (index >= 0 && index < this.program.rules.size()) {
                this.selRule = index;
                this.selCond = -1;
                this.selAction = -1;
            }
            return true;
        }
        if (rule == null) {
            return false;
        }
        if (id.equals("trig")) {
            rule.trigger = (rule.trigger + 1) % Rule.TRIGGER_NAMES.length;
            this.modified = true;
            return true;
        }
        if (id.equals("junc")) {
            rule.junction = rule.junction == 0 ? 1 : 0;
            this.modified = true;
            return true;
        }
        if (id.equals("iv:-") || id.equals("iv:+")) {
            rule.interval = Math.max(10, Math.min(400, rule.interval + (id.endsWith("+") ? 5 : -5)));
            this.modified = true;
            return true;
        }
        if (id.equals("cd:-") || id.equals("cd:+")) {
            rule.cooldown = Math.max(0, Math.min(600, rule.cooldown + (id.endsWith("+") ? 5 : -5)));
            this.modified = true;
            return true;
        }
        if (id.equals("cond:add")) {
            if (rule.conditions.size() >= Rule.MAX_CONDITIONS) {
                this.status = "\u6761\u4ef6\u5df2\u6ee1\uff08" + Rule.MAX_CONDITIONS + "\uff09";
            } else {
                Condition condition = new Condition();
                condition.left = Channel.TEMPERATURE;
                condition.right = 6000.0D;
                rule.conditions.add(condition);
                this.selCond = rule.conditions.size() - 1;
                this.selAction = -1;
                this.modified = true;
            }
            return true;
        }
        if (id.startsWith("cond:")) {
            String rest = id.substring(5);
            if (rest.equals("del")) {
                if (this.selCond >= 0) {
                    rule.conditions.remove(this.selCond);
                    this.selCond = -1;
                    this.modified = true;
                }
                return true;
            }
            int index = number(rest);
            if (index >= 0 && index < rule.conditions.size()) {
                this.selCond = index;
                this.selAction = -1;
            }
            return true;
        }
        if (id.equals("act:add")) {
            if (rule.actions.size() >= Rule.MAX_ACTIONS) {
                this.status = "\u52a8\u4f5c\u5df2\u6ee1\uff08" + Rule.MAX_ACTIONS + "\uff09";
            } else {
                rule.actions.add(Action.of(ActionType.ALARM));
                this.selAction = rule.actions.size() - 1;
                this.selCond = -1;
                this.modified = true;
            }
            return true;
        }
        if (id.startsWith("act:")) {
            String rest = id.substring(4);
            if (rest.equals("del")) {
                if (this.selAction >= 0) {
                    rule.actions.remove(this.selAction);
                    this.selAction = -1;
                    this.modified = true;
                }
                return true;
            }
            int index = number(rest);
            if (index >= 0 && index < rule.actions.size()) {
                this.selAction = index;
                this.selCond = -1;
            }
            return true;
        }
        return handleConditionAndAction(id, rule);
    }

    private boolean handleConditionAndAction(String id, Rule rule) {
        Condition condition = currentCondition();
        if (condition != null) {
            if (id.equals("c:ch")) {
                condition.left = condition.left.next(1);
                if (condition.left.isBoolean()) {
                    condition.right = 1.0D;
                    condition.op = 4;
                }
                this.modified = true;
                return true;
            }
            if (id.equals("c:op")) {
                condition.op = (condition.op + 1) % Condition.OPS.length;
                this.modified = true;
                return true;
            }
            if (id.equals("c:right")) {
                // Constant -> channel -> constant: the second form is what lets
                // a rule say "hotter than its own melting point".
                condition.rightChannel = condition.rightChannel == null
                        ? Channel.MELTING : null;
                this.modified = true;
                return true;
            }
            if (id.equals("c:val:-") || id.equals("c:val:+")) {
                condition.right += (id.endsWith("+") ? 1 : -1) * step(condition.right);
                this.modified = true;
                return true;
            }
            if (id.equals("c:del")) {
                rule.conditions.remove(this.selCond);
                this.selCond = -1;
                this.modified = true;
                return true;
            }
        }
        Action action = currentAction();
        if (action != null) {
            if (id.equals("a:type")) {
                action.type = action.type.next(1);
                action.clamp();
                this.modified = true;
                return true;
            }
            if (id.equals("a:amt:-") || id.equals("a:amt:+")) {
                action.amount = Math.max(1.0D, Math.min(15.0D,
                        action.amount + (id.endsWith("+") ? 1 : -1)));
                this.modified = true;
                return true;
            }
            if (id.equals("a:sec:-") || id.equals("a:sec:+")) {
                action.seconds = Math.max(1.0D, Math.min(120.0D,
                        action.seconds + (id.endsWith("+") ? 1 : -1)));
                this.modified = true;
                return true;
            }
            if (id.equals("a:page")) {
                action.page = (action.page + 1) % Action.PAGE_NAMES.length;
                this.modified = true;
                return true;
            }
            if (id.equals("a:del")) {
                rule.actions.remove(this.selAction);
                this.selAction = -1;
                this.modified = true;
                return true;
            }
        }
        return false;
    }

    private boolean handlePreset(String id) {
        if (id.equals("preset:save")) {
            String name = PanelPresets.sanitize(this.presetName);
            if (name.isEmpty()) {
                name = PanelPresets.sanitize(this.layout.name);
            }
            if (name.isEmpty()) {
                this.status = "\u5148\u7ed9\u9884\u8bbe\u8d77\u4e2a\u540d\u5b57";
            } else {
                PanelPresets.get().put(name, this.layout, this.program);
                this.status = "\u5df2\u4fdd\u5b58\u9884\u8bbe " + name;
            }
            return true;
        }
        if (id.equals("preset:load")) {
            if (this.selPreset >= 0) {
                PanelLayout loaded = PanelPresets.get().layout(this.selPreset);
                if (loaded != null) {
                    this.layout = loaded;
                    this.program = PanelPresets.get().logic(this.selPreset);
                    this.selWidget = this.layout.size() > 0 ? 0 : -1;
                    this.selRule = this.program.rules.size() > 0 ? 0 : -1;
                    this.selCond = -1;
                    this.selAction = -1;
                    this.modified = true;
                    this.status = "\u5df2\u52a0\u8f7d\u9884\u8bbe\uff0c\u8bb0\u5f97\u4fdd\u5b58";
                }
            } else {
                this.status = "\u5148\u5728\u5de6\u4fa7\u9009\u4e2d\u4e00\u4e2a\u9884\u8bbe";
            }
            return true;
        }
        if (id.equals("preset:del")) {
            if (this.selPreset >= 0) {
                PanelPresets.get().remove(this.selPreset);
                this.selPreset = -1;
                this.status = "\u5df2\u5220\u9664\u9884\u8bbe";
            }
            return true;
        }
        if (id.startsWith("preset:")) {
            int index = number(id.substring(7));
            if (index >= 0 && index < PanelPresets.get().names().size()) {
                this.selPreset = index;
                this.presetName = PanelPresets.get().names().get(index);
            }
            return true;
        }
        return false;
    }

    /** Last cursor position, needed to compute the drag offset on click. */
    private int lastMouseX;
    private int lastMouseY;

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (!this.dragging || this.dragIndex < 0 || this.dragIndex >= this.layout.size()) {
            return;
        }
        int[] cell = this.canvas.cellAt(mouseX - this.left, mouseY - this.top);
        if (cell == null) {
            return;
        }
        Widget widget = this.layout.widgets.get(this.dragIndex);
        int nx = Math.max(0, Math.min(PanelLayout.COLS - widget.gw, cell[0] - this.dragCellX));
        int ny = Math.max(0, Math.min(PanelLayout.ROWS - widget.gh, cell[1] - this.dragCellY));
        if (!this.layout.blocked(this.dragIndex, nx, ny, widget.gw, widget.gh)) {
            widget.gx = nx;
            widget.gy = ny;
            this.modified = true;
            this.canvas.markDirty();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = false;
        this.dragIndex = -1;
        this.canvas.setDragging(false);
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.field.getVisible() && this.field.isFocused() && keyCode != 1) {
            if (this.field.textboxKeyTyped(typedChar, keyCode)) {
                return;
            }
        }
        // Deleting the selected control with the keyboard is the one shortcut
        // this window needs: it is what players reach for first. Suppressed
        // while a text field has focus, where delete means backspace.
        if (keyCode == org.lwjgl.input.Keyboard.KEY_DELETE
                && this.mode == StudioCanvas.MODE_DESIGN
                && this.selWidget >= 0 && !this.field.isFocused()) {
            this.layout.remove(this.selWidget);
            this.selWidget = this.layout.size() - 1;
            this.modified = true;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }
        // The rule list can overflow a short window; the wheel picks the rule
        // instead of growing a scrollbar nobody asked for.
        if (this.mode == StudioCanvas.MODE_LOGIC && !this.program.rules.isEmpty()) {
            int delta = wheel > 0 ? -1 : 1;
            this.selRule = Math.max(0, Math.min(this.program.rules.size() - 1, this.selRule + delta));
            this.selCond = -1;
            this.selAction = -1;
            this.canvas.markDirty();
        }
    }

    @Override
    public void onGuiClosed() {
        this.canvas.dispose();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ------------------------------------------------------------------ util

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static int number(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    /** A step that stays useful across five orders of magnitude. */
    private static double step(double value) {
        double abs = Math.abs(value);
        if (abs >= 10000.0D) {
            return 1000.0D;
        }
        if (abs >= 1000.0D) {
            return 100.0D;
        }
        if (abs >= 100.0D) {
            return 10.0D;
        }
        return 1.0D;
    }

    /** Full-bright textured quad, same recipe as the terminal's. */
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

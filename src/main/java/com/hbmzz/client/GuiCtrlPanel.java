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

import com.hbmzz.client.screen.CtrlCanvas;
import com.hbmzz.ctrl.CtrlEngine;
import com.hbmzz.ctrl.CtrlLink;
import com.hbmzz.ctrl.CtrlPanel;
import com.hbmzz.ctrl.CtrlRule;
import com.hbmzz.ctrl.CtrlType;
import com.hbmzz.ctrl.CtrlWidget;
import com.hbmzz.ctrl.MachineBridge;
import com.hbmzz.item.ItemMonitorLinker;
import com.hbmzz.network.CtrlDesignMessage;
import com.hbmzz.network.CtrlUseMessage;
import com.hbmzz.network.NetworkHandler;
import com.hbmzz.tile.TileEntityCtrlPanel;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.io.IOException;
import java.util.List;

/**
 * 机器控制面板: the window that both <em>drives</em> the panel and <em>builds</em>
 * it.
 *
 * <p>Three tabs, one model. 运行 is the panel as an operator meets it - turn a
 * knob and the machine behind it moves; 控件 and 联动 are the workshop, where
 * that knob is placed and wired. They share one working copy ({@link #work}),
 * which is why a control added in the editor can be tried out in 运行 before it
 * is saved, and why nothing reaches the world until 保存并应用.</p>
 *
 * <p>Server authority is split deliberately. Operator input goes out
 * immediately as {@link CtrlUseMessage} - index and value only - so the machine
 * reacts under the player's hand; the server's copy decides which event, which
 * variable and which machine that index means. Design, by contrast, is sent
 * once, whole, as {@link CtrlDesignMessage}, and re-validated there. Editors are
 * conveniences; the server is the boundary.</p>
 *
 * <p>The window itself is one AWT-painted sheet (see {@link CtrlCanvas}), the
 * same trick the monitor terminal uses. That buys real typography and lets the
 * panel preview be painted by the exact {@link com.hbmzz.client.screen.CtrlPainter}
 * the block face would use, so the arrangement cannot drift from what it
 * describes.</p>
 */
public class GuiCtrlPanel extends GuiScreen {

    /** Milliseconds during which local input outranks an incoming sync. */
    private static final long OP_HOLD = 600L;

    private final BlockPos pos;
    private final CtrlCanvas canvas = new CtrlCanvas();

    /** Working copy. The tile's panel is only read from, never edited here. */
    private CtrlPanel work;

    private int mode;
    private int selWidget = -1;
    private int selRule = -1;

    private int winW;
    private int winH;
    private int left;
    private int top;
    private float scale = 1.0F;

    private boolean dragging;
    private int dragIndex = -1;
    private int dragCellX;
    private int dragCellY;

    /** Index of an operator control being held down in 运行, -1 for none. */
    private int operating = -1;
    /** Last index the player drove, so a stale sync cannot snap it back. */
    private int lastOpIndex = -1;
    private long lastOpAt;

    private String editId = "";
    private String editText = "";

    private String status = "右键控件可以删除";
    private boolean modified;

    /** Next-frame request, and when the sheet was last painted. */
    private boolean frameDue = true;
    private long lastFrame;

    private int lastMouseX;
    private int lastMouseY;

    public GuiCtrlPanel(BlockPos pos) {
        this.pos = pos;
    }

    private TileEntityCtrlPanel tile() {
        if (this.mc == null || this.mc.world == null) {
            return null;
        }
        TileEntity te = this.mc.world.getTileEntity(this.pos);
        return te instanceof TileEntityCtrlPanel ? (TileEntityCtrlPanel) te : null;
    }

    private CtrlWidget widget() {
        return this.work == null ? null : this.work.get(this.selWidget);
    }

    private CtrlRule rule() {
        if (this.work == null || this.selRule < 0 || this.selRule >= this.work.rules.size()) {
            return null;
        }
        return this.work.rules.get(this.selRule);
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void initGui() {
        // initGui also runs on a window resize, so the working copy is built
        // once and only the geometry is recomputed - a resize must not throw
        // away ten minutes of arranging controls.
        if (this.work == null) {
            TileEntityCtrlPanel te = tile();
            this.work = te != null ? te.getPanel().copy() : CtrlPanel.defaults();
            this.mode = 0;
            this.selWidget = this.work.widgets.isEmpty() ? -1 : 0;
            this.selRule = this.work.rules.isEmpty() ? -1 : 0;
            this.modified = false;
        }

        // Fit the 640x400 sheet into whatever the GUI scale left us. Larger
        // screens get a 2x cap: past that the window stops reading as a
        // physical panel and starts reading as a billboard.
        this.scale = Math.min(Math.min((this.width - 8) / (float) CtrlCanvas.W,
                (this.height - 8) / (float) CtrlCanvas.H), 2.0F);
        this.winW = Math.round(CtrlCanvas.W * this.scale);
        this.winH = Math.round(CtrlCanvas.H * this.scale);
        this.left = (this.width - this.winW) / 2;
        this.top = (this.height - this.winH) / 2;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        this.drawRect(0, 0, this.width, this.height, 0xC8080D12);
        syncRuntime();

        int lx = toLogicalX(mouseX);
        int ly = toLogicalY(mouseY);
        long now = System.currentTimeMillis();
        // 20fps for a sheet this size (640x400 built at 3x). A control panel is
        // not an animation; redrawing it at the frame rate only burns CPU. Any
        // click, drag or keystroke pulls the next frame forward, so input still
        // feels immediate.
        if (this.frameDue || now - this.lastFrame >= 50L || this.canvas.textureId() == 0) {
            this.frameDue = false;
            this.lastFrame = now;
            this.canvas.render(this.work, this.mode, this.selWidget, this.selRule,
                    this.dragging ? this.dragIndex : -1, lx, ly, statusLine(),
                    MachineBridge.available(), this.editId, this.editText);
            this.canvas.upload();
        }
        drawTexture(this.left, this.top, this.winW, this.winH, this.canvas.textureId());
    }

    /**
     * Copies the live values off the tile into the working copy.
     *
     * <p>Design fields are never touched: this is what makes the readouts on a
     * 运行 tab that is also an editor show current numbers. The control the
     * player is currently turning is skipped for a moment, because the server's
     * acknowledgement of that turn arrives after the next sync and would
     * otherwise yank the knob back under the cursor.</p>
     */
    private void syncRuntime() {
        TileEntityCtrlPanel te = tile();
        if (te == null) {
            return;
        }
        CtrlPanel live = te.getPanel();
        long now = System.currentTimeMillis();
        int count = Math.min(this.work.widgets.size(), live.widgets.size());
        for (int i = 0; i < count; i++) {
            CtrlWidget mine = this.work.widgets.get(i);
            CtrlWidget theirs = live.widgets.get(i);
            if (mine.type != theirs.type) {
                continue;
            }
            if (i == this.lastOpIndex && now - this.lastOpAt < OP_HOLD) {
                continue;
            }
            mine.value = theirs.value;
            mine.text = theirs.text;
            mine.latched = theirs.latched;
        }
    }

    private String statusLine() {
        String text = this.status == null ? "" : this.status;
        return this.modified ? text + " · 未保存" : text;
    }

    // ------------------------------------------------------------ interaction

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.frameDue = true;
        int lx = toLogicalX(mouseX);
        int ly = toLogicalY(mouseY);
        String id = this.canvas.hitAt(lx, ly);

        if (id == null) {
            this.editId = "";
            return;
        }
        if (handleRun(id, lx, ly)) {
            return;
        }
        if (mouseButton == 1) {
            // Right click deletes, like the monitor's studio. One gesture,
            // no context menu to hunt for.
            if (this.mode != 0 && id.startsWith("widget:")) {
                this.selWidget = number(id.substring(7));
                deleteWidget();
            }
            return;
        }
        handle(id, lx, ly);
    }

    private void handle(String id, int lx, int ly) {
        if (id.startsWith("tab:")) {
            switchMode(number(id.substring(4)));
            return;
        }
        if (id.startsWith("goto:")) {
            switchMode(number(id.substring(5)));
            return;
        }
        if (id.startsWith("add:")) {
            addWidget(CtrlType.byName(id.substring(4)));
            return;
        }
        if (id.startsWith("widget:")) {
            int index = number(id.substring(7));
            if (this.work.get(index) == null) {
                return;
            }
            this.selWidget = index;
            this.editId = "";
            beginDrag(index, lx, ly);
            return;
        }
        if (id.startsWith("run:")) {
            return;
        }
        if (id.startsWith("slot:")) {
            handleSlot(id);
            return;
        }
        if (id.startsWith("field:")) {
            beginEdit(id);
            return;
        }
        if (id.startsWith("rule:")) {
            handleRulePick(id);
            return;
        }
        if (id.equals("addrule")) {
            addRule();
            return;
        }
        if (id.equals("save")) {
            save();
            return;
        }
        if (id.equals("delwidget")) {
            deleteWidget();
            return;
        }
        if (id.equals("copywidget")) {
            copyWidget();
            return;
        }
        if (id.equals("pickkey")) {
            pickKey();
            return;
        }
        if (id.equals("pickevent")) {
            pickEvent();
            return;
        }
        if (id.equals("rtoggle")) {
            CtrlRule rule = rule();
            if (rule != null) {
                rule.enabled = !rule.enabled;
                touch();
            }
            return;
        }
        if (id.equals("rdel")) {
            deleteRule();
            return;
        }
        handleStepper(id);
    }

    private void switchMode(int next) {
        this.mode = Math.max(0, Math.min(2, next));
        this.editId = "";
        this.operating = -1;
        this.dragging = false;
        this.dragIndex = -1;
        this.status = this.mode == 0 ? "拖动旋钮、单击开关即可操控机器" : "右键控件可以删除";
        this.canvas.markDirty();
    }

    private void addWidget(CtrlType type) {
        CtrlWidget added = this.work.add(type);
        if (added == null) {
            this.status = "控件已满（" + CtrlPanel.MAX_WIDGETS + "）或没有空位";
            return;
        }
        this.selWidget = this.work.widgets.size() - 1;
        this.status = "已添加 " + type.label();
        touch();
    }

    private void deleteWidget() {
        if (this.selWidget < 0 || this.selWidget >= this.work.widgets.size()) {
            return;
        }
        this.work.remove(this.selWidget);
        this.selWidget = Math.min(this.selWidget, this.work.widgets.size() - 1);
        this.status = "已删除控件";
        touch();
    }

    private void copyWidget() {
        CtrlWidget source = widget();
        if (source == null) {
            return;
        }
        CtrlWidget copy = source.copy();
        // Find a spot near the original rather than at the top-left corner:
        // duplicates usually belong next to what they were copied from.
        int w = copy.gw;
        int h = copy.gh;
        if (!this.work.blocked(-1, copy.gx + w, copy.gy, w, h)) {
            copy.gx += w;
        } else if (!this.work.blocked(-1, copy.gx, copy.gy + h, w, h)) {
            copy.gy += h;
        } else if (!this.work.blocked(-1, 0, 0, w, h)) {
            copy.gx = 0;
            copy.gy = 0;
        } else {
            this.status = "没有空位放副本了";
            return;
        }
        if (this.work.widgets.size() >= CtrlPanel.MAX_WIDGETS) {
            this.status = "控件已满（" + CtrlPanel.MAX_WIDGETS + "）";
            return;
        }
        this.work.widgets.add(copy);
        this.selWidget = this.work.widgets.size() - 1;
        this.status = "已复制控件";
        touch();
    }

    private void addRule() {
        CtrlRule added = this.work.addRule();
        if (added == null) {
            this.status = "规则已满（" + CtrlPanel.MAX_RULES + "）";
            return;
        }
        this.selRule = this.work.rules.size() - 1;
        this.status = "已新增规则";
        touch();
    }

    private void deleteRule() {
        CtrlRule rule = rule();
        if (rule == null) {
            return;
        }
        this.work.rules.remove(this.selRule);
        this.selRule = Math.min(this.selRule, this.work.rules.size() - 1);
        this.status = "已删除规则";
        touch();
    }

    private void handleRulePick(String id) {
        String rest = id.substring(5);
        if (rest.equals("new")) {
            addRule();
            return;
        }
        int index = number(rest);
        if (index >= 0 && index < this.work.rules.size()) {
            this.selRule = index;
            this.editId = "";
            this.canvas.markDirty();
        }
    }

    // ----------------------------------------------------------------- slots

    private void handleSlot(String id) {
        String[] parts = id.split(":");
        if (parts.length < 3) {
            return;
        }
        int slot = number(parts[1]);
        if (slot < 0 || slot >= CtrlPanel.LINKS) {
            return;
        }
        if (parts[2].equals("bind")) {
            bindSlot(slot);
        } else if (parts[2].equals("clear")) {
            this.work.links[slot].clear();
            this.status = "槽位 " + CtrlLink.letter(slot) + " 已清空";
            touch();
        }
    }

    /**
     * Binds a slot to the machine recorded in the held data line.
     *
     * <p>The position is not sent here - it becomes part of the design, and the
     * design is sent when 保存 is pressed. That keeps one write path into the
     * world instead of two, and means a mis-click can be taken back by simply
     * not saving.</p>
     */
    private void bindSlot(int slot) {
        BlockPos target = heldMachinePos();
        if (target == null) {
            this.status = "先手持数据线右键机器记录";
            return;
        }
        double limit = TileEntityCtrlPanel.LINK_RANGE;
        if (this.pos.distanceSq(target) > limit * limit) {
            this.status = "超出 " + (int) limit + " 格范围";
            return;
        }
        CtrlLink link = this.work.links[slot];
        link.set(target, "");
        MachineBridge.describe(link, this.mc.world);
        if (link.label.isEmpty()) {
            link.label = "机器";
        }
        this.status = CtrlLink.letter(slot) + " → " + link.label;
        touch();
    }

    /** Machine position stashed in the held data line, or null. */
    private BlockPos heldMachinePos() {
        BlockPos found = readCtrlTag(this.mc.player.getHeldItemMainhand());
        return found != null ? found : readCtrlTag(this.mc.player.getHeldItemOffhand());
    }

    private static BlockPos readCtrlTag(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof ItemMonitorLinker)) {
            return null;
        }
        NBTTagCompound tag = stack.getTagCompound();
        return tag != null && tag.hasKey("ctrl") ? BlockPos.fromLong(tag.getLong("ctrl")) : null;
    }

    // ------------------------------------------------------------- run input

    /** Drives an operator control. Returns false when the click was not one. */
    private boolean handleRun(String id, int lx, int ly) {
        if (this.mode != 0 || !id.startsWith("run:")) {
            return false;
        }
        int index = number(id.substring(4));
        CtrlWidget target = this.work.get(index);
        if (target == null || !target.isOperator()) {
            return false;
        }
        if (target.type == CtrlType.SWITCH) {
            operate(index, target.value >= 0.5D ? 0.0D : 1.0D);
        } else if (target.type == CtrlType.BUTTON) {
            // A button is a momentary press: the event goes out on every hit,
            // even when the value was already 1, because the machine may treat
            // it as a trigger. The cap springs back immediately.
            push(index, 1.0D);
            target.value = 0.0D;
            this.canvas.markDirty();
        } else if (target.type == CtrlType.ESTOP) {
            operate(index, target.latched ? 0.0D : 1.0D);
        } else {
            this.operating = index;
            dragTo(lx, ly);
        }
        return true;
    }

    /**
     * Maps the cursor onto a knob or a slider.
     *
     * <p>Angles follow the painter: 225 degrees is the left end of the sweep,
     * and the sweep runs up over the top to 315. The wedge at the bottom is dead
     * on a real knob, so it snaps to whichever end is nearer instead of
     * jumping.</p>
     */
    private void dragTo(int lx, int ly) {
        CtrlWidget target = this.work.get(this.operating);
        if (target == null) {
            return;
        }
        int[] rect = CtrlCanvas.rectOf(target);
        double ratio;
        if (target.type == CtrlType.SLIDER) {
            int span = Math.max(1, rect[3] - 8);
            ratio = 1.0D - (ly - rect[1] - 4) / (double) span;
        } else {
            double cx = rect[0] + rect[2] / 2.0D;
            double cy = rect[1] + rect[3] / 2.0D;
            double deg = Math.toDegrees(Math.atan2(cy - ly, lx - cx));
            if (deg < -45.0D) {
                deg += 360.0D;
            }
            if (deg >= 315.0D) {
                ratio = 1.0D;
            } else if (deg > 225.0D) {
                ratio = deg < 270.0D ? 0.0D : 1.0D;
            } else {
                ratio = (225.0D - deg) / 270.0D;
            }
        }
        ratio = Math.max(0.0D, Math.min(1.0D, ratio));
        operate(this.operating, target.min + ratio * (target.max - target.min));
    }

    private void operate(int index, double value) {
        CtrlWidget target = this.work.get(index);
        if (target == null || !target.isOperator()) {
            return;
        }
        push(index, CtrlEngine.quantise(target, value));
    }

    /**
     * Applies a value locally and tells the server.
     *
     * <p>Local first, because the round trip is a tick and a knob that lags its
     * own cursor feels broken. The server's copy lands half a second later and
     * wins; it is the same arithmetic, so there is nothing to argue about.</p>
     */
    private void push(int index, double value) {
        CtrlWidget target = this.work.get(index);
        if (target == null) {
            return;
        }
        target.value = value;
        if (target.type == CtrlType.ESTOP) {
            target.latched = value >= 0.5D;
        }
        this.lastOpIndex = index;
        this.lastOpAt = System.currentTimeMillis();
        NetworkHandler.sendToServer(new CtrlUseMessage(this.pos, index, value));
        this.canvas.markDirty();
    }

    // ---------------------------------------------------------- text editing

    private void beginEdit(String id) {
        String value = currentText(id);
        if (value == null) {
            return;
        }
        this.editId = id;
        this.editText = value;
        this.canvas.markDirty();
    }

    /** Seed text for a field; null when the field does not apply. */
    private String currentText(String id) {
        CtrlWidget widget = widget();
        CtrlRule rule = rule();
        switch (id) {
            case "field:label":
                return widget == null ? null : widget.label;
            case "field:key":
                return widget == null ? null : widget.key;
            case "field:event":
                return widget == null ? null : widget.event;
            case "field:var":
                return widget == null ? null : widget.var;
            case "field:rkey":
                return rule == null ? null : rule.srcKey;
            case "field:revent":
                return rule == null ? null : rule.dstEvent;
            case "field:rvar":
                return rule == null ? null : rule.dstVar;
            default:
                return null;
        }
    }

    private void commitEdit() {
        String text = this.editText == null ? "" : this.editText.trim();
        CtrlWidget widget = widget();
        CtrlRule rule = rule();
        switch (this.editId) {
            case "field:label":
                if (widget != null) {
                    widget.label = text;
                    widget.clamp();
                }
                break;
            case "field:key":
                if (widget != null) {
                    widget.key = text;
                }
                break;
            case "field:event":
                if (widget != null) {
                    widget.event = text;
                    if (widget.var.isEmpty()) {
                        widget.var = defaultVar(widget.link, text);
                    }
                }
                break;
            case "field:var":
                if (widget != null) {
                    widget.var = text;
                }
                break;
            case "field:rkey":
                if (rule != null) {
                    rule.srcKey = text;
                }
                break;
            case "field:revent":
                if (rule != null) {
                    rule.dstEvent = text;
                    if (rule.dstVar.isEmpty()) {
                        rule.dstVar = defaultVar(rule.dstLink, text);
                    }
                }
                break;
            case "field:rvar":
                if (rule != null) {
                    rule.dstVar = text;
                }
                break;
            default:
                break;
        }
        this.editId = "";
        this.editText = "";
        touch();
    }

    /** Variable name the machine expects for an event, when we know it. */
    private String defaultVar(int slot, String event) {
        if (slot < 0 || slot >= CtrlPanel.LINKS || this.work.links[slot].isEmpty()) {
            return "";
        }
        return MachineBridge.defaultVar(this.mc.world.getTileEntity(this.work.links[slot].pos), event);
    }

    // ---------------------------------------------------------- key / event

    private void pickKey() {
        CtrlWidget widget = widget();
        if (widget == null) {
            return;
        }
        if (!widget.bound() || this.work.links[widget.link].isEmpty()) {
            this.status = "先给控件选一个已绑定的槽位";
            return;
        }
        List<String> keys = this.work.links[widget.link].keys;
        if (keys.isEmpty()) {
            this.status = "这台机器没有上报可读数据";
            return;
        }
        int at = keys.indexOf(widget.key);
        widget.key = keys.get((at + 1) % keys.size());
        this.status = "数据键 → " + widget.key;
        touch();
    }

    private void pickEvent() {
        CtrlWidget widget = widget();
        if (widget == null) {
            return;
        }
        if (!widget.bound() || this.work.links[widget.link].isEmpty()) {
            this.status = "先给控件选一个已绑定的槽位";
            return;
        }
        List<String> events = this.work.links[widget.link].events;
        if (events.isEmpty()) {
            this.status = "这台机器没有公布可接受的事件";
            return;
        }
        int at = events.indexOf(widget.event);
        String next = events.get((at + 1) % events.size());
        widget.event = next;
        String var = MachineBridge.defaultVar(this.mc.world.getTileEntity(this.work.links[widget.link].pos), next);
        if (!var.isEmpty() || widget.var.isEmpty()) {
            widget.var = var;
        }
        this.status = "事件 → " + next;
        touch();
    }

    // -------------------------------------------------------------- steppers

    private void handleStepper(String id) {
        CtrlWidget widget = widget();
        CtrlRule rule = rule();
        boolean plus = id.endsWith("+");
        if (widget != null) {
            if (id.startsWith("wslot:")) {
                widget.link = cycleLink(widget.link, plus ? 1 : -1);
                this.status = "槽位 → " + CtrlLink.letter(widget.link);
                touch();
                return;
            }
            if (id.startsWith("min:") || id.startsWith("max:")) {
                boolean isMin = id.startsWith("min:");
                double base = isMin ? widget.min : widget.max;
                double next = base + (plus ? 1 : -1) * step(base);
                if (isMin) {
                    widget.min = Math.min(next, widget.max - 1.0E-3D);
                } else {
                    widget.max = Math.max(next, widget.min + 1.0E-3D);
                }
                widget.clamp();
                touch();
                return;
            }
            if (id.startsWith("steps:")) {
                widget.steps = Math.max(2, Math.min(16, widget.steps + (plus ? 1 : -1)));
                touch();
                return;
            }
            if (id.startsWith("dec:")) {
                widget.decimals = Math.max(0, Math.min(3, widget.decimals + (plus ? 1 : -1)));
                touch();
                return;
            }
            if (id.startsWith("color:")) {
                widget.color = Math.floorMod(widget.color + (plus ? 1 : -1), 4);
                touch();
                return;
            }
        }
        if (rule != null) {
            if (id.startsWith("rslot:")) {
                rule.srcLink = cycleLink(rule.srcLink, plus ? 1 : -1);
                touch();
                return;
            }
            if (id.startsWith("rdslot:")) {
                rule.dstLink = cycleLink(rule.dstLink, plus ? 1 : -1);
                touch();
                return;
            }
            if (id.startsWith("rop:")) {
                rule.op = Math.floorMod(rule.op + (plus ? 1 : -1), CtrlRule.OPS.length);
                touch();
                return;
            }
            if (id.startsWith("rth:")) {
                rule.threshold += (plus ? 1 : -1) * step(rule.threshold);
                touch();
                return;
            }
            if (id.startsWith("rval:")) {
                rule.value += (plus ? 1 : -1) * step(rule.value);
                touch();
                return;
            }
            if (id.startsWith("rcd:")) {
                rule.cooldown = Math.max(0, Math.min(600, rule.cooldown + (plus ? 5 : -5)));
                touch();
            }
        }
    }

    /** -1 (unbound) then 0..5, wrapping in both directions. */
    private static int cycleLink(int current, int direction) {
        int next = current + direction;
        if (next < -1) {
            next = CtrlPanel.LINKS - 1;
        } else if (next >= CtrlPanel.LINKS) {
            next = -1;
        }
        return next;
    }

    private void touch() {
        this.modified = true;
        this.canvas.markDirty();
    }

    // ----------------------------------------------------------------- save

    private void save() {
        this.work.validate();
        NetworkHandler.sendToServer(new CtrlDesignMessage(this.pos, this.work));
        this.modified = false;
        this.status = "已保存并应用";
        this.canvas.markDirty();
    }

    // ------------------------------------------------------------ drag & keys

    private void beginDrag(int index, int lx, int ly) {
        int[] cell = this.canvas.cellAt(lx, ly);
        CtrlWidget target = this.work.get(index);
        if (cell == null || target == null) {
            return;
        }
        this.dragging = true;
        this.dragIndex = index;
        this.dragCellX = cell[0] - target.gx;
        this.dragCellY = cell[1] - target.gy;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        this.frameDue = true;
        int lx = toLogicalX(mouseX);
        int ly = toLogicalY(mouseY);
        if (this.operating >= 0) {
            dragTo(lx, ly);
            return;
        }
        if (!this.dragging || this.dragIndex < 0 || this.dragIndex >= this.work.widgets.size()) {
            return;
        }
        int[] cell = this.canvas.cellAt(lx, ly);
        if (cell == null) {
            return;
        }
        CtrlWidget target = this.work.widgets.get(this.dragIndex);
        int nx = Math.max(0, Math.min(CtrlPanel.COLS - target.gw, cell[0] - this.dragCellX));
        int ny = Math.max(0, Math.min(CtrlPanel.ROWS - target.gh, cell[1] - this.dragCellY));
        if (!this.work.blocked(this.dragIndex, nx, ny, target.gw, target.gh)) {
            target.gx = nx;
            target.gy = ny;
            touch();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        this.dragging = false;
        this.dragIndex = -1;
        this.operating = -1;
        super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        this.frameDue = true;
        if (!this.editId.isEmpty()) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                this.editId = "";
                this.editText = "";
                this.canvas.markDirty();
                return;
            }
            if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                commitEdit();
                return;
            }
            if (keyCode == Keyboard.KEY_BACK) {
                if (!this.editText.isEmpty()) {
                    this.editText = this.editText.substring(0, this.editText.length() - 1);
                    this.canvas.markDirty();
                }
                return;
            }
            if (keyCode == Keyboard.KEY_V && isCtrlKeyDown()) {
                String clip = getClipboardString();
                if (clip != null) {
                    this.editText = (this.editText + clip).replace('\n', ' ').replace('\r', ' ');
                    trimEdit();
                }
                return;
            }
            if (typedChar >= 32 && typedChar != 127) {
                this.editText = this.editText + typedChar;
                trimEdit();
            }
            return;
        }
        // Delete removes the selected control: the shortcut players reach for
        // first, and the one the studio already teaches.
        if (keyCode == Keyboard.KEY_DELETE && this.mode != 0 && this.selWidget >= 0) {
            deleteWidget();
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void trimEdit() {
        if (this.editText.length() > 40) {
            this.editText = this.editText.substring(0, 40);
        }
        this.canvas.markDirty();
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

    private int toLogicalX(int mouseX) {
        return Math.round((mouseX - this.left) / this.scale);
    }

    private int toLogicalY(int mouseY) {
        return Math.round((mouseY - this.top) / this.scale);
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

package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetTartarus;
import com.rsaudio.tile.TileTartarus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 塔塔鲁斯设置界面：<b>手持木斧 + 潜行右键</b>打开。
 *
 * <h3>为什么用木斧开</h3>
 * 空手右键要留给"手动开 / 关舱盖"。设置界面挂在木斧上，避免每次过门都被界面挡住。
 *
 * <h3>操作</h3>
 * 调半径（未建造时）→ 建造 → 之后开门 / 关门 / 拆除。半径改了要重新建造才生效。
 */
public class GuiTartarus extends GuiScreen {

    private static final int ID_RADIUS_MINUS = 0;
    private static final int ID_RADIUS_PLUS = 1;
    private static final int ID_BUILD = 2;
    private static final int ID_REMOVE = 3;
    private static final int ID_OPEN = 4;
    private static final int ID_CLOSE = 5;
    private static final int ID_DONE = 6;

    private final BlockPos pos;
    private int radius = TileTartarus.DEFAULT_RADIUS;
    private boolean built;
    private boolean open;
    private int progressPercent;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    private boolean loaded;

    private int rowTop, rowGap, btnW;

    public GuiTartarus(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();
        int cx = this.width / 2;
        this.btnW = Math.min(120, Math.max(40, (this.width - 16) / 6));
        int gap = 4;
        int bottomRowY = this.height - 32;
        this.rowGap = 24;
        this.rowTop = bottomRowY - this.rowGap;
        if (this.rowTop < 102) {
            this.rowGap = Math.max(18, bottomRowY - 102);
            this.rowTop = bottomRowY - this.rowGap;
        }

        int y0 = this.rowTop;
        int x0 = cx - (btnW * 2 + gap) / 2;
        this.buttonList.add(new GuiButton(ID_RADIUS_MINUS, x0, y0, btnW, 20,
                I18n.format("gui.rsaudio.tartarus.radius_minus")));
        this.buttonList.add(new GuiButton(ID_RADIUS_PLUS, x0 + (btnW + gap), y0, btnW, 20,
                I18n.format("gui.rsaudio.tartarus.radius_plus")));

        int y1 = this.rowTop + this.rowGap;
        int x1 = cx - (btnW * 6 + gap * 5) / 2;
        this.buttonList.add(new GuiButton(ID_BUILD, x1, y1, btnW, 20, I18n.format("gui.rsaudio.tartarus.build")));
        this.buttonList.add(new GuiButton(ID_REMOVE, x1 + (btnW + gap), y1, btnW, 20, I18n.format("gui.rsaudio.tartarus.remove")));
        this.buttonList.add(new GuiButton(ID_OPEN, x1 + (btnW + gap) * 2, y1, btnW, 20, I18n.format("gui.rsaudio.tartarus.btn_open")));
        this.buttonList.add(new GuiButton(ID_CLOSE, x1 + (btnW + gap) * 3, y1, btnW, 20, I18n.format("gui.rsaudio.tartarus.btn_close")));
        this.buttonList.add(new GuiButton(ID_DONE, x1 + (btnW + gap) * 4, y1, btnW, 20, I18n.format("gui.rsaudio.tartarus.done")));
    }

    private void readFromTile() {
        TileTartarus tile = getTile();
        if (tile == null) {
            return;
        }
        this.radius = tile.getRadius();
        this.built = tile.isBuilt();
        this.open = tile.wantsOpen();
        this.progressPercent = Math.round(tile.getOpenAmount() * 100.0F);
        this.loaded = true;
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_RADIUS_MINUS:
                this.radius = TileTartarus.clampRadius(this.radius - 1);
                sendRadius();
                break;
            case ID_RADIUS_PLUS:
                this.radius = TileTartarus.clampRadius(this.radius + 1);
                sendRadius();
                break;
            case ID_BUILD:
                send(PacketSetTartarus.BUILD);
                this.status = I18n.format("gui.rsaudio.tartarus.build_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_REMOVE:
                send(PacketSetTartarus.REMOVE);
                this.built = false;
                this.open = false;
                this.status = I18n.format("gui.rsaudio.tartarus.remove_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_OPEN:
                send(PacketSetTartarus.OPEN);
                this.status = I18n.format("gui.rsaudio.tartarus.open_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_CLOSE:
                send(PacketSetTartarus.CLOSE);
                this.status = I18n.format("gui.rsaudio.tartarus.close_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    private void sendRadius() {
        send(PacketSetTartarus.SET_RADIUS);
        this.status = I18n.format("gui.rsaudio.tartarus.radius_set", radius);
        this.statusColor = 0x90FF90;
    }

    private void send(byte action) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketSetTartarus(this.pos, action, this.radius));
        } catch (Throwable t) {
            RsAudio.warn("塔塔鲁斯设置发送失败（可能已断开连接）: " + t);
        }
    }

    private TileTartarus getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileTartarus ? (TileTartarus) te : null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.loaded) {
            readFromTile();
            return;
        }
        TileTartarus tile = getTile();
        if (tile == null) {
            return;
        }
        boolean changed = tile.getRadius() != this.radius || tile.isBuilt() != this.built
                || tile.wantsOpen() != this.open
                || Math.round(tile.getOpenAmount() * 100.0F) != this.progressPercent;
        if (changed) {
            readFromTile();
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.tartarus.title"), cx, 8, 0xFFFFFF);
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.tartarus.params", radius, TileTartarus.MIN_RADIUS, TileTartarus.MAX_RADIUS),
                cx, 20, 0xFFFF80);

        String state;
        if (!built) {
            state = I18n.format("gui.rsaudio.tartarus.unbuilt");
        } else if (open && progressPercent > 0 && progressPercent < 100) {
            state = I18n.format("gui.rsaudio.tartarus.moving", progressPercent);
        } else {
            state = I18n.format(open ? "gui.rsaudio.tartarus.open" : "gui.rsaudio.tartarus.closed");
        }
        drawCenteredString(this.fontRenderer, state, cx, 34, 0xC0C0FF);

        String line;
        int color;
        if (!this.status.isEmpty()) {
            line = this.status;
            color = this.statusColor;
        } else {
            TileTartarus tile = getTile();
            line = tile == null ? "" : tile.getStatus();
            color = 0xFFFFFF;
        }
        if (line == null) {
            line = "";
        }
        int lineY = 76;
        int drawn = 0;
        for (String piece : this.fontRenderer.listFormattedStringToWidth(line, this.width - 30)) {
            if (drawn >= 2) {
                break;
            }
            drawCenteredString(this.fontRenderer, piece, cx, lineY, color);
            lineY += 10;
            drawn++;
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

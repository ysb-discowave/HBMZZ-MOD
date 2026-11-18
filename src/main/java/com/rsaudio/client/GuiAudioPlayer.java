package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetBroadcast;
import com.rsaudio.network.PacketSetMode;
import com.rsaudio.network.PacketSetSound;
import com.rsaudio.tile.TileAudioPlayer;
import com.rsaudio.util.Broadcaster;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 音频播放器的选歌界面。
 * <p>
 * 音乐多的时候（几百首）靠翻页很难找，所以界面顶部带一个搜索框：
 * 点一下输入框就能输入关键词过滤，支持按显示名和内部名匹配。
 * 鼠标滚轮也能前后翻页。
 */
public class GuiAudioPlayer extends GuiScreen {

    private static final int ROW_HEIGHT = 12;
    private static final int LIST_WIDTH = 320;
    /** 搜索框宽度：右侧留出页面宽度给页码，省下一行高度给下面的播报行 */
    private static final int SEARCH_WIDTH = 180;
    private static final int SEARCH_TOP = 34;
    /** 模式按钮那一行 */
    private static final int MODE_TOP = 54;
    private static final int LIST_TOP = 78;
    private static final int MAX_ROWS = 16;

    private static final int ID_PREVIEW = 0;
    private static final int ID_CLEAR = 1;
    private static final int ID_RESCAN = 2;
    private static final int ID_PREV = 3;
    private static final int ID_CLEAR_SEARCH = 4;
    private static final int ID_NEXT = 5;
    /** 三种模式按钮用连续 id：ID_MODE_BASE + 模式序号 */
    private static final int ID_MODE_BASE = 6;
    private static final int ID_FINISH_TOGGLE = 20;
    private static final int ID_BROADCAST_COLOR = 21;
    /** 打开 reasim 调参界面 */
    private static final int ID_REASIM = 22;

    /** 广播那一行距窗口底部的距离（这一行在按钮行之上） */
    private static final int BROADCAST_BOTTOM = 74;
    /** 广播文字输入框的宽度（其余分给左侧标签和右侧颜色按钮） */
    private static final int COLOR_BUTTON_WIDTH = 90;
    private static final int BROADCAST_LABEL_WIDTH = 40;

    private final BlockPos pos;
    private int page = 0;
    private int rows = 10;
    private String current = "";
    private String status = "";

    /** 界面上显示的播放模式（点一下立刻发服务端，改不了也没关系，服务端会广播回真值） */
    private TileAudioPlayer.Mode mode = TileAudioPlayer.Mode.LOOP;
    private boolean outputOnFinish = false;

    /** 播放时播报到聊天栏的文字与颜色 */
    private String broadcast = "";
    private int broadcastColor = Broadcaster.DEFAULT_COLOR;

    private GuiTextField search;
    private GuiTextField broadcastInput;
    /** 过滤后的可见列表 */
    private List<AudioLibrary.Entry> view = new ArrayList<AudioLibrary.Entry>();

    public GuiAudioPlayer(BlockPos pos) {
        this.pos = pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        this.current = readCurrentSound();
        this.rows = computeRows();

        // 模式相关：从方块实体里读当前值
        TileAudioPlayer tile = getTile();
        this.mode = tile == null ? TileAudioPlayer.Mode.LOOP : tile.getMode();
        this.outputOnFinish = tile != null && tile.isOutputOnFinish();
        this.broadcast = tile == null ? "" : tile.getBroadcastText();
        this.broadcastColor = tile == null ? Broadcaster.DEFAULT_COLOR : tile.getBroadcastColor();

        int cx = this.width / 2;
        int bottom = this.height - 26;
        int left = listLeft();

        // 模式行：三个模式按钮 + 一个"播完输出红石信号"开关
        int modeW = 64;
        int modeGap = 4;
        int modeTotal = modeW * 3 + modeGap * 2;
        int toggleW = 108;
        int reasimW = 76;
        int rowWidth = modeTotal + 8 + toggleW + 8 + reasimW;
        int mx = cx - rowWidth / 2;
        TileAudioPlayer.Mode[] modes = TileAudioPlayer.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            this.buttonList.add(new GuiButton(ID_MODE_BASE + i, mx + i * (modeW + modeGap), MODE_TOP,
                    modeW, 20, modeLabel(modes[i])));
        }
        this.buttonList.add(new GuiButton(ID_FINISH_TOGGLE, mx + modeTotal + 8, MODE_TOP,
                toggleW, 20, finishToggleLabel()));
        // reasim 在模式行最右边：它和播放模式一样属于"这台机器怎么响"，
        // 放在同一行比塞进底部那排操作按钮更好找
        this.buttonList.add(new GuiButton(ID_REASIM, mx + modeTotal + 8 + toggleW + 8, MODE_TOP,
                reasimW, 20, reasimLabel()));

        // 播报行：左侧标签 + 中间输入框 + 右侧颜色按钮（点一下换一个颜色）
        int bcTop = this.height - BROADCAST_BOTTOM;
        int fieldX = left + BROADCAST_LABEL_WIDTH;
        int fieldW = LIST_WIDTH - BROADCAST_LABEL_WIDTH - 4 - COLOR_BUTTON_WIDTH;
        String previousBroadcast = this.broadcastInput == null
                ? this.broadcast : this.broadcastInput.getText();
        this.broadcastInput = new GuiTextField(101, this.fontRenderer, fieldX, bcTop + 1, fieldW, 16);
        this.broadcastInput.setMaxStringLength(Broadcaster.MAX_LENGTH);
        this.broadcastInput.setText(previousBroadcast);
        this.broadcastInput.setFocused(false);
        this.buttonList.add(new GuiButton(ID_BROADCAST_COLOR,
                left + LIST_WIDTH - COLOR_BUTTON_WIDTH, bcTop, COLOR_BUTTON_WIDTH, 18,
                colorLabel()));

        this.buttonList.add(new GuiButton(ID_PREVIEW, cx - 160, bottom, 100, 20, I18n.format("gui.rsaudio.preview")));
        this.buttonList.add(new GuiButton(ID_CLEAR, cx - 50, bottom, 100, 20, I18n.format("gui.rsaudio.clear")));
        this.buttonList.add(new GuiButton(ID_RESCAN, cx + 60, bottom, 100, 20, I18n.format("gui.rsaudio.rescan")));
        this.buttonList.add(new GuiButton(ID_PREV, cx - 160, bottom - 22, 100, 20, I18n.format("gui.rsaudio.prev")));
        this.buttonList.add(new GuiButton(ID_CLEAR_SEARCH, cx - 50, bottom - 22, 100, 20, I18n.format("gui.rsaudio.clear_search")));
        this.buttonList.add(new GuiButton(ID_NEXT, cx + 60, bottom - 22, 100, 20, I18n.format("gui.rsaudio.next")));

        // 搜索框右侧留出位置给页码，省下一行高度给播报行
        String previous = this.search == null ? "" : this.search.getText();
        this.search = new GuiTextField(100, this.fontRenderer, left, SEARCH_TOP, SEARCH_WIDTH, 16);
        this.search.setMaxStringLength(48);
        this.search.setText(previous);
        this.search.setFocused(false);

        applyFilter();
    }

    /** 颜色按钮上的文字：当前颜色的名字（颜色本身在 drawScreen 里画成色块） */
    private String colorLabel() {
        return I18n.format(colorKey(this.broadcastColor));
    }

    private static String colorKey(int ordinal) {
        int value = ordinal < 0 || ordinal >= Broadcaster.COLOR_COUNT ? Broadcaster.DEFAULT_COLOR : ordinal;
        return "gui.rsaudio.color." + value;
    }

    /** 当前模式的那个按钮前面加个箭头，一眼看出选中了哪个 */
    private String modeLabel(TileAudioPlayer.Mode value) {
        return (value == this.mode ? "▶ " : "   ") + I18n.format(value.langKey);
    }

    private String finishToggleLabel() {
        return I18n.format(this.outputOnFinish ? "gui.rsaudio.finish.on" : "gui.rsaudio.finish.off");
    }

    /** 关着的时候只写 REASIM，开着的时候多写一个"开"，一眼看得出这台机器被调过 */
    private String reasimLabel() {
        TileAudioPlayer tile = getTile();
        boolean on = tile != null && tile.getReasim().enabled;
        return on ? "REASIM: " + I18n.format("gui.rsaudio.on") : "REASIM";
    }

    private void refreshModeButtons() {
        for (GuiButton button : this.buttonList) {
            if (button.id >= ID_MODE_BASE && button.id < ID_MODE_BASE + TileAudioPlayer.Mode.values().length) {
                button.displayString = modeLabel(TileAudioPlayer.Mode.values()[button.id - ID_MODE_BASE]);
            } else if (button.id == ID_FINISH_TOGGLE) {
                button.displayString = finishToggleLabel();
            } else if (button.id == ID_BROADCAST_COLOR) {
                button.displayString = colorLabel();
            }
        }
    }

    private TileAudioPlayer getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileAudioPlayer ? (TileAudioPlayer) te : null;
    }

    /** 根据窗口高度算出能放几行（底部要给播报行 + 按钮两行 + 状态文字留位置） */
    private int computeRows() {
        int available = (this.height - 104) - LIST_TOP;
        return Math.max(3, Math.min(MAX_ROWS, available / ROW_HEIGHT));
    }

    private void applyFilter() {
        String query = this.search == null ? "" : this.search.getText().trim().toLowerCase(Locale.ROOT);
        this.view = new ArrayList<AudioLibrary.Entry>();
        for (AudioLibrary.Entry entry : AudioLibrary.getEntries()) {
            if (query.isEmpty()
                    || entry.displayName.toLowerCase(Locale.ROOT).contains(query)
                    || entry.soundName.contains(query)) {
                this.view.add(entry);
            }
        }
        this.page = 0;
        refreshPageButtons();
    }

    private void refreshPageButtons() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_PREV) {
                button.enabled = this.page > 0;
            } else if (button.id == ID_NEXT) {
                button.enabled = this.page + 1 < pageCount();
            }
        }
    }

    private int pageCount() {
        return Math.max(1, (this.view.size() + this.rows - 1) / this.rows);
    }

    private int listLeft() {
        return this.width / 2 - LIST_WIDTH / 2;
    }

    private int listBottom() {
        return LIST_TOP + this.rows * ROW_HEIGHT;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_PREVIEW: {
                AudioLibrary.Entry selected = findEntry(this.current);
                if (selected != null) {
                    // 试听也走 reasim：不然调完参数在主界面点试听，听到的又是干净的声音，
                    // 玩家会以为设置没生效
                    TileAudioPlayer tile = getTile();
                    ClientSoundPlayer.preview(selected.soundName,
                            tile == null ? null : tile.getReasim());
                    this.status = I18n.format("gui.rsaudio.playing", selected.displayName);
                } else {
                    this.status = I18n.format("gui.rsaudio.no_selection");
                }
                break;
            }
            case ID_REASIM:
                this.mc.displayGuiScreen(new GuiReasim(this.pos, this));
                break;
            case ID_CLEAR:
                select("");
                break;
            case ID_RESCAN:
                rescan();
                break;
            case ID_PREV:
                this.page = Math.max(0, this.page - 1);
                refreshPageButtons();
                break;
            case ID_CLEAR_SEARCH:
                if (this.search != null) {
                    this.search.setText("");
                    this.search.setFocused(false);
                }
                applyFilter();
                this.status = I18n.format("gui.rsaudio.found", this.view.size());
                break;
            case ID_NEXT:
                this.page = Math.min(pageCount() - 1, this.page + 1);
                refreshPageButtons();
                break;
            case ID_FINISH_TOGGLE:
                this.outputOnFinish = !this.outputOnFinish;
                sendMode();
                this.status = I18n.format(this.outputOnFinish
                        ? "gui.rsaudio.finish.on_hint" : "gui.rsaudio.finish.off_hint");
                refreshModeButtons();
                break;
            case ID_BROADCAST_COLOR:
                this.broadcastColor = Broadcaster.nextColor(this.broadcastColor);
                sendBroadcast();
                this.status = I18n.format("gui.rsaudio.broadcast.saved",
                        I18n.format(colorKey(this.broadcastColor)));
                refreshModeButtons();
                break;
            default:
                if (button.id >= ID_MODE_BASE && button.id < ID_MODE_BASE + TileAudioPlayer.Mode.values().length) {
                    this.mode = TileAudioPlayer.Mode.values()[button.id - ID_MODE_BASE];
                    sendMode();
                    this.status = I18n.format("gui.rsaudio.mode.set", I18n.format(this.mode.langKey));
                    refreshModeButtons();
                }
                break;
        }
    }

    private void sendMode() {
        PacketHandler.INSTANCE.sendToServer(
                new PacketSetMode(this.pos, this.mode.ordinal(), this.outputOnFinish));
    }

    /** 把播报文字与颜色发给服务端（回车、点颜色按钮、关界面时各发一次） */
    private void sendBroadcast() {
        if (this.broadcastInput != null) {
            this.broadcast = Broadcaster.sanitize(this.broadcastInput.getText());
        }
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(
                    new PacketSetBroadcast(this.pos, this.broadcast, this.broadcastColor));
        } catch (Throwable t) {
            // 断开连接时 onGuiClosed 也会被调用，这时发包可能失败；设置本身无所谓丢，别让它崩客户端
            RsAudio.warn("保存广播设置失败（可能已断开连接）: " + t);
        }
    }

    private void rescan() {
        try {
            AudioLibrary.rescan();
            boolean changed = DynamicSoundPack.generate();
            if (changed || !DynamicSoundPack.isEnabled()) {
                DynamicSoundPack.enableAndReload();
            }
            applyFilter();
            this.status = I18n.format("gui.rsaudio.found", this.view.size());
        } catch (Throwable t) {
            RsAudio.warn("重新扫描失败: " + t);
            this.status = I18n.format("gui.rsaudio.rescan_failed");
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        boolean inSearch = this.search != null && this.search.mouseClicked(mouseX, mouseY, mouseButton);
        boolean inBroadcast = this.broadcastInput != null
                && this.broadcastInput.mouseClicked(mouseX, mouseY, mouseButton);
        if (inSearch || inBroadcast) {
            // 两个输入框互斥：点了谁，另一个就失焦，免得键盘输入跑到别的框里
            this.search.setFocused(inSearch);
            this.broadcastInput.setFocused(inBroadcast);
            return;
        }
        this.search.setFocused(false);
        this.broadcastInput.setFocused(false);
        if (mouseButton != 0) {
            return;
        }
        int left = listLeft();
        for (int row = 0; row < this.rows; row++) {
            int index = this.page * this.rows + row;
            if (index >= this.view.size()) {
                break;
            }
            int top = LIST_TOP + row * ROW_HEIGHT;
            if (mouseX >= left && mouseX <= left + LIST_WIDTH && mouseY >= top && mouseY <= top + ROW_HEIGHT) {
                select(this.view.get(index).soundName);
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        // GuiTextField 会把 ESC 吞掉（只清选区不关界面），所以 ESC 先交给原版处理
        if (keyCode == Keyboard.KEY_ESCAPE) {
            super.keyTyped(typedChar, keyCode);
            return;
        }
        boolean searchFocused = this.search != null && this.search.isFocused();
        boolean broadcastFocused = this.broadcastInput != null && this.broadcastInput.isFocused();

        // Tab 在两个输入框之间来回切
        if (keyCode == Keyboard.KEY_TAB && (searchFocused || broadcastFocused)) {
            if (searchFocused) {
                this.search.setFocused(false);
                this.broadcastInput.setFocused(true);
            } else {
                sendBroadcast();
                this.broadcastInput.setFocused(false);
                this.search.setFocused(true);
            }
            return;
        }
        // 播报框里回车 = 保存
        if (broadcastFocused && (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER)) {
            sendBroadcast();
            this.status = I18n.format("gui.rsaudio.broadcast.saved",
                    I18n.format(colorKey(this.broadcastColor)));
            return;
        }
        // 只有输入框处于焦点时才会吃按键，否则 E 等还是原版行为
        if (searchFocused && this.search.textboxKeyTyped(typedChar, keyCode)) {
            applyFilter();
            return;
        }
        if (broadcastFocused && this.broadcastInput.textboxKeyTyped(typedChar, keyCode)) {
            this.broadcast = Broadcaster.sanitize(this.broadcastInput.getText());
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && pageCount() > 1) {
            if (wheel > 0) {
                this.page = Math.max(0, this.page - 1);
            } else {
                this.page = Math.min(pageCount() - 1, this.page + 1);
            }
            refreshPageButtons();
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.search != null) {
            this.search.updateCursorCounter();
        }
        if (this.broadcastInput != null) {
            this.broadcastInput.updateCursorCounter();
        }
    }

    private void select(String soundName) {
        PacketHandler.INSTANCE.sendToServer(new PacketSetSound(pos, soundName));
        this.current = soundName;
        this.status = soundName.isEmpty() ? I18n.format("gui.rsaudio.cleared_hint")
                : I18n.format("gui.rsaudio.selected", soundName);
    }

    private AudioLibrary.Entry findEntry(String soundName) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        }
        for (AudioLibrary.Entry entry : AudioLibrary.getEntries()) {
            if (entry.soundName.equals(soundName)) {
                return entry;
            }
        }
        return null;
    }

    private String readCurrentSound() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return "";
        }
        TileEntity te = world.getTileEntity(pos);
        if (te instanceof TileAudioPlayer) {
            return ((TileAudioPlayer) te).getSoundName();
        }
        return "";
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.title"), this.width / 2, 10, 0xFFFFFF);

        String currentText = this.current.isEmpty() ? I18n.format("gui.rsaudio.none")
                : I18n.format("gui.rsaudio.current", this.current);
        drawCenteredString(this.fontRenderer, currentText, this.width / 2, 22, 0xA0FFA0);

        int left = listLeft();
        this.search.drawTextBox();
        if (this.search.getText().isEmpty() && !this.search.isFocused()) {
            this.fontRenderer.drawString(I18n.format("gui.rsaudio.search"), left + 4, SEARCH_TOP + 4, 0x808080);
        }

        // 页码挪到搜索行右侧，把底下那一行让给"播报"输入。
        // 留了 trim 兜底：换语言/页数变多时也不会压到搜索框上。
        String pageText = I18n.format("gui.rsaudio.page", this.page + 1, Math.max(1, pageCount()),
                AudioLibrary.getEntries().size());
        pageText = this.fontRenderer.trimStringToWidth(pageText,
                Math.max(24, LIST_WIDTH - SEARCH_WIDTH - 8));
        this.fontRenderer.drawString(pageText,
                left + LIST_WIDTH - this.fontRenderer.getStringWidth(pageText), SEARCH_TOP + 4, 0xCCCCCC);

        int bottom = listBottom();
        drawRect(left - 2, LIST_TOP - 2, left + LIST_WIDTH + 2, bottom + 2, 0x80000000);

        if (this.view.isEmpty()) {
            String emptyKey = AudioLibrary.getEntries().isEmpty() ? "gui.rsaudio.empty" : "gui.rsaudio.no_match";
            drawCenteredString(this.fontRenderer, I18n.format(emptyKey), this.width / 2, LIST_TOP + 30, 0xFF8080);
            if (AudioLibrary.getEntries().isEmpty()) {
                if (AudioLibrary.getUnplayableCount() > 0) {
                    // 最常见的情况：玩家把 mp3 丢进来了。1.12.2 只认 ogg，必须说清楚。
                    drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.unplayable",
                                    String.valueOf(AudioLibrary.getUnplayableCount()),
                                    AudioLibrary.getUnplayableTypes()),
                            this.width / 2, LIST_TOP + 44, 0xFFB060);
                    drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.unplayable_hint"),
                            this.width / 2, LIST_TOP + 58, 0xAAAAAA);
                } else {
                    drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.empty_hint"),
                            this.width / 2, LIST_TOP + 44, 0xAAAAAA);
                }
            }
        } else {
            for (int row = 0; row < this.rows; row++) {
                int index = this.page * this.rows + row;
                if (index >= this.view.size()) {
                    break;
                }
                AudioLibrary.Entry entry = this.view.get(index);
                int top = LIST_TOP + row * ROW_HEIGHT;
                int textY = top + 2;
                boolean selected = entry.soundName.equals(this.current);
                boolean hovered = mouseX >= left && mouseX <= left + LIST_WIDTH
                        && mouseY >= top && mouseY <= top + ROW_HEIGHT;
                if (selected) {
                    drawRect(left, top, left + LIST_WIDTH, top + ROW_HEIGHT, 0x6040A040);
                } else if (hovered) {
                    drawRect(left, top, left + LIST_WIDTH, top + ROW_HEIGHT, 0x40FFFFFF);
                }
                String prefix = selected ? "▶ " : "   ";
                this.fontRenderer.drawStringWithShadow(prefix + entry.displayName, left + 4, textY,
                        selected ? 0x90FF90 : 0xFFFFFF);
            }
        }

        // 播报行：标签 + 输入框（右侧的颜色按钮由 super 一起画）
        int bcTop = this.height - BROADCAST_BOTTOM;
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.broadcast.label"),
                left, bcTop + 5, 0xE0E0E0);
        this.broadcastInput.drawTextBox();
        if (this.broadcastInput.getText().isEmpty() && !this.broadcastInput.isFocused()) {
            this.fontRenderer.drawString(I18n.format("gui.rsaudio.broadcast.hint"),
                    left + BROADCAST_LABEL_WIDTH + 4, bcTop + 5, 0x808080);
        }

        int statusY = this.height - 92;
        if (!this.status.isEmpty()) {
            drawCenteredString(this.fontRenderer, this.status, this.width / 2, statusY, 0xFFFF80);
        } else if (AudioLibrary.getUnplayableCount() > 0) {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.unplayable",
                            String.valueOf(AudioLibrary.getUnplayableCount()),
                            AudioLibrary.getUnplayableTypes()),
                    this.width / 2, statusY, 0xFFB060);
        } else {
            // 没别的要提示时，就把当前模式的行为写在下面，省得玩家猜
            drawCenteredString(this.fontRenderer, I18n.format(this.mode.descKey),
                    this.width / 2, statusY, 0xAAAAAA);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);

        // 色块必须在 super 之后画，否则会被按钮盖住
        drawColorSwatch();
    }

    /** 在颜色按钮右端画一个当前颜色的小方块，一眼看出选了哪个色 */
    private void drawColorSwatch() {
        for (GuiButton button : this.buttonList) {
            if (button.id != ID_BROADCAST_COLOR) {
                continue;
            }
            int size = 10;
            int swatchX = button.x + button.width - size - 4;
            int swatchY = button.y + (button.height - size) / 2;
            drawRect(swatchX - 1, swatchY - 1, swatchX + size + 1, swatchY + size + 1, 0xFF000000);
            int ordinal = this.broadcastColor < 0 || this.broadcastColor >= Broadcaster.COLOR_COUNT
                    ? Broadcaster.DEFAULT_COLOR : this.broadcastColor;
            drawRect(swatchX, swatchY, swatchX + size, swatchY + size,
                    0xFF000000 | Broadcaster.COLORS[ordinal]);
            return;
        }
    }

    @Override
    public void onGuiClosed() {
        // 关界面时把播报文字落盘，免得玩家敲完直接按 ESC 就丢了
        sendBroadcast();
        ClientSoundPlayer.stopPreview();
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

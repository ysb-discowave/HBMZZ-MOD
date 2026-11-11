package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetCamera;
import com.rsaudio.tile.TileMonitorCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 监控摄像头的设置界面：频道 / 视野 / 云台角度 / 启用 / 名字。
 *
 * <h3>朝向 = 放置时的面 + 这里的云台偏移</h3>
 * 贴哪个面在放置时就定死了（{@code FACING} = 你右击时点到的那个面），
 * 只给了 6 个离散方向，挂歪一点就可能什么都拍不到。所以界面上再给一组
 * <b>云台控制</b>：
 * <ul>
 *   <li><b>水平</b>（yaw 偏移，±180°）：绕竖轴左右转。对贴墙的摄像头就是"左右摇头"。</li>
 *   <li><b>俯仰</b>（pitch 偏移，±90°）：上下抬头 / 低头。</li>
 * </ul>
 * <p>
 * ★ 吸顶（朝下）和落地（朝上）的摄像头，<b>只有俯仰有用</b>：
 * 一个正朝下看的镜头绕着视线轴自转，画面完全不变。想把吸顶镜头掰成"斜着往下看"，
 * 得把俯仰拉到 -45° 这类值。界面上不解释这一点的话，玩家会以为水平键坏了。
 *
 * <h3>为什么角度是整数度</h3>
 * 走的是和频道/视野同一条 {@code PacketSetCamera}（{@code writeInt}）。
 * 用 float 传角度，服务端存 14.999998、客户端存 15.000001 这种往返误差会让
 * "值没变就不推送"的相等判断失效，每点一次都白推一次区块更新。整数度就没这问题。
 *
 * <h3>"云台归零"</h3>
 * 转到一半忘了原来的方向、或者把摄像头转晕了想回到"正对镜头面"，按一下复位。
 * 它只清两个偏移，不动频道 / 视野 / 名字 —— 所以可以放心按。
 */
public class GuiMonitorCamera extends GuiScreen {

    private static final int ID_CH_MINUS = 0;
    private static final int ID_CH_PLUS = 1;
    private static final int ID_FOV_MINUS = 2;
    private static final int ID_FOV_PLUS = 3;
    private static final int ID_ENABLED = 4;
    private static final int ID_DONE = 5;
    private static final int ID_YAW_MINUS = 6;
    private static final int ID_YAW_PLUS = 7;
    private static final int ID_PITCH_MINUS = 8;
    private static final int ID_PITCH_PLUS = 9;
    private static final int ID_RESET_GIMBAL = 10;

    /**
     * 四行控制键的竖直位置。行高 18、行距 20。
     * <p>
     * ★ 这几行是"上密下疏"预算里最容易被压坏的部分：底下还有名字输入框、
     * 提示行、状态行和按钮行，全都贴着 {@code this.height} 从下往上排。
     * 改这里的数<b>必须</b>回头核对 {@link #drawScreen} 里那几条 {@code height - n}
     * 的量，否则在 240 高的标准窗口下会叠字（算过：名字框底 142、提示 152、状态 170、
     * 按钮 188..208，各行都留了 10px 以上）。
     */
    private static final int ROW_Y = 34;
    private static final int ROW_STEP = 20;
    /** 名字框顶相对第四行顶端的下移量 */
    private static final int LABEL_GAP = 10;

    private final BlockPos pos;
    private int channel = TileMonitorCamera.DEFAULT_CHANNEL;
    private int fov = TileMonitorCamera.FOV_DEFAULT;
    private int yaw = TileMonitorCamera.YAW_DEFAULT;
    private int pitch = TileMonitorCamera.PITCH_DEFAULT;
    private boolean enabled = true;
    private GuiTextField labelField;
    private boolean loaded;

    public GuiMonitorCamera(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();

        int cx = this.width / 2;
        int rowW = 22;

        int y1 = ROW_Y;
        this.buttonList.add(new GuiButton(ID_CH_MINUS, cx - 60, y1, rowW, 18, "◀"));
        this.buttonList.add(new GuiButton(ID_CH_PLUS, cx + 38, y1, rowW, 18, "▶"));

        int y2 = ROW_Y + ROW_STEP;
        this.buttonList.add(new GuiButton(ID_FOV_MINUS, cx - 60, y2, rowW, 18, "-"));
        this.buttonList.add(new GuiButton(ID_FOV_PLUS, cx + 38, y2, rowW, 18, "+"));

        int y3 = ROW_Y + ROW_STEP * 2;
        this.buttonList.add(new GuiButton(ID_YAW_MINUS, cx - 60, y3, rowW, 18, "◀"));
        this.buttonList.add(new GuiButton(ID_YAW_PLUS, cx + 38, y3, rowW, 18, "▶"));

        int y4 = ROW_Y + ROW_STEP * 3;
        this.buttonList.add(new GuiButton(ID_PITCH_MINUS, cx - 60, y4, rowW, 18, "▲"));
        this.buttonList.add(new GuiButton(ID_PITCH_PLUS, cx + 38, y4, rowW, 18, "▼"));

        int labelY = ROW_Y + ROW_STEP * 4 + LABEL_GAP;
        this.labelField = new GuiTextField(0, this.fontRenderer, cx - 90, labelY, 180, 18);
        this.labelField.setMaxStringLength(TileMonitorCamera.LABEL_MAX_LENGTH);
        this.labelField.setText(getTile() == null ? "" : getTile().getLabel());
        this.labelField.setFocused(false);

        // ★ 底行三个按钮的宽度按窗口宽反算：窗口被拉窄时（玩家可以随便拉大小）
        //   固定 3×112 会直接捅出屏幕右边。18 = 左右各留 8 的边距 + 一点余量。
        int gap = 8;
        int botW = Math.min(112, (this.width - 18 - gap * 2) / 3);
        if (botW < 40) {
            botW = 40;
        }
        int botTotal = botW * 3 + gap * 2;
        int x2 = cx - botTotal / 2;
        int y2b = this.height - 52;
        this.buttonList.add(new GuiButton(ID_RESET_GIMBAL, x2, y2b, botW, 20,
                I18n.format("gui.rsaudio.camera.reset_gimbal")));
        this.buttonList.add(new GuiButton(ID_ENABLED, x2 + botW + gap, y2b, botW, 20, enabledLabel()));
        this.buttonList.add(new GuiButton(ID_DONE, x2 + (botW + gap) * 2, y2b, botW, 20,
                I18n.format("gui.rsaudio.monitor.done")));
    }

    private String enabledLabel() {
        return I18n.format(enabled ? "gui.rsaudio.camera.enabled_on" : "gui.rsaudio.camera.enabled_off");
    }

    private void readFromTile() {
        TileMonitorCamera tile = getTile();
        if (tile != null) {
            this.channel = tile.getChannel();
            this.fov = tile.getFov();
            this.yaw = tile.getYawOffset();
            this.pitch = tile.getPitchOffset();
            this.enabled = tile.isEnabled();
            this.loaded = true;
        }
    }

    private TileMonitorCamera getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileMonitorCamera ? (TileMonitorCamera) te : null;
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_CH_MINUS:
                apply(this.channel - 1, this.fov, this.yaw, this.pitch, this.enabled);
                break;
            case ID_CH_PLUS:
                apply(this.channel + 1, this.fov, this.yaw, this.pitch, this.enabled);
                break;
            case ID_FOV_MINUS:
                apply(this.channel, this.fov - 5, this.yaw, this.pitch, this.enabled);
                break;
            case ID_FOV_PLUS:
                apply(this.channel, this.fov + 5, this.yaw, this.pitch, this.enabled);
                break;
            case ID_YAW_MINUS:
                apply(this.channel, this.fov, this.yaw - TileMonitorCamera.YAW_STEP, this.pitch, this.enabled);
                break;
            case ID_YAW_PLUS:
                apply(this.channel, this.fov, this.yaw + TileMonitorCamera.YAW_STEP, this.pitch, this.enabled);
                break;
            case ID_PITCH_MINUS:
                apply(this.channel, this.fov, this.yaw, this.pitch - TileMonitorCamera.PITCH_STEP, this.enabled);
                break;
            case ID_PITCH_PLUS:
                apply(this.channel, this.fov, this.yaw, this.pitch + TileMonitorCamera.PITCH_STEP, this.enabled);
                break;
            case ID_RESET_GIMBAL:
                apply(this.channel, this.fov, TileMonitorCamera.YAW_DEFAULT, TileMonitorCamera.PITCH_DEFAULT,
                        this.enabled);
                break;
            case ID_ENABLED:
                apply(this.channel, this.fov, this.yaw, this.pitch, !this.enabled);
                refreshEnabledButton();
                break;
            case ID_DONE:
                // 名字是自由文本，离开界面时统一提交一次，免得每敲一个字发一个包
                send(this.channel, this.fov, this.yaw, this.pitch, this.enabled, this.labelField.getText());
                this.mc.displayGuiScreen(null);
                break;
            default:
                break;
        }
    }

    private void apply(int newChannel, int newFov, int newYaw, int newPitch, boolean newEnabled) {
        this.channel = TileMonitorCamera.clampChannel(newChannel);
        this.fov = TileMonitorCamera.clampFov(newFov);
        // ★ 本地也走一遍 clamp：不然界面上显示的度数和真正存下去的不一样
        //   （例如一路按到 195°，方块实体存的是 -165°，界面却还写着 195°）
        this.yaw = TileMonitorCamera.clampYaw(newYaw);
        this.pitch = TileMonitorCamera.clampPitch(newPitch);
        this.enabled = newEnabled;
        send(this.channel, this.fov, this.yaw, this.pitch, this.enabled,
                this.labelField == null ? "" : this.labelField.getText());
    }

    private void refreshEnabledButton() {
        for (GuiButton button : this.buttonList) {
            if (button.id == ID_ENABLED) {
                button.displayString = enabledLabel();
                return;
            }
        }
    }

    private void send(int channel, int fov, int yaw, int pitch, boolean enabled, String label) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(
                    new PacketSetCamera(this.pos, channel, fov, yaw, pitch, enabled, label));
        } catch (Throwable t) {
            RsAudio.warn("监控摄像头设置发送失败（可能已断开连接）: " + t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (this.labelField != null && this.labelField.isFocused()) {
            // Esc 让输入框先失焦，再按一次才关界面 —— 否则想取消输入就直接退出了
            if (keyCode == 1) {
                this.labelField.setFocused(false);
                return;
            }
            this.labelField.textboxKeyTyped(typedChar, keyCode);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (this.labelField != null) {
            this.labelField.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (this.labelField != null) {
            this.labelField.updateCursorCounter();
        }
        if (!this.loaded) {
            TileMonitorCamera tile = getTile();
            if (tile != null) {
                readFromTile();
                refreshEnabledButton();
            }
        }
    }

    @Override
    public void onGuiClosed() {
        // 关界面（含按 Esc 直接关）也把名字存下来，不然玩家敲完名字按 Esc 就白敲了
        if (this.labelField != null) {
            send(this.channel, this.fov, this.yaw, this.pitch, this.enabled, this.labelField.getText());
        }
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.camera.title"), cx, 10, 0xFFFFFF);

        // 坐标 + 朝向（基础朝向不可改，但要让玩家知道自己在改哪一颗）
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.camera.where", this.pos.getX(), this.pos.getY(), this.pos.getZ(),
                        facingName()),
                cx, 22, 0x909090);

        // 四行：数值画在两个按钮中间（按钮在 cx±60..cx±38，中间正好空出 76px）
        int y1 = ROW_Y;
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.camera.channel_value", this.channel), cx, y1 + 5, 0xFFFF80);
        int y2 = ROW_Y + ROW_STEP;
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.camera.fov_value", this.fov), cx, y2 + 5, 0xFFFF80);
        int y3 = ROW_Y + ROW_STEP * 2;
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.camera.yaw_value", signed(this.yaw)), cx, y3 + 5, 0x80FFFF);
        int y4 = ROW_Y + ROW_STEP * 3;
        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.camera.pitch_value", signed(this.pitch)), cx, y4 + 5, 0x80FFFF);

        int labelY = ROW_Y + ROW_STEP * 4 + LABEL_GAP;
        this.fontRenderer.drawStringWithShadow(I18n.format("gui.rsaudio.camera.label_hint"),
                cx - 90, labelY - 10, 0xA0C0FF);
        if (this.labelField != null) {
            this.labelField.drawTextBox();
        }

        // ★ 竖直方向的预算（按钮占 height-52 .. height-32）：提示行必须让开这段，
        //   否则"点一下停用"那行字会压在按钮上。四行控制键 + 名字框到 labelY+18 为止，
        //   和这里的 height-88 之间留了余量，标准 240 高的窗口下不会叠。
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.camera.hint"),
                cx, this.height - 88, 0xAAAAAA);

        String state = I18n.format(enabled ? "gui.rsaudio.camera.state_on" : "gui.rsaudio.camera.state_off");
        drawCenteredString(this.fontRenderer, state, cx, this.height - 70,
                enabled ? 0x90FF90 : 0x909090);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    /**
     * 带正负号的整数字面量。
     * <p>
     * ★ 不走语言文件里的 {@code %+d}：语言文件里的参数替换在客户端是
     * {@code String.format} 那条路，但服务端/原版的另一套替换是正则实现的，
     * 认的只有 {@code %s} / {@code %d} 这些朴素形式，{@code %+d} 会原样漏出来。
     * 在 Java 侧拼好再交给 {@code %s}，两边都不会错。
     */
    private static String signed(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }

    /** 朝向的可读文本（"朝北"这种，取语言文件） */
    private String facingName() {
        net.minecraft.util.EnumFacing facing = net.minecraft.util.EnumFacing.NORTH;
        World world = Minecraft.getMinecraft().world;
        if (world != null) {
            net.minecraft.block.state.IBlockState state = world.getBlockState(this.pos);
            if (state.getBlock() instanceof com.rsaudio.block.BlockMonitorCamera) {
                facing = state.getValue(com.rsaudio.block.BlockMonitorCamera.FACING);
            }
        }
        String key = "gui.rsaudio.camera.facing_" + facing.getName();
        return I18n.format(key);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

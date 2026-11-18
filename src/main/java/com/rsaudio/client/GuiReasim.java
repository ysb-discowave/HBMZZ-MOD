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

package com.rsaudio.client;

import com.rsaudio.audio.ReasimParams;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetReasim;
import com.rsaudio.tile.TileAudioPlayer;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.IOException;

/**
 * reasim 调参界面。
 * <p>
 * 单独一屏而不是塞进播放器主界面：主界面已经被"列表 + 模式 + 播报"占满了，
 * 再挤进去只会让两边都不好用。这里是"把机器调成一只破喇叭"的地方，进来就是为了调参。
 * <p>
 * <b>开关逻辑在这里是怎么体现的：</b>
 * <ul>
 *   <li>顶部那个 {@code REASIM: 开/关} 按钮就是总开关。关掉之后所有滑条变灰
 *       （不是隐藏）—— 玩家需要看见"我上次调到了多少"，回来时不用重调。</li>
 *   <li>关掉时<b>不重开</b>正在播放的歌：客户端只是把整条 DSP 旁路掉，
 *       声音立刻变回原样。重开会"咔"一下，那是不必要的噪声。</li>
 *   <li>"自动回声"打开时回声滑条变灰并显示环境算出来的值 —— 它仍然可以点，
 *       点一下就是关掉自动、把这个值接管成手动值。</li>
 * </ul>
 */
@SideOnly(Side.CLIENT)
public class GuiReasim extends GuiScreen {

    private static final int ID_ENABLE = 0;
    private static final int ID_VOLUME = 1;
    private static final int ID_ECHO = 2;
    private static final int ID_AUTO = 3;
    private static final int ID_DISTORTION = 4;
    private static final int ID_HISS = 5;
    private static final int ID_STATIC = 6;
    private static final int ID_PREVIEW = 7;
    private static final int ID_DONE = 8;

    private static final int PANEL_WIDTH = 320;
    private static final int ROW_HEIGHT = 24;

    private final BlockPos pos;
    private final GuiScreen parent;
    private final ReasimParams params;

    private GuiSlider volume;
    private GuiSlider echo;
    private GuiSlider distortion;
    private GuiSlider hiss;
    private GuiSlider noise;
    private GuiButton enableButton;
    private GuiButton autoButton;

    /** 有改动还没发出去 */
    private boolean dirty;
    /** 发包含"拖动结束"的防抖：拖动时每帧都发没有意义，服务端只关心松手后的值 */
    private int debounce;
    /** 自动回声的刷新计时（世界里读方块不便宜） */
    private int echoTimer;
    private float autoEcho = 0.30F;

    public GuiReasim(BlockPos pos, GuiScreen parent) {
        this.pos = pos;
        this.parent = parent;
        TileAudioPlayer tile = getTile();
        this.params = tile != null ? tile.getReasim() : new ReasimParams();
    }

    private TileAudioPlayer getTile() {
        // 构造期 this.mc 还没被设上（GuiScreen 是在 displayGuiScreen 里才挂到 Minecraft 上的），
        // 所以这里直接问单例，不能读 this.mc
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getMinecraft();
        if (minecraft.world == null) {
            return null;
        }
        TileEntity tile = minecraft.world.getTileEntity(this.pos);
        return tile instanceof TileAudioPlayer ? (TileAudioPlayer) tile : null;
    }

    @Override
    public void initGui() {
        this.buttonList.clear();
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int top = this.height / 2 - 76;

        this.enableButton = new GuiButton(ID_ENABLE, left, top, 150, 20, enableLabel());
        this.autoButton = new GuiButton(ID_AUTO, left + 158, top + ROW_HEIGHT, 150, 20, autoLabel());
        this.buttonList.add(this.enableButton);
        this.buttonList.add(this.autoButton);

        this.volume = new GuiSlider(ID_VOLUME, left, top + ROW_HEIGHT,
                PANEL_WIDTH, 20, I18n.format("gui.rsaudio.reasim.volume"), normalized(this.params.volume,
                ReasimParams.VOLUME_MIN, ReasimParams.VOLUME_MAX)) {
            @Override
            protected void onValueChanged() {
                params.volume = ReasimParams.VOLUME_MIN
                        + getValue() * (ReasimParams.VOLUME_MAX - ReasimParams.VOLUME_MIN);
                markDirty();
            }
        };
        this.buttonList.add(this.volume);

        this.echo = new GuiSlider(ID_ECHO, left, top + ROW_HEIGHT * 2,
                PANEL_WIDTH - 158, 20, I18n.format("gui.rsaudio.reasim.echo"), this.params.echo) {
            @Override
            protected void onValueChanged() {
                params.echo = getValue();
                markDirty();
            }
        };
        this.buttonList.add(this.echo);

        this.distortion = new GuiSlider(ID_DISTORTION, left, top + ROW_HEIGHT * 3,
                PANEL_WIDTH, 20, I18n.format("gui.rsaudio.reasim.distortion"), this.params.distortion) {
            @Override
            protected void onValueChanged() {
                params.distortion = getValue();
                markDirty();
            }
        };
        this.buttonList.add(this.distortion);

        this.hiss = new GuiSlider(ID_HISS, left, top + ROW_HEIGHT * 4,
                PANEL_WIDTH, 20, I18n.format("gui.rsaudio.reasim.hiss"), this.params.hiss) {
            @Override
            protected void onValueChanged() {
                params.hiss = getValue();
                markDirty();
            }
        };
        this.buttonList.add(this.hiss);

        this.noise = new GuiSlider(ID_STATIC, left, top + ROW_HEIGHT * 5,
                PANEL_WIDTH, 20, I18n.format("gui.rsaudio.reasim.static_noise"), this.params.staticNoise) {
            @Override
            protected void onValueChanged() {
                params.staticNoise = getValue();
                markDirty();
            }
        };
        this.buttonList.add(this.noise);

        this.buttonList.add(new GuiButton(ID_PREVIEW, left, top + ROW_HEIGHT * 6 + 6, 150, 20,
                I18n.format("gui.rsaudio.reasim.preview")));
        this.buttonList.add(new GuiButton(ID_DONE, left + 158, top + ROW_HEIGHT * 6 + 6, 150, 20,
                I18n.format("gui.rsaudio.reasim.done")));

        this.echoTimer = 0;
        refreshEnabledState();
    }

    // ------------------------------------------------------------------ 状态

    private String enableLabel() {
        return I18n.format("gui.rsaudio.reasim.enable") + ": "
                + I18n.format(this.params.enabled ? "gui.rsaudio.on" : "gui.rsaudio.off");
    }

    private String autoLabel() {
        return I18n.format("gui.rsaudio.reasim.auto") + ": "
                + I18n.format(this.params.echoAuto ? "gui.rsaudio.on" : "gui.rsaudio.off");
    }

    /** 总开关关掉时滑条变灰但保留位置；自动回声开着时回声滑条让位 */
    private void refreshEnabledState() {
        boolean on = this.params.enabled;
        this.volume.enabled = on;
        this.distortion.enabled = on;
        this.hiss.enabled = on;
        this.noise.enabled = on;
        this.echo.enabled = on && !this.params.echoAuto;
        this.autoButton.enabled = on;
        this.enableButton.displayString = enableLabel();
        this.autoButton.displayString = autoLabel();
    }

    private void markDirty() {
        this.dirty = true;
        this.debounce = 2;
    }

    private void send() {
        this.dirty = false;
        PacketHandler.INSTANCE.sendToServer(new PacketSetReasim(this.pos, this.params));
    }

    private static float normalized(float value, float min, float max) {
        if (max <= min) {
            return 0.0F;
        }
        float t = (value - min) / (max - min);
        return t < 0.0F ? 0.0F : (t > 1.0F ? 1.0F : t);
    }

    // ------------------------------------------------------------------ 事件

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_ENABLE:
                this.params.enabled = !this.params.enabled;
                refreshEnabledState();
                markDirty();
                break;
            case ID_AUTO:
                this.params.echoAuto = !this.params.echoAuto;
                if (!this.params.echoAuto) {
                    // 从自动切回手动时，把刚才量到的值接管过来，
                    // 否则滑条会突然跳回一个很久以前的手动值，听起来像"关了自动反而变了"
                    this.params.echo = this.autoEcho;
                    this.echo.setValue(this.autoEcho);
                }
                refreshEnabledState();
                markDirty();
                break;
            case ID_ECHO:
                // 点/拖自动模式下的回声滑条 = 接管：关掉自动，用这个值
                if (this.params.echoAuto) {
                    this.params.echoAuto = false;
                    refreshEnabledState();
                    markDirty();
                }
                break;
            case ID_PREVIEW:
                previewCurrent();
                break;
            case ID_DONE:
            default:
                ClientSoundPlayer.stopPreview();
                this.mc.displayGuiScreen(this.parent);
                break;
        }
    }

    private void previewCurrent() {
        String soundName = null;
        TileAudioPlayer tile = getTile();
        if (tile != null) {
            soundName = tile.getSoundName();
        }
        if (soundName == null || soundName.isEmpty()) {
            // 还没选曲目时就放列表里的第一首 —— 试听按钮不该因为"没选"而什么都不发生
            if (!AudioLibrary.getEntries().isEmpty()) {
                soundName = AudioLibrary.getEntries().get(0).soundName;
            }
        }
        if (soundName == null || soundName.isEmpty()) {
            return;
        }
        // 试听走的是"当前界面上的值"，不必等它同步到服务端再回来
        ClientSoundPlayer.preview(soundName, this.params.copy());
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        // 自动回声：每 20 tick 重新量一次房间，量完直接显示在滑条上
        if (--this.echoTimer <= 0) {
            this.echoTimer = 20;
            if (this.mc.world != null) {
                this.autoEcho = ReasimEnvironment.autoEcho(this.mc.world, this.pos);
            }
        }
        if (this.params.echoAuto) {
            this.echo.setValueSilently(this.autoEcho);
        }
        boolean dragging = isDragging();
        if (this.dirty && !dragging && --this.debounce <= 0) {
            send();
        }
    }

    private boolean isDragging() {
        return this.volume.isDragging() || this.echo.isDragging() || this.distortion.isDragging()
                || this.hiss.isDragging() || this.noise.isDragging();
    }

    @Override
    public void onGuiClosed() {
        // 关掉界面时把最后的状态提交掉，别让玩家调了半天白调
        if (this.dirty) {
            send();
        }
        ClientSoundPlayer.stopPreview();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int left = this.width / 2 - PANEL_WIDTH / 2;
        int top = this.height / 2 - 76;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.reasim.title"),
                this.width / 2, top - 14, 0xFFFFFF);

        // 自动模式下把"量出来的房间有多闷"写出来，玩家才知道这个数是怎么来的
        if (this.params.enabled && this.params.echoAuto) {
            String hint = I18n.format("gui.rsaudio.reasim.auto_hint", Math.round(this.autoEcho * 100.0F));
            this.fontRenderer.drawString(hint, left + PANEL_WIDTH - 150 + 4,
                    top + ROW_HEIGHT * 2 + 6, 0xA0C0FF);
        }
        if (!this.params.enabled) {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.reasim.off_hint"),
                    this.width / 2, top + ROW_HEIGHT * 7 + 14, 0x909090);
        } else if (!ReasimBridge.available()) {
            drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.reasim.unavailable"),
                    this.width / 2, top + ROW_HEIGHT * 7 + 14, 0xFF8080);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}

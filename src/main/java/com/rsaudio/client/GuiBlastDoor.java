package com.rsaudio.client;

import com.rsaudio.RsAudio;
import com.rsaudio.access.AccessCore;
import com.rsaudio.block.BlockAlarmLight;
import com.rsaudio.block.BlockBlastDoor;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSetDoor;
import com.rsaudio.tile.TileBlastDoor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;

/**
 * 防核门设置界面：<b>手持木斧 + 潜行右键</b>打开。
 *
 * <h3>为什么用木斧开</h3>
 * 门的<b>本地开关门也是木斧</b>（木斧右键 = 开/关门，2026-09-25 起空手右键不再动门）。
 * 所以界面和开关门挂在同一个操作上，不会互相抢：木斧不潜行 = 开关门，木斧潜行 = 这个界面。
 * <p>
 * ★ 这两条现在都由方块自己处理（{@code BlockBlastDoor#onBlockActivated}），
 * 结构魔杖那边对门一律早退 —— 见 {@code WandHandler} 里"防核门必须整块早退"那条。
 *
 * <h3>参数是"整体覆盖"的</h3>
 * 每次点按钮都把<b>整套参数</b>（尺寸/朝向/收进/反向 + 频道/联锁/循环/重型/分级间隔）
 * 发过去，而不是"这次改了哪一项"。界面上的值就是玩家的意图，
 * 整体覆盖不会出现"服务端以为宽是 3、界面显示 5"这种偏差。
 *
 * <h3>改尺寸要先拆门，改门禁不用</h3>
 * 尺寸和收进方向决定门板几何，改了会让旧门板变成没人认领的孤儿，
 * 所以服务端会先拆掉并提示重新「建造」。门禁参数不改几何，就地生效
 * （见 {@code TileBlastDoor#applyParams}）—— 界面上的按钮也就分成了两组，
 * 上面几行是"立刻生效"，最下面那行里的建造/拆除是"要重新建"。
 *
 * <h3>三件 QSERF 机构的入口都在这里</h3>
 * <ul>
 *   <li><b>FAAS 远程锁定门</b>：右下角那颗「封锁 / 解除封锁」。</li>
 *   <li><b>气闸</b>：把两道面对面放的门设成<b>同一个频道</b>、都打开「联锁」，
 *       再调一段循环时间。之后它们就互斥了 —— 一扇开着另一扇开不了，
 *       一扇关上之后另一扇还要等循环时间。</li>
 *   <li><b>塔塔鲁斯大门</b>：打开「重型」，门就变成逐行升起。</li>
 * </ul>
 */
public class GuiBlastDoor extends GuiScreen {

    private static final int ID_W_MINUS = 0;
    private static final int ID_W_PLUS = 1;
    private static final int ID_H_MINUS = 2;
    private static final int ID_H_PLUS = 3;
    private static final int ID_ROTATE = 4;
    private static final int ID_RETRACT = 5;
    private static final int ID_INVERT = 6;
    private static final int ID_BUILD = 7;
    private static final int ID_REMOVE = 8;
    private static final int ID_OPEN = 9;
    private static final int ID_CLOSE = 10;
    private static final int ID_DONE = 11;
    private static final int ID_CH_MINUS = 12;
    private static final int ID_CH_PLUS = 13;
    private static final int ID_INTERLOCK = 14;
    private static final int ID_HEAVY = 15;
    private static final int ID_CYCLE_MINUS = 16;
    private static final int ID_CYCLE_PLUS = 17;
    private static final int ID_STAGE_MINUS = 18;
    private static final int ID_STAGE_PLUS = 19;
    private static final int ID_LOCK = 20;
    private static final int ID_ALARM_TOGGLE = 21;
    private static final int ID_ALARM_COLOR = 22;

    /** 循环时间按一下调多少刻（5 刻 = 0.25 秒） */
    private static final int CYCLE_STEP = 5;

    /** 俯视顺时针，和 {@code EnumFacing#getHorizontalIndex} 的顺序一致 */
    private static final EnumFacing[] HORIZONTALS = {
            EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST,
    };

    private final BlockPos pos;
    private int doorWidth = TileBlastDoor.DEFAULT_WIDTH;
    private int doorHeight = TileBlastDoor.DEFAULT_HEIGHT;
    private boolean retractUp;
    private boolean invert;
    private int channel = AccessCore.CHANNEL_DEFAULT;
    private boolean interlock;
    private int cycle = AccessCore.CYCLE_DEFAULT;
    private boolean heavy;
    private int stageDelay = AccessCore.STAGE_DEFAULT;
    private boolean alarmLights = true;
    private int alarmColor = 0;

    private boolean built;
    private boolean open;
    private boolean latched;
    private int blockReason = AccessCore.REASON_NONE;
    private int cycleRemaining;
    private int progressPercent;
    private EnumFacing facing = EnumFacing.NORTH;
    private String status = "";
    private int statusColor = 0xFFFFFF;
    private boolean loaded;

    // 五行按钮的排版（initGui 里算好，drawScreen 也要用）
    private int rowTop;
    private int rowGap;
    private int btnW;

    public GuiBlastDoor(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    // ------------------------------------------------------------------ 初始化

    @Override
    public void initGui() {
        this.buttonList.clear();
        readFromTile();
        readFacing();

        int cx = this.width / 2;
        // 最宽的一行有 6 个按钮。宽度按它自适应，窄窗口也不会溢出。
        int widest = 6;
        this.btnW = Math.min(96, Math.max(30, (this.width - 16 - 4 * (widest - 1)) / widest));
        int gap = 4;

        int bottomRowY = this.height - 32;
        this.rowGap = 24;
        this.rowTop = bottomRowY - this.rowGap * 5;
        // ★ 窗口不够高时压缩行距（信息行占到 y=94，所以顶部不能早于 102）。
        //   压不动就让它盖住提示行 —— 按钮点不到比少显示一行提示严重得多。
        if (this.rowTop < 102) {
            this.rowGap = Math.max(18, (bottomRowY - 102) / 5);
            this.rowTop = bottomRowY - this.rowGap * 5;
        }

        // 第 0 行：宽 / 高
        int y0 = this.rowTop;
        int x0 = cx - (btnW * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_W_MINUS, x0, y0, btnW, 20,
                I18n.format("gui.rsaudio.door.width_minus")));
        this.buttonList.add(new GuiButton(ID_W_PLUS, x0 + (btnW + gap), y0, btnW, 20,
                I18n.format("gui.rsaudio.door.width_plus")));
        this.buttonList.add(new GuiButton(ID_H_MINUS, x0 + (btnW + gap) * 2, y0, btnW, 20,
                I18n.format("gui.rsaudio.door.height_minus")));
        this.buttonList.add(new GuiButton(ID_H_PLUS, x0 + (btnW + gap) * 3, y0, btnW, 20,
                I18n.format("gui.rsaudio.door.height_plus")));

        // 第 1 行：朝向 / 收进方向 / 反向
        int y1 = this.rowTop + this.rowGap;
        int x1 = cx - (btnW * 3 + gap * 2) / 2;
        this.buttonList.add(new GuiButton(ID_ROTATE, x1, y1, btnW, 20, rotateLabel()));
        this.buttonList.add(new GuiButton(ID_RETRACT, x1 + (btnW + gap), y1, btnW, 20, retractLabel()));
        this.buttonList.add(new GuiButton(ID_INVERT, x1 + (btnW + gap) * 2, y1, btnW, 20, invertLabel()));

        // 第 2 行：频道 / 联锁 / 重型
        int y2 = this.rowTop + this.rowGap * 2;
        int x2 = cx - (btnW * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_CH_MINUS, x2, y2, btnW, 20,
                I18n.format("gui.rsaudio.door.channel_minus")));
        this.buttonList.add(new GuiButton(ID_CH_PLUS, x2 + (btnW + gap), y2, btnW, 20,
                I18n.format("gui.rsaudio.door.channel_plus")));
        this.buttonList.add(new GuiButton(ID_INTERLOCK, x2 + (btnW + gap) * 2, y2, btnW, 20, interlockLabel()));
        this.buttonList.add(new GuiButton(ID_HEAVY, x2 + (btnW + gap) * 3, y2, btnW, 20, heavyLabel()));

        // 第 3 行：循环时间 / 分级间隔
        int y3 = this.rowTop + this.rowGap * 3;
        int x3 = cx - (btnW * 4 + gap * 3) / 2;
        this.buttonList.add(new GuiButton(ID_CYCLE_MINUS, x3, y3, btnW, 20,
                I18n.format("gui.rsaudio.door.cycle_minus")));
        this.buttonList.add(new GuiButton(ID_CYCLE_PLUS, x3 + (btnW + gap), y3, btnW, 20,
                I18n.format("gui.rsaudio.door.cycle_plus")));
        this.buttonList.add(new GuiButton(ID_STAGE_MINUS, x3 + (btnW + gap) * 2, y3, btnW, 20,
                I18n.format("gui.rsaudio.door.stage_minus")));
        this.buttonList.add(new GuiButton(ID_STAGE_PLUS, x3 + (btnW + gap) * 3, y3, btnW, 20,
                I18n.format("gui.rsaudio.door.stage_plus")));

        // 第 4 行：建造 / 拆除 / 开门 / 关门 / 封锁 / 完成
        int y4 = this.rowTop + this.rowGap * 4;
        int x4 = cx - (btnW * 6 + gap * 5) / 2;
        this.buttonList.add(new GuiButton(ID_BUILD, x4, y4, btnW, 20,
                I18n.format("gui.rsaudio.door.build")));
        this.buttonList.add(new GuiButton(ID_REMOVE, x4 + (btnW + gap), y4, btnW, 20,
                I18n.format("gui.rsaudio.door.remove")));
        this.buttonList.add(new GuiButton(ID_OPEN, x4 + (btnW + gap) * 2, y4, btnW, 20,
                I18n.format("gui.rsaudio.door.open")));
        this.buttonList.add(new GuiButton(ID_CLOSE, x4 + (btnW + gap) * 3, y4, btnW, 20,
                I18n.format("gui.rsaudio.door.close")));
        this.buttonList.add(new GuiButton(ID_LOCK, x4 + (btnW + gap) * 4, y4, btnW, 20, lockLabel()));
        this.buttonList.add(new GuiButton(ID_DONE, x4 + (btnW + gap) * 5, y4, btnW, 20,
                I18n.format("gui.rsaudio.door.done")));

        // 第 5 行：警报灯开关 / 颜色（旋转警报灯）
        int y5 = this.rowTop + this.rowGap * 5;
        int x5 = cx - (btnW * 2 + gap) / 2;
        this.buttonList.add(new GuiButton(ID_ALARM_TOGGLE, x5, y5, btnW, 20, alarmToggleLabel()));
        this.buttonList.add(new GuiButton(ID_ALARM_COLOR, x5 + (btnW + gap), y5, btnW, 20, alarmColorLabel()));
    }

    private void readFromTile() {
        TileBlastDoor tile = getTile();
        if (tile == null) {
            return;
        }
        this.doorWidth = tile.getWidth();
        this.doorHeight = tile.getHeight();
        this.retractUp = tile.isRetractUp();
        this.invert = tile.isInvert();
        this.channel = tile.getChannel();
        this.interlock = tile.isInterlock();
        this.cycle = tile.getCycleTicks();
        this.heavy = tile.isHeavy();
        this.stageDelay = tile.getStageDelay();
        this.built = tile.isBuilt();
        this.open = tile.wantsOpen();
        this.latched = tile.isLatched();
        this.blockReason = tile.getBlockReason();
        this.cycleRemaining = tile.getCycleRemaining();
        this.progressPercent = tile.getAccess().getProgressPercent(tile.getHeight());
        this.alarmLights = tile.isAlarmLights();
        this.alarmColor = tile.getAlarmColor();
        this.loaded = true;
    }

    private void readFacing() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return;
        }
        net.minecraft.block.state.IBlockState state = world.getBlockState(this.pos);
        if (state.getBlock() instanceof BlockBlastDoor) {
            this.facing = state.getValue(BlockBlastDoor.FACING);
        }
    }

    // ------------------------------------------------------------------ 按钮文字

    private String rotateLabel() {
        return I18n.format("gui.rsaudio.door.facing." + facing.getName());
    }

    private String retractLabel() {
        return I18n.format(retractUp ? "gui.rsaudio.door.retract_up"
                : "gui.rsaudio.door.retract_down");
    }

    private String invertLabel() {
        return I18n.format(invert ? "gui.rsaudio.door.invert_on" : "gui.rsaudio.door.invert_off");
    }

    private String interlockLabel() {
        return I18n.format(interlock ? "gui.rsaudio.door.interlock_on" : "gui.rsaudio.door.interlock_off");
    }

    private String heavyLabel() {
        return I18n.format(heavy ? "gui.rsaudio.door.heavy_on" : "gui.rsaudio.door.heavy_off");
    }

    private String lockLabel() {
        return I18n.format(latched ? "gui.rsaudio.door.unlock" : "gui.rsaudio.door.lock");
    }

    private String channelText() {
        return channel == AccessCore.CHANNEL_NONE
                ? I18n.format("gui.rsaudio.door.channel_none")
                : I18n.format("gui.rsaudio.door.channel_value", channel);
    }

    private String alarmToggleLabel() {
        return I18n.format(alarmLights ? "gui.rsaudio.door.alarm_on" : "gui.rsaudio.door.alarm_off");
    }

    private String alarmColorLabel() {
        String name = I18n.format("rsaudio.alarm." + BlockAlarmLight.COLOR_NAMES[alarmColor] + ".name");
        return I18n.format("gui.rsaudio.door.alarm_color", name);
    }

    private void refreshLabels() {
        for (GuiButton button : this.buttonList) {
            switch (button.id) {
                case ID_ROTATE:
                    button.displayString = rotateLabel();
                    break;
                case ID_RETRACT:
                    button.displayString = retractLabel();
                    break;
                case ID_INVERT:
                    button.displayString = invertLabel();
                    break;
                case ID_INTERLOCK:
                    button.displayString = interlockLabel();
                    break;
                case ID_HEAVY:
                    button.displayString = heavyLabel();
                    break;
                case ID_LOCK:
                    button.displayString = lockLabel();
                    break;
                case ID_ALARM_TOGGLE:
                    button.displayString = alarmToggleLabel();
                    break;
                case ID_ALARM_COLOR:
                    button.displayString = alarmColorLabel();
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ 交互

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        switch (button.id) {
            case ID_W_MINUS:
                this.doorWidth = TileBlastDoor.clampSize(this.doorWidth - 1);
                apply();
                break;
            case ID_W_PLUS:
                this.doorWidth = TileBlastDoor.clampSize(this.doorWidth + 1);
                apply();
                break;
            case ID_H_MINUS:
                this.doorHeight = TileBlastDoor.clampSize(this.doorHeight - 1);
                apply();
                break;
            case ID_H_PLUS:
                this.doorHeight = TileBlastDoor.clampSize(this.doorHeight + 1);
                apply();
                break;
            case ID_ROTATE:
                this.facing = HORIZONTALS[(this.facing.getHorizontalIndex() + 1) % HORIZONTALS.length];
                apply();
                break;
            case ID_RETRACT:
                this.retractUp = !this.retractUp;
                apply();
                break;
            case ID_INVERT:
                this.invert = !this.invert;
                apply();
                break;
            case ID_CH_MINUS:
                this.channel = AccessCore.stepChannel(this.channel, -1);
                apply();
                break;
            case ID_CH_PLUS:
                this.channel = AccessCore.stepChannel(this.channel, +1);
                apply();
                break;
            case ID_INTERLOCK:
                this.interlock = !this.interlock;
                apply();
                break;
            case ID_HEAVY:
                this.heavy = !this.heavy;
                apply();
                break;
            case ID_CYCLE_MINUS:
                this.cycle = AccessCore.clampCycle(this.cycle - CYCLE_STEP);
                apply();
                break;
            case ID_CYCLE_PLUS:
                this.cycle = AccessCore.clampCycle(this.cycle + CYCLE_STEP);
                apply();
                break;
            case ID_STAGE_MINUS:
                this.stageDelay = AccessCore.clampStage(this.stageDelay - 1);
                apply();
                break;
            case ID_STAGE_PLUS:
                this.stageDelay = AccessCore.clampStage(this.stageDelay + 1);
                apply();
                break;
            case ID_LOCK:
                // 门闩由服务端定夺（可能有别的来源正在锁着），所以这里只发请求，
                // 真正的 latched 值等下一次同步回来。本地先乐观翻转一下，界面才不会顿。
                this.latched = !this.latched;
                send(PacketSetDoor.TOGGLE_LOCK);
                refreshLabels();
                break;
            case ID_BUILD:
                send(PacketSetDoor.BUILD);
                this.status = I18n.format("gui.rsaudio.door.build_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_REMOVE:
                send(PacketSetDoor.REMOVE);
                this.built = false;
                this.open = false;
                this.status = I18n.format("gui.rsaudio.door.remove_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_OPEN:
                send(PacketSetDoor.OPEN);
                this.status = I18n.format("gui.rsaudio.door.open_hint");
                this.statusColor = 0x90FF90;
                break;
            case ID_CLOSE:
                send(PacketSetDoor.CLOSE);
                this.status = I18n.format("gui.rsaudio.door.close_hint");
                this.statusColor = 0xFFFF80;
                break;
            case ID_DONE:
                this.mc.displayGuiScreen(null);
                break;
            case ID_ALARM_TOGGLE:
                this.alarmLights = !this.alarmLights;
                apply();
                break;
            case ID_ALARM_COLOR:
                this.alarmColor = (this.alarmColor + 1) % BlockAlarmLight.COLOR_COUNT;
                apply();
                break;
            default:
                break;
        }
    }

    /** 把当前整套参数发给服务端（服务端会同时处理"改尺寸/朝向要先拆旧门"这件事） */
    private void apply() {
        send(PacketSetDoor.APPLY);
        refreshLabels();
        this.status = I18n.format("gui.rsaudio.door.applied", describe());
        this.statusColor = 0x90FF90;
    }

    private void send(byte action) {
        if (Minecraft.getMinecraft().world == null) {
            return;
        }
        try {
            PacketHandler.INSTANCE.sendToServer(new PacketSetDoor(this.pos, action,
                    this.doorWidth, this.doorHeight, (byte) this.facing.getHorizontalIndex(),
                    this.retractUp, this.invert, this.channel, this.interlock,
                    this.cycle, this.heavy, this.stageDelay, this.alarmLights, this.alarmColor));
        } catch (Throwable t) {
            RsAudio.warn("防核门设置发送失败（可能已断开连接）: " + t);
        }
    }

    private TileBlastDoor getTile() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(this.pos);
        return te instanceof TileBlastDoor ? (TileBlastDoor) te : null;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (!this.loaded) {
            readFromTile();
            readFacing();
            refreshLabels();
            return;
        }
        // 服务端那边状态变了（别人开门、红石开门、气闸循环在跑）就跟着更新显示
        TileBlastDoor tile = getTile();
        if (tile == null) {
            return;
        }
        boolean changed = tile.getWidth() != this.doorWidth || tile.getHeight() != this.doorHeight
                || tile.isRetractUp() != this.retractUp || tile.isInvert() != this.invert
                || tile.getChannel() != this.channel || tile.isInterlock() != this.interlock
                || tile.getCycleTicks() != this.cycle || tile.isHeavy() != this.heavy
                || tile.getStageDelay() != this.stageDelay
                || tile.isBuilt() != this.built || tile.wantsOpen() != this.open
                || tile.isLatched() != this.latched
                || tile.getBlockReason() != this.blockReason
                || tile.getCycleRemaining() != this.cycleRemaining
                || tile.getAccess().getProgressPercent(tile.getHeight()) != this.progressPercent
                || tile.isAlarmLights() != this.alarmLights
                || tile.getAlarmColor() != this.alarmColor;
        if (changed) {
            readFromTile();
            readFacing();
            refreshLabels();
        }
    }

    // ------------------------------------------------------------------ 绘制

    /** 供界面显示：3×3 · 已建 · 已开 · 频道3 · 已封锁 */
    private String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(doorWidth).append('×').append(doorHeight);
        sb.append(I18n.format(built ? "gui.rsaudio.door.state_built" : "gui.rsaudio.door.state_unbuilt"));
        if (built) {
            if (open && progressPercent > 0 && progressPercent < 100) {
                sb.append(I18n.format("gui.rsaudio.door.state_moving", progressPercent));
            } else {
                sb.append(I18n.format(open ? "gui.rsaudio.door.state_open" : "gui.rsaudio.door.state_closed"));
            }
        }
        if (channel != AccessCore.CHANNEL_NONE) {
            sb.append(I18n.format("gui.rsaudio.door.state_channel", channel));
        }
        if (interlock) {
            sb.append(I18n.format("gui.rsaudio.door.state_interlock"));
        }
        if (heavy) {
            sb.append(I18n.format("gui.rsaudio.door.state_heavy"));
        }
        if (latched) {
            sb.append(I18n.format("gui.rsaudio.door.state_locked"));
        }
        if (invert) {
            sb.append(I18n.format("gui.rsaudio.door.state_invert"));
        }
        return sb.toString();
    }

    /** 门禁那一行的文字 + 配色 */
    private String[] accessLine() {
        switch (blockReason) {
            case AccessCore.REASON_MANUAL:
                return new String[] { I18n.format("gui.rsaudio.door.access_manual"), "0xFF6060" };
            case AccessCore.REASON_REMOTE:
                return new String[] { I18n.format("gui.rsaudio.door.access_remote"), "0xFF6060" };
            case AccessCore.REASON_CYCLE:
                return new String[] { I18n.format("gui.rsaudio.door.access_cycle", cycleRemaining), "0xFFD040" };
            case AccessCore.REASON_INTERLOCK:
                return new String[] { I18n.format("gui.rsaudio.door.access_interlock"), "0xFFD040" };
            default:
                if (!built) {
                    return new String[] { I18n.format("gui.rsaudio.door.access_unbuilt"), "0xAAAAAA" };
                }
                return new String[] { I18n.format("gui.rsaudio.door.access_free"), "0x90FF90" };
        }
    }

    private static int colorOf(String hex) {
        // 颜色字符串形如 "0xAAAAAA"（带 0x 前缀），Integer.decode 原生支持
        // 0x / 0X / # 前缀；Long.parseLong(hex, 16) 不会剥离前缀会抛 NumberFormatException。
        return Integer.decode(hex);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        int cx = this.width / 2;
        drawCenteredString(this.fontRenderer, I18n.format("gui.rsaudio.door.title"), cx, 8, 0xFFFFFF);
        drawCenteredString(this.fontRenderer, describe(), cx, 20, 0xFFFF80);

        String[] access = accessLine();
        drawCenteredString(this.fontRenderer, access[0], cx, 34, colorOf(access[1]));

        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.door.params", channelText(),
                        I18n.format(interlock ? "gui.rsaudio.door.on" : "gui.rsaudio.door.off"),
                        I18n.format(heavy ? "gui.rsaudio.door.on" : "gui.rsaudio.door.off"),
                        cycle, stageDelay),
                cx, 46, 0xC0C0FF);

        drawCenteredString(this.fontRenderer,
                I18n.format("gui.rsaudio.door.facing_line",
                        I18n.format("gui.rsaudio.door.facing." + facing.getName()),
                        I18n.format(retractUp ? "gui.rsaudio.door.retract_up" : "gui.rsaudio.door.retract_down"),
                        TileBlastDoor.MIN_SIZE, TileBlastDoor.MAX_SIZE),
                cx, 60, 0xE0E0E0);

        String tileStatus = getTile() == null ? "" : getTile().getStatus();
        String line;
        int color;
        if (!this.status.isEmpty()) {
            line = this.status;
            color = this.statusColor;
        } else if (tileStatus != null && !tileStatus.isEmpty()) {
            line = tileStatus;
            color = 0xFFFFFF;
        } else {
            line = I18n.format("gui.rsaudio.door.hint");
            color = 0xAAAAAA;
        }
        // ★ 最多两行：上面的按钮框从 rowTop 开始（标准 240 高的窗口下是 112），
        //   这两行从 76 起、每行 10 —— 到 96 结束，留出安全距离。
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

package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.audio.ReasimParams;
import com.rsaudio.block.BlockAudioPlayer;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketPlaySound;
import com.rsaudio.util.Broadcaster;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.NetworkRegistry;

/**
 * 音频播放器方块实体。
 * <p>
 * 保存选中的音频名 + 播放模式，并检测红石信号变化来播放/停止。
 * 真正的播放发生在客户端（{@code ClientSoundPlayer}），服务端只负责"决定该不该播"。
 * <p>
 * 三种模式：
 * <ul>
 *     <li>{@link Mode#PULSE} 脉冲模式：上升沿触发一次播放，<b>不循环</b>；中途掉信号不会停，
 *     只有整首放完才停。</li>
 *     <li>{@link Mode#LOOP} 循环模式：有信号就循环播放，信号消失立刻停。</li>
 *     <li>{@link Mode#TOGGLE} 脉冲循环模式：每来一次上升沿翻转一次（开始循环播放 / 停止）。</li>
 * </ul>
 * 另外可选"播放完输出红石信号"：脉冲模式下整首播完时，输出一段短红石脉冲。
 */
public class TileAudioPlayer extends TileEntity implements ITickable, IBroadcastHolder {

    /** 播放模式 */
    public enum Mode {
        /** 脉冲：收到一次信号播放一遍，放完才停 */
        PULSE("gui.rsaudio.mode.pulse", "gui.rsaudio.mode.pulse.desc"),
        /** 循环：有信号就循环，没信号就停 */
        LOOP("gui.rsaudio.mode.loop", "gui.rsaudio.mode.loop.desc"),
        /** 脉冲循环：每收到一次信号翻转一次 */
        TOGGLE("gui.rsaudio.mode.toggle", "gui.rsaudio.mode.toggle.desc");

        public final String langKey;
        public final String descKey;

        Mode(String langKey, String descKey) {
            this.langKey = langKey;
            this.descKey = descKey;
        }

        public static Mode byOrdinal(int ordinal) {
            Mode[] values = values();
            if (ordinal < 0 || ordinal >= values.length) {
                return LOOP;
            }
            return values[ordinal];
        }
    }

    /** 广播范围（方块） */
    private static final double RANGE = 128.0D;

    /** "播放完输出红石信号"的脉冲长度（刻）；实际逻辑在 {@link RedstoneTrigger} */
    public static final int FINISH_PULSE_TICKS = RedstoneTrigger.FINISH_PULSE_TICKS;

    /**
     * 脉冲模式的兜底上限：正常情况下客户端放完会回报一声，这里只是防呆。
     * 如果方块附近一个玩家都没有，就没人回报"放完了"，锁存会一直挂着，
     * 之后再来信号也放不了，所以超时后强制解锁（30 分钟，正常歌曲远达不到）。
     */
    private static final long MAX_PULSE_LATCH_TICKS = 20L * 60L * 30L;

    private String soundName = "";
    private Mode mode = Mode.LOOP;
    private boolean outputOnFinish = false;
    /** 播放开始时播报到聊天栏的文字（空 = 不播报） */
    private String broadcast = "";
    /** 播报文字的颜色（原版 TextFormatting 的 ordinal） */
    private int broadcastColor = Broadcaster.DEFAULT_COLOR;

    /**
     * reasim（扬声器真实度模拟）参数。
     * <p>
     * 存在方块上而不是客户端本地：音色属于这台机器，不属于某个听的人。
     * 多人服务器里所有人听到的是同一种破喇叭，重进世界也还在。
     */
    private final ReasimParams reasim = new ReasimParams();

    /** 服务端的逻辑播放状态（真正的播放在客户端） */
    private boolean playing = false;
    private boolean initialized = false;
    /** 本次播放开始的游戏时间（用于兜底解锁） */
    private long playStartTick = 0L;
    /** 播完输出的红石脉冲剩余刻数 */
    private int pulseTicks = 0;
    /**
     * 红石输入的上升/下降沿判定 + "自激保护"（见 {@link RedstoneTrigger} 的类注释：
     * 播完的脉冲会经相邻红石线绕回来被自己当成新信号，导致无限循环）。
     */
    private final RedstoneTrigger trigger = new RedstoneTrigger();

    public String getSoundName() {
        return soundName;
    }

    public boolean hasSound() {
        return soundName != null && !soundName.isEmpty();
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isOutputOnFinish() {
        return outputOnFinish;
    }

    public boolean isPlaying() {
        return playing;
    }

    // ------------------------------------------------------------------ 聊天栏播报

    @Override
    public String getBroadcastText() {
        return broadcast;
    }

    @Override
    public int getBroadcastColor() {
        return broadcastColor;
    }

    /** 服务端设置播报文字与颜色（存档 + 同步给周围客户端） */
    @Override
    public void setBroadcast(String text, int color) {
        String value = Broadcaster.sanitize(text);
        int colorValue = color;
        if (value.equals(this.broadcast) && colorValue == this.broadcastColor) {
            return;
        }
        this.broadcast = value;
        this.broadcastColor = colorValue;
        markDirty();
        if (world != null && !world.isRemote) {
            syncToClient();
        }
    }

    /** 客户端收到同步包时使用：只改值，不产生任何回发 */
    @Override
    public void applySyncedBroadcast(String text, int color) {
        this.broadcast = Broadcaster.sanitize(text);
        this.broadcastColor = color;
    }

    public void setSoundName(String name) {
        String value = name == null ? "" : name;
        if (value.equals(this.soundName)) {
            return;
        }
        this.soundName = value;
        markDirty();
        if (world != null && !world.isRemote) {
            if (!hasSound()) {
                stopPlayback();
            } else if (mode == Mode.LOOP && world.isBlockPowered(pos)) {
                // 循环模式下通电时换歌：立刻换成新的一首
                startPlayback();
            } else if (playing) {
                // 正在播着的时候换歌：从新的一首重新开始
                startPlayback();
            }
            syncToClient();
        }
    }

    /** 切换播放模式（服务端调用） */
    public void setMode(Mode value) {
        if (value == null || value == this.mode) {
            return;
        }
        this.mode = value;
        markDirty();
        if (world != null && !world.isRemote) {
            // 换模式时按新规则对齐一次，避免出现"界面显示循环但实际停了"这种不一致
            switch (this.mode) {
                case LOOP:
                    if (world.isBlockPowered(pos) && hasSound()) {
                        startPlayback();
                    } else {
                        stopPlayback();
                    }
                    break;
                case PULSE:
                case TOGGLE:
                default:
                    // 这两种模式都是"等下一次上升沿"，所以先停掉
                    stopPlayback();
                    break;
            }
            syncToClient();
        }
    }

    /** 设置"播放完是否输出红石信号"（服务端调用） */
    public void setOutputOnFinish(boolean value) {
        if (value == this.outputOnFinish) {
            return;
        }
        this.outputOnFinish = value;
        markDirty();
        if (world != null && !world.isRemote) {
            syncToClient();
        }
    }

    /** 客户端收到同步包时使用：只改值，不产生任何回发 */
    public void applySyncedMode(Mode value, boolean finishOutput) {
        this.mode = value == null ? Mode.LOOP : value;
        this.outputOnFinish = finishOutput;
    }

    // ------------------------------------------------------------------ reasim

    /** 取一份快照（调用方改它不会影响方块） */
    public ReasimParams getReasim() {
        return this.reasim.copy();
    }

    /**
     * 设置 reasim 参数（服务端调用）。
     * <p>
     * <b>正在播的时候改参数不会重开音乐</b>：播放是客户端按方块实体同步下来的值
     * 实时重配 DSP 的（见 {@code ClientSoundPlayer.tick}），所以拧滑条听起来是
     * "音色变了"，而不是"咔一下从头再来"。
     */
    public void setReasim(ReasimParams value) {
        if (value == null) {
            return;
        }
        ReasimParams next = value.copy().clamp();
        if (next.sameAs(this.reasim)) {
            return;
        }
        copyInto(this.reasim, next);
        markDirty();
        if (world != null && !world.isRemote) {
            syncToClient();
        }
    }

    /** 客户端从同步标签读到参数时使用：只改值 */
    public void applySyncedReasim(ReasimParams value) {
        if (value != null) {
            copyInto(this.reasim, value.copy().clamp());
        }
    }

    private static void copyInto(ReasimParams target, ReasimParams source) {
        target.enabled = source.enabled;
        target.volume = source.volume;
        target.echo = source.echo;
        target.echoAuto = source.echoAuto;
        target.distortion = source.distortion;
        target.hiss = source.hiss;
        target.staticNoise = source.staticNoise;
    }

    private void writeReasim(NBTTagCompound tag) {
        tag.setBoolean("ReOn", reasim.enabled);
        tag.setBoolean("ReAuto", reasim.echoAuto);
        tag.setFloat("ReVol", reasim.volume);
        tag.setFloat("ReEcho", reasim.echo);
        tag.setFloat("ReDist", reasim.distortion);
        tag.setFloat("ReHiss", reasim.hiss);
        tag.setFloat("ReStat", reasim.staticNoise);
    }

    private void readReasim(NBTTagCompound tag) {
        // 没有这些键 = 2.8.0 之前的存档，保持默认（关闭）即可
        if (!tag.hasKey("ReOn")) {
            return;
        }
        ReasimParams next = new ReasimParams();
        next.enabled = tag.getBoolean("ReOn");
        next.echoAuto = tag.getBoolean("ReAuto");
        next.volume = tag.getFloat("ReVol");
        next.echo = tag.getFloat("ReEcho");
        next.distortion = tag.getFloat("ReDist");
        next.hiss = tag.getFloat("ReHiss");
        next.staticNoise = tag.getFloat("ReStat");
        copyInto(this.reasim, next.clamp());
    }

    /** 方块被破坏 / 被替换时，通知所有客户端把音乐停掉 */
    public void broadcastStop() {
        if (world != null && !world.isRemote) {
            sendPacket(new PacketPlaySound(pos, "", true, false, null));
        }
    }

    // ------------------------------------------------------------------ 播放控制

    /**
     * 这次播放该不该循环 —— 直接由模式推导，不再让调用方传参。
     * <p>
     * ★ 这里踩过坑：以前是 {@code startPlayback(boolean loop)} 由各处调用方自己传，
     * 结果循环模式的分支传成了 {@code false}，表现就是"循环模式根本不循环，只放一遍"。
     * 循环与否本来就只取决于模式（脉冲放一遍，循环/脉冲循环都是循环），
     * 所以收进这里算，调用方再也传不错。
     */
    private boolean shouldLoop() {
        return mode != Mode.PULSE;
    }

    private void startPlayback() {
        if (!hasSound()) {
            return;
        }
        boolean loop = shouldLoop();
        playing = true;
        playStartTick = world == null ? 0L : world.getTotalWorldTime();
        updateActiveState(true);
        sendPacket(new PacketPlaySound(pos, soundName, false, loop, reasim.copy()));
        // 播报只在"一次播放开始"时发一次。循环模式的循环是声音引擎在放，
        // 中途不会回到这里，所以循环播放只会显示一次。
        Broadcaster.send(world, pos, broadcast, broadcastColor);
    }

    private void stopPlayback() {
        if (!playing) {
            return;
        }
        playing = false;
        updateActiveState(false);
        sendPacket(new PacketPlaySound(pos, "", true, false, null));
    }

    /**
     * 客户端播放完了（或者根本没放出来）时调用。
     * 多台客户端会各发一次，这里只认第一次，重复的忽略。
     */
    public void onPlaybackFinished() {
        if (world == null || world.isRemote || !playing) {
            return;
        }
        playing = false;
        updateActiveState(false);
        if (outputOnFinish) {
            triggerFinishPulse();
        }
    }

    private void triggerFinishPulse() {
        pulseTicks = FINISH_PULSE_TICKS;
        setPulseState(true);
        // ★ 同时进入"自激保护"：这段时间里不处理任何输入。
        //   否则这个脉冲会经相邻红石线绕回来，被自己当成新信号 → 放完立刻又播（死循环）。
        trigger.armSelfTriggerGuard();
    }

    @Override
    public void onChunkUnload() {
        super.onChunkUnload();
        // 客户端这边区块卸载后声音会一直响，主动停掉
        if (world != null && world.isRemote) {
            RsAudio.proxy.stopClientSound(pos);
        }
    }

    // ------------------------------------------------------------------ 红石

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }

        if (pulseTicks > 0) {
            pulseTicks--;
            if (pulseTicks == 0) {
                setPulseState(false);
            }
        }

        boolean powered = world.isBlockPowered(pos);

        if (!initialized) {
            // 刚加载/刚放置：只对齐外观，不触发播放，避免读档就响。
            // 同时把"播完脉冲"清干净 —— 这个脉冲是运行时状态，读档时不该残留，
            // 否则方块状态里可能留着 pulse=true，一直对外供电。
            initialized = true;
            trigger.reset(powered);
            playing = false;
            pulseTicks = 0;
            updateActiveState(false);
            setPulseState(false);
            return;
        }

        // ★ 上升/下降沿判定 + 自激保护都在这里（纯逻辑，可脱离 MC 单测）。
        //   自激保护的成因见 RedstoneTrigger 的类注释。
        RedstoneTrigger.Result edge = trigger.step(powered);
        if (edge == RedstoneTrigger.Result.GUARDED) {
            // 保护期内不处理输入；trigger 内部已经同步了 lastPowered，
            // 所以保护结束时残留的回音不会变成假上升沿。
            return;
        }
        boolean rising = edge == RedstoneTrigger.Result.RISING;
        boolean falling = edge == RedstoneTrigger.Result.FALLING;

        // 脉冲模式兜底解锁（详见 MAX_PULSE_LATCH_TICKS 的说明）
        if (mode == Mode.PULSE && playing
                && world.getTotalWorldTime() - playStartTick > MAX_PULSE_LATCH_TICKS) {
            RsAudio.warn("音频播放器 %s 的播放结束回报超时，已强制解锁", pos);
            stopPlayback();
        }

        switch (mode) {
            case LOOP:
                if (falling && playing) {
                    stopPlayback();
                } else if (rising && !playing) {
                    startPlayback();
                }
                break;
            case PULSE:
                // 只在上升沿开播；掉信号不管它，等客户端报告放完
                if (rising && !playing) {
                    startPlayback();
                }
                break;
            case TOGGLE:
                if (rising) {
                    if (playing) {
                        stopPlayback();
                    } else {
                        startPlayback();
                    }
                }
                break;
            default:
                break;
        }
    }

    private void sendPacket(PacketPlaySound packet) {
        PacketHandler.INSTANCE.sendToAllAround(packet, new NetworkRegistry.TargetPoint(
                world.provider.getDimension(), pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, RANGE));
    }

    private void updateActiveState(boolean active) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockAudioPlayer && state.getValue(BlockAudioPlayer.ACTIVE) != active) {
            world.setBlockState(pos, state.withProperty(BlockAudioPlayer.ACTIVE, active), 3);
        }
    }

    private void setPulseState(boolean value) {
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockAudioPlayer && state.getValue(BlockAudioPlayer.PULSE) != value) {
            world.setBlockState(pos, state.withProperty(BlockAudioPlayer.PULSE, value), 3);
        }
    }

    /** 把方块实体的数据推给周围客户端（界面要读模式） */
    private void syncToClient() {
        IBlockState state = world.getBlockState(pos);
        world.notifyBlockUpdate(pos, state, state, 3);
    }

    // ------------------------------------------------------------------ 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.soundName = compound.getString("Sound");
        this.mode = Mode.byOrdinal(compound.getByte("Mode"));
        this.outputOnFinish = compound.getBoolean("FinishOut");
        this.broadcast = compound.getString("Bcast");
        this.broadcastColor = compound.hasKey("BcastColor")
                ? compound.getByte("BcastColor") : Broadcaster.DEFAULT_COLOR;
        readReasim(compound);
        this.initialized = false;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setString("Sound", soundName);
        compound.setByte("Mode", (byte) mode.ordinal());
        compound.setBoolean("FinishOut", outputOnFinish);
        compound.setString("Bcast", broadcast);
        compound.setByte("BcastColor", (byte) broadcastColor);
        writeReasim(compound);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setString("Sound", soundName);
        tag.setByte("Mode", (byte) mode.ordinal());
        tag.setBoolean("FinishOut", outputOnFinish);
        tag.setString("Bcast", broadcast);
        tag.setByte("BcastColor", (byte) broadcastColor);
        writeReasim(tag);
        return tag;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.soundName = tag.getString("Sound");
        this.mode = Mode.byOrdinal(tag.getByte("Mode"));
        this.outputOnFinish = tag.getBoolean("FinishOut");
        this.broadcast = tag.getString("Bcast");
        if (tag.hasKey("BcastColor")) {
            this.broadcastColor = tag.getByte("BcastColor");
        }
        readReasim(tag);
    }

    @Override
    public boolean shouldRefresh(net.minecraft.world.World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

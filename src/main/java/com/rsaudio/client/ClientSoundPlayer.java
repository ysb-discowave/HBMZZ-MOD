package com.rsaudio.client;

import com.rsaudio.Config;
import com.rsaudio.RsAudio;
import com.rsaudio.audio.ReasimParams;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketSoundFinished;
import com.rsaudio.tile.TileAudioPlayer;
import com.rsaudio.util.OggDuration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.tileentity.TileEntity;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 客户端声音播放：把某个方块位置绑定的音频放出来 / 停掉。
 * <p>
 * 1.12.2 里 {@code SoundEvent.REGISTRY} 只登记原版音效，模组/资源包的音效由
 * {@code SoundHandler} 从 sounds.json 里按名字查找（{@code getAccessor}），
 * 所以这里直接 new 一个 SoundEvent 即可，是否存在用 accessor 判断。
 * <p>
 * 关于"放完了"这件事：MC 没有给我们"播放结束"的回调，所以这里自己算。
 * 时长直接从 ogg 文件里读（{@link OggDuration}：只扫 Ogg 页头里的 granule，不解码音频），
 * 到点后再确认一次声音真的停了，然后回服务端一条 {@link PacketSoundFinished}。
 * 循环模式则相反：引擎的 repeat 负责无缝循环，这个计时器只做兜底重播
 * （万一某些情况下流式音频不会自动循环，也能一直放下去）。
 */
public class ClientSoundPlayer {

    /** 到点后确认"是否真的没在播"的轮询间隔（刻） */
    private static final int POLL_TICKS = 5;
    /** 循环模式下连续查到多少次"没在播"才认定真的停了（避免把引擎自己的循环接缝误判成结束） */
    private static final int LOOP_MISSES = 2;
    /** 单次播放：超出估算时长这么多刻还没停就不再等了（兜底，防止卡住） */
    private static final int ONESHOT_GRACE_TICKS = 400;
    /** 时长解析不出来时，第一次检查前先等多久 */
    private static final int UNKNOWN_FIRST_CHECK = 20;
    /** 绝对上限，任何会话都不可能超过这个时长（1 小时） */
    private static final int ABSOLUTE_MAX_TICKS = 20 * 60 * 60;

    /** 当前真正在播的声音对象，用来问引擎"还在放吗" */
    private static final Map<BlockPos, ISound> PLAYING = new HashMap<BlockPos, ISound>();
    /** 正在跟踪的播放会话（负责判断"放完了"、循环模式下重播） */
    private static final Map<BlockPos, Session> SESSIONS = new HashMap<BlockPos, Session>();

    private static ISound preview;
    /** 带效果的试听走 reasim，这条会话不挂在任何方块上 */
    private static ReasimSession previewSession;

    /**
     * 走 reasim 的会话。与 {@link #SESSIONS} 是<b>互斥</b>的两条路：
     * 同一首歌只会走其中一条，不会两边同时响。
     */
    private static final Map<BlockPos, ReasimHandle> REASIM = new HashMap<BlockPos, ReasimHandle>();

    /** 自动回声不必每 tick 都重算（那是上百次方块查询），20 tick 一次足够 */
    private static final int ECHO_REFRESH_TICKS = 20;

    private static final class ReasimHandle {
        final ReasimSession session;
        int echoTimer;
        float echo = 0.30F;

        ReasimHandle(ReasimSession session) {
            this.session = session;
        }
    }

    private static final class Session {
        final BlockPos pos;
        final String soundName;
        final boolean loop;
        final int durationTicks;
        ISound sound;
        int untilCheck;
        int elapsed;
        int misses;

        Session(BlockPos pos, String soundName, boolean loop, ISound sound, int durationTicks) {
            this.pos = pos;
            this.soundName = soundName;
            this.loop = loop;
            this.sound = sound;
            this.durationTicks = durationTicks;
            this.untilCheck = durationTicks > 0 ? durationTicks : UNKNOWN_FIRST_CHECK;
        }
    }

    /**
     * 播放某个方块上的音乐。
     *
     * @param loop 是否循环（由服务端按播放模式决定）
     */
    public static void play(BlockPos pos, String soundName, boolean loop) {
        play(pos, soundName, loop, null);
    }

    /**
     * 播放某个方块上的音乐。
     *
     * @param loop   是否循环（由服务端按播放模式决定）
     * @param params reasim 参数；null 或未启用时走普通播放
     */
    public static void play(BlockPos pos, String soundName, boolean loop, ReasimParams params) {
        stop(pos);
        if (params != null && params.enabled && ReasimBridge.available()) {
            ReasimSession session = ReasimSession.start(pos, soundName, loop, params);
            if (session != null) {
                REASIM.put(pos.toImmutable(), new ReasimHandle(session));
                return;
            }
            // 开不起来（文件不是 ogg / 解码器拒绝 / 引擎刚重建）就安静退回普通播放。
            // 这里只提醒一次：循环模式下一首歌结束就会再进来，每次刷日志没有意义。
            if (!warnedReasimFallback) {
                warnedReasimFallback = true;
                RsAudio.warn("reasim 没能接管 %s，先用普通播放（只有音量，没有失真与噪声）", soundName);
            }
        }
        ISound sound = create(soundName, pos, loop);
        if (sound == null) {
            // 文件不存在 / 资源包还没这个音效：直接报告"放完了"，
            // 免得方块永远卡在播放态、脉冲模式下也永远等不到结束信号
            if (!loop) {
                PacketHandler.INSTANCE.sendToServer(new PacketSoundFinished(pos));
            }
            return;
        }
        Minecraft.getMinecraft().getSoundHandler().playSound(sound);
        SESSIONS.put(pos, new Session(pos, soundName, loop, sound, durationTicksOf(soundName)));
    }

    private static boolean warnedReasimFallback;

    public static void stop(BlockPos pos) {
        ReasimHandle handle = REASIM.remove(pos);
        if (handle != null) {
            handle.session.stop();
        }
        SESSIONS.remove(pos);
        ISound sound = PLAYING.remove(pos);
        if (sound != null) {
            Minecraft.getMinecraft().getSoundHandler().stopSound(sound);
        }
    }

    public static void stopAll() {
        for (ReasimHandle handle : REASIM.values()) {
            handle.session.stop();
        }
        REASIM.clear();
        for (ISound sound : PLAYING.values()) {
            Minecraft.getMinecraft().getSoundHandler().stopSound(sound);
        }
        PLAYING.clear();
        SESSIONS.clear();
        stopPreview();
    }

    // ------------------------------------------------------------------ 计时 / 结束判定

    /** 每个客户端 tick 调一次 */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null) {
            SESSIONS.clear();
            PLAYING.clear();
            for (ReasimHandle handle : REASIM.values()) {
                handle.session.stop();
            }
            REASIM.clear();
            return;
        }
        tickReasim(mc);
        if (SESSIONS.isEmpty()) {
            return;
        }
        SoundHandler handler = mc.getSoundHandler();

        Iterator<Session> iterator = SESSIONS.values().iterator();
        while (iterator.hasNext()) {
            Session s = iterator.next();
            s.elapsed++;
            if (--s.untilCheck > 0) {
                continue;
            }

            boolean stillPlaying = handler != null && s.sound != null && handler.isSoundPlaying(s.sound);

            if (stillPlaying) {
                s.misses = 0;
                if (s.loop) {
                    // 引擎在自己循环（我们设了 repeat），过 1 秒再确认一次就行
                    s.untilCheck = 20;
                } else if (s.elapsed > ABSOLUTE_MAX_TICKS
                        || (s.durationTicks > 0 && s.elapsed > s.durationTicks + ONESHOT_GRACE_TICKS)) {
                    finish(s);
                    iterator.remove();
                } else {
                    // 实际比我们估算的长（编码器延迟之类），再等 5 刻看看
                    s.untilCheck = POLL_TICKS;
                }
            } else if (s.loop) {
                s.misses++;
                if (s.misses >= LOOP_MISSES) {
                    restart(s);
                } else {
                    s.untilCheck = 10;
                }
            } else {
                finish(s);
                iterator.remove();
            }
        }
    }

    /**
     * reasim 会话的每 tick 维护：报结束、跟方块参数、丢掉已经没主的会话。
     * <p>
     * 参数是从<b>方块实体</b>读的，不是从本地缓存读的 —— 这样界面拧滑条之后
     * 方块同步下来的新值会在一两 tick 内直接生效，不需要额外的"改参数"网络包。
     */
    private static void tickReasim(Minecraft mc) {
        if (REASIM.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockPos, ReasimHandle>> iterator = REASIM.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, ReasimHandle> entry = iterator.next();
            BlockPos pos = entry.getKey();
            ReasimHandle handle = entry.getValue();

            // 自然放完 / 内部中止都要回报，否则脉冲模式会一直锁着不放下一首
            if (handle.session.consumeFinished()) {
                PacketHandler.INSTANCE.sendToServer(new PacketSoundFinished(pos));
                iterator.remove();
                continue;
            }
            if (handle.session.isStopped()) {
                iterator.remove();
                continue;
            }
            TileEntity tileEntity = mc.world.getTileEntity(pos);
            if (!(tileEntity instanceof TileAudioPlayer)) {
                // 方块被拆了 / 区块卸载了：不停掉的话声音会一直在那儿响
                handle.session.stop();
                iterator.remove();
                continue;
            }
            TileAudioPlayer tile = (TileAudioPlayer) tileEntity;
            if (--handle.echoTimer <= 0) {
                handle.echoTimer = ECHO_REFRESH_TICKS;
                handle.echo = ReasimEnvironment.autoEcho(mc.world, pos);
            }
            handle.session.update(tile.getReasim(), handle.echo);
        }
    }

    private static void finish(Session s) {
        PLAYING.remove(s.pos);
        PacketHandler.INSTANCE.sendToServer(new PacketSoundFinished(s.pos));
    }

    /** 循环模式下的兜底重播（引擎没帮我们循环时用得上） */
    private static void restart(Session s) {
        ISound sound = create(s.soundName, s.pos, true);
        if (sound == null) {
            // 这会儿读不到了，过 1 秒再试
            s.misses = 0;
            s.untilCheck = 20;
            return;
        }
        Minecraft.getMinecraft().getSoundHandler().playSound(sound);
        s.sound = sound;
        s.misses = 0;
        s.elapsed = 0;
        s.untilCheck = s.durationTicks > 0 ? s.durationTicks : UNKNOWN_FIRST_CHECK;
    }

    // ------------------------------------------------------------------ 试听

    /** 试听（固定放一遍，不循环、不惊动服务端）。不带 reasim 效果 */
    public static void preview(String soundName) {
        preview(soundName, null);
    }

    /**
     * 带 reasim 效果的试听。
     * <p>
     * 界面里的滑条必须能立刻听到 —— 否则调参全靠猜，玩家只能反复"保存→去听→回来改"。
     * 引擎拿不到的时候安静退回不带效果的试听，至少还能确认文件选对了。
     */
    public static void preview(String soundName, ReasimParams params) {
        stopPreview();
        Minecraft mc = Minecraft.getMinecraft();
        if (params != null && params.enabled && ReasimBridge.available()
                && mc.player != null && soundName != null && !soundName.isEmpty()) {
            ReasimSession session = ReasimSession.startPreview(mc.player.getPosition(), soundName, params);
            if (session != null) {
                previewSession = session;
                return;
            }
        }
        float x = mc.player == null ? 0.0F : (float) mc.player.posX;
        float y = mc.player == null ? 0.0F : (float) mc.player.posY;
        float z = mc.player == null ? 0.0F : (float) mc.player.posZ;
        ResourceLocation location = AudioLibrary.soundLocation(soundName);
        if (!exists(location)) {
            warnMissing(soundName);
            return;
        }
        ISound sound = new PositionedSoundRecord(new SoundEvent(location), SoundCategory.RECORDS,
                1.0F, 1.0F, x, y, z);
        mc.getSoundHandler().playSound(sound);
        preview = sound;
    }

    public static void stopPreview() {
        if (previewSession != null) {
            previewSession.stop();
            previewSession = null;
        }
        if (preview != null) {
            Minecraft.getMinecraft().getSoundHandler().stopSound(preview);
            preview = null;
        }
    }

    // ------------------------------------------------------------------ 工具

    private static ISound create(String soundName, BlockPos pos, boolean loop) {
        if (soundName == null || soundName.isEmpty()) {
            return null;
        }
        ResourceLocation location = AudioLibrary.soundLocation(soundName);
        if (!exists(location)) {
            warnMissing(soundName);
            return null;
        }
        // repeatDelay 必须是 0，MC 才会把 toLoop 传给声音引擎（见 SoundManager.playSound）
        ISound sound = new PositionedSoundRecord(location, SoundCategory.RECORDS, 4.0F, 1.0F,
                loop, 0, ISound.AttenuationType.LINEAR,
                pos.getX() + 0.5F, pos.getY() + 0.5F, pos.getZ() + 0.5F);
        PLAYING.put(pos, sound);
        return sound;
    }

    private static boolean exists(ResourceLocation location) {
        SoundHandler handler = Minecraft.getMinecraft().getSoundHandler();
        return handler != null && handler.getAccessor(location) != null;
    }

    /** 读 ogg 的真实时长换算成刻；读不到返回 -1（调用方会退化成"只靠轮询判断结束"） */
    private static int durationTicksOf(String soundName) {
        File file = AudioLibrary.fileOf(soundName);
        if (file == null) {
            return -1;
        }
        double seconds = OggDuration.readSeconds(file);
        if (seconds <= 0.0D) {
            return -1;
        }
        return (int) Math.round(seconds * 20.0D);
    }

    private static void warnMissing(String soundName) {
        RsAudio.warn("找不到音频 %s:%s —— 请确认 %s 里有这个 ogg 文件，"
                        + "然后在界面里点「重新扫描」或按 F3+T 重载资源",
                RsAudio.MODID, soundName, Config.getAudioDir());
    }
}

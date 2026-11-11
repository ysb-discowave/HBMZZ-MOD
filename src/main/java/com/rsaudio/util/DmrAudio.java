package com.rsaudio.util;

import com.rsaudio.dmr.DmrSpec;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * DMR 事件 → 音频的映射表。
 *
 * <h3>这张表就是"哪件事响哪段音"的唯一出处</h3>
 * 内核（{@code com.rsaudio.dmr}）只产出事件键，措辞和音效都在外面。想换一首曲子、
 * 或者把某条广播换成别的声音，<b>只改这张表</b>即可，不需要碰状态机。
 *
 * <h3>文件名为什么写中文</h3>
 * 这里的 key 是<b>玩家在 audio 目录里看到的那个文件名</b>（不含 .ogg），不是资源名。
 * 原因见 {@link com.rsaudio.client.AudioLibrary#soundNameOf}：
 * 全中文名会被规范化成 {@code track_xxx} 这种带文件名哈希的形式，
 * 那个哈希会随目录内容变化，写死在代码里就等于埋了一颗"哪天多放一首歌就全错位"的雷。
 * <p>
 * 另外，找不到文件时 {@link RemoteAudio} 只记一条警告、不抛异常 ——
 * 音频目录是玩家的东西，缺文件不该让反应堆逻辑跟着坏掉。
 *
 * <h3>三栏的含义</h3>
 * <ul>
 *   <li><b>音乐</b>：按位置播放（有距离衰减），所以"站在反应堆旁边才听得见"。</li>
 *   <li><b>语音</b>：全设施广播（放在每个玩家自己脚下，无衰减）—— FAAS 的角色。</li>
 *   <li><b>音效</b>：按位置播放的短音（警报、爆炸、机械动作）。</li>
 * </ul>
 * 三栏各占一个虚拟位置槽，所以熔毁音乐和 Code Black 语音可以同时响
 * （见 {@link RemoteAudio#slotPos}）。
 *
 * <h3>★ 每一栏都可以写"候选链"</h3>
 * 形如 {@code "正品名|通用替代"}。音频目录是玩家的东西，缺文件是常态；
 * 写死单个名字时文件一不在事件就<b>整条哑掉</b>（只有一行日志，游戏里看不出来）。
 * 写法与判据见 {@link RemoteAudio#splitChain}。
 * <p>
 * ★★ <b>改动这张表之后必须跑 {@code tools/check_audio_refs.py}。</b>
 * 它会把每条候选链拿去和音频目录对一遍，把"正在用替代品"和"整条落空"的条目都列出来。
 * 这一步不能省：写错一个字的结果不是编译错误，而是<b>游戏里那一件事没有声音</b>，
 * 而这是最容易一直没人发现的一类缺陷 —— 本表里就曾经有 17 条指向不存在的文件。
 */
public final class DmrAudio {

    /** 事件键 → {音乐, 语音, 音效}，任一栏为 null 表示这件事不响那一类 */
    private static final Map<String, String[]> TABLE = new HashMap<String, String[]>();

    /** 一般事件的声音能传多远（格） */
    private static final double RANGE = 96.0D;
    /** 重大事件（熔毁 / Code Black / 内爆）要整个设施都听见 */
    private static final double RANGE_MAJOR = 320.0D;

    /** 需要全设施级别音量的事件 */
    private static final java.util.Set<String> MAJOR = new java.util.HashSet<String>();

    private DmrAudio() {
    }

    static {
        put(DmrSpec.EV_IGNITION_START, null, "开核广播", "启动|电力恢复声");
        put(DmrSpec.EV_IGNITION_DENIED_SUBSYS, null, null, "锁定|大门关闭");
        put(DmrSpec.EV_IGNITION_DENIED_FUEL, null, null, "锁定|大门关闭");
        put(DmrSpec.EV_ONLINE, "反应堆正常启动音乐", "开核成功声", null);
        put(DmrSpec.EV_COOLDOWN_DONE, null, null, "某事物关闭声（未知来源）");
        // 正常停堆被前置条件挡下：与应急停机那两条同样的声音，玩家不用学两套
        put(DmrSpec.EV_COOLDOWN_FAILED_TEMP, null, "广播 (10)", null);
        put(DmrSpec.EV_COOLDOWN_FAILED_EFSS, null, "广播 (11)", null);

        put(DmrSpec.EV_SAFE_TEMP, null, "超过安全阈值|法斯广播 (9)", null);
        put(DmrSpec.EV_WARN_TEMP, null, "法斯广播声", "警报声");
        put(DmrSpec.EV_INTEGRITY_DROP, null, null, "反应堆共振声");
        put(DmrSpec.EV_FUEL_LOW, null, "法斯广播", "稳定器功率低|警报声 (2)");
        put(DmrSpec.EV_FUEL_MONITOR_FAIL, null, "法斯广播 (2)", null);
        put(DmrSpec.EV_SUSTAIN_LOSS, null, "法斯广播 (3)", "核心冷却断电|断电环境音");
        put(DmrSpec.EV_STALL, null, "法斯广播 (4)", "某事物关闭声（未知来源）");
        put(DmrSpec.EV_EFSS_LOW, null, "法斯广播 (5)", "稳定器功率低|警报声 (2)");
        put(DmrSpec.EV_LASER_LOST, null, "法斯广播 (6)", "爆炸声");
        put(DmrSpec.EV_LASER_REPAIRED, null, null, "机械声");
        put(DmrSpec.EV_MAINTENANCE_IN, null, "法斯广播 (7)", "锁定|大门关闭");
        put(DmrSpec.EV_MAINTENANCE_OUT, null, "法斯广播 (8)", "解锁|大门开启");
        put(DmrSpec.EV_MAINTENANCE_NEED_CELLS, null, null, "锁定|大门关闭");

        // ---- 停机前置条件被卡住：每一条给一条不同的广播，玩家靠耳朵也能分辨卡在哪
        put(DmrSpec.EV_SHUTDOWN_FAILED_THERMAL, null, "广播 (10)", null);
        put(DmrSpec.EV_SHUTDOWN_FAILED_EFSS, null, "广播 (11)", null);
        put(DmrSpec.EV_SHUTDOWN_FAILED_LASER, null, "广播 (12)", null);
        put(DmrSpec.EV_SHUTDOWN_FAILED_KEYS, null, "广播 (13)", null);
        put(DmrSpec.EV_SHUTDOWN_FAILED_CELLS, null, "广播 (14)", null);
        put(DmrSpec.EV_CODE_REJECTED, null, null, "锁定|大门关闭");
        put(DmrSpec.EV_CELL_PULLED, null, null, "解锁|大门开启");
        put(DmrSpec.EV_CELL_TRAP, null, "广播 (15)", "锁定|大门关闭");
        put(DmrSpec.EV_CODE_LOCATED, null, "广播 (16)", null);

        // ---- 熔毁整条线：音乐是相位专属的，P1 和 P2 各一首，长度和窗口对齐
        put(DmrSpec.EV_MELTDOWN, "新版本P1融毁音乐", "法斯广播声", "紧急警报系统");
        // ★ P2 的语音原本写的 "法斯广播（深金...）" —— 音频目录里的真实文件名没有"法斯"两个字。
        //   这类"差一个字"的错法最坑：不报错、不崩，只是那一条广播永远不响。
        put(DmrSpec.EV_PHASE2, "新版本P2反应堆融毁音乐", "广播（深金...）", "防空警报");
        put(DmrSpec.EV_CODE_BLACK, null, "法斯广播声主启动按钮解锁", "防空警报");
        put(DmrSpec.EV_IMPLODE, null, null, "DMR爆炸声");
        // 停机成功之后**不该再有音乐接着放**（停都停了还在放融毁曲很奇怪），
        // 所以这里放的是"成功"那一首；窗口结束时 TileDmrController 会显式 stopMusic。
        put(DmrSpec.EV_SHUTDOWN_OK_P1, "停机成功|P1成功音乐", "P1成功音乐", null);
        put(DmrSpec.EV_SHUTDOWN_OK_P2, "停机成功|P1成功音乐", "停机成功|开核成功声", null);

        MAJOR.add(DmrSpec.EV_MELTDOWN);
        MAJOR.add(DmrSpec.EV_PHASE2);
        MAJOR.add(DmrSpec.EV_CODE_BLACK);
        MAJOR.add(DmrSpec.EV_IMPLODE);
        MAJOR.add(DmrSpec.EV_WARN_TEMP);
    }

    private static void put(String event, String music, String voice, String sfx) {
        TABLE.put(event, new String[]{music, voice, sfx});
    }

    /**
     * 处理一个内核事件：按表把该响的声音都发出去。
     * <p>
     * 只在服务端调用。同一个事件会依次发音乐 / 语音 / 音效三条包，
     * 三条互不干扰（各占一个虚拟位置槽）。
     */
    public static void onEvent(World world, BlockPos origin, String eventKey) {
        if (world == null || world.isRemote || origin == null || eventKey == null) {
            return;
        }
        String[] roles = TABLE.get(eventKey);
        if (roles == null) {
            return;
        }
        double range = MAJOR.contains(eventKey) ? RANGE_MAJOR : RANGE;
        if (roles[0] != null) {
            // 音乐用"位置播放"：站在设施里听得见，跑到外面就淡出 —— 这是刻意的，
            // 让"离反应堆多远"这件事在听觉上有意义
            RemoteAudio.playAt(world, origin, range, RemoteAudio.SLOT_MUSIC, roles[0], false);
        }
        if (roles[1] != null) {
            RemoteAudio.announce(world, origin, range, RemoteAudio.SLOT_VOICE, roles[1]);
        }
        if (roles[2] != null) {
            RemoteAudio.playAt(world, origin, range, RemoteAudio.SLOT_SFX, roles[2], false);
        }
    }

    /**
     * 停掉 DMR 的音乐轨（正常停机、内爆之后用）。
     * 播放新音乐会自然顶掉旧的，但"停机成功"这类事件之后我们不再播音乐，
     * 需要显式掐掉，否则融毁音乐会在安全停机之后继续放。
     */
    public static void stopMusic(World world, BlockPos origin) {
        RemoteAudio.stop(world, origin, RANGE_MAJOR, RemoteAudio.SLOT_MUSIC);
    }

    /** 供界面 / 指令显示：这张表里登记了多少个事件 */
    public static int tableSize() {
        return TABLE.size();
    }

    /** 供自检脚本核对"表里每个事件键都是内核真的会发出来的" */
    public static java.util.Set<String> events() {
        return TABLE.keySet();
    }
}

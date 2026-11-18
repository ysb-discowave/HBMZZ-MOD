package com.rsaudio.util;

import com.rsaudio.access.AirlockCore;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>非 DMR 机器</b>的事件 → 音频映射表：气闸控制器、防核门、以及后续别的设施件。
 *
 * <h3>和 {@link DmrAudio} 的关系</h3>
 * 两张表是并列的，各自管自己那一摊：DMR 的反应堆时序（点火 / 熔毁 / 停机）在
 * {@code DmrAudio}，这里管"门和气闸"这种<b>动作型</b>的音效。分开的原因只有一个
 * ——它们的生命周期不同；而共用 {@link RemoteAudio} 的三个虚拟位置槽，所以能同时响：
 * 熔毁音乐放着的时候，远处的防核门照样能"哐"一声关上。
 *
 * <h3>★ 表的结构和 DmrAudio 完全一样（三栏 + 候选链）</h3>
 * 这不是为了好看，是为了能被同一套自检脚本解析：
 * {@code tools/check_audio_refs.py} 不认识"某个类特有的表长什么样"，
 * 它只认 {@code put(事件, 音乐, 语音, 音效)} 这个形状。表一改形状，
 * 除非同步改脚本，否则<b>整张表会被静默跳过</b>——体检报"通过"，实际一条都没查。
 * （本文件第一版就是两栏的，脚本当场报了"表条目: 0"。）
 *
 * <h3>★ 为什么每条都是"候选链"而不是一个名字</h3>
 * 音频目录 {@code config/rsaudio/audio} 是玩家的东西，缺文件是常态。
 * 表里写死单个名字时，文件一不在这件事就<b>整条哑掉</b>：只留一行日志，
 * 玩家在游戏里完全看不出来"这里本来该有声音"。
 * <p>
 * 所以每一栏都写成 {@code "首选|备选|兜底"}：
 * <ul>
 *   <li>首选是玩家库里的那个"正品"名字（例如 {@code 气闸声}）；</li>
 *   <li>备选是语义上最接近的通用机械音（例如 {@code 机械声}）——
 *       正品还没放进目录时至少还有动静；</li>
 *   <li>{@code tools/check_audio_refs.py} 会把"正在用备选"的条目列出来，
 *       于是"该补哪个文件"变成一份看得见的待办清单，而不是静默的空洞。</li>
 * </ul>
 *
 * <h3>距离</h3>
 * 门和气闸都是"你身边那台机器"，所以用 {@link #RANGE}，
 * 不需要 DMR 那种"整个设施都听得见"的 320 格。
 */
public final class MachineAudio {

    /** 机器类音效的传播半径（格）。门/气闸是"就近"的东西，看得见才听得见 */
    public static final double RANGE = 48.0D;

    // ★ 门开/关的音效（DOOR_OPEN / DOOR_CLOSE）已彻底删除：用户反馈"开门警报吵死了"。
    //   连同 doorMotion 方法一起删掉，而不是只去掉调用点 —— 留着方法迟早会被
    //   某处重新接回去，又变成"响了才发现没删干净"。门户现在全程静音。

    /** 气闸事件 → {音乐, 语音, 音效}，任一栏为 null 表示这件事不响那一类 */
    private static final Map<Integer, String[]> AIRLOCK = new HashMap<Integer, String[]>();

    static {
        // 两侧已密封、开始净化：这是整段流程真正"开始"的那一刻
        put(AirlockCore.EVENT_DECON_START, null, null, "气闸声|机械声");
        // 净化结束：机械松开、准备放行
        put(AirlockCore.EVENT_DECON_DONE, null, null, "机械声|倒水声");
        // ★ 下面四条一律静音：用户反馈"开门警报吵死了"。
        //   ★ 注意保留 put(...) 条目本身 —— 自检脚本 check_audio_refs.py 是按
        //     "内核里每个 EVENT_* 有没有对应的 put" 来核对覆盖率的。
        //     把整条删掉会被判成"这个事件没接音效"从而体检失败；
        //     改成全 null 则是"已接线、但有意不响"，语义正确且体检照过。
        //   ★ 只静音"开门 / 警报"这类刺耳的；气闸的机械环境音（气闸声 / 机械声 / 倒水声）保留。

        // 对面那扇开始开（原"大门开启"，已静音）
        put(AirlockCore.EVENT_OPEN_SIDE, null, null, null);
        // 目标侧被人锁着 → 憋在里面等（原"警报声"，已静音：卡住时反复响最吵）
        put(AirlockCore.EVENT_HOLD, null, null, null);
        // 急停 / 超时 → 循环没走完（原"警报声"，已静音）
        put(AirlockCore.EVENT_ABORT, null, null, null);
        // 整套走完（原"大门开启"，已静音）
        put(AirlockCore.EVENT_DONE, null, null, null);
    }

    private MachineAudio() {
    }

    private static void put(int event, String music, String voice, String sfx) {
        AIRLOCK.put(Integer.valueOf(event), new String[]{music, voice, sfx});
    }

    /**
     * 处理一个气闸事件。只在服务端调用。
     *
     * @param event {@link AirlockCore#EVENT_NONE} 或其它 {@code AirlockCore.EVENT_*}；
     *              {@code EVENT_NONE} 与未登记的事件都不响
     */
    public static void airlockEvent(World world, BlockPos pos, int event) {
        if (world == null || world.isRemote || pos == null) {
            return;
        }
        String[] roles = AIRLOCK.get(Integer.valueOf(event));
        if (roles == null) {
            return;
        }
        double range = RANGE;
        if (roles[0] != null) {
            RemoteAudio.playAt(world, pos, range, RemoteAudio.SLOT_MUSIC, roles[0], false);
        }
        if (roles[1] != null) {
            RemoteAudio.announce(world, pos, range, RemoteAudio.SLOT_VOICE, roles[1]);
        }
        if (roles[2] != null) {
            RemoteAudio.playAt(world, pos, range, RemoteAudio.SLOT_SFX, roles[2], false);
        }
    }

    /** 表里登记了多少个气闸事件（自检用） */
    public static int airlockTableSize() {
        return AIRLOCK.size();
    }

    /** 表里登记的气闸事件键（自检脚本核对"每个内核事件都接上了"用） */
    public static java.util.Set<Integer> airlockEvents() {
        return AIRLOCK.keySet();
    }
}

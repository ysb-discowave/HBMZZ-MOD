package com.rsaudio.client;

import com.rsaudio.network.F9Snapshot;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧的 F9 读数表：按控制柜坐标存最近一次收到的快照。
 * <p>
 * 和 {@link ClientDmrState} 是同一张表的两份实例。为什么不共用一个类：
 * 两份快照的类型不同，硬塞进一个泛型表要为了"少一个文件"付出到处强转的代价，
 * 而世界卸载时两个都要清 —— 那行清理挂在 {@code ClientProxy} 上，漏一个就会
 * "换存档后旧坐标的读数串到新世界"（DMR 那条注释里踩过）。
 * <p>
 * {@link #clear()} 必须挂在<b>世界卸载</b>上（见 {@code ClientProxy}）。
 */
public final class ClientF9State {

    private static final Map<Long, F9Snapshot> STATES = new HashMap<Long, F9Snapshot>();

    private ClientF9State() {
    }

    public static void put(BlockPos pos, F9Snapshot snapshot) {
        if (pos == null || snapshot == null) {
            return;
        }
        STATES.put(Long.valueOf(pos.toLong()), snapshot);
    }

    /** 没收到过就返回 null —— 界面必须能处理"还没同步到"的状态 */
    public static F9Snapshot get(BlockPos pos) {
        return pos == null ? null : STATES.get(Long.valueOf(pos.toLong()));
    }

    public static void clear() {
        STATES.clear();
    }
}

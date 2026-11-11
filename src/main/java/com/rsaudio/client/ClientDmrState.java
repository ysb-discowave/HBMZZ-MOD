package com.rsaudio.client;

import com.rsaudio.network.DmrSnapshot;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧的反应堆读数表：按控制柜坐标存最近一次收到的快照。
 * <p>
 * 为什么不把快照写进客户端的方块实体：客户端那份方块实体是"影子"，方块一卸载、
 * 区块一重载就会换一个新的实例，写进去的读数会莫名其妙丢掉；而且界面开着的时候
 * 方块实体理论上可能已经不 tick 了。用一张静态表，界面每次绘屏直接查表，
 * 服务端什么时候推、客户端什么时候画，两边完全解耦。
 * <p>
 * {@link #clear()} 要挂在<b>世界卸载</b>上（见 {@code ClientProxy}）：
 * 不清的话换存档后旧坐标的读数会串到新世界的同一坐标上。
 */
public final class ClientDmrState {

    private static final Map<Long, DmrSnapshot> STATES = new HashMap<Long, DmrSnapshot>();

    private ClientDmrState() {
    }

    public static void put(BlockPos pos, DmrSnapshot snapshot) {
        if (pos == null || snapshot == null) {
            return;
        }
        STATES.put(Long.valueOf(pos.toLong()), snapshot);
    }

    /** 没收到过就返回 null —— 界面必须能处理"还没同步到"的状态 */
    public static DmrSnapshot get(BlockPos pos) {
        return pos == null ? null : STATES.get(Long.valueOf(pos.toLong()));
    }

    public static void clear() {
        STATES.clear();
    }
}

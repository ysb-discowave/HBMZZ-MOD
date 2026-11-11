package com.rsaudio.client;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端侧的列车读数表：按实体 id 存最近一次收到的快照 int[]。
 *
 * <h3>★ 为什么是"按实体 id 的静态表"而不是"存在实体对象上"</h3>
 * 和 {@link ClientDmrState} 同一个理由：客户端的实体是"影子"，
 * 玩家走出视距再回来实体会被重新创建，写在对象上的读数会丢；
 * 而渲染器每帧都要读它。用静态表把"服务端什么时候推"和"渲染器什么时候画"解耦开。
 *
 * <h3>★ 槽位含义一律以 {@code TrainCore.saveState()} 为准</h3>
 * 客户端<b>不应该</b>自己去猜某个下标是什么。所以这里提供的是"按下标取值"的
 * 基础访问器，再由 {@link #speed(int[])} / {@link #cars(int[])} 这样的小工具
 * 把下标收在一处。下标散在渲染器里的话，内核改一次槽位布局就要满世界找。
 */
public final class ClientTrainState {

    private static final Map<Integer, int[]> STATES = new HashMap<Integer, int[]>();

    private ClientTrainState() {
    }

    public static void put(int entityId, int[] state) {
        if (state == null) {
            return;
        }
        STATES.put(Integer.valueOf(entityId), state);
    }

    /** 没收到过就返回 null —— 渲染器必须能处理"还没同步到"的状态 */
    public static int[] get(int entityId) {
        return STATES.get(Integer.valueOf(entityId));
    }

    public static void remove(int entityId) {
        STATES.remove(Integer.valueOf(entityId));
    }

    public static void clear() {
        STATES.clear();
    }

    // ---- 下标访问器（与 TrainCore.saveState 一一对应）

    /** 速度（格/tick）。槽位 4/5 是两个 int 拼出来的 double */
    public static double speed(int[] s) {
        if (s == null || s.length < 6) {
            return 0.0;
        }
        long bits = ((long) s[4] << 32) | (s[5] & 0xFFFFFFFFL);
        return Double.longBitsToDouble(bits);
    }

    /** 当前相位（TrainSpec.PH_*） */
    public static int phase(int[] s) {
        return s == null || s.length < 8 ? 0 : s[7];
    }

    /** 编组里的车厢数（不含车头） */
    public static int cars(int[] s) {
        return s == null || s.length < 13 ? 0 : s[12];
    }

    /** 车上乘客数 */
    public static int passengers(int[] s) {
        return s == null || s.length < 14 ? 0 : s[13];
    }

    /** 故障原因（TrainSpec.FAIL_*） */
    public static int fail(int[] s) {
        return s == null || s.length < 15 ? 0 : s[14];
    }
}

package com.rsaudio.truck;

/**
 * 货运卡车内核 —— 零 Minecraft 依赖的行驶状态机。
 * <p>
 * 和 {@code com.rsaudio.train.TrainCore} 同一分工纪律：
 * <b>实体只是胶水</b>（扫世界 / 喂 tick / 搬坐标 / 记日志 / 存读档），
 * 一切"车该往哪儿、跑多快、什么时候停"的物理全在这里，
 * 所以这里能被 {@code tools/check_truck_core.py} 拿真 javac 单测。
 *
 * <h3>它比列车内核简单在哪</h3>
 * 列车有轨道图 / 站点 / 时刻表，卡车只有<b>一条直线上的匀速巡航</b>：
 * <ul>
 *   <li>状态就两个：{@link #STATE_IDLE}（熄火）/ {@link #STATE_RUNNING}（行驶）；</li>
 *   <li>方向就四个：0=N / 1=E / 2=S / 3=W —— 与 blockstate 的 facing 同名同序，
 *       车位方块（{@code BlockTruckPad.FACING}）直接喂它；</li>
 *   <li>"前方有没有障碍"内核<b>不知道</b>（它不 import 世界），
 *       由实体每 tick 用 {@link #setBlocked(boolean)} 喂进来。</li>
 * </ul>
 *
 * <h3>★ 为什么"受阻要减速滑停"而不是立刻停</h3>
 * 卡车是几十吨的重机械：撞墙前必然有一段刹车距离。
 * 立刻停（速度瞬切 0）在观感上等于"车穿过了墙皮" ——
 * 而且"立刻停"和"滑停"在代码上是同一条 {@code speed -= DECEL} 路径，
 * 少一条分支就少一个写错的机会。滑停走完之后内核自己切回 IDLE 并报
 * {@code "blocked"} 事件 —— 实体拿这个事件向附近的玩家解释"车为什么停了"。
 *
 * <h3>★ 事件去重</h3>
 * 每个事件只报一次（{@link #drainEvent()} 取走即清空）：
 * "前方受阻"这条如果每 tick 都报，20 tick 就刷一条，玩家直接关日志。
 */
public class TruckCore {

    public static final int STATE_IDLE = 0;
    public static final int STATE_RUNNING = 1;

    /** 满速（格/tick）：0.05 = 每 20 tick 走一格。货运卡车，不赶时间 */
    public static final double SPEED_MAX = 0.05D;
    /** 加速度（格/tick²）：约 20 tick 到满速 */
    public static final double ACCEL = 0.0025D;
    /** 减速度（格/tick²）：停车 / 受阻滑停共用 */
    public static final double DECEL = 0.005D;

    /** 状态机槽位（存档 / 生成数据用）：[state, dir, speed*10000] */
    public static final int STATE_SLOTS = 3;

    /** 方向码 0..3，与 blockstate 的 facing 同名同序：0=N 1=E 2=S 3=W */
    private int dir = 2;
    private int state = STATE_IDLE;
    private double speed = 0.0D;
    private boolean blocked = false;
    private String event = null;

    // ---------------------------------------------------------------- 读数

    public int getState() {
        return state;
    }

    public int getDirection() {
        return dir;
    }

    public double getSpeed() {
        return speed;
    }

    /** 默认方向（刚 new 出来、还没被车位喂方向之前） */
    public static final int DEFAULT_DIR = 2;   // 南 —— MC 的 yaw 0 就是 +Z（南）

    /**
     * 方向码 → MC yaw（度）。
     * <p>
     * MC 的朝向约定是"朝向 = (-sin yaw, cos yaw)"（0=+Z 南、90=-X 西），
     * 所以：0(N)→180、1(E)→270、2(S)→0、3(W)→90。
     * 这张表和 {@code FreightTruckRenderer} 的 {@code rotate(-yaw)} 是同一套符号约定。
     */
    public static int yawOfDir(int dir) {
        return (dir * 90 + 180) % 360;
    }

    /** 方向码 → 世界 X 分量（0=N,1=E,2=S,3=W） */
    public static double dirX(int dir) {
        if (dir == 1) {
            return 1.0D;
        }
        if (dir == 3) {
            return -1.0D;
        }
        return 0.0D;
    }

    /** 方向码 → 世界 Z 分量（+Z 是南） */
    public static double dirZ(int dir) {
        if (dir == 2) {
            return 1.0D;
        }
        if (dir == 0) {
            return -1.0D;
        }
        return 0.0D;
    }

    // ---------------------------------------------------------------- 指令

    /** 设置方向（车位放出车时用；倒车走 {@link #reverse()}） */
    public void setDirection(int d) {
        if (d >= 0 && d <= 3) {
            this.dir = d;
        }
    }

    /** 点火。前方受阻时拒发并报 {@code still_blocked}（不会"点火即撞墙"） */
    public void start() {
        if (state == STATE_RUNNING) {
            return;
        }
        if (blocked) {
            event = "still_blocked";
            return;
        }
        state = STATE_RUNNING;
        event = "start";
    }

    /** 熄火。速度归零 */
    public void stop() {
        if (state != STATE_RUNNING) {
            return;
        }
        state = STATE_IDLE;
        speed = 0.0D;
        event = "stop";
    }

    /** 调头（180°）。行驶中也可以调头 —— 相当于换倒挡 */
    public void reverse() {
        dir = (dir + 2) % 4;
        event = "reverse";
    }

    /** 实体每 tick 喂"前方有没有障碍"（内核自己不看世界） */
    public void setBlocked(boolean b) {
        blocked = b;
    }

    public boolean isBlocked() {
        return blocked;
    }

    // ---------------------------------------------------------------- tick

    /**
     * 推进一步。
     *
     * @return 本 tick 沿<b>当前方向</b>移动的距离（格）；0 = 没动
     */
    public double tick() {
        if (state == STATE_RUNNING) {
            if (blocked) {
                speed = Math.max(0.0D, speed - DECEL);
                if (speed <= 0.0D) {
                    speed = 0.0D;
                    state = STATE_IDLE;
                    event = "blocked";
                }
            } else {
                speed = Math.min(SPEED_MAX, speed + ACCEL);
            }
        } else {
            speed = 0.0D;
        }
        return speed;
    }

    /** 取出（并清空）本 tick 的事件键：start / stop / reverse / blocked / still_blocked */
    public String drainEvent() {
        String e = event;
        event = null;
        return e;
    }

    // ---------------------------------------------------------------- 存档

    public int[] saveState() {
        return new int[]{state, dir, (int) Math.round(speed * 10000.0D)};
    }

    /**
     * 灌回存档。不合法（null / 长度 / 越界）一律拒绝，保持原状态 ——
     * 和 TrainCore.loadState 同一条防呆纪律：存档坏了不能把车变成"鬼状态"。
     */
    public void loadState(int[] st) {
        if (st == null || st.length != STATE_SLOTS) {
            return;
        }
        if (st[0] != STATE_IDLE && st[0] != STATE_RUNNING) {
            return;
        }
        if (st[1] < 0 || st[1] > 3) {
            return;
        }
        if (st[2] < 0 || st[2] > (int) Math.round(SPEED_MAX * 10000.0D)) {
            return;
        }
        state = st[0];
        dir = st[1];
        speed = st[2] / 10000.0D;
    }
}

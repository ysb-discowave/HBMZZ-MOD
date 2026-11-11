package com.rsaudio.network;

import com.rsaudio.elevator.ElevatorCore;
import com.rsaudio.elevator.ElevatorSpec;
import io.netty.buffer.ByteBuf;

/**
 * 电梯状态的<b>只读快照</b>：服务端每 {@code SYNC_INTERVAL} 刻发一份到客户端，
 * 客户端只拿它画画，<b>不做任何乐观更新</b>（本地先动再等服务端纠正是"电梯在抖"的经典成因）。
 * <p>
 * 和 {@code F9Snapshot} / {@code DmrSnapshot} 同一套路子，两条关键约定也一样：
 * <ul>
 *   <li>字段全是 public final —— 这是个"数据包"，不是可变对象；</li>
 *   <li>{@link #fromBytes} 把长度当<b>不可信输入</b>处理（数组长度先夹，再读），
 *       否则一个畸形包就能让客户端抛异常崩掉。</li>
 * </ul>
 *
 * <h3>★ 为什么连"层表"都要发过去</h3>
 * 轿厢的整数索引 → 世界坐标的换算是 {@link ElevatorSpec#axisStep}。
 * 客户端要画"轿厢现在在哪一层"，就得知道层表；否则只能瞎猜。
 * 层表最长 {@link ElevatorSpec#MAX_FLOORS} 条，整包几十字节，代价可以忽略。
 */
public class ElevatorSnapshot {

    /** 数据版本。改布局就 +1（客户端和服务端版本不一致时宁可整包丢弃，也别读出乱码） */
    public static final int VERSION = 1;

    public final int version;
    public final int type;
    public final double pos;
    public final double vel;
    public final int dir;
    public final int state;
    public final double doorAmount;
    public final int fault;
    public final int trips;
    public final int shaftMissing;
    /** 轿厢此刻占据的整数路径索引（客户端画轿厢用） */
    public final int cellIndex;
    /** 目标层在 floors[] 里的下标；-1 = 没有目标 */
    public final int targetSlot;
    /** 升序的层表（路径索引） */
    public final int[] floors;
    /** 呼叫位图：第 i 位 = floors[i] 被叫了 */
    public final int callMask;
    /** 井道认出来了（控制器的运行灯用的是它和 fault 的组合） */
    public final boolean structureOk;

    public ElevatorSnapshot(int version, int type, double pos, double vel, int dir, int state,
                            double doorAmount, int fault, int trips, int shaftMissing,
                            int cellIndex, int targetSlot, int[] floors, int callMask,
                            boolean structureOk) {
        this.version = version;
        this.type = type;
        this.pos = pos;
        this.vel = vel;
        this.dir = dir;
        this.state = state;
        this.doorAmount = doorAmount;
        this.fault = fault;
        this.trips = trips;
        this.shaftMissing = shaftMissing;
        this.cellIndex = cellIndex;
        this.targetSlot = targetSlot;
        this.floors = floors == null ? new int[0] : floors;
        this.callMask = callMask;
        this.structureOk = structureOk;
    }

    // ------------------------------------------------------------------ 便捷查询
    // ★ 这几个方法存在的唯一理由：GUI 里会写十几遍，写十几遍就有十几处会写错下标。
    //   统一放在这里，"越界返回安全值"这条规矩也就只有一处。

    /**
     * 空快照（"还没收到服务端读数"）。
     * <p>
     * ★ 字段全是 final，所以没法写无参构造 —— 用这个静态工厂替代。
     * GUI 拿到它就显示"等待服务端"而不是一堆 0（0 会被误读成"电梯在一层、门关着、一切正常"）。
     */
    public static ElevatorSnapshot empty() {
        return new ElevatorSnapshot(VERSION, ElevatorSpec.TYPE_PASSENGER, 0.0D, 0.0D, 0,
                ElevatorSpec.ST_FAULT, 0.0D, ElevatorSpec.FAULT_NONE, 0, 0, 0, -1,
                new int[0], 0, false);
    }

    /** 这份快照是不是"还没收到读数"（GUI 靠它决定要不要画那行等待提示） */
    public boolean isEmpty() {
        return floors.length == 0 && !structureOk && trips == 0 && pos == 0.0D;
    }

    public int floorCount() {
        return floors.length;
    }

    public int floorIndex(int slot) {
        return (slot < 0 || slot >= floors.length) ? -1 : floors[slot];
    }

    public boolean hasCall(int slot) {
        return slot >= 0 && slot < floors.length && (callMask & (1 << slot)) != 0;
    }

    public int callCount() {
        int n = 0;
        for (int i = 0; i < floors.length && i < 31; i++) {
            if ((callMask & (1 << i)) != 0) {
                n++;
            }
        }
        return n;
    }

    /** 轿厢此刻在层表的第几层；在两楼之间给 -1 */
    public int currentSlot() {
        for (int i = 0; i < floors.length; i++) {
            if (floors[i] == cellIndex) {
                return i;
            }
        }
        return -1;
    }

    public String stateKey() {
        return ElevatorSpec.stateName(state);
    }

    public String faultKey() {
        return ElevatorSpec.faultName(fault);
    }

    public String typeKey() {
        return ElevatorSpec.typeName(type);
    }

    public boolean hasFault() {
        return fault != ElevatorSpec.FAULT_NONE;
    }

    /** 门开度 > 一半（"能进出"）—— 和内核 {@code passable()} 用同一个阈值 */
    public boolean passable() {
        return doorAmount > 0.5D;
    }

    // ------------------------------------------------------------------ 序列化

    public static ElevatorSnapshot capture(ElevatorCore core, boolean structureOk) {
        int n = core.floorCount();
        int[] f = new int[n];
        for (int i = 0; i < n; i++) {
            f[i] = core.floorIndex(i);
        }
        int mask = 0;
        for (int i = 0; i < n && i < 31; i++) {
            if (core.hasCall(i)) {
                mask |= (1 << i);
            }
        }
        return new ElevatorSnapshot(VERSION, core.type(), core.pos(), core.vel(), core.dir(),
                core.state(), core.doorAmount(), core.fault(), core.trips(), core.shaftMissing(),
                core.cellIndex(), core.targetSlot(), f, mask, structureOk);
    }

    public void toBytes(ByteBuf buf) {
        buf.writeByte(version);
        buf.writeByte(type);
        buf.writeInt((int) Math.round(pos * 1000.0D));
        buf.writeInt((int) Math.round(vel * 1000.0D));
        buf.writeByte(dir);
        buf.writeByte(state);
        buf.writeShort((int) Math.round(doorAmount * 1000.0D));
        buf.writeByte(fault);
        buf.writeInt(trips);
        buf.writeInt(shaftMissing);
        buf.writeInt(cellIndex);
        buf.writeByte(targetSlot);
        buf.writeBoolean(structureOk);
        // ★ 层数先夹再写：调用方（服务端）已经保证 ≤ MAX_FLOORS，
        //   但这里再夹一次，是为了让 toBytes/fromBytes 这一对**自己**就是安全的，
    //   不依赖任何调用方的纪律。
        int n = Math.min(floors.length, ElevatorSpec.MAX_FLOORS);
        buf.writeByte(n);
        for (int i = 0; i < n; i++) {
            buf.writeInt(floors[i]);
        }
        buf.writeInt(callMask);
    }

    /**
     * 从字节流读回来。
     * <p>
     * ★★★ 长度一律当<b>不可信</b>：先 {@code Math.min}/{@code Math.max} 夹到合法区间，
     * 再按夹过的值去读。原样 {@code buf.readInt()} 一个"层数 = 200"的包会让客户端
     * 越界抛异常 —— 而这条路径在客户端 tick 里，表现是<b>一收到包就崩</b>。
     */
    public static ElevatorSnapshot fromBytes(ByteBuf buf) {
        int version = buf.readByte() & 0xFF;
        int type = buf.readByte();
        double pos = buf.readInt() / 1000.0D;
        double vel = buf.readInt() / 1000.0D;
        int dir = buf.readByte();
        int state = buf.readByte();
        double doorAmount = (buf.readShort() & 0xFFFF) / 1000.0D;
        int fault = buf.readByte();
        int trips = buf.readInt();
        int shaftMissing = buf.readInt();
        int cellIndex = buf.readInt();
        int targetSlot = buf.readByte();
        boolean structureOk = buf.readBoolean();
        int raw = buf.readByte() & 0xFF;
        int n = Math.min(raw, ElevatorSpec.MAX_FLOORS);
        int[] f = new int[n];
        for (int i = 0; i < n; i++) {
            f[i] = buf.readInt();
        }
        int callMask = buf.readInt();

        // 越界值夹回：网络包可能来自旧版本客户端 / 被改过的客户端
        type = (type < 0 || type >= ElevatorSpec.TYPE_COUNT) ? ElevatorSpec.TYPE_PASSENGER : type;
        dir = dir > 0 ? 1 : (dir < 0 ? -1 : 0);
        state = (state < 0 || state > ElevatorSpec.ST_FAULT) ? ElevatorSpec.ST_IDLE : state;
        fault = (fault < 0 || fault > ElevatorSpec.FAULT_OVERLOAD) ? ElevatorSpec.FAULT_NONE : fault;
        doorAmount = doorAmount < 0.0D ? 0.0D : (doorAmount > 1.0D ? 1.0D : doorAmount);
        if (targetSlot >= n) {
            targetSlot = -1;
        }
        return new ElevatorSnapshot(version, type, pos, vel, dir, state, doorAmount, fault,
                trips, shaftMissing, cellIndex, targetSlot, f, callMask, structureOk);
    }
}

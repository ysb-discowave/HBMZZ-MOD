package com.rsaudio.network;

import com.rsaudio.dmr.DmrCore;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/**
 * 反应堆状态的"只读快照"。
 * <p>
 * 为什么要单独一个类，而不是让 {@link PacketDmrSync} 自己带二十几个字段：
 * <ol>
 *   <li>服务端每 5 tick 推一次，字段多了 <b>toBytes / fromBytes 极易写歪</b>（少写一个 double、
 *       顺序调个个儿，编译期全都不报错，进游戏才表现成"温度永远是 0"）；集中在一个类里
 *       一读一写挨着放，肉眼能对得上；</li>
 *   <li>界面要读的就是这一坨，客户端存一份快照正好当界面数据源。</li>
 * </ol>
 * <p>
 * ★ 它故意<b>不住在 {@code com.rsaudio.dmr} 包里</b>：那个包是零依赖内核，
 * {@code tools/check_dmr_core.py} 会拿纯 javac 编译它 —— 一旦让内核包 import netty，
 * 那条自检链路就断了。
 */
public class DmrSnapshot {

    // ---- 状态读数
    public int state;
    public String stateName = "unknown";
    public double tempK;
    public double integrity;
    public double avgFuel;
    public double catalyzerEff;
    public int loadPct;
    public int outputKw;
    public int alarm;
    public int subsystems;
    /** 熔毁阶段 1/2；非熔毁时为 0 */
    public int phase;
    /** 当前阶段剩余窗口（tick） */
    public int phaseTimer;
    /**
     * ★ 这里<b>故意没有</b>停机码。
     * <p>
     * 停机码只存在于服务端的内核里，玩家必须去设施里把那张便签找回来才念得出。
     * 只要它进过任何一个下行包，客户端就有办法读到（改个 mod 就行）——
     * 那"要跑一趟去找码"这件事立刻退化回"打开作弊客户端"。所以快照里根本没有这个字段。
     */
    public String implodeReason = "";
    public long ticks;
    public boolean structureOk;

    // ---- 停机前置条件（照 QSERF：温度 / EFSS 水位 / 功率激光 / 双钥匙）
    /** EFSS 应急消防水位 (%) */
    public double efss;
    /** 还在工作的功率激光根数 */
    public int lasers;
    /** 已经拔下来的燃料单元数（Phase 2 物理通道） */
    public int cellPulled;
    /** 换料通道是否已经锁死 */
    public boolean cellChannelLocked;
    /** 离"能按下停机按钮"还差几条前置条件。0 = 全齐 */
    public int shutdownBlockers;

    // ---- 功率订单
    public boolean orderActive;
    public int orderTargetPct;
    public int orderTimer;
    public int ordersDone;
    public int ordersFailed;
    public int ordersTotal;

    public DmrSnapshot() {
    }

    /** 在服务端抓一份（唯一会碰 {@link DmrCore} 的地方） */
    public static DmrSnapshot capture(DmrCore core, boolean structureOk) {
        DmrSnapshot s = new DmrSnapshot();
        s.state = core.getState();
        s.stateName = core.getStateName();
        s.tempK = core.getTempK();
        s.integrity = core.getIntegrity();
        s.avgFuel = core.getAvgFuel();
        s.catalyzerEff = core.getCatalyzerEfficiency();
        s.loadPct = core.getLoadPct();
        s.outputKw = core.outputKw();
        s.alarm = core.getAlarm();
        s.subsystems = core.getSubsystems();
        s.phase = core.getPhase();
        s.phaseTimer = core.getPhaseTimer();
        s.implodeReason = core.getImplodeReason() == null ? "" : core.getImplodeReason();
        s.ticks = core.getTicks();
        s.structureOk = structureOk;
        s.efss = core.getEfss();
        s.lasers = core.getLasers();
        s.cellPulled = core.getCellPulled();
        s.cellChannelLocked = core.isCellChannelLocked();
        s.shutdownBlockers = core.shutdownBlockers();
        s.orderActive = core.isOrderActive();
        s.orderTargetPct = core.getOrderTargetPct();
        s.orderTimer = core.getOrderTimer();
        s.ordersDone = core.getOrdersDone();
        s.ordersFailed = core.getOrdersFailed();
        s.ordersTotal = core.getOrdersTotal();
        return s;
    }

    // ------------------------------------------------------------ 序列化

    public void toBytes(ByteBuf buf) {
        buf.writeInt(state);
        ByteBufUtils.writeUTF8String(buf, safe(stateName));
        buf.writeDouble(tempK);
        buf.writeDouble(integrity);
        buf.writeDouble(avgFuel);
        buf.writeDouble(catalyzerEff);
        buf.writeInt(loadPct);
        buf.writeInt(outputKw);
        buf.writeInt(alarm);
        buf.writeInt(subsystems);
        buf.writeInt(phase);
        buf.writeInt(phaseTimer);
        ByteBufUtils.writeUTF8String(buf, safe(implodeReason));
        buf.writeLong(ticks);
        buf.writeBoolean(structureOk);
        // ★ 这一段是新加的字段。toBytes / fromBytes 顺序必须<b>逐行对齐</b> ——
        //   少写一个、或者两边的顺序调个个儿，编译期全都不报错，
        //   进游戏才表现成"水位永远是 0 / 激光永远 3 根"这种查半天的问题。
        buf.writeDouble(efss);
        buf.writeInt(lasers);
        buf.writeInt(cellPulled);
        buf.writeBoolean(cellChannelLocked);
        buf.writeInt(shutdownBlockers);
        buf.writeBoolean(orderActive);
        buf.writeInt(orderTargetPct);
        buf.writeInt(orderTimer);
        buf.writeInt(ordersDone);
        buf.writeInt(ordersFailed);
        buf.writeInt(ordersTotal);
    }

    public void fromBytes(ByteBuf buf) {
        state = buf.readInt();
        stateName = ByteBufUtils.readUTF8String(buf);
        tempK = buf.readDouble();
        integrity = buf.readDouble();
        avgFuel = buf.readDouble();
        catalyzerEff = buf.readDouble();
        loadPct = buf.readInt();
        outputKw = buf.readInt();
        alarm = buf.readInt();
        subsystems = buf.readInt();
        phase = buf.readInt();
        phaseTimer = buf.readInt();
        implodeReason = ByteBufUtils.readUTF8String(buf);
        ticks = buf.readLong();
        structureOk = buf.readBoolean();
        efss = buf.readDouble();
        lasers = buf.readInt();
        cellPulled = buf.readInt();
        cellChannelLocked = buf.readBoolean();
        shutdownBlockers = buf.readInt();
        orderActive = buf.readBoolean();
        orderTargetPct = buf.readInt();
        orderTimer = buf.readInt();
        ordersDone = buf.readInt();
        ordersFailed = buf.readInt();
        ordersTotal = buf.readInt();
    }

    public boolean hasSub(int bit) {
        return (subsystems & bit) != 0;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

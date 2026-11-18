package com.rsaudio.network;

import com.rsaudio.f9.F9Core;
import com.rsaudio.f9.F9Spec;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;

/**
 * F9（Foxtrot Mk.9 / Project Helios）反应堆状态的"只读快照"。
 * <p>
 * 和 {@link DmrSnapshot} 完全同构，理由也一样：服务端按自己的节奏推读数，
 * 客户端存一份当界面数据源。单独一个类是为了让 {@code toBytes / fromBytes}
 * 一读一写挨着放 —— 字段有二十来个，散在包里写，少写一个 double 编译期不报错，
 * 进游戏才表现成"压力永远是 0"。
 *
 * <h3>和 DMR 快照的三个区别</h3>
 * <ol>
 *   <li>控制棒是<b>一根一根</b>的读数（9 根），所以要多带一个数组 + 一个卡棒掩码；</li>
 *   <li>开关位更杂：4 个子系统 + 4 个转换器 + 4 个泄压阀，全部揉在 {@link #subsystems}
 *       这一个 int 里 —— 转换器和泄压阀也用位表示（见 {@link F9Spec#converterBit} /
 *       {@link F9Spec#valveBit}），所以取用一律走 {@link #converterOn(int)} 这种助手，
 *       不要把位移算在界面的绘制代码里；</li>
 *   <li>多一个<b>同步仪指针</b>（{@link #needle}）和上一次的指针（{@link #lastNeedle}）——
 *       同步时要判"指针有没有停在 -1~1"，只看一帧是不够的，界面得知道它有没有在动。</li>
 * </ol>
 * <p>
 * ★ 同样<b>不住在 {@code com.rsaudio.f9} 包里</b>：那个包是零依赖内核，
 * {@code tools/check_f9_core.py} 会拿纯 javac 编译它 —— 一旦让内核包 import netty，
 * 那条自检链路就断了。
 */
public class F9Snapshot {

    // ---- 状态机
    public int state;
    public String stateName = "unknown";
    public long ticks;
    /** 结构（3×3×3 外壳 + 堆芯 + 控制柜）是否成立。不成立时界面按钮一律点不动 */
    public boolean structureOk;

    // ---- 控制棒
    /** 每根棒的插入度（0..100）。索引和 {@code F9Spec} 里的编号一致 */
    public double[] rodInsert = new double[F9Spec.ROD_COUNT];
    /** 卡棒掩码：第 i 位 = 第 i 根卡住 */
    public int rodJammedMask;
    /** 9 根的平均插入度 —— 攻略里的 35% / 40% 说的都是这个数 */
    public double avgInsert;
    /** 当前选中的棒：{@link F9Core#ROD_SELECT_ALL} = 全局，0..8 = 单根 */
    public int selectedRod = F9Core.ROD_SELECT_ALL;
    /** 当前选中的分区（0..2） */
    public int selectedSector;
    /** 拉杆方向：+1 拔 / -1 插 / 0 静止 */
    public int transitDir;

    // ---- 四条物理量
    public double flux;
    public double psi;
    public double tempC;
    /** 热功率（kW，取整）。同步窗口是 39000..41000 */
    public int kw;
    /** 真正送到电网的功率；没同步时为 0 */
    public int outputKw;
    public double integrity;

    // ---- 同步仪
    public double needle;
    public double lastNeedle;
    public int syncTicks;
    /** 还差几条才能同步（0 = 可以按同步按钮了） */
    public int syncBlockers;

    // ---- 开关位
    public int subsystems;

    // ---- 事故
    public String scramReason = "";
    public int backlashTimer;
    public int regenTimer;
    public int regenerations;

    public F9Snapshot() {
    }

    /** 在服务端抓一份（唯一会碰 {@link F9Core} 的地方） */
    public static F9Snapshot capture(F9Core core, boolean structureOk) {
        F9Snapshot s = new F9Snapshot();
        s.state = core.getState();
        s.stateName = core.getStateName();
        s.ticks = core.getTicks();
        s.structureOk = structureOk;

        for (int i = 0; i < s.rodInsert.length; i++) {
            s.rodInsert[i] = core.getRodInsert(i);
            if (core.isRodJammed(i)) {
                s.rodJammedMask |= 1 << i;
            }
        }
        s.avgInsert = core.getAvgInsert();
        s.selectedRod = core.getSelectedRod();
        s.selectedSector = core.getSelectedSector();
        s.transitDir = core.getTransitDir();

        s.flux = core.getFlux();
        s.psi = core.getPsi();
        s.tempC = core.getTempC();
        s.kw = core.getKwInt();
        s.outputKw = core.outputKw();
        s.integrity = core.getIntegrity();

        s.needle = core.getNeedle();
        s.lastNeedle = core.getLastNeedle();
        s.syncTicks = core.getSyncTicks();
        s.syncBlockers = core.syncBlockers();

        s.subsystems = core.getSubsystems();

        s.scramReason = core.getScramReason() == null ? "" : core.getScramReason();
        s.backlashTimer = core.getBacklashTimer();
        s.regenTimer = core.getRegenTimer();
        s.regenerations = core.getRegenerations();
        return s;
    }

    // ------------------------------------------------------------ 查询助手

    /** 某个开关位是不是开着 */
    public boolean hasSub(int bit) {
        return (subsystems & bit) != 0;
    }

    /** 第 i 个转换器活塞是否接着（= 在发电） */
    public boolean converterOn(int i) {
        return hasSub(F9Spec.converterBit(i));
    }

    /** 第 i 个泄压阀是否开着 */
    public boolean valveOn(int i) {
        return hasSub(F9Spec.valveBit(i));
    }

    /** 第 i 根控制棒是否卡住 */
    public boolean rodJammed(int i) {
        return i >= 0 && i < F9Spec.ROD_COUNT && (rodJammedMask & (1 << i)) != 0;
    }

    /** 接上的转换器个数 */
    public int converterCount() {
        int n = 0;
        for (int i = 0; i < F9Spec.CONVERTER_COUNT; i++) {
            if (converterOn(i)) {
                n++;
            }
        }
        return n;
    }

    /** 开着的泄压阀个数 */
    public int valveCount() {
        int n = 0;
        for (int i = 0; i < F9Spec.VALVE_COUNT; i++) {
            if (valveOn(i)) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------ 序列化

    public void toBytes(ByteBuf buf) {
        buf.writeInt(state);
        ByteBufUtils.writeUTF8String(buf, safe(stateName));
        buf.writeLong(ticks);
        buf.writeBoolean(structureOk);

        buf.writeInt(rodInsert.length);
        for (double v : rodInsert) {
            buf.writeDouble(v);
        }
        buf.writeInt(rodJammedMask);
        buf.writeDouble(avgInsert);
        buf.writeInt(selectedRod);
        buf.writeInt(selectedSector);
        buf.writeInt(transitDir);

        buf.writeDouble(flux);
        buf.writeDouble(psi);
        buf.writeDouble(tempC);
        buf.writeInt(kw);
        buf.writeInt(outputKw);
        buf.writeDouble(integrity);

        buf.writeDouble(needle);
        buf.writeDouble(lastNeedle);
        buf.writeInt(syncTicks);
        buf.writeInt(syncBlockers);

        buf.writeInt(subsystems);

        ByteBufUtils.writeUTF8String(buf, safe(scramReason));
        buf.writeInt(backlashTimer);
        buf.writeInt(regenTimer);
        buf.writeInt(regenerations);
    }

    public void fromBytes(ByteBuf buf) {
        state = buf.readInt();
        stateName = ByteBufUtils.readUTF8String(buf);
        ticks = buf.readLong();
        structureOk = buf.readBoolean();

        int n = buf.readInt();
        // ★ 长度不信任：包是可以被伪造的，数组长度直接来自网络。
        //   超界就退回规格长度，宁可少画几根棒也不能越界写崩客户端。
        if (n != F9Spec.ROD_COUNT) {
            n = F9Spec.ROD_COUNT;
        }
        rodInsert = new double[n];
        for (int i = 0; i < n; i++) {
            rodInsert[i] = buf.readDouble();
        }
        rodJammedMask = buf.readInt();
        avgInsert = buf.readDouble();
        selectedRod = buf.readInt();
        selectedSector = buf.readInt();
        transitDir = buf.readInt();

        flux = buf.readDouble();
        psi = buf.readDouble();
        tempC = buf.readDouble();
        kw = buf.readInt();
        outputKw = buf.readInt();
        integrity = buf.readDouble();

        needle = buf.readDouble();
        lastNeedle = buf.readDouble();
        syncTicks = buf.readInt();
        syncBlockers = buf.readInt();

        subsystems = buf.readInt();

        scramReason = ByteBufUtils.readUTF8String(buf);
        backlashTimer = buf.readInt();
        regenTimer = buf.readInt();
        regenerations = buf.readInt();
    }

    /** 读某一根棒的插入度，越界给 100（= 全插）—— 界面永远不该因为越界而崩 */
    public double rod(int i) {
        return i >= 0 && i < rodInsert.length ? rodInsert[i] : F9Spec.ROD_INSERT_SHUTDOWN;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}

/*
 * HBMZZ - control panel and monitor extensions for HBM's Nuclear Tech.
 * Copyright (C) 2026 Yang Sibo (ysb-discowave)
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * See NOTICE.md for third-party provenance.
 */

package com.hbmzz.rbmk;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端的 RBMK 事件记录，喂给两个传感器方块。
 *
 * <p>和 DFC 的 {@code DfcBlastWatch} 同一个道理：判定只在这张表里做一次，方块
 * 只读结果。但 RBMK 有<strong>两种</strong>事件，而且语义不同：</p>
 *
 * <h3>爆炸：瞬态</h3>
 * <p>爆炸只发生一次。RBMK 起爆走的是 {@code world.createExplosion}（见
 * {@code RbmkEffects.nuclear}），那会向 Forge 发 {@code ExplosionEvent.Detonate}。
 * 这里监听到爆炸后，再问一句「爆点附近有没有反应堆柱子」——离爆心
 * {@code rbmkSensor.coreRange} 以内有任意一根柱子，就记一次。否则这是别处的
 * TNT，不算 RBMK。记录之后按「满格按住 → 逐级衰减 → 归零」的包络输出，跟
 * DFC 完全一样。</p>
 *
 * <h3>跳棒：持续状态</h3>
 * <p>跳棒不是一次事件，而是<strong>持续一分钟的状态</strong>：控制棒在 WARMUP
 * 阶段整个倒计时里反复跳。它没有 Forge 事件——是 {@code RbmkSurge} 自己每 tick
 * 往柱子的 {@code leafia$damage} 写字段驱动的。所以这里不靠事件，而是由
 * {@code RbmkSurge} 在状态转换的两个拐点上显式通知：</p>
 * <ul>
 *   <li>IDLE → WARMUP：{@link #onRodJumpStart}，开始跳了；</li>
 *   <li>WARMUP → SPENT：{@link #onRodJumpEnd}，反应堆封顶、跳棒停了。</li>
 * </ul>
 * <p>在这两个拐点之间，跳棒传感器输出满格；封顶的瞬间归零。它<strong>不做衰减
 * 包络</strong>——跳棒本身就是持续状态，包络会骗人说「快停了」而实际上还在跳。</p>
 *
 * <h3>为什么是内存态</h3>
 * <p>两张表都只在一个事件的生命周期内有意义（爆炸十几秒、跳棒一分钟）。写进存档
 * 只会留下一条过期记录，下次进游戏传感器先莫名其妙响一段——那是伪造事故。所以
 * 世界卸载、服务器停止时清空。</p>
 */
public final class RbmkEventWatch {

    /** 每个维度只留最近一次爆炸：信号是「刚炸了」，不是「炸过几次」。 */
    private static final Map<Integer, Blast> BLAST = new HashMap<>();

    /** 每个维度此刻是否跳棒中，以及正在跳的那台反应堆的位置（供右键读数算距离）。 */
    private static final Map<Integer, RodJump> ROD = new HashMap<>();

    /** 事件总线要的是实例；整张表是静态的，实例不带状态。 */
    public static final RbmkEventWatch INSTANCE = new RbmkEventWatch();

    private RbmkEventWatch() {
    }

    /** 一次 RBMK 爆炸的记录。{@code source} 只用于右键读数，不参与判定。 */
    public static final class Blast {
        public final int dim;
        public final long tick;
        public final double x;
        public final double y;
        public final double z;
        public final String source;

        Blast(int dim, long tick, double x, double y, double z, String source) {
            this.dim = dim;
            this.tick = tick;
            this.x = x;
            this.y = y;
            this.z = z;
            this.source = source;
        }

        public double distanceTo(double px, double py, double pz) {
            double dx = px - this.x;
            double dy = py - this.y;
            double dz = pz - this.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** 一台正在跳棒的反应堆。只记它的位置；「在跳」这件事本身就是表里的条目。 */
    public static final class RodJump {
        public final int dim;
        public final double x;
        public final double y;
        public final double z;

        RodJump(int dim, double x, double y, double z) {
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public double distanceTo(double px, double py, double pz) {
            double dx = px - this.x;
            double dy = py - this.y;
            double dz = pz - this.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    // ------------------------------------------------------------- detection

    /**
     * 兜底路径：任何爆炸，只要爆点附近有一根反应堆柱子，就算一次 RBMK 爆炸。
     *
     * <p>不查破坏清单、改用「爆点到最近柱子的距离」，是因为 RBMK 的核爆在反应堆
     * <em>顶部</em>发生（见 {@code RbmkSurge.launch}），而柱子在被炸掉之前的
     * 破坏清单里未必都齐；距离判定对「柱子被这次爆炸摧毁」这一帧天然稳定，也和
     * DFC 的 {@code nearCore} 是同一套逻辑。</p>
     *
     * <p>hbmzz 自己的蒸汽爆炸不走这里判——它们在自己的起爆点显式调
     * {@link #onBlast}，见下方说明。这个事件处理器是兜底：基础模组自己的炸毁
     * 爆炸、以及距离判定能盖到的其它爆炸，仍然靠它接住。</p>
     */
    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        World world = event.getWorld();
        if (world == null || world.isRemote || !HBMZZConfig.rbmkSensorEnabled || !RbmkParts.available()) {
            return;
        }
        if (event.getExplosion() == null) {
            return;
        }
        net.minecraft.util.math.Vec3d at = event.getExplosion().getPosition();
        double nearest = nearestColumnDistance(world, at.x, at.y, at.z);
        int range = HBMZZConfig.rbmkSensorCoreRange;
        if (nearest < 0 || (range > 0 && nearest > range)) {
            return;
        }
        record(world, at.x, at.y, at.z, "RBMK 核爆");
    }

    /**
     * 一次 RBMK 蒸汽爆炸：由 {@code RbmkSurge.launch} 在掀盖起爆的同一 tick 显式上报。
     *
     * <p>为什么不走 {@link #onExplosion} 的距离判定：掀盖起爆点在反应堆<em>顶部</em>
     * 上方一截，离柱子的垂直距离经常超出 {@code rbmkSensor.coreRange}，靠「爆点找
     * 柱子」去猜会漏判。爆炸发生那一刻由起爆方自己报，坐标、来源都已知，判定不
     * 依赖距离。和跳棒传感器一样，这是拐点显式通知而非事件旁路。</p>
     *
     * <p>{@code world} 判空、{@code source} 可空（缺省写「RBMK 核爆」）。同维度
     * 只留最近一次——后到的爆炸会覆盖先到的，信号是「刚炸了」而非「炸过几次」。</p>
     */
    public static void onBlast(World world, double x, double y, double z, String source) {
        if (world == null || world.isRemote || !HBMZZConfig.rbmkSensorEnabled) {
            return;
        }
        record(world, x, y, z, source == null || source.isEmpty() ? "RBMK 核爆" : source);
    }

    /**
     * 跳棒开始：反应堆从 IDLE 进到 WARMUP，控制棒要开始跳了。
     * 由 {@code RbmkSurge} 在状态转换的同一 tick 调用，只此一处。
     */
    public static void onRodJumpStart(World world, BlockPos reactor) {
        if (world == null || world.isRemote || !HBMZZConfig.rbmkSensorEnabled) {
            return;
        }
        int dim = world.provider.getDimension();
        ROD.put(dim, new RodJump(dim,
                reactor.getX() + 0.5D, reactor.getY() + 0.5D, reactor.getZ() + 0.5D));
        if (HBMZZ.logger != null) {
            HBMZZ.logger.debug("RBMK rod jump recorded in dim {}", dim);
        }
    }

    /** 跳棒结束：反应堆封顶、跳棒停了（WARMUP → SPENT）。同维度只留一台，清掉即可。 */
    public static void onRodJumpEnd(World world) {
        if (world == null || world.isRemote) {
            return;
        }
        ROD.remove(world.provider.getDimension());
    }

    /** 世界卸载：两张表都是按维度存的内存状态。 */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world != null && !world.isRemote) {
            int dim = world.provider.getDimension();
            BLAST.remove(dim);
            ROD.remove(dim);
        }
    }

    /**
     * 服务器停了，清空两张表。
     *
     * <p>★ 同 DFC：{@code FMLServerStoppedEvent} 不是 {@code Event} 子类，
     * 不能写 {@code @SubscribeEvent}（注册时会抛异常让整个模组起不来）。做成
     * 普通方法，由 {@link com.hbmzz.HBMZZ#serverStopping} 显式调用。</p>
     */
    public void onServerStopped() {
        BLAST.clear();
        ROD.clear();
    }

    // ---------------------------------------------------------------- lookup

    /** 该维度最近一次 RBMK 爆炸，没有则 {@code null}（供右键读数）。 */
    public static Blast lastBlast(World world) {
        if (world == null || world.isRemote) {
            return null;
        }
        return BLAST.get(world.provider.getDimension());
    }

    /** 该维度此刻跳棒中的反应堆，没有则 {@code null}（供右键读数）。 */
    public static RodJump rodJump(World world) {
        if (world == null || world.isRemote) {
            return null;
        }
        return ROD.get(world.provider.getDimension());
    }

    /**
     * 爆炸传感器：这个坐标此刻该输出几级红石。
     *
     * <p>纯函数，同 DFC——只依赖「爆炸在哪一 tick、多远」和配置。爆炸是一次性的，
     * 所以走满格按住 + 逐级衰减的包络。</p>
     */
    public static int blastStrengthAt(World world, BlockPos pos) {
        if (world == null || world.isRemote || pos == null || !HBMZZConfig.rbmkSensorEnabled) {
            return 0;
        }
        Blast blast = BLAST.get(world.provider.getDimension());
        if (blast == null) {
            return 0;
        }
        double distance = blast.distanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        int range = HBMZZConfig.rbmkSensorRange;
        if (range > 0 && distance > range) {
            return 0;
        }
        return strengthFor(world.getTotalWorldTime() - blast.tick);
    }

    /**
     * 跳棒传感器：这个坐标此刻该输出几级红石。
     *
     * <p>跳棒是<strong>持续状态</strong>：只要该维度有一台反应堆在跳、且本方块在
     * 它 {@code range} 以内，就输出满格；封顶的瞬间条目被移除，直接归零。不做衰减
     * 包络——跳棒不是「快停了」，它就是要么在跳、要么停了。</p>
     */
    public static int jumpStrengthAt(World world, BlockPos pos) {
        if (world == null || world.isRemote || pos == null || !HBMZZConfig.rbmkSensorEnabled) {
            return 0;
        }
        RodJump rod = ROD.get(world.provider.getDimension());
        if (rod == null) {
            return 0;
        }
        double distance = rod.distanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        int range = HBMZZConfig.rbmkSensorRange;
        if (range > 0 && distance > range) {
            return 0;
        }
        return Math.max(1, Math.min(15, HBMZZConfig.rbmkSensorStrength));
    }

    /**
     * 信号包络：先满格按住，再一级一级往下掉，最后归零。
     *
     * <p>与 DFC 同一套阶梯：红石只有 0..15 这十六级，写成离散的边沿比连续函数好
     * 预测——每个接收端的取整都一致。</p>
     */
    public static int strengthFor(long ageTicks) {
        int max = Math.max(1, Math.min(15, HBMZZConfig.rbmkSensorStrength));
        if (ageTicks < 0) {
            return 0;
        }
        if (ageTicks < HBMZZConfig.rbmkSensorHoldTicks) {
            return max;
        }
        long decayAge = ageTicks - HBMZZConfig.rbmkSensorHoldTicks;
        int decay = HBMZZConfig.rbmkSensorDecayTicks;
        if (decay <= 0) {
            return 0;
        }
        if (decayAge >= decay) {
            return 0;
        }
        int steps = Math.max(1, max - 1);
        long stepTicks = Math.max(1L, decay / steps);
        int fallen = (int) Math.min(steps, decayAge / stepTicks);
        return Math.max(1, max - fallen);
    }

    // --------------------------------------------------------------- private

    private static void record(World world, double x, double y, double z, String source) {
        int dim = world.provider.getDimension();
        BLAST.put(dim, new Blast(dim, world.getTotalWorldTime(), x, y, z, source));
        if (HBMZZ.logger != null) {
            HBMZZ.logger.debug("RBMK blast recorded in dim {} at {} {} {} ({})", dim,
                    (int) x, (int) y, (int) z, source);
        }
    }

    /** 离爆点最近的反应堆柱子的距离；一个都没有则返回 -1。 */
    private static double nearestColumnDistance(World world, double x, double y, double z) {
        double best = -1.0D;
        for (TileEntity te : world.loadedTileEntityList) {
            if (!RbmkParts.isColumn(te)) {
                continue;
            }
            BlockPos pos = te.getPos();
            double dx = pos.getX() + 0.5D - x;
            double dy = pos.getY() + 0.5D - y;
            double dz = pos.getZ() + 0.5D - z;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (best < 0 || dist < best) {
                best = dist;
            }
        }
        return best;
    }
}

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

package com.hbmzz.blast;

import com.hbmzz.HBMZZ;
import com.hbmzz.HBMZZConfig;
import com.hbmzz.dfc.DFCReader;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.world.ExplosionEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * 服务端的 DFC 爆炸记录。
 *
 * <h3>为什么要记在服务端的一张表里，而不是每个传感器自己看</h3>
 * <p>爆炸只有一次，而传感器可能有很多块：装在门口一块、装在控制室一块、装在
 * 应急柴油机旁边一块。如果每块自己判断，就要各自找一遍核心、各自算一遍距离，
 * 而且一旦有一块因为区块没加载错过了那一 tick，它后面永远补不回来。所以判定
 * <strong>只在服务端做一次</strong>，写进一张按维度分组的表；方块只读这张表，
 * 读的是「什么时候炸的、炸在哪」，而不是「我看见了一次爆炸」。</p>
 *
 * <h3>检测的是什么，不是什么</h3>
 * <p>不是猜温度跳变——拉一根控制棒、倒一桶冷却液、黑客平板改一个字段，温度都会跳，
 * 那种启发式会天天误报。这里认的是两条<strong>已发生的事实</strong>：</p>
 * <ol>
 *   <li><strong>NTM 的核爆实体在 DFC 核心旁边入世。</strong>DFC 熔毁时是核心自己
 *       spawn 出 {@code EntityNukeExplosionMK3}（1.12.2 的 NTM 与它的 CE / 诅咒版
 *       共用这条路径），实体入世是一个 Forge 事件，比任何轮询都准，且零开销。</li>
 *   <li><strong>任何爆炸把核心方块本身列进了破坏清单。</strong>这是给「不 spawn 核爆
 *       实体」的爆炸留的兜底：原版 TNT、别家模组的爆炸、我们自己的核弹都把受影响
 *       方块交给 {@code ExplosionEvent.Detonate}，只要那颗 DFC 自己在清单里，就等于
 *       「DFC 被炸了」——不管是谁炸的。</li>
 * </ol>
 *
 * <p>两条都要求爆炸点附近确实有一颗 {@code com.hbm.tileentity.machine.TileEntityCore}，
 * 所以「在核电站另一头引爆一枚普通核弹」不会把传感器点着。这个半径是
 * {@code dfcSensor.coreRange}，设成 0 就退化成「本维度任何核爆都算」。</p>
 *
 * <h3>为什么是瞬态，不存盘</h3>
 * <p>记录只在一个信号的生命周期里有意义——最长也就十几秒。写进 {@code WorldSavedData}
 * 意味着存档里留一条过期记录，下次进游戏传感器会先莫名其妙输出一段信号；那不是
 * 「续上」，那是<strong>伪造一次事故</strong>。所以这张表是内存里的，世界卸载与服务器
 * 停止时清空。</p>
 */
public final class DfcBlastWatch {

    /** 一个维度只留最近一次：信号是「刚炸了」，不是「炸过几次」。 */
    private static final Map<Integer, Blast> LAST = new HashMap<>();

    /**
     * 事件总线要的是一个实例。整张表是静态的，实例本身不带状态——挂在
     * {@code HBMZZ.preInit} 的就是这一个，别在别处再 new 第二份。
     */
    public static final DfcBlastWatch INSTANCE = new DfcBlastWatch();

    private DfcBlastWatch() {
    }

    /** 一次 DFC 爆炸的记录。{@code source} 只用于右键读数，不参与任何判定。 */
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

    // ------------------------------------------------------------- detection

    /**
     * 核爆实体入世 = 有人按下了核弹，或者一台 DFC 熔毁了。
     *
     * <p>两类都在这里经过，所以要再问一句「爆炸点上有没有 DFC 核心」。问不到的
     * 话说明这是别处的核弹，交回原处继续炸它的。</p>
     */
    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        World world = event.getWorld();
        if (world == null || world.isRemote || !HBMZZConfig.dfcSensorEnabled) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity == null) {
            return;
        }
        // 按简单类名认，不按包名：附属把它挪到自己的包里也照样认得出。
        String name = entity.getClass().getSimpleName();
        if (!name.startsWith("EntityNukeExplosionMK")) {
            return;
        }
        BlockPos at = new BlockPos(entity.posX, entity.posY, entity.posZ);
        if (!nearCore(world, at)) {
            return;
        }
        record(world, entity.posX, entity.posY, entity.posZ, "核爆实体");
    }

    /**
     * 兜底：任何爆炸，只要把 DFC 核心自己列进了破坏清单，就算数。
     *
     * <p>这条同时覆盖「附属换掉了 DFC 的爆炸实现」和「有人拿 TNT 手动拆核心」——
     * 后者本来就是一次爆炸摧毁了核心，传感器响得没毛病。</p>
     */
    @SubscribeEvent
    public void onExplosion(ExplosionEvent.Detonate event) {
        World world = event.getWorld();
        if (world == null || world.isRemote || !HBMZZConfig.dfcSensorEnabled) {
            return;
        }
        if (event.getAffectedBlocks() == null || event.getExplosion() == null) {
            return;
        }
        net.minecraft.util.math.Vec3d at = event.getExplosion().getPosition();
        BlockPos center = new BlockPos(at.x, at.y, at.z);
        TileEntity core = findCore(world, center);
        if (core == null) {
            return;
        }
        // 一整个核爆的破坏清单可以到十万格，所以先找到核心再查它在不在清单里，
        // 而不是反着来：对着十万格逐个 getTileEntity 会把这一 tick 直接吃掉。
        for (BlockPos pos : event.getAffectedBlocks()) {
            if (core.getPos().equals(pos)) {
                record(world, at.x, at.y, at.z, "爆炸摧毁核心");
                return;
            }
        }
    }

    /** 世界卸载：这张表是按维度存的内存状态，不清就会串到下一个存档里去。 */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world != null && !world.isRemote) {
            LAST.remove(world.provider.getDimension());
        }
    }

    /**
     * 服务器停了，把这张表清空。
     *
     * <p>★ 这里<b>不能</b>写 {@code @SubscribeEvent}。{@code FMLServerStoppedEvent}
     * 继承的是 {@code FMLStateEvent}，不是 {@code Event}；{@code EventBus.register}
     * 会当场校验参数类型并抛
     * {@code IllegalArgumentException: ... takes a argument that is not an Event class}，
     * 而这条校验发生在 {@code preInit} 的注册调用里 —— 结果是<b>整个模组加载失败、
     * 游戏进不去</b>，且报错指向注册那一行而不是这里。
     *
     * <p>FML 状态事件只能走 {@code @Mod.EventHandler}。所以这里做成普通无参方法，
     * 由 {@link com.hbmzz.HBMZZ#serverStopping} 显式调用（那里本来就在给
     * HackRegistry 与 DfcAutoShutdown 收尾）。
     */
    public void onServerStopped() {
        LAST.clear();
    }

    // ---------------------------------------------------------------- lookup

    /** 该维度最近一次 DFC 爆炸，没有则 {@code null}。 */
    public static Blast last(World world) {
        if (world == null || world.isRemote) {
            return null;
        }
        return LAST.get(world.provider.getDimension());
    }

    /**
     * 这个坐标此刻应该输出几级红石信号。
     *
     * <p>纯函数：只依赖「爆炸发生在哪一 tick、多远」和配置，不读世界、不写状态，
     * 所以同一 tick 里问一百块传感器一百次，答案都一致——这正是「全厂同时响」
     * 的实现方式，不需要任何广播。</p>
     */
    public static int strengthAt(World world, BlockPos pos) {
        if (world == null || world.isRemote || pos == null || !HBMZZConfig.dfcSensorEnabled) {
            return 0;
        }
        Blast blast = LAST.get(world.provider.getDimension());
        if (blast == null) {
            return 0;
        }
        double distance = blast.distanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        if (HBMZZConfig.dfcSensorRange > 0 && distance > HBMZZConfig.dfcSensorRange) {
            return 0;
        }
        return strengthFor(world.getTotalWorldTime() - blast.tick);
    }

    /**
     * 信号包络：先满格按住，再一级一级往下掉，最后归零。
     *
     * <p>之所以是阶梯而不是线性小数：红石本身只有 0..15 这十六级，中间值根本不存在。
     * 写成「每 {@code stepTicks} tick 掉一级」之后，接收端看到的是一串离散的边沿，
     * 中继器、比较器、红石粉的行为都能预测；写成连续函数反而要在每个接收端取整一次，
     * 每处的取整还未必一样。</p>
     */
    public static int strengthFor(long ageTicks) {
        int max = Math.max(1, Math.min(15, HBMZZConfig.dfcSensorStrength));
        if (ageTicks < 0) {
            return 0;
        }
        if (ageTicks < HBMZZConfig.dfcSensorHoldTicks) {
            return max;
        }
        long decayAge = ageTicks - HBMZZConfig.dfcSensorHoldTicks;
        int decay = HBMZZConfig.dfcSensorDecayTicks;
        if (decay <= 0) {
            return 0;
        }
        if (decayAge >= decay) {
            return 0;
        }
        // 满格之后还剩 max-1 级要掉（15 → 14 ... → 1），掉完就到 0。
        int steps = Math.max(1, max - 1);
        long stepTicks = Math.max(1L, decay / steps);
        int fallen = (int) Math.min(steps, decayAge / stepTicks);
        return Math.max(1, max - fallen);
    }

    // --------------------------------------------------------------- private

    private static void record(World world, double x, double y, double z, String source) {
        int dim = world.provider.getDimension();
        LAST.put(dim, new Blast(dim, world.getTotalWorldTime(), x, y, z, source));
        if (HBMZZ.logger != null) {
            HBMZZ.logger.debug("DFC blast recorded in dim {} at {} {} {} ({})", dim,
                    (int) x, (int) y, (int) z, source);
        }
    }

    /** {@code anyBlast} 打开时不做核心判定，本维度任何核爆都算。 */
    private static boolean nearCore(World world, BlockPos at) {
        if (HBMZZConfig.dfcSensorAnyBlast) {
            return true;
        }
        return findCore(world, at) != null;
    }

    private static TileEntity findCore(World world, BlockPos at) {
        return DFCReader.findCore(world, at, HBMZZConfig.dfcSensorCoreRange);
    }
}

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
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 服务端的 DFC 坍缩监视。
 *
 * <h3>它记的是什么</h3>
 * <p>坍缩不是一次爆炸，而是<strong>一段持续的状态</strong>：DFC 核心一旦进入
 * 熔毁倒计时，{@code getDFCCollapsing()} 就大于 0，一直挂到爆炸那一刻才归零。
 * 传感器要的是「它<strong>正在</strong>坍缩」这件事本身——开始坍缩时点亮、爆炸结束
 * 时熄灭，中间全程满格。它不做衰减包络：包络会骗人说「快结束了」，可实际上核还在
 * 倒数，该满格的时候就得满格。</p>
 *
 * <h3>为什么必须自己轮询，而不像 DfcBlastWatch 那样等事件</h3>
 * <p>爆炸有 Forge 事件（核爆实体入世、破坏清单），坍缩没有——它是核心自己每 tick
 * 往内部字段里倒数，外部没有任何事件可挂。而且现有的 {@code DfcTstx} 虽然也在扫
 * 坍缩核心，但那套扫描被 {@code DFC-TSTX} 这条 gamerule 门控着，规则关着就不扫，
 * 不能拿来当传感器的数据源。所以这里开一个<strong>独立的服务端 ticker</strong>，
 * 每 {@code dfcCollapseSensor.scanTicks} tick 扫一次本维度的已加载方块实体，把
 * 「此刻正在坍缩的核心坐标」记进一张按维度分组的表。判定只在这一处做一次，方块
 * 只读结果，跟 DFC/RBMK 两个爆炸传感器是同一个道理。</p>
 *
 * <h3>为什么是内存态、不存盘</h3>
 * <p>这张表只在一个坍缩的生命周期内有意义（最长也就是倒计时那几十秒）。写进存档
 * 会留下一条过期记录，下次进游戏传感器先莫名其妙输出满格信号——那是伪造事故。
 * 所以世界卸载、服务器停止时清空。</p>
 */
public final class DfcCollapseWatch {

    /** 维度号 → 此刻正在坍缩的核心坐标集合（坐标存成 long，不持有 BlockPos 引用）。 */
    private static final Map<Integer, Set<Long>> COLLAPSING = new HashMap<>();

    /** 每个世界各自还剩几 tick 才再扫一次。弱引用键：世界卸载后条目自动失效。 */
    private static final WeakHashMap<World, Integer> SCAN = new WeakHashMap<>();

    /**
     * 事件总线要的是一个实例。整张表是静态的，实例本身不带状态——挂在
     * {@code HBMZZ.preInit} 的就是这一个，别在别处再 new 第二份。
     */
    public static final DfcCollapseWatch INSTANCE = new DfcCollapseWatch();

    private DfcCollapseWatch() {
    }

    // ------------------------------------------------------------- detection

    /**
     * 每 tick 末（服务端）按配置的间隔扫一次本维度的已加载方块实体。
     *
     * <p>用 {@code WeakHashMap} 存各世界的扫描倒计时而不是一个全局计数器：不同维度
     * 是各自 tick 的，同一个世界卸载后条目自然脱落，不会把别人的节奏带跑。</p>
     */
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.world == null || event.world.isRemote) {
            return;
        }
        if (!HBMZZConfig.dfcCollapseSensorEnabled) {
            return;
        }
        World world = event.world;
        int scanIn = SCAN.getOrDefault(world, 0);
        if (--scanIn > 0) {
            SCAN.put(world, scanIn);
            return;
        }
        SCAN.put(world, Math.max(1, HBMZZConfig.dfcCollapseSensorScanTicks));
        try {
            rescan(world);
        } catch (Throwable broken) {
            // 一轮没扫完不值得把这一 tick 拖垮；下一轮还会再试。
        }
    }

    /** 扫一次本维度：把此刻正在坍缩的核心坐标重建成一张新集合，没变就不换手。 */
    private static void rescan(World world) {
        int dim = world.provider.getDimension();
        Set<Long> now = new HashSet<>();
        List<TileEntity> list = world.loadedTileEntityList;
        for (int i = 0; i < list.size(); i++) {
            TileEntity te;
            try {
                te = list.get(i);
            } catch (IndexOutOfBoundsException raced) {
                // 别的线程正在这张列表上增删（别的模组的 TE 在 tick）：停手，下轮重扫。
                break;
            }
            if (te == null || te.getPos() == null) {
                continue;
            }
            if (DFCReader.isCore(te) && DFCReader.collapsing(te)) {
                now.add(te.getPos().toLong());
            }
        }
        Set<Long> before = COLLAPSING.get(dim);
        if (before != null && before.equals(now)) {
            // 状态没变：沿用旧集合，避免每轮都换一张新表、让读端白跑一次对账。
            return;
        }
        if (now.isEmpty()) {
            COLLAPSING.remove(dim);
            if (HBMZZ.logger != null) {
                HBMZZ.logger.debug("DFC collapse cleared in dim {}", dim);
            }
        } else {
            COLLAPSING.put(dim, now);
            if (HBMZZ.logger != null) {
                HBMZZ.logger.debug("DFC collapse active in dim {} ({} core(s))", dim, now.size());
            }
        }
    }

    /** 世界卸载：这张表是按维度存的内存状态，不清就会串到下一个存档里去。 */
    @SubscribeEvent
    public void onWorldUnload(WorldEvent.Unload event) {
        World world = event.getWorld();
        if (world != null && !world.isRemote) {
            COLLAPSING.remove(world.provider.getDimension());
            SCAN.remove(world);
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
     * 游戏进不去</b>。所以做成普通无参方法，由 {@link com.hbmzz.HBMZZ#serverStopping}
     * 显式调用。</p>
     */
    public void onServerStopped() {
        COLLAPSING.clear();
        SCAN.clear();
    }

    // ---------------------------------------------------------------- lookup

    /** 该维度此刻是否有任何 DFC 核心在坍缩（供右键读数与判定是否点亮）。 */
    public static boolean collapsing(World world) {
        if (world == null || world.isRemote) {
            return false;
        }
        Set<Long> cores = COLLAPSING.get(world.provider.getDimension());
        return cores != null && !cores.isEmpty();
    }

    /**
     * 这个坐标此刻该输出几级红石。
     *
     * <p>纯函数：只依赖「本维度有没有正在坍缩的核心、离本方块多近」和配置，不读世界、
     * 不写状态。只要范围内有一颗核心在坍缩就返回满格，否则 0——没有中间档，也没有衰减。</p>
     */
    public static int collapseStrengthAt(World world, BlockPos pos) {
        if (world == null || world.isRemote || pos == null || !HBMZZConfig.dfcCollapseSensorEnabled) {
            return 0;
        }
        Set<Long> cores = COLLAPSING.get(world.provider.getDimension());
        if (cores == null || cores.isEmpty()) {
            return 0;
        }
        int range = HBMZZConfig.dfcCollapseSensorRange;
        if (range > 0) {
            boolean near = false;
            double px = pos.getX() + 0.5D;
            double py = pos.getY() + 0.5D;
            double pz = pos.getZ() + 0.5D;
            double maxSq = (double) range * (double) range;
            for (long packed : cores) {
                // BlockPos.toLong 的逆：低 26 位 x、中 12 位 y、高 26 位 z。
                int cx = (int) ((packed >> 38) & 0x3FFFFFFL);
                int cy = (int) ((packed >> 26) & 0xFFFL);
                int cz = (int) (packed & 0x3FFFFFFL);
                double dx = cx + 0.5D - px;
                double dy = cy + 0.5D - py;
                double dz = cz + 0.5D - pz;
                if (dx * dx + dy * dy + dz * dz <= maxSq) {
                    near = true;
                    break;
                }
            }
            if (!near) {
                return 0;
            }
        }
        return Math.max(1, Math.min(15, HBMZZConfig.dfcCollapseSensorStrength));
    }
}

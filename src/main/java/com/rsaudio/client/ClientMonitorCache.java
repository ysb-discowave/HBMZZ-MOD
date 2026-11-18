package com.rsaudio.client;

import com.rsaudio.tile.TileMonitorCamera;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端侧的"摄像头名册"。
 *
 * <h3>为什么要名册</h3>
 * 监控控制台要回答的问题是"3 号频道有哪几个摄像头、离我最近的是哪个"。
 * 服务端不发这种东西 —— 频道就是个普通整数，名册完全可以从
 * {@code world.loadedTileEntityList}（所有已加载的方块实体）里现场筛出来，
 * 天然跟着加载/卸载走，不需要任何同步包，也不会有"服务端说有个摄像头、
 * 客户端根本没加载那一块所以看不见"的错位。
 *
 * <h3>为什么缓存</h3>
 * 界面每帧都要用名册；一个大厅里几千个方块实体，每帧全扫一遍是白烧 CPU。
 * 每 {@link #RESCAN_TICKS} 刻扫一次就够了 —— 摄像头不会自己长出来。
 */
public final class ClientMonitorCache {

    /** 每几刻重扫一次名册 */
    private static final int RESCAN_TICKS = 10;

    private static final List<TileMonitorCamera> ALL = new ArrayList<TileMonitorCamera>();
    private static final Map<Integer, List<TileMonitorCamera>> BY_CHANNEL =
            new HashMap<Integer, List<TileMonitorCamera>>();

    private static long lastScanTick = Long.MIN_VALUE;
    private static World lastWorld = null;

    private ClientMonitorCache() {
    }

    /** 每客户端 tick 调一次（挂在客户端代理的 tick 处理里） */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        World world = mc.world;
        if (world == null) {
            if (lastWorld != null) {
                clear();
            }
            return;
        }
        if (world != lastWorld) {
            clear();
            lastWorld = world;
        }
        long now = world.getTotalWorldTime();
        if (lastScanTick != Long.MIN_VALUE && now - lastScanTick < RESCAN_TICKS) {
            return;
        }
        lastScanTick = now;
        rescan(world);
    }

    /** 界面刚打开时想立刻拿到最新名册（不想等最多 10 刻） */
    public static void refreshNow() {
        World world = Minecraft.getMinecraft().world;
        if (world == null) {
            clear();
            return;
        }
        lastWorld = world;
        lastScanTick = world.getTotalWorldTime();
        rescan(world);
    }

    private static void rescan(World world) {
        ALL.clear();
        BY_CHANNEL.clear();
        List<TileEntity> loaded = world.loadedTileEntityList;
        for (int i = 0; i < loaded.size(); i++) {
            TileEntity te = loaded.get(i);
            if (!(te instanceof TileMonitorCamera)) {
                continue;
            }
            TileMonitorCamera camera = (TileMonitorCamera) te;
            // 正在被移除的方块实体 pos 可能已经是 null，别在这儿 NPE
            if (camera.getPos() == null || camera.getWorld() == null) {
                continue;
            }
            ALL.add(camera);
        }
        // 按坐标排序，名册顺序稳定 —— 界面上不会因为扫描顺序变化而跳来跳去
        Collections.sort(ALL, new java.util.Comparator<TileMonitorCamera>() {
            @Override
            public int compare(TileMonitorCamera a, TileMonitorCamera b) {
                BlockPos pa = a.getPos();
                BlockPos pb = b.getPos();
                if (pa.getX() != pb.getX()) {
                    return Integer.compare(pa.getX(), pb.getX());
                }
                if (pa.getZ() != pb.getZ()) {
                    return Integer.compare(pa.getZ(), pb.getZ());
                }
                return Integer.compare(pa.getY(), pb.getY());
            }
        });
        for (TileMonitorCamera camera : ALL) {
            Integer key = Integer.valueOf(camera.getChannel());
            List<TileMonitorCamera> list = BY_CHANNEL.get(key);
            if (list == null) {
                list = new ArrayList<TileMonitorCamera>();
                BY_CHANNEL.put(key, list);
            }
            list.add(camera);
        }
    }

    public static void clear() {
        ALL.clear();
        BY_CHANNEL.clear();
        lastScanTick = Long.MIN_VALUE;
        lastWorld = null;
    }

    public static List<TileMonitorCamera> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** 某个频道上的所有摄像头（含被停用的），按坐标序 */
    public static List<TileMonitorCamera> onChannel(int channel) {
        List<TileMonitorCamera> list = BY_CHANNEL.get(Integer.valueOf(channel));
        return list == null ? Collections.<TileMonitorCamera>emptyList() : list;
    }

    /** 某个频道上有几个"启用中"的摄像头 */
    public static int enabledCount(int channel) {
        int count = 0;
        for (TileMonitorCamera camera : onChannel(channel)) {
            if (camera.isEnabled()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 某个频道上离 {@code from} 最近的<b>启用中</b>的摄像头。
     * <p>
     * "最近"这条规则是频道机制的全部：控制台不需要和摄像头配对，
     * 装在同一层楼的监控自然就是最近的那个；想换摄像头就把它停用掉。
     *
     * @return 找不到就返回 null
     */
    public static TileMonitorCamera nearestOn(int channel, BlockPos from) {
        TileMonitorCamera best = null;
        double bestDistance = Double.MAX_VALUE;
        for (TileMonitorCamera camera : onChannel(channel)) {
            if (!camera.isEnabled()) {
                continue;
            }
            BlockPos pos = camera.getPos();
            if (pos == null) {
                continue;
            }
            double distance = from == null ? 0.0D : pos.distanceSq(from);
            if (from != null && distance > 256.0D * 256.0D) {
                // 太远了：不是"这栋楼的监控"，别去渲染别人的画面
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = camera;
            }
        }
        return best;
    }

    /** 频道号（1..16）上有没有任何摄像头注册 */
    public static boolean hasAny(int channel) {
        return !onChannel(channel).isEmpty();
    }
}

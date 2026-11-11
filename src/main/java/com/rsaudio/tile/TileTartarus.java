package com.rsaudio.tile;

import com.rsaudio.block.BlockTartarus;
import com.rsaudio.block.BlockTartarusHatch;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 塔塔鲁斯的方块实体（挂在中央控制器那一格上）。
 *
 * <h3>形状</h3>
 * 控制器在圆心，建造后在周围按 {@code 半径²} 生成一圈圆形舱盖格（不含圆心，圆心是控制器自己）。
 * 关着时整片都是实心地板；开了就整片沉进竖井、可通过。
 *
 * <h3>开 / 关（缓慢）</h3>
 * {@link #openAmount} 是 0..1 的开合进度，每 tick 朝意图（{@link #wantOpen}）逼近，
 * 速度 {@link #SPEED} ≈ 5 秒走完全程——这就是"缓慢开合"。
 * 客户端另有一份 {@link #clientAnim} 自己插值，保证动画连贯、且不会每 tick 都发网络包。
 * <p>
 * 碰撞（能不能走上去 / 掉下去）在<b>进度过半</b>（{@code openAmount >= 0.5}）时才翻转：
 * 这样舱盖是"开到一半才让位"，不会出现刚一动就能穿模掉下去的违和感。
 *
 * <h3>关门防夹</h3>
 * 关门时如果舱盖下（含下沉深度的圆形范围）还有实体，先记下来等它离开再关（{@link #pendingClose}），
 * 直接关会把人/物夹死在方块里。
 */
public class TileTartarus extends TileEntity implements ITickable {

    public static final int MIN_RADIUS = 2;
    /**
     * ★ 半径上限 8 → 16 → <b>32</b>：直径 64 格。
     *
     * <p>两轮反馈都是"太小了"：先是 8（直径 16 格）不够，提到 16（直径 32 格）
     * 之后仍然不够。核电站 / 船坞这种尺度需要的不是"一口大井"，
     * 而是"能给一艘小艇当舱盖"的东西 —— 那得是 60 格量级，所以上限给到 32。
     *
     * <h4>★ 上限 32 的规模与成本（都已在别处配好，改这里时别忘了）</h4>
     * <ol>
     *   <li><b>舱盖格数按平方长</b>：r=32 约 <b>3200 格</b>（r=16 是 796 格）。
     *       建造 / 拆除会连写 3200 次 {@code setBlockState}，约 <b>几十毫秒的一次性开销</b> ——
     *       可接受，但这是"点一下会顿一小下"，不是瞬时。所以：
     *       <b>绝不可以在 tick 里遍历全环</b>（{@link #update()} 里就没有），
     *       也不要在建造时对每格做额外查询。</li>
     *   <li><b>{@link #getRenderBoundingBox()}</b> 的水平范围会到 ±34 格（69×69）——
     *       它只是剔除用的粗盒，不逐方块计算，这个大小没问题；
     *       但如果哪天想改成"精确包围盒"，r=32 时每帧扫 3200 格会很痛。</li>
     *   <li><b>TESR 的 {@code VIEW_RANGE}</b> 必须随半径长：直径 64 格的盘，
     *       站在盘上根本看不到形，得退到 200 格外。{@code TartarusRenderer}
     *       已改成 {@code VIEW_RANGE + radius * 4}。</li>
     *   <li><b>舱盖板块数</b>也要跟着放开（见 {@code TartarusRenderer.MAX_SEGMENTS}），
     *       否则大半径下每块会变得又宽又糙，白放大。</li>
     * </ol>
     * <b>兼容性</b>：上限只影响<b>以后新建</b>的塔塔鲁斯。已有存档的半径存在 NBT 里，
     * 读取走 {@link #clampRadius}，只要不超过新上限就原样保留 —— 所以
     * <b>调大上限永远不会弄坏存档里的现有舱盖</b>（这条已经全量扫描确认过：
     * 所有存档里目前都没有塔塔鲁斯实体，根本没有迁移问题）。
     */
    public static final int MAX_RADIUS = 32;
    /**
     * ★ 默认半径 6（直径 12 格）保持不变。
     *
     * <p>默认值**故意不跟着上限一起放大**：默认是"玩家第一次放下来就看到的"。
     * 12 格直径在一间普通机房里已经很有压迫感；直接给 32 格（直径 64）会
     * 一放下来就吃掉整个建筑，而且建造要写 3200 个方块、玩家还没想清楚就要拆。
     * 想要多大的，在设置界面里按 "+" 调到满意为止 —— 上限已经敞开。
     */
    public static final int DEFAULT_RADIUS = 6;

    /** 开启时舱盖下沉多少格（纯视觉 + 让顶部可通行） */
    public static final float DESCENT = 1.5F;
    /** 每 tick 开合进度变化量（0.01 → 满开/满关约 100 刻 = 5 秒，缓慢） */
    public static final float SPEED = 0.01F;

    private int radius = DEFAULT_RADIUS;
    private boolean built = false;
    private boolean wantOpen = false;
    /** 服务端权威的开合进度 0..1（0=关死，1=全开） */
    private float openAmount = 0.0F;
    /** 因为舱盖下有人/物而推迟的关门 */
    private boolean pendingClose = false;
    /** 关闭途中因盘下有实体而暂停时的状态去重旗标（不存档，只防 status 每 tick 刷） */
    private boolean closeHeldAnnounced = false;
    private boolean removing = false;
    private String status = "";

    /** 客户端专用：TESR 自平滑插值用的当前显示进度（不写入存档） */
    public transient float clientAnim = 0.0F;
    /** 客户端专用：上次插值时间戳（毫秒） */
    public transient long lastAnimTime = 0L;

    // -------- 参数 / 状态查询（服务端 + 客户端共用） --------

    public int getRadius() {
        return radius;
    }

    public boolean isBuilt() {
        return built;
    }

    public boolean wantsOpen() {
        return wantOpen;
    }

    public float getOpenAmount() {
        return openAmount;
    }

    /** 已经开到底 */
    public boolean isOpen() {
        return openAmount > 0.99F;
    }

    /** 开合途中 */
    public boolean isMoving() {
        if (!built) {
            return false;
        }
        return Math.abs(openAmount - (wantOpen ? 1.0F : 0.0F)) > 0.001F;
    }

    public String getStatus() {
        return status == null ? "" : status;
    }

    /** 这个坐标是不是本舱盖覆盖的格子（中心 + 圆环） */
    public boolean contains(BlockPos p) {
        if (p.getY() != pos.getY()) {
            return false;
        }
        int dx = p.getX() - pos.getX();
        int dz = p.getZ() - pos.getZ();
        if (dx == 0 && dz == 0) {
            return true;
        }
        return dx * dx + dz * dz <= radius * radius;
    }

    private BlockPos cellAt(int dx, int dz) {
        return pos.add(dx, 0, dz);
    }

    // -------- 交互 --------

    public void toggleOpen(EntityPlayer player) {
        if (!built) {
            setStatus("还没建造舱盖");
            return;
        }
        requestOpen(!wantOpen, player);
    }

    public void requestOpen(boolean desired, EntityPlayer player) {
        if (!built) {
            setStatus("还没建造舱盖");
            return;
        }
        if (desired == wantOpen) {
            pendingClose = false;
            return;
        }
        if (!desired && entitiesInside()) {
            // 关门会把人夹住 → 先记下来，等舱盖下空了再关
            pendingClose = true;
            setStatus("舱盖下还有实体，等它离开再关");
            if (player != null) {
                player.sendStatusMessage(new TextComponentString("舱盖下还有实体，等它离开再关"), true);
            }
            return;
        }
        pendingClose = false;
        this.wantOpen = desired;
        markDirty();
        syncToClient();
        setStatus(desired ? "正在开启…" : "正在关闭…");
    }

    /**
     * 半径只在未建造时改；建造后想改尺寸要先拆除。
     * <p>
     * ★★★ 这条不变式原来只写在注释里，而且只在<b>一个调用点</b>
     *   （{@code PacketSetTartarus}）上被检查过一次 —— 和防爆门 applyCells 那个坑同款：
     *   <b>同一条不变式写在两处、只写对一处</b>。现在把门禁放进 tile 自己，谁调都不越界。
     *   <p>
     *   万一被绕过，后果不是"改不动"，而是<b>世界里的圆盘和 radius 字段对不上</b>：
     *   {@link #removeHatch} 是按 {@code ringCells()}（用当前 radius 现算）去清格的 ——
     *   半径被改过，它清的就是"新半径的圆盘"，而世界里铺的是旧的，
     *   于是一大片舱盖格清不掉，控制器拆了、残骸留在世界里。
     *   （2026-09-22 实测存档里有 1870 格无主舱盖、控制器 0 格。成因未完全坐实 ——
     *    所以 {@code BlockTartarusHatch} 那边补了"无主舱盖可以整片清掉"这条出路。）
     * <p>
     * ★ 通式与防爆门那条一样：<b>当"某物长什么样"是算出来的，"它当前是什么样"就必须
     *   真的存下来或者被锁住</b> —— 只锁注释不锁代码，就是在给玩家埋残骸。
     *
     * @return true = 改成功了；false = 已经建造，拒绝（先把舱盖拆了）
     */
    public boolean setRadius(int r) {
        if (built) {
            setStatus("舱盖已经建造，改半径要先拆掉");
            return false;
        }
        int want = clampRadius(r);
        if (want == radius) {
            return true;
        }
        this.radius = want;
        markDirty();
        syncToClient();
        return true;
    }

    public static int clampRadius(int r) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, r));
    }

    /** 界面 / 网络包用的简短描述 */
    public String describe() {
        return "塔塔鲁斯 · 半径 " + radius + (built ? " · 已建造" : " · 未建造");
    }

    // -------- 建造 / 拆除 --------

    public String build(EntityPlayer player) {
        if (built) {
            // 已经有舱盖了：先拆再建
            removeHatch(false);
        }
        List<BlockPos> cells = ringCells();
        for (BlockPos cell : cells) {
            IBlockState s = world.getBlockState(cell);
            if (!(s.getBlock().isAir(s, world, cell) || s.getBlock() instanceof BlockTartarusHatch)) {
                return "空间被占用，舱盖建不起来（清理掉再试）";
            }
        }
        for (BlockPos cell : cells) {
            world.setBlockState(cell, BlockTartarusHatch.stateFor(false), 3);
        }
        this.built = true;
        this.wantOpen = false;
        this.openAmount = 0.0F;
        this.pendingClose = false;
        setBlockOpen(false);
        markDirty();
        syncToClient();
        return null;
    }

    /**
     * 拆掉整扇舱盖。
     * @param includeController true = 连控制器一起拆（拆舱盖格时调用）；
     *                         false = 只拆舱盖格（拆控制器本身时调用，控制器由 breakBlock 处理）
     */
    public void removeHatch(boolean includeController) {
        if (removing) {
            return;
        }
        removing = true;
        try {
            if (world != null && !world.isRemote) {
                for (BlockPos cell : ringCells()) {
                    if (world.getBlockState(cell).getBlock() instanceof BlockTartarusHatch) {
                        world.setBlockToAir(cell);
                    }
                }
                if (includeController && world.getBlockState(pos).getBlock() instanceof BlockTartarus) {
                    world.setBlockToAir(pos);
                }
            }
        } finally {
            removing = false;
        }
        this.built = false;
        this.wantOpen = false;
        this.openAmount = 0.0F;
        this.pendingClose = false;
        markDirty();
        if (world != null && !world.isRemote) {
            syncToClient();
        }
    }

    private List<BlockPos> ringCells() {
        List<BlockPos> cells = new ArrayList<BlockPos>();
        int r = radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) {
                    continue; // 圆心是控制器，不铺舱盖格
                }
                if (dx * dx + dz * dz <= r * r) {
                    cells.add(cellAt(dx, dz));
                }
            }
        }
        return cells;
    }

    // -------- 每 tick（仅服务端） --------

    @Override
    public void update() {
        if (world == null || world.isRemote || removing) {
            return;
        }
        if (!built) {
            this.wantOpen = false;
            return;
        }
        boolean changed = false;
        float target = wantOpen ? 1.0F : 0.0F;
        if (openAmount < target) {
            openAmount = Math.min(target, openAmount + SPEED);
            changed = true;
        } else if (openAmount > target) {
            // ★★ 关闭途中盘扫过的体积里进来实体 → 原地暂停。
            //   只在"发起关门那一刻"查一次是不够的：关闭要好几秒，
            //   生物完全可以在中途掉进竖井 / 溜进盘的扫掠范围，
            //   盘继续上抬就会把它压在盘底和井底之间 —— "被门夹死"。
            if (entitiesInside()) {
                if (!closeHeldAnnounced) {
                    closeHeldAnnounced = true;
                    setStatus("关闭暂停：舱盖下有实体，等它离开");
                }
            } else {
                closeHeldAnnounced = false;
                openAmount = Math.max(target, openAmount - SPEED);
                changed = true;
            }
        }
        // 越过 0.5 阈值 → 切换方块的可通行状态（碰撞 + 视觉让位）
        boolean wantPassable = openAmount >= 0.5F;
        if (isPassableState() != wantPassable) {
            setBlockOpen(wantPassable);
            changed = true;
        }
        // 推迟的关门：舱盖下空了就补上。
        // ★★ 这条分支以前写反了：pendingClose=true 只会出现在 wantOpen=true
        //   （还开着、等实体走）的时候，旧条件 !wantOpen 永远为假 →
        //   推迟的关门永远不补，舱盖卡死在开位。想关是 wantOpen=false。
        if (wantOpen && pendingClose && !entitiesInside()) {
            pendingClose = false;
            closeHeldAnnounced = false;
            wantOpen = false;
            setStatus("正在关闭…");
            changed = true;
        }
        if (changed) {
            if (Math.abs(openAmount - target) < 1e-4F) {
                setStatus(wantOpen ? "已开启" : "已关闭");
                closeHeldAnnounced = false;
            }
            markDirty();
            syncToClient();
        }
    }

    /** 当前方块层面记录的可通行状态（从控制器方块状态读） */
    private boolean isPassableState() {
        IBlockState s = world.getBlockState(pos);
        return s.getBlock() instanceof BlockTartarus && s.getValue(BlockTartarus.OPEN);
    }

    /** 把控制器和所有舱盖格的 OPEN 状态统一设成 passable */
    private void setBlockOpen(boolean passable) {
        IBlockState cs = world.getBlockState(pos);
        if (cs.getBlock() instanceof BlockTartarus && cs.getValue(BlockTartarus.OPEN) != passable) {
            world.setBlockState(pos, cs.withProperty(BlockTartarus.OPEN, passable), 3);
        }
        for (BlockPos cell : ringCells()) {
            IBlockState s = world.getBlockState(cell);
            if (s.getBlock() instanceof BlockTartarusHatch && s.getValue(BlockTartarusHatch.OPEN) != passable) {
                world.setBlockState(cell, s.withProperty(BlockTartarusHatch.OPEN, passable), 3);
            }
        }
    }

    /**
     * 舱盖下（盘关闭时会<b>扫过</b>的体积）有没有实体。
     *
     * <p>★ 竖直范围是 {@code y-DESCENT .. y+0.55}：盘底最高只抬到 {@code y+0.02}、
     * 顶面到 {@code y+0.52}，这个范围里的实体才会被盘真的压到。
     * 旧版上沿写到 {@code y+1.0}，把<b>站在舱盖地板上</b>的生物也算成"舱盖下有实体"
     * （它们的脚刚好在 y+1 那个平面上）—— 关门请求永远被拒，舱盖一直开着，
     * 玩家看到的就是"这扇门怎么老是关不上 / 一直开着"。
     */
    private boolean entitiesInside() {
        if (world == null) {
            return false;
        }
        int r = radius;
        AxisAlignedBB box = new AxisAlignedBB(
                pos.getX() - r, pos.getY() - DESCENT, pos.getZ() - r,
                pos.getX() + r + 1.0D, pos.getY() + 0.55D, pos.getZ() + r + 1.0D);
        return !world.getEntitiesWithinAABB(Entity.class, box).isEmpty();
    }

    private void setStatus(String s) {
        this.status = s == null ? "" : s;
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // -------- 存档 / 同步 --------

    @Override
    public void readFromNBT(NBTTagCompound c) {
        super.readFromNBT(c);
        this.radius = clampRadius(c.hasKey("Radius") ? c.getInteger("Radius") : DEFAULT_RADIUS);
        this.built = c.getBoolean("Built");
        this.wantOpen = c.getBoolean("Open");
        this.openAmount = c.hasKey("OpenAmount") ? c.getFloat("OpenAmount") : (wantOpen ? 1.0F : 0.0F);
        this.status = c.getString("Status");
        this.pendingClose = false;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound c) {
        super.writeToNBT(c);
        c.setInteger("Radius", radius);
        c.setBoolean("Built", built);
        c.setBoolean("Open", wantOpen);
        c.setFloat("OpenAmount", openAmount);
        c.setString("Status", status == null ? "" : status);
        return c;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Radius", radius);
        tag.setBoolean("Built", built);
        tag.setBoolean("Open", wantOpen);
        tag.setFloat("OpenAmount", openAmount);
        tag.setString("Status", status == null ? "" : status);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.radius = clampRadius(tag.hasKey("Radius") ? tag.getInteger("Radius") : DEFAULT_RADIUS);
        this.built = tag.getBoolean("Built");
        this.wantOpen = tag.getBoolean("Open");
        this.openAmount = tag.hasKey("OpenAmount") ? tag.getFloat("OpenAmount") : (wantOpen ? 1.0F : 0.0F);
        this.status = tag.getString("Status");
        this.clientAnim = this.openAmount;
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    // -------- 渲染包围盒（TESR 大圆盘要扩大，否则转开视角会被裁剪） --------

    /**
     * ★ 这个盒子的作用只是"别在区块剔除时把这扇舱盖裁掉"，不是精确包围盒
     * （精确包围盒的代价是每帧扫一大片方块，得不偿失）。
     *
     * <p>所以留足余量：水平方向 {@code radius + 2} 格 —— 多出来的 2 格是给
     * 外沿那一圈盖边和危险条用的；竖直方向要同时覆盖
     * <b>盘的厚度（约半格）</b> 和 <b>开合的下沉量 {@link #DESCENT}</b>，
     * 再加一层余量。
     *
     * <p>★ 半径上限提到 16 之后这里尤其不能省：{@code radius + 1} 在 r=16 时只有
     * 17 格，盘的最外沿（半径 16 格）刚好压在边界上，一转头就会出现
     * "圆盘边缘被切掉一块"那种只有目视才能发现的 bug。
     * 这个盒子会变成 36×36×5 格 —— 它只是个**剔除用的粗包围盒**，
     * 不参与逐方块计算，所以这个大小是可以接受的。
     */
    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        int r = radius + 2;
        double d = DESCENT + 2.0D;
        return new AxisAlignedBB(pos.getX() - r, pos.getY() - d, pos.getZ() - r,
                pos.getX() + r + 1.0D, pos.getY() + d, pos.getZ() + r + 1.0D);
    }
}

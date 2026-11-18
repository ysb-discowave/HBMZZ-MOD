package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockStructureLoader;
import com.rsaudio.structure.StructureFile;
import com.rsaudio.structure.StructureStorage;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.UUID;

/**
 * 结构加载器的方块实体：保存"用哪个结构、贴在哪个偏移"，并负责粘贴作业。
 *
 * <h3>为什么要分 tick 贴</h3>
 * 一个 64×64×64 的选区有 26 万格。如果在一次 tick 里全部 {@code setBlockState}，
 * 服务端会直接卡住好几秒（还会连带触发一大堆邻居更新）。
 * 所以粘贴拆成三个阶段，每个阶段每 tick 只做有限格：
 * <ol>
 *     <li><b>清空</b>：把目标长方体刷成空气（flag 2：只同步客户端，不触发邻居连锁）</li>
 *     <li><b>放置</b>：放非空气方块（flag 2）</li>
 *     <li><b>收尾</b>：恢复方块实体 + 逐格 {@code checkLight}/邻居更新（这一步才让红石、光照生效）</li>
 * </ol>
 * 这样即使结构很大，服务器也只是"忙几秒"，而不是"卡死"。
 *
 * <h3>自激保护</h3>
 * 加载器自己会被红石触发；如果贴出来的结构里正好有个红石块紧贴加载器，
 * 收尾时就会产生一次新的上升沿，于是无限循环。所以 {@link #finishJob} 结束时
 * 强制把红石状态同步成"当前值"，把这个假上升沿吞掉。
 */
public class TileStructureLoader extends TileEntity implements ITickable {

    /** 偏移范围（每轴 -16 ~ +16），够把结构贴到加载器上方/旁边了 */
    public static final int MAX_OFFSET = 16;

    private static final int CLEAR_PER_TICK = 4096;
    private static final int PLACE_PER_TICK = 2048;
    private static final int FINISH_PER_TICK = 4096;

    private static final int PHASE_CLEAR = 0;
    private static final int PHASE_PLACE = 1;
    private static final int PHASE_FINISH = 2;

    private static final int NBT_VERSION = 1;

    /** 结构名（不含扩展名） */
    private String structure = "";
    private int offX = 0;
    private int offY = 1;          // 默认贴在加载器正上方一格
    private int offZ = 0;

    private final RedstoneTrigger trigger = new RedstoneTrigger();
    private boolean initialized = false;

    /** 正在粘贴 */
    private boolean loading = false;
    private StructureFile job;
    private int phase = PHASE_CLEAR;
    private int cursor = 0;
    private int placed = 0;
    private int failed = 0;
    private String status = "";

    /** 被改过的坐标（收尾阶段用来重算光照/邻居），用 long 打包省内存 */
    private long[] changed = new long[1024];
    private int changedCount = 0;

    /** 是谁点的"加载"（完成时给他回一句话） */
    private UUID instigator;

    // ---------------------------------------------------------------- 参数

    public String getStructure() {
        return structure;
    }

    /** 服务端设置结构名 */
    public void setStructure(String name) {
        String value = StructureStorage.sanitizeName(name);
        if (value.equals(this.structure)) {
            return;
        }
        this.structure = value;
        markDirty();
        syncToClient();
    }

    public int getOffsetX() {
        return offX;
    }

    public int getOffsetY() {
        return offY;
    }

    public int getOffsetZ() {
        return offZ;
    }

    /** 服务端设置偏移（会按 {@link #MAX_OFFSET} 裁剪） */
    public void setOffset(int x, int y, int z) {
        int nx = clampOffset(x);
        int ny = clampOffset(y);
        int nz = clampOffset(z);
        if (nx == offX && ny == offY && nz == offZ) {
            return;
        }
        offX = nx;
        offY = ny;
        offZ = nz;
        markDirty();
        syncToClient();
    }

    public static int clampOffset(int value) {
        if (value < -MAX_OFFSET) {
            return -MAX_OFFSET;
        }
        return value > MAX_OFFSET ? MAX_OFFSET : value;
    }

    public boolean isLoading() {
        return loading;
    }

    public String getStatus() {
        return status;
    }

    /** 目标区域的起始角 */
    public BlockPos getOrigin() {
        return pos.add(offX, offY, offZ);
    }

    // ---------------------------------------------------------------- 加载

    /**
     * 用手里的设置加载一次。
     *
     * @param player 触发者（可为 null，例如红石触发），完成时会收到一条提示
     */
    public void loadNow(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return;
        }
        if (loading) {
            setStatus("正在加载中，等它贴完");
            return;
        }
        String name = StructureStorage.sanitizeName(structure);
        if (name.isEmpty()) {
            setStatus("还没有设置结构名");
            return;
        }
        StructureFile file = StructureStorage.load(name);
        if (file == null) {
            setStatus("找不到结构「" + name + "」，或者文件已损坏（看日志）");
            return;
        }
        BlockPos origin = getOrigin();
        BlockPos far = origin.add(file.sizeX - 1, file.sizeY - 1, file.sizeZ - 1);
        if (!world.isBlockLoaded(origin) || !world.isBlockLoaded(far)) {
            setStatus("目标区域有一部分不在已加载的区块里，请靠近一点或改偏移");
            return;
        }
        if (contains(origin, file, pos)) {
            setStatus("这个偏移会让结构盖住加载器本身，换个偏移");
            return;
        }

        this.job = file;
        this.phase = PHASE_CLEAR;
        this.cursor = 0;
        this.placed = 0;
        this.failed = 0;
        this.changedCount = 0;
        this.loading = true;
        this.instigator = player == null ? null : player.getUniqueID();
        setStatus("开始加载「" + name + "」" + file.dimensions() + "，" + file.blockCount + " 个方块");
        BlockStructureLoader.setActive(world, pos, true);
        markDirty();
        syncToClient();
        tellInstigator("开始加载「" + name + "」" + file.dimensions() + "，共 " + file.blockCount + " 个方块");
    }

    /** 结构的目标区域是否包含某个位置 */
    private static boolean contains(BlockPos origin, StructureFile file, BlockPos target) {
        return target.getX() >= origin.getX() && target.getX() < origin.getX() + file.sizeX
                && target.getY() >= origin.getY() && target.getY() < origin.getY() + file.sizeY
                && target.getZ() >= origin.getZ() && target.getZ() < origin.getZ() + file.sizeZ;
    }

    private void setStatus(String text) {
        this.status = text == null ? "" : text;
        markDirty();
        syncToClient();
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    private void tellInstigator(String message) {
        if (instigator == null || world == null || world.isRemote) {
            return;
        }
        for (EntityPlayer player : world.playerEntities) {
            if (player instanceof EntityPlayerMP && instigator.equals(player.getUniqueID())) {
                player.sendStatusMessage(new TextComponentString(message), false);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- 每 tick 作业

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        boolean powered = world.isBlockPowered(pos);
        if (!initialized) {
            // 第一 tick 只记录当前电平：不然"紧挨着拉杆放置"会立刻被当成一次上升沿
            initialized = true;
            trigger.reset(powered);
        } else if (trigger.step(powered) == RedstoneTrigger.Result.RISING) {
            loadNow(null);
        }
        if (job != null) {
            advance();
        }
    }

    private void advance() {
        BlockPos origin = getOrigin();
        switch (phase) {
            case PHASE_CLEAR: {
                int end = Math.min(job.volume(), cursor + CLEAR_PER_TICK);
                for (; cursor < end; cursor++) {
                    BlockPos target = origin.add(job.voxelOffset(cursor));
                    if (world.getBlockState(target).getBlock() != Blocks.AIR) {
                        world.setBlockState(target, Blocks.AIR.getDefaultState(), 2);
                        remember(target);
                    }
                }
                if (cursor >= job.volume()) {
                    phase = PHASE_PLACE;
                    cursor = 0;
                }
                return;
            }
            case PHASE_PLACE: {
                int end = Math.min(job.blockCount, cursor + PLACE_PER_TICK);
                for (; cursor < end; cursor++) {
                    IBlockState state = job.stateAt(cursor);
                    if (state == null) {
                        failed++;
                        continue;
                    }
                    BlockPos target = origin.add(job.blockOffset(cursor));
                    world.setBlockState(target, state, 2);
                    remember(target);
                    placed++;
                }
                if (cursor >= job.blockCount) {
                    phase = PHASE_FINISH;
                    cursor = 0;
                }
                return;
            }
            case PHASE_FINISH: {
                int end = Math.min(changedCount, cursor + FINISH_PER_TICK);
                for (; cursor < end; cursor++) {
                    BlockPos target = BlockPos.fromLong(changed[cursor]);
                    world.checkLight(target);
                    world.notifyNeighborsRespectDebug(target, world.getBlockState(target).getBlock(), false);
                }
                if (cursor >= changedCount) {
                    restoreTiles();
                    finishJob();
                }
                return;
            }
            default:
                // 兜底：状态不该出现，直接结束作业
                finishJob();
        }
    }

    /** 记录被改过的坐标，收尾阶段要用 */
    private void remember(BlockPos target) {
        if (changedCount >= changed.length) {
            int grown = Math.min(Math.max(changed.length * 2, 1024), 1 << 20);
            if (grown == changed.length) {
                return;                       // 到顶了就放弃记录，只是少刷一点光照
            }
            long[] bigger = new long[grown];
            System.arraycopy(changed, 0, bigger, 0, changedCount);
            changed = bigger;
        }
        changed[changedCount++] = target.toLong();
    }

    /**
     * 恢复方块实体（箱子里的东西、机器设置）。
     * <p>
     * 标签里的 {@code rx/ry/rz} 是保存时记下的相对坐标；这里把 x/y/z 改写成新位置，
     * 再让 {@link TileEntity#create} 反序列化 —— {@code readFromNBT} 会用 x/y/z 设自己的位置。
     */
    private void restoreTiles() {
        BlockPos origin = getOrigin();
        int restored = 0;
        for (NBTTagCompound source : job.tiles) {
            if (!source.hasKey("rx")) {
                continue;
            }
            BlockPos target = origin.add(source.getInteger("rx"), source.getInteger("ry"), source.getInteger("rz"));
            if (!world.isBlockLoaded(target)) {
                continue;
            }
            NBTTagCompound tag = source.copy();
            tag.setInteger("x", target.getX());
            tag.setInteger("y", target.getY());
            tag.setInteger("z", target.getZ());
            try {
                TileEntity tile = TileEntity.create(world, tag);
                if (tile == null) {
                    continue;                  // 方块实体类型没了（模组被删），跳过
                }
                world.setTileEntity(target, tile);
                tile.setWorld(world);
                tile.setPos(target);
                restored++;
            } catch (Throwable t) {
                RsAudio.warn("恢复方块实体失败 @" + target + ": " + t);
            }
        }
        if (restored > 0) {
            RsAudio.log("结构加载器恢复了 %d 个方块实体", restored);
        }
    }

    private void finishJob() {
        StructureFile done = job;
        BlockPos origin = getOrigin();
        if (done != null) {
            world.markBlockRangeForRenderUpdate(origin,
                    origin.add(done.sizeX - 1, done.sizeY - 1, done.sizeZ - 1));
        }
        String message = "加载完成：" + (done == null ? "" : done.dimensions() + "，")
                + "放了 " + placed + " 个方块"
                + (failed > 0 ? "；" + failed + " 个方块在当前环境里不存在，已跳过" : "");
        this.job = null;
        this.loading = false;
        this.phase = PHASE_CLEAR;
        this.cursor = 0;
        this.changedCount = 0;
        BlockStructureLoader.setActive(world, pos, false);
        // ★ 吞掉这次"因为贴出来一个红石源而产生的假上升沿"，否则会自己触发自己，无限加载
        trigger.reset(world.isBlockPowered(pos));
        setStatus(message);
        tellInstigator(message);
    }

    // ---------------------------------------------------------------- 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        this.structure = compound.getString("Structure");
        this.offX = clampOffset(compound.getInteger("OffX"));
        this.offY = clampOffset(compound.hasKey("OffY") ? compound.getInteger("OffY") : 1);
        this.offZ = clampOffset(compound.getInteger("OffZ"));
        this.status = compound.getString("Status");
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Version", NBT_VERSION);
        compound.setString("Structure", structure);
        compound.setInteger("OffX", offX);
        compound.setInteger("OffY", offY);
        compound.setInteger("OffZ", offZ);
        compound.setString("Status", status == null ? "" : status);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setString("Structure", structure);
        tag.setInteger("OffX", offX);
        tag.setInteger("OffY", offY);
        tag.setInteger("OffZ", offZ);
        tag.setString("Status", status == null ? "" : status);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.structure = tag.getString("Structure");
        this.offX = clampOffset(tag.getInteger("OffX"));
        this.offY = clampOffset(tag.hasKey("OffY") ? tag.getInteger("OffY") : 1);
        this.offZ = clampOffset(tag.getInteger("OffZ"));
        this.status = tag.getString("Status");
    }

    @Override
    public SPacketUpdateTileEntity getUpdatePacket() {
        return new SPacketUpdateTileEntity(pos, 1, getUpdateTag());
    }

    @Override
    public void onDataPacket(NetworkManager net, SPacketUpdateTileEntity pkt) {
        handleUpdateTag(pkt.getNbtCompound());
    }

    @Override
    public boolean shouldRefresh(World world, BlockPos pos, IBlockState oldState, IBlockState newState) {
        return oldState.getBlock() != newState.getBlock();
    }
}

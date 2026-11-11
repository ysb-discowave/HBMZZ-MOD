package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockRedstoneComputer;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.network.PacketProgramSync;
import com.rsaudio.redstone.asm.Assembler;
import com.rsaudio.redstone.asm.Cpu;
import com.rsaudio.redstone.asm.Program;
import com.rsaudio.util.Broadcaster;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/**
 * 红石计算机的方块实体：保存程序文本、驱动 {@link Cpu}、把输出推到世界上。
 *
 * <h3>为什么只存"文本"</h3>
 * 存档里只存程序源码；每次世界加载后再汇编一次。
 * 汇编结果（{@link Program}）是运行时缓存，不进存档 —— 这样即使以后指令集升级了，
 * 旧存档里的程序也会被重新汇编成新格式，不会因为字节码布局变了就读不出来。
 *
 * <h3>输出怎么生效</h3>
 * CPU 的输出只是六个寄存器。每 tick 跑完后和上一 tick 比一次，
 * 只有真的变了才 {@code notifyNeighborsOfStateChange} —— 不然每 tick 六次邻居通知，
 * 一个基地里摆几十台就会明显掉 TPS。
 *
 * <h3>调试面板</h3>
 * 界面打开时会告诉服务端"我在看着这台机器"（viewers），
 * 服务端每 5 tick 推一次 pc/寄存器/输出。关掉界面就停止推送 —— 不能一直往外发。
 */
public class TileRedstoneComputer extends TileEntity implements ITickable {

    /** 源码长度上限（要能被一个网络包装下；正常程序几百行足够） */
    public static final int MAX_SOURCE_CHARS = 12000;
    /** 每 tick 执行多少条指令（同时也是死循环的限速阀） */
    public static final int STEPS_PER_TICK = Cpu.DEFAULT_STEPS_PER_TICK;
    /** 调试面板推送间隔（刻） */
    private static final int VIEWER_INTERVAL = 5;
    /** 超过这个距离就不再推送调试数据（格） */
    private static final double VIEWER_RANGE = 64.0D;

    private static final int NBT_VERSION = 1;

    private String source = Assembler.DEFAULT_SOURCE;
    private final Cpu cpu = new Cpu();

    /** 源码 → 程序 的汇编是否已经做过 */
    private boolean compiled = false;
    /** 汇编错误（空 = 没有错误） */
    private String error = "";
    private int errorLine = 0;
    /** 上一次播报的文字，用来去重（同一句话在循环里只显示一次） */
    private String lastMessage = "";
    /** 上一次的输出快照，用来判断"要不要通知邻居" */
    private final int[] lastOutputs = new int[Cpu.FACE_COUNT];

    private final Set<UUID> viewers = new HashSet<UUID>();
    private int viewerTimer = 0;

    /** 程序运行中（存档里也记着，世界重载后会继续跑） */
    private boolean running = false;

    // ---------------------------------------------------------------- 程序

    public String getSource() {
        return source;
    }

    /** 服务端设置程序源码（会立刻重新汇编；如果本来在跑，就从头重新跑） */
    public void setSource(String text) {
        String value = text == null ? "" : text;
        if (value.length() > MAX_SOURCE_CHARS) {
            value = value.substring(0, MAX_SOURCE_CHARS);
        }
        boolean wasRunning = running;
        this.source = value;
        this.compiled = false;
        this.lastMessage = "";
        compile();
        if (wasRunning) {
            if (cpu.getProgram().isEmpty()) {
                running = false;
            } else {
                cpu.start();
            }
        }
        markDirty();
        pushFullSync();
        refreshRunningState();
    }

    /** 汇编（结果缓存在 cpu 里） */
    private void compile() {
        if (compiled) {
            return;
        }
        compiled = true;
        Assembler.Result result = Assembler.assemble(source);
        if (result.isOk()) {
            error = "";
            errorLine = 0;
            cpu.setProgram(result.program);
        } else {
            error = result.error;
            errorLine = result.errorLine;
            cpu.setProgram(Program.EMPTY);
            RsAudio.log("红石计算机程序有错：%s（第 %d 行）", result.error, result.errorLine);
        }
    }

    public boolean isRunning() {
        return running;
    }

    public String getError() {
        return error == null ? "" : error;
    }

    public int getErrorLine() {
        return errorLine;
    }

    public Cpu getCpu() {
        return cpu;
    }

    public int getOutputFor(int face) {
        return cpu.getOutput(face);
    }

    /** 快捷 运行/停止，返回开关之后的状态 */
    public boolean toggleRunning() {
        if (running) {
            stop();
        } else {
            run();
        }
        return running;
    }

    /** 运行（从头开始） */
    public void run() {
        compile();
        if (cpu.getProgram().isEmpty()) {
            running = false;
            refreshRunningState();
            return;
        }
        cpu.start();
        running = true;
        markDirty();
        refreshRunningState();
        pushFullSync();
    }

    /** 暂停：保留寄存器和输出（外面的电路保持现状，不会突然断电） */
    public void stop() {
        cpu.pause();
        running = false;
        markDirty();
        refreshRunningState();
        pushFullSync();
    }

    /** 复位：清空寄存器与输出 */
    public void resetProgram() {
        cpu.reset();
        running = false;
        clearOutputsAndNotify();
        markDirty();
        refreshRunningState();
        pushFullSync();
    }

    /** 复位到"没跑过"的状态，输出清零并把变化通知出去 */
    private void clearOutputsAndNotify() {
        boolean changed = false;
        for (int i = 0; i < Cpu.FACE_COUNT; i++) {
            if (lastOutputs[i] != 0) {
                changed = true;
            }
            lastOutputs[i] = 0;
        }
        cpu.clearOutputs();
        if (changed && world != null && !world.isRemote) {
            world.notifyNeighborsOfStateChange(pos, this.getBlockType(), false);
        }
    }

    /** 拆方块时松掉所有输出 */
    public void releaseOutputs() {
        running = false;
        clearOutputsAndNotify();
    }

    // ---------------------------------------------------------------- 每 tick

    /** CPU 与外部世界的接口：读六个面的输入强度、播报文字 */
    private final Cpu.Io io = new Cpu.Io() {

        @Override
        public int input(int face) {
            if (world == null || face < 0 || face >= Cpu.FACE_COUNT) {
                return 0;
            }
            EnumFacing facing = EnumFacing.values()[face];
            // 和原版中继器读输入一样：问"对面那格"在朝我这面有多少强度
            return world.getRedstonePower(pos.offset(facing), facing);
        }

        @Override
        public void message(String text) {
            broadcast(text);
        }
    };

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        if (!compiled) {
            compile();
        }
        if (running) {
            Cpu.Status status = cpu.tick(io, STEPS_PER_TICK);
            applyOutputs();
            if (status == Cpu.Status.ERROR) {
                running = false;
                refreshRunningState();
                pushFullSync();
            }
        }
        syncViewers();
    }

    /** 把 CPU 的输出寄存器推到世界上（只在真的变了才通知邻居） */
    private void applyOutputs() {
        boolean changed = false;
        for (int i = 0; i < Cpu.FACE_COUNT; i++) {
            int value = cpu.getOutput(i);
            if (value != lastOutputs[i]) {
                lastOutputs[i] = value;
                changed = true;
            }
        }
        if (changed) {
            world.notifyNeighborsOfStateChange(pos, this.getBlockType(), false);
        }
    }

    /** 播报（同一句话只在变化时播一次，避免循环里刷屏） */
    private void broadcast(String text) {
        String value = Broadcaster.sanitize(text);
        if (value.isEmpty() || value.equals(lastMessage)) {
            return;
        }
        lastMessage = value;
        Broadcaster.send(world, pos, value, Broadcaster.DEFAULT_COLOR);
    }

    // ---------------------------------------------------------------- 运行指示灯

    private void refreshRunningState() {
        if (world == null || world.isRemote) {
            return;
        }
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() instanceof BlockRedstoneComputer
                && state.getValue(BlockRedstoneComputer.RUNNING) != running) {
            world.setBlockState(pos, state.withProperty(BlockRedstoneComputer.RUNNING, running), 3);
        }
    }

    // ---------------------------------------------------------------- 调试面板

    public void addViewer(UUID playerId) {
        if (playerId != null) {
            viewers.add(playerId);
            viewerTimer = VIEWER_INTERVAL;      // 立刻推一次，界面不用等
        }
    }

    public void removeViewer(UUID playerId) {
        viewers.remove(playerId);
    }

    private void syncViewers() {
        if (viewers.isEmpty()) {
            return;
        }
        if (viewerTimer > 0) {
            viewerTimer--;
            return;
        }
        viewerTimer = VIEWER_INTERVAL;
        PacketProgramSync packet = new PacketProgramSync(this);
        Iterator<UUID> iterator = viewers.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            EntityPlayerMP target = findPlayer(id);
            if (target == null) {
                iterator.remove();
                continue;
            }
            PacketHandler.INSTANCE.sendTo(packet, target);
        }
    }

    private EntityPlayerMP findPlayer(UUID id) {
        if (world == null) {
            return null;
        }
        double limit = VIEWER_RANGE * VIEWER_RANGE;
        for (EntityPlayer player : world.playerEntities) {
            if (player instanceof EntityPlayerMP && id.equals(player.getUniqueID())) {
                return player.getDistanceSq(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= limit
                        ? (EntityPlayerMP) player : null;
            }
        }
        return null;
    }

    /** 界面刚打开 / 程序刚变过：把完整状态推给所有正在看的玩家 */
    private void pushFullSync() {
        if (world == null || world.isRemote || viewers.isEmpty()) {
            return;
        }
        PacketProgramSync packet = new PacketProgramSync(this);
        for (UUID id : viewers) {
            EntityPlayerMP target = findPlayer(id);
            if (target != null) {
                PacketHandler.INSTANCE.sendTo(packet, target);
            }
        }
    }

    // ---------------------------------------------------------------- 存档 / 同步

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        if (compound.hasKey("Source")) {
            this.source = compound.getString("Source");
        }
        this.running = compound.getBoolean("Running");
        this.compiled = false;
        this.lastMessage = "";
        compile();
        if (running && !cpu.getProgram().isEmpty()) {
            cpu.start();
        } else {
            running = false;
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Version", NBT_VERSION);
        compound.setString("Source", source == null ? "" : source);
        compound.setBoolean("Running", running);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        // ★ 只同步"运行中"这一个布尔量：程序文本走 PacketProgramSync，
        //   否则每次方块更新都要带上 12KB 的源码。
        tag.setBoolean("Running", running);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        this.running = tag.getBoolean("Running");
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

package com.rsaudio.tile;

import com.rsaudio.RsAudio;
import com.rsaudio.dmr.DmrCore;
import com.rsaudio.dmr.DmrSpec;
import com.rsaudio.network.PacketDmrAction;
import com.rsaudio.panel.PanelCore;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

/**
 * 远程控制面板的方块实体：<b>面板上的 6 个按钮各等于什么动作</b>，以及"当前控制的是哪台机器"。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li><b>解析目标</b>：每 {@value #RESOLVE_INTERVAL} tick 就近找一台 DMR 控制柜 / 一扇同频道的门，
 *       顺带把目标的当前状态压成 {@code statusBits}（面板灯用）；</li>
 *   <li><b>分发动作</b>：收到"按了第几个格子"，按当前模式翻成动作码，转交给目标机器；</li>
 *   <li><b>同步</b>：模式和频道推给客户端（界面要用），状态位推给客户端（渲染器要画灯）。</li>
 * </ol>
 * 它<b>不做数值计算、也不自己实现任何规则</b>：能不能点火、门能不能开、
 * 有没有被 FAAS 锁着，全部由那台机器自己判定并把原因回给玩家。
 * 面板只是一只伸得比较长的手。
 *
 * <h3>★ 为什么动作直接调目标的方法，而不发网络包</h3>
 * {@code PacketDmrAction} 那条路是给<b>客户端</b>用的：玩家点了界面，客户端发上来，
 * 服务端收到后要把它当<b>不可信输入</b>校验（距离、动作号范围、停机码长度……）。
 * 而这里是服务端自己算出来的调用，参数不是从网络上来的。
 * 但即便如此，仍然走 {@link TileDmrController#handleAction} 这个入口而不是直接摆弄
 * {@link DmrCore} —— 那边是这个模块唯一的行为汇聚点（补事件、存档标记、回推读数），
 * 绕过去就会漏掉其中之一。
 *
 * <h3>★ 目标为什么要"定期重解析"而不是绑死</h3>
 * 面板装好之后，旁边那台机器可能被拆了、可能又新建了一台更近的、门可能被改了频道。
 * 绑死一个坐标的话，这些变化都会变成"面板对着空气使劲"。
 * 每秒钟重找一次，代价是一次"遍历已加载方块实体"（和气闸控制器、门禁终端同一个做法），
 * 换来的是"面板永远连着当前最近的那台"。
 */
public class TileRemotePanel extends TileEntity implements ITickable {

    /** 重找目标的间隔（tick）。1 秒一次，够快，也不至于每 tick 扫全世界的方块实体 */
    public static final int RESOLVE_INTERVAL = 20;

    /** 控制哪种机器，{@link PanelCore#MODE_DMR} / {@link PanelCore#MODE_GATE} */
    private int mode = PanelCore.MODE_DMR;
    /** 门模式下用来筛同频道的门；DMR 模式下不使用（就近一台就够） */
    private int channel = PanelCore.CHANNEL_MIN;
    /** 面板灯的状态位，见 {@link PanelCore#ST_LINKED} 那一组 */
    private int statusBits;

    /** 服务端：当前解析出来的目标；客户端为 null */
    private transient BlockPos targetPos;
    /** 目标的可读描述（界面显示用）。空串 = 没找到 */
    private String targetText = "";
    /** 最近一次操作的反馈（界面显示用） */
    private String status = "";

    /**
     * 重找目标的倒计时。<b>初值就是间隔</b>：这样第一次 {@code update()} 立刻解析一次
     * （见下面那段注释：不能在 {@code onLoad()} 里做）。
     */
    private int resolveTimer = RESOLVE_INTERVAL;

    // ------------------------------------------------------------------ 读取

    public int getMode() {
        return mode;
    }

    public int getChannel() {
        return channel;
    }

    public int getStatusBits() {
        return statusBits;
    }

    public String getTargetText() {
        return targetText == null ? "" : targetText;
    }

    public String getStatus() {
        return status == null ? "" : status;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }

    /** 有没有找到目标。渲染器和界面都看这个 */
    public boolean isLinked() {
        return (statusBits & PanelCore.ST_LINKED) != 0;
    }

    // ------------------------------------------------------------------ 设置

    /** 界面改模式/频道走这里（整体覆盖，见 {@code PacketSetRemotePanel}） */
    public void applyParams(int newMode, int newChannel) {
        int m = PanelCore.clampMode(newMode);
        int c = PanelCore.clampChannel(newChannel);
        if (m == mode && c == channel) {
            return;
        }
        mode = m;
        channel = c;
        // ★ 换了模式/频道立刻重解析，不要等下一个周期 ——
        //   不然玩家改完界面会看到面板还亮着上一个目标的灯，以为没生效
        resolveTimer = RESOLVE_INTERVAL;
        if (world != null && !world.isRemote) {
            resolve();
            syncToClient();
        }
        markDirty();
    }

    // ------------------------------------------------------------------ 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        if (++resolveTimer >= RESOLVE_INTERVAL) {
            resolveTimer = 0;
            resolve();
        }
    }

    /*
     * ★ 这里刻意<b>不</b>重写 onLoad() 去"立刻解析一次"。
     *
     * 看着很划算（面板放下去不用等 1 秒才连上），但 onLoad 是<b>区块加载路径</b>上被调的
     * —— 那会儿世界正在往 loadedTileEntityList 里塞方块实体，而这个 resolve()
     * 恰恰要遍历那个列表，撞上就是 ConcurrentModificationException（而且发生在读档时，
     * 表现为"进存档直接崩"，很难往"面板"上想）。
     *
     * 现在改成 resolveTimer 初始化成 RESOLVE_INTERVAL：第一次 update()（服务端 tick，
     * 一个绝对安全、别人不会同时在改集合的时机）就会解析。代价是面板最多空 1 tick（50 毫秒），
     * 肉眼看不见。
     */

    // ------------------------------------------------------------------ 解析目标

    /**
     * 找目标 + 更新状态位。只在服务端跑。
     * <p>
     * 变化时才 {@code syncToClient()}：这个函数每秒都被调一次，
     * 无条件推送会变成每秒一次方块更新广播。
     */
    private void resolve() {
        if (world == null || world.isRemote) {
            return;
        }
        int bits = 0;
        BlockPos found = null;
        String text = "";
        if (mode == PanelCore.MODE_DMR) {
            TileDmrController dmr = nearestDmr();
            if (dmr != null) {
                found = dmr.getPos();
                text = format(found);
                bits |= PanelCore.ST_LINKED;
                DmrCore core = dmr.getCore();
                if (core != null) {
                    int state = core.getState();
                    if (state != DmrSpec.ST_OFFLINE && state != DmrSpec.ST_SAFE
                            && state != DmrSpec.ST_IMPLODED) {
                        bits |= PanelCore.ST_DMR_RUNNING;
                    }
                    if (state == DmrSpec.ST_MAINTENANCE) {
                        bits |= PanelCore.ST_DMR_MAINT;
                    }
                    if ((core.getSubsystems() & DmrSpec.SUB_ALL) == DmrSpec.SUB_ALL) {
                        bits |= PanelCore.ST_DMR_SUBSYS;
                    }
                    // ★ 下面三位是给"停机 / 装料 / 修激光"这三颗按钮的灯用的。
                    //   判据全部取自内核的公开读取接口，不在这里重写一遍规则 ——
                    //   尤其"装得进去吗"直接问 canInstallFuelCell()，和按下去那一路径同一把尺子。
                    if (state == DmrSpec.ST_SAFE) {
                        bits |= PanelCore.ST_DMR_SAFE;
                    }
                    if (core.canInstallFuelCell()) {
                        bits |= PanelCore.ST_DMR_CELL_READY;
                    }
                    if (core.getLasers() < DmrSpec.LASER_COUNT) {
                        bits |= PanelCore.ST_DMR_LASER_BAD;
                    }
                }
            }
        } else {
            TileBlastDoor door = nearestDoor();
            if (door != null) {
                found = door.getPos();
                text = format(found) + (door.isHeavy() ? "（重型）" : "");
                bits |= PanelCore.ST_LINKED;
                if (door.isOpen()) {
                    bits |= PanelCore.ST_GATE_OPEN;
                } else {
                    bits |= PanelCore.ST_GATE_CLOSED;
                }
                if (door.isLatched()) {
                    bits |= PanelCore.ST_GATE_LATCH;
                } else {
                    bits |= PanelCore.ST_GATE_UNLOCKED;
                }
                if (door.isInterlock()) {
                    bits |= PanelCore.ST_GATE_INTERLOCK;
                }
                if (door.isHeavy()) {
                    bits |= PanelCore.ST_GATE_HEAVY;
                }
            }
        }

        boolean displayed = bits != statusBits || !text.equals(targetText);
        targetPos = found;
        targetText = text;
        statusBits = bits;
        if (displayed) {
            markDirty();
            syncToClient();
        }
        // 目标没了/变了就把上一次那句"没有找到目标"清掉，不然界面会一直挂着旧警告
        if (found != null && !status.isEmpty()) {
            status = "";
            markDirty();
        }
    }

    /** 就近一台 DMR 控制柜 */
    private TileDmrController nearestDmr() {
        double limit = (double) PanelCore.SEARCH_RADIUS * PanelCore.SEARCH_RADIUS;
        TileDmrController best = null;
        double bestDist = Double.MAX_VALUE;
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileDmrController)) {
                continue;
            }
            double dist = te.getPos().distanceSq(pos);
            if (dist <= limit && dist < bestDist) {
                bestDist = dist;
                best = (TileDmrController) te;
            }
        }
        return best;
    }

    /**
     * 就近一扇<b>同频道、已建造</b>的防核门，<b>优先重型</b>。
     * <p>
     * ★ 为什么优先重型：塔塔鲁斯那种逐行升降的大门才是"需要挂在墙上的遥控器"的东西 ——
     * 它开门要十几秒，人站在门口等着很蠢。普通门顺手就开了，
     * 而且门自己身上就有交互，面板没必要跟它抢。
     * 但如果这一片只有普通门，也不该摆着不用：退回"就近的那扇"。
     */
    private TileBlastDoor nearestDoor() {
        double limit = (double) PanelCore.SEARCH_RADIUS * PanelCore.SEARCH_RADIUS;
        TileBlastDoor bestHeavy = null;
        TileBlastDoor bestAny = null;
        double heavyDist = Double.MAX_VALUE;
        double anyDist = Double.MAX_VALUE;
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileBlastDoor)) {
                continue;
            }
            TileBlastDoor door = (TileBlastDoor) te;
            // 没建造的门没有"开/关"可言；频道对不上说明不是这一组，都不算候选
            if (!door.isBuilt() || door.getChannel() != channel) {
                continue;
            }
            double dist = door.getPos().distanceSq(pos);
            if (dist > limit) {
                continue;
            }
            if (dist < anyDist) {
                anyDist = dist;
                bestAny = door;
            }
            if (door.isHeavy() && dist < heavyDist) {
                heavyDist = dist;
                bestHeavy = door;
            }
        }
        return bestHeavy != null ? bestHeavy : bestAny;
    }

    // ------------------------------------------------------------------ 按下按钮

    /**
     * 服务端入口：玩家按了面板上的某一点。
     *
     * @param u 归一化面坐标，0 = 观看者的最左，1 = 最右
     * @param v 归一化面坐标，0 = 方块底边，1 = 顶边
     * @return 这一下有没有落到实处
     */
    public boolean pressFace(EntityPlayer player, double u, double v) {
        if (world == null || world.isRemote) {
            return false;
        }
        int index = PanelCore.hitButton(u, v);
        if (index < 0) {
            // 按在缝里、边上或标题条上：那里没有按钮，什么都不该发生。
            // ★ 这里刻意不报错、不提示 —— 玩家按到缝里是常事，每次都弹一行字反而烦
            return false;
        }
        int action = PanelCore.actionOf(mode, index);
        if (action == PanelCore.ACT_NONE) {
            return false;
        }
        // 先重解析：手上的目标可能刚从"一台机器"变成"没有机器"
        resolve();
        return dispatch(player, action);
    }

    private boolean dispatch(EntityPlayer player, int action) {
        if (mode == PanelCore.MODE_DMR) {
            return dispatchDmr(player, action);
        }
        return dispatchGate(player, action);
    }

    private boolean dispatchDmr(EntityPlayer player, int action) {
        TileDmrController dmr = dmrAt(targetPos);
        if (dmr == null) {
            noTarget(player);
            return false;
        }
        switch (action) {
            case PanelCore.ACT_DMR_IGNITE:
                dmr.handleAction(player, PacketDmrAction.ACT_IGNITE, 0);
                return true;
            case PanelCore.ACT_DMR_COOLDOWN:
                dmr.handleAction(player, PacketDmrAction.ACT_COOLDOWN, 0);
                return true;
            case PanelCore.ACT_DMR_MAINT:
                // 一个按钮两用：现在在维护里就往外出，否则往进走。
                // 进不去的原因（不在停机状态）由内核发事件说明，这里不抢它的活
                dmr.handleAction(player, onMaintenance(dmr)
                        ? PacketDmrAction.ACT_EXIT_MAINT
                        : PacketDmrAction.ACT_ENTER_MAINT, 0);
                return true;
            case PanelCore.ACT_DMR_CELL:
                dmr.handleAction(player, PacketDmrAction.ACT_INSTALL_CELL, 0);
                return true;
            case PanelCore.ACT_DMR_LASER:
                dmr.handleAction(player, PacketDmrAction.ACT_REPAIR_LASERS, 0);
                return true;
            case PanelCore.ACT_DMR_SUBSYS:
                return toggleAllSubsystems(player, dmr);
            default:
                return false;
        }
    }

    private boolean dispatchGate(EntityPlayer player, int action) {
        TileBlastDoor door = doorAt(targetPos);
        if (door == null) {
            noTarget(player);
            return false;
        }
        switch (action) {
            case PanelCore.ACT_GATE_OPEN:
                door.requestOpen(true, player);
                return true;
            case PanelCore.ACT_GATE_CLOSE:
                door.requestOpen(false, player);
                return true;
            case PanelCore.ACT_GATE_LOCK:
                return door.setManualLatch(true, player);
            case PanelCore.ACT_GATE_UNLOCK:
                return door.setManualLatch(false, player);
            case PanelCore.ACT_GATE_INTERLOCK:
                // ★ 只有"门禁参数"能这样远程改 —— applyParams 里改尺寸会拆掉旧门板，
                //   那不是遥控器该干的事（面板只翻转联锁这一位，其余原样回填）
                door.applyParams(door.getWidth(), door.getHeight(), door.isRetractUp(), door.isInvert(),
                        door.getChannel(), !door.isInterlock(), door.getCycleTicks(),
                        door.isHeavy(), door.getStageDelay());
                return true;
            case PanelCore.ACT_GATE_HEAVY:
                door.applyParams(door.getWidth(), door.getHeight(), door.isRetractUp(), door.isInvert(),
                        door.getChannel(), door.isInterlock(), door.getCycleTicks(),
                        !door.isHeavy(), door.getStageDelay());
                return true;
            default:
                return false;
        }
    }

    private boolean onMaintenance(TileDmrController dmr) {
        DmrCore core = dmr.getCore();
        return core != null && core.getState() == DmrSpec.ST_MAINTENANCE;
    }

    /**
     * 子系统全开 / 全关。
     *
     * <h3>★ 为什么算一个"要翻转哪些位"的掩码，而不是直接把 {@code SUB_ALL} 发过去</h3>
     * 内核那边的动作是 <b>toggle</b>（{@code toggleSubsystem(bit)} 按位取反），
     * 而面板上那颗按钮写的是"全开/全关"。两个语义差一步：
     * 现在四个子系统里开着两个，直接把 {@code SUB_ALL} 扔过去会把两个开着的关掉、
     * 两个关着的打开 —— 净效果是"什么都没变"，玩家会以为按钮坏了。
     * <p>
     * 所以这里先读当前状态，算出<b>目标状态</b>，再取"当前与目标不一致的那些位"发过去。
     * 一次 toggle 到位，而且仍然只走 {@code ACT_TOGGLE_SUB} 这一个动作码。
     */
    private boolean toggleAllSubsystems(EntityPlayer player, TileDmrController dmr) {
        DmrCore core = dmr.getCore();
        if (core == null) {
            return false;
        }
        int current = core.getSubsystems() & DmrSpec.SUB_ALL;
        boolean allOn = current == DmrSpec.SUB_ALL;
        int flip = allOn ? current : (DmrSpec.SUB_ALL & ~current);
        if (flip == 0) {
            return false;
        }
        dmr.handleAction(player, PacketDmrAction.ACT_TOGGLE_SUB, flip);
        return true;
    }

    // ------------------------------------------------------------------ 目标查询 / 反馈

    private TileDmrController dmrAt(BlockPos p) {
        if (p == null || world == null || !world.isBlockLoaded(p)) {
            return null;
        }
        TileEntity te = world.getTileEntity(p);
        return te instanceof TileDmrController ? (TileDmrController) te : null;
    }

    private TileBlastDoor doorAt(BlockPos p) {
        if (p == null || world == null || !world.isBlockLoaded(p)) {
            return null;
        }
        TileEntity te = world.getTileEntity(p);
        return te instanceof TileBlastDoor ? (TileBlastDoor) te : null;
    }

    /**
     * 面板没接上机器时的提示。
     * <p>
     * ★ 这种情况必须<b>出声</b>。面板没有任何"失败"的视觉反馈（灯不亮太容易被忽略），
     * 玩家会一直按、一直没反应，最后来问"是不是坏了"。
     * 说清楚"没找到目标、以及往哪找"才是可诊断的。
     */
    private void noTarget(EntityPlayer player) {
        String text = mode == PanelCore.MODE_DMR
                ? "面板没找到可控制的反应堆（" + PanelCore.SEARCH_RADIUS + " 格内的 DMR 控制柜）"
                : "面板没找到同频道的防门（频道 " + channel + "、" + PanelCore.SEARCH_RADIUS + " 格内）";
        status = text;
        markDirty();
        if (player != null) {
            player.sendStatusMessage(new TextComponentTranslation("chat.rsaudio.panel.no_target"), true);
        }
        RsAudio.log("远程面板（%s）没有目标：%s", pos, text);
    }

    private static String format(BlockPos p) {
        return p == null ? "" : "(" + p.getX() + " " + p.getY() + " " + p.getZ() + ")";
    }

    // ------------------------------------------------------------------ 同步

    /**
     * 让客户端重新拿一次 NBT。
     * <p>
     * 用原版那条"通知邻居方块我变了"的路子（和门、气闸控制器一样）：
     * 它会走到 {@link #getUpdatePacket()}，把下面那份精简 NBT 推给附近的人。
     * 只推<b>面板自己的</b>几个字段（模式/频道/状态位/目标描述）——
     * 目标的读数不在这里，那是目标自己的方块实体在推。
     */
    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        mode = PanelCore.clampMode(compound.getInteger("Mode"));
        channel = PanelCore.clampChannel(compound.hasKey("Channel")
                ? compound.getInteger("Channel") : PanelCore.CHANNEL_MIN);
        statusBits = compound.getInteger("Bits");
        targetText = compound.getString("Target");
        status = compound.getString("Status");
        // 目标坐标不存档：它是"当前世界上谁离我最近"的投影，读档时重新找一遍才是对的
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        compound.setInteger("Mode", mode);
        compound.setInteger("Channel", channel);
        compound.setInteger("Bits", statusBits);
        compound.setString("Target", targetText == null ? "" : targetText);
        compound.setString("Status", status == null ? "" : status);
        return compound;
    }

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        tag.setInteger("Mode", mode);
        tag.setInteger("Channel", channel);
        tag.setInteger("Bits", statusBits);
        tag.setString("Target", targetText == null ? "" : targetText);
        tag.setString("Status", status == null ? "" : status);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        mode = PanelCore.clampMode(tag.getInteger("Mode"));
        channel = PanelCore.clampChannel(tag.hasKey("Channel")
                ? tag.getInteger("Channel") : PanelCore.CHANNEL_MIN);
        statusBits = tag.getInteger("Bits");
        targetText = tag.getString("Target");
        status = tag.getString("Status");
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

    /** 让界面能从客户端那份方块实体上读到"面板自己"的字段 */
    public static TileRemotePanel get(World world, BlockPos pos) {
        if (world == null || pos == null) {
            return null;
        }
        TileEntity te = world.getTileEntity(pos);
        return te instanceof TileRemotePanel ? (TileRemotePanel) te : null;
    }

    /** 仅供调试/日志：一句话描述这台面板现在在干什么 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(mode == PanelCore.MODE_DMR ? "DMR" : "门");
        sb.append(" CH").append(channel);
        sb.append(isLinked() ? " → " + targetText : " → 无目标");
        sb.append(" bits=0x").append(Integer.toHexString(statusBits));
        return sb.toString();
    }
}

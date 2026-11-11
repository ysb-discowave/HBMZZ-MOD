package com.rsaudio.tile;

import com.rsaudio.access.TerminalCore;
import com.rsaudio.block.BlockAccessTerminal;
import com.rsaudio.network.PacketAnnounce;
import com.rsaudio.network.PacketHandler;
import com.rsaudio.tts.TtsSpec;
import com.rsaudio.util.TtsServer;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.SPacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * FAAS 门禁终端的方块实体：把一片区域（一个频道的所有门）整体锁死 / 放行。
 *
 * <h2>职责边界</h2>
 * 所有<b>规则</b>都在 {@link TerminalCore} 里（那个类不 import 任何 Minecraft 类，
 * 由 {@code tools/check_terminal_core.py} 钉住）。这里只负责三件事：
 * <ol>
 *   <li><b>接线</b>：遍历世界里的门，把内核给出的 {@code RECONCILE_LOCK / UNLOCK / NOTHING}
 *       落到 {@code TileBlastDoor#setRemoteLock} 上；</li>
 *   <li><b>触发</b>：区域状态翻转时立刻校准一次；此外每 {@link TerminalCore#REASSERT_INTERVAL}
 *       刻兜底校准一次（这样才能顾上"封锁之后才造的门"）；</li>
 *   <li><b>播报</b>：只有在区域状态<b>真的翻转</b>时才弹窗 + 语音，兜底校准不播。</li>
 * </ol>
 *
 * <h2>★ 校准结果是"预测"，真正动了几扇要数一遍</h2>
 * 内核的 {@code acceptDoor} 只知道门闩位，它判断的是"这扇门<b>该</b>动"。
 * 真正的 {@code setRemoteLock} 可能返回 false（这一刻刚好没建成、已经被拆了……）。
 * 所以 {@code reconcileAll} 会自己数实际成功的次数，再用 {@link TerminalCore#setLastAffected}
 * 覆盖掉预测值 —— 播报里说的"锁定了 3 扇门"必须是真锁上了 3 扇。
 *
 * <h2>★ 为什么遍历 loadedTileEntityList 是可以接受的</h2>
 * 那是个几千项的列表，逐项 instanceof 确实有成本。但兜底校准只有 1 秒一次，
 * 而且每扇门那一步（{@code acceptDoor}）对"已经对了"的门是零副作用的 ——
 * 不会触发 markDirty、不会发方块更新。真正的开销只有一次列表遍历。
 * 相比之下，用"每 tick 精确维护一张频道索引表"要处理方块加载/卸载/拆建/跨维度，
 * 出错方式是"索引漏了一扇门 → 那扇门永远不锁"，比多花一次遍历严重得多。
 */
public class TileAccessTerminal extends TileEntity implements ITickable {

    /** 全部按钮都按不动的空状态文案里的默认值 */
    private String announceTitle = "FAAS 门禁";
    private String lockText = "区域封锁已启动，所有门禁门已锁定";
    private String releaseText = "区域封锁已解除，门禁门恢复通行";
    private int range = TtsSpec.DEFAULT_RANGE;
    private int noticeTicks = TtsSpec.DEFAULT_NOTICE_TICKS;

    /** 上一 tick 的红石状态（用来找边沿） */
    private boolean lastPowered;
    /** 第一次 update 只记录电平，不动作 —— 否则服务器一启动就把整片区域锁上 */
    private boolean initialized;

    private String status = "";

    /** 门禁 / 区域封锁的策略内核 */
    private final TerminalCore core = new TerminalCore();

    // ---------------------------------------------------------------- 读取

    public TerminalCore getCore() {
        return core;
    }

    public int getChannel() {
        return core.getChannel();
    }

    public boolean isLocked() {
        return core.isLocked();
    }

    public boolean isInvert() {
        return core.isInvert();
    }

    public boolean isAnnounce() {
        return core.isAnnounce();
    }

    public int getDoorCount() {
        return core.getDoorCount();
    }

    public int getLockedCount() {
        return core.getLockedCount();
    }

    public int getLastAffected() {
        return core.getLastAffected();
    }

    public String getAnnounceTitle() {
        return announceTitle;
    }

    public String getLockText() {
        return lockText;
    }

    public String getReleaseText() {
        return releaseText;
    }

    public int getRange() {
        return range;
    }

    public int getNoticeTicks() {
        return noticeTicks;
    }

    public String getStatus() {
        return status == null ? "" : status;
    }

    // ---------------------------------------------------------------- 设置

    /**
     * 服务端权威入口：来自网络包或界面保存。整套参数一起覆盖（和防核门一样的约定）。
     *
     * <h3>★ 这里刻意<b>不</b>改区域封锁状态</h3>
     * "改配置"和"封锁/放行"是两件事，必须走两条路：
     * <ul>
     *   <li>改配置（频道、反向、播报开关、文案、范围）—— 立刻生效，<b>不播报</b>，
     *       也不该顺手把整片区域重新锁一遍；</li>
     *   <li>封锁 / 放行 —— 走 {@link #setLocked}，那里才会校准 + 播报。</li>
     * </ul>
     * 如果这个方法顺手把 {@code locked} 也写进去，调用方紧接着再调 {@code setLocked} 时，
     * 内核会认为"状态没变"而<b>跳过播报</b> —— 症状是按了封锁按钮门真的锁上了、
     * 但大喇叭一声不响，而且不报错。所以状态只由 {@link #setLocked} 一个入口改。
     *
     * @param channel 门禁频道（1..16，0 会被夹到 1）
     */
    public void applySettings(int channel, boolean invert, boolean announce,
                              String title, String lockText, String releaseText,
                              int range, int noticeTicks) {
        boolean channelChanged = core.getChannel() != TerminalCore.clampChannel(channel);
        core.setChannel(channel);
        core.setInvert(invert);
        core.setAnnounce(announce);
        this.announceTitle = TtsSpec.sanitizeTitle(title);
        this.lockText = TtsSpec.sanitizeTts(lockText);
        this.releaseText = TtsSpec.sanitizeTts(releaseText);
        this.range = TtsSpec.clampRange(range);
        this.noticeTicks = TtsSpec.clampNoticeTicks(noticeTicks);

        if (channelChanged) {
            // 换了频道 -> 之前那批门的统计已经不对了，直接清掉（下一轮扫描会重算）
            core.beginScan();
        }
        markDirty();
        syncToClient();
        // 保存时就预热语音：合成有十几秒固定开销，挪到"玩家还在填表"的时候，
        // 真正封锁时几乎立刻出声。没装引擎时 warm 会安静地返回
        if (!this.lockText.isEmpty()) {
            TtsServer.warm(this.lockText, 1.0D, -1);
        }
        if (!this.releaseText.isEmpty()) {
            TtsServer.warm(this.releaseText, 1.0D, -1);
        }
    }

    /**
     * 设置区域封锁状态并立刻生效。
     *
     * @param player 触发的人（用来回一条提示）；红石触发时传 null
     * @return 状态是否真的翻转了（没翻转时不会播报）
     */
    public boolean setLocked(boolean locked, EntityPlayer player) {
        if (!core.setLocked(locked)) {
            // 没变：仍然跑一轮校准（可能是被"新造的门"之类的原因调用的），但不播报
            reconcileAll();
            syncToClient();
            return false;
        }
        int affected = reconcileAll();
        announce(locked, affected, player);
        markDirty();
        syncToClient();
        return true;
    }

    /** 把正面板上那颗锁定指示灯对齐到当前状态（不校准、不播报） */
    public void refreshIndicator() {
        if (world == null || world.isRemote) {
            return;
        }
        BlockAccessTerminal.setLockedIndicator(world, pos, core.isLocked());
    }

    /** 翻转区域封锁（界面上的按钮 / 潜行右键） */
    public boolean toggleLocked(EntityPlayer player) {
        return setLocked(!core.isLocked(), player);
    }

    /** 立即按当前设置播报一次（不改变区域状态） */
    public void announceNow(EntityPlayer player) {
        if (world == null || world.isRemote) {
            return;
        }
        int affected = reconcileAll();
        announce(core.isLocked(), affected, player);
    }

    // ---------------------------------------------------------------- 校准

    /**
     * 把区域状态落到频道里每一扇门上。
     *
     * @param onlyTighten true = 兜底校准：只补锁、不撤锁。
     *                    ★ 兜底校准的职责只有"补上封锁之后才造出来的门"，
     *                    放行方向根本不需要自动的东西（新建的门本来就是通的）。
     *                    让它两个方向都做的话，同一个频道上放两台终端
     *                    （一台封锁一台放行）会让门每 20 刻来回换一次状态 ——
     *                    两边都没错，但门在抖。见 {@code TerminalCore.reconcile(int, boolean)}。
     * @return 实际被改动（{@code setRemoteLock} 返回 true）的门数
     */
    private int reconcileAll(boolean onlyTighten) {
        if (world == null || world.isRemote) {
            return 0;
        }
        int channel = core.getChannel();
        int applied = 0;
        core.beginScan(onlyTighten);
        for (TileEntity te : world.loadedTileEntityList) {
            if (!(te instanceof TileBlastDoor)) {
                continue;
            }
            TileBlastDoor door = (TileBlastDoor) te;
            // 只认"已建造"的门：没建造的门板根本不存在，给它上锁没有意义，
            // 而且会让 isSettled() 一直报"没落齐"
            if (!door.isBuilt()) {
                continue;
            }
            if (door.getChannel() != channel) {
                continue;
            }
            int action = core.acceptDoor(door.getLatchBits());
            if (action == TerminalCore.RECONCILE_LOCK) {
                if (door.setRemoteLock(true, null)) {
                    applied++;
                }
            } else if (action == TerminalCore.RECONCILE_UNLOCK) {
                // ★ 这里传 false 走的是 AccessCore.unlatch(REASON_REMOTE)，
                //   只会熄灭"远程"那一位 —— 玩家手动上的锁原样留着。
                //   见 TerminalCore 的类注释"校准只动远程那一位"
                if (door.setRemoteLock(false, null)) {
                    applied++;
                }
            }
        }
        // 用实际发生的事覆盖内核的预测值
        core.setLastAffected(applied);
        // ★ 频道里一扇门都没有时必须说清楚：否则按了「封锁」界面上"0 / 0 已锁定"，
        //   玩家只会得出"这个终端是坏的"。真正的成因九成是<b>门的频道没设成同一个</b>
        //   （门的频道默认是 0 = 不参与门禁，而终端默认是 1），
        //   把这句话写进状态栏，玩家立刻就知道该去哪里改。
        if (core.getDoorCount() == 0) {
            String text = "频道 " + channel + " 里没有已建造的门 —— 门的「频道」要在门的设置界面里设成 "
                    + channel + "（默认是 0 = 不参与门禁）";
            if (!text.equals(status)) {
                setStatus(text);
            }
        }
        return applied;
    }

    /** 完整校准（两个方向都做）：由 {@link #setLocked} 调用 */
    private int reconcileAll() {
        return reconcileAll(false);
    }

    /** 频道里已建造的门数（给界面上那行"3 / 4 已锁定"用；会真的扫一遍） */
    public int countDoorsNow() {
        reconcileAll();
        return core.getDoorCount();
    }

    // ---------------------------------------------------------------- 播报

    /**
     * 播报一次区域状态变更：范围内的玩家收到右下角弹窗；装了语音引擎还会念出来。
     *
     * @param locked  变更后的区域状态
     * @param affected 实际被改动的门数（写进提示里，便于玩家确认"真的封上了"）
     */
    private void announce(boolean locked, int affected, EntityPlayer activator) {
        if (world == null || world.isRemote) {
            return;
        }
        String body = locked ? lockText : releaseText;
        String text = (locked ? "区域已封锁" : "区域已解除封锁") + "（" + affected + " 扇门）";
        setStatus(text);
        if (activator != null) {
            activator.sendStatusMessage(new TextComponentString(text), true);
        }

        if (!core.isAnnounce()) {
            return;
        }
        List<EntityPlayerMP> targets = collectTargets();

        // ---- 弹窗（不需要引擎，永远可用）
        if (!announceTitle.isEmpty() || !body.isEmpty()) {
            for (EntityPlayerMP player : targets) {
                try {
                    PacketHandler.INSTANCE.sendTo(
                            new PacketAnnounce(announceTitle, body, noticeTicks), player);
                } catch (Throwable t) {
                    // 单个玩家掉线不该影响其他人
                }
            }
        }
        // ---- 语音（需要服务端装好引擎；没装就静默降级，弹窗照发）
        if (!body.isEmpty() && TtsServer.isReady()) {
            TtsServer.speak(body, 1.0D, -1, targets);
        }
    }

    /** 按半径收集玩家。全部用平方比较，不开方 */
    private List<EntityPlayerMP> collectTargets() {
        List<EntityPlayerMP> out = new ArrayList<EntityPlayerMP>();
        double limit = (double) range * (double) range;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        for (EntityPlayer player : world.playerEntities) {
            if (!(player instanceof EntityPlayerMP) || player.isDead) {
                continue;
            }
            if (player.getDistanceSq(x, y, z) <= limit) {
                out.add((EntityPlayerMP) player);
            }
        }
        return out;
    }

    private void setStatus(String text) {
        this.status = text == null ? "" : text;
        markDirty();
        syncToClient();
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        boolean powered = world.isBlockPowered(pos);
        if (!initialized) {
            initialized = true;
            lastPowered = powered;
            // ★ 启动时只把指示灯对齐到当前状态，<b>不</b>根据红石电平做动作 ——
            //   否则服务器一开就把整片区域锁上了（玩家会以为存档坏了）。
            //   但要立刻校准一次：存档是"封锁"状态的话，门必须在读档后就真的锁着，
            //   不能等最多 1 秒的兜底周期 —— 那 1 秒里被锁的门是能开的。
            //   （只收紧：读档是放行状态时不需要"顺手解开"谁）
            reconcileAll(true);
            BlockAccessTerminal.setLockedIndicator(world, pos, core.isLocked());
            syncToClient();
            return;
        }
        if (powered != lastPowered) {
            lastPowered = powered;
            // ★ 默认"来信号 = 封锁"：反应堆出事时给终端一个信号就把整片区域封死
            setLocked(core.targetFromSignal(powered), null);
        }

        // 兜底校准：顾不上"封锁之后才造的门"是这块功能最容易被漏掉的洞。
        // ★ 只收紧 —— 自动的东西负责"变严"，"变松"必须有人明确要求
        if (core.tick()) {
            int applied = reconcileAll(true);
            if (applied > 0) {
                syncToClient();
            }
        }

        BlockAccessTerminal.setLockedIndicator(world, pos, core.isLocked());
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ---------------------------------------------------------------- 存档 / 同步

    private void load(NBTTagCompound tag) {
        // ★ 读不到就 reset 回默认；长度不对也要整份丢弃（见 TerminalCore.loadState）
        core.loadState(tag.hasKey("Core") ? tag.getIntArray("Core") : null);
        this.announceTitle = TtsSpec.sanitizeTitle(
                tag.hasKey("ATitle") ? tag.getString("ATitle") : "FAAS 门禁");
        this.lockText = TtsSpec.sanitizeTts(
                tag.hasKey("ALock") ? tag.getString("ALock") : "区域封锁已启动，所有门禁门已锁定");
        this.releaseText = TtsSpec.sanitizeTts(
                tag.hasKey("ARelease") ? tag.getString("ARelease") : "区域封锁已解除，门禁门恢复通行");
        this.range = TtsSpec.clampRange(
                tag.hasKey("ARange") ? tag.getInteger("ARange") : TtsSpec.DEFAULT_RANGE);
        this.noticeTicks = TtsSpec.clampNoticeTicks(
                tag.hasKey("ANotice") ? tag.getInteger("ANotice") : TtsSpec.DEFAULT_NOTICE_TICKS);
        this.status = tag.getString("Status");
    }

    private void save(NBTTagCompound tag) {
        tag.setIntArray("Core", core.saveState());
        tag.setString("ATitle", announceTitle);
        tag.setString("ALock", lockText);
        tag.setString("ARelease", releaseText);
        tag.setInteger("ARange", range);
        tag.setInteger("ANotice", noticeTicks);
        tag.setString("Status", status == null ? "" : status);
    }

    @Override
    public void readFromNBT(NBTTagCompound compound) {
        super.readFromNBT(compound);
        load(compound);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound compound) {
        super.writeToNBT(compound);
        save(compound);
        return compound;
    }

    /**
     * 同步给客户端（界面要用）。
     * <p>
     * ★ 这里比 {@link #save} 多带了三个统计量。它们<b>不持久化</b>（是"扫出来的"，存档里的
     * 那份一定是过期的），但界面必须看得到 —— 否则"3 / 4 已锁定"永远显示 0 / 0。
     */
    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        save(tag);
        tag.setInteger("Doors", core.getDoorCount());
        tag.setInteger("LockedDoors", core.getLockedCount());
        tag.setInteger("Affected", core.getLastAffected());
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        load(tag);
        // ★ 统计量由服务端直接报（客户端自己扫不出同样的结果），见 TerminalCore.setCounts
        core.setCounts(tag.getInteger("Doors"), tag.getInteger("LockedDoors"),
                tag.getInteger("Affected"));
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

package com.rsaudio.tile;

import com.rsaudio.block.BlockAnnouncer;
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
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 广播器的方块实体：存三行文本和三个参数，并把它们变成"弹窗 + 语音"。
 *
 * <h3>谁是权威</h3>
 * 所有清洗（截断、折行折叠、范围收敛）都在<b>服务端</b>做，客户端界面只是把玩家
 * 输入原样发上来。这样即使有人改客户端发超长文本，服务端也不会被刷屏或撑爆合成。
 *
 * <h3>为什么不用 neighborChanged 抓红石沿</h3>
 * 红石线的更新顺序在复杂电路里不保证，靠 neighborChanged 抓"上升沿"很容易漏事件
 * （尤其是相邻方块同时变化时）。每 tick 读一次 {@code isBlockPowered} 再和上一 tick
 * 比，逻辑上多跑一点，但<b>不会漏</b> —— 这个方块本来就要 tick（指示灯要灭）。
 */
public class TileAnnouncer extends TileEntity implements ITickable {

    /** 广播后指示灯亮多久（tick） */
    private static final int ACTIVE_TICKS = 30;

    private String title = "";
    private String content = "";
    private String ttsText = "";
    private int range = TtsSpec.DEFAULT_RANGE;
    private int noticeTicks = TtsSpec.DEFAULT_NOTICE_TICKS;
    private double lengthScale = TtsSpec.DEFAULT_LENGTH_SCALE;

    /** 上一 tick 的红石状态（用来找上升沿） */
    private boolean lastPowered;
    /** 指示灯剩余点亮时间 */
    private int activeTicks;

    // ---------------------------------------------------------------- 读取

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public String getTtsText() {
        return ttsText;
    }

    public int getRange() {
        return range;
    }

    public int getNoticeTicks() {
        return noticeTicks;
    }

    public double getLengthScale() {
        return lengthScale;
    }

    // ---------------------------------------------------------------- 写入

    /** 服务端权威入口：来自网络包或界面保存 */
    public void applySettings(String title, String content, String ttsText,
                              int range, int noticeTicks, double lengthScale) {
        this.title = TtsSpec.sanitizeTitle(title);
        this.content = TtsSpec.sanitizeContent(content);
        this.ttsText = TtsSpec.sanitizeTts(ttsText);
        this.range = TtsSpec.clampRange(range);
        this.noticeTicks = TtsSpec.clampNoticeTicks(noticeTicks);
        this.lengthScale = TtsSpec.clampLengthScale(lengthScale);
        markDirty();
        syncToClient();
        // ★ 保存就先合成一遍：合成有十几秒的固定开销，把它挪到"玩家还在填表"的时候，
        //   这样之后无论是点「广播」还是红石触发，都是直接命中缓存、几乎立刻出声。
        //   失败/没装引擎时 warm 自己会安静地返回，不影响弹窗那条路。
        if (!this.ttsText.isEmpty()) {
            TtsServer.warm(this.ttsText, this.lengthScale, -1);
        }
    }

    // ---------------------------------------------------------------- 广播

    /**
     * 广播一次：范围内的玩家收到右下角弹窗；配了朗读文本且引擎就绪时还会播语音。
     *
     * @param activator 手动触发的人（用来回一条提示）；红石触发时传 null
     */
    public void broadcast(EntityPlayer activator) {
        if (world == null || world.isRemote) {
            return;
        }
        if (title.isEmpty() && content.isEmpty() && ttsText.isEmpty()) {
            if (activator != null) {
                activator.sendStatusMessage(
                        new TextComponentTranslation("chat.rsaudio.announcer.empty"), true);
            }
            return;
        }

        List<EntityPlayerMP> targets = collectTargets();

        // ---- 弹窗（不需要引擎，永远可用）
        if (!title.isEmpty() || !content.isEmpty()) {
            for (EntityPlayerMP player : targets) {
                try {
                    PacketHandler.INSTANCE.sendTo(
                            new PacketAnnounce(title, content, noticeTicks), player);
                } catch (Throwable t) {
                    // 单个玩家掉线不该影响其他人
                }
            }
        }

        // ---- 语音（需要服务端装好引擎）
        boolean silent = false;
        boolean spoke = false;
        if (!ttsText.isEmpty()) {
            if (!TtsServer.isReady()) {
                silent = true;
            } else {
                spoke = TtsServer.speak(ttsText, lengthScale, -1, targets);
            }
        }

        if (activator != null) {
            String key = spoke ? "chat.rsaudio.announcer.sent" : "chat.rsaudio.announcer.sent_silent";
            activator.sendStatusMessage(
                    new TextComponentTranslation(key, Integer.valueOf(targets.size())), true);
            if (silent) {
                activator.sendStatusMessage(
                        new TextComponentTranslation("chat.rsaudio.announcer.no_tts"), false);
            }
        }

        setActive(true);
    }

    /** 按半径收集玩家。用水平距离 + 垂直距离都用平方比较，不做开方 */
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

    private void setActive(boolean active) {
        activeTicks = active ? ACTIVE_TICKS : 0;
        BlockAnnouncer.setActive(world, pos, active);
    }

    // ---------------------------------------------------------------- 每 tick

    @Override
    public void update() {
        if (world == null || world.isRemote) {
            return;
        }
        boolean powered = world.isBlockPowered(pos);
        if (powered && !lastPowered) {
            broadcast(null);
        }
        lastPowered = powered;

        if (activeTicks > 0) {
            activeTicks--;
            if (activeTicks == 0) {
                BlockAnnouncer.setActive(world, pos, false);
            }
        }
    }

    private void syncToClient() {
        if (world != null && !world.isRemote) {
            IBlockState state = world.getBlockState(pos);
            world.notifyBlockUpdate(pos, state, state, 3);
        }
    }

    // ---------------------------------------------------------------- 存档 / 同步

    /** 读回来一律过一遍清洗：旧存档、手改的 NBT 都可能带越界值 */
    private void load(NBTTagCompound tag) {
        this.title = TtsSpec.sanitizeTitle(tag.getString("Title"));
        this.content = TtsSpec.sanitizeContent(tag.getString("Content"));
        this.ttsText = TtsSpec.sanitizeTts(tag.getString("Tts"));
        this.range = TtsSpec.clampRange(
                tag.hasKey("Range") ? tag.getInteger("Range") : TtsSpec.DEFAULT_RANGE);
        this.noticeTicks = TtsSpec.clampNoticeTicks(
                tag.hasKey("NoticeTicks") ? tag.getInteger("NoticeTicks") : TtsSpec.DEFAULT_NOTICE_TICKS);
        this.lengthScale = TtsSpec.clampLengthScale(
                tag.hasKey("LengthScale") ? tag.getDouble("LengthScale") : TtsSpec.DEFAULT_LENGTH_SCALE);
    }

    private void save(NBTTagCompound tag) {
        tag.setString("Title", title);
        tag.setString("Content", content);
        tag.setString("Tts", ttsText);
        tag.setInteger("Range", range);
        tag.setInteger("NoticeTicks", noticeTicks);
        tag.setDouble("LengthScale", lengthScale);
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

    @Override
    public NBTTagCompound getUpdateTag() {
        NBTTagCompound tag = super.getUpdateTag();
        save(tag);
        return tag;
    }

    @Override
    public void handleUpdateTag(NBTTagCompound tag) {
        super.handleUpdateTag(tag);
        load(tag);
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

package com.rsaudio.network;

import com.rsaudio.tile.TileAccessTerminal;
import com.rsaudio.tts.TtsSpec;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：FAAS 门禁终端的设置与操作。
 *
 * <h3>为什么"区域状态"也要整体发过来</h3>
 * 和 {@code PacketSetDoor} 一样是<b>整体覆盖</b>：界面上的值就是玩家的意图。
 * 界面上那颗「封锁全部 / 解除封锁」改的其实就是同一个 {@code locked} 字段，
 * 所以它不需要单独的动作码 —— 状态从 false 变成 true，服务端自己去校准 + 播报。
 * <p>
 * ★ 这样还顺手解决了一个问题：<b>红石也改这个状态</b>。如果封锁按钮走一条独立的
 * "立刻封锁"指令，那玩家在界面里看到的状态就可能和红石那边打起来
 * （界面显示已封锁、红石下一刻把它解开，而界面还停在旧状态）。统一成一个字段之后，
 * 界面每次保存都会把服务端拉回"玩家看到的那份状态"，语义是稳定的。
 *
 * <h3>清洗在服务端再做一遍</h3>
 * 客户端送来的字符串一律当不可信，服务端用 {@code TtsSpec} 再截一次
 * （和 {@code PacketSetAnnouncer} 同一个约定）。
 */
public class PacketSetTerminal implements IMessage {

    /** 保存设置（区域状态变了的话顺带校准 + 播报） */
    public static final byte APPLY = 0;
    /** 按当前设置立刻播报一次，不改区域状态 */
    public static final byte BROADCAST_NOW = 1;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action;
    private int channel;
    private boolean locked;
    private boolean invert;
    private boolean announce;
    private String title = "";
    private String lockText = "";
    private String releaseText = "";
    private int range;
    private int noticeTicks;

    public PacketSetTerminal() {
    }

    public PacketSetTerminal(BlockPos pos, byte action, int channel, boolean locked,
                             boolean invert, boolean announce, String title, String lockText,
                             String releaseText, int range, int noticeTicks) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.action = action;
        this.channel = channel;
        this.locked = locked;
        this.invert = invert;
        this.announce = announce;
        this.title = title == null ? "" : title;
        this.lockText = lockText == null ? "" : lockText;
        this.releaseText = releaseText == null ? "" : releaseText;
        this.range = range;
        this.noticeTicks = noticeTicks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.channel = buf.readInt();
        this.locked = buf.readBoolean();
        this.invert = buf.readBoolean();
        this.announce = buf.readBoolean();
        this.title = ByteBufUtils.readUTF8String(buf);
        this.lockText = ByteBufUtils.readUTF8String(buf);
        this.releaseText = ByteBufUtils.readUTF8String(buf);
        this.range = buf.readInt();
        this.noticeTicks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        buf.writeInt(channel);
        buf.writeBoolean(locked);
        buf.writeBoolean(invert);
        buf.writeBoolean(announce);
        ByteBufUtils.writeUTF8String(buf, title);
        ByteBufUtils.writeUTF8String(buf, lockText);
        ByteBufUtils.writeUTF8String(buf, releaseText);
        buf.writeInt(range);
        buf.writeInt(noticeTicks);
    }

    public static class Handler implements IMessageHandler<PacketSetTerminal, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetTerminal message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 卡 64 格：只有真的站在终端旁边才操作得了它。
                    // 门禁终端能一次锁掉整片区域，不设距离限制的话远程乱锁会很难查
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileAccessTerminal)) {
                        return;
                    }
                    TileAccessTerminal tile = (TileAccessTerminal) te;

                    // 配置（频道/反向/播报开关/文案/范围）：改了立刻生效，不播报
                    tile.applySettings(message.channel, message.invert, message.announce,
                            message.title, message.lockText, message.releaseText,
                            message.range, message.noticeTicks);

                    if (message.action == BROADCAST_NOW) {
                        tile.announceNow(player);
                        return;
                    }
                    // ★ 区域状态只由 setLocked 一个入口改，而且它内部会判断"真的翻转了吗"：
                    //   翻转了才校准 + 播报。玩家在界面里调个范围、改两个字，
                    //   不该顺手把整片区域重新锁一遍，更不该再响一次大喇叭。
                    //   （状态没变时不主动扫世界 —— 1 秒内的兜底周期会补上，见 TileAccessTerminal）
                    if (!tile.setLocked(message.locked, player)) {
                        tile.refreshIndicator();
                    }
                }
            });
            return null;
        }
    }
}

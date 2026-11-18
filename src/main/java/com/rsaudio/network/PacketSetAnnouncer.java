package com.rsaudio.network;

import com.rsaudio.tile.TileAnnouncer;
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
 * 客户端 -> 服务端：广播器的设置（三个文本 + 范围 + 弹窗时长 + 语速），
 * 并且可以选择"存下就完"还是"存下并立刻广播一次"。
 * <p>
 * ★ 清洗（截断、折行折叠）必须<b>在服务端再做一遍</b>：客户端送来的东西一律当不可信。
 * 用的是 {@code TtsSpec} 里那一份实现，所以服务端截出来的结果和界面里看到的一致。
 */
public class PacketSetAnnouncer implements IMessage {

    /** 只保存设置 */
    public static final boolean ACTION_SAVE = false;
    /** 保存并立刻广播一次 */
    public static final boolean ACTION_BROADCAST = true;

    private BlockPos pos = BlockPos.ORIGIN;
    private String title = "";
    private String content = "";
    private String ttsText = "";
    private int range = TtsSpec.DEFAULT_RANGE;
    private int noticeTicks = TtsSpec.DEFAULT_NOTICE_TICKS;
    private double lengthScale = TtsSpec.DEFAULT_LENGTH_SCALE;
    private boolean broadcast;

    public PacketSetAnnouncer() {
    }

    public PacketSetAnnouncer(BlockPos pos, String title, String content, String ttsText,
                             int range, int noticeTicks, double lengthScale, boolean broadcast) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.title = title == null ? "" : title;
        this.content = content == null ? "" : content;
        this.ttsText = ttsText == null ? "" : ttsText;
        this.range = range;
        this.noticeTicks = noticeTicks;
        this.lengthScale = lengthScale;
        this.broadcast = broadcast;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.title = ByteBufUtils.readUTF8String(buf);
        this.content = ByteBufUtils.readUTF8String(buf);
        this.ttsText = ByteBufUtils.readUTF8String(buf);
        this.range = buf.readInt();
        this.noticeTicks = buf.readInt();
        this.lengthScale = buf.readDouble();
        this.broadcast = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, title);
        ByteBufUtils.writeUTF8String(buf, content);
        ByteBufUtils.writeUTF8String(buf, ttsText);
        buf.writeInt(range);
        buf.writeInt(noticeTicks);
        buf.writeDouble(lengthScale);
        buf.writeBoolean(broadcast);
    }

    public static class Handler implements IMessageHandler<PacketSetAnnouncer, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetAnnouncer message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 和警报灯一样卡 64 格：只有真的站在机器旁边才改得动它
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileAnnouncer)) {
                        return;
                    }
                    TileAnnouncer tile = (TileAnnouncer) te;
                    tile.applySettings(message.title, message.content, message.ttsText,
                            message.range, message.noticeTicks, message.lengthScale);
                    if (message.broadcast) {
                        tile.broadcast(player);
                    }
                }
            });
            return null;
        }
    }
}

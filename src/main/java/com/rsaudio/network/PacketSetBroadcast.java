package com.rsaudio.network;

import com.rsaudio.tile.IBroadcastHolder;
import com.rsaudio.util.Broadcaster;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：设置方块的"聊天栏播报"文字 + 颜色。
 * <p>
 * 音频播放器和红石延迟器共用这一条包，服务端按方块实体实现的
 * {@link IBroadcastHolder} 分派，不用为两种方块各写一条。
 */
public class PacketSetBroadcast implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private String text = "";
    private int color = Broadcaster.DEFAULT_COLOR;

    public PacketSetBroadcast() {
    }

    public PacketSetBroadcast(BlockPos pos, String text, int color) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.text = text == null ? "" : text;
        this.color = color;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.text = ByteBufUtils.readUTF8String(buf);
        this.color = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, text == null ? "" : text);
        buf.writeByte(color);
    }

    public static class Handler implements IMessageHandler<PacketSetBroadcast, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetBroadcast message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 防作弊：只允许操作附近的方块
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof IBroadcastHolder)) {
                        return;
                    }
                    IBroadcastHolder holder = (IBroadcastHolder) te;
                    holder.setBroadcast(Broadcaster.sanitize(message.text), message.color);

                    // 广播给周围客户端，别的玩家打开界面时看到的就是最新值
                    PacketHandler.INSTANCE.sendToAllAround(
                            new PacketBroadcastSync(message.pos, holder.getBroadcastText(),
                                    holder.getBroadcastColor()),
                            new NetworkRegistry.TargetPoint(world.provider.getDimension(),
                                    message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                                    message.pos.getZ() + 0.5D, Broadcaster.RANGE));
                }
            });
            return null;
        }
    }
}

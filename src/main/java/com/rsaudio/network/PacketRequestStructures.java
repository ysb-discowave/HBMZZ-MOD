package com.rsaudio.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：请求刷新结构列表。
 * <p>
 * 结构文件在服务端的 config 目录里，客户端看不到（联机时更是别的机器），
 * 所以加载器界面只能请服务端把清单发过来。
 */
public class PacketRequestStructures implements IMessage {

    public PacketRequestStructures() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        // 无载荷
    }

    @Override
    public void toBytes(ByteBuf buf) {
        // 无载荷
    }

    public static class Handler implements IMessageHandler<PacketRequestStructures, IMessage> {

        @Override
        public IMessage onMessage(final PacketRequestStructures message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    PacketHandler.INSTANCE.sendTo(new PacketStructureList(), player);
                }
            });
            return null;
        }
    }
}

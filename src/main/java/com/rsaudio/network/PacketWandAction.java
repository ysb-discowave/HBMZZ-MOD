package com.rsaudio.network;

import com.rsaudio.util.WandHandler;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：结构魔杖界面的操作。
 * <p>
 * 魔杖界面本身是纯客户端的，但"选区"在服务端内存里、保存文件也只能服务端做，
 * 所以界面上的按钮都通过这个包发回去。
 */
public class PacketWandAction implements IMessage {

    public static final byte SAVE = 0;
    public static final byte CLEAR = 1;
    public static final byte REQUEST = 2;

    private byte action;
    private String name = "";

    public PacketWandAction() {
    }

    public PacketWandAction(byte action, String name) {
        this.action = action;
        this.name = name == null ? "" : name;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.action = buf.readByte();
        this.name = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeByte(action);
        ByteBufUtils.writeUTF8String(buf, name);
    }

    public static class Handler implements IMessageHandler<PacketWandAction, IMessage> {

        @Override
        public IMessage onMessage(final PacketWandAction message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    switch (message.action) {
                        case SAVE:
                            WandHandler.saveSelection(player, message.name);
                            break;
                        case CLEAR:
                            WandHandler.clearSelection(player);
                            break;
                        default:
                            WandHandler.sync(player, null);
                            break;
                    }
                }
            });
            return null;
        }
    }
}

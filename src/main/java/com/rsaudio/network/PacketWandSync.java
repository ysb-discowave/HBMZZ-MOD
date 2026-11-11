package com.rsaudio.network;

import com.rsaudio.RsAudio;
import com.rsaudio.structure.StructureSelection;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：把魔杖选区同步给本人。
 * <p>
 * 选区存在服务端（真值只有一个），界面要显示"选了多少格"就得同步回来。
 * 用两个布尔量标记"这个角选了没" —— 不能拿坐标 0 当哨兵，
 * 因为 (0,0,0) 是个合法坐标，那样选区在原点附近时会显示成"没选"。
 */
public class PacketWandSync implements IMessage {

    private boolean hasPos1;
    private long pos1;
    private boolean hasPos2;
    private long pos2;
    private String name = "";
    private String message = "";

    public PacketWandSync() {
    }

    public PacketWandSync(StructureSelection selection, String message) {
        if (selection != null) {
            this.hasPos1 = selection.getPos1() != null;
            this.pos1 = hasPos1 ? selection.getPos1().toLong() : 0L;
            this.hasPos2 = selection.getPos2() != null;
            this.pos2 = hasPos2 ? selection.getPos2().toLong() : 0L;
            this.name = selection.getName();
        }
        this.message = message == null ? "" : message;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.hasPos1 = buf.readBoolean();
        this.pos1 = buf.readLong();
        this.hasPos2 = buf.readBoolean();
        this.pos2 = buf.readLong();
        this.name = ByteBufUtils.readUTF8String(buf);
        this.message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(hasPos1);
        buf.writeLong(pos1);
        buf.writeBoolean(hasPos2);
        buf.writeLong(pos2);
        ByteBufUtils.writeUTF8String(buf, name);
        ByteBufUtils.writeUTF8String(buf, message);
    }

    public static class Handler implements IMessageHandler<PacketWandSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketWandSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    try {
                        com.rsaudio.client.ClientWandState.apply(message.hasPos1, message.pos1,
                                message.hasPos2, message.pos2, message.name, message.message);
                    } catch (Throwable t) {
                        RsAudio.warn("应用魔杖选区失败: " + t);
                    }
                }
            });
            return null;
        }
    }
}

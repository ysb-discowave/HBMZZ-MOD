package com.rsaudio.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：在<b>屏幕右下角</b>弹一个提示框。
 * <p>
 * 只带文字和停留时长，不带位置 —— 弹窗是纯 HUD 元素，
 * 玩家关心的是"说了什么"，不是"哪台机器在说"。
 */
public class PacketAnnounce implements IMessage {

    private String title = "";
    private String content = "";
    private int noticeTicks;

    public PacketAnnounce() {
    }

    public PacketAnnounce(String title, String content, int noticeTicks) {
        this.title = title == null ? "" : title;
        this.content = content == null ? "" : content;
        this.noticeTicks = noticeTicks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.title = ByteBufUtils.readUTF8String(buf);
        this.content = ByteBufUtils.readUTF8String(buf);
        this.noticeTicks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, title);
        ByteBufUtils.writeUTF8String(buf, content);
        buf.writeInt(noticeTicks);
    }

    public static class Handler implements IMessageHandler<PacketAnnounce, IMessage> {

        @Override
        public IMessage onMessage(final PacketAnnounce message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    com.rsaudio.client.ClientNotice.show(
                            message.title, message.content, message.noticeTicks);
                }
            });
            return null;
        }
    }
}

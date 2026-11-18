package com.rsaudio.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：这段语音发完了，可以开始播。
 */
public class PacketTtsEnd implements IMessage {

    private int streamId;

    public PacketTtsEnd() {
    }

    public PacketTtsEnd(int streamId) {
        this.streamId = streamId;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.streamId = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(streamId);
    }

    public static class Handler implements IMessageHandler<PacketTtsEnd, IMessage> {

        @Override
        public IMessage onMessage(final PacketTtsEnd message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    com.rsaudio.client.TtsClientPlayer.onEnd(message.streamId);
                }
            });
            return null;
        }
    }
}

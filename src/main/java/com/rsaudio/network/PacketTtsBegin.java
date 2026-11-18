package com.rsaudio.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：一段语音即将开始传输。
 * <p>
 * 客户端收到它就开一个"接收槽"，后面按 index 填分片。
 * 先有 begin 才收分片，是为了让乱序到达的尾巴直接被丢掉，
 * 而不是在客户端里拼出半截音频（见 {@code AudioReassembler}）。
 */
public class PacketTtsBegin implements IMessage {

    private int streamId;
    private int totalBytes;
    private int chunkCount;

    public PacketTtsBegin() {
    }

    public PacketTtsBegin(int streamId, int totalBytes, int chunkCount) {
        this.streamId = streamId;
        this.totalBytes = totalBytes;
        this.chunkCount = chunkCount;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.streamId = buf.readInt();
        this.totalBytes = buf.readInt();
        this.chunkCount = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(streamId);
        buf.writeInt(totalBytes);
        buf.writeInt(chunkCount);
    }

    public static class Handler implements IMessageHandler<PacketTtsBegin, IMessage> {

        @Override
        public IMessage onMessage(final PacketTtsBegin message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    com.rsaudio.client.TtsClientPlayer.onBegin(
                            message.streamId, message.totalBytes, message.chunkCount);
                }
            });
            return null;
        }
    }
}

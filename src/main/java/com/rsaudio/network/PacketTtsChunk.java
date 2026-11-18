package com.rsaudio.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：一段语音的第 index 个分片。
 * <p>
 * ★ 分片长度<b>显式写在包里</b>，不用 {@code buf.readableBytes()} 推：
 * 一来省得以后往这个包里加字段时把长度算错，二来长度本身就是校验 ——
 * 服务端切出来多长，客户端就该收到多长，对不上是可查的。
 * <p>
 * 每片 {@code TtsSpec.CHUNK_BYTES}（16KB），远低于 1.12.2 的网络帧上限。
 */
public class PacketTtsChunk implements IMessage {

    private int streamId;
    private int index;
    private byte[] data = new byte[0];

    public PacketTtsChunk() {
    }

    public PacketTtsChunk(int streamId, int index, byte[] data) {
        this.streamId = streamId;
        this.index = index;
        this.data = data == null ? new byte[0] : data;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.streamId = buf.readInt();
        this.index = buf.readInt();
        int len = buf.readInt();
        // 越界的长度声明必须挡住：直接 new byte[len] 会被一个坏包直接撑爆堆
        if (len < 0 || len > buf.readableBytes()) {
            this.data = new byte[0];
            return;
        }
        this.data = new byte[len];
        buf.readBytes(this.data);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(streamId);
        buf.writeInt(index);
        buf.writeInt(data.length);
        buf.writeBytes(data);
    }

    public static class Handler implements IMessageHandler<PacketTtsChunk, IMessage> {

        @Override
        public IMessage onMessage(final PacketTtsChunk message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    com.rsaudio.client.TtsClientPlayer.onChunk(
                            message.streamId, message.index, message.data);
                }
            });
            return null;
        }
    }
}

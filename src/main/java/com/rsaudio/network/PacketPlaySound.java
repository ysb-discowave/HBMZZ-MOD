package com.rsaudio.network;

import com.rsaudio.audio.ReasimParams;
import com.rsaudio.client.ClientSoundPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：在该位置播放 / 停止音乐。
 * <p>
 * reasim 参数跟着这一包一起下去，而不是让客户端自己去查方块实体：
 * 播放的<b>那一瞬间</b>用什么音色由服务端决定，客户端没有资格改
 * （否则改本地就能让别人的服务器上放出干净的声音）。
 * 播放中途改参数走的是方块实体的同步标签，不是这个包。
 */
public class PacketPlaySound implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private String soundName = "";
    private boolean stop = false;
    /** 是否循环播放（由播放模式决定） */
    private boolean loop = false;
    /** reasim 参数；停止包 / 未开启时为 null */
    private ReasimParams reasim;

    public PacketPlaySound() {
    }

    public PacketPlaySound(BlockPos pos, String soundName, boolean stop, boolean loop) {
        this(pos, soundName, stop, loop, null);
    }

    public PacketPlaySound(BlockPos pos, String soundName, boolean stop, boolean loop, ReasimParams reasim) {
        this.pos = pos;
        this.soundName = soundName == null ? "" : soundName;
        this.stop = stop;
        this.loop = loop;
        this.reasim = reasim;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.soundName = ByteBufUtils.readUTF8String(buf);
        this.stop = buf.readBoolean();
        this.loop = buf.readBoolean();
        // 显式的"有没有参数"标志，而不是靠剩余长度猜：包体长度是不可靠的约定
        if (buf.readBoolean()) {
            ReasimParams params = new ReasimParams();
            params.enabled = buf.readBoolean();
            params.echoAuto = buf.readBoolean();
            params.volume = buf.readFloat();
            params.echo = buf.readFloat();
            params.distortion = buf.readFloat();
            params.hiss = buf.readFloat();
            params.staticNoise = buf.readFloat();
            this.reasim = params.clamp();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, soundName);
        buf.writeBoolean(stop);
        buf.writeBoolean(loop);
        boolean hasParams = this.reasim != null;
        buf.writeBoolean(hasParams);
        if (hasParams) {
            buf.writeBoolean(this.reasim.enabled);
            buf.writeBoolean(this.reasim.echoAuto);
            buf.writeFloat(this.reasim.volume);
            buf.writeFloat(this.reasim.echo);
            buf.writeFloat(this.reasim.distortion);
            buf.writeFloat(this.reasim.hiss);
            buf.writeFloat(this.reasim.staticNoise);
        }
    }

    public static class Handler implements IMessageHandler<PacketPlaySound, IMessage> {

        @Override
        public IMessage onMessage(final PacketPlaySound message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    if (message.stop) {
                        ClientSoundPlayer.stop(message.pos);
                    } else {
                        ClientSoundPlayer.play(message.pos, message.soundName, message.loop, message.reasim);
                    }
                }
            });
            return null;
        }
    }
}

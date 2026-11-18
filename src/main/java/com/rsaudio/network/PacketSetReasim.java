package com.rsaudio.network;

import com.rsaudio.audio.ReasimParams;
import com.rsaudio.tile.TileAudioPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：提交一整组 reasim 参数。
 * <p>
 * <b>为什么是"整组"而不是"某一个滑条"：</b>拖动滑条时每帧都会产生一个新值，
 * 但真正有意义的只有松手后的最终值；一次发一整组，服务端不需要维护
 * "哪个字段在第几次拖动里"这种状态，也就不存在半路丢包导致两端不一致的问题。
 * 界面上只在实际变化时才发（见 {@code GuiReasim} 的 {@code send()}）。
 * <p>
 * 服务端应用之后 {@code setReasim} 会走方块自己的 {@code notifyBlockUpdate}，
 * 同步标签里带着新参数，周围的客户端下一 tick 就会重配自己的 DSP —— 不需要额外的广播包。
 */
public class PacketSetReasim implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private ReasimParams params = new ReasimParams();

    public PacketSetReasim() {
    }

    public PacketSetReasim(BlockPos pos, ReasimParams params) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.params = params == null ? new ReasimParams() : params.copy().clamp();
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        ReasimParams read = new ReasimParams();
        read.enabled = buf.readBoolean();
        read.echoAuto = buf.readBoolean();
        read.volume = buf.readFloat();
        read.echo = buf.readFloat();
        read.distortion = buf.readFloat();
        read.hiss = buf.readFloat();
        read.staticNoise = buf.readFloat();
        this.params = read.clamp();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeBoolean(params.enabled);
        buf.writeBoolean(params.echoAuto);
        buf.writeFloat(params.volume);
        buf.writeFloat(params.echo);
        buf.writeFloat(params.distortion);
        buf.writeFloat(params.hiss);
        buf.writeFloat(params.staticNoise);
    }

    public static class Handler implements IMessageHandler<PacketSetReasim, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetReasim message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 防作弊：只允许操作附近的方块（和模式包同一条界限）
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileAudioPlayer)) {
                        return;
                    }
                    ((TileAudioPlayer) te).setReasim(message.params);
                }
            });
            return null;
        }
    }
}

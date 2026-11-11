package com.rsaudio.network;

import com.rsaudio.client.ClientDmrState;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：控制柜的状态快照，供界面显示。
 * <p>
 * 为什么不用方块实体自带的 {@code getUpdateTag} 同步：那条路是"整块方块的 NBT 广播"，
 * 而反应堆的读数每 tick 都在变 —— 用它等于每 tick 广播一次 NBT，卡的是所有附近玩家。
 * 这里改成服务端<b>按自己的节奏</b>推（静止时慢、变化时快，见 {@code TileDmrController}），
 * 客户端只管把快照塞进 {@link ClientDmrState}，界面从那儿读。
 */
public class PacketDmrSync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private DmrSnapshot snapshot = new DmrSnapshot();

    public PacketDmrSync() {
    }

    public PacketDmrSync(BlockPos pos, DmrSnapshot snapshot) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.snapshot = snapshot == null ? new DmrSnapshot() : snapshot;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.snapshot = new DmrSnapshot();
        this.snapshot.fromBytes(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        snapshot.toBytes(buf);
    }

    public static class Handler implements IMessageHandler<PacketDmrSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketDmrSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientDmrState.put(message.pos, message.snapshot);
                }
            });
            return null;
        }
    }
}

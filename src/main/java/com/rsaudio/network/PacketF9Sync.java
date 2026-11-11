package com.rsaudio.network;

import com.rsaudio.client.ClientF9State;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：F9 控制柜的读数快照，供界面显示。
 * <p>
 * 和 {@link PacketDmrSync} 一样，不走方块实体的 {@code getUpdateTag}：
 * 那条路是"整块方块的 NBT 广播"，而反应堆读数每 tick 都在变 —— 用它等于每 tick
 * 广播一次 NBT，卡的是所有附近玩家。这里由服务端按自己的节奏推（见 {@code TileF9Controller}），
 * 客户端只管把快照塞进 {@link ClientF9State}。
 */
public class PacketF9Sync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private F9Snapshot snapshot = new F9Snapshot();

    public PacketF9Sync() {
    }

    public PacketF9Sync(BlockPos pos, F9Snapshot snapshot) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.snapshot = snapshot == null ? new F9Snapshot() : snapshot;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.snapshot = new F9Snapshot();
        this.snapshot.fromBytes(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        snapshot.toBytes(buf);
    }

    public static class Handler implements IMessageHandler<PacketF9Sync, IMessage> {

        @Override
        public IMessage onMessage(final PacketF9Sync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientF9State.put(message.pos, message.snapshot);
                }
            });
            return null;
        }
    }
}

package com.rsaudio.network;

import com.rsaudio.client.ClientElevatorState;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：电梯读数快照。
 * <p>
 * 和 {@link PacketF9Sync} 一样，<b>不走</b>方块实体的 {@code getUpdateTag}：
 * 那条路是"整块方块的 NBT 广播"，而电梯的轿厢位置每刻都在动 ——
 * 用它等于每刻广播一次 NBT，卡的是附近所有人。这里由服务端按自己的节奏推
 * （见 {@code TileElevatorController}），客户端只管把快照塞进 {@link ClientElevatorState}。
 */
public class PacketElevatorSync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private ElevatorSnapshot snapshot = ElevatorSnapshot.empty();

    public PacketElevatorSync() {
    }

    public PacketElevatorSync(BlockPos pos, ElevatorSnapshot snapshot) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.snapshot = snapshot == null ? ElevatorSnapshot.empty() : snapshot;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.snapshot = ElevatorSnapshot.fromBytes(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        snapshot.toBytes(buf);
    }

    public static class Handler implements IMessageHandler<PacketElevatorSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketElevatorSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    ClientElevatorState.put(message.pos, message.snapshot);
                }
            });
            return null;
        }
    }
}

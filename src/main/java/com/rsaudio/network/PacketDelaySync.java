package com.rsaudio.network;

import com.rsaudio.tile.TileRedstoneDelayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：同步某个红石延迟器的延迟时间。
 * <p>
 * 顶面刻度条是 {@code getActualState} 现算的，所以除了改客户端方块实体的值，
 * 还得主动让这一块重建渲染，否则要等到下次因为别的原因重建才会更新。
 */
public class PacketDelaySync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int ticks;

    public PacketDelaySync() {
    }

    public PacketDelaySync(BlockPos pos, int ticks) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.ticks = ticks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.ticks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeInt(ticks);
    }

    public static class Handler implements IMessageHandler<PacketDelaySync, IMessage> {

        @Override
        public IMessage onMessage(final PacketDelaySync message, MessageContext ctx) {
            final Minecraft mc = Minecraft.getMinecraft();
            mc.addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = mc.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (te instanceof TileRedstoneDelayer) {
                        ((TileRedstoneDelayer) te).setDelayTicksClient(message.ticks);
                    }
                    world.markBlockRangeForRenderUpdate(message.pos, message.pos);
                }
            });
            return null;
        }
    }
}

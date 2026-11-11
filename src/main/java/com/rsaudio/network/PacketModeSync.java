package com.rsaudio.network;

import com.rsaudio.tile.TileAudioPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 服务端 -> 客户端：同步音频播放器的播放模式与"播完输出红石信号"开关 */
public class PacketModeSync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int mode = 0;
    private boolean outputOnFinish = false;

    public PacketModeSync() {
    }

    public PacketModeSync(BlockPos pos, int mode, boolean outputOnFinish) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.mode = mode;
        this.outputOnFinish = outputOnFinish;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.mode = buf.readByte();
        this.outputOnFinish = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(mode);
        buf.writeBoolean(outputOnFinish);
    }

    public static class Handler implements IMessageHandler<PacketModeSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketModeSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = Minecraft.getMinecraft().world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (te instanceof TileAudioPlayer) {
                        TileAudioPlayer tile = (TileAudioPlayer) te;
                        tile.applySyncedMode(TileAudioPlayer.Mode.byOrdinal(message.mode), message.outputOnFinish);
                    }
                }
            });
            return null;
        }
    }
}

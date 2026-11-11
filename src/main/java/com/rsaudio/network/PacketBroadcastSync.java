package com.rsaudio.network;

import com.rsaudio.tile.IBroadcastHolder;
import com.rsaudio.util.Broadcaster;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 服务端 -> 客户端：同步方块的"聊天栏播报"文字与颜色 */
public class PacketBroadcastSync implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private String text = "";
    private int color = Broadcaster.DEFAULT_COLOR;

    public PacketBroadcastSync() {
    }

    public PacketBroadcastSync(BlockPos pos, String text, int color) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.text = text == null ? "" : text;
        this.color = color;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.text = ByteBufUtils.readUTF8String(buf);
        this.color = buf.readByte();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, text == null ? "" : text);
        buf.writeByte(color);
    }

    public static class Handler implements IMessageHandler<PacketBroadcastSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketBroadcastSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = Minecraft.getMinecraft().world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (te instanceof IBroadcastHolder) {
                        ((IBroadcastHolder) te).applySyncedBroadcast(message.text, message.color);
                    }
                }
            });
            return null;
        }
    }
}

package com.rsaudio.network;

import com.rsaudio.tile.TileAudioPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/** 客户端 -> 服务端：设置某个音频播放器的音频文件 */
public class PacketSetSound implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private String soundName = "";

    public PacketSetSound() {
    }

    public PacketSetSound(BlockPos pos, String soundName) {
        this.pos = pos;
        this.soundName = soundName == null ? "" : soundName;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.soundName = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        ByteBufUtils.writeUTF8String(buf, soundName);
    }

    public static class Handler implements IMessageHandler<PacketSetSound, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetSound message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 防作弊：只允许操作附近的方块
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (te instanceof TileAudioPlayer) {
                        ((TileAudioPlayer) te).setSoundName(message.soundName);
                    }
                }
            });
            return null;
        }
    }
}

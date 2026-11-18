package com.rsaudio.network;

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
 * 客户端 -> 服务端：这首放完了。
 * <p>
 * 服务端没有"播放结束"的概念（声音只在客户端），所以脉冲模式要靠客户端回报。
 * 一台播放器周围可能有好几台客户端都在放同一首，会各发一条，服务端只认第一条。
 */
public class PacketSoundFinished implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;

    public PacketSoundFinished() {
    }

    public PacketSoundFinished(BlockPos pos) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
    }

    public static class Handler implements IMessageHandler<PacketSoundFinished, IMessage> {

        @Override
        public IMessage onMessage(final PacketSoundFinished message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 只接受附近的客户端回报，防止远程玩家乱报
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 192.0D * 192.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (te instanceof TileAudioPlayer) {
                        ((TileAudioPlayer) te).onPlaybackFinished();
                    }
                }
            });
            return null;
        }
    }
}

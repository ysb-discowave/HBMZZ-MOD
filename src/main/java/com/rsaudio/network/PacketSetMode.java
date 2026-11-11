package com.rsaudio.network;

import com.rsaudio.tile.TileAudioPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：设置音频播放器的播放模式 + "播完输出红石信号"开关。
 * <p>
 * 服务端应用后会把最终结果广播回周围客户端（{@link PacketModeSync}），
 * 这样别的玩家打开界面时看到的也是最新值。
 */
public class PacketSetMode implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int mode = 0;
    private boolean outputOnFinish = false;

    public PacketSetMode() {
    }

    public PacketSetMode(BlockPos pos, int mode, boolean outputOnFinish) {
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

    public static class Handler implements IMessageHandler<PacketSetMode, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetMode message, MessageContext ctx) {
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
                    if (!(te instanceof TileAudioPlayer)) {
                        return;
                    }
                    TileAudioPlayer tile = (TileAudioPlayer) te;
                    tile.setOutputOnFinish(message.outputOnFinish);
                    tile.setMode(TileAudioPlayer.Mode.byOrdinal(message.mode));

                    // 广播给周围客户端，别的玩家打开界面时看到的就是最新值
                    PacketHandler.INSTANCE.sendToAllAround(
                            new PacketModeSync(message.pos, tile.getMode().ordinal(), tile.isOutputOnFinish()),
                            new NetworkRegistry.TargetPoint(world.provider.getDimension(),
                                    message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                                    message.pos.getZ() + 0.5D, 128.0D));
                }
            });
            return null;
        }
    }
}

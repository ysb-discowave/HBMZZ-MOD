package com.rsaudio.network;

import com.rsaudio.tile.TileMonitorConsole;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：监控控制台的设置（看哪个频道 / 屏幕上要不要字幕条）。
 * <p>
 * 为什么要发给服务端：控制台屏幕上的画面<b>每个客户端各画各的</b>，
 * 但"你看的是哪个频道"必须是共享状态 —— 否则同一个控制台，
 * 两个人看到的画面不一样，那就不叫一台设备了。
 */
public class PacketSetMonitor implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int channel;
    private boolean overlay;

    public PacketSetMonitor() {
    }

    public PacketSetMonitor(BlockPos pos, int channel, boolean overlay) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.channel = channel;
        this.overlay = overlay;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.channel = buf.readInt();
        this.overlay = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeInt(channel);
        buf.writeBoolean(overlay);
    }

    public static class Handler implements IMessageHandler<PacketSetMonitor, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetMonitor message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileMonitorConsole)) {
                        return;
                    }
                    TileMonitorConsole tile = (TileMonitorConsole) te;
                    tile.setChannel(message.channel);
                    tile.setOverlay(message.overlay);
                }
            });
            return null;
        }
    }
}

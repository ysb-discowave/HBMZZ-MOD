package com.rsaudio.network;

import com.rsaudio.tile.TileAlarmLight;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：警报灯的频率 / 蜂鸣设置。
 * <p>
 * 只有这一个方向：设置完之后方块实体会用 {@code getUpdateTag} 把值同步回来
 * （两个整数而已，不值当再写一个专门的同步包）。
 */
public class PacketSetAlarm implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int period;
    private boolean beep;

    public PacketSetAlarm() {
    }

    public PacketSetAlarm(BlockPos pos, int period, boolean beep) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.period = period;
        this.beep = beep;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.period = buf.readInt();
        this.beep = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeInt(period);
        buf.writeBoolean(beep);
    }

    public static class Handler implements IMessageHandler<PacketSetAlarm, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetAlarm message, MessageContext ctx) {
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
                    if (!(te instanceof TileAlarmLight)) {
                        return;
                    }
                    TileAlarmLight tile = (TileAlarmLight) te;
                    tile.setPeriod(message.period);
                    tile.setBeep(message.beep);
                }
            });
            return null;
        }
    }
}

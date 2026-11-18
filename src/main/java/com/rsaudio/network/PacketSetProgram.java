package com.rsaudio.network;

import com.rsaudio.tile.TileRedstoneComputer;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：红石计算机界面的操作。
 * <p>
 * 程序文本本身就是"用户输入"，必须由服务端存（存档里也只存服务端这一份），
 * 所以界面里的每一次改动/点运行都要发回来。
 * <p>
 * {@link #VIEWING} 用来告诉服务端"我这台界面还开着"：服务端据此每 5 tick 推一次调试数据，
 * 关界面时发 false —— <b>不这么做的话，服务端会一直往一个没人看的界面推数据</b>。
 */
public class PacketSetProgram implements IMessage {

    public static final byte SET_TEXT = 0;
    public static final byte RUN = 1;
    public static final byte STOP = 2;
    public static final byte RESET = 3;
    public static final byte VIEWING = 4;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action;
    private String text = "";
    private boolean viewing;

    public PacketSetProgram() {
    }

    public static PacketSetProgram text(BlockPos pos, String text) {
        PacketSetProgram packet = new PacketSetProgram();
        packet.pos = pos == null ? BlockPos.ORIGIN : pos;
        packet.action = SET_TEXT;
        packet.text = text == null ? "" : text;
        return packet;
    }

    public static PacketSetProgram action(BlockPos pos, byte action) {
        PacketSetProgram packet = new PacketSetProgram();
        packet.pos = pos == null ? BlockPos.ORIGIN : pos;
        packet.action = action;
        return packet;
    }

    public static PacketSetProgram viewing(BlockPos pos, boolean viewing) {
        PacketSetProgram packet = new PacketSetProgram();
        packet.pos = pos == null ? BlockPos.ORIGIN : pos;
        packet.action = VIEWING;
        packet.viewing = viewing;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.text = ByteBufUtils.readUTF8String(buf);
        this.viewing = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        ByteBufUtils.writeUTF8String(buf, text);
        buf.writeBoolean(viewing);
    }

    public static class Handler implements IMessageHandler<PacketSetProgram, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetProgram message, MessageContext ctx) {
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
                    if (!(te instanceof TileRedstoneComputer)) {
                        return;
                    }
                    TileRedstoneComputer tile = (TileRedstoneComputer) te;
                    switch (message.action) {
                        case SET_TEXT:
                            tile.setSource(message.text);
                            break;
                        case RUN:
                            tile.run();
                            break;
                        case STOP:
                            tile.stop();
                            break;
                        case RESET:
                            tile.resetProgram();
                            break;
                        default:
                            if (message.viewing) {
                                tile.addViewer(player.getUniqueID());
                            } else {
                                tile.removeViewer(player.getUniqueID());
                            }
                            break;
                    }
                }
            });
            return null;
        }
    }
}

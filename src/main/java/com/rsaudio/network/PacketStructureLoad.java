package com.rsaudio.network;

import com.rsaudio.tile.TileStructureLoader;
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
 * 客户端 -> 服务端：结构加载器界面的操作（设置结构名 / 调偏移 / 立刻加载）。
 * <p>
 * 方块实体自己的参数用 {@code getUpdateTag} 同步回客户端（值很小），
 * 所以这里只需要单向的"设值"包。
 */
public class PacketStructureLoad implements IMessage {

    public static final byte SET_NAME = 0;
    public static final byte SET_OFFSET = 1;
    public static final byte LOAD = 2;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action;
    private String name = "";
    private int offX;
    private int offY;
    private int offZ;

    public PacketStructureLoad() {
    }

    public static PacketStructureLoad name(BlockPos pos, String name) {
        PacketStructureLoad packet = new PacketStructureLoad();
        packet.pos = pos == null ? BlockPos.ORIGIN : pos;
        packet.action = SET_NAME;
        packet.name = name == null ? "" : name;
        return packet;
    }

    public static PacketStructureLoad offset(BlockPos pos, int x, int y, int z) {
        PacketStructureLoad packet = new PacketStructureLoad();
        packet.pos = pos == null ? BlockPos.ORIGIN : pos;
        packet.action = SET_OFFSET;
        packet.offX = x;
        packet.offY = y;
        packet.offZ = z;
        return packet;
    }

    public static PacketStructureLoad load(BlockPos pos) {
        PacketStructureLoad packet = new PacketStructureLoad();
        packet.pos = pos == null ? BlockPos.ORIGIN : pos;
        packet.action = LOAD;
        return packet;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.name = ByteBufUtils.readUTF8String(buf);
        this.offX = buf.readInt();
        this.offY = buf.readInt();
        this.offZ = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        ByteBufUtils.writeUTF8String(buf, name);
        buf.writeInt(offX);
        buf.writeInt(offY);
        buf.writeInt(offZ);
    }

    public static class Handler implements IMessageHandler<PacketStructureLoad, IMessage> {

        @Override
        public IMessage onMessage(final PacketStructureLoad message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 防作弊：只允许操作附近的方块（和延迟器界面一致）
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileStructureLoader)) {
                        return;
                    }
                    TileStructureLoader tile = (TileStructureLoader) te;
                    switch (message.action) {
                        case SET_NAME:
                            tile.setStructure(message.name);
                            break;
                        case SET_OFFSET:
                            tile.setOffset(message.offX, message.offY, message.offZ);
                            break;
                        default:
                            tile.loadNow(player);
                            break;
                    }
                }
            });
            return null;
        }
    }
}

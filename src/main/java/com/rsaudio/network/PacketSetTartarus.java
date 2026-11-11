package com.rsaudio.network;

import com.rsaudio.tile.TileTartarus;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：塔塔鲁斯设置界面的操作。
 * <p>
 * 动作码：{@link #SET_RADIUS}（未建造时改半径）/ {@link #BUILD}（建造）/ {@link #REMOVE}（拆除）/
 * {@link #OPEN}（开）/ {@link #CLOSE}（关）。
 */
public class PacketSetTartarus implements IMessage {

    public static final byte SET_RADIUS = 0;
    public static final byte BUILD = 1;
    public static final byte REMOVE = 2;
    public static final byte OPEN = 3;
    public static final byte CLOSE = 4;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action;
    private int radius;

    public PacketSetTartarus() {
    }

    public PacketSetTartarus(BlockPos pos, byte action, int radius) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.action = action;
        this.radius = radius;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.radius = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        buf.writeInt(radius);
    }

    public static class Handler implements IMessageHandler<PacketSetTartarus, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetTartarus message, MessageContext ctx) {
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
                    if (!(te instanceof TileTartarus)) {
                        return;
                    }
                    TileTartarus tile = (TileTartarus) te;
                    switch (message.action) {
                        case SET_RADIUS:
                            if (!tile.isBuilt()) {
                                tile.setRadius(message.radius);
                            } else {
                                player.sendStatusMessage(new TextComponentString("已建造：先拆除再改尺寸"), true);
                            }
                            break;
                        case BUILD: {
                            String err = tile.build(player);
                            if (err != null) {
                                player.sendStatusMessage(new TextComponentString(err), true);
                            } else {
                                player.sendStatusMessage(new TextComponentString(tile.describe()), true);
                            }
                            break;
                        }
                        case REMOVE:
                            tile.removeHatch(false);
                            player.sendStatusMessage(new TextComponentString("已拆除舱盖"), true);
                            break;
                        case OPEN:
                            tile.requestOpen(true, player);
                            break;
                        case CLOSE:
                            tile.requestOpen(false, player);
                            break;
                        default:
                            break;
                    }
                }
            });
            return null;
        }
    }
}

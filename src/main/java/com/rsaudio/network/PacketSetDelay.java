package com.rsaudio.network;

import com.rsaudio.block.BlockRedstoneDelayer;
import com.rsaudio.tile.TileRedstoneDelayer;
import com.rsaudio.util.DelayText;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：设置某个红石延迟器的延迟时间。
 * <p>
 * 服务端校验通过后会把结果广播回周围客户端（{@link PacketDelaySync}），
 * 这样界面里改的值能立刻反映到所有能看到这个方块的玩家（也包括顶面刻度条）。
 */
public class PacketSetDelay implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int ticks;

    public PacketSetDelay() {
    }

    public PacketSetDelay(BlockPos pos, int ticks) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.ticks = ticks;
    }

    public BlockPos getPos() {
        return pos;
    }

    public int getTicks() {
        return ticks;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.ticks = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeInt(ticks);
    }

    public static class Handler implements IMessageHandler<PacketSetDelay, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetDelay message, MessageContext ctx) {
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
                    if (!(te instanceof TileRedstoneDelayer)) {
                        return;
                    }
                    int value = BlockRedstoneDelayer.clampDelay(message.ticks);
                    TileRedstoneDelayer tile = (TileRedstoneDelayer) te;
                    tile.setDelayTicks(value);

                    IBlockState state = world.getBlockState(message.pos);
                    if (state.getBlock() instanceof BlockRedstoneDelayer) {
                        ((BlockRedstoneDelayer) state.getBlock()).rescheduleIfWaiting(world, message.pos, state);
                    }

                    // 广播给周围客户端，保证显示值同步
                    PacketHandler.INSTANCE.sendToAllAround(new PacketDelaySync(message.pos, value),
                            new NetworkRegistry.TargetPoint(world.provider.getDimension(),
                                    message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                                    message.pos.getZ() + 0.5D, 128.0D));

                    player.sendStatusMessage(new TextComponentTranslation("chat.rsaudio.delay_set",
                            String.valueOf(value), DelayText.formatDuration(value)), true);
                }
            });
            return null;
        }
    }
}

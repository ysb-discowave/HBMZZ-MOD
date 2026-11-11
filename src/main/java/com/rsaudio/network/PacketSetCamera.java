package com.rsaudio.network;

import com.rsaudio.tile.TileMonitorCamera;
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
 * 客户端 -> 服务端：监控摄像头的设置（频道 / 视野 / 云台角度 / 启用 / 名字）。
 * <p>
 * 只有一个方向：写完之后服务端方块实体会用 {@code getUpdateTag} 把值同步回所有客户端
 * （见 {@code TileMonitorCamera}），所以不需要再写一个专门的下行包。
 * <p>
 * 这是全模组里唯一一个带字符串的"设置包"，所以用 {@link ByteBufUtils#writeUTF8String}
 * 而不是自己算长度 —— 中文名字占的字节数和字符数不一样，自己写很容易错。
 * <p>
 * ★ 云台角度走 {@code writeInt} 而不是 {@code writeFloat}：角度在方块实体里本来就是
 * 整数度（界面上一次转 {@link TileMonitorCamera#YAW_STEP} 度），用 int 传输就<b>不会
 * 出现浮点往返误差</b>——否则"服务端存 14.999998、客户端存 15.000001"会让
 * {@code setYawOffset} 里的相等判断失效，每设置一次都白多推一次区块更新。
 */
public class PacketSetCamera implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private int channel;
    private int fov;
    private int yaw;
    private int pitch;
    private boolean enabled;
    private String label = "";

    public PacketSetCamera() {
    }

    public PacketSetCamera(BlockPos pos, int channel, int fov, int yaw, int pitch, boolean enabled, String label) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.channel = channel;
        this.fov = fov;
        this.yaw = yaw;
        this.pitch = pitch;
        this.enabled = enabled;
        this.label = label == null ? "" : label;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.channel = buf.readInt();
        this.fov = buf.readInt();
        this.yaw = buf.readInt();
        this.pitch = buf.readInt();
        this.enabled = buf.readBoolean();
        this.label = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeInt(channel);
        buf.writeInt(fov);
        buf.writeInt(yaw);
        buf.writeInt(pitch);
        buf.writeBoolean(enabled);
        ByteBufUtils.writeUTF8String(buf, label);
    }

    public static class Handler implements IMessageHandler<PacketSetCamera, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetCamera message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 距离校验：和别的设置包一致，防止改了很远处的别人的方块
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileMonitorCamera)) {
                        return;
                    }
                    TileMonitorCamera tile = (TileMonitorCamera) te;
                    tile.setChannel(message.channel);
                    tile.setFov(message.fov);
                    tile.setYawOffset(message.yaw);
                    tile.setPitchOffset(message.pitch);
                    tile.setEnabled(message.enabled);
                    tile.setLabel(message.label);
                }
            });
            return null;
        }
    }
}

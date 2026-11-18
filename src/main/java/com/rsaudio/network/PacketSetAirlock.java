package com.rsaudio.network;

import com.rsaudio.tile.TileAirlockController;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：气闸控制器的设置与操作。
 *
 * <h3>三个动作</h3>
 * <ul>
 *   <li>{@link #APPLY}：整套参数覆盖（频道 / 净化时长 / 超时 / 播报开关）。</li>
 *   <li>{@link #ACTIVATE}：走一轮完整循环。和空手右键走同一条路 ——
 *       界面上的「启动」按钮不该有另一套语义。</li>
 *   <li>{@link #CANCEL}：急停。两侧保持关闭。</li>
 * </ul>
 *
 * <h3>为什么激活要发参数过来</h3>
 * 和 {@code PacketSetDoor} / {@code PacketSetTerminal} 一样是<b>整体覆盖</b>：
 * 玩家在界面里可能刚把净化时长从 180 调到 60，然后直接按「启动」——
 * 如果「启动」只发一个动作码，那次调节就丢了，玩家会看到"净化还是走了 9 秒"。
 * 所以每个动作都带着当前整套参数。
 *
 * <h3>距离卡 8 格</h3>
 * 比门禁终端的 64 格严得多：气闸是"走进去、按一下"的东西，没有远距离操作的理由。
 * 卡得紧一点，也顺手堵掉了"站在外面遥控开门"这类绕过舱室的做法。
 */
public class PacketSetAirlock implements IMessage {

    /** 保存设置 */
    public static final byte APPLY = 0;
    /** 启动一轮循环 */
    public static final byte ACTIVATE = 1;
    /** 急停 */
    public static final byte CANCEL = 2;

    /** 操作距离（格） */
    public static final double MAX_DISTANCE = 8.0D;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action;
    private int channel;
    private int deconTicks;
    private int lockTimeout;
    private boolean announce;

    public PacketSetAirlock() {
    }

    public PacketSetAirlock(BlockPos pos, byte action, int channel, int deconTicks,
                            int lockTimeout, boolean announce) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.action = action;
        this.channel = channel;
        this.deconTicks = deconTicks;
        this.lockTimeout = lockTimeout;
        this.announce = announce;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.channel = buf.readInt();
        this.deconTicks = buf.readInt();
        this.lockTimeout = buf.readInt();
        this.announce = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        buf.writeInt(channel);
        buf.writeInt(deconTicks);
        buf.writeInt(lockTimeout);
        buf.writeBoolean(announce);
    }

    public static class Handler implements IMessageHandler<PacketSetAirlock, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetAirlock message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    double limit = MAX_DISTANCE * MAX_DISTANCE;
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > limit) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileAirlockController)) {
                        return;
                    }
                    TileAirlockController tile = (TileAirlockController) te;

                    // 先落参数：三个动作都带着整套参数，不然玩家刚调的值会被丢掉
                    tile.applySettings(message.channel, message.deconTicks,
                            message.lockTimeout, message.announce);

                    switch (message.action) {
                        case ACTIVATE:
                            tile.activate(player);
                            break;
                        case CANCEL:
                            tile.cancel();
                            break;
                        default:
                            // APPLY：只改配置，不动循环状态。
                            // ★ 刻意不在保存时顺手启动一轮 —— 玩家在界面里连点几下加号，
                            //   不该把气闸跑起来好几次
                            break;
                    }
                }
            });
            return null;
        }
    }
}

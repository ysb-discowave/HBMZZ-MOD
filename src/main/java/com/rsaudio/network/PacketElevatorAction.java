package com.rsaudio.network;

import com.rsaudio.tile.TileElevatorController;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：电梯界面上的每一次操作。
 * <p>
 * 和 {@link PacketF9Action} 同一个约定：一个"动作号 + 一个整数参数"的通用结构。
 * 界面上的按钮有十来种，逐一定义会让 {@code PacketHandler} 的 id 表迅速膨胀，
 * 而那张表的 id <b>只能追加不能重排</b>。
 *
 * <h3>★ 参数一律当不可信</h3>
 * 动作号越界、层号越界、方向不是 ±1，全部在服务端挡掉。
 * 客户端只负责把玩家点了什么原样报上来 —— 它<b>不</b>做任何本地预判，
 * 否则会出现"界面显示已受理、电梯其实没理它"这种最难查的一类不一致。
 */
public class PacketElevatorAction implements IMessage {

    /** 呼叫某一层。arg = 层在层表里的下标 */
    public static final int ACT_CALL = 0;
    /** 开门键（门开着就重新计时）。arg 忽略 */
    public static final int ACT_HOLD_DOOR = 1;
    /** 关门键。arg 忽略 */
    public static final int ACT_CLOSE_DOOR = 2;
    /** 取消所有呼叫。arg 忽略 */
    public static final int ACT_CLEAR_CALLS = 3;
    /** 立刻重扫井道（玩家刚砌完井道，不想等那 20 刻）。arg 忽略 */
    public static final int ACT_RESCAN = 4;

    private BlockPos pos = BlockPos.ORIGIN;
    private int action;
    private int arg;

    public PacketElevatorAction() {
    }

    public PacketElevatorAction(BlockPos pos, int action, int arg) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.action = action;
        this.arg = arg;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.arg = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        buf.writeInt(arg);
    }

    public static class Handler implements IMessageHandler<PacketElevatorAction, IMessage> {

        @Override
        public IMessage onMessage(final PacketElevatorAction message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 和警报灯 / 广播器 / DMR / F9 同一条约定：
                    // 只有真站在机器旁边才操作得动它（防"远程遥控别人家的电梯"）。
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileElevatorController)) {
                        return;
                    }
                    ((TileElevatorController) te).handleAction(player, message.action, message.arg);
                }
            });
            return null;
        }
    }
}

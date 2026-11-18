package com.rsaudio.network;

import com.rsaudio.tile.TileF9Controller;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：F9 控制柜上的每一次操作。
 * <p>
 * 和 {@link PacketDmrAction} 同一个约定：一个"动作号 + 一个整数参数"的通用结构，
 * 而不是每个按钮发一个包 —— 控制柜有十来种操作，逐一定义会让 {@code PacketHandler}
 * 的 id 表迅速膨胀，而那张表的 id 只能追加不能重排。
 * <p>
 * ★ 参数一律当不可信：动作号越界、棒索引越界、子系统位不在掩码里，全部在服务端挡掉。
 * 客户端只负责把玩家点了什么原样报上来。
 *
 * <h3>★ 拉杆为什么需要"松手"这个动作</h3>
 * 内核的 {@code moveRods(dir)} 语义是"这一 tick 之后还接着走一秒"（每次调用重挂一秒），
 * 所以<b>按住</b>在 tick 粒度上就等于"每 tick 都再调一次"。界面按住时每两 tick 发一次
 * {@link #ACT_MOVE_ROD}，松开时发 {@link #ACT_RELEASE_ROD} ——
 * 少了后者，连续移动模式下棒会一直走到插入度撞到 0 或 100 才停。
 */
public class PacketF9Action implements IMessage {

    /** 启动反应堆（冷停堆 -> 运行）。前置条件由内核判（安全系统必须开着） */
    public static final int ACT_START = 0;
    /** 推/拉控制棒。arg = +1 拔（升功率）/ -1 插（降功率） */
    public static final int ACT_MOVE_ROD = 1;
    /** 松开拉杆 */
    public static final int ACT_RELEASE_ROD = 2;
    /** 停住拉杆（"停"按钮） */
    public static final int ACT_STOP_ROD = 3;
    /** 切换一个子系统。arg = 子系统位（{@code F9Spec.SUB_*}） */
    public static final int ACT_TOGGLE_SUB = 4;
    /** 接/断一个转换器活塞。arg = 0..3 */
    public static final int ACT_TOGGLE_CONVERTER = 5;
    /** 开/关一个泄压阀。arg = 0..3 */
    public static final int ACT_TOGGLE_VALVE = 6;
    /** 选中控制棒。arg = -1 全局 / 0..8 单根 */
    public static final int ACT_SELECT_ROD = 7;
    /** 选中分区。arg = 0..2 */
    public static final int ACT_SELECT_SECTOR = 8;
    /** 同步到电网 */
    public static final int ACT_SYNC = 9;
    /** 解列 */
    public static final int ACT_DESYNC = 10;
    /** 停机（泄压 -> 解列 -> 插棒 -> 回待机） */
    public static final int ACT_SHUTDOWN = 11;

    private BlockPos pos = BlockPos.ORIGIN;
    private int action;
    private int arg;

    public PacketF9Action() {
    }

    public PacketF9Action(BlockPos pos, int action, int arg) {
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

    public static class Handler implements IMessageHandler<PacketF9Action, IMessage> {

        @Override
        public IMessage onMessage(final PacketF9Action message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 和警报灯 / 广播器 / DMR 同一条约定：只有真站在机器旁边才操作得动它
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileF9Controller)) {
                        return;
                    }
                    ((TileF9Controller) te).handleAction(player, message.action, message.arg);
                }
            });
            return null;
        }
    }
}

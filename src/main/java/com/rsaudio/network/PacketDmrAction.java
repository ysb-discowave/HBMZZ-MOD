package com.rsaudio.network;

import com.rsaudio.tile.TileDmrController;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：控制柜上的每一次操作。
 * <p>
 * 用一个"动作号 + 一个整数参数"的通用结构，而不是给每个按钮发一个包 ——
 * 控制柜有十来个操作，逐一定义会让 {@code PacketHandler} 的 id 表迅速膨胀，
 * 而那张表的 id 是<b>只能追加不能重排</b>的（见 {@code PacketHandler} 的注释）。
 * <p>
 * ★ 参数一律当不可信：动作号越界、停机码长度不对、子系统位不在掩码里，
 * 全部在服务端这一侧挡掉。客户端只负责把玩家点了什么原样报上来。
 */
public class PacketDmrAction implements IMessage {

    /** 切换一个子系统（arg = 子系统位） */
    public static final int ACT_TOGGLE_SUB = 0;
    /** 设定输出负荷百分比（arg = %） */
    public static final int ACT_SET_LOAD = 1;
    /** 主点火按钮 */
    public static final int ACT_IGNITE = 2;
    /** 正常停堆冷却 */
    public static final int ACT_COOLDOWN = 3;
    /** 进入维护模式 */
    public static final int ACT_ENTER_MAINT = 4;
    /** 在维护模式里装一块新燃料单元 */
    public static final int ACT_INSTALL_CELL = 5;
    /** 结束维护、重启反应堆 */
    public static final int ACT_EXIT_MAINT = 6;
    /** 提交六位停机码（arg = 码） */
    public static final int ACT_SUBMIT_CODE = 7;
    /** 硬重启功率激光（对应 QSERF 的 Power Laser Hard Restart） */
    public static final int ACT_REPAIR_LASERS = 8;
    /**
     * 在堆芯腔体里拔下一块燃料单元（Phase 2 的物理停机通道）。
     * <p>
     * 服务端会先核对玩家是不是真的站在腔体里；不是就直接拒掉。
     * 拔料的人由服务端自己取（{@code player.getEntityId()}），客户端报什么都不算数 ——
     * 不然"三个人各拔一块"这条规则随便改个包就绕过去了。
     */
    public static final int ACT_PULL_CELL = 9;

    private BlockPos pos = BlockPos.ORIGIN;
    private int action;
    private int arg;

    public PacketDmrAction() {
    }

    public PacketDmrAction(BlockPos pos, int action, int arg) {
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

    public static class Handler implements IMessageHandler<PacketDmrAction, IMessage> {

        @Override
        public IMessage onMessage(final PacketDmrAction message, MessageContext ctx) {
            final EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServerWorld().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    World world = player.world;
                    if (world == null || !world.isBlockLoaded(message.pos)) {
                        return;
                    }
                    // 和警报灯/广播器同一条约定：只有真站在机器旁边才操作得动它
                    if (player.getDistanceSq(message.pos.getX() + 0.5D, message.pos.getY() + 0.5D,
                            message.pos.getZ() + 0.5D) > 64.0D * 64.0D) {
                        return;
                    }
                    TileEntity te = world.getTileEntity(message.pos);
                    if (!(te instanceof TileDmrController)) {
                        return;
                    }
                    ((TileDmrController) te).handleAction(player, message.action, message.arg);
                }
            });
            return null;
        }
    }
}

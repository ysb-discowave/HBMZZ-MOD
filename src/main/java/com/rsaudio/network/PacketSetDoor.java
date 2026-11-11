package com.rsaudio.network;

import com.rsaudio.RsAudio;
import com.rsaudio.block.BlockBlastDoor;
import com.rsaudio.tile.TileBlastDoor;
import com.rsaudio.util.WandHandler;
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
 * 客户端 -> 服务端：防核门设置界面的操作。
 * <p>
 * 每次都把<b>整套参数</b>一起发过去（而不是"这次改了哪一项"）：
 * 界面上的值就是玩家的意图，整体覆盖比增量更新简单得多，也不会因为丢包/顺序问题出现
 * "服务端以为宽是 3、客户端显示 5"这种偏差。
 * <p>
 * ★ 门禁参数（频道/联锁/循环/重型/分级间隔）走的是同一条 {@code APPLY} 路径：
 * 它们不改几何，所以服务端能<b>就地生效</b>，不像宽高那样需要先拆门。
 */
public class PacketSetDoor implements IMessage {

    public static final byte APPLY = 0;
    public static final byte BUILD = 1;
    public static final byte REMOVE = 2;
    public static final byte OPEN = 3;
    public static final byte CLOSE = 4;
    /** 翻转门闩（封锁 / 解除封锁） */
    public static final byte TOGGLE_LOCK = 5;

    private BlockPos pos = BlockPos.ORIGIN;
    private byte action;
    private int width;
    private int height;
    private byte facing;
    private boolean retractUp;
    private boolean invert;
    private int channel;
    private boolean interlock;
    private int cycle;
    private boolean heavy;
    private int stageDelay;
    /** 门上旋转警报灯：是否启用 */
    private boolean alarmLights = true;
    /** 警报灯颜色下标（0..5，复用 BlockAlarmLight 调色板） */
    private int alarmColor = 0;

    public PacketSetDoor() {
    }

    public PacketSetDoor(BlockPos pos, byte action, int width, int height, byte facing,
                         boolean retractUp, boolean invert, int channel, boolean interlock,
                         int cycle, boolean heavy, int stageDelay, boolean alarmLights, int alarmColor) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.action = action;
        this.width = width;
        this.height = height;
        this.facing = facing;
        this.retractUp = retractUp;
        this.invert = invert;
        this.channel = channel;
        this.interlock = interlock;
        this.cycle = cycle;
        this.heavy = heavy;
        this.stageDelay = stageDelay;
        this.alarmLights = alarmLights;
        this.alarmColor = alarmColor;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.action = buf.readByte();
        this.width = buf.readInt();
        this.height = buf.readInt();
        this.facing = buf.readByte();
        this.retractUp = buf.readBoolean();
        this.invert = buf.readBoolean();
        this.channel = buf.readInt();
        this.interlock = buf.readBoolean();
        this.cycle = buf.readInt();
        this.heavy = buf.readBoolean();
        this.stageDelay = buf.readInt();
        this.alarmLights = buf.readBoolean();
        this.alarmColor = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(action);
        buf.writeInt(width);
        buf.writeInt(height);
        buf.writeByte(facing);
        buf.writeBoolean(retractUp);
        buf.writeBoolean(invert);
        buf.writeInt(channel);
        buf.writeBoolean(interlock);
        buf.writeInt(cycle);
        buf.writeBoolean(heavy);
        buf.writeInt(stageDelay);
        buf.writeBoolean(alarmLights);
        buf.writeInt(alarmColor);
    }

    public static class Handler implements IMessageHandler<PacketSetDoor, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetDoor message, MessageContext ctx) {
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
                    if (!(te instanceof TileBlastDoor)) {
                        return;
                    }
                    TileBlastDoor tile = (TileBlastDoor) te;

                    // 朝向是方块状态，不是方块实体字段：改朝向要换方块状态
                    net.minecraft.util.EnumFacing facing =
                            net.minecraft.util.EnumFacing.getHorizontal(message.facing & 3);
                    if (world.getBlockState(message.pos).getBlock() instanceof BlockBlastDoor) {
                        net.minecraft.block.state.IBlockState state = world.getBlockState(message.pos);
                        if (state.getValue(BlockBlastDoor.FACING) != facing) {
                            // 换朝向前先把旧门拆掉，不然旧门板会留在原地
                            tile.removeDoor(false, "换了朝向（旧门板按旧朝向长，留着就成孤儿了）");
                            world.setBlockState(message.pos, state.withProperty(BlockBlastDoor.FACING, facing), 3);
                        }
                    }

                    tile.applyParams(message.width, message.height, message.retractUp, message.invert,
                            message.channel, message.interlock, message.cycle,
                            message.heavy, message.stageDelay);
                    // ★ 警报灯开关/颜色是"立刻生效"的参数，和门禁参数一样整体覆盖
                    tile.setAlarmConfig(message.alarmLights, message.alarmColor);

                    switch (message.action) {
                        case BUILD: {
                            String error = tile.build(player);
                            if (error != null) {
                                player.sendStatusMessage(new TextComponentString(error), true);
                            } else {
                                player.sendStatusMessage(new TextComponentString(tile.describe()), true);
                            }
                            break;
                        }
                        case REMOVE:
                            tile.removeDoor(false, "设置界面点了「拆除」");
                            player.sendStatusMessage(new TextComponentString("已拆除门板"), true);
                            break;
                        case OPEN:
                            // ★★★ 服务端这块唯一的人为开门入口也自己校验一次"手里是木斧吗"。
                            //   正常永远不会被拒 —— 设置界面本身就必须拿木斧才开得出来
                            //   （见 BlockBlastDoor#onBlockActivated），所以玩家点「开门」时
                            //   手上一定还是那把斧子。它挡的是"绕过界面直接发包"。
                            //   判据复用 WandHandler.isWand（全模组唯一一把"手持木斧"的尺子）。
                            if (!WandHandler.isWand(player)) {
                                RsAudio.warn("[防核门] 玩家 %s 没拿木斧就想开门 @ %s —— 已拒绝"
                                                + "（本地开关门只能用木斧；红石 / 气闸 / 远程面板不受此限）",
                                        player.getName(), message.pos);
                                break;
                            }
                            tile.requestOpen(true, player);
                            break;
                        case CLOSE:
                            tile.requestOpen(false, player);
                            break;
                        case TOGGLE_LOCK:
                            tile.toggleLatch(player);
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

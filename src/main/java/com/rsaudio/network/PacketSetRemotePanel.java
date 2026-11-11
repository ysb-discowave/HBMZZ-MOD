package com.rsaudio.network;

import com.rsaudio.panel.PanelCore;
import com.rsaudio.tile.TileRemotePanel;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：远程控制面板的设置（模式 + 频道）。
 *
 * <h3>为什么"按按钮"不走这个包</h3>
 * 面板上的 6 个按钮是<b>方块面上的实体按钮</b>，玩家那一击本来就带着命中坐标、
 * 由原版 {@code onBlockActivated} 直接送到了服务端（{@code hitX/hitY/hitZ}）。
 * 服务端当场就能算出"按的是哪一个"，没有任何需要客户端代劳的东西。
 * <p>
 * 要是在这里再加一个"按下第 N 个按钮"的动作码，风险是<b>多出一条可以伪造的路径</b>：
 * 改个包就能在任意距离对任意面板按下任意按钮，而面板的合法性判定（朝向、
 * 是不是按在按钮上）本来在服务端只此一份。少一个入口就少一份要守的门。
 * <p>
 * 所以这个包只管界面里那两样<b>配置</b>：模式和频道。
 *
 * <h3>距离卡 8 格</h3>
 * 和气闸控制器同一个数：界面是靠潜行右键面前的方框打开的，人必然就在旁边。
 * 卡紧一点顺手堵掉"隔着半张地图改别人面板"这种事。
 */
public class PacketSetRemotePanel implements IMessage {

    /** 操作距离（格），和气闸控制器保持一致 */
    public static final double MAX_DISTANCE = 8.0D;

    private BlockPos pos = BlockPos.ORIGIN;
    private int mode;
    private int channel;

    public PacketSetRemotePanel() {
    }

    public PacketSetRemotePanel(BlockPos pos, int mode, int channel) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        this.mode = mode;
        this.channel = channel;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        this.mode = buf.readByte();
        this.channel = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        buf.writeByte(mode);
        buf.writeInt(channel);
    }

    public static class Handler implements IMessageHandler<PacketSetRemotePanel, IMessage> {

        @Override
        public IMessage onMessage(final PacketSetRemotePanel message, MessageContext ctx) {
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
                    if (!(te instanceof TileRemotePanel)) {
                        return;
                    }
                    // ★ 越界的模式和频道由方块实体那边夹回来（PanelCore.clampMode/clampChannel），
                    //   不在这里拦 —— 夹取的规则只能有一处，就在这里拦住的话，
                    //   将来内核放宽范围时会出现"界面能用、包被丢"的错位
                    ((TileRemotePanel) te).applyParams(
                            PanelCore.clampMode(message.mode), PanelCore.clampChannel(message.channel));
                }
            });
            return null;
        }
    }
}

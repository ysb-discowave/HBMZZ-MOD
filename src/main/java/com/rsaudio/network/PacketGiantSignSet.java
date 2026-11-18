package com.rsaudio.network;

import com.rsaudio.block.BlockGiantSign;
import com.rsaudio.tile.TileGiantSign;
import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 客户端 -> 服务端：巨大标志牌的内容（三行字 + 字色 + 照明）。
 *
 * <h3>为什么只有一个方向</h3>
 * 和 {@code PacketSetCamera} 同样的理由：写完之后服务端方块实体走
 * {@code getUpdateTag} 把值同步回所有客户端，所以不需要再写一个专门的下行包。
 *
 * <h3>★ 字符串一律走 {@link ByteBufUtils#writeUTF8String}</h3>
 * 玩家会写中文。中文在 UTF-8 下一个字 3 字节，而 {@code String.length()} 是
 * <b>char 数</b> —— 自己写长度前缀（"先写 length 再写 bytes"）时最经典的错
 * 就是拿字符数当字节数，表现是拆包/粘包后整个连接被踢。让别人管这件事。
 *
 * <h3>★ 服务端只认"哪一块牌子"，不认"谁写的"</h3>
 * 这里不记 owner：牌子的语义是"公共标识"（设施里的告示），
 * 谁都能改。要是做了所有权，队友之间互相改不动反而更别扭。
 * 但距离校验照旧 —— 那是防"改到几十格外的别人的牌子"，和所有权无关。
 */
public class PacketGiantSignSet implements IMessage {

    private BlockPos pos = BlockPos.ORIGIN;
    private String[] lines = new String[]{ "", "", "" };
    private int color;
    private boolean lit;

    public PacketGiantSignSet() {
    }

    public PacketGiantSignSet(BlockPos pos, String[] lines, int color, boolean lit) {
        this.pos = pos == null ? BlockPos.ORIGIN : pos;
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            this.lines[i] = lines != null && i < lines.length && lines[i] != null ? lines[i] : "";
        }
        this.color = color;
        this.lit = lit;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = BlockPos.fromLong(buf.readLong());
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            this.lines[i] = ByteBufUtils.readUTF8String(buf);
        }
        this.color = buf.readInt();
        this.lit = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos.toLong());
        for (int i = 0; i < TileGiantSign.LINE_COUNT; i++) {
            ByteBufUtils.writeUTF8String(buf, lines[i] == null ? "" : lines[i]);
        }
        buf.writeInt(color);
        buf.writeBoolean(lit);
    }

    public static class Handler implements IMessageHandler<PacketGiantSignSet, IMessage> {

        @Override
        public IMessage onMessage(final PacketGiantSignSet message, MessageContext ctx) {
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
                    if (!(te instanceof TileGiantSign)) {
                        return;
                    }
                    TileGiantSign tile = (TileGiantSign) te;
                    // ★ setContent 返回"内容真的变了没有"。变了才动方块状态：
                    //   只是切照明时不希望三行文本也被当成"改动过"再走一遍同步。
                    tile.setContent(message.lines, message.color);
                    IBlockState state = world.getBlockState(message.pos);
                    if (state.getBlock() instanceof BlockGiantSign
                            && state.getValue(BlockGiantSign.LIT).booleanValue() != message.lit) {
                        world.setBlockState(message.pos,
                                state.withProperty(BlockGiantSign.LIT, Boolean.valueOf(message.lit)), 3);
                    }
                    // 内容和状态都没变时也要保证照明状态最终一致（上面那条 if 已经保证）
                    tile.markDirty();
                }
            });
            return null;
        }
    }
}

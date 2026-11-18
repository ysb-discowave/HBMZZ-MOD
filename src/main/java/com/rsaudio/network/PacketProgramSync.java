package com.rsaudio.network;

import com.rsaudio.redstone.asm.Cpu;
import com.rsaudio.tile.TileRedstoneComputer;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * 服务端 -> 客户端：红石计算机的完整状态（程序文本 + 运行状态 + 调试数据）。
 * <p>
 * 分两种触发：界面刚打开（推一次全量），以及界面开着时每 5 tick 推一次
 * （pc / 寄存器 / 六个面的输出，用来画那个实时面板）。
 */
public class PacketProgramSync implements IMessage {

    private long pos;
    private String source = "";
    private boolean running;
    private int errorLine;
    private String error = "";
    private int pc;
    private int[] regs = new int[Cpu.REG_COUNT];
    private int[] outputs = new int[Cpu.FACE_COUNT];
    private int ticks;
    private String message = "";

    public PacketProgramSync() {
    }

    public PacketProgramSync(TileRedstoneComputer tile) {
        this.pos = tile.getPos().toLong();
        this.source = tile.getSource() == null ? "" : tile.getSource();
        this.running = tile.isRunning();
        this.errorLine = tile.getErrorLine();
        this.error = tile.getError() == null ? "" : tile.getError();
        Cpu cpu = tile.getCpu();
        this.pc = cpu.getPc();
        for (int i = 0; i < Cpu.REG_COUNT; i++) {
            this.regs[i] = cpu.getRegister(i);
        }
        for (int i = 0; i < Cpu.FACE_COUNT; i++) {
            this.outputs[i] = cpu.getOutput(i);
        }
        this.ticks = cpu.getProgramTicks();
    }

    public BlockPos getPos() {
        return BlockPos.fromLong(pos);
    }

    public String getSource() {
        return source;
    }

    public boolean isRunning() {
        return running;
    }

    public int getErrorLine() {
        return errorLine;
    }

    public String getError() {
        return error;
    }

    public int getPc() {
        return pc;
    }

    public int getReg(int index) {
        return index >= 0 && index < regs.length ? regs[index] : 0;
    }

    public int getOutput(int face) {
        return face >= 0 && face < outputs.length ? outputs[face] : 0;
    }

    public int getTicks() {
        return ticks;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.pos = buf.readLong();
        this.source = ByteBufUtils.readUTF8String(buf);
        this.running = buf.readBoolean();
        this.errorLine = buf.readInt();
        this.error = ByteBufUtils.readUTF8String(buf);
        this.pc = buf.readInt();
        this.regs = new int[Cpu.REG_COUNT];
        for (int i = 0; i < Cpu.REG_COUNT; i++) {
            this.regs[i] = buf.readInt();
        }
        this.outputs = new int[Cpu.FACE_COUNT];
        for (int i = 0; i < Cpu.FACE_COUNT; i++) {
            this.outputs[i] = buf.readInt();
        }
        this.ticks = buf.readInt();
        this.message = ByteBufUtils.readUTF8String(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeLong(pos);
        ByteBufUtils.writeUTF8String(buf, source);
        buf.writeBoolean(running);
        buf.writeInt(errorLine);
        ByteBufUtils.writeUTF8String(buf, error);
        buf.writeInt(pc);
        for (int i = 0; i < Cpu.REG_COUNT; i++) {
            buf.writeInt(i < regs.length ? regs[i] : 0);
        }
        for (int i = 0; i < Cpu.FACE_COUNT; i++) {
            buf.writeInt(i < outputs.length ? outputs[i] : 0);
        }
        buf.writeInt(ticks);
        ByteBufUtils.writeUTF8String(buf, message);
    }

    public static class Handler implements IMessageHandler<PacketProgramSync, IMessage> {

        @Override
        public IMessage onMessage(final PacketProgramSync message, MessageContext ctx) {
            Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                @Override
                public void run() {
                    com.rsaudio.client.ClientComputerState.apply(message);
                }
            });
            return null;
        }
    }
}
